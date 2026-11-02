package com.branchdiff.analysis;

import com.branchdiff.diff.ChangeSet;
import com.branchdiff.diff.MethodChange;
import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.MethodInfo;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Finding.Confidence;
import com.branchdiff.report.Finding.Severity;
import com.branchdiff.util.Ast;
import com.branchdiff.util.Names;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reverse impact: methods/types theirs removed or re-signed, and the places in ours that still use the old form. */
public final class ImpactAnalyzer {
    private final CodeIndex base;
    private final CodeIndex ours;
    private final CodeIndex theirs;
    private final ChangeSet oursChanges;

    public ImpactAnalyzer(CodeIndex base, CodeIndex ours, CodeIndex theirs, ChangeSet oursChanges) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
        this.oursChanges = oursChanges;
    }

    public List<Finding> analyze(ChangeSet theirsChanges) {
        List<Finding> res = new ArrayList<>();
        for (MethodChange ch : theirsChanges.changes) {
            boolean relevant = ch.kind == MethodChange.Kind.REMOVED || ch.kind == MethodChange.Kind.SIGNATURE_CHANGED
                    || ch.kind == MethodChange.Kind.RENAMED
                    || (ch.kind == MethodChange.Kind.MODIFIED && ch.declarationChanged);
            if (!relevant || ch.classRemoved) continue;
            MethodInfo old = ch.oldMethod;
            // if ours itself no longer has the old method, ours' call sites were already migrated by ours
            MethodInfo oursM = ours.findMethod(old.owner.fqn, old.key());
            if (oursM == null) continue;
            // ours already made the same declaration change (return type / static / abstract), so its callers match it
            if (ch.kind == MethodChange.Kind.MODIFIED && ch.newMethod != null
                    && oursM.returnType.equals(ch.newMethod.returnType) && oursM.isStatic == ch.newMethod.isStatic
                    && oursM.isAbstract == ch.newMethod.isAbstract) continue;
            scanCalls(ch, res);
        }
        for (String removedType : theirsChanges.removedClasses) {
            if (!ours.classes.containsKey(removedType) || !base.classes.containsKey(removedType)) continue;
            if (removedType.contains("$")) continue;
            scanTypeUsages(removedType, res);
        }
        return res;
    }

    private void scanCalls(MethodChange ch, List<Finding> res) {
        MethodInfo old = ch.oldMethod;
        String ownerFqn = old.owner.fqn;
        for (ClassInfo c : ours.classes.values()) {
            if (!c.inScope) continue; // sibling modules are looked up, never reported
            for (MethodInfo caller : c.methods.values()) {
                if (caller.node == null) continue;
                if (caller.owner.fqn.equals(ownerFqn) && caller.key().equals(old.key())) continue;
                Set<String> tvars = Scopes.typeVars(c, caller.node);
                Scopes scopes = null;
                List<Node> sites = new ArrayList<>();
                List<Boolean> certain = new ArrayList<>();
                if (old.constructor) {
                    for (ObjectCreationExpr oce : caller.node.findAll(ObjectCreationExpr.class)) {
                        if (oce.getArguments().size() != old.params.size()) continue;
                        if (!ownerFqn.equals(ours.resolveType(oce.getType().getNameWithScope(), c, tvars))) continue;
                        try {
                            ResolvedConstructorDeclaration r = oce.resolve();
                            List<String> ps = new ArrayList<>();
                            for (int i = 0; i < r.getNumberOfParams(); i++) ps.add(Names.simpleErased(r.getParam(i).describeType()));
                            if (!MethodInfo.key("<init>", ps).equals(old.key())) continue;
                            sites.add(oce);
                            certain.add(true);
                        } catch (Throwable t) {
                            sites.add(oce);
                            certain.add(false);
                        }
                    }
                } else {
                    for (MethodCallExpr call : caller.node.findAll(MethodCallExpr.class)) {
                        if (!call.getNameAsString().equals(old.name) || call.getArguments().size() != old.params.size()) continue;
                        try {
                            ResolvedMethodDeclaration r = call.resolve();
                            String decl = r.declaringType().getQualifiedName();
                            if (!decl.equals(ownerFqn) && !ours.isAssignable(decl, ownerFqn) && !ours.isAssignable(ownerFqn, decl)) continue;
                            sites.add(call);
                            certain.add(true);
                        } catch (Throwable t) {
                            if (scopes == null) scopes = new Scopes(ours, c, caller.node, tvars);
                            List<String> types = scopes.scopeTypes(call.getScope().orElse(null));
                            boolean match = types.isEmpty();
                            for (String ty : types) if (ty.equals(ownerFqn) || ours.isAssignable(ty, ownerFqn)) match = true;
                            if (!match) continue;
                            sites.add(call);
                            certain.add(false);
                        }
                    }
                    for (MethodReferenceExpr mr : caller.node.findAll(MethodReferenceExpr.class)) {
                        if (!mr.getIdentifier().equals(old.name)) continue;
                        if (scopes == null) scopes = new Scopes(ours, c, caller.node, tvars);
                        String scopeText = mr.getScope().toString();
                        String t = ours.resolveType(scopeText, c, tvars);
                        if (ownerFqn.equals(t) || (t == null && !scopes.scopeTypes(mr.getScope()).isEmpty()
                                && scopes.scopeTypes(mr.getScope()).contains(ownerFqn))) {
                            sites.add(mr);
                            certain.add(false);
                        }
                    }
                }
                for (int i = 0; i < sites.size(); i++) res.add(siteFinding(ch, caller, sites.get(i), certain.get(i)));
            }
        }
    }

    private Finding siteFinding(MethodChange ch, MethodInfo caller, Node site, boolean certain) {
        MethodChange oc = oursChanges.byNewId.get(caller.id());
        boolean oursClassOnly = !base.classes.containsKey(caller.owner.fqn);
        MethodInfo theirsCaller = theirs.methodsById.get(caller.id());
        Severity sev;
        String why;
        if (oursClassOnly || (oc != null && (oc.kind == MethodChange.Kind.ADDED))) {
            sev = Severity.HIGH;
            why = "caller was ADDED by ours: the merge will not update it";
        } else if (oc != null) {
            sev = Severity.HIGH;
            why = "caller was " + oc.kind + " by ours: theirs' update of this caller (if any) will conflict or be lost";
        } else if (theirsCaller != null && !stillCalls(theirsCaller, ch.oldMethod)) {
            sev = Severity.LOW;
            why = "caller unchanged in ours; theirs updated it, so a clean merge carries theirs' version";
        } else if (theirsCaller == null) {
            sev = Severity.LOW;
            why = "caller unchanged in ours but removed/renamed in theirs, so a clean merge removes it too (re-check if you only cherry-pick)";
        } else {
            sev = Severity.MEDIUM;
            why = "caller unchanged in ours and theirs still appears to call the old form (check overloads)";
        }
        String newForm = ch.newMethod == null ? "(removed in theirs)" : ch.newMethod.key();
        Finding f = new Finding(Category.SIGNATURE_IMPACT, sev, certain ? Confidence.CERTAIN : Confidence.UNCERTAIN,
                "STILL_USES_OLD_SIGNATURE", ch.kind.name(), ch.oldMethod.owner.fqn + "#" + ch.oldMethod.key(),
                "THEIRS_CHANGED", "Theirs " + verb(ch) + " " + ch.oldMethod.key() + " -> " + newForm + "; " + why);
        f.oursAt(caller.owner.file + ":" + Ast.line(site));
        if (ch.newMethod != null) f.theirsAt(ch.newMethod.location());
        f.neededBy(caller.owner.fqn + "#" + caller.key() + " [in ours]", caller.owner.file + ":" + Ast.line(site));
        f.detail("callSite", site.toString().length() > 160 ? site.toString().substring(0, 160) + "..." : site.toString());
        if (ch.kind == MethodChange.Kind.MODIFIED) f.detail("returnType", ch.oldMethod.returnType + " -> " + ch.newMethod.returnType);
        return f;
    }

    private static String verb(MethodChange ch) {
        return switch (ch.kind) {
            case REMOVED -> "removed";
            case RENAMED -> "renamed";
            case SIGNATURE_CHANGED -> "changed the parameters of";
            case MODIFIED -> "changed the return type/modifiers of";
            default -> "changed";
        };
    }

    private static boolean stillCalls(MethodInfo theirsCaller, MethodInfo old) {
        if (theirsCaller.node == null) return false;
        for (MethodCallExpr c : theirsCaller.node.findAll(MethodCallExpr.class)) {
            if (c.getNameAsString().equals(old.name) && c.getArguments().size() == old.params.size()) return true;
        }
        return false;
    }

    private void scanTypeUsages(String removedType, List<Finding> res) {
        for (var e : ours.units.entrySet()) {
            for (ClassOrInterfaceType t : e.getValue().findAll(ClassOrInterfaceType.class)) {
                Optional<TypeDeclaration> td = t.findAncestor(TypeDeclaration.class);
                if (td.isEmpty()) continue;
                ClassInfo ctx = ours.classes.get(((TypeDeclaration<?>) td.get()).getFullyQualifiedName().orElse(""));
                if (ctx == null || ctx.fqn.equals(removedType) || ctx.fqn.startsWith(removedType + ".")) continue;
                if (!removedType.equals(ours.resolveType(t.getNameWithScope(), ctx))) continue;
                Optional<CallableDeclaration> cd = t.findAncestor(CallableDeclaration.class);
                String user = ctx.fqn + cd.map(c -> "#" + ((CallableDeclaration<?>) c).getNameAsString() + "(..)").orElse("");
                boolean oursNew = !base.classes.containsKey(ctx.fqn);
                Finding f = new Finding(Category.SIGNATURE_IMPACT, oursNew ? Severity.HIGH : Severity.MEDIUM,
                        Confidence.CERTAIN, "STILL_USES_REMOVED_TYPE", "TYPE_REMOVED", removedType, "THEIRS_CHANGED",
                        "Theirs deleted/moved type " + Names.simpleName(removedType) + " but ours still references it"
                                + (oursNew ? " from a class that only ours has" : ""));
                f.oursAt(e.getKey() + ":" + Ast.line(t));
                f.neededBy(user + " [in ours]", e.getKey() + ":" + Ast.line(t));
                res.add(f);
                break; // one finding per file is enough
            }
        }
    }
}
