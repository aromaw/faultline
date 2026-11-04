package com.branchdiff.index;

import com.branchdiff.index.CodeIndex.BeanOp;
import com.branchdiff.index.CodeIndex.InjectOp;
import com.branchdiff.index.CodeIndex.RefOp;
import com.branchdiff.index.CodeIndex.SpringOp;
import com.branchdiff.index.CodeIndex.ValueOp;
import com.branchdiff.util.Ast;
import com.branchdiff.util.Fingerprint;
import com.branchdiff.util.Names;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The summary of one Java file: what the index needs of it without its AST. A summary depends only on the file's
 * content (not on its path or version), so it is stored as bytes, keyed by the content's blob id, in the
 * {@link SummaryCache}, and every index (fork point, base, ours, theirs, and later runs) decodes the same bytes.
 *
 * <p>The encoded form is the only form: a freshly parsed file is encoded and then decoded like a cached one, so an
 * index is the same whether its summaries came from the parser or from the disk. Bump {@link #FORMAT} whenever what
 * is extracted, or how, changes.
 *
 * <p>Member texts are kept as {@link Fingerprint}s, not text (equality is all the index needs; a report prints the
 * few texts it shows from the AST again), and annotations as their normalized source, not AST nodes.
 */
final class FileSummaries {
    /** version of the extraction and of the encoding below */
    static final int FORMAT = 2; // 2: varargs flag of methods

    private FileSummaries() {
    }

    /** a decoded summary, bound to a path and an index */
    static final class FileSummary {
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

    // ------------------------------------------------------------------ extraction

    /** parses {@code src} and returns its encoded summary */
    static byte[] extract(JavaParser parser, String src) {
        Writer w = new Writer();
        ParseResult<CompilationUnit> r = parser.parse(src);
        // location and message only: Problem#toString appends the parser's stack trace, which depends on the calling
        // thread (and would make a cached summary differ from a fresh one)
        String problem = r.isSuccessful() ? null
                : r.getProblems().stream().findFirst().map(p -> p.getVerboseMessage()).orElse("parse error");
        w.str(problem);
        if (r.getResult().isEmpty()) {
            w.bool(false);
            return w.bytes();
        }
        w.bool(true);
        CompilationUnit cu = r.getResult().get();
        Ast.dropTokens(cu);
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        w.str(pkg);
        List<ClassInfo.Import> imports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) imports.add(new ClassInfo.Import(imp.getNameAsString(), imp.isStatic(), imp.isAsterisk()));
        w.num(imports.size());
        for (ClassInfo.Import i : imports) {
            w.str(i.name());
            w.num((i.isStatic() ? 1 : 0) | (i.isAsterisk() ? 2 : 0));
        }
        List<ClassInfo> types = new ArrayList<>();
        List<Boolean> top = new ArrayList<>();
        int ti = -1;
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            ti++;
            boolean member = td.isTopLevelType() || td.getParentNode().map(p -> p instanceof TypeDeclaration).orElse(false);
            if (!member) continue;
            Optional<String> fqn = td.getFullyQualifiedName();
            if (fqn.isEmpty()) continue;
            types.add(indexType(pkg, td, ti, fqn.get(), imports));
            top.add(td.isTopLevelType());
        }
        w.num(types.size());
        for (int i = 0; i < types.size(); i++) writeType(w, types.get(i), top.get(i));
        return w.bytes();
    }

    /** the class header used for "class-level changed" context: annotations, modifiers, kind, name, supertypes */
    static String header(TypeDeclaration<?> td, ClassInfo.Kind kind, List<String> supers) {
        StringBuilder header = new StringBuilder();
        td.getAnnotations().forEach(a -> header.append(Ast.normalized(a)).append('\n'));
        header.append(td.getModifiers()).append(' ').append(kind).append(' ').append(td.getNameAsString()).append(' ').append(supers);
        return header.toString();
    }

    private static ClassInfo indexType(String pkg, TypeDeclaration<?> td, int typeIndex, String fqn, List<ClassInfo.Import> imports) {
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
        ClassInfo ci = new ClassInfo(fqn, td.getNameAsString(), pkg, kind, isAbstract, null, Ast.line(td), outer, null,
                typeIndex, imports);
        ci.superRaw.addAll(supers);
        ci.typeParams.addAll(typeParams);
        for (AnnotationExpr a : td.getAnnotations()) ci.annotations.put(Names.simpleName(a.getNameAsString()), Ast.normalized(a));

        int memberIndex = -1;
        for (BodyDeclaration<?> m : td.getMembers()) {
            memberIndex++;
            if (m instanceof FieldDeclaration f) {
                Set<String> anns = new LinkedHashSet<>();
                f.getAnnotations().forEach(a -> anns.add(Names.simpleName(a.getNameAsString())));
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
                        td.getNameAsString(), true, true, true, true, Set.of(), Ast.line(ec));
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
                        false, true, false, false, Set.of(), Ast.line(p)));
                String key = MethodInfo.key(p.getNameAsString(), List.of());
                if (!ci.methods.containsKey(key)) {
                    String decl = "record-accessor " + raw;
                    ci.methods.put(key, new MethodInfo(ci, p.getNameAsString(), List.of(), Names.simpleErased(raw), false,
                            false, false, false, false, Set.of(), Ast.line(p), Ast.line(p), Fingerprint.of(decl), Fingerprint.EMPTY,
                            decl, "", -1));
                }
            }
        }
        // read for every class (a summary does not depend on the scope); the index uses them for in-scope classes only
        boolean stereotype = false;
        for (String s : ci.annotations.keySet()) if (CodeIndex.STEREOTYPES.contains(s)) stereotype = true;
        ci.springOps = captureInjection(ci, td, stereotype);
        return ci;
    }

    private static void addMethod(ClassInfo ci, CallableDeclaration<?> cd, int memberIndex, String name, String returnRaw,
                                  boolean ctor, boolean isStatic, boolean isAbstract, boolean isDefault) {
        List<String> params = new ArrayList<>();
        for (Parameter p : cd.getParameters()) {
            String t = Names.simpleErased(p.getType().asString()) + (p.isVarArgs() ? "[]" : "");
            params.add(t);
        }
        Set<String> anns = new LinkedHashSet<>();
        cd.getAnnotations().forEach(a -> anns.add(Names.simpleName(a.getNameAsString())));
        MethodInfo mi = new MethodInfo(ci, name, params, ctor ? ci.simpleName : Names.simpleErased(returnRaw), ctor,
                isStatic, isAbstract && !ctor, isDefault, cd.getParameters().isNonEmpty() && cd.getParameters().getLast().get().isVarArgs(),
                anns, Ast.line(cd), Ast.endLine(cd),
                Fingerprint.of(Ast.normalized(cd)), Fingerprint.of(MethodInfo.body(cd)), null, null, memberIndex);
        ci.methods.put(mi.key(), mi);
    }

    /**
     * Injection points, {@code @Value} / {@code Environment#getProperty} references and {@code @Bean} methods of a
     * class, read from its AST; type names are resolved later, when every class of the version is known.
     */
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
            ops.add(new RefOp(member, key.asString(), hasDefault ? "<default>" : null, call.toString(), "Environment." + n,
                    Ast.line(call)));
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

    // ------------------------------------------------------------------ encoding

    private static void writeType(Writer w, ClassInfo c, boolean topLevel) {
        w.bool(topLevel);
        w.str(c.fqn);
        w.str(c.simpleName);
        w.num(c.kind.ordinal());
        w.bool(c.isAbstract);
        w.num(c.line);
        w.str(c.outerFqn);
        w.num(c.typeIndex);
        w.num(c.annotations.size());
        c.annotations.forEach((k, v) -> {
            w.str(k);
            w.str(v);
        });
        w.strs(c.superRaw);
        w.strs(c.typeParams);
        w.num(c.fields.size());
        for (FieldInfo f : c.fields.values()) {
            w.str(f.name);
            w.str(f.typeRaw);
            w.str(f.type);
            w.num((f.isStatic ? 1 : 0) | (f.isFinal ? 2 : 0) | (f.hasInitializer ? 4 : 0) | (f.enumConstant ? 8 : 0));
            w.strs(f.annotations);
            w.num(f.line);
            w.str(f.initializer);
        }
        w.num(c.methods.size());
        for (MethodInfo m : c.methods.values()) {
            w.str(m.name);
            w.strs(m.params);
            w.str(m.returnType);
            w.num((m.constructor ? 1 : 0) | (m.isStatic ? 2 : 0) | (m.isAbstract ? 4 : 0) | (m.isDefault ? 8 : 0) | (m.varargs ? 16 : 0));
            w.strs(m.annotations);
            w.num(m.line);
            w.num(m.endLine);
            w.num(m.memberIndex + 1);
            w.print(m.declPrint);
            w.print(m.bodyPrint);
            if (!m.hasNode()) {
                w.str(m.normalizedDecl());
                w.str(m.normalizedBody());
            }
        }
        List<SpringOp> ops = c.springOps == null ? List.of() : c.springOps;
        w.num(ops.size());
        for (SpringOp op : ops) {
            if (op instanceof ValueOp v) {
                w.num(0);
                w.str(v.member());
                w.strs(v.raws());
                w.num(v.line());
            } else if (op instanceof InjectOp i) {
                w.num(1);
                w.str(i.member());
                w.str(i.typeRaw());
                w.str(i.qualifier());
                w.bool(i.optional());
                w.str(i.via());
                w.num(i.line());
            } else if (op instanceof BeanOp b) {
                w.num(2);
                w.str(b.typeRaw());
                w.strs(b.names());
                w.str(b.kind());
                w.num(b.line());
            } else if (op instanceof RefOp r) {
                w.num(3);
                w.str(r.member());
                w.str(r.key());
                w.str(r.defaultValue());
                w.str(r.raw());
                w.str(r.via());
                w.num(r.line());
            }
        }
    }

    /**
     * Decodes a summary for {@code path} of {@code index}. Throws {@link IllegalArgumentException} (or another runtime
     * exception) on malformed data; the cache's own framing (length, checksum) catches damaged entries before this.
     */
    static FileSummary decode(byte[] data, String path, CodeIndex index) {
        Reader r = new Reader(data);
        FileSummary f = new FileSummary(path);
        f.problem = r.str();
        f.parsed = r.bool();
        if (!f.parsed) {
            r.end();
            return f;
        }
        f.pkg = r.str();
        int ni = r.num();
        ClassInfo.Import[] imps = new ClassInfo.Import[ni];
        for (int i = 0; i < ni; i++) {
            String name = r.str();
            int flags = r.num();
            imps[i] = new ClassInfo.Import(name, (flags & 1) != 0, (flags & 2) != 0);
        }
        List<ClassInfo.Import> imports = List.of(imps);
        int nt = r.num();
        for (int t = 0; t < nt; t++) {
            f.topLevel.add(r.bool());
            f.types.add(readType(r, f.pkg, path, index, imports));
        }
        r.end();
        return f;
    }

    private static ClassInfo readType(Reader r, String pkg, String path, CodeIndex index, List<ClassInfo.Import> imports) {
        String fqn = r.str();
        String simple = r.str();
        ClassInfo.Kind kind = ClassInfo.Kind.values()[r.num()];
        boolean isAbstract = r.bool();
        int line = r.num();
        String outer = r.str();
        int typeIndex = r.num();
        ClassInfo c = new ClassInfo(fqn, simple, pkg, kind, isAbstract, path, line, outer, index, typeIndex, imports);
        for (int i = 0, n = r.num(); i < n; i++) c.annotations.put(r.str(), r.str());
        c.superRaw.addAll(r.strs());
        c.typeParams.addAll(r.strs());
        for (int i = 0, n = r.num(); i < n; i++) {
            String name = r.str();
            String typeRaw = r.str();
            String type = r.str();
            int flags = r.num();
            Set<String> anns = r.strSet();
            FieldInfo fi = new FieldInfo(c, name, typeRaw, type, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                    (flags & 8) != 0, anns, r.num());
            fi.initializer = r.str();
            c.fields.put(name, fi);
        }
        for (int i = 0, n = r.num(); i < n; i++) {
            String name = r.str();
            List<String> params = r.strs();
            String ret = r.str();
            int flags = r.num();
            Set<String> anns = r.strSet();
            int l = r.num(), end = r.num(), member = r.num() - 1;
            Fingerprint decl = r.print(), body = r.print();
            String declText = null, bodyText = null;
            if (member < 0) {
                declText = r.str();
                bodyText = r.str();
            }
            MethodInfo mi = new MethodInfo(c, name, params, ret, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                    (flags & 8) != 0, (flags & 16) != 0, anns, l, end, decl, body, declText, bodyText, member);
            c.methods.put(mi.key(), mi);
        }
        int nops = r.num();
        List<SpringOp> ops = new ArrayList<>(nops);
        for (int i = 0; i < nops; i++) {
            switch (r.num()) {
                case 0 -> ops.add(new ValueOp(r.str(), r.strs(), r.num()));
                case 1 -> ops.add(new InjectOp(r.str(), r.str(), r.str(), r.bool(), r.str(), r.num()));
                case 2 -> ops.add(new BeanOp(r.str(), r.strSet(), r.str(), r.num()));
                case 3 -> ops.add(new RefOp(r.str(), r.str(), r.str(), r.str(), r.str(), r.num()));
                default -> throw new IllegalArgumentException("bad spring op");
            }
        }
        c.springOps = ops.isEmpty() ? List.of() : ops;
        return c;
    }

    /**
     * Strings are written once per summary and referred to by number afterwards (type names, annotation names and
     * modifiers repeat a lot); numbers are unsigned varints.
     */
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        private final Map<String, Integer> seen = new HashMap<>();

        void num(int v) {
            if (v < 0) throw new IllegalArgumentException("negative " + v);
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }

        void bool(boolean b) {
            out.write(b ? 1 : 0);
        }

        void lng(long v) {
            for (int i = 56; i >= 0; i -= 8) out.write((int) (v >>> i) & 0xFF);
        }

        /** 0 = null, 1 = a new string (length, UTF-8), n >= 2 = the (n-2)th string written before */
        void str(String s) {
            if (s == null) {
                num(0);
                return;
            }
            Integer ref = seen.get(s);
            if (ref != null) {
                num(ref + 2);
                return;
            }
            seen.put(s, seen.size());
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            num(1);
            num(b.length);
            out.write(b, 0, b.length);
        }

        void strs(java.util.Collection<String> l) {
            num(l.size());
            for (String s : l) str(s);
        }

        void print(Fingerprint p) {
            lng(p.high());
            lng(p.low());
            num(p.length());
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static final class Reader {
        private final byte[] b;
        private int pos;
        private final List<String> seen = new ArrayList<>();

        Reader(byte[] b) {
            this.b = b;
        }

        private int u8() {
            if (pos >= b.length) throw new IllegalArgumentException("truncated summary");
            return b[pos++] & 0xFF;
        }

        int num() {
            int v = 0;
            for (int shift = 0; ; shift += 7) {
                if (shift > 28) throw new IllegalArgumentException("bad varint");
                int x = u8();
                v |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) break;
            }
            if (v < 0) throw new IllegalArgumentException("bad varint");
            return v;
        }

        boolean bool() {
            int x = u8();
            if (x > 1) throw new IllegalArgumentException("bad boolean");
            return x == 1;
        }

        long lng() {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | u8();
            return v;
        }

        String str() {
            int tag = num();
            if (tag == 0) return null;
            if (tag >= 2) {
                if (tag - 2 >= seen.size()) throw new IllegalArgumentException("bad string reference");
                return seen.get(tag - 2);
            }
            int len = num();
            if (len > b.length - pos) throw new IllegalArgumentException("truncated summary");
            String s = new String(b, pos, len, StandardCharsets.UTF_8);
            pos += len;
            // names, FQNs, imports, types and initializers repeat across files and across the four versions (most
            // files are the same blob in several of them); the JVM's string table keeps one copy and drops it when
            // unused. Interning only short strings left 17 MB of duplicates on ruoyi-vue-pro.
            s = s.intern();
            seen.add(s);
            return s;
        }

        List<String> strs() {
            int n = num();
            if (n == 0) return List.of();
            String[] a = new String[n];
            for (int i = 0; i < n; i++) a[i] = str();
            return Arrays.asList(a);
        }

        Set<String> strSet() {
            int n = num();
            if (n == 0) return Set.of();
            // most member annotation sets have one element (@Schema on a VO field): a singleton is 16 bytes, a
            // LinkedHashSet about 200. Read-only either way; iteration order stays the source order.
            if (n == 1) return java.util.Collections.singleton(str());
            Set<String> s = new LinkedHashSet<>(n * 4 / 3 + 1); // no resize, no oversized table (release 17)
            for (int i = 0; i < n; i++) s.add(str());
            return java.util.Collections.unmodifiableSet(s);
        }

        Fingerprint print() {
            long h = lng(), l = lng();
            return new Fingerprint(h, l, num());
        }

        void end() {
            if (pos != b.length) throw new IllegalArgumentException("trailing bytes in summary");
        }
    }
}
