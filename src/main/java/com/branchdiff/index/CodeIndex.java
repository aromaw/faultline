package com.branchdiff.index;

import com.branchdiff.index.SpringModel.BeanDef;
import com.branchdiff.index.SpringModel.ConfigPropsClass;
import com.branchdiff.index.SpringModel.InjectionPoint;
import com.branchdiff.index.SpringModel.ValueRef;
import com.branchdiff.index.FileSummaries.FileSummary;
import com.branchdiff.model.Snapshot;
import com.branchdiff.util.Ast;
import com.branchdiff.util.Names;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Everything we know about one version of the service: classes, methods, beans, config keys, mapper xml. */
public final class CodeIndex {
    public static final Set<String> STEREOTYPES = Set.of("Component", "Service", "Repository", "Controller",
            "RestController", "Configuration", "ControllerAdvice", "RestControllerAdvice", "Named",
            "SpringBootApplication", "AutoConfiguration");
    private static final Set<String> NAMED_STEREOTYPES = Set.of("Component", "Service", "Repository", "Controller",
            "RestController", "Configuration", "Named");
    private static final Set<String> SPRING_DATA_REPOS = Set.of("Repository", "CrudRepository", "JpaRepository",
            "PagingAndSortingRepository", "ListCrudRepository", "MongoRepository", "ReactiveCrudRepository",
            "ElasticsearchRepository", "R2dbcRepository", "BaseMapper", "IService");
    private static final Set<String> CONTAINER_TYPES = Set.of("List", "Set", "Collection", "Map", "Optional",
            "ObjectProvider", "Provider", "ObjectFactory", "Iterable", "Stream");
    private static final Set<String> JAVA_LANG = Set.of("String", "Object", "Integer", "Long", "Short", "Byte",
            "Double", "Float", "Boolean", "Character", "Number", "Math", "System", "Thread", "Runnable", "Exception",
            "RuntimeException", "Error", "Throwable", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "UnsupportedOperationException", "Iterable", "Comparable", "Enum", "Record",
            "StringBuilder", "StringBuffer", "Class", "Void", "Override", "Deprecated", "SuppressWarnings",
            "FunctionalInterface", "CharSequence", "AutoCloseable", "Cloneable", "InterruptedException",
            "IndexOutOfBoundsException", "ArithmeticException", "ClassCastException", "SafeVarargs");
    private static final Set<String> PRIMITIVES = Set.of("int", "long", "short", "byte", "double", "float",
            "boolean", "char", "void", "var");

    public final Snapshot snapshot;
    public final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    public final Map<String, MethodInfo> methodsById = new LinkedHashMap<>();
    /** sibling classes not indexed because the service dir declares the same FQN */
    public final List<String> shadowedSiblingClasses = new ArrayList<>();
    public final List<String> parseErrors = new ArrayList<>();
    public final List<BeanDef> beans = new ArrayList<>();
    public final List<InjectionPoint> injectionPoints = new ArrayList<>();
    public final List<ValueRef> valueRefs = new ArrayList<>();
    public final List<ConfigPropsClass> configPropsClasses = new ArrayList<>();
    public final Set<String> mapperScanPackages = new LinkedHashSet<>();
    public final ConfigIndex config = new ConfigIndex();
    public final MapperXmlIndex mapperXml = new MapperXmlIndex();
    public final Set<String> sourceRoots = new LinkedHashSet<>();
    public final boolean symbolSolverEnabled;
    /** type solver of this version (project sources + JDK + classpath jars); null for the merge-base */
    public final TypeSolver solver;

    private CodeIndex(Snapshot snapshot, TypeSolver solver) {
        this.snapshot = snapshot;
        this.symbolSolverEnabled = solver != null;
        this.solver = solver;
    }

    public static boolean isTestPath(String p) {
        return p.contains("/src/test/") || p.startsWith("src/test/");
    }

    /**
     * Builds the index. {@code solver} may be null (no symbol resolution, base version), in which case only
     * name-based analysis is possible on this version's AST.
     */
    public static CodeIndex build(Snapshot snapshot, TypeSolver solver, boolean includeTests) {
        CodeIndex idx = new CodeIndex(snapshot, solver);
        List<String> service = new ArrayList<>();
        for (Map.Entry<String, String> e : snapshot.files().entrySet()) {
            String path = e.getKey();
            if (!includeTests && isTestPath(path)) continue;
            if (path.endsWith(".java")) {
                service.add(path);
            } else if (ConfigIndex.isConfigFile(path)) {
                idx.config.addFile(path, e.getValue());
            } else if (MapperXmlIndex.maybeMapperXml(path, e.getValue())) {
                idx.mapperXml.addFile(path, e.getValue());
            }
        }
        List<String> siblings = new ArrayList<>();
        for (String path : snapshot.siblingFiles().keySet()) {
            if (path.endsWith(".java") && !isTestPath(path)) siblings.add(path);
        }
        // parse in parallel, keep only the summaries; merge sequentially in file order (deterministic)
        List<FileSummary> svc = idx.summarize(service, snapshot.files());
        List<FileSummary> sib = idx.summarize(siblings, snapshot.siblingFiles());
        for (FileSummary f : svc) {
            if (f.problem != null) idx.parseErrors.add(f.path + ": " + f.problem);
        }
        for (FileSummary f : sib) {
            if (f.problem != null) idx.parseErrors.add(f.path + " (sibling): " + f.problem);
        }
        for (FileSummary f : svc) idx.register(f, true);
        for (FileSummary f : sib) idx.register(f, false);
        for (ClassInfo c : idx.classes.values()) {
            for (String raw : c.superRaw) {
                String r = idx.resolveType(raw, c);
                c.supertypes.add(r != null ? r : Names.eraseGenerics(raw));
            }
        }
        idx.collectSpring();
        return idx;
    }

    // ------------------------------------------------------------------ lazy ASTs

    /** service-dir java files that parsed (possibly with problems), in snapshot order */
    private final List<String> javaPaths = new ArrayList<>();
    private final List<String> siblingJavaPaths = new ArrayList<>();
    private final Set<String> javaPathSet = new java.util.HashSet<>();
    private JavaParser lazyParser;

    private ParserConfiguration parserConfig() {
        ParserConfiguration cfg = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        if (solver != null) cfg.setSymbolResolver(new JavaSymbolSolver(solver));
        return cfg;
    }

    /** the java files of the service dir (the reporting scope), in snapshot order */
    public List<String> javaFiles() {
        return java.util.Collections.unmodifiableList(javaPaths);
    }

    public int javaFileCount() {
        return javaPaths.size();
    }

    public int siblingJavaFileCount() {
        return siblingJavaPaths.size();
    }

    /** source of a java file of this version (service dir or sibling module), null when absent */
    public String source(String path) {
        String s = snapshot.files().get(path);
        return s != null ? s : snapshot.siblingFiles().get(path);
    }

    /**
     * The compilation unit of a service-dir java file, parsed on demand (see {@link AstCache}); null when the file is
     * absent or did not parse.
     */
    public CompilationUnit unit(String path) {
        return javaPathSet.contains(path) ? entry(path).cu() : null;
    }

    AstCache.Entry entry(String path) {
        AstCache.Entry e = AstCache.get(this, path);
        if (e != null) return e;
        String src = source(path);
        if (src == null) throw new IllegalStateException("no source for " + path + " in " + snapshot.label());
        CompilationUnit cu;
        synchronized (this) {
            if (lazyParser == null) lazyParser = new JavaParser(parserConfig());
            cu = lazyParser.parse(src).getResult().orElseThrow(() -> new IllegalStateException("cannot parse " + path));
        }
        Ast.dropTokens(cu);
        List<TypeDeclaration<?>> types = new ArrayList<>();
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) types.add(td);
        e = new AstCache.Entry(cu, types, src.length());
        AstCache.put(this, path, e);
        return e;
    }

    /**
     * Whether a java file of this version mentions {@code word} as a whole identifier. Every name an AST node can
     * carry is written in the source, so this is a safe prefilter before parsing a file to look for uses of a name
     * (unicode escapes in identifiers aside).
     */
    public boolean mentions(String path, String word) {
        return mentionsWord(source(path), word);
    }

    public static boolean mentionsWord(String src, String word) {
        if (src == null || word == null || word.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = src.indexOf(word, from);
            if (i < 0) return false;
            int end = i + word.length();
            boolean startOk = i == 0 || !Character.isJavaIdentifierPart(src.charAt(i - 1));
            boolean endOk = end >= src.length() || !Character.isJavaIdentifierPart(src.charAt(end));
            if (startOk && endOk) return true;
            from = i + 1;
        }
    }

    /** whether the file mentions any of the words */
    public boolean mentionsAny(String path, java.util.Collection<String> words) {
        String src = source(path);
        for (String w : words) if (mentionsWord(src, w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ indexing

    private static final int CHUNK = 16;

    private List<FileSummary> summarize(List<String> paths, Map<String, String> contents) {
        int chunks = (paths.size() + CHUNK - 1) / CHUNK;
        // the first failure (typically OutOfMemoryError) stops the other chunks too: a parallel stream would keep
        // running them after rethrowing, holding memory the caller is trying to release
        java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
        List<List<FileSummary>> parts = java.util.stream.IntStream.range(0, chunks).parallel().mapToObj(ci -> {
            List<FileSummary> out = new ArrayList<>();
            if (failed.get() != null) return out;
            try {
                JavaParser parser = new JavaParser(parserConfig());
                for (int i = ci * CHUNK; i < Math.min(paths.size(), (ci + 1) * CHUNK) && failed.get() == null; i++) {
                    String path = paths.get(i);
                    out.add(FileSummaries.decode(FileSummaries.extract(parser, contents.get(path)), path, this));
                }
            } catch (RuntimeException | Error e) {
                out = null;
                failed.compareAndSet(null, e);
            }
            return out;
        }).toList();
        Throwable t = failed.get();
        if (t instanceof Error e) throw e;
        if (t != null) throw (RuntimeException) t;
        List<FileSummary> res = new ArrayList<>(paths.size());
        for (List<FileSummary> p : parts) res.addAll(p);
        return res;
    }

    private void register(FileSummary f, boolean inScope) {
        if (!f.parsed) return;
        if (inScope) {
            javaPaths.add(f.path);
        } else {
            siblingJavaPaths.add(f.path);
        }
        javaPathSet.add(f.path);
        String dir = f.path.contains("/") ? f.path.substring(0, f.path.lastIndexOf('/')) : "";
        String pkgPath = f.pkg.replace('.', '/');
        if (!inScope) {
            // sibling roots are not part of the service's source roots
        } else if (!pkgPath.isEmpty() && dir.endsWith(pkgPath)) {
            sourceRoots.add(dir.substring(0, dir.length() - pkgPath.length()).replaceAll("/$", ""));
        } else if (pkgPath.isEmpty()) {
            sourceRoots.add(dir);
        }
        for (int i = 0; i < f.types.size(); i++) {
            ClassInfo ci = f.types.get(i);
            if (!inScope && classes.containsKey(ci.fqn)) {
                // the service's own class wins over a sibling class with the same FQN
                if (f.topLevel.get(i)) shadowedSiblingClasses.add(ci.fqn + " (" + f.path + ")");
                continue;
            }
            ci.inScope = inScope;
            if (!inScope) ci.springOps = null;
            classes.put(ci.fqn, ci);
            for (MethodInfo mi : ci.methods.values()) if (mi.hasNode()) methodsById.put(mi.id(), mi);
        }
    }

    // ------------------------------------------------------------------ type resolution (import based, no solver)

    public boolean isProjectType(String fqn) {
        return fqn != null && classes.containsKey(fqn);
    }

    /**
     * Resolves a type name as written in {@code ctx} to a FQN using nesting, imports, package and java.lang.
     * Returns null for primitives, type variables and names that cannot be resolved.
     */
    public String resolveType(String written, ClassInfo ctx) {
        return resolveType(written, ctx, Set.of());
    }

    public String resolveType(String written, ClassInfo ctx, Set<String> extraTypeVars) {
        if (written == null) return null;
        String t = Names.eraseGenerics(written).replace("...", "").replace("[]", "").trim();
        if (t.isEmpty() || PRIMITIVES.contains(t) || t.equals("?")) return null;
        if (extraTypeVars.contains(t)) return null;
        if (t.contains(".")) {
            if (classes.containsKey(t)) return t;
            int dot = t.indexOf('.');
            String first = resolveType(t.substring(0, dot), ctx, extraTypeVars);
            if (first != null && classes.containsKey(first + t.substring(dot))) return first + t.substring(dot);
            return t;
        }
        // nested types of ctx and its outers, type vars
        ClassInfo c = ctx;
        while (c != null) {
            if (c.typeParams.contains(t)) return null;
            if (classes.containsKey(c.fqn + "." + t)) return c.fqn + "." + t;
            if (c.simpleName.equals(t)) return c.fqn;
            // nested types inherited from project supertypes
            for (String s : c.supertypes) {
                if (classes.containsKey(s + "." + t)) return s + "." + t;
            }
            c = c.outerFqn == null ? null : classes.get(c.outerFqn);
        }
        if (ctx == null) {
            for (ClassInfo ci : classes.values()) if (ci.simpleName.equals(t)) return ci.fqn;
            return null;
        }
        for (ClassInfo.Import imp : ctx.imports) {
            if (imp.isStatic() || imp.isAsterisk()) continue;
            String n = imp.name();
            if (n.equals(t) || n.endsWith("." + t)) return n;
        }
        String samePkg = ctx.pkg.isEmpty() ? t : ctx.pkg + "." + t;
        if (classes.containsKey(samePkg)) return samePkg;
        for (ClassInfo.Import imp : ctx.imports) {
            if (imp.isStatic() || !imp.isAsterisk()) continue;
            String cand = imp.name() + "." + t;
            if (classes.containsKey(cand)) return cand;
        }
        if (JAVA_LANG.contains(t)) return "java.lang." + t;
        return null;
    }

    /** All supertypes (transitively, FQNs where resolvable) of a type in this index. */
    public Set<String> ancestors(String fqn) {
        Set<String> res = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(fqn);
        while (!q.isEmpty()) {
            ClassInfo c = classes.get(q.poll());
            if (c == null) continue;
            for (String s : c.supertypes) if (res.add(s)) q.add(s);
        }
        return res;
    }

    /** Supertypes (transitively) of a project type that are not project types themselves, excluding Object. */
    public Set<String> externalAncestors(String fqn) {
        Set<String> res = new LinkedHashSet<>();
        for (String a : ancestors(fqn)) {
            if (!classes.containsKey(a) && !a.equals("java.lang.Object") && !a.equals("Object")) res.add(a);
        }
        return res;
    }

    /**
     * Whether a library supertype of project type {@code fqn} declares or inherits a method {@code name} that
     * accepts {@code arity} arguments ({@code arity < 0}: any). Returns null when the type has a library
     * supertype that cannot be resolved (for example without a Maven classpath), FALSE when every library
     * supertype was resolved and none has such a method, or when there is no library supertype at all.
     */
    public Boolean externalSupertypeHasMethod(String fqn, String name, int arity) {
        Set<String> ext = externalAncestors(fqn);
        if (ext.isEmpty()) return Boolean.FALSE;
        boolean unresolved = false;
        for (String t : ext) {
            if (solver == null) {
                unresolved = true;
                continue;
            }
            try {
                var ref = solver.tryToSolveType(t);
                if (!ref.isSolved()) {
                    unresolved = true;
                    continue;
                }
                for (var mu : ref.getCorrespondingDeclaration().getAllMethods()) {
                    if (!mu.getName().equals(name)) continue;
                    if (arity < 0) return Boolean.TRUE;
                    int n = mu.getNoParams();
                    boolean varargs = n > 0 && mu.getDeclaration().hasVariadicParameter();
                    if (n == arity || (varargs && arity >= n - 1)) return Boolean.TRUE;
                }
            } catch (Throwable e) {
                unresolved = true;
            }
        }
        return unresolved ? null : Boolean.FALSE;
    }

    public boolean isAssignable(String fromFqn, String toFqn) {
        if (fromFqn.equals(toFqn)) return true;
        for (String a : ancestors(fromFqn)) {
            if (a.equals(toFqn) || Names.simpleName(a).equals(toFqn) || a.equals(Names.simpleName(toFqn))) return true;
        }
        return false;
    }

    /** Finds a method by exact key in the class or any project supertype. */
    public MethodInfo findMethod(String classFqn, String key) {
        ClassInfo c = classes.get(classFqn);
        if (c == null) return null;
        MethodInfo m = c.methods.get(key);
        if (m != null) return m;
        for (String a : ancestors(classFqn)) {
            ClassInfo ac = classes.get(a);
            if (ac != null && ac.methods.containsKey(key)) return ac.methods.get(key);
        }
        return null;
    }

    public List<MethodInfo> findMethodsByName(String classFqn, String name) {
        List<MethodInfo> res = new ArrayList<>();
        ClassInfo c = classes.get(classFqn);
        if (c == null) return res;
        res.addAll(c.methodsNamed(name));
        for (String a : ancestors(classFqn)) {
            ClassInfo ac = classes.get(a);
            if (ac != null) res.addAll(ac.methodsNamed(name));
        }
        return res;
    }

    public FieldInfo findField(String classFqn, String name) {
        ClassInfo c = classes.get(classFqn);
        if (c == null) return null;
        if (c.fields.containsKey(name)) return c.fields.get(name);
        for (String a : ancestors(classFqn)) {
            ClassInfo ac = classes.get(a);
            if (ac != null && ac.fields.containsKey(name)) return ac.fields.get(name);
        }
        return null;
    }

    public List<ClassInfo> classesBySimpleName(String simple) {
        List<ClassInfo> res = new ArrayList<>();
        for (ClassInfo c : classes.values()) if (c.simpleName.equals(simple)) res.add(c);
        return res;
    }

    // ------------------------------------------------------------------ spring facts

    public boolean isMyBatisMapper(ClassInfo c) {
        if (!c.isInterface()) return false;
        if (c.hasAnnotation("Mapper")) return true;
        if (mapperXml.hasNamespace(c.fqn)) return true;
        for (String p : mapperScanPackages) if (c.pkg.equals(p) || c.pkg.startsWith(p + ".")) return true;
        return false;
    }

    private void collectSpring() {
        for (ClassInfo c : classes.values()) {
            AnnotationExpr ms = c.annotation("MapperScan");
            if (ms != null) mapperScanPackages.addAll(Ast.stringValues(ms, "value", "basePackages"));
        }
        for (ClassInfo c : classes.values()) {
            boolean stereotype = false;
            for (String s : c.annotationNames()) if (STEREOTYPES.contains(s)) stereotype = true;
            Set<String> names = new LinkedHashSet<>();
            if (stereotype && !c.isInterface()) {
                for (String s : NAMED_STEREOTYPES) {
                    AnnotationExpr a = c.annotation(s);
                    if (a != null) names.addAll(Ast.stringValues(a, "value"));
                }
                names.removeIf(String::isBlank);
                if (names.isEmpty()) names.add(Names.decapitalize(c.simpleName));
                beans.add(new BeanDef(c.fqn, names, "component", c.file, c.line));
            }
            if (c.isInterface()) {
                boolean repo = false;
                for (String s : c.superRaw) if (SPRING_DATA_REPOS.contains(Names.simpleErased(s))) repo = true;
                if (repo || isMyBatisMapper(c) || c.hasAnnotation("FeignClient", "Repository", "HttpExchange")) {
                    beans.add(new BeanDef(c.fqn, Set.of(Names.decapitalize(c.simpleName)),
                            isMyBatisMapper(c) ? "mybatis-mapper" : "interface-proxy", c.file, c.line));
                }
            }
            AnnotationExpr cp = c.annotation("ConfigurationProperties");
            if (cp != null) {
                List<String> prefixes = Ast.stringValues(cp, "value", "prefix");
                if (!prefixes.isEmpty() && c.inScope) configPropsClasses.add(new ConfigPropsClass(c.fqn, prefixes.get(0), c.file, c.line));
                if (!stereotype) {
                    beans.add(new BeanDef(c.fqn, Set.of(Names.decapitalize(c.simpleName)), "configuration-properties", c.file, c.line));
                }
            }
            if (c.inScope) collectInjection(c);
        }
    }

    /**
     * Injection points, {@code @Value} / {@code Environment#getProperty} references and {@code @Bean} methods of a
     * class, read from its AST while it is parsed ({@link FileSummaries}); type names are resolved later ({@link #collectSpring}), when every
     * class of the version is known.
     */
    sealed interface SpringOp permits ValueOp, InjectOp, BeanOp, RefOp {
    }

    record ValueOp(String member, List<String> raws, int line) implements SpringOp {
    }

    record InjectOp(String member, String typeRaw, String qualifier, boolean optional, String via, int line) implements SpringOp {
    }

    record BeanOp(String typeRaw, Set<String> names, String kind, int line) implements SpringOp {
    }

    record RefOp(String member, String key, String defaultValue, String raw, String via, int line) implements SpringOp {
    }

    private void collectInjection(ClassInfo c) {
        if (c.springOps == null) return;
        for (SpringOp op : c.springOps) {
            if (op instanceof ValueOp v) {
                addValueRefs(c, v.member(), v.raws(), v.line());
            } else if (op instanceof InjectOp i) {
                addInjection(c, i.member(), i.typeRaw(), i.qualifier(), i.optional(), i.via(), i.line());
            } else if (op instanceof BeanOp b) {
                String t = resolveType(b.typeRaw(), c);
                beans.add(new BeanDef(t != null ? t : Names.eraseGenerics(b.typeRaw()), b.names(), b.kind(), c.file, b.line()));
            } else if (op instanceof RefOp r) {
                valueRefs.add(new ValueRef(c.fqn, r.member(), r.key(), r.defaultValue(), r.raw(), r.via(), c.file, r.line()));
            }
        }
        c.springOps = null;
    }

    private void addInjection(ClassInfo c, String member, String typeRaw, String qualifier, boolean optional,
                              String via, int line) {
        String erased = Names.simpleErased(typeRaw);
        boolean container = CONTAINER_TYPES.contains(erased);
        String fqn = resolveType(typeRaw, c);
        injectionPoints.add(new InjectionPoint(c.fqn, member, typeRaw, fqn, qualifier, optional || container, via, c.file, line));
    }

    private void addValueRefs(ClassInfo c, String member, List<String> raws, int line) {
        for (String raw : raws) {
            for (String body : Names.placeholderBodies(raw)) {
                String[] kd = Names.splitPlaceholder(body);
                valueRefs.add(new ValueRef(c.fqn, member, kd[0], kd[1], raw, "@Value", c.file, line));
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public List<BeanDef> beansOfType(String typeFqn) {
        List<BeanDef> res = new ArrayList<>();
        for (BeanDef b : beans) {
            if (b.typeFqn().equals(typeFqn) || isAssignable(b.typeFqn(), typeFqn)) res.add(b);
        }
        return res;
    }

    public List<BeanDef> beansNamed(String name) {
        List<BeanDef> res = new ArrayList<>();
        for (BeanDef b : beans) if (b.names().contains(name)) res.add(b);
        return res;
    }

    /** type variable names declared on a method (for resolution of T in signatures) */
    public static Set<String> methodTypeVars(CallableDeclaration<?> cd) {
        Set<String> res = new LinkedHashSet<>();
        if (cd != null) for (TypeParameter tp : cd.getTypeParameters()) res.add(tp.getNameAsString());
        return res;
    }
}
