package com.branchdiff.conflict;

import com.branchdiff.conflict.ConflictModel.EntryPoint;
import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.MethodInfo;
import com.branchdiff.util.Ast;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry points of one version (Spring MVC handler methods with HTTP method and path, {@code @Scheduled} jobs,
 * event / message listeners, startup runners) and caller tracing from a symbol up to them over the reverse usage
 * edges, following interface / superclass dispatch (a call to {@code ISysRoleService#updateRole} reaches
 * {@code SysRoleServiceImpl#updateRole}).
 */
public final class EntryPoints {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_RESULTS = 25;
    private static final Map<String, String> HTTP = Map.of("GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "");
    private static final Set<String> LISTENERS = Set.of("EventListener", "TransactionalEventListener", "KafkaListener",
            "RabbitListener", "JmsListener", "StreamListener", "RocketMQMessageListener", "SqsListener");

    /** who uses a node: [user node key, "path:line"] */
    public interface Users {
        List<String[]> of(String key);
    }

    private final CodeIndex idx;
    private final Users users;

    public EntryPoints(CodeIndex idx, Users users) {
        this.idx = idx;
        this.users = users;
    }

    /** the entry point a node key ("M:...") is, or null */
    public EntryPoint describe(String key) {
        if (!key.startsWith("M:")) return null;
        MethodInfo m = idx.methodsById.get(key.substring(2));
        if (m == null || m.node() == null) return null;
        ClassInfo c = m.owner;
        EntryPoint e = null;
        boolean controller = c.hasAnnotation("RestController", "Controller") || c.hasAnnotation("RequestMapping");
        for (AnnotationExpr a : m.node().getAnnotations()) {
            String n = simple(a.getNameAsString());
            if (controller && HTTP.containsKey(n)) {
                String verb = HTTP.get(n);
                if (verb.isEmpty()) verb = requestMethods(a);
                String path = join(classPath(c), first(paths(a)));
                e = new EntryPoint();
                e.type = "HTTP";
                e.route = verb + " " + path;
            } else if (n.equals("Scheduled")) {
                e = new EntryPoint();
                e.type = "SCHEDULED";
                List<String> parts = new ArrayList<>();
                for (String attr : List.of("cron", "fixedRate", "fixedDelay", "fixedRateString", "fixedDelayString")) {
                    Ast.attr(a, attr).ifPresent(v -> parts.add(attr + "=" + v));
                }
                e.route = "@Scheduled(" + String.join(", ", parts) + ")";
            } else if (LISTENERS.contains(n)) {
                e = new EntryPoint();
                e.type = "LISTENER";
                e.route = "@" + n + (a.isSingleMemberAnnotationExpr() || a.isNormalAnnotationExpr()
                        ? a.toString().substring(a.toString().indexOf('(')) : "");
            }
            if (e != null) break;
        }
        if (e == null && (m.name.equals("run") && implementsAny(c, "CommandLineRunner", "ApplicationRunner")
                || m.name.equals("onApplicationEvent") && implementsAny(c, "ApplicationListener"))) {
            e = new EntryPoint();
            e.type = m.name.equals("run") ? "STARTUP" : "LISTENER";
            e.route = c.simpleName + "#" + m.name;
        }
        if (e == null && m.name.equals("main") && m.isStatic && m.params.equals(List.of("String[]"))) {
            e = new EntryPoint();
            e.type = "MAIN";
            e.route = c.simpleName + ".main";
        }
        if (e == null) return null;
        EntryPoint res = e;
        res.node = key;
        res.location = m.location();
        Ast.annotation(m.node(), "PreAuthorize").flatMap(a -> Ast.attr(a, "value")).ifPresent(v -> res.permission = literal(v));
        return res;
    }

    /** entry points that (transitively, up to {@link #MAX_DEPTH} callers) reach one of {@code starts} */
    public List<EntryPoint> trace(List<String> starts) {
        List<EntryPoint> res = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<List<String>> queue = new ArrayDeque<>();
        for (String s : starts) {
            if (seen.add(s)) queue.add(List.of(s));
        }
        while (!queue.isEmpty() && res.size() < MAX_RESULTS) {
            List<String> path = queue.poll();
            String key = path.get(path.size() - 1);
            EntryPoint e = describe(key);
            if (e != null) {
                List<String> p = new ArrayList<>(path);
                java.util.Collections.reverse(p);
                e.path = p;
                res.add(e);
                continue;
            }
            if (path.size() > MAX_DEPTH) continue;
            for (String k : dispatchKeys(key)) {
                for (String[] u : users.of(k)) {
                    String from = u[0];
                    if (from == null || !seen.add(from)) continue;
                    List<String> np = new ArrayList<>(path);
                    np.add(from);
                    queue.add(np);
                }
            }
        }
        return res;
    }

    /** the key itself plus the same method declared in supertypes (calls through an interface reach the impl) */
    List<String> dispatchKeys(String key) {
        List<String> res = new ArrayList<>();
        res.add(key);
        if (!key.startsWith("M:")) return res;
        MethodInfo m = idx.methodsById.get(key.substring(2));
        if (m == null) return res;
        for (String anc : idx.ancestors(m.owner.fqn)) {
            if (anc.equals(m.owner.fqn)) continue;
            ClassInfo a = idx.classes.get(anc);
            if (a != null && a.methods.containsKey(m.key())) res.add("M:" + anc + "#" + m.key());
        }
        return res;
    }

    private boolean implementsAny(ClassInfo c, String... names) {
        for (String raw : c.superRaw) for (String n : names) if (simple(raw.replaceAll("<.*", "")).equals(n)) return true;
        return false;
    }

    private static String classPath(ClassInfo c) {
        AnnotationExpr a = c.annotations.get("RequestMapping");
        return a == null ? "" : first(paths(a));
    }

    private static List<String> paths(AnnotationExpr a) {
        List<String> v = Ast.stringValues(a, "value", "path");
        if (v.isEmpty() && a.isMarkerAnnotationExpr()) return List.of("");
        return v;
    }

    private static String requestMethods(AnnotationExpr a) {
        return Ast.attr(a, "method").map(e -> {
            Set<String> ms = new java.util.LinkedHashSet<>();
            for (Expression x : e.findAll(Expression.class)) {
                if (x.isFieldAccessExpr()) ms.add(x.asFieldAccessExpr().getNameAsString());
                else if (x.isNameExpr() && !x.getParentNode().map(p -> p instanceof com.github.javaparser.ast.expr.FieldAccessExpr).orElse(false)) {
                    ms.add(x.asNameExpr().getNameAsString());
                }
            }
            return ms.isEmpty() ? "ANY" : String.join("|", ms);
        }).orElse("ANY");
    }

    static String join(String a, String b) {
        String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
        if (!x.isEmpty() && !x.startsWith("/")) x = "/" + x;
        if (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        if (!y.isEmpty() && !y.startsWith("/")) y = "/" + y;
        String r = x + y;
        return r.isEmpty() ? "/" : r;
    }

    private static String first(List<String> l) {
        return l.isEmpty() ? "" : l.get(0);
    }

    private static String literal(Expression e) {
        return e.isStringLiteralExpr() ? e.asStringLiteralExpr().asString() : e.toString();
    }

    private static String simple(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }
}
