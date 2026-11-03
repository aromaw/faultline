package com.branchdiff.analysis;

import com.branchdiff.Options;
import com.branchdiff.diff.ChangeSet;
import com.branchdiff.diff.MethodChange;
import com.branchdiff.git.GitRunner;
import com.branchdiff.git.SiblingModules;
import com.branchdiff.git.SnapshotLoader;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Report;
import com.github.javaparser.resolution.TypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Orchestrates: git snapshots -> indexes -> change sets -> analyzers -> report. */
public final class Analyzer {
    private final Options opt;
    private final List<String> warnings = new ArrayList<>();
    /** the three versions of the last run (used by the HTML report to embed file contents) */
    public Snapshot lastBase, lastOurs, lastTheirs;
    /** git access of the last {@link #runFromGit()} (null for {@link #run} on in-memory snapshots) */
    public GitRunner lastGit;

    /** Everything a follow-up analysis (the conflict mode) needs while the type solvers' temp sources still exist. */
    public record Context(CodeIndex base, CodeIndex ours, CodeIndex theirs, ContextBuilder context, ChangeSet theirsChanges,
                          ChangeSet theirsChangesAll, List<Finding> findings, Report report, List<String> warnings,
                          boolean librariesComparable) {
    }

    /** called at the end of {@link #run}, before the temporary workspaces are deleted */
    public java.util.function.Consumer<Context> after;

    public Analyzer(Options opt) {
        this.opt = opt;
    }

    private void log(String s) {
        if (opt.verbose) System.err.println("[branch-diff] " + s);
    }

    public Report runFromGit() {
        return runFromGit(null);
    }

    /** @param baseRef explicit base commit (the picked commit's parent in conflict mode); null = merge-base */
    public Report runFromGit(String baseRef) {
        GitRunner git = new GitRunner(opt.repo.toAbsolutePath().normalize());
        lastGit = git;
        git.run("rev-parse", "--git-dir");
        String oursC = git.resolveCommit(opt.ours);
        String theirsC = git.resolveCommit(opt.theirs);
        String baseC = baseRef != null ? git.resolveCommit(baseRef) : git.mergeBase(oursC, theirsC);
        log("ours=" + oursC + " theirs=" + theirsC + " merge-base=" + baseC);
        SnapshotLoader loader = new SnapshotLoader(git);
        String dir = SnapshotLoader.normalizeDir(opt.serviceDir);
        List<String> siblings = SiblingModules.discover(git, List.of(oursC, theirsC, baseC), dir, opt.siblings);
        if (!siblings.isEmpty()) log("sibling modules (" + opt.siblings + "): " + siblings);
        opt.phase("reading the three versions from git");
        Snapshot base = loader.load("base", baseC, baseC, dir, siblings);
        Snapshot ours = loader.load("ours", opt.ours, oursC, dir, siblings);
        Snapshot theirs = loader.load("theirs", opt.theirs, theirsC, dir, siblings);
        if (ours.files().keySet().stream().noneMatch(p -> p.endsWith(".java"))
                && theirs.files().keySet().stream().noneMatch(p -> p.endsWith(".java"))) {
            warnings.add("No .java files found under service dir '" + dir + "' in ours or theirs");
        }
        Report r = run(base, ours, theirs);
        r.repo = git.repo().toString();
        return r;
    }

    public Report run(Snapshot base, Snapshot ours, Snapshot theirs) {
        lastBase = base;
        lastOurs = ours;
        lastTheirs = theirs;
        Path tmp;
        try {
            tmp = Files.createTempDirectory("branchdiff-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Workspace wsOurs = new Workspace(ours, tmp, opt.keepTemp);
        Workspace wsTheirs = new Workspace(theirs, tmp, opt.keepTemp);
        try {
            if (opt.useMaven) {
                log("resolving classpath for ours (mvn dependency:build-classpath) ...");
                wsOurs.resolveClasspath(opt.mvnCmd, opt.mavenTimeoutSeconds, opt.verbose);
                log("ours classpath: " + wsOurs.classpathStatus);
                log("resolving classpath for theirs ...");
                wsTheirs.resolveClasspath(opt.mvnCmd, opt.mavenTimeoutSeconds, opt.verbose);
                log("theirs classpath: " + wsTheirs.classpathStatus);
            } else {
                wsOurs.classpathStatus = wsTheirs.classpathStatus = "skipped (--no-maven)";
            }
            if (!wsOurs.classpathOk) warnings.add("ours classpath unavailable (" + firstLine(wsOurs.classpathStatus)
                    + "): library types unresolved, more items will be name-matched / UNCERTAIN");
            if (!wsTheirs.classpathOk) warnings.add("theirs classpath unavailable (" + firstLine(wsTheirs.classpathStatus)
                    + "): library types unresolved, more items will be name-matched / UNCERTAIN");
            TypeSolver sOurs = wsOurs.typeSolver(opt.includeTests, warnings);
            TypeSolver sTheirs = wsTheirs.typeSolver(opt.includeTests, warnings);
            log("parsing ...");
            opt.phase("parsing base");
            long tParse = System.nanoTime();
            CodeIndex iBase = CodeIndex.build(base, null, opt.includeTests);
            opt.phase("parsing ours (tenant)");
            CodeIndex iOurs = CodeIndex.build(ours, sOurs, opt.includeTests);
            opt.phase("parsing theirs (pick)");
            CodeIndex iTheirs = CodeIndex.build(theirs, sTheirs, opt.includeTests);
            log("parsed " + (iBase.javaFileCount() + iOurs.javaFileCount() + iTheirs.javaFileCount()) + " java files in "
                    + (System.nanoTime() - tParse) / 1_000_000 + " ms (summary cache: "
                    + com.branchdiff.index.SummaryCache.getDefault().stats() + ")");
            for (CodeIndex i : List.of(iBase, iOurs, iTheirs)) {
                for (String e : i.shadowedSiblingClasses) warnings.add("[" + i.snapshot.label() + "] sibling class ignored, the service declares the same FQN: " + e);
                for (String e : i.parseErrors) warnings.add("[" + i.snapshot.label() + "] parse problem: " + e);
                for (String e : i.config.errors()) warnings.add("[" + i.snapshot.label() + "] config parse problem: " + e);
                for (String e : i.mapperXml.errors()) warnings.add("[" + i.snapshot.label() + "] mapper xml problem: " + e);
            }
            // full change sets include sibling modules (their API changes can break the service);
            // the scoped ones are limited to --service-dir and drive everything that reports theirs' own code
            ChangeSet csTheirsAll = ChangeSet.compute("theirs", iBase, iTheirs);
            ChangeSet csOursAll = ChangeSet.compute("ours", iBase, iOurs);
            ChangeSet csTheirs = csTheirsAll.scoped(iBase, iTheirs);
            ChangeSet csOurs = csOursAll.scoped(iBase, iOurs);
            log("change sets: theirs=" + csTheirs.changes.size() + " ours=" + csOurs.changes.size()
                    + " (incl. siblings: theirs=" + csTheirsAll.changes.size() + " ours=" + csOursAll.changes.size() + ")");

            opt.phase("semantic checks");
            long tFind = System.nanoTime();
            List<Finding> findings = new ArrayList<>();
            DependencyAnalyzer dep = new DependencyAnalyzer(iBase, iOurs, iTheirs);
            findings.addAll(safe("dependency closure", () -> dep.analyze(csTheirs)));
            findings.addAll(safe("spring checks", () -> new SpringAnalyzer(iBase, iOurs, iTheirs).analyze(csTheirsAll)));
            findings.addAll(safe("maven dependencies", () -> PomAnalyzer.analyze(base, ours, theirs)));
            findings.addAll(safe("signature impact", () -> new ImpactAnalyzer(iBase, iOurs, iTheirs, csOursAll).analyze(csTheirsAll)));
            findings.addAll(safe("removed field impact", () -> new FieldImpactAnalyzer(iBase, iOurs, iTheirs, csOursAll).analyze()));
            findings.addAll(safe("value changes", () -> new ValueChangeAnalyzer(iBase, iOurs, iTheirs).analyze()));
            BothModifiedAnalyzer both = new BothModifiedAnalyzer(base, ours, theirs);
            findings.addAll(safe("both-modified", () -> both.analyze(csTheirs, csOurs)));
            findings.sort(Comparator.comparing((Finding f) -> f.category).thenComparing(f -> f.severity)
                    .thenComparing(f -> f.confidence).thenComparing(f -> f.symbol));

            for (int i = 0; i < findings.size(); i++) findings.get(i).id = i + 1;
            log("findings in " + (System.nanoTime() - tFind) / 1_000_000 + " ms");

            Report r = new Report();
            r.generatedAt = OffsetDateTime.now().toString();
            r.serviceDir = ours.serviceDir().isEmpty() ? "." : ours.serviceDir();
            r.refs.put("ours", ours.ref() + " (" + ours.commit() + ")");
            r.refs.put("theirs", theirs.ref() + " (" + theirs.commit() + ")");
            r.refs.put("mergeBase", base.commit());
            r.findings = findings;
            r.warnings = warnings;
            r.predictedTextualConflicts = textualConflicts(base, ours, theirs);
            opt.phase("dependency chains and reverse impact");
            long t0 = System.nanoTime();
            boolean libs = wsOurs.classpathOk && wsTheirs.classpathOk;
            ContextBuilder cb = null;
            try {
                cb = new ContextBuilder(iBase, iOurs, iTheirs, libs);
                if (libs) cb.withJars(new JarArtifacts(wsTheirs.jars), new JarArtifacts(wsOurs.jars));
                r.context = cb.build(findings);
                log("context: " + r.context.nodes.size() + " nodes, " + r.context.chains.size() + " chains in "
                        + (System.nanoTime() - t0) / 1_000_000 + " ms");
            } catch (RuntimeException | StackOverflowError e) {
                warnings.add("context view failed: " + e);
                if (opt.verbose) e.printStackTrace();
            }
            Map<String, Object> s = r.summary;
            s.put("theirsMethodChanges", counts(csTheirs));
            s.put("oursMethodChanges", counts(csOurs));
            s.put("classesAddedByTheirs", csTheirs.addedClasses.size());
            s.put("classesRemovedByTheirs", csTheirs.removedClasses.size());
            Map<String, Integer> byCat = new LinkedHashMap<>();
            for (Finding.Category c : Finding.Category.values()) byCat.put(c.name(), 0);
            int high = 0, uncertain = 0;
            for (Finding f : findings) {
                byCat.merge(f.category.name(), 1, Integer::sum);
                if (f.severity == Finding.Severity.HIGH) high++;
                if (f.uncertain()) uncertain++;
            }
            s.put("findingsByCategory", byCat);
            s.put("findingsTotal", findings.size());
            s.put("highSeverity", high);
            s.put("uncertain", uncertain);
            s.put("identicalChangesOnBothSides", both.identicalChanges);
            s.put("filesWithPredictedTextualConflicts", r.predictedTextualConflicts.size());
            Map<String, Object> n = r.analysisNotes;
            n.put("oursClasspath", wsOurs.classpathStatus);
            n.put("theirsClasspath", wsTheirs.classpathStatus);
            n.put("theirsMethodsAnalyzed", dep.stats.methodsAnalyzed);
            n.put("callsResolvedBySymbolSolver", dep.stats.callsResolved);
            n.put("callsNameMatchedFallback", dep.stats.callsFallback);
            n.put("callsToExternalLibraries", dep.stats.callsExternal);
            n.put("javaFiles", ordered("base", iBase.javaFileCount(), "ours", iOurs.javaFileCount(), "theirs", iTheirs.javaFileCount()));
            if (!ours.siblingDirs().isEmpty()) {
                n.put("siblingModules", ours.siblingDirs());
                n.put("siblingJavaFiles", ordered("base", iBase.siblingJavaFileCount(), "ours", iOurs.siblingJavaFileCount(),
                        "theirs", iTheirs.siblingJavaFileCount()));
            }
            n.put("siblingMode", opt.siblings.name().toLowerCase());
            n.put("configFiles", ordered("ours", iOurs.config.files(), "theirs", iTheirs.config.files()));
            n.put("mapperXmlNamespaces", ordered("ours", iOurs.mapperXml.namespaces().size(), "theirs", iTheirs.mapperXml.namespaces().size()));
            r.changeSets.put("theirs", listChanges(csTheirs));
            r.changeSets.put("ours", listChanges(csOurs));
            if (after != null) {
                if (cb == null) cb = new ContextBuilder(iBase, iOurs, iTheirs, libs);
                opt.phase("explaining conflict hunks");
                after.accept(new Context(iBase, iOurs, iTheirs, cb, csTheirs, csTheirsAll, findings, r, warnings, libs));
            }
            return r;
        } finally {
            // the symbol solver keeps one facade (with its caches and type solver) per type solver in a static map
            com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.clearInstances();
            log("AST cache: " + com.branchdiff.index.AstCache.stats());
            // the cached ASTs of this run reference its type solvers (temp sources, jar indexes): drop them
            com.branchdiff.index.AstCache.clear();
            wsOurs.close();
            wsTheirs.close();
            if (!opt.keepTemp) tmp.toFile().delete();
            else log("kept temp dir " + tmp);
        }
    }

    /** a map in the given key order (Map.of iterates in a different order on every JVM run) */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private List<Finding> safe(String what, Supplier<List<Finding>> s) {
        try {
            return s.get();
        } catch (RuntimeException | StackOverflowError e) {
            warnings.add(what + " failed: " + e);
            if (opt.verbose) e.printStackTrace();
            return List.of();
        }
    }

    private static Map<String, Integer> counts(ChangeSet cs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        cs.counts().forEach((k, v) -> m.put(k.name(), v));
        return m;
    }

    private static List<Map<String, String>> listChanges(ChangeSet cs) {
        List<Map<String, String>> res = new ArrayList<>();
        for (MethodChange c : cs.changes) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("kind", c.kind.name());
            m.put("method", c.describe());
            m.put("location", c.newMethod != null ? c.newMethod.location() : c.oldMethod.location() + " (base)");
            res.add(m);
        }
        return res;
    }

    static List<String> textualConflicts(Snapshot base, Snapshot ours, Snapshot theirs) {
        List<String> res = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>(ours.files().keySet());
        paths.addAll(theirs.files().keySet());
        for (String p : paths) {
            String b = base.files().get(p), o = ours.files().get(p), t = theirs.files().get(p);
            if (Objects.equals(o, t) || Objects.equals(b, o) || Objects.equals(b, t)) continue;
            int c = MergeFile.conflicts(b, o, t);
            if (c != 0) res.add(p + (c > 0 ? " (" + c + " hunk(s))" : " (merge-file failed)"));
        }
        return res;
    }
}
