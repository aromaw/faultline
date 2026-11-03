package com.branchdiff;

import com.branchdiff.analysis.Analyzer;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class TestSupport {
    private TestSupport() {
    }

    /**
     * Builds the sample repository with scripts/create-sample.sh into {@code repo}. Skipped (assumption) only when
     * bash, git or python3 is missing; a script that runs and fails is a test failure, so a platform difference (BSD
     * sed on macOS) cannot silently skip the sample-based tests.
     */
    public static void createSample(java.nio.file.Path repo) throws java.io.IOException, InterruptedException {
        java.nio.file.Path script = java.nio.file.Path.of("scripts/create-sample.sh").toAbsolutePath();
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(script), script.toString());
        Process tools = new ProcessBuilder("bash", "-c", "command -v git && command -v python3")
                .redirectErrorStream(true).start();
        tools.getInputStream().readAllBytes();
        org.junit.jupiter.api.Assumptions.assumeTrue(tools.waitFor() == 0, "the sample needs bash, git and python3");
        Process p = new ProcessBuilder("bash", script.toString(), repo.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        org.junit.jupiter.api.Assertions.assertEquals(0, p.waitFor(), "scripts/create-sample.sh failed:\n" + out);
    }

    /** files given as alternating path, content */
    public static Snapshot snap(String label, String... pathAndContent) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < pathAndContent.length; i += 2) files.put(pathAndContent[i], pathAndContent[i + 1]);
        return new Snapshot(label, label, label + "-sha", "", files);
    }

    public static Snapshot with(Snapshot s, String label, String... pathAndContent) {
        Map<String, String> files = new LinkedHashMap<>(s.files());
        for (int i = 0; i < pathAndContent.length; i += 2) {
            if (pathAndContent[i + 1] == null) files.remove(pathAndContent[i]);
            else files.put(pathAndContent[i], pathAndContent[i + 1]);
        }
        return new Snapshot(label, label, label + "-sha", s.serviceDir(), files, s.siblingDirs(), s.siblingFiles());
    }

    /** same snapshot with sibling-module sources (alternating path, content; null content removes) */
    public static Snapshot withSiblings(Snapshot s, String label, String... pathAndContent) {
        Map<String, String> files = new LinkedHashMap<>(s.siblingFiles());
        for (int i = 0; i < pathAndContent.length; i += 2) {
            if (pathAndContent[i + 1] == null) files.remove(pathAndContent[i]);
            else files.put(pathAndContent[i], pathAndContent[i + 1]);
        }
        return new Snapshot(label, label, label + "-sha", s.serviceDir(), s.files(), List.of("common"), files);
    }

    public static Report analyze(Snapshot base, Snapshot ours, Snapshot theirs) {
        Options o = new Options();
        o.useMaven = false;
        return new Analyzer(o).run(base, ours, theirs);
    }

    public static List<Finding> of(Report r, Finding.Category c) {
        return r.findings.stream().filter(f -> f.category == c).collect(Collectors.toList());
    }

    public static String dump(Report r) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : r.findings) {
            sb.append(f.category).append(' ').append(f.severity).append(' ').append(f.confidence).append(' ')
                    .append(f.status).append(' ').append(f.kind).append(' ').append(f.symbol).append(' ').append(f.origin).append('\n');
        }
        return sb.toString();
    }

    public static String java(String pkg, String body) {
        return "package " + pkg + ";\n" + body;
    }
}
