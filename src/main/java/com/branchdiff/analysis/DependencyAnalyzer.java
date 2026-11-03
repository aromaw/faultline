package com.branchdiff.analysis;

import com.branchdiff.diff.ChangeSet;
import com.branchdiff.diff.MethodChange;
import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.FieldInfo;
import com.branchdiff.index.MethodInfo;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Finding.Confidence;
import com.branchdiff.report.Finding.Severity;
import com.branchdiff.util.Ast;
import com.branchdiff.util.Names;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For every method theirs added or modified, computes its dependency closure (called methods, constructors,
 * referenced types, fields, constants) and checks each project-internal dependency against ours.
 */
public final class DependencyAnalyzer {
    public enum DepKind {TYPE, METHOD, CONSTRUCTOR, FIELD, CONSTANT, METHOD_REF}

    /** One dependency of a theirs method. candidates != empty means name-based guess (uncertain). */
    public record Dep(DepKind kind, String owner, String member, String name, int arity, boolean certain,
                      List<String> candidateOwners) {
        public String id() {
            return kind + "|" + owner + "|" + member;
        }
    }

    public static final class Stats {
        public int methodsAnalyzed;
        public int callsResolved;
        public int callsFallback;
        public int callsExternal;
    }

    private final CodeIndex base;
    private final CodeIndex ours;
    private final CodeIndex theirs;
    public final Stats stats = new Stats();

    public DependencyAnalyzer(CodeIndex base, CodeIndex ours, CodeIndex theirs) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
    }

    public List<Finding> analyze(ChangeSet theirsChanges) {
        Map<String, Finding> byKey = new LinkedHashMap<>();
        for (MethodChange ch : theirsChanges.changes) {
            if (ch.newMethod == null || ch.newMethod.node() == null) continue;
            MethodInfo m = ch.newMethod;
            stats.methodsAnalyzed++;
            Map<String, Integer> deps = new LinkedHashMap<>(); // dep id -> first usage line
            Map<String, Dep> depById = new LinkedHashMap<>();
            for (Map.Entry<Dep, Integer> e : collect(m).entrySet()) {
                depById.putIfAbsent(e.getKey().id(), e.getKey());
                deps.putIfAbsent(e.getKey().id(), e.getValue());
            }
            for (Map.Entry<String, Dep> e : depById.entrySet()) {
                Dep d = e.getValue();
                if (d.kind == DepKind.METHOD && d.owner.equals(m.owner.fqn) && d.member.equals(m.key())) continue; // recursion
                check(d, m, ch, deps.get(e.getKey()), byKey);
            }
        }
        return collapse(new ArrayList<>(byKey.values()));
    }

    // ------------------------------------------------------------------ collection

    public Map<Dep, Integer> collect(MethodInfo m) {
        return collect(m.node(), m.owner, m.node());
    }

    /**
     * Dependencies of any declaration or expression {@code node} written inside class {@code ctx} of the index this
     * analyzer treats as "theirs" (callers may pass the same index three times to collect dependencies of ours).
     * {@code callable} supplies method type variables and locals; it may be null (field initializers, whole types).
     */
    public Map<Dep, Integer> collect(Node node, ClassInfo ctx, CallableDeclaration<?> callable) {
        Map<Dep, Integer> out = new LinkedHashMap<>();
        Set<String> tvars = Scopes.typeVars(ctx, callable);
        Scopes scopes = new Scopes(theirs, ctx, callable != null ? callable : node, tvars);

        for (MethodCallExpr call : node.findAll(MethodCallExpr.class)) collectCall(call, scopes, out);
        for (ObjectCreationExpr oce : node.findAll(ObjectCreationExpr.class)) collectNew(oce, ctx, tvars, out);
        for (FieldAccessExpr fa : node.findAll(FieldAccessExpr.class)) collectFieldAccess(fa, scopes, out);
        for (NameExpr ne : node.findAll(NameExpr.class)) collectName(ne, scopes, ctx, tvars, out);
        for (MethodReferenceExpr mr : node.findAll(MethodReferenceExpr.class)) collectMethodRef(mr, scopes, ctx, tvars, out);
        for (ClassOrInterfaceType t : node.findAll(ClassOrInterfaceType.class)) {
            if (t.getParentNode().orElse(null) instanceof ClassOrInterfaceType) continue; // scope part of qualified
            String fqn = theirs.resolveType(t.getNameWithScope(), ctx, tvars);
            if (theirs.isProjectType(fqn)) out.putIfAbsent(new Dep(DepKind.TYPE, fqn, "", Names.simpleName(fqn), 0, true, List.of()), Ast.line(t));
        }
        for (AnnotationExpr a : node.findAll(AnnotationExpr.class)) {
            String fqn = theirs.resolveType(a.getNameAsString(), ctx, tvars);
            if (theirs.isProjectType(fqn)) out.putIfAbsent(new Dep(DepKind.TYPE, fqn, "", Names.simpleName(fqn), 0, true, List.of()), Ast.line(a));
        }
        return out;
    }

    private void collectCall(MethodCallExpr call, Scopes scopes, Map<Dep, Integer> out) {
        String name = call.getNameAsString();
        int arity = call.getArguments().size();
        int line = Ast.line(call);
        try {
            ResolvedMethodDeclaration r = call.resolve();
            String owner = r.declaringType().getQualifiedName();
            if (!theirs.isProjectType(owner)) {
                stats.callsExternal++;
                return;
            }
            stats.callsResolved++;
            List<String> params = new ArrayList<>();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                String t = r.getParam(i).describeType();
                params.add(Names.simpleErased(t) + (r.getParam(i).isVariadic() && !t.endsWith("[]") && !t.endsWith("...") ? "[]" : ""));
            }
            String key = MethodInfo.key(name, params);
            // the solver may describe params differently from the declaration text; prefer the declared key
            MethodInfo declared = theirs.findMethod(owner, key);
            if (declared == null) {
                List<MethodInfo> byName = filterArity(theirs.findMethodsByName(owner, name), r.getNumberOfParams());
                if (byName.size() == 1) key = byName.get(0).key();
            }
            out.putIfAbsent(new Dep(DepKind.METHOD, owner, key, name, arity, true, List.of()), line);
            return;
        } catch (Throwable t) {
            stats.callsFallback++;
        }
        // name-based fallback
        Expression scope = call.getScope().orElse(null);
        List<String> scopeTypes = scopes.scopeTypes(scope);
        if (scope == null) {
            scopeTypes = new ArrayList<>(scopeTypes);
            for (var imp : scopes.imports()) {
                if (!imp.isStatic()) continue;
                String n = imp.name();
                if (imp.isAsterisk()) scopeTypes.add(n);
                else if (n.endsWith("." + name)) scopeTypes.add(Names.packageOf(n));
            }
        }
        List<MethodInfo> cands = new ArrayList<>();
        boolean scopeKnown = !scopeTypes.isEmpty();
        if (scopeKnown) {
            for (String t : scopeTypes) cands.addAll(filterArity(theirs.findMethodsByName(t, name), arity));
            if (cands.isEmpty()) {
                // unknown method on a known type: external super type, lombok, etc.
                stats.callsExternal++;
                return;
            }
        } else {
            for (ClassInfo c : theirs.classes.values()) cands.addAll(filterArity(c.methodsNamed(name), arity));
            if (cands.isEmpty()) {
                stats.callsExternal++;
                return;
            }
        }
        Set<String> owners = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (MethodInfo c : cands) {
            owners.add(c.owner.fqn);
            keys.add(c.key());
        }
        String owner = owners.iterator().next();
        String key = keys.size() == 1 ? keys.iterator().next() : name + "(/" + arity + ")";
        out.putIfAbsent(new Dep(DepKind.METHOD, owners.size() == 1 ? owner : String.join("|", owners), key, name, arity,
                false, new ArrayList<>(owners)), line);
    }

    private static List<MethodInfo> filterArity(List<MethodInfo> ms, int arity) {
        List<MethodInfo> res = new ArrayList<>();
        for (MethodInfo m : ms) {
            boolean varargs = !m.params.isEmpty() && m.params.get(m.params.size() - 1).endsWith("[]")
                    && m.node() != null && !m.node().getParameters().isEmpty()
                    && m.node().getParameter(m.params.size() - 1).isVarArgs();
            if (m.params.size() == arity || (varargs && arity >= m.params.size() - 1)) res.add(m);
        }
        return res;
    }

    private void collectNew(ObjectCreationExpr oce, ClassInfo ctx, Set<String> tvars, Map<Dep, Integer> out) {
        String fqn = theirs.resolveType(oce.getType().getNameWithScope(), ctx, tvars);
        if (!theirs.isProjectType(fqn)) return;
        int line = Ast.line(oce);
        if (oce.getAnonymousClassBody().isPresent()) return; // type dep recorded via ClassOrInterfaceType
        int arity = oce.getArguments().size();
        try {
            ResolvedConstructorDeclaration r = oce.resolve();
            List<String> params = new ArrayList<>();
            for (int i = 0; i < r.getNumberOfParams(); i++) params.add(Names.simpleErased(r.getParam(i).describeType()));
            String key = MethodInfo.key("<init>", params);
            ClassInfo c = theirs.classes.get(fqn);
            if (c.constructors().isEmpty()) return; // default constructor
            if (!c.methods.containsKey(key)) {
                List<MethodInfo> byArity = filterArity(c.constructors(), arity);
                if (byArity.size() == 1) key = byArity.get(0).key();
            }
            out.putIfAbsent(new Dep(DepKind.CONSTRUCTOR, fqn, key, "<init>", arity, true, List.of()), line);
        } catch (Throwable t) {
            ClassInfo c = theirs.classes.get(fqn);
            if (c.constructors().isEmpty()) return;
            List<MethodInfo> byArity = filterArity(c.constructors(), arity);
            if (byArity.size() == 1) {
                out.putIfAbsent(new Dep(DepKind.CONSTRUCTOR, fqn, byArity.get(0).key(), "<init>", arity, false, List.of(fqn)), line);
            } else {
                out.putIfAbsent(new Dep(DepKind.CONSTRUCTOR, fqn, "<init>(/" + arity + ")", "<init>", arity, false, List.of(fqn)), line);
            }
        }
    }

    private void collectFieldAccess(FieldAccessExpr fa, Scopes scopes, Map<Dep, Integer> out) {
        int line = Ast.line(fa);
        try {
            ResolvedValueDeclaration v = fa.resolve();
            addResolvedValue(v, fa.getNameAsString(), line, out);
            return;
        } catch (Throwable ignored) {
            // fallback
        }
        for (String t : scopes.scopeTypes(fa.getScope())) {
            if (!theirs.isProjectType(t)) continue;
            FieldInfo f = theirs.findField(t, fa.getNameAsString());
            if (f != null) {
                out.putIfAbsent(new Dep(f.isConstant() ? DepKind.CONSTANT : DepKind.FIELD, f.owner.fqn, f.name, f.name,
                        0, false, List.of(f.owner.fqn)), line);
                return;
            }
        }
    }

    private void addResolvedValue(ResolvedValueDeclaration v, String name, int line, Map<Dep, Integer> out) {
        String owner = null;
        boolean constant = false;
        if (v.isField()) {
            owner = v.asField().declaringType().getQualifiedName();
            constant = v.asField().isStatic();
        } else if (v.isEnumConstant()) {
            owner = Names.eraseGenerics(v.asEnumConstant().getType().describe());
            constant = true;
        }
        if (owner != null && theirs.isProjectType(owner)) {
            FieldInfo f = theirs.findField(owner, name);
            if (f != null && f.isConstant()) constant = true;
            out.putIfAbsent(new Dep(constant ? DepKind.CONSTANT : DepKind.FIELD, owner, name, name, 0, true, List.of()), line);
        }
    }

    private void collectName(NameExpr ne, Scopes scopes, ClassInfo ctx, Set<String> tvars, Map<Dep, Integer> out) {
        Node parent = ne.getParentNode().orElse(null);
        if (parent instanceof AnnotationExpr) return;
        String name = ne.getNameAsString();
        int line = Ast.line(ne);
        try {
            ResolvedValueDeclaration v = ne.resolve();
            addResolvedValue(v, name, line, out);
            return;
        } catch (Throwable ignored) {
            // fallback
        }
        if (scopes.isLocal(name)) return;
        FieldInfo f = scopes.visibleField(name);
        if (f != null) {
            out.putIfAbsent(new Dep(f.isConstant() ? DepKind.CONSTANT : DepKind.FIELD, f.owner.fqn, f.name, f.name, 0,
                    false, List.of(f.owner.fqn)), line);
            return;
        }
        // static import of a constant
        for (var imp : ctx.imports) {
            if (imp.isStatic() && !imp.isAsterisk() && imp.name().endsWith("." + name)) {
                String owner = Names.packageOf(imp.name());
                if (theirs.isProjectType(owner)) {
                    FieldInfo sf = theirs.findField(owner, name);
                    if (sf != null) out.putIfAbsent(new Dep(DepKind.CONSTANT, owner, name, name, 0, false, List.of(owner)), line);
                }
                return;
            }
        }
        if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            String fqn = theirs.resolveType(name, ctx, tvars);
            if (theirs.isProjectType(fqn)) out.putIfAbsent(new Dep(DepKind.TYPE, fqn, "", Names.simpleName(fqn), 0, true, List.of()), line);
        }
    }

    private void collectMethodRef(MethodReferenceExpr mr, Scopes scopes, ClassInfo ctx, Set<String> tvars,
                                  Map<Dep, Integer> out) {
        String name = mr.getIdentifier();
        if (name.equals("new")) return;
        int line = Ast.line(mr);
        try {
            ResolvedMethodDeclaration r = mr.resolve();
            String owner = r.declaringType().getQualifiedName();
            if (theirs.isProjectType(owner)) {
                out.putIfAbsent(new Dep(DepKind.METHOD_REF, owner, name, name, -1, true, List.of()), line);
            }
            return;
        } catch (Throwable ignored) {
            // fallback
        }
        Expression scope = mr.getScope();
        List<String> types = scope instanceof TypeExpr te
                ? listOf(theirs.resolveType(te.getType().asString(), ctx, tvars))
                : scopes.scopeTypes(scope);
        for (String t : types) {
            if (theirs.isProjectType(t) && !theirs.findMethodsByName(t, name).isEmpty()) {
                out.putIfAbsent(new Dep(DepKind.METHOD_REF, theirs.findMethodsByName(t, name).get(0).owner.fqn, name, name,
                        -1, false, List.of(t)), line);
                return;
            }
        }
    }

    private static List<String> listOf(String s) {
        return s == null ? List.of() : List.of(s);
    }

    // ------------------------------------------------------------------ checking

    /** Result of looking a dependency up in one version. */
    public record Lookup(String status, String detail, String location) {
        public boolean ok() {
            return status.equals("OK");
        }
    }

    public Lookup lookup(Dep d, CodeIndex idx) {
        List<String> owners = d.candidateOwners.isEmpty() ? List.of(d.owner) : d.candidateOwners;
        boolean anyOwner = false;
        for (String o : owners) if (idx.classes.containsKey(o)) anyOwner = true;
        if (!anyOwner) return new Lookup("MISSING_TYPE", "type " + owners + " does not exist", null);
        switch (d.kind) {
            case TYPE:
                return new Lookup("OK", "", idx.classes.get(d.owner).location());
            case METHOD: {
                if (!d.certain && d.member.contains("(/")) {
                    for (String o : owners) {
                        if (!filterArity(idx.findMethodsByName(o, d.name), d.arity).isEmpty()) return new Lookup("OK", "", null);
                    }
                    return new Lookup("MISSING", "no method " + d.name + " with " + d.arity + " args", null);
                }
                for (String o : owners) {
                    MethodInfo m = idx.findMethod(o, d.member);
                    if (m != null) {
                        MethodInfo tm = theirs.findMethod(d.owner, d.member);
                        if (tm != null && !tm.returnType.equals(m.returnType)) {
                            return new Lookup("INCONSISTENT", "return type is " + m.returnType + " (theirs expects "
                                    + tm.returnType + ")", m.location());
                        }
                        if (tm != null && tm.isStatic != m.isStatic) {
                            return new Lookup("INCONSISTENT", (m.isStatic ? "static" : "instance") + " method (theirs expects "
                                    + (tm.isStatic ? "static" : "instance") + ")", m.location());
                        }
                        return new Lookup("OK", "", m.location());
                    }
                }
                for (String o : owners) {
                    List<MethodInfo> same = idx.findMethodsByName(o, d.name);
                    if (!same.isEmpty()) {
                        List<String> sigs = new ArrayList<>();
                        for (MethodInfo s : same) sigs.add(s.key());
                        return new Lookup("INCONSISTENT", "only " + String.join(", ", sigs) + " exists", same.get(0).location());
                    }
                }
                return new Lookup("MISSING", "", null);
            }
            case CONSTRUCTOR: {
                ClassInfo c = idx.classes.get(d.owner);
                if (c.methods.containsKey(d.member)) return new Lookup("OK", "", c.methods.get(d.member).location());
                if (d.member.contains("(/") && !filterArity(c.constructors(), d.arity).isEmpty()) return new Lookup("OK", "", null);
                if (c.constructors().isEmpty() && d.arity == 0) return new Lookup("OK", "", null);
                List<String> sigs = new ArrayList<>();
                for (MethodInfo s : c.constructors()) sigs.add(s.key());
                return new Lookup(sigs.isEmpty() ? "MISSING" : "INCONSISTENT",
                        sigs.isEmpty() ? "only the default constructor exists" : "constructors: " + String.join(", ", sigs), c.location());
            }
            case FIELD:
            case CONSTANT: {
                for (String o : owners) {
                    FieldInfo f = idx.findField(o, d.member);
                    if (f != null) {
                        FieldInfo tf = theirs.findField(d.owner, d.member);
                        if (tf != null && !tf.type.equals(f.type)) {
                            return new Lookup("INCONSISTENT", "type is " + f.type + " (theirs: " + tf.type + ")", f.location());
                        }
                        return new Lookup("OK", "", f.location());
                    }
                }
                return new Lookup("MISSING", "", null);
            }
            case METHOD_REF: {
                for (String o : owners) if (!idx.findMethodsByName(o, d.member).isEmpty()) return new Lookup("OK", "", null);
                return new Lookup("MISSING", "", null);
            }
            default:
                return new Lookup("OK", "", null);
        }
    }

    private void check(Dep d, MethodInfo needer, MethodChange ch, int line, Map<String, Finding> byKey) {
        Lookup inOurs = lookup(d, ours);
        if (inOurs.ok()) return;
        // theirs may redeclare a method that ours' class still inherits from a library supertype
        // (e.g. a StringUtils extending commons-lang3), so check ours' library supertypes before reporting
        String inheritNote = null;
        if ((d.kind == DepKind.METHOD || d.kind == DepKind.METHOD_REF) && !inOurs.status.equals("MISSING_TYPE")) {
            List<String> owners = d.candidateOwners.isEmpty() ? List.of(d.owner) : d.candidateOwners;
            for (String o : owners) {
                if (!ours.classes.containsKey(o)) continue;
                Boolean inherited = ours.externalSupertypeHasMethod(o, d.name, d.kind == DepKind.METHOD_REF ? -1 : d.arity);
                if (Boolean.TRUE.equals(inherited)) return;
                if (inherited == null && inheritNote == null) {
                    inheritNote = "ours' " + Names.simpleName(o) + " has library supertype(s) " + ours.externalAncestors(o)
                            + " that could not be resolved (no classpath?); the method may be inherited from them";
                }
            }
        }
        Lookup inBase = lookup(d, base);
        String origin;
        if (inBase.ok()) origin = inOurs.status.equals("INCONSISTENT") ? "OURS_CHANGED" : "OURS_REMOVED";
        else origin = "THEIRS_NEW";
        boolean typeMissing = inOurs.status.equals("MISSING_TYPE");
        String kind = typeMissing ? "TYPE" : d.kind.name();
        String symbol = typeMissing ? (d.candidateOwners.isEmpty() ? d.owner : String.join(" | ", d.candidateOwners))
                : d.owner + (d.kind == DepKind.TYPE ? "" : (d.kind == DepKind.FIELD || d.kind == DepKind.CONSTANT ? "." : "#") + d.member);
        String status = typeMissing ? "MISSING" : inOurs.status;
        String key = kind + "|" + symbol;
        Finding f = byKey.get(key);
        if (f == null) {
            boolean sameClass = needer.owner.fqn.equals(d.owner);
            Severity sev = origin.startsWith("OURS") ? Severity.HIGH : (sameClass ? Severity.LOW : Severity.MEDIUM);
            String msg;
            if (typeMissing) {
                msg = "Type does not exist in ours" + (origin.equals("OURS_REMOVED") ? " (it existed in the merge-base: ours deleted/moved it)" : " (added by theirs)");
            } else if (status.equals("INCONSISTENT")) {
                msg = "Exists in ours with an incompatible signature: " + inOurs.detail;
            } else {
                msg = "Missing in ours" + (origin.equals("OURS_REMOVED") ? " (existed in the merge-base: ours removed it, git will not report this)"
                        : sameClass ? " (introduced by theirs in the same class; arrives with that class if it merges cleanly, must be ported together when cherry-picking)"
                        : " (introduced by theirs; must be ported together)");
            }
            f = new Finding(Category.MISSING_DEPENDENCY, sev, d.certain ? Confidence.CERTAIN : Confidence.UNCERTAIN,
                    status, kind, symbol, origin, msg);
            if (inOurs.location != null) f.oursAt(inOurs.location);
            f.theirsAt(typeMissing && theirs.classes.containsKey(d.owner) ? theirs.classes.get(d.owner).location() : theirsDefinition(d));
            if (!d.certain) f.detail("resolution", "name-based fallback (symbol solver could not resolve)");
            if (inheritNote != null) {
                f.confidence = Confidence.UNCERTAIN;
                f.detail("inheritance", inheritNote);
            }
            byKey.put(key, f);
        }
        if (f.severity == Severity.LOW && !needer.owner.fqn.equals(d.owner)) {
            f.severity = Severity.MEDIUM; // also needed from another class
            f.message = f.message.replace(" in the same class; arrives with that class if it merges cleanly, must be ported together when cherry-picking", "; must be ported together");
        }
        boolean neederInside = needer.owner.fqn.equals(d.owner) || needer.owner.fqn.startsWith(d.owner + ".");
        if (typeMissing && !d.kind.equals(DepKind.TYPE) && !neederInside) {
            @SuppressWarnings("unchecked")
            Set<String> members = (Set<String>) f.details.computeIfAbsent("membersUsed", k -> new LinkedHashSet<String>());
            members.add(d.member);
        }
        f.neededBy(needer.owner.fqn + "#" + needer.key() + " [" + ch.kind + " in theirs]", needer.owner.file + ":" + line);
    }

    private String theirsDefinition(Dep d) {
        ClassInfo c = theirs.classes.get(d.candidateOwners.isEmpty() ? d.owner : d.candidateOwners.get(0));
        if (c == null) return null;
        switch (d.kind) {
            case METHOD:
            case CONSTRUCTOR: {
                MethodInfo m = theirs.findMethod(c.fqn, d.member);
                if (m != null) return m.location();
                List<MethodInfo> ms = theirs.findMethodsByName(c.fqn, d.name);
                return ms.isEmpty() ? c.location() : ms.get(0).location();
            }
            case FIELD:
            case CONSTANT: {
                FieldInfo f = theirs.findField(c.fqn, d.member);
                return f != null ? f.location() : c.location();
            }
            default:
                return c.location();
        }
    }

    /**
     * Drops findings whose only consumers live inside a type that is itself missing in ours (the type finding
     * already covers them), and drops "type X missing" findings only needed by X's own methods.
     */
    private List<Finding> collapse(List<Finding> in) {
        Set<String> missingTypes = new LinkedHashSet<>();
        for (Finding f : in) if (f.kind.equals("TYPE") && f.status.equals("MISSING")) missingTypes.add(f.symbol);
        List<Finding> out = new ArrayList<>();
        for (Finding f : in) {
            List<Finding.Usage> keep = new ArrayList<>();
            for (Finding.Usage u : f.neededBy) {
                String neederClass = u.by().substring(0, u.by().indexOf('#'));
                boolean insideMissing = false;
                for (String t : missingTypes) {
                    if (neederClass.equals(t) || neederClass.startsWith(t + ".")) insideMissing = true;
                }
                // usages from inside a missing type only matter for *other* types
                if (insideMissing && (f.symbol.equals(neederClass) || f.symbol.startsWith(neederClass + "#")
                        || f.symbol.startsWith(neederClass + "."))) continue;
                keep.add(u);
            }
            if (keep.isEmpty()) continue;
            f.neededBy = keep;
            out.add(f);
        }
        return out;
    }
}
