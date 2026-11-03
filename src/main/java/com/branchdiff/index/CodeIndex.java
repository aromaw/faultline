package com.branchdiff.index;

import com.branchdiff.index.SpringModel.BeanDef;
import com.branchdiff.index.SpringModel.ConfigPropsClass;
import com.branchdiff.index.SpringModel.InjectionPoint;
import com.branchdiff.index.SpringModel.ValueRef;
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
        List<FileSummary> svc = idx.summarize(service, snapshot.files(), true);
        List<FileSummary> sib = idx.summarize(siblings, snapshot.siblingFiles(), false);
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

    /** what parsing one file contributes; no AST is kept */
    private static final class FileSummary {
        final String path;
        String problem;
        boolean parsed;
        String pkg = "";
        final List<ClassInfo> types = new ArrayList<>();
        final List<Boolean> topLevel = new ArrayList<>();

        FileSummary(String path) {
            this.path = path;
        }
    }

    private static final int CHUNK = 16;

    private List<FileSummary> summarize(List<String> paths, Map<String, String> contents, boolean inScope) {
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
                    out.add(summarize(parser, path, contents.get(path), inScope));
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

    private FileSummary summarize(JavaParser parser, String path, String src, boolean inScope) {
        FileSummary f = new FileSummary(path);
        ParseResult<CompilationUnit> r = parser.parse(src);
        if (!r.isSuccessful()) f.problem = r.getProblems().stream().findFirst().map(Object::toString).orElse("parse error");
        if (r.getResult().isEmpty()) return f;
        CompilationUnit cu = r.getResult().get();
        Ast.dropTokens(cu);
        f.parsed = true;
        f.pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<ClassInfo.Import> imports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) imports.add(new ClassInfo.Import(imp.getNameAsString(), imp.isStatic(), imp.isAsterisk()));
        List<ClassInfo.Import> imps = List.copyOf(imports);
        int ti = -1;
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            ti++;
            boolean member = td.isTopLevelType() || td.getParentNode().map(p -> p instanceof TypeDeclaration).orElse(false);
            if (!member) continue;
            Optional<String> fqnOpt = td.getFullyQualifiedName();
            if (fqnOpt.isEmpty()) continue;
            f.types.add(indexType(path, f.pkg, td, ti, fqnOpt.get(), inScope, imps));
            f.topLevel.add(td.isTopLevelType());
        }
        return f;
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
            classes.put(ci.fqn, ci);
            for (MethodInfo mi : ci.methods.values()) if (mi.hasNode()) methodsById.put(mi.id(), mi);
        }
    }

    private ClassInfo indexType(String path, String pkg, TypeDeclaration<?> td, int typeIndex, String fqn, boolean inScope,
                                List<ClassInfo.Import> imports) {
        ClassInfo.Kind kind;
        boolean isAbstract = false;
        List<String> supers = new ArrayList<>();
        Set<String> typeParams = new LinkedHashSet<>();
        if (td instanceof ClassOrInterfaceDeclaration c) {
            kind = c.isInterface() ? ClassInfo.Kind.INTERFACE : ClassInfo.Kind.CLASS;
            isAbstract = c.isAbstract() || c.isInterface();
            c.getExtendedTypes().forEach(t -> supers.add(t.asString()));
            c.getImplementedTypes().forEach(t -> supers.add(t.asString()));
            c.getTypeParameters().forEach(tp -> typeParams.add(tp.getNameAsString()));
        } else if (td instanceof EnumDeclaration en) {
            kind = ClassInfo.Kind.ENUM;
            en.getImplementedTypes().forEach(t -> supers.add(t.asString()));
        } else if (td instanceof RecordDeclaration rd) {
            kind = ClassInfo.Kind.RECORD;
            rd.getImplementedTypes().forEach(t -> supers.add(t.asString()));
            rd.getTypeParameters().forEach(tp -> typeParams.add(tp.getNameAsString()));
        } else if (td instanceof AnnotationDeclaration) {
            kind = ClassInfo.Kind.ANNOTATION;
        } else {
            kind = ClassInfo.Kind.CLASS;
        }
        String outer = td.getParentNode().filter(p -> p instanceof TypeDeclaration)
                .flatMap(p -> ((TypeDeclaration<?>) p).getFullyQualifiedName()).orElse(null);
        ClassInfo ci = new ClassInfo(fqn, td.getNameAsString(), pkg, kind, isAbstract, path, Ast.line(td), outer, this,
                typeIndex, imports);
        ci.inScope = inScope;
        ci.superRaw.addAll(supers);
        ci.typeParams.addAll(typeParams);
        // detached copies: the summary must not keep the file's AST alive
        for (AnnotationExpr a : td.getAnnotations()) ci.annotations.put(Names.simpleName(a.getNameAsString()), a.clone());
        // header for "class-level changed" detection
        StringBuilder header = new StringBuilder();
        td.getAnnotations().forEach(a -> header.append(Ast.normalized(a)).append('\n'));
        header.append(td.getModifiers()).append(' ').append(kind).append(' ').append(td.getNameAsString()).append(' ').append(supers);
        ci.normalizedHeader = header.toString();

        int memberIndex = -1;
        for (BodyDeclaration<?> m : td.getMembers()) {
            memberIndex++;
            if (m instanceof FieldDeclaration f) {
                Map<String, AnnotationExpr> anns = new LinkedHashMap<>();
                f.getAnnotations().forEach(a -> anns.put(Names.simpleName(a.getNameAsString()), a.clone()));
                boolean st = f.isStatic() || kind == ClassInfo.Kind.INTERFACE;
                boolean fin = f.isFinal() || kind == ClassInfo.Kind.INTERFACE;
                for (VariableDeclarator v : f.getVariables()) {
                    String raw = v.getType().asString();
                    FieldInfo fi = new FieldInfo(ci, v.getNameAsString(), raw, Names.simpleErased(raw),
                            st, fin, v.getInitializer().isPresent(), false, anns, Ast.line(v));
                    fi.initializer = v.getInitializer().map(Ast::normalized).orElse(null);
                    ci.fields.put(v.getNameAsString(), fi);
                }
            } else if (m instanceof MethodDeclaration md) {
                addMethod(ci, md, memberIndex, md.getNameAsString(), md.getType().asString(), false, md.isStatic(),
                        md.getBody().isEmpty(), md.isDefault());
            } else if (m instanceof ConstructorDeclaration cd) {
                addMethod(ci, cd, memberIndex, "<init>", fqn, true, false, false, false);
            }
        }
        if (td instanceof EnumDeclaration en) {
            for (EnumConstantDeclaration ec : en.getEntries()) {
                FieldInfo fi = new FieldInfo(ci, ec.getNameAsString(), td.getNameAsString(),
                        td.getNameAsString(), true, true, true, true, Map.of(), Ast.line(ec));
                StringBuilder init = new StringBuilder("(");
                for (int i = 0; i < ec.getArguments().size(); i++) {
                    if (i > 0) init.append(", ");
                    init.append(Ast.normalized(ec.getArguments().get(i)));
                }
                init.append(')');
                for (BodyDeclaration<?> b : ec.getClassBody()) init.append('\n').append(Ast.normalized(b));
                fi.initializer = init.toString();
                ci.fields.put(ec.getNameAsString(), fi);
            }
        }
        if (td instanceof RecordDeclaration rd) {
            for (Parameter p : rd.getParameters()) {
                String raw = p.getType().asString();
                ci.fields.put(p.getNameAsString(), new FieldInfo(ci, p.getNameAsString(), raw, Names.simpleErased(raw),
                        false, true, false, false, Map.of(), Ast.line(p)));
                String key = MethodInfo.key(p.getNameAsString(), List.of());
                if (!ci.methods.containsKey(key)) {
                    ci.methods.put(key, new MethodInfo(ci, p.getNameAsString(), List.of(), Names.simpleErased(raw), false,
                            false, false, false, Set.of(), Ast.line(p), Ast.line(p), "record-accessor " + raw, "", -1));
                }
            }
        }
        if (inScope) {
            boolean stereotype = false;
            for (String s : ci.annotations.keySet()) if (STEREOTYPES.contains(s)) stereotype = true;
            ci.springOps = captureInjection(ci, td, stereotype);
        }
        return ci;
    }

    private void addMethod(ClassInfo ci, CallableDeclaration<?> cd, int memberIndex, String name, String returnRaw, boolean ctor,
                           boolean isStatic, boolean isAbstract, boolean isDefault) {
        List<String> params = new ArrayList<>();
        for (Parameter p : cd.getParameters()) {
            String t = Names.simpleErased(p.getType().asString()) + (p.isVarArgs() ? "[]" : "");
            params.add(t);
        }
        Set<String> anns = new LinkedHashSet<>();
        cd.getAnnotations().forEach(a -> anns.add(Names.simpleName(a.getNameAsString())));
        String body = "";
        if (cd instanceof MethodDeclaration md) body = md.getBody().map(Ast::normalized).orElse("");
        if (cd instanceof ConstructorDeclaration c) body = Ast.normalized(c.getBody());
        MethodInfo mi = new MethodInfo(ci, name, params, ctor ? ci.simpleName : Names.simpleErased(returnRaw), ctor,
                isStatic, isAbstract && !ctor, isDefault, anns, Ast.line(cd), Ast.endLine(cd), Ast.normalized(cd), body, memberIndex);
        ci.methods.put(mi.key(), mi);
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
            AnnotationExpr ms = c.annotations.get("MapperScan");
            if (ms != null) mapperScanPackages.addAll(Ast.stringValues(ms, "value", "basePackages"));
        }
        for (ClassInfo c : classes.values()) {
            boolean stereotype = false;
            for (String s : c.annotations.keySet()) if (STEREOTYPES.contains(s)) stereotype = true;
            Set<String> names = new LinkedHashSet<>();
            if (stereotype && !c.isInterface()) {
                for (String s : NAMED_STEREOTYPES) {
                    AnnotationExpr a = c.annotations.get(s);
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
            AnnotationExpr cp = c.annotations.get("ConfigurationProperties");
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
     * class, read from its AST while it is parsed; type names are resolved later ({@link #collectSpring}), when every
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

    record RefOp(ValueRef ref) implements SpringOp {
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
                valueRefs.add(r.ref());
            }
        }
        c.springOps = null;
    }

    private static List<SpringOp> captureInjection(ClassInfo c, TypeDeclaration<?> td, boolean isBean) {
        List<SpringOp> ops = new ArrayList<>();
        boolean lombokRequired = c.hasAnnotation("RequiredArgsConstructor");
        boolean lombokAll = c.hasAnnotation("AllArgsConstructor");
        for (FieldDeclaration f : td.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                String member = "field " + v.getNameAsString();
                Optional<AnnotationExpr> val = Ast.annotation(f, "Value");
                if (val.isPresent()) {
                    ops.add(new ValueOp(member, Ast.stringValues(val.get(), "value"), Ast.line(v)));
                    continue;
                }
                boolean annotated = Ast.hasAnnotation(f, "Autowired", "Inject", "Resource");
                boolean lombok = isBean && !f.isStatic() && v.getInitializer().isEmpty()
                        && ((lombokRequired && f.isFinal()) || lombokAll);
                if (annotated || lombok) {
                    String qualifier = qualifierOf(f.getAnnotations());
                    boolean optional = Ast.annotation(f, "Autowired").flatMap(a -> Ast.attr(a, "required"))
                            .map(e -> e instanceof BooleanLiteralExpr b && !b.getValue()).orElse(false);
                    ops.add(new InjectOp(member, v.getType().asString(), qualifier, optional,
                            annotated ? "field" : "lombok-constructor", Ast.line(v)));
                }
            }
        }
        if (isBean) {
            List<ConstructorDeclaration> ctors = td.getConstructors();
            List<ConstructorDeclaration> chosen = new ArrayList<>();
            for (ConstructorDeclaration cd : ctors) if (Ast.hasAnnotation(cd, "Autowired", "Inject")) chosen.add(cd);
            if (chosen.isEmpty() && ctors.size() == 1) chosen.add(ctors.get(0));
            for (ConstructorDeclaration cd : chosen) addParams(ops, cd.getParameters(), "constructor", "constructor param ");
        }
        for (MethodDeclaration md : td.getMethods()) {
            if (Ast.hasAnnotation(md, "Autowired", "Inject")) {
                addParams(ops, md.getParameters(), "setter", md.getNameAsString() + "() param ");
            }
            Optional<AnnotationExpr> bean = Ast.annotation(md, "Bean");
            if (bean.isPresent()) {
                Set<String> names = new LinkedHashSet<>(Ast.stringValues(bean.get(), "value", "name"));
                if (names.isEmpty()) names.add(md.getNameAsString());
                ops.add(new BeanOp(md.getType().asString(), names, "@Bean " + c.simpleName + "." + md.getNameAsString() + "()",
                        Ast.line(md)));
                addParams(ops, md.getParameters(), "bean-method-param", "@Bean " + md.getNameAsString() + "() param ");
            }
            for (Parameter p : md.getParameters()) {
                if (!Ast.hasAnnotation(md, "Bean", "Autowired", "Inject")) {
                    Ast.annotation(p, "Value").ifPresent(a -> ops.add(new ValueOp(md.getNameAsString() + "() param " + p.getNameAsString(),
                            Ast.stringValues(a, "value"), Ast.line(p))));
                }
            }
        }
        // Environment#getProperty("literal")
        for (MethodCallExpr call : td.findAll(MethodCallExpr.class)) {
            String n = call.getNameAsString();
            if (!(n.equals("getProperty") || n.equals("getRequiredProperty"))) continue;
            if (call.getArguments().isEmpty() || !(call.getArgument(0) instanceof StringLiteralExpr key)) continue;
            String scope = call.getScope().map(Node::toString).orElse("");
            if (!scope.toLowerCase().contains("env")) continue;
            boolean hasDefault = n.equals("getProperty") && call.getArguments().size() >= 2
                    && !(call.getArguments().size() == 2 && call.getArgument(1).isClassExpr());
            String member = call.findAncestor(CallableDeclaration.class).map(cd -> cd.getNameAsString() + "()").orElse("?");
            ops.add(new RefOp(new ValueRef(c.fqn, member, key.asString(), hasDefault ? "<default>" : null,
                    call.toString(), "Environment." + n, c.file, Ast.line(call))));
        }
        return ops.isEmpty() ? List.of() : ops;
    }

    private static void addParams(List<SpringOp> ops, NodeList<Parameter> params, String via, String prefix) {
        for (Parameter p : params) {
            Optional<AnnotationExpr> val = Ast.annotation(p, "Value");
            if (val.isPresent()) {
                ops.add(new ValueOp(prefix + p.getNameAsString(), Ast.stringValues(val.get(), "value"), Ast.line(p)));
                continue;
            }
            boolean optional = Ast.hasAnnotation(p, "Nullable");
            ops.add(new InjectOp(prefix + p.getNameAsString(), p.getType().asString(), qualifierOf(p.getAnnotations()),
                    optional, via, Ast.line(p)));
        }
    }

    private void addInjection(ClassInfo c, String member, String typeRaw, String qualifier, boolean optional,
                              String via, int line) {
        String erased = Names.simpleErased(typeRaw);
        boolean container = CONTAINER_TYPES.contains(erased);
        String fqn = resolveType(typeRaw, c);
        injectionPoints.add(new InjectionPoint(c.fqn, member, typeRaw, fqn, qualifier, optional || container, via, c.file, line));
    }

    private static String qualifierOf(NodeList<AnnotationExpr> anns) {
        for (AnnotationExpr a : anns) {
            String n = Names.simpleName(a.getNameAsString());
            if (n.equals("Qualifier") || n.equals("Named")) {
                List<String> v = Ast.stringValues(a, "value");
                if (!v.isEmpty()) return v.get(0);
            }
            if (n.equals("Resource")) {
                List<String> v = Ast.stringValues(a, "name");
                if (!v.isEmpty() && !v.get(0).isBlank()) return v.get(0);
            }
        }
        return null;
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
