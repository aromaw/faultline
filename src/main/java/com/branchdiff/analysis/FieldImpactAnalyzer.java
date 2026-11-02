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
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reverse impact for fields: fields, constants and enum constants that theirs deleted or renamed (the declaring
 * class still exists in theirs) while ours still has them, and the places in ours' code (methods, constructors,
 * field initializers, enum constant arguments, initializer blocks) that still reference them. Severity follows the
 * method signature-impact rules: the referencing member was added/modified by ours (or its class only exists in
 * ours) -> HIGH, because the merge will not fix it; unchanged in ours and theirs updated it -> LOW.
 * <p>
 * The opposite direction (ours removed a field that theirs' new/modified code needs) is reported by
 * {@link DependencyAnalyzer} as {@code MISSING ... OURS_REMOVED}.
 */
public final class FieldImpactAnalyzer {
    /** A field of the merge-base that theirs no longer has. {@code renamedTo} is theirs' replacement if detected. */
    record Removed(FieldInfo field, FieldInfo renamedTo) {
        String symbol() {
            return field.owner.fqn + "." + field.name;
        }

        String what() {
            return field.enumConstant ? "enum constant" : field.isConstant() ? "constant" : field.isStatic ? "static field" : "field";
        }

        String kind() {
            return (field.enumConstant ? "ENUM_CONSTANT" : field.isConstant() ? "CONSTANT" : "FIELD")
                    + (renamedTo != null ? "_RENAMED" : "_REMOVED");
        }
    }

    private final CodeIndex base;
    private final CodeIndex ours;
    private final CodeIndex theirs;
    private final ChangeSet oursChanges;

    public FieldImpactAnalyzer(CodeIndex base, CodeIndex ours, CodeIndex theirs, ChangeSet oursChanges) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
        this.oursChanges = oursChanges;
    }

    /** Fields of the merge-base that theirs removed/renamed and ours still declares. */
    List<Removed> removedFields() {
        List<Removed> res = new ArrayList<>();
        for (ClassInfo bc : base.classes.values()) {
            ClassInfo tc = theirs.classes.get(bc.fqn);
            if (tc == null || !ours.classes.containsKey(bc.fqn)) continue; // type removal is reported separately
            for (FieldInfo f : bc.fields.values()) {
                if (theirs.findField(bc.fqn, f.name) != null) continue;
                if (ours.findField(bc.fqn, f.name) == null) continue; // ours dropped it as well
                res.add(new Removed(f, renameTarget(bc, f, tc)));
            }
        }
        return res;
    }

    /** A field theirs added to the same class with the same kind, type and (non-empty) initializer. */
    private FieldInfo renameTarget(ClassInfo bc, FieldInfo f, ClassInfo tc) {
        String init = initializer(bc, f.name);
        if (init.isEmpty()) return null;
        List<FieldInfo> cands = new ArrayList<>();
        for (FieldInfo n : tc.fields.values()) {
            if (bc.fields.containsKey(n.name)) continue;
            if (n.enumConstant != f.enumConstant || n.isStatic != f.isStatic || !n.type.equals(f.type)) continue;
            if (init.equals(initializer(tc, n.name))) cands.add(n);
        }
        return cands.size() == 1 ? cands.get(0) : null;
    }

    /** normalized initializer of a field (or arguments + body of an enum constant), "" if none */
    static String initializer(ClassInfo c, String name) {
        if (c.node instanceof EnumDeclaration en) {
            for (EnumConstantDeclaration ec : en.getEntries()) {
                if (!ec.getNameAsString().equals(name)) continue;
                if (ec.getArguments().isEmpty() && ec.getClassBody().isEmpty()) return "";
                return ec.getArguments().stream().map(Ast::normalized).collect(Collectors.joining(","))
                        + ec.getClassBody().stream().map(Ast::normalized).collect(Collectors.joining());
            }
        }
        for (FieldDeclaration fd : c.node.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                if (v.getNameAsString().equals(name)) return v.getInitializer().map(Ast::normalized).orElse("");
            }
        }
        return "";
    }

    public List<Finding> analyze() {
        List<Removed> removed = removedFields();
        if (removed.isEmpty()) return List.of();
        Map<String, List<Removed>> byName = new LinkedHashMap<>();
        for (Removed r : removed) byName.computeIfAbsent(r.field.name, k -> new ArrayList<>()).add(r);
        Map<String, Finding> out = new LinkedHashMap<>();
        for (ClassInfo c : ours.classes.values()) {
            if (!c.inScope) continue;
            Map<Node, Scopes> scopesByMember = new LinkedHashMap<>();
            List<Expression> refs = new ArrayList<>(c.node.findAll(NameExpr.class));
            refs.addAll(c.node.findAll(FieldAccessExpr.class));
            for (Expression ref : refs) {
                String name = ref instanceof NameExpr ne ? ne.getNameAsString() : ((FieldAccessExpr) ref).getNameAsString();
                List<Removed> rs = byName.get(name);
                if (rs == null) continue;
                Node member = memberOf(ref, c.node);
                if (member == null) continue;
                for (Removed r : rs) {
                    Boolean certain = matches(ref, name, r, c, member, scopesByMember);
                    if (certain == null) continue;
                    report(r, c, member, ref, certain, out);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** The direct member of {@code td} that contains {@code n}; null if n belongs to a nested named type. */
    static Node memberOf(Node n, TypeDeclaration<?> td) {
        Node cur = n;
        while (cur.getParentNode().isPresent()) {
            Node p = cur.getParentNode().get();
            if (p == td) return cur;
            if (p instanceof TypeDeclaration) return null;
            cur = p;
        }
        return null;
    }

    /** null = not a reference to r; TRUE = resolved by the symbol solver; FALSE = name/import based match */
    private Boolean matches(Expression ref, String name, Removed r, ClassInfo c, Node member, Map<Node, Scopes> scopesCache) {
        String owner = r.field.owner.fqn;
        try {
            ResolvedValueDeclaration v = ref instanceof NameExpr ne ? ne.resolve() : ((FieldAccessExpr) ref).resolve();
            String decl = null;
            if (v.isField()) decl = v.asField().declaringType().getQualifiedName();
            else if (v.isEnumConstant()) decl = Names.eraseGenerics(v.asEnumConstant().getType().describe());
            return owner.equals(decl) ? Boolean.TRUE : null;
        } catch (Throwable ignored) {
            // fall back to name/import based matching
        }
        Scopes scopes = scopesCache.computeIfAbsent(member, m -> new Scopes(ours, c,
                m instanceof CallableDeclaration<?> cd ? cd : m, m instanceof CallableDeclaration<?> cd ? Scopes.typeVars(c, cd) : c.typeParams));
        if (ref instanceof FieldAccessExpr fa) {
            for (String t : scopes.scopeTypes(fa.getScope())) {
                if (t.equals(owner) || ours.isAssignable(t, owner)) return Boolean.FALSE;
            }
            return null;
        }
        if (scopes.isLocal(name)) return null;
        FieldInfo f = scopes.visibleField(name);
        if (f != null) return f.owner.fqn.equals(owner) ? Boolean.FALSE : null;
        for (ImportDeclaration imp : c.cu.getImports()) {
            if (!imp.isStatic()) continue;
            String n = imp.getNameAsString();
            if (!imp.isAsterisk() && n.equals(owner + "." + name)) return Boolean.FALSE;
            if (imp.isAsterisk() && n.equals(owner)) return Boolean.FALSE;
        }
        return null;
    }

    private void report(Removed r, ClassInfo c, Node member, Expression ref, boolean certain, Map<String, Finding> out) {
        String memberName;
        Severity sev;
        String why;
        if (member instanceof CallableDeclaration<?>) {
            MethodInfo caller = null;
            for (MethodInfo m : c.methods.values()) if (m.node == member) caller = m;
            if (caller == null) return;
            memberName = "#" + caller.key();
            MethodChange oc = oursChanges.byNewId.get(caller.id());
            MethodInfo theirsCaller = theirs.methodsById.get(caller.id());
            if (!base.classes.containsKey(c.fqn) || (oc != null && oc.kind == MethodChange.Kind.ADDED)) {
                sev = Severity.HIGH;
                why = "the referencing code was ADDED by ours: the merge will not update it";
            } else if (oc != null) {
                sev = Severity.HIGH;
                why = "the referencing method was " + oc.kind + " by ours: theirs' update of it (if any) will conflict or be lost";
            } else if (theirsCaller != null && theirsCaller.node != null && !mentions(theirsCaller.node, r.field.name)) {
                sev = Severity.LOW;
                why = "referencing method unchanged in ours; theirs updated it, so a clean merge carries theirs' version";
            } else if (theirsCaller == null) {
                sev = Severity.LOW;
                why = "referencing method unchanged in ours but removed/renamed in theirs, so a clean merge removes it too (re-check if you only cherry-pick)";
            } else {
                sev = Severity.MEDIUM;
                why = "referencing method unchanged in ours and theirs' version still mentions '" + r.field.name + "' (check what it refers to)";
            }
        } else {
            String key = memberKey(member);
            memberName = memberLabel(member);
            ClassInfo bc = base.classes.get(c.fqn);
            ClassInfo tc = theirs.classes.get(c.fqn);
            Node bm = bc == null ? null : findMember(bc.node, key);
            Node tm = tc == null ? null : findMember(tc.node, key);
            if (bm == null || !Ast.normalized(bm).equals(Ast.normalized(member))) {
                sev = Severity.HIGH;
                why = "the referencing declaration was added/changed by ours: the merge will not update it";
            } else if (tm != null && !mentions(tm, r.field.name)) {
                sev = Severity.LOW;
                why = "referencing declaration unchanged in ours; theirs updated it, so a clean merge carries theirs' version";
            } else if (tm == null) {
                sev = Severity.LOW;
                why = "referencing declaration unchanged in ours but removed in theirs, so a clean merge removes it too (re-check if you only cherry-pick)";
            } else {
                sev = Severity.MEDIUM;
                why = "referencing declaration unchanged in ours and theirs' version still mentions '" + r.field.name + "' (check what it refers to)";
            }
        }
        String at = c.file + ":" + Ast.line(ref);
        String key = r.symbol() + "|" + c.fqn + memberName;
        Finding existing = out.get(key);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            List<String> more = (List<String>) existing.details.computeIfAbsent("moreReferences", k -> new ArrayList<String>());
            if (!existing.oursLocation.equals(at) && !more.contains(at)) more.add(at);
            if (!certain) existing.confidence = Confidence.UNCERTAIN;
            return;
        }
        String change = r.renamedTo != null ? "renamed " + r.what() + " " + Names.simpleName(r.field.owner.fqn) + "." + r.field.name
                + " -> " + r.renamedTo.name : "removed " + r.what() + " " + Names.simpleName(r.field.owner.fqn) + "." + r.field.name;
        Finding f = new Finding(Category.SIGNATURE_IMPACT, sev, certain ? Confidence.CERTAIN : Confidence.UNCERTAIN,
                "STILL_USES_REMOVED_FIELD", r.kind(), r.symbol(), "THEIRS_CHANGED",
                "Theirs " + change + " but ours still references it; " + why);
        f.oursAt(at);
        ClassInfo tc = theirs.classes.get(r.field.owner.fqn);
        f.theirsAt(r.renamedTo != null ? r.renamedTo.location() : tc != null ? tc.location() : null);
        f.neededBy(c.fqn + memberName + " [in ours]", at);
        String text = ref.toString();
        f.detail("reference", text.length() > 160 ? text.substring(0, 160) + "..." : text);
        f.detail("base", r.field.location());
        if (!certain) f.detail("resolution", "name/import-based fallback (symbol solver could not resolve)");
        out.put(key, f);
    }

    private static boolean mentions(Node n, String name) {
        for (NameExpr ne : n.findAll(NameExpr.class)) if (ne.getNameAsString().equals(name)) return true;
        for (FieldAccessExpr fa : n.findAll(FieldAccessExpr.class)) if (fa.getNameAsString().equals(name)) return true;
        return false;
    }

    /** stable identity of a non-callable member across versions */
    static String memberKey(Node m) {
        if (m instanceof FieldDeclaration fd) {
            return "field:" + fd.getVariables().stream().map(VariableDeclarator::getNameAsString).collect(Collectors.joining(","));
        }
        if (m instanceof EnumConstantDeclaration ec) return "enum:" + ec.getNameAsString();
        int idx = 0;
        if (m.getParentNode().isPresent()) {
            for (Node s : m.getParentNode().get().getChildNodes()) {
                if (s == m) break;
                if (s.getClass() == m.getClass()) idx++;
            }
        }
        return m.getClass().getSimpleName() + "#" + idx;
    }

    private static Node findMember(TypeDeclaration<?> td, String key) {
        for (Node n : td.getChildNodes()) {
            if (n instanceof BodyDeclaration<?> || n instanceof com.github.javaparser.ast.expr.AnnotationExpr) {
                if (memberKey(n).equals(key)) return n;
            }
        }
        return null;
    }

    private static String memberLabel(Node m) {
        if (m instanceof FieldDeclaration fd) {
            return "." + fd.getVariables().stream().map(VariableDeclarator::getNameAsString).collect(Collectors.joining(",")) + " (field initializer)";
        }
        if (m instanceof EnumConstantDeclaration ec) return "." + ec.getNameAsString() + " (enum constant)";
        return " (" + memberKey(m) + ")";
    }
}
