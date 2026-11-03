package com.branchdiff.conflict;

import com.branchdiff.conflict.ConflictModel.Member;
import com.branchdiff.conflict.ConflictModel.Range;
import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.MethodInfo;
import com.branchdiff.model.Snapshot;
import com.branchdiff.util.Ast;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The members of one version of a file, with line ranges: Java types, methods, constructors, fields, enum constants
 * and the import block; MyBatis statements; pom dependencies and properties; YAML / properties keys. Ids use the
 * context-node key format of the report ({@code M:a.B#m(String)}, {@code F:a.B.x}, {@code T:a.B},
 * {@code SQL:ns#id}, {@code CFG:key}, {@code MVN:group:artifact}) so members link to dependency / impact trees.
 */
public final class Members {
    /** a member with the normalized text used for rename / move detection */
    public record M(Member member, String name, String owner, String body) {
        public String id() {
            return member.id();
        }

        public String kind() {
            return member.kind();
        }
    }

    private Members() {
    }

    public static List<M> of(String path, String content) {
        if (content == null) return List.of();
        String p = path.toLowerCase();
        try {
            if (p.endsWith(".java")) return java(path, content);
            if (p.endsWith("pom.xml")) return pom(path, content);
            if (p.endsWith(".xml")) return xml(path, content);
            if (p.endsWith(".yml") || p.endsWith(".yaml")) return yaml(path, content);
            if (p.endsWith(".properties")) return properties(path, content);
        } catch (RuntimeException | StackOverflowError e) {
            return List.of();
        }
        return List.of();
    }

    // ------------------------------------------------------------------ java

    static List<M> java(String path, String content) {
        Snapshot s = new Snapshot("file", "", "", "", Map.of(path, content));
        CodeIndex idx = CodeIndex.build(s, null, true);
        List<M> res = new ArrayList<>();
        CompilationUnit cu = idx.unit(path);
        if (cu == null) return res;
        if (!cu.getImports().isEmpty()) {
            int a = Integer.MAX_VALUE, b = 0;
            StringBuilder body = new StringBuilder();
            for (ImportDeclaration i : cu.getImports()) {
                a = Math.min(a, Ast.line(i));
                b = Math.max(b, Ast.endLine(i));
                body.append(i.getNameAsString()).append(i.isAsterisk() ? ".*" : "").append('\n');
            }
            res.add(new M(new Member("imports", "IMPORTS:" + path, "import section", path, a, b), "imports", "", body.toString()));
        }
        for (ClassInfo c : idx.classes.values()) {
            res.add(new M(new Member("class", "T:" + c.fqn, c.simpleName, path, start(c.node()), Ast.endLine(c.node())),
                    c.simpleName, c.outerFqn == null ? c.pkg : c.outerFqn, c.normalizedHeader()));
            for (MethodInfo m : c.methods.values()) {
                if (m.node() == null) continue;
                String label = c.simpleName + "#" + (m.constructor ? c.simpleName : m.name) + "(" + String.join(",", m.params) + ")";
                res.add(new M(new Member(m.constructor ? "constructor" : "method", "M:" + m.id(), label, path, start(m.node()),
                        Ast.endLine(m.node())), m.name, c.fqn, m.bodyPrint.length() == 0 ? m.normalizedDecl() : m.normalizedBody()));
            }
            for (FieldDeclaration fd : c.node().getFields()) {
                for (VariableDeclarator v : fd.getVariables()) {
                    boolean constant = fd.isStatic() && fd.isFinal() || c.isInterface();
                    res.add(new M(new Member(constant ? "constant" : "field", "F:" + c.fqn + "." + v.getNameAsString(),
                            c.simpleName + "." + v.getNameAsString(), path, start(fd), Ast.endLine(fd)), v.getNameAsString(), c.fqn,
                            v.getType().asString() + " = " + v.getInitializer().map(Ast::normalized).orElse("")));
                }
            }
            if (c.node() instanceof EnumDeclaration en) {
                for (EnumConstantDeclaration ec : en.getEntries()) {
                    res.add(new M(new Member("constant", "F:" + c.fqn + "." + ec.getNameAsString(), c.simpleName + "." + ec.getNameAsString(),
                            path, start(ec), Ast.endLine(ec)), ec.getNameAsString(), c.fqn, Ast.normalized(ec)));
                }
            }
        }
        return res;
    }

    /** first line of a declaration including its javadoc / leading comment */
    static int start(Node n) {
        int s = Ast.line(n);
        if (n.getComment().isPresent()) s = Math.min(s, Ast.line(n.getComment().get()));
        return s;
    }

    // ------------------------------------------------------------------ xml

    private static final Pattern NAMESPACE = Pattern.compile("<mapper\\b[^>]*\\bnamespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern STATEMENT = Pattern.compile("<(select|insert|update|delete|sql|resultMap)\\b[^>]*?\\bid\\s*=\\s*\"([^\"]+)\"");

    static List<M> xml(String path, String content) {
        List<M> res = new ArrayList<>();
        Matcher ns = NAMESPACE.matcher(content);
        if (!ns.find()) return res;
        String namespace = ns.group(1);
        Matcher m = STATEMENT.matcher(content);
        while (m.find()) {
            String tag = m.group(1), id = m.group(2);
            int startLine = lineAt(content, m.start());
            int gt = content.indexOf('>', m.end());
            int endOff;
            if (gt > 0 && content.charAt(gt - 1) == '/') {
                endOff = gt;
            } else {
                int close = content.indexOf("</" + tag + ">", m.end());
                endOff = close < 0 ? content.length() : close;
            }
            int endLine = lineAt(content, endOff);
            String body = content.substring(m.start(), Math.min(content.length(), endOff)).replaceAll("\\s+", " ");
            String kind = tag.equals("resultMap") ? "resultMap" : tag.equals("sql") ? "sql-fragment" : "statement";
            res.add(new M(new Member(kind, "SQL:" + namespace + "#" + id, simple(namespace) + "." + id + " <" + tag + ">", path,
                    startLine, endLine), id, namespace, body));
        }
        return res;
    }

    private static final Pattern DEPENDENCY = Pattern.compile("<(dependency|plugin)>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern PROPERTIES = Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL);
    private static final Pattern PROPERTY = Pattern.compile("<([\\w.\\-]+)>([^<]*)</\\1>");

    static List<M> pom(String path, String content) {
        List<M> res = new ArrayList<>();
        Matcher d = DEPENDENCY.matcher(content);
        while (d.find()) {
            Matcher g = GROUP.matcher(d.group(2)), a = ARTIFACT.matcher(d.group(2));
            String grp = g.find() ? g.group(1) : "?", art = a.find() ? a.group(1) : "?";
            res.add(new M(new Member(d.group(1).equals("plugin") ? "maven-plugin" : "maven-dependency", "MVN:" + grp + ":" + art,
                    art, path, lineAt(content, d.start()), lineAt(content, d.end())), art, grp, d.group(2).replaceAll("\\s+", " ")));
        }
        Matcher ps = PROPERTIES.matcher(content);
        if (ps.find()) {
            int base = ps.start(1);
            Matcher p = PROPERTY.matcher(ps.group(1));
            while (p.find()) {
                int l = lineAt(content, base + p.start());
                res.add(new M(new Member("maven-property", "MVNPROP:" + p.group(1), "${" + p.group(1) + "}", path, l, l), p.group(1), "",
                        p.group(2).trim()));
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ config

    private static final Pattern YAML_KEY = Pattern.compile("^(\\s*)(?:-\\s+)?([\\w.\\-\\[\\]@$]+)\\s*:(?:\\s+(.*))?$");

    static List<M> yaml(String path, String content) {
        List<M> res = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        List<int[]> stack = new ArrayList<>(); // [indent, index into res]
        List<String> names = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.isBlank() || l.trim().startsWith("#")) continue;
            Matcher m = YAML_KEY.matcher(l);
            int indent = l.length() - l.stripLeading().length();
            while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) {
                close(res, stack.remove(stack.size() - 1)[1], i);
                names.remove(names.size() - 1);
            }
            if (l.trim().equals("---")) continue;
            if (!m.matches()) continue;
            names.add(m.group(2));
            String key = String.join(".", names);
            res.add(new M(new Member("config-key", "CFG:" + key, key, path, i + 1, i + 1), key, "",
                    m.group(3) == null ? "" : m.group(3).trim()));
            stack.add(new int[] {indent, res.size() - 1});
        }
        while (!stack.isEmpty()) close(res, stack.remove(stack.size() - 1)[1], lines.length);
        return res;
    }

    private static void close(List<M> res, int idx, int nextLine0) {
        M m = res.get(idx);
        // the block ends before the next line with the same or smaller indentation (trailing blank lines excluded)
        int end = Math.max(m.member().start(), nextLine0);
        res.set(idx, new M(new Member(m.kind(), m.id(), m.member().label(), m.member().path(), m.member().start(), end), m.name(),
                m.owner(), m.body()));
    }

    static List<M> properties(String path, String content) {
        List<M> res = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue;
            int eq = indexOfSep(l);
            if (eq <= 0) continue;
            String key = l.substring(0, eq).trim();
            res.add(new M(new Member("config-key", "CFG:" + key, key, path, i + 1, i + 1), key, "", l.substring(eq + 1).trim()));
        }
        return res;
    }

    private static int indexOfSep(String l) {
        for (int i = 0; i < l.length(); i++) if (l.charAt(i) == '=' || l.charAt(i) == ':') return i;
        return -1;
    }

    static int lineAt(String s, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < s.length(); i++) if (s.charAt(i) == '\n') line++;
        return line;
    }

    private static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    // ------------------------------------------------------------------ locating

    /**
     * The members a line range touches: the innermost members overlapping it (several when the range spans more than
     * one), otherwise the innermost member containing it (e.g. the class for a blank line between methods). An empty
     * range is an insertion point; it is attributed to the member it falls in or, between members, to the enclosing
     * type and the member that follows.
     */
    public static List<Member> locate(List<M> members, Range r) {
        int s = r.start(), e = r.empty() ? r.start() : r.end();
        List<M> overlapping = new ArrayList<>();
        for (M m : members) {
            if (m.kind().equals("class")) continue;
            int ms = m.member().start(), me = m.member().end();
            boolean hit = r.empty() ? (s > ms && s <= me) : (s <= me && e >= ms);
            if (hit) overlapping.add(m);
        }
        // drop members that contain another overlapping one (e.g. a yaml parent key)
        List<M> inner = new ArrayList<>();
        for (M m : overlapping) {
            boolean containsOther = false;
            for (M o : overlapping) {
                if (o != m && o.member().start() >= m.member().start() && o.member().end() <= m.member().end()
                        && (o.member().end() - o.member().start()) < (m.member().end() - m.member().start())) containsOther = true;
            }
            if (!containsOther) inner.add(m);
        }
        List<Member> res = new ArrayList<>();
        inner.sort(Comparator.comparingInt(m -> m.member().start()));
        for (M m : inner) res.add(m.member());
        if (!res.isEmpty()) return res;
        M cls = null;
        for (M m : members) {
            if (!m.kind().equals("class")) continue;
            if (m.member().start() <= s && m.member().end() >= e
                    && (cls == null || m.member().end() - m.member().start() < cls.member().end() - cls.member().start())) cls = m;
        }
        if (cls != null) res.add(cls.member());
        if (r.empty()) {
            M next = null;
            for (M m : members) {
                if (m.kind().equals("class") || m.member().start() < s) continue;
                if (next == null || m.member().start() < next.member().start()) next = m;
            }
            if (next != null && (cls == null || next.member().end() <= cls.member().end())) res.add(next.member());
        }
        return res;
    }

    public static Map<String, M> byId(List<M> ms) {
        Map<String, M> res = new LinkedHashMap<>();
        for (M m : ms) res.putIfAbsent(m.id(), m);
        return res;
    }
}
