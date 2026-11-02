package com.branchdiff;

import com.branchdiff.analysis.Analyzer;
import com.branchdiff.conflict.ConflictCommand;
import com.branchdiff.conflict.ConflictMarkdown;
import com.branchdiff.git.SiblingModules;
import com.branchdiff.report.Finding;
import com.branchdiff.report.MarkdownRenderer;
import com.branchdiff.report.Report;
import com.branchdiff.report.html.HtmlRenderer;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class Main {
    private static final String USAGE = """
            faultline - explains cherry-pick / merge conflicts of Java (Spring / MyBatis / Maven) code: where each conflict
            hunk is, why it conflicts (what mainline did to that code since the tenant forked), what taking theirs needs and
            whether the tenant has it, and what keeping ours breaks (tenant callers up to HTTP endpoints / jobs).

            Usage:
              faultline conflicts [--repo <path>] [options]
                  analyze the repository's current cherry-pick / merge / revert (CHERRY_PICK_HEAD / MERGE_HEAD /
                  REVERT_HEAD and the unmerged index entries, stages 1/2/3). This is the default when no --theirs is given.
              faultline conflicts --onto <tenant-ref> --pick <commit | c1,c2,... | A..B> [options]
                  simulate the cherry-pick offline (git merge-tree, the same merge cherry-pick runs) without touching the
                  worktree, the index or any ref, then analyze it
              faultline branch --theirs <ref> [--ours <ref>] [options]
                  secondary mode: compare whole branches (what theirs changed that ours lacks or is inconsistent with)

            Conflict options:
              --mainline <ref>       mainline branch; the tenant's fork point is merge-base(tenant, mainline)
                                     (default: merge-base(tenant, picked commit's parent))
              --onto <ref>           tenant ref for --pick (default: HEAD)
              --pick <spec>          commit(s) to pick: one commit, a comma separated list, or a range A..B

            Common options:
              --repo <path>          git repository (default: .)
              --service-dir <dir>    sub-directory to analyze (default: repo root; conflict mode works best on the root)
              --format md|json|html  report format (default: md)
              --json | --html        shorthand for --format json / html (html: one self-contained file, conflict hunks first,
                                     tabs Conflict / Why / Take theirs / Take ours / Checklist, IDE-like ours|base|theirs view)
              --output, -o <file>    write report to file instead of stdout
              --no-maven             do not run 'mvn dependency:build-classpath' (faster, more UNCERTAIN items)
              --mvn <cmd>            maven executable (default: mvn)
              --maven-timeout <sec>  timeout per classpath resolution (default: 600)
              --include-tests        also analyze src/test/java
              --include-siblings [deps|all|none], --no-siblings
                                     (branch mode) index sibling modules of the same repo for lookups
              --keep-temp            keep the temporary materialized sources (debugging)
              --fail-on-findings     exit with code 2 if any HIGH certain finding exists (or, in conflict mode, any
                                     symbol the pick needs is missing in the tenant)
              -v, --verbose          progress on stderr
              -h, --help

            Branch options:
              --ours <ref>           our branch/commit (default: HEAD)
              --theirs <ref>         branch/commit being migrated into ours (required in branch mode)

            The worktree, index and refs are never modified. The offline simulation only adds unreferenced objects
            (merge-tree / commit-tree) to the object database.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(64);
            return;
        }
        if (o == null) {
            System.out.println(USAGE);
            return;
        }
        try {
            Report r;
            Analyzer analyzer;
            Map<String, String[]> conflictFiles = Map.of();
            if (o.command.equals("conflicts")) {
                ConflictCommand cc = new ConflictCommand();
                r = cc.run(o);
                analyzer = cc.analyzer;
                conflictFiles = cc.contents;
            } else {
                analyzer = new Analyzer(o);
                r = analyzer.runFromGit();
            }
            String out = switch (o.format) {
                case "json" -> toJson(r);
                case "html" -> HtmlRenderer.render(r, HtmlRenderer.source(analyzer.lastBase, analyzer.lastOurs,
                        analyzer.lastTheirs, analyzer.lastGit), conflictFiles);
                default -> r.conflicts != null ? ConflictMarkdown.render(r) : MarkdownRenderer.render(r);
            };
            if (o.output != null) {
                if (o.output.toAbsolutePath().getParent() != null) Files.createDirectories(o.output.toAbsolutePath().getParent());
                Files.writeString(o.output, out, StandardCharsets.UTF_8);
                System.err.println("report written to " + o.output.toAbsolutePath() + (r.conflicts != null ? " ("
                        + r.conflicts.summary.get("conflictHunks") + " conflict hunks in " + r.conflicts.files.size() + " files, " : " (")
                        + r.findings.size() + " findings, " + r.summary.get("highSeverity") + " high)");
            } else {
                System.out.print(out);
            }
            boolean conflictGaps = r.conflicts != null && r.conflicts.symbols.values().stream().anyMatch(n -> !"IN_PICK".equals(n.origin));
            if (o.failOnFindings && (conflictGaps || r.findings.stream().anyMatch(f -> f.severity == Finding.Severity.HIGH && !f.uncertain()))) {
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            if (o.verbose) e.printStackTrace();
            System.exit(1);
        }
    }

    public static String toJson(Report r) throws Exception {
        ObjectMapper m = new ObjectMapper();
        m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        m.enable(SerializationFeature.INDENT_OUTPUT);
        return m.writeValueAsString(r);
    }

    static Options parse(String[] args) {
        Options o = new Options();
        int first = 0;
        if (args.length > 0 && (args[0].equals("conflicts") || args[0].equals("branch"))) {
            o.command = args[0];
            first = 1;
        }
        for (int i = first; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--include-siblings=")) {
                o.siblings = SiblingModules.Mode.parse(a.substring("--include-siblings=".length()));
                continue;
            }
            switch (a) {
                case "-h", "--help" -> {
                    return null;
                }
                case "--repo" -> o.repo = Path.of(val(args, ++i, a));
                case "--ours" -> o.ours = val(args, ++i, a);
                case "--theirs" -> o.theirs = val(args, ++i, a);
                case "--service-dir" -> o.serviceDir = val(args, ++i, a);
                case "--format" -> o.format = val(args, ++i, a);
                case "--json" -> o.format = "json";
                case "--html" -> o.format = "html";
                case "--output", "-o" -> o.output = Path.of(val(args, ++i, a));
                case "--no-maven" -> o.useMaven = false;
                case "--mvn" -> o.mvnCmd = val(args, ++i, a);
                case "--maven-timeout" -> o.mavenTimeoutSeconds = Integer.parseInt(val(args, ++i, a));
                case "--include-tests" -> o.includeTests = true;
                case "--include-siblings" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) o.siblings = SiblingModules.Mode.parse(args[++i]);
                    else o.siblings = SiblingModules.Mode.DEPS;
                }
                case "--no-siblings" -> o.siblings = SiblingModules.Mode.NONE;
                case "--keep-temp" -> o.keepTemp = true;
                case "--fail-on-findings" -> o.failOnFindings = true;
                case "-v", "--verbose" -> o.verbose = true;
                case "--onto" -> o.onto = val(args, ++i, a);
                case "--pick" -> o.pick = val(args, ++i, a);
                case "--mainline" -> o.mainline = val(args, ++i, a);
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        if (o.command == null) o.command = o.theirs != null ? "branch" : "conflicts";
        if (o.command.equals("branch") && o.theirs == null) throw new IllegalArgumentException("--theirs is required in branch mode");
        if (o.command.equals("conflicts") && o.theirs != null) {
            throw new IllegalArgumentException("--theirs belongs to branch mode; in conflict mode use --onto/--pick or a repository mid cherry-pick");
        }
        if (!o.format.equals("md") && !o.format.equals("json") && !o.format.equals("html")) {
            throw new IllegalArgumentException("--format must be md, json or html");
        }
        return o;
    }

    private static String val(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " needs a value");
        return args[i];
    }
}
