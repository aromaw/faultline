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
    /** compilation units of the service dir (the reporting scope) */
    public final Map<String, CompilationUnit> units = new LinkedHashMap<>();
    /** compilation units of sibling modules (lookups only) */
    public final Map<String, CompilationUnit> siblingUnits = new LinkedHashMap<>();
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

    private CodeIndex(Snapshot snapshot, boolean symbolSolverEnabled) {
        this.snapshot = snapshot;
        this.symbolSolverEnabled = symbolSolverEnabled;
    }

    public static boolean isTestPath(String p) {
        return p.contains("/src/test/") || p.startsWith("src/test/");
    }

    /**
     * Builds the index. {@code solver} may be null (no symbol resolution, base version), in which case only
     * name-based analysis is possible on this version's AST.
     */
    public static CodeIndex build(Snapshot snapshot, TypeSolver solver, boolean includeTests) {
        CodeIndex idx = new CodeIndex(snapshot, solver != null);
        ParserConfiguration cfg = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        if (solver != null) cfg.setSymbolResolver(new JavaSymbolSolver(solver));
        JavaParser parser = new JavaParser(cfg);
        for (Map.Entry<String, String> e : snapshot.files().entrySet()) {
            String path = e.getKey();
            if (!includeTests && isTestPath(path)) continue;
            if (path.endsWith(".java")) {
                ParseResult<CompilationUnit> r = parser.parse(e.getValue());
                if (r.getResult().isPresent() && r.isSuccessful()) {
                    CompilationUnit cu = r.getResult().get();
                    idx.units.put(path, cu);
                } else {
                    idx.parseErrors.add(path + ": " + r.getProblems().stream().findFirst().map(Object::toString).orElse("parse error"));
                    r.getResult().ifPresent(cu -> idx.units.put(path, cu));
                }
            } else if (ConfigIndex.isConfigFile(path)) {
                idx.config.addFile(path, e.getValue());
            } else if (MapperXmlIndex.maybeMapperXml(path, e.getValue())) {
                idx.mapperXml.addFile(path, e.getValue());
            }
        }
        for (Map.Entry<String, String> e : snapshot.siblingFiles().entrySet()) {
            String path = e.getKey();
            if (!path.endsWith(".java") || isTestPath(path)) continue;
            ParseResult<CompilationUnit> r = parser.parse(e.getValue());
            if (!r.isSuccessful()) {
                idx.parseErrors.add(path + " (sibling): " + r.getProblems().stream().findFirst().map(Object::toString).orElse("parse error"));
            }
            r.getResult().ifPresent(cu -> idx.siblingUnits.put(path, cu));
        }
        for (Map.Entry<String, CompilationUnit> e : idx.units.entrySet()) idx.indexUnit(e.getKey(), e.getValue(), true);
        for (Map.Entry<String, CompilationUnit> e : idx.siblingUnits.entrySet()) idx.indexUnit(e.getKey(), e.getValue(), false);
        for (ClassInfo c : idx.classes.values()) {
            for (String raw : c.superRaw) {
                String r = idx.resolveType(raw, c);
                c.supertypes.add(r != null ? r : Names.eraseGenerics(raw));
            }
        }
        idx.collectSpring();
        return idx;
    }

    // ------------------------------------------------------------------ indexing

    private void indexUnit(String path, CompilationUnit cu, boolean inScope) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        String pkgPath = pkg.replace('.', '/');
        if (!inScope) {
            // sibling roots are not part of the service's source roots
        } else if (!pkgPath.isEmpty() && dir.endsWith(pkgPath)) {
            sourceRoots.add(dir.substring(0, dir.length() - pkgPath.length()).replaceAll("/$", ""));
        } else if (pkgPath.isEmpty()) {
            sourceRoots.add(dir);
        }
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            boolean member = td.isTopLevelType() || td.getParentNode().map(p -> p instanceof TypeDeclaration).orElse(false);
            if (!member) continue;
            Optional<String> fqnOpt = td.getFullyQualifiedName();
            if (fqnOpt.isEmpty()) continue;
            if (!inScope && classes.containsKey(fqnOpt.get())) {
                // the service's own class wins over a sibling class with the same FQN
                if (td.isTopLevelType()) shadowedSiblingClasses.add(fqnOpt.get() + " (" + path + ")");
                continue;
            }
            indexType(path, cu, pkg, td, fqnOpt.get(), inScope);
        }
    }

    private void indexType(String path, CompilationUnit cu, String pkg, TypeDeclaration<?> td, String fqn, boolean inScope) {
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
        ClassInfo ci = new ClassInfo(fqn, td.getNameAsString(), pkg, kind, isAbstract, path, Ast.line(td), outer, td, cu);
        ci.inScope = inScope;
        ci.superRaw.addAll(supers);
        ci.typeParams.addAll(typeParams);
        for (AnnotationExpr a : td.getAnnotations()) ci.annotations.put(Names.simpleName(a.getNameAsString()), a);
        // header for "class-level changed" detection
        StringBuilder header = new StringBuilder();
        td.getAnnotations().forEach(a -> header.append(Ast.normalized(a)).append('\n'));
        header.append(td.getModifiers()).append(' ').append(kind).append(' ').append(td.getNameAsString()).append(' ').append(supers);
        ci.normalizedHeader = header.toString();
        classes.put(fqn, ci);

        for (BodyDeclaration<?> m : td.getMembers()) {
            if (m instanceof FieldDeclaration f) {
                Map<String, AnnotationExpr> anns = new LinkedHashMap<>();
                f.getAnnotations().forEach(a -> anns.put(Names.simpleName(a.getNameAsString()), a));
                boolean st = f.isStatic() || kind == ClassInfo.Kind.INTERFACE;
                boolean fin = f.isFinal() || kind == ClassInfo.Kind.INTERFACE;
                for (VariableDeclarator v : f.getVariables()) {
                    String raw = v.getType().asString();
                    ci.fields.put(v.getNameAsString(), new FieldInfo(ci, v.getNameAsString(), raw, Names.simpleErased(raw),
                            st, fin, v.getInitializer().isPresent(), false, anns, Ast.line(v)));
                }
            } else if (m instanceof MethodDeclaration md) {
                addMethod(ci, md, md.getNameAsString(), md.getType().asString(), false, md.isStatic(),
                        md.getBody().isEmpty(), md.isDefault());
            } else if (m instanceof ConstructorDeclaration cd) {
                addMethod(ci, cd, "<init>", fqn, true, false, false, false);
            }
        }
        if (td instanceof EnumDeclaration en) {
            for (EnumConstantDeclaration ec : en.getEntries()) {
                ci.fields.put(ec.getNameAsString(), new FieldInfo(ci, ec.getNameAsString(), td.getNameAsString(),
                        td.getNameAsString(), true, true, true, true, Map.of(), Ast.line(ec)));
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
                            false, false, false, Set.of(), Ast.line(p), Ast.line(p), "record-accessor " + raw, "", null));
                }
            }
        }
    }

    private void addMethod(ClassInfo ci, CallableDeclaration<?> cd, String name, String returnRaw, boolean ctor,
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
                isStatic, isAbstract && !ctor, isDefault, anns, Ast.line(cd), Ast.endLine(cd), Ast.normalized(cd), body, cd);
        ci.methods.put(mi.key(), mi);
        methodsById.put(mi.id(), mi);
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
        CompilationUnit cu = ctx.cu;
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic() || imp.isAsterisk()) continue;
            String n = imp.getNameAsString();
            if (n.equals(t) || n.endsWith("." + t)) return n;
        }
        String samePkg = ctx.pkg.isEmpty() ? t : ctx.pkg + "." + t;
        if (classes.containsKey(samePkg)) return samePkg;
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic() || !imp.isAsterisk()) continue;
            String cand = imp.getNameAsString() + "." + t;
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
            if (c.inScope) collectInjection(c, stereotype);
        }
    }

    private void collectInjection(ClassInfo c, boolean isBean) {
        TypeDeclaration<?> td = c.node;
        boolean lombokRequired = c.hasAnnotation("RequiredArgsConstructor");
        boolean lombokAll = c.hasAnnotation("AllArgsConstructor");
        for (FieldDeclaration f : td.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                String member = "field " + v.getNameAsString();
                Optional<AnnotationExpr> val = Ast.annotation(f, "Value");
                if (val.isPresent()) {
                    addValueRefs(c, member, val.get(), Ast.line(v));
                    continue;
                }
                boolean annotated = Ast.hasAnnotation(f, "Autowired", "Inject", "Resource");
                boolean lombok = isBean && !f.isStatic() && v.getInitializer().isEmpty()
                        && ((lombokRequired && f.isFinal()) || lombokAll);
                if (annotated || lombok) {
                    String qualifier = qualifierOf(f.getAnnotations());
                    boolean optional = Ast.annotation(f, "Autowired").flatMap(a -> Ast.attr(a, "required"))
                            .map(e -> e instanceof BooleanLiteralExpr b && !b.getValue()).orElse(false);
                    addInjection(c, member, v.getType().asString(), qualifier, optional,
                            annotated ? "field" : "lombok-constructor", Ast.line(v));
                }
            }
        }
        if (isBean) {
            List<ConstructorDeclaration> ctors = td.getConstructors();
            List<ConstructorDeclaration> chosen = new ArrayList<>();
            for (ConstructorDeclaration cd : ctors) if (Ast.hasAnnotation(cd, "Autowired", "Inject")) chosen.add(cd);
            if (chosen.isEmpty() && ctors.size() == 1) chosen.add(ctors.get(0));
            for (ConstructorDeclaration cd : chosen) addParams(c, cd.getParameters(), "constructor", "constructor param ");
        }
        for (MethodDeclaration md : td.getMethods()) {
            if (Ast.hasAnnotation(md, "Autowired", "Inject")) {
                addParams(c, md.getParameters(), "setter", md.getNameAsString() + "() param ");
            }
            Optional<AnnotationExpr> bean = Ast.annotation(md, "Bean");
            if (bean.isPresent()) {
                Set<String> names = new LinkedHashSet<>(Ast.stringValues(bean.get(), "value", "name"));
                if (names.isEmpty()) names.add(md.getNameAsString());
                String t = resolveType(md.getType().asString(), c);
                beans.add(new BeanDef(t != null ? t : Names.eraseGenerics(md.getType().asString()), names,
                        "@Bean " + c.simpleName + "." + md.getNameAsString() + "()", c.file, Ast.line(md)));
                addParams(c, md.getParameters(), "bean-method-param", "@Bean " + md.getNameAsString() + "() param ");
            }
            for (Parameter p : md.getParameters()) {
                if (!Ast.hasAnnotation(md, "Bean", "Autowired", "Inject")) {
                    Ast.annotation(p, "Value").ifPresent(a -> addValueRefs(c, md.getNameAsString() + "() param " + p.getNameAsString(), a, Ast.line(p)));
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
            valueRefs.add(new ValueRef(c.fqn, member, key.asString(), hasDefault ? "<default>" : null,
                    call.toString(), "Environment." + n, c.file, Ast.line(call)));
        }
    }

    private void addParams(ClassInfo c, NodeList<Parameter> params, String via, String prefix) {
        for (Parameter p : params) {
            Optional<AnnotationExpr> val = Ast.annotation(p, "Value");
            if (val.isPresent()) {
                addValueRefs(c, prefix + p.getNameAsString(), val.get(), Ast.line(p));
                continue;
            }
            boolean optional = Ast.hasAnnotation(p, "Nullable");
            addInjection(c, prefix + p.getNameAsString(), p.getType().asString(), qualifierOf(p.getAnnotations()),
                    optional, via, Ast.line(p));
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

    private void addValueRefs(ClassInfo c, String member, AnnotationExpr a, int line) {
        for (String raw : Ast.stringValues(a, "value")) {
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
