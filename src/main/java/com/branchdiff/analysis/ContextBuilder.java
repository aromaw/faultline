package com.branchdiff.analysis;

import com.branchdiff.analysis.DependencyAnalyzer.Dep;
import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.ConfigIndex;
import com.branchdiff.index.FieldInfo;
import com.branchdiff.index.MapperXmlIndex;
import com.branchdiff.index.MethodInfo;
import com.branchdiff.index.SpringModel.BeanDef;
import com.branchdiff.index.SpringModel.InjectionPoint;
import com.branchdiff.index.SpringModel.ValueRef;
import com.branchdiff.report.ContextModel;
import com.branchdiff.report.ContextModel.Chain;
import com.branchdiff.report.ContextModel.ChecklistItem;
import com.branchdiff.report.ContextModel.FindingContext;
import com.branchdiff.report.ContextModel.Loc;
import com.branchdiff.report.ContextModel.Node;
import com.branchdiff.report.ContextModel.TreeNode;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.util.Ast;
import com.branchdiff.util.Names;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the code-context view ({@link ContextModel}) of a finished analysis:
 * <ul>
 *   <li>dependency trees: from theirs' code that needs a finding's subject, through the subject, down to what the
 *   subject itself needs in theirs. Only nodes that ours lacks or that differ from theirs are expanded further,
 *   bounded by {@link #MAX_DEPTH} levels below the root, cycle-safe (a node already on the path is not expanded);</li>
 *   <li>reverse impact trees: who in ours (methods, field initializers, injection points, @Value references,
 *   mapper interface methods) depends on the subject, transitively up to {@link #MAX_IMPACT_DEPTH} levels;</li>
 *   <li>chains: findings whose trees share anchors (the subject or the code that needs it) are grouped, each chain
 *   gets its root(s) and a port checklist ordered dependencies first.</li>
 * </ul>
 * Node keys: {@code M:owner#sig}, {@code F:owner.name}, {@code T:fqn}, {@code CFG:key}, {@code SQL:ns#id},
 * {@code BEAN:type}, {@code LIB:fqn}, {@code MVN:group:artifact}.
 */
public final class ContextBuilder {
    public static final int MAX_DEPTH = 4;
    public static final int MAX_IMPACT_DEPTH = 3;
    static final int MAX_CHILDREN = 40;
    static final int MAX_ROOTS = 8;
    static final int MAX_TREE_NODES = 400;
    static final int MAX_NODES = 4000;
    private static final Set<String> MYBATIS_STATEMENT_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete",
            "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider");
    private static final Pattern MEMBER_BY = Pattern.compile("^([\\w.$]+)(#[^\\s\\[]+\\))?(\\.[\\w$]+)?");
    private static final Pattern SPRING_BY = Pattern.compile("^([\\w.$]+) \\((.*)\\)$");

    record Edge(String to, String at, boolean uncertain) {
    }

    private final CodeIndex base, ours, theirs;
    /** collects in theirs */
    private final DependencyAnalyzer theirsDeps;
    /** collects in ours (all three indexes = ours) */
    private final DependencyAnalyzer oursDeps;
    private final boolean librariesComparable;
    private final ContextModel model = new ContextModel();
    private final Map<String, List<Edge>> forward = new HashMap<>();
    /** key -> users in ours, filled on demand by {@link #usersOfKey} */
    private final Map<String, List<Edge>> reverse = new HashMap<>();
    private final Map<String, Boolean> libMissing = new HashMap<>();
    private final Map<String, String> mavenPom = new HashMap<>();
    private int treeBudget;
    private JarArtifacts theirsJars;
    private JarArtifacts oursJars;
    private boolean nodeLimitHit;

    /**
     * @param librariesComparable true when both ours' and theirs' Maven classpaths were resolved, so a library type
     *                            that theirs resolves and ours does not can be reported as missing from ours
     */
    public ContextBuilder(CodeIndex base, CodeIndex ours, CodeIndex theirs, boolean librariesComparable) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
        this.theirsDeps = new DependencyAnalyzer(base, ours, theirs);
        this.oursDeps = new DependencyAnalyzer(ours, ours, ours);
        this.librariesComparable = librariesComparable;
        model.maxDepth = MAX_DEPTH;
        model.maxImpactDepth = MAX_IMPACT_DEPTH;
    }

    // ------------------------------------------------------------------ entry point

    /** {@code findings} must carry their ids (1-based position in the report). */
    /** Lets library nodes point at the Maven artifact that provides them (needs both resolved classpaths). */
    public ContextBuilder withJars(JarArtifacts theirsJars, JarArtifacts oursJars) {
        this.theirsJars = theirsJars;
        this.oursJars = oursJars;
        return this;
    }

    public ContextModel build(List<Finding> findings) {
        for (Finding f : findings) {
            if (f.category == Category.MAVEN_DEPENDENCY && f.theirsLocation != null) mavenPom.put(f.symbol, f.theirsLocation);
        }
        Map<Integer, Set<String>> anchors = new LinkedHashMap<>();
        Map<Integer, Set<String>> portNodes = new LinkedHashMap<>();
        Map<Integer, List<String[]>> edges = new LinkedHashMap<>();
        for (Finding f : findings) {
            FindingContext fc = new FindingContext();
            String subject = subjectKey(f);
            fc.node = subject;
            node(subject).findings.add(f.id);
            Set<String> a = new LinkedHashSet<>();
            a.add(subject);
            List<String[]> e = new ArrayList<>();
            // dependency trees, rooted at theirs' code that needs the subject
            List<Need> needs = needers(f);
            int roots = 0;
            treeBudget = MAX_TREE_NODES;
            for (Need n : needs) {
                a.add(n.key);
                if (!n.theirsSide || roots >= MAX_ROOTS || n.key.equals(subject)) continue;
                roots++;
                TreeNode t = tree(n.key, null, false, 0, new HashSet<>(), true);
                if (!contains(t, subject)) {
                    // the analyzers saw a use the collector did not (Spring injection, name-based match, ...)
                    TreeNode s = tree(subject, n.at, false, 1, new HashSet<>(Set.of(n.key)), true);
                    t.children.add(0, s);
                }
                fc.dependencies.add(t);
            }
            if (fc.dependencies.isEmpty()) fc.dependencies.add(tree(subject, null, false, 0, new HashSet<>(), true));
            // reverse impact in ours
            fc.impact = impactTree(subject, null, false, 0, new HashSet<>());
            for (Need n : needs) {
                if (n.theirsSide || n.key.equals(subject) || contains(fc.impact, n.key)) continue;
                fc.impact.children.add(new TreeNode(n.key, n.at));
                node(n.key);
            }
            Set<String> p = new LinkedHashSet<>();
            for (TreeNode t : fc.dependencies) collectPort(t, p, e, true);
            for (TreeNode c : fc.impact.children) {
                e.add(new String[] {c.node, subject});
                if (a.contains(c.node)) p.add(c.node);
            }
            anchors.put(f.id, a);
            portNodes.put(f.id, p);
            edges.put(f.id, e);
            model.findings.put(f.id, fc);
        }
        chains(findings, anchors, portNodes, edges);
        limitations();
        return model;
    }

    // ------------------------------------------------------------------ public access for the conflict analysis

    public ContextModel model() {
        return model;
    }

    /** the node for {@code key} (created, with its status in ours / base / theirs, if new) */
    public Node nodeOf(String key) {
        return node(key);
    }

    /** forward dependency tree of a theirs symbol, depth {@link #MAX_DEPTH} (root always expanded) */
    public TreeNode dependencyTree(String key) {
        treeBudget = MAX_TREE_NODES;
        return tree(key, null, false, 0, new HashSet<>(), true);
    }

    /** reverse impact tree of a symbol in ours (users, up to {@link #MAX_IMPACT_DEPTH} levels) */
    public TreeNode impactTreeOf(String key) {
        return impactTree(key, null, false, 0, new HashSet<>());
    }

    /** direct users of {@code key} in ours: [user key, "path:line"] */
    public List<String[]> usersOf(String key) {
        List<String[]> res = new ArrayList<>();
        for (Edge e : usersOfKey(key)) res.add(new String[] {e.to, e.at});
        return res;
    }

    /** node key of a dependency collected in {@code idx} */
    public String keyOf(Dep d, CodeIndex idx) {
        return depKey(d, idx);
    }

    /** node key of a method given as "owner#sig" (resolving name-only signatures) */
    public String methodNodeKey(String ownerHashSig, CodeIndex idx) {
        return methodKey(ownerHashSig, idx);
    }

    private void limitations() {
        model.limitations.add("Dependency trees expand only symbols that ours lacks or that differ from theirs, at most "
                + MAX_DEPTH + " levels below the root; reverse impact goes up to " + MAX_IMPACT_DEPTH + " levels of callers.");
        model.limitations.add("Calls the symbol solver cannot resolve are matched by name and arity (marked uncertain); "
                + "calls through reflection, Spring AOP, XML or string-based wiring are not seen.");
        if (!librariesComparable) {
            model.limitations.add("Maven classpaths were not resolved for both sides, so library types missing from ours "
                    + "are not detected; Maven dependency findings are not linked into chains.");
        } else {
            model.limitations.add("Library types missing from ours are mapped to the artifact whose jar provides them on theirs' "
                    + "classpath (local repository layout); an artifact that theirs only gets transitively is shown as such.");
        }
        if (nodeLimitHit) model.limitations.add("The node limit (" + MAX_NODES + ") was reached; some trees are truncated.");
    }

    // ------------------------------------------------------------------ subjects and needers

    record Need(String key, String at, boolean theirsSide) {
    }

    String subjectKey(Finding f) {
        String s = f.symbol;
        switch (f.category) {
            case MAVEN_DEPENDENCY:
                return "MVN:" + s;
            case SPRING_CONFIG:
                return "CFG:" + s;
            case SPRING_BEAN:
                return "BEAN:" + s;
            case MYBATIS:
                return "SQL:" + s;
            case INTERFACE_IMPL: {
                int i = s.indexOf(" must implement ");
                return i < 0 ? "T:" + s : methodKey(s.substring(i + " must implement ".length()), theirs);
            }
            case CONSTANT_VALUE:
                return "F:" + s;
            default:
                break;
        }
        if (f.kind != null && f.kind.equals("TYPE")) return "T:" + s.split(" \\| ")[0];
        if (s.contains("#")) return methodKey(s.split(" \\| ")[0], f.category == Category.MISSING_DEPENDENCY ? theirs : ours);
        if (f.kind != null && (f.kind.contains("TYPE") || f.kind.contains("CLASS"))) return "T:" + s;
        return "F:" + s;
    }

    /** "owner#sig" to a node key, resolving name-only ({@code name(/2)}) and inherited members against {@code idx}. */
    String methodKey(String ownerHashSig, CodeIndex idx) {
        int h = ownerHashSig.indexOf('#');
        String owner = ownerHashSig.substring(0, h), sig = ownerHashSig.substring(h + 1);
        return "M:" + canonicalMethod(owner, sig, idx);
    }

    private String canonicalMethod(String owner, String sig, CodeIndex idx) {
        MethodInfo m = idx.findMethod(owner, sig);
        if (m == null && (sig.contains("(/") || !sig.contains("("))) {
            String name = sig.contains("(") ? sig.substring(0, sig.indexOf('(')) : sig;
            List<MethodInfo> ms = idx.findMethodsByName(owner, name);
            int arity = arityOf(sig);
            for (MethodInfo c : ms) if (arity < 0 || c.params.size() == arity) {
                m = c;
                break;
            }
        }
        return m != null ? m.id() : owner + "#" + sig;
    }

    private static int arityOf(String sig) {
        Matcher m = Pattern.compile("\\(/(\\d+)\\)").matcher(sig);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    List<Need> needers(Finding f) {
        List<Need> res = new ArrayList<>();
        for (Finding.Usage u : f.neededBy) {
            String by = u.by();
            boolean oursSide = by.contains("[in ours]");
            String key = null;
            if (f.category == Category.MYBATIS && by.endsWith("(theirs mapper method)")) {
                String sym = by.substring(0, by.indexOf(' '));
                key = methodKey(sym, theirs);
            } else if (f.category == Category.SPRING_BEAN || f.category == Category.SPRING_CONFIG) {
                Matcher m = SPRING_BY.matcher(by);
                if (m.matches()) key = memberKey(m.group(1), m.group(2), theirs);
            }
            if (key == null) {
                Matcher m = MEMBER_BY.matcher(by);
                if (!m.find()) continue;
                String owner = m.group(1);
                if (m.group(2) != null) key = methodKey(owner + m.group(2), oursSide ? ours : theirs);
                else if (m.group(3) != null) key = "F:" + owner + m.group(3);
                else {
                    Matcher p = Pattern.compile("\\(([\\w$<>]+\\([^)]*\\))\\)").matcher(by);
                    key = p.find() ? methodKey(owner + "#" + p.group(1), oursSide ? ours : theirs) : "T:" + owner;
                }
            }
            res.add(new Need(key, u.location(), !oursSide));
        }
        if (f.category == Category.INTERFACE_IMPL && f.symbol.contains(" must implement ")) {
            res.add(new Need("T:" + f.symbol.substring(0, f.symbol.indexOf(' ')), f.oursLocation, false));
        }
        return res;
    }

    /** Spring member descriptions ("field x", "constructor param p", "name() param p", "@Bean name() param p", "getX()") to a node key. */
    String memberKey(String owner, String member, CodeIndex idx) {
        String m = member.contains(",") ? member.substring(0, member.indexOf(',')).trim() : member.trim();
        if (m.startsWith("field ")) return fieldKey(owner, m.substring(6).trim(), idx);
        if (m.startsWith("constructor param")) {
            ClassInfo c = idx.classes.get(owner);
            if (c != null && !c.constructors().isEmpty()) return "M:" + c.constructors().get(0).id();
            return "T:" + owner;
        }
        Matcher mm = Pattern.compile("(?:@Bean )?([\\w$]+)\\(\\)").matcher(m);
        if (mm.find()) {
            List<MethodInfo> ms = idx.findMethodsByName(owner, mm.group(1));
            if (!ms.isEmpty()) return "M:" + ms.get(0).id();
        }
        return "T:" + owner;
    }

    private String fieldKey(String owner, String name, CodeIndex idx) {
        FieldInfo f = idx.findField(owner, name);
        return "F:" + (f != null ? f.owner.fqn : owner) + "." + name;
    }

    // ------------------------------------------------------------------ trees

    private static boolean contains(TreeNode t, String key) {
        if (t == null) return false;
        if (key.equals(t.node)) return true;
        for (TreeNode c : t.children) if (contains(c, key)) return true;
        return false;
    }

    TreeNode tree(String key, String at, boolean uncertain, int depth, Set<String> path, boolean force) {
        TreeNode t = new TreeNode(key, at);
        t.uncertain = uncertain;
        Node n = node(key);
        if (path.contains(key)) {
            t.cycle = true;
            return t;
        }
        if (!force && !n.needsPort()) return t;
        List<Edge> deps = forward(key);
        if (deps.isEmpty()) return t;
        if (depth >= MAX_DEPTH || treeBudget <= 0 || nodeLimitHit) {
            t.truncated = true;
            return t;
        }
        path.add(key);
        List<Edge> sorted = new ArrayList<>(deps);
        sorted.sort(Comparator.comparingInt(e -> rank(node(e.to).ours)));
        int i = 0;
        for (Edge e : sorted) {
            if (i++ >= MAX_CHILDREN) {
                t.omitted = sorted.size() - MAX_CHILDREN;
                break;
            }
            treeBudget--;
            t.children.add(tree(e.to, e.at, e.uncertain, depth + 1, path, false));
        }
        path.remove(key);
        return t;
    }

    TreeNode impactTree(String key, String at, boolean uncertain, int depth, Set<String> path) {
        TreeNode t = new TreeNode(key, at);
        t.uncertain = uncertain;
        node(key);
        if (path.contains(key)) {
            t.cycle = true;
            return t;
        }
        List<Edge> users = usersOfKey(key);
        if (users.isEmpty()) return t;
        if (depth >= MAX_IMPACT_DEPTH || nodeLimitHit) {
            t.truncated = true;
            return t;
        }
        path.add(key);
        int i = 0;
        for (Edge e : users) {
            if (i++ >= MAX_CHILDREN) {
                t.omitted = users.size() - MAX_CHILDREN;
                break;
            }
            t.children.add(impactTree(e.to, e.at, e.uncertain, depth + 1, path));
        }
        path.remove(key);
        return t;
    }

    private static int rank(String status) {
        return switch (status) {
            case ContextModel.MISSING -> 0;
            case ContextModel.CHANGED -> 1;
            case ContextModel.DIFFERS -> 2;
            case ContextModel.UNKNOWN -> 3;
            default -> 4;
        };
    }

    /** Port-relevant nodes of a dependency tree and the dependency edges (parent depends on child) between them. */
    private void collectPort(TreeNode t, Set<String> out, List<String[]> edges, boolean root) {
        Node n = model.nodes.get(t.node);
        if (root || n.needsPort()) out.add(t.node);
        for (TreeNode c : t.children) {
            if (model.nodes.get(c.node).needsPort() || c.cycle) edges.add(new String[] {t.node, c.node});
            if (!c.cycle) collectPort(c, out, edges, false);
        }
    }

    // ------------------------------------------------------------------ forward edges (theirs)

    List<Edge> forward(String key) {
        List<Edge> cached = forward.get(key);
        if (cached != null) return cached;
        List<Edge> res = new ArrayList<>();
        forward.put(key, res);
        try {
            if (key.startsWith("M:")) forwardMethod(key, res);
            else if (key.startsWith("F:")) forwardField(key, res);
            else if (key.startsWith("T:")) forwardType(key.substring(2), res);
            else if (key.startsWith("BEAN:")) forwardBean(key.substring(5), res);
            else if (key.startsWith("LIB:")) forwardLibrary(key.substring(4), res);
        } catch (RuntimeException | StackOverflowError e) {
            node(key).note = append(node(key).note, "dependencies could not be collected: " + e.getClass().getSimpleName());
        }
        return res;
    }

    private void forwardMethod(String key, List<Edge> res) {
        MethodInfo m = method(key.substring(2), theirs);
        if (m == null || m.node == null) return;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<Dep, Integer> e : theirsDeps.collect(m).entrySet()) {
            String k = depKey(e.getKey(), theirs);
            if (k == null || k.equals(key) || !seen.add(k)) continue;
            res.add(new Edge(k, m.owner.file + ":" + e.getValue(), !e.getKey().certain()));
        }
        ClassInfo c = m.owner;
        if (theirs.isMyBatisMapper(c) && !m.isDefault && !m.isStatic) {
            boolean annotated = false;
            for (String a : m.annotations) if (MYBATIS_STATEMENT_ANNOTATIONS.contains(a)) annotated = true;
            if (!annotated && seen.add("SQL:" + c.fqn + "#" + m.name)) res.add(new Edge("SQL:" + c.fqn + "#" + m.name, m.location(), false));
        }
        for (ValueRef v : theirs.valueRefs) {
            if (v.ownerFqn().equals(c.fqn) && v.member().startsWith(m.name + "()") && seen.add("CFG:" + v.key())) {
                res.add(new Edge("CFG:" + v.key(), v.location(), false));
            }
        }
        libraries(m.node, c, m.owner.file, res, seen);
    }

    private void forwardField(String key, List<Edge> res) {
        String s = key.substring(2);
        int dot = s.lastIndexOf('.');
        String owner = s.substring(0, dot), name = s.substring(dot + 1);
        FieldInfo f = theirs.findField(owner, name);
        if (f == null) return;
        ClassInfo c = f.owner;
        Set<String> seen = new HashSet<>();
        FieldDeclaration fd = fieldDecl(c, name);
        if (fd != null) {
            for (Map.Entry<Dep, Integer> e : theirsDeps.collect(fd, c, null).entrySet()) {
                String k = depKey(e.getKey(), theirs);
                if (k == null || k.equals(key) || !seen.add(k)) continue;
                res.add(new Edge(k, c.file + ":" + e.getValue(), !e.getKey().certain()));
            }
            libraries(fd, c, c.file, res, seen);
        }
        for (InjectionPoint ip : theirs.injectionPoints) {
            if (ip.ownerFqn().equals(c.fqn) && ip.member().equals("field " + name) && ip.typeFqn() != null
                    && seen.add("BEAN:" + ip.typeFqn())) {
                res.add(new Edge("BEAN:" + ip.typeFqn(), ip.location(), false));
            }
        }
        for (ValueRef v : theirs.valueRefs) {
            if (v.ownerFqn().equals(c.fqn) && v.member().equals("field " + name) && seen.add("CFG:" + v.key())) {
                res.add(new Edge("CFG:" + v.key(), v.location(), false));
            }
        }
    }

    private void forwardType(String fqn, List<Edge> res) {
        ClassInfo c = theirs.classes.get(fqn);
        if (c == null) return;
        Set<String> seen = new HashSet<>();
        for (String s : c.supertypes) {
            if (theirs.isProjectType(s) && seen.add("T:" + s)) res.add(new Edge("T:" + s, c.location(), false));
        }
        for (Map.Entry<Dep, Integer> e : theirsDeps.collect(c.node, c, null).entrySet()) {
            Dep d = e.getKey();
            String owner = d.candidateOwners().isEmpty() ? d.owner() : d.candidateOwners().get(0);
            if (owner.equals(fqn) || owner.startsWith(fqn + ".")) continue; // own members arrive with the type
            String k = depKey(d, theirs);
            if (k == null || !seen.add(k)) continue;
            res.add(new Edge(k, c.file + ":" + e.getValue(), !d.certain()));
        }
        for (InjectionPoint ip : theirs.injectionPoints) {
            if (ip.ownerFqn().equals(fqn) && ip.typeFqn() != null && seen.add("BEAN:" + ip.typeFqn())) {
                res.add(new Edge("BEAN:" + ip.typeFqn(), ip.location(), false));
            }
        }
        for (ValueRef v : theirs.valueRefs) {
            if (v.ownerFqn().equals(fqn) && seen.add("CFG:" + v.key())) res.add(new Edge("CFG:" + v.key(), v.location(), false));
        }
        libraries(c.node, c, c.file, res, seen);
    }

    private void forwardBean(String type, List<Edge> res) {
        for (BeanDef b : theirs.beansOfType(type)) {
            ClassInfo provider = theirs.classes.get(b.typeFqn());
            if (!b.kind().equals("component") || provider == null) provider = classInFile(theirs, b.file());
            if (provider != null) {
                res.add(new Edge("T:" + provider.fqn, b.location(), false));
                return;
            }
        }
    }

    private static ClassInfo classInFile(CodeIndex idx, String file) {
        for (ClassInfo c : idx.classes.values()) if (c.file.equals(file) && c.outerFqn == null) return c;
        return null;
    }

    /** Library (non-project) types used by {@code n} that theirs' classpath has and ours' does not. */
    private void libraries(com.github.javaparser.ast.Node n, ClassInfo ctx, String file, List<Edge> res, Set<String> seen) {
        if (!librariesComparable || ours.solver == null || theirs.solver == null) return;
        for (ClassOrInterfaceType t : n.findAll(ClassOrInterfaceType.class)) {
            if (t.getParentNode().orElse(null) instanceof ClassOrInterfaceType) continue;
            String fqn = theirs.resolveType(t.getNameWithScope(), ctx);
            if (fqn == null || !fqn.contains(".") || theirs.isProjectType(fqn) || fqn.startsWith("java.")) continue;
            if (!libraryMissingInOurs(fqn)) continue;
            if (seen.add("LIB:" + fqn)) res.add(new Edge("LIB:" + fqn, file + ":" + Ast.line(t), false));
        }
    }

    /** A library type depends on the artifact whose jar provides it on theirs' classpath. */
    private void forwardLibrary(String fqn, List<Edge> res) {
        if (theirsJars == null) return;
        JarArtifacts.Gav g = theirsJars.artifactOf(fqn);
        if (g != null) res.add(new Edge("MVN:" + g.ga(), null, false));
    }

    private boolean libraryMissingInOurs(String fqn) {
        return libMissing.computeIfAbsent(fqn, k -> solves(theirs, k) && !solves(ours, k));
    }

    private static boolean solves(CodeIndex idx, String fqn) {
        try {
            return idx.solver.tryToSolveType(fqn).isSolved();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Node key of a collected dependency, normalized to the declaring type in {@code idx}. */
    String depKey(Dep d, CodeIndex idx) {
        String owner = d.candidateOwners().isEmpty() ? d.owner() : d.candidateOwners().get(0);
        switch (d.kind()) {
            case TYPE:
                return "T:" + owner;
            case FIELD:
            case CONSTANT:
                return fieldKey(owner, d.member(), idx);
            case METHOD:
            case CONSTRUCTOR:
                return "M:" + canonicalMethod(owner, d.member(), idx);
            case METHOD_REF: {
                List<MethodInfo> ms = idx.findMethodsByName(owner, d.member());
                return ms.isEmpty() ? "M:" + owner + "#" + d.member() + "(..)" : "M:" + ms.get(0).id();
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ reverse edges (ours)

    /**
     * Users of {@code key} in ours (reverse edges), computed on demand. Symbol resolution is the expensive part, so
     * only the ours members whose AST mentions the key's simple name (as a name or identifier) are resolved, and each
     * member is resolved at most once. Every dependency {@link DependencyAnalyzer#collect} reports is derived from a
     * name written in the member (call name, type name, field name, method reference), so this prefilter returns the
     * same edges, in the same order, as resolving every member of the project.
     */
    List<Edge> usersOfKey(String key) {
        List<Edge> cached = reverse.get(key);
        if (cached != null) return cached;
        if (revEntries == null) indexReverseEntries();
        List<Edge> l = new ArrayList<>();
        String name = simpleNameOfKey(key);
        if (name != null) {
            for (RevEntry en : revByName.getOrDefault(name, List.of())) {
                for (RevEdge r : revEdges(en)) if (r.key.equals(key)) addEdge(l, en.from, r.at, r.uncertain);
            }
        }
        for (Edge e : staticReverse().getOrDefault(key, List.of())) addEdge(l, e.to, e.at, e.uncertain);
        l.sort(Comparator.comparing(Edge::to));
        reverse.put(key, l);
        return l;
    }

    /** a member of ours whose dependencies may give reverse edges: a method, or a field with an initializer */
    private record RevEntry(ClassInfo owner, MethodInfo method, FieldDeclaration field, String from) {
    }

    private record RevEdge(String key, String at, boolean uncertain) {
    }

    private List<RevEntry> revEntries;
    private final Map<String, List<RevEntry>> revByName = new HashMap<>();
    private final Map<RevEntry, List<RevEdge>> revEdgeCache = new java.util.IdentityHashMap<>();
    private Map<String, List<Edge>> staticReverse;

    /** same iteration order as a full scan: per class, its methods then its initialized fields */
    private void indexReverseEntries() {
        revEntries = new ArrayList<>();
        for (ClassInfo c : ours.classes.values()) {
            if (!c.inScope) continue;
            for (MethodInfo m : c.methods.values()) {
                if (m.node == null) continue;
                index(new RevEntry(c, m, null, "M:" + m.id()), m.node);
            }
            for (FieldDeclaration fd : c.node.getFields()) {
                if (fd.getVariables().stream().noneMatch(v -> v.getInitializer().isPresent())) continue;
                index(new RevEntry(c, null, fd, "F:" + c.fqn + "." + fd.getVariable(0).getNameAsString()), fd);
            }
        }
    }

    private void index(RevEntry e, com.github.javaparser.ast.Node node) {
        revEntries.add(e);
        // every name a dependency can come from: simple names (calls, fields, variables, types), qualified names
        // (annotations, imports) and method reference identifiers
        Set<String> names = new HashSet<>();
        node.walk(n -> {
            if (n instanceof com.github.javaparser.ast.expr.SimpleName sn) names.add(sn.getIdentifier());
            else if (n instanceof com.github.javaparser.ast.expr.Name nm) names.add(nm.getIdentifier());
            else if (n instanceof com.github.javaparser.ast.expr.MethodReferenceExpr mr) names.add(mr.getIdentifier());
        });
        for (String n : names) revByName.computeIfAbsent(n, k -> new ArrayList<>()).add(e);
    }

    private List<RevEdge> revEdges(RevEntry en) {
        List<RevEdge> res = revEdgeCache.get(en);
        if (res != null) return res;
        res = new ArrayList<>();
        try {
            if (en.method != null) {
                for (Map.Entry<Dep, Integer> e : oursDeps.collect(en.method).entrySet()) {
                    String k = depKey(e.getKey(), ours);
                    if (k != null && !k.equals(en.from)) res.add(new RevEdge(k, en.owner.file + ":" + e.getValue(), !e.getKey().certain()));
                }
            } else {
                for (Map.Entry<Dep, Integer> e : oursDeps.collect(en.field, en.owner, null).entrySet()) {
                    String k = depKey(e.getKey(), ours);
                    if (k != null && !k.equals(en.from) && !k.startsWith("T:")) {
                        res.add(new RevEdge(k, en.owner.file + ":" + e.getValue(), !e.getKey().certain()));
                    }
                }
            }
        } catch (RuntimeException | StackOverflowError ignored) {
            // one unparsable member must not hide the others (the edges found before the failure are kept)
        }
        revEdgeCache.put(en, res);
        return res;
    }

    /** the simple name a member must mention to depend on {@code key}; null for keys no member dependency produces */
    static String simpleNameOfKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return null;
        String body = key.substring(colon + 1);
        switch (key.substring(0, colon)) {
            case "M": {
                int h = body.indexOf('#');
                if (h < 0) return null;
                String sig = body.substring(h + 1);
                String n = sig.contains("(") ? sig.substring(0, sig.indexOf('(')) : sig;
                return n.equals("<init>") ? lastSegment(body.substring(0, h)) : n;
            }
            case "F":
            case "T":
                return lastSegment(body);
            default:
                return null;
        }
    }

    private static String lastSegment(String s) {
        int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf('$'));
        return i < 0 ? s : s.substring(i + 1);
    }

    /** reverse edges that need no symbol resolution: config values, injections, mapper statements */
    private Map<String, List<Edge>> staticReverse() {
        if (staticReverse != null) return staticReverse;
        staticReverse = new HashMap<>();
        for (ValueRef v : ours.valueRefs) {
            if (ours.classes.containsKey(v.ownerFqn()) && ours.classes.get(v.ownerFqn()).inScope) {
                addRev("CFG:" + v.key(), memberKey(v.ownerFqn(), v.member(), ours), v.location(), false);
            }
        }
        for (InjectionPoint ip : ours.injectionPoints) {
            if (ip.typeFqn() != null) addRev("BEAN:" + ip.typeFqn(), memberKey(ip.ownerFqn(), ip.member(), ours), ip.location(), false);
        }
        for (String ns : ours.mapperXml.namespaces()) {
            for (MapperXmlIndex.Statement st : ours.mapperXml.statements(ns).values()) {
                for (MethodInfo m : ours.findMethodsByName(ns, st.id())) addRev("SQL:" + ns + "#" + st.id(), "M:" + m.id(), m.location(), false);
            }
        }
        return staticReverse;
    }

    private void addRev(String to, String from, String at, boolean uncertain) {
        addEdge(staticReverse.computeIfAbsent(to, k -> new ArrayList<>()), from, at, uncertain);
    }

    private static void addEdge(List<Edge> l, String from, String at, boolean uncertain) {
        for (Edge e : l) if (e.to.equals(from)) return;
        l.add(new Edge(from, at, uncertain));
    }

    // ------------------------------------------------------------------ chains and checklists

    private void chains(List<Finding> findings, Map<Integer, Set<String>> anchors, Map<Integer, Set<String>> port,
                        Map<Integer, List<String[]>> edges) {
        List<Integer> ids = new ArrayList<>(anchors.keySet());
        Map<Integer, Integer> parent = new HashMap<>();
        for (Integer id : ids) parent.put(id, id);
        // findings are linked when one's anchors (subject or needer) occur among another's anchors or among the
        // nodes it must bring over that ours lacks or has incompatibly; a dependency ours already has and that only
        // differs in content (DIFFERS) does not link otherwise unrelated work
        Map<String, List<Integer>> byAnchor = new HashMap<>();
        for (Integer id : ids) for (String a : anchors.get(id)) byAnchor.computeIfAbsent(a, k -> new ArrayList<>()).add(id);
        for (Integer id : ids) {
            Set<String> mine = new LinkedHashSet<>(anchors.get(id));
            for (String k : port.get(id)) {
                String st = node(k).ours;
                if (st.equals(ContextModel.MISSING) || st.equals(ContextModel.CHANGED)) mine.add(k);
            }
            for (String k : mine) for (Integer other : byAnchor.getOrDefault(k, List.of())) union(parent, id, other);
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (Integer id : ids) groups.computeIfAbsent(find(parent, id), k -> new ArrayList<>()).add(id);
        Map<Integer, Finding> byId = new HashMap<>();
        for (Finding f : findings) byId.put(f.id, f);
        List<List<Integer>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((List<Integer> g) -> -g.size()).thenComparingInt(g -> g.get(0)));
        int n = 1;
        for (List<Integer> g : ordered) {
            Chain c = new Chain();
            c.id = "C" + n++;
            c.findings.addAll(g);
            boolean portDir = false;
            Set<String> nodes = new LinkedHashSet<>();
            Map<String, Set<String>> deps = new LinkedHashMap<>();
            Set<String> subjects = new LinkedHashSet<>();
            Set<String> callers = new LinkedHashSet<>();
            for (Integer id : g) {
                for (TreeNode t : model.findings.get(id).impact.children) callers.add(t.node);
                Finding f = byId.get(id);
                if (isPort(f)) portDir = true;
                subjects.add(model.findings.get(id).node);
                nodes.addAll(anchors.get(id));
                nodes.addAll(port.get(id));
                for (String[] e : edges.get(id)) {
                    deps.computeIfAbsent(e[0], k -> new LinkedHashSet<>()).add(e[1]);
                    nodes.add(e[0]);
                    nodes.add(e[1]);
                }
                model.findings.get(id).chain = c.id;
            }
            c.direction = portDir ? "port" : "impact";
            Set<String> depended = new HashSet<>();
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) for (String t : e.getValue()) if (!t.equals(e.getKey())) depended.add(t);
            for (String k : nodes) if (!depended.contains(k)) c.roots.add(k);
            if (c.roots.isEmpty()) c.roots.add(nodes.iterator().next()); // pure cycle
            c.roots.sort(Comparator.comparingInt((String r) -> -reach(r, deps).size()));
            c.root = c.roots.get(0);
            c.nodes.addAll(nodes);
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) for (String t : e.getValue()) c.edges.add(List.of(e.getKey(), t));
            checklist(c, deps, subjects, callers);
            model.chains.add(c);
        }
    }

    private static boolean isPort(Finding f) {
        return switch (f.category) {
            case MISSING_DEPENDENCY, SPRING_BEAN, SPRING_CONFIG, INTERFACE_IMPL, MAVEN_DEPENDENCY -> true;
            case MYBATIS -> "MISSING".equals(f.status);
            default -> false;
        };
    }

    private static Set<String> reach(String from, Map<String, Set<String>> deps) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(from));
        while (!q.isEmpty()) for (String t : deps.getOrDefault(q.poll(), Set.of())) if (seen.add(t)) q.add(t);
        return seen;
    }

    /** Post-order from the roots: everything a node depends on comes before it; cycles are cut where they close. */
    private void checklist(Chain c, Map<String, Set<String>> deps, Set<String> subjects, Set<String> callers) {
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String r : c.roots) post(r, deps, new HashSet<>(), done, order);
        for (String k : c.nodes) post(k, deps, new HashSet<>(), done, order);
        Map<String, String> neededBy = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) for (String t : e.getValue()) neededBy.putIfAbsent(t, e.getKey());
        int step = 1;
        for (String k : order) {
            Node n = model.nodes.get(k);
            String action = action(n, c.direction, subjects.contains(k), callers);
            if (action == null) continue;
            String by = neededBy.get(k);
            String why = by == null ? (c.roots.contains(k) ? "chain root" : "") : "needed by " + model.nodes.get(by).label;
            if ("impact".equals(c.direction) && !subjects.contains(k) && by != null) why = "uses " + model.nodes.get(by).label;
            c.checklist.add(new ChecklistItem(step++, k, action, why));
        }
    }

    private static void post(String k, Map<String, Set<String>> deps, Set<String> path, Set<String> done, List<String> out) {
        if (done.contains(k) || !path.add(k)) return;
        for (String d : deps.getOrDefault(k, Set.of())) post(d, deps, path, done, out);
        path.remove(k);
        if (done.add(k)) out.add(k);
    }

    private String action(Node n, String direction, boolean subject, Set<String> callers) {
        if ("impact".equals(direction)) {
            if (subject) {
                String c = n.change.startsWith("theirs ") ? n.change.substring(7) : n.change;
                return switch (n.change) {
                    case "theirs removed" -> "Theirs removed this " + n.kind + "; ours still has and uses it: migrate the users or keep it";
                    case "theirs changed", "both changed" -> "Reconcile this " + n.kind + " (" + n.change + ")";
                    default -> "Review this " + n.kind + " (" + c + ")";
                };
            }
            if (!callers.contains(n.key)) return n.needsPort() ? portAction(n) : null;
            return "Update this " + n.kind + " in ours: it uses the changed symbol";
        }
        if (!n.needsPort() && !subject) return null;
        if (n.kind.equals(ContextModel.LIBRARY)) {
            for (Edge e : forward(n.key)) {
                if (e.to.startsWith("MVN:")) return "Comes with " + e.to.substring(4) + " (listed above); nothing else to add for this type";
            }
        }
        return portAction(n);
    }

    private static String portAction(Node n) {
        String what = n.kind;
        return switch (n.ours) {
            case ContextModel.MISSING -> switch (n.kind) {
                case ContextModel.CONFIG -> "Add config key to ours' application config";
                case ContextModel.MAPPER -> "Add the mapper XML statement";
                case ContextModel.BEAN -> "Provide a bean of this type";
                case ContextModel.LIBRARY -> "Add a Maven dependency that provides this type";
                case ContextModel.MAVEN -> n.loc.containsKey("theirs") ? "Add the dependency to ours' pom.xml"
                        : "Make sure ours gets this artifact (theirs gets it transitively)";
                case ContextModel.CLASS -> "Add the class (whole file)";
                default -> "Add " + what;
            };
            case ContextModel.CHANGED -> "Align the " + what + " signature/type with theirs";
            case ContextModel.DIFFERS -> "Port theirs' changes to this " + what;
            default -> "Review " + what + " (" + n.ours.toLowerCase() + " in ours)";
        };
    }

    private static int find(Map<Integer, Integer> p, int x) {
        while (p.get(x) != x) {
            p.put(x, p.get(p.get(x)));
            x = p.get(x);
        }
        return x;
    }

    private static void union(Map<Integer, Integer> p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p.put(Math.max(ra, rb), Math.min(ra, rb));
    }

    // ------------------------------------------------------------------ nodes

    /** One version's view of a node: present?, comparable content, range. */
    private record Side(boolean present, String content, Loc loc, String note) {
        static final Side ABSENT = new Side(false, null, null, null);
    }

    Node node(String key) {
        Node n = model.nodes.get(key);
        if (n != null) return n;
        n = new Node();
        n.key = key;
        if (model.nodes.size() >= MAX_NODES) nodeLimitHit = true;
        model.nodes.put(key, n);
        String body = key.substring(key.indexOf(':') + 1);
        n.symbol = body;
        Side b, o, t;
        switch (key.substring(0, key.indexOf(':'))) {
            case "M" -> {
                MethodInfo tm = method(body, theirs);
                n.kind = body.contains("#<init>(") ? ContextModel.CONSTRUCTOR : ContextModel.METHOD;
                n.label = Names.simpleName(body.substring(0, body.indexOf('#'))) + body.substring(body.indexOf('#'));
                if (n.kind.equals(ContextModel.CONSTRUCTOR)) n.label = n.label.replace("#<init>", "#new ");
                b = methodSide(body, base);
                o = methodSide(body, ours);
                t = methodSide(body, theirs);
                if (!o.present && t.present) {
                    // same name with another signature: incompatible rather than missing
                    MethodInfo alt = sameName(body, ours);
                    if (alt != null) {
                        o = new Side(false, null, range(alt, ours), "ours has " + alt.key() + " instead");
                        n.ours = ContextModel.CHANGED;
                    }
                } else if (o.present && t.present && tm != null) {
                    MethodInfo om = method(body, ours);
                    if (!om.returnType.equals(tm.returnType) || om.isStatic != tm.isStatic) {
                        n.ours = ContextModel.CHANGED;
                        n.note = "ours: " + (om.isStatic ? "static " : "") + om.returnType + ", theirs: " + (tm.isStatic ? "static " : "") + tm.returnType;
                    }
                }
            }
            case "F" -> {
                int dot = body.lastIndexOf('.');
                String owner = body.substring(0, dot), name = body.substring(dot + 1);
                FieldInfo tf = theirs.findField(owner, name), of = ours.findField(owner, name);
                FieldInfo any = tf != null ? tf : of;
                n.kind = any != null && any.isConstant() ? ContextModel.CONSTANT : ContextModel.FIELD;
                n.label = Names.simpleName(owner) + "." + name;
                b = fieldSide(owner, name, base);
                o = fieldSide(owner, name, ours);
                t = fieldSide(owner, name, theirs);
                if (tf != null && of != null && !tf.type.equals(of.type)) {
                    n.ours = ContextModel.CHANGED;
                    n.note = "ours: " + of.typeRaw + ", theirs: " + tf.typeRaw;
                }
            }
            case "T" -> {
                n.kind = ContextModel.CLASS;
                n.label = labelOfType(body);
                b = typeSide(body, base);
                o = typeSide(body, ours);
                t = typeSide(body, theirs);
                ClassInfo any = theirs.classes.getOrDefault(body, ours.classes.get(body));
                if (any != null && any.kind != ClassInfo.Kind.CLASS) n.note = any.kind.name().toLowerCase();
            }
            case "CFG" -> {
                n.kind = ContextModel.CONFIG;
                n.label = body;
                b = configSide(body, base);
                o = configSide(body, ours);
                t = configSide(body, theirs);
            }
            case "SQL" -> {
                n.kind = ContextModel.MAPPER;
                int h = body.indexOf('#');
                n.label = Names.simpleName(body.substring(0, Math.max(0, h))) + body.substring(h);
                b = sqlSide(body, base);
                o = sqlSide(body, ours);
                t = sqlSide(body, theirs);
            }
            case "BEAN" -> {
                n.kind = ContextModel.BEAN;
                n.label = "bean " + (body.contains(" ") ? body : Names.simpleName(body));
                b = beanSide(body, base);
                o = beanSide(body, ours);
                t = beanSide(body, theirs);
            }
            case "LIB" -> {
                n.kind = ContextModel.LIBRARY;
                n.label = Names.simpleName(body) + " (" + Names.packageOf(body) + ")";
                boolean missing = libMissing.getOrDefault(body, false);
                b = Side.ABSENT;
                t = new Side(true, null, null, null);
                o = missing ? Side.ABSENT : new Side(true, null, null, null);
                n.note = missing ? "library type resolved on theirs' classpath, not on ours'" : null;
            }
            case "MVN" -> {
                n.kind = ContextModel.MAVEN;
                n.label = body;
                b = Side.ABSENT;
                o = Side.ABSENT;
                t = new Side(true, null, pomLoc(body), null);
                JarArtifacts.Gav tg = theirsJars == null ? null : theirsJars.get(body);
                JarArtifacts.Gav og = oursJars == null ? null : oursJars.get(body);
                if (tg != null && t.loc == null) n.note = append(n.note, "theirs gets " + tg.version() + " transitively (not declared in the scanned pom)");
                if (og != null) n.note = append(n.note, "ours resolves " + og.version() + (tg != null && !tg.version().equals(og.version()) ? " (theirs " + tg.version() + ")" : ""));
            }
            default -> {
                n.kind = "unknown";
                n.label = body;
                b = o = t = Side.ABSENT;
            }
        }
        if (b.loc != null) n.loc.put("base", b.loc);
        if (o.loc != null) n.loc.put("ours", o.loc);
        if (t.loc != null) n.loc.put("theirs", t.loc);
        if (o.note != null) n.note = append(n.note, o.note);
        derive(n, b, o, t);
        return n;
    }

    private static void derive(Node n, Side b, Side o, Side t) {
        String status;
        if (!o.present && t.present) {
            status = ContextModel.MISSING;
            n.change = b.present ? "ours removed" : "theirs added";
        } else if (o.present && !t.present) {
            status = ContextModel.PRESENT;
            n.change = b.present ? "theirs removed" : "ours added";
        } else if (!o.present) {
            status = ContextModel.UNKNOWN;
            n.change = b.present ? "both removed" : "not found in the sources";
        } else if (Objects.equals(o.content, t.content)) {
            status = ContextModel.PRESENT;
            n.change = !b.present ? "both added (same)" : Objects.equals(b.content, o.content) ? "unchanged" : "same change on both sides";
        } else if (b.present && Objects.equals(b.content, t.content)) {
            status = ContextModel.PRESENT;
            n.change = "ours changed";
        } else if (b.present && Objects.equals(b.content, o.content)) {
            status = ContextModel.DIFFERS;
            n.change = "theirs changed";
        } else {
            status = ContextModel.DIFFERS;
            n.change = b.present ? "both changed" : "both added (different)";
        }
        if (n.ours == null) n.ours = status;
        if (n.kind.equals(ContextModel.LIBRARY) && !t.present) n.ours = ContextModel.UNKNOWN;
    }

    private static String append(String a, String b) {
        return a == null || a.isEmpty() ? b : a + "; " + b;
    }

    private String labelOfType(String fqn) {
        String pkg = null;
        for (CodeIndex i : List.of(theirs, ours, base)) {
            ClassInfo c = i.classes.get(fqn);
            if (c != null) {
                pkg = c.pkg;
                break;
            }
        }
        return pkg != null && !pkg.isEmpty() && fqn.startsWith(pkg + ".") ? fqn.substring(pkg.length() + 1) : Names.simpleName(fqn);
    }

    static MethodInfo method(String id, CodeIndex idx) {
        int h = id.indexOf('#');
        if (h < 0) return null;
        MethodInfo m = idx.methodsById.get(id);
        return m != null ? m : idx.findMethod(id.substring(0, h), id.substring(h + 1));
    }

    private static MethodInfo sameName(String id, CodeIndex idx) {
        int h = id.indexOf('#');
        String sig = id.substring(h + 1);
        String name = sig.contains("(") ? sig.substring(0, sig.indexOf('(')) : sig;
        List<MethodInfo> ms = idx.findMethodsByName(id.substring(0, h), name);
        return ms.isEmpty() ? null : ms.get(0);
    }

    private Side methodSide(String id, CodeIndex idx) {
        MethodInfo m = method(id, idx);
        if (m == null) return Side.ABSENT;
        return new Side(true, m.normalizedDecl, range(m, idx), null);
    }

    private static Loc range(MethodInfo m, CodeIndex idx) {
        int start = m.line, end = Math.max(m.endLine, m.line), name = m.line;
        if (m.node != null) {
            // include the javadoc so the reader sees the whole member; point at the name, not the first annotation
            start = m.node.getComment().flatMap(c -> c.getRange()).map(r -> r.begin.line).filter(l -> l < m.line).orElse(start);
            name = m.node.getName().getBegin().map(p -> p.line).orElse(name);
        }
        return new Loc(m.owner.file, start, end, name);
    }

    private static FieldDeclaration fieldDecl(ClassInfo c, String name) {
        if (c == null || c.node == null) return null;
        for (BodyDeclaration<?> b : c.node.getMembers()) {
            if (b instanceof FieldDeclaration fd) {
                for (VariableDeclarator v : fd.getVariables()) if (v.getNameAsString().equals(name)) return fd;
            }
        }
        return null;
    }

    private static Side fieldSide(String owner, String name, CodeIndex idx) {
        FieldInfo f = idx.findField(owner, name);
        if (f == null) return Side.ABSENT;
        FieldDeclaration fd = fieldDecl(f.owner, name);
        String content = fd != null ? Ast.normalized(fd) : f.typeRaw + " = " + f.initializer;
        if (f.enumConstant) content = f.initializer;
        int start = fd != null ? Ast.line(fd) : f.line, end = fd != null ? Ast.endLine(fd) : f.line;
        return new Side(true, content, new Loc(f.owner.file, start, Math.max(start, end), f.line), null);
    }

    private static Side typeSide(String fqn, CodeIndex idx) {
        ClassInfo c = idx.classes.get(fqn);
        if (c == null) return Side.ABSENT;
        int begin = Ast.line(c.node), end = Ast.endLine(c.node);
        if (end - begin > 60) {
            int first = end;
            for (BodyDeclaration<?> m : c.node.getMembers()) first = Math.min(first, Ast.line(m) - 1);
            end = Math.max(begin, Math.min(first, begin + 60));
        }
        return new Side(true, c.normalizedHeader, new Loc(c.file, begin, Math.max(begin, end), c.line), null);
    }

    private static Side configSide(String key, CodeIndex idx) {
        List<ConfigIndex.KeyLoc> ks = idx.config.find(key);
        if (ks.isEmpty()) return Side.ABSENT;
        ConfigIndex.KeyLoc k = ks.get(0);
        return new Side(true, null, new Loc(k.file(), Math.max(1, k.line() - 3), k.line() + 3, k.line()),
                ks.size() > 1 ? "defined in " + ks.size() + " places" : null);
    }

    private static Side sqlSide(String nsId, CodeIndex idx) {
        int h = nsId.indexOf('#');
        if (h < 0) return Side.ABSENT;
        String ns = nsId.substring(0, h), id = nsId.substring(h + 1);
        MapperXmlIndex.Body body = null;
        for (MapperXmlIndex.Body bd : idx.mapperXml.bodies(ns).values()) if (bd.id().equals(id)) body = bd;
        MapperXmlIndex.Statement st = idx.mapperXml.statements(ns).get(id);
        if (body == null && st == null) return Side.ABSENT;
        String file = body != null ? body.file() : st.file();
        int line = body != null ? body.line() : st.line();
        String tag = body != null ? body.tag() : st.kind();
        int end = closingLine(idx.snapshot.files().get(file), line, "</" + tag + ">");
        return new Side(true, body != null ? body.normalized() : null, new Loc(file, line, end, line), null);
    }

    static int closingLine(String text, int fromLine, String closing) {
        if (text == null) return fromLine;
        String[] lines = text.split("\n", -1);
        for (int i = Math.max(0, fromLine - 1); i < lines.length && i < fromLine + 400; i++) {
            if (lines[i].contains(closing) || (i == fromLine - 1 && lines[i].trim().endsWith("/>"))) return i + 1;
        }
        return fromLine;
    }

    private static Side beanSide(String type, CodeIndex idx) {
        List<BeanDef> bs = type.contains(" ") ? List.of() : idx.beansOfType(type);
        if (bs.isEmpty()) return Side.ABSENT;
        BeanDef b = bs.get(0);
        return new Side(true, null, new Loc(b.file(), b.line(), b.line(), b.line()), b.kind());
    }

    private Loc pomLoc(String ga) {
        String loc = mavenPom.get(ga);
        if (loc == null) return null;
        String path = loc.replaceAll(":\\d+(-\\d+)?$", "");
        String text = theirs.snapshot.files().get(path);
        if (text == null) return new Loc(path, 1, 1, 1);
        String[] lines = text.split("\n", -1);
        String artifact = "<artifactId>" + ga.substring(ga.indexOf(':') + 1) + "</artifactId>";
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains(artifact)) continue;
            int s = i, e = i;
            while (s > 0 && !lines[s].contains("<dependency>") && i - s < 6) s--;
            while (e < lines.length - 1 && !lines[e].contains("</dependency>") && e - i < 12) e++;
            return new Loc(path, s + 1, e + 1, i + 1);
        }
        return new Loc(path, 1, 1, 1);
    }
}
