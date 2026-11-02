package com.branchdiff.git;

import com.branchdiff.model.Snapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the relevant files of a commit (java sources, poms, spring config, mapper xml) without a checkout. */
public class SnapshotLoader {
    private final GitRunner git;

    public SnapshotLoader(GitRunner git) {
        this.git = git;
    }

    public static boolean isRelevant(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".properties") || lower.endsWith(".xml");
    }

    public Snapshot load(String label, String ref, String commit, String serviceDir) {
        return load(label, ref, commit, serviceDir, List.of());
    }

    /**
     * Also loads {@code <sibling>/src/main/java/**.java} (and the sibling's pom.xml, used to keep the temporary
     * classpath resolution working) for each sibling module dir.
     */
    public Snapshot load(String label, String ref, String commit, String serviceDir, List<String> siblingDirs) {
        String dir = normalizeDir(serviceDir);
        Map<String, String> blobs = git.listBlobs(commit, dir);
        Map<String, String> wanted = new LinkedHashMap<>();
        blobs.forEach((path, sha) -> {
            if (isRelevant(path)) wanted.put(path, sha);
        });
        // ancestor poms (parent pom resolution for `mvn dependency:build-classpath`)
        if (!dir.isEmpty()) {
            String prefix = "";
            String[] parts = dir.split("/");
            for (int i = 0; i < parts.length; i++) {
                String pom = prefix + "pom.xml";
                Map<String, String> p = git.listBlobs(commit, pom);
                if (p.containsKey(pom)) wanted.put(pom, p.get(pom));
                prefix = prefix + parts[i] + "/";
            }
        }
        Map<String, String> files = git.readBlobs(wanted);
        Map<String, String> sibWanted = new LinkedHashMap<>();
        for (String sib : siblingDirs) {
            String prefix = sib.isEmpty() ? "" : sib + "/";
            git.listBlobs(commit, prefix + SiblingModules.MAIN_JAVA).forEach((path, sha) -> {
                if (path.endsWith(".java") && !files.containsKey(path)) sibWanted.put(path, sha);
            });
            Map<String, String> pom = git.listBlobs(commit, prefix + "pom.xml");
            if (pom.containsKey(prefix + "pom.xml") && !files.containsKey(prefix + "pom.xml")) {
                sibWanted.put(prefix + "pom.xml", pom.get(prefix + "pom.xml"));
            }
        }
        Map<String, String> sibFiles = git.readBlobs(sibWanted);
        return new Snapshot(label, ref, commit, dir, files, List.copyOf(siblingDirs), sibFiles);
    }

    public static String normalizeDir(String serviceDir) {
        if (serviceDir == null) return "";
        String d = serviceDir.replace('\\', '/').trim();
        while (d.startsWith("./")) d = d.substring(2);
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d.equals(".") ? "" : d;
    }
}
