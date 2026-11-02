package com.branchdiff.analysis;

import com.branchdiff.diff.ChangeSet;
import com.branchdiff.diff.MethodChange;
import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.ConfigIndex;
import com.branchdiff.index.FieldInfo;
import com.branchdiff.index.MapperXmlIndex;
import com.branchdiff.index.MethodInfo;
import com.branchdiff.index.SpringModel.BeanDef;
import com.branchdiff.index.SpringModel.ConfigPropsClass;
import com.branchdiff.index.SpringModel.InjectionPoint;
import com.branchdiff.index.SpringModel.ValueRef;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Finding.Confidence;
import com.branchdiff.report.Finding.Severity;
import com.branchdiff.util.Names;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Spring / MyBatis specific gaps: beans, @Value keys, @ConfigurationProperties, interface impls, mapper XML. */
public final class SpringAnalyzer {
    private static final Set<String> MYBATIS_STATEMENT_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete",
            "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider");

    private final CodeIndex base;
    private final CodeIndex ours;
    private final CodeIndex theirs;

    public SpringAnalyzer(CodeIndex base, CodeIndex ours, CodeIndex theirs) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
    }

    public List<Finding> analyze(ChangeSet theirsChanges) {
        List<Finding> res = new ArrayList<>();
        res.addAll(beans());
        res.addAll(values());
        res.addAll(configProperties());
        res.addAll(interfaceImpls(theirsChanges));
        res.addAll(mybatis(theirsChanges));
        return res;
    }

    // ------------------------------------------------------------------ beans

    List<Finding> beans() {
        Set<String> baseKeys = new HashSet<>();
        for (InjectionPoint ip : base.injectionPoints) baseKeys.add(ip.key());
        Map<String, Finding> out = new LinkedHashMap<>();
        for (InjectionPoint ip : theirs.injectionPoints) {
            if (ip.optional() || baseKeys.contains(ip.key())) continue;
            String injector = ip.ownerFqn() + " (" + ip.member() + ", " + ip.via() + ")";
            if (ip.qualifier() != null) {
                if (!ours.beansNamed(ip.qualifier()).isEmpty()) continue;
                List<BeanDef> inTheirs = theirs.beansNamed(ip.qualifier());
                String key = "name|" + ip.qualifier();
                Finding f = out.get(key);
                if (f == null) {
                    f = new Finding(Category.SPRING_BEAN, Severity.HIGH,
                            inTheirs.isEmpty() ? Confidence.UNCERTAIN : Confidence.CERTAIN, "MISSING", "BEAN_NAME",
                            "@Qualifier/@Resource(\"" + ip.qualifier() + "\") " + Names.simpleName(nn(ip.typeFqn(), ip.typeRaw())),
                            "THEIRS_NEW", inTheirs.isEmpty()
                            ? "No bean named '" + ip.qualifier() + "' in ours or theirs sources (may come from XML / auto-configuration / a library)"
                            : "Bean named '" + ip.qualifier() + "' is defined only in theirs (" + inTheirs.get(0).kind() + ")");
                    if (!inTheirs.isEmpty()) f.theirsAt(inTheirs.get(0).location());
                    out.put(key, f);
                }
                f.neededBy(injector, ip.location());
                continue;
            }
            String t = ip.typeFqn();
            if (t == null) continue;
            boolean project = theirs.isProjectType(t) || ours.isProjectType(t) || base.isProjectType(t);
            if (!ours.beansOfType(t).isEmpty()) continue;
            List<BeanDef> inTheirs = theirs.beansOfType(t);
            if (inTheirs.isEmpty() && !project) continue; // framework / library bean, assume auto-configured
            String key = "type|" + t;
            Finding f = out.get(key);
            if (f == null) {
                boolean typeInOurs = ours.isProjectType(t);
                String origin = base.beansOfType(t).isEmpty() ? "THEIRS_NEW" : "OURS_REMOVED";
                f = new Finding(Category.SPRING_BEAN, Severity.HIGH,
                        inTheirs.isEmpty() ? Confidence.UNCERTAIN : Confidence.CERTAIN, "MISSING", "BEAN", t, origin,
                        inTheirs.isEmpty()
                                ? "No bean of type " + Names.simpleName(t) + " found in either version's sources (may come from a library, XML or @Import)"
                                : "No bean of type " + Names.simpleName(t) + " in ours" + (typeInOurs ? "" : " (the type itself does not exist in ours)")
                                + "; theirs defines it via " + inTheirs.get(0).kind());
                if (!inTheirs.isEmpty()) f.theirsAt(inTheirs.get(0).location());
                out.put(key, f);
            }
            f.neededBy(injector, ip.location());
        }
        return new ArrayList<>(out.values());
    }

    private static String nn(String a, String b) {
        return a != null ? a : b;
    }

    // ------------------------------------------------------------------ @Value / Environment

    List<Finding> values() {
        Set<String> baseIds = new HashSet<>();
        for (ValueRef v : base.valueRefs) baseIds.add(v.identity());
        Set<String> oursIds = new HashSet<>();
        for (ValueRef v : ours.valueRefs) oursIds.add(v.identity());
        Map<String, Finding> out = new LinkedHashMap<>();
        for (ValueRef v : theirs.valueRefs) {
            if (v.defaultValue() != null) continue;
            if (v.key().contains("${") || v.key().isBlank()) continue;
            if (ours.config.has(v.key())) continue;
            boolean newRef = !baseIds.contains(v.identity());
            boolean inBaseConfig = base.config.has(v.key());
            boolean oursHasRef = oursIds.contains(v.identity());
            List<ConfigIndex.KeyLoc> inTheirs = theirs.config.find(v.key());
            String origin;
            if (newRef) {
                // ours already has the same reference and theirs has no config entry to port either:
                // the key is resolved outside the scanned config files on both sides, not a migration gap
                if (oursHasRef && inTheirs.isEmpty()) continue;
                origin = "THEIRS_NEW";
            } else if (inBaseConfig) {
                // ours removed both the reference and the key: nothing in ours needs the key any more
                if (!oursHasRef) continue;
                origin = "OURS_REMOVED";
            } else continue; // pre-existing reference that was never in config files (env var etc.)
            Finding f = out.get(v.key());
            if (f == null) {
                boolean envLike = v.key().equals(v.key().toUpperCase()) && v.key().matches("[A-Z0-9_]+");
                Confidence conf = (!inTheirs.isEmpty() && !envLike) ? Confidence.CERTAIN : Confidence.UNCERTAIN;
                String msg = !inTheirs.isEmpty()
                        ? "Property is required (no default) and is defined in theirs' config but in none of ours' application*/bootstrap* files"
                        : "Property is required (no default) but not defined in any config file of ours or theirs (env var / config server / command line?)";
                if (origin.equals("OURS_REMOVED")) msg += "; ours deleted it from config";
                f = new Finding(Category.SPRING_CONFIG, Severity.HIGH, conf, "MISSING", "PROPERTY", v.key(), origin, msg);
                if (!inTheirs.isEmpty()) {
                    ConfigIndex.KeyLoc k = inTheirs.get(0);
                    f.theirsAt(k.location()).detail("theirsProfile", k.profile());
                }
                f.detail("ours config files", ours.config.files());
                out.put(v.key(), f);
            }
            f.neededBy(v.ownerFqn() + " (" + v.member() + ", " + v.via() + " " + v.raw() + ")", v.location());
        }
        return new ArrayList<>(out.values());
    }

    // ------------------------------------------------------------------ @ConfigurationProperties

    List<Finding> configProperties() {
        List<Finding> res = new ArrayList<>();
        for (ConfigPropsClass cp : theirs.configPropsClasses) {
            ClassInfo c = theirs.classes.get(cp.classFqn());
            ClassInfo bc = base.classes.get(cp.classFqn());
            List<String> missing = new ArrayList<>();
            Finding f = null;
            for (FieldInfo fi : c.fields.values()) {
                if (fi.isStatic) continue;
                String key = cp.prefix() + "." + Names.kebab(fi.name);
                if (ours.config.has(key)) continue;
                List<ConfigIndex.KeyLoc> inTheirs = theirs.config.find(key);
                if (inTheirs.isEmpty()) continue; // not configured anywhere -> Java default applies
                boolean newField = bc == null || !bc.fields.containsKey(fi.name);
                String origin = (!newField && base.config.has(key)) ? "OURS_REMOVED" : "THEIRS_NEW";
                if (f == null) {
                    f = new Finding(Category.SPRING_CONFIG, Severity.MEDIUM, Confidence.CERTAIN, "MISSING",
                            "CONFIGURATION_PROPERTIES", "@ConfigurationProperties(prefix=\"" + cp.prefix() + "\") "
                            + cp.classFqn(), origin,
                            "Keys bound by this properties class are configured in theirs but missing from ours' config files"
                                    + (ours.config.under(cp.prefix()).isEmpty() ? " (ours has nothing under prefix '" + cp.prefix() + "')" : ""));
                    f.theirsAt(inTheirs.get(0).location());
                    f.neededBy(cp.classFqn(), cp.file() + ":" + cp.line());
                    res.add(f);
                }
                missing.add(key + " (theirs: " + inTheirs.get(0).location() + ")");
            }
            if (f != null) f.detail("missingKeys", missing);
        }
        return res;
    }

    // ------------------------------------------------------------------ interface / abstract methods

    List<Finding> interfaceImpls(ChangeSet theirsChanges) {
        List<Finding> res = new ArrayList<>();
        for (MethodChange ch : theirsChanges.changes) {
            if (ch.newMethod == null) continue;
            if (ch.kind != MethodChange.Kind.ADDED && ch.kind != MethodChange.Kind.SIGNATURE_CHANGED
                    && ch.kind != MethodChange.Kind.RENAMED) continue;
            MethodInfo m = ch.newMethod;
            ClassInfo owner = m.owner;
            boolean abstractMethod = (owner.isInterface() && !m.isDefault && !m.isStatic && m.isAbstract)
                    || (!owner.isInterface() && m.isAbstract && m.node != null && m.node.isAbstract());
            if (!abstractMethod) continue;
            for (ClassInfo impl : ours.classes.values()) {
                if (!impl.inScope || impl.isInterface() || impl.isAbstract || impl.fqn.equals(owner.fqn)) continue;
                if (!ours.ancestors(impl.fqn).contains(owner.fqn)) continue;
                if (ours.findMethod(impl.fqn, m.key()) != null) continue;
                boolean nameArity = false;
                for (MethodInfo x : ours.findMethodsByName(impl.fqn, m.name)) {
                    if (x.params.size() == m.params.size() && !x.isAbstract) nameArity = true;
                }
                MethodInfo theirsImpl = theirs.classes.containsKey(impl.fqn) ? theirs.findMethod(impl.fqn, m.key()) : null;
                boolean oursOnly = !theirs.classes.containsKey(impl.fqn);
                Severity sev = theirsImpl != null ? Severity.LOW : Severity.HIGH;
                String msg;
                if (theirsImpl != null) {
                    msg = "Ours' version of this implementation lacks the method; theirs' version of the same class implements it, so it should arrive with the merge of that class (verify it merges cleanly)";
                } else if (oursOnly) {
                    msg = "Implementation exists only in ours (theirs never saw it) and does not implement the new abstract method: will not compile after merge";
                } else {
                    msg = "Neither ours' nor theirs' version of this implementation implements the new abstract method";
                }
                Finding f = new Finding(Category.INTERFACE_IMPL, sev, nameArity ? Confidence.UNCERTAIN : Confidence.CERTAIN,
                        "MISSING", "IMPLEMENTATION", impl.fqn + " must implement " + owner.fqn + "#" + m.key(),
                        "THEIRS_NEW", msg).oursAt(impl.location()).theirsAt(m.location());
                if (theirsImpl != null) f.detail("theirsImplementation", theirsImpl.location());
                if (nameArity) f.detail("note", "ours has a method with the same name/arity; generic or type-name differences possible");
                f.neededBy(owner.fqn + "#" + m.key() + " [" + ch.kind + " in theirs]", m.location());
                res.add(f);
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ MyBatis

    List<Finding> mybatis(ChangeSet theirsChanges) {
        List<Finding> res = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        // 1) mapper interface methods added/changed in theirs -> need an XML statement (unless annotated)
        for (MethodChange ch : theirsChanges.changes) {
            if (ch.newMethod == null || ch.kind == MethodChange.Kind.MODIFIED) continue;
            MethodInfo m = ch.newMethod;
            ClassInfo c = m.owner;
            if (!c.inScope || !theirs.isMyBatisMapper(c) || m.isDefault || m.isStatic) continue;
            boolean annotated = false;
            for (String a : m.annotations) if (MYBATIS_STATEMENT_ANNOTATIONS.contains(a)) annotated = true;
            if (annotated) continue;
            String ns = c.fqn;
            MapperXmlIndex.Statement theirsStmt = theirs.mapperXml.statements(ns).get(m.name);
            MapperXmlIndex.Statement oursStmt = ours.mapperXml.statements(ns).get(m.name);
            ClassInfo oursMapper = ours.classes.get(ns);
            boolean oursHasMethod = oursMapper != null && !oursMapper.methodsNamed(m.name).isEmpty();
            if (oursStmt != null && oursHasMethod) continue;
            reported.add(ns + "#" + m.name);
            res.add(mapperFinding(ns, m.name, oursHasMethod, oursStmt != null, theirsStmt, m.location(),
                    theirsStmt == null ? Confidence.UNCERTAIN : Confidence.CERTAIN,
                    theirsStmt == null ? "theirs itself has no <select|insert|update|delete id=\"" + m.name
                            + "\"> for this mapper method (BindingException at runtime?)" : null));
        }
        // 2) XML statements added in theirs -> ours needs both the statement and the interface method
        for (String ns : theirs.mapperXml.namespaces()) {
            for (MapperXmlIndex.Statement st : theirs.mapperXml.statements(ns).values()) {
                if (base.mapperXml.statements(ns).containsKey(st.id())) continue;
                if (reported.contains(ns + "#" + st.id())) continue;
                boolean oursStmt = ours.mapperXml.statements(ns).containsKey(st.id());
                ClassInfo oursMapper = ours.classes.get(ns);
                boolean oursHasMethod = oursMapper != null && !oursMapper.methodsNamed(st.id()).isEmpty();
                if (oursStmt && oursHasMethod) continue;
                ClassInfo tm = theirs.classes.get(ns);
                String loc = tm != null && !tm.methodsNamed(st.id()).isEmpty() ? tm.methodsNamed(st.id()).get(0).location() : st.location();
                res.add(mapperFinding(ns, st.id(), oursHasMethod, oursStmt, st, loc, Confidence.CERTAIN, null));
            }
        }
        return res;
    }

    private Finding mapperFinding(String ns, String id, boolean oursHasMethod, boolean oursHasStmt,
                                  MapperXmlIndex.Statement theirsStmt, String theirsMethodLoc, Confidence conf, String extra) {
        List<String> missing = new ArrayList<>();
        if (!oursHasMethod) missing.add("interface method " + Names.simpleName(ns) + "." + id + "(..)");
        if (!oursHasStmt) missing.add("XML statement id=\"" + id + "\""
                + (ours.mapperXml.hasNamespace(ns) ? " in " + ours.mapperXml.fileOf(ns) : " (ours has no mapper XML for this namespace)"));
        Finding f = new Finding(Category.MYBATIS, Severity.HIGH, conf, "MISSING", "MAPPER_STATEMENT", ns + "#" + id,
                "THEIRS_NEW", "Ours is missing: " + String.join(" + ", missing) + (extra == null ? "" : "; " + extra));
        f.theirsAt(theirsStmt != null ? theirsStmt.location() : theirsMethodLoc);
        f.neededBy(ns + "#" + id + " (theirs mapper method)", theirsMethodLoc);
        if (theirsStmt != null) f.detail("theirsXml", theirsStmt.location()).detail("statementKind", theirsStmt.kind());
        return f;
    }
}
