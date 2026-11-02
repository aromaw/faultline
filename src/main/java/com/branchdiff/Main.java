package com.branchdiff;

import com.branchdiff.analysis.Analyzer;
import com.branchdiff.git.SiblingModules;
import com.branchdiff.report.Finding;
import com.branchdiff.report.MarkdownRenderer;
import com.branchdiff.report.Report;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private static final String USAGE = """
            branch-diff-tool - find code that 'theirs' changed and 'ours' is missing or inconsistent with
            (semantic / dependency gaps that plain git diff and git merge do not report).

            Usage:
              java -jar branch-diff-tool.jar --theirs <ref> [options]

            Options:
              --repo <path>          git repository (default: .)
              --ours <ref>           our branch/commit (default: HEAD)
              --theirs <ref>         branch/commit being migrated into ours (required)
              --service-dir <dir>    service sub-directory inside the repo (default: repo root)
              --format md|json       report format (default: md)
              --json                 shorthand for --format json
              --output, -o <file>    write report to file instead of stdout
              --no-maven             do not run 'mvn dependency:build-classpath' (faster, more UNCERTAIN items)
              --mvn <cmd>            maven executable (default: mvn)
              --maven-timeout <sec>  timeout per classpath resolution (default: 600)
              --include-tests        also analyze src/test/java
              --include-siblings [deps|all|none]
                                     index sibling modules of the same repo so that their classes are checked
                                     instead of being treated as external libraries (default: deps = modules the
                                     service pom depends on, transitively; all = every <dir>/src/main/java in the
                                     repo; bare flag = deps). Findings stay limited to --service-dir.
              --no-siblings          same as --include-siblings none
              --keep-temp            keep the temporary materialized sources (debugging)
              --fail-on-findings     exit with code 2 if any HIGH certain finding exists
              -v, --verbose          progress on stderr
              -h, --help

            The repository is only read (rev-parse, merge-base, ls-tree, cat-file); the worktree, index and refs are
            never touched.
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
            Report r = new Analyzer(o).runFromGit();
            String out = o.format.equals("json") ? toJson(r) : MarkdownRenderer.render(r);
            if (o.output != null) {
                if (o.output.toAbsolutePath().getParent() != null) Files.createDirectories(o.output.toAbsolutePath().getParent());
                Files.writeString(o.output, out, StandardCharsets.UTF_8);
                System.err.println("report written to " + o.output.toAbsolutePath() + " (" + r.findings.size() + " findings, "
                        + r.summary.get("highSeverity") + " high)");
            } else {
                System.out.print(out);
            }
            if (o.failOnFindings && r.findings.stream().anyMatch(f -> f.severity == Finding.Severity.HIGH && !f.uncertain())) {
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
        for (int i = 0; i < args.length; i++) {
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
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        if (o.theirs == null) throw new IllegalArgumentException("--theirs is required");
        if (!o.format.equals("md") && !o.format.equals("json")) throw new IllegalArgumentException("--format must be md or json");
        return o;
    }

    private static String val(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " needs a value");
        return args[i];
    }
}
