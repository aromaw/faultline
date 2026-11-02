package com.branchdiff.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Maps library classes to the Maven artifact (group:artifact:version) whose jar provides them, using the jar paths
 * resolved by {@code mvn dependency:build-classpath}. Coordinates are read from the local repository layout
 * ({@code .../group/path/artifact/version/artifact-version.jar}); jars outside that layout are ignored.
 */
public final class JarArtifacts {
    /** Coordinates of one artifact. */
    public record Gav(String group, String artifact, String version) {
        public String ga() {
            return group + ":" + artifact;
        }
    }

    private final Map<Path, Gav> jars = new HashMap<>();
    private final Map<String, Gav> byGa = new HashMap<>();
    private final Map<String, Gav> byPackage = new HashMap<>();

    public JarArtifacts(List<Path> jarPaths) {
        for (Path p : jarPaths) {
            Gav g = coordinates(p);
            if (g != null) {
                jars.put(p, g);
                byGa.putIfAbsent(g.ga(), g);
            }
        }
    }

    static Gav coordinates(Path jar) {
        Path abs = jar.toAbsolutePath().normalize();
        int n = abs.getNameCount();
        if (n < 4) return null;
        String file = abs.getFileName().toString();
        String version = abs.getName(n - 2).toString();
        String artifact = abs.getName(n - 3).toString();
        if (!file.startsWith(artifact + "-" + version) || !file.endsWith(".jar")) return null;
        int repo = -1;
        for (int i = 0; i < n - 3; i++) if (abs.getName(i).toString().equals("repository")) repo = i;
        if (repo < 0 || repo + 1 >= n - 3) return null;
        StringBuilder group = new StringBuilder();
        for (int i = repo + 1; i < n - 3; i++) group.append(group.length() > 0 ? "." : "").append(abs.getName(i));
        return new Gav(group.toString(), artifact, version);
    }

    /** The artifact whose jar contains {@code fqn} (a top-level or nested class name), or null. */
    public Gav artifactOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        if (dot < 0) return null;
        String pkg = fqn.substring(0, dot);
        if (byPackage.containsKey(pkg)) return byPackage.get(pkg);
        // try progressively shorter names so nested classes (a.b.Outer.Inner) are found as a/b/Outer$Inner.class
        Gav found = null;
        String[] parts = fqn.split("\\.");
        outer:
        for (int split = parts.length - 1; split >= 1 && found == null; split--) {
            StringBuilder e = new StringBuilder();
            for (int i = 0; i < parts.length; i++) e.append(i == 0 ? "" : i < split ? "/" : i == split ? "/" : "$").append(parts[i]);
            String entry = e + ".class";
            for (Map.Entry<Path, Gav> j : jars.entrySet()) {
                try (ZipFile z = new ZipFile(j.getKey().toFile())) {
                    if (z.getEntry(entry) != null) {
                        found = j.getValue();
                        break outer;
                    }
                } catch (IOException ignored) {
                    // unreadable jar: skip
                }
            }
        }
        byPackage.put(pkg, found);
        return found;
    }

    /** The resolved coordinates of {@code group:artifact}, or null when it is not on this classpath. */
    public Gav get(String ga) {
        return byGa.get(ga);
    }
}
