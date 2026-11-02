package com.branchdiff.model;

import java.util.List;
import java.util.Map;

/**
 * Contents of one version (base / ours / theirs) of the service directory, read straight from git objects.
 *
 * @param label        "base", "ours" or "theirs"
 * @param ref          the ref the user gave (or the merge-base sha)
 * @param commit       resolved commit sha
 * @param serviceDir   repo-relative service dir ("" = repo root)
 * @param files        repo-relative path -> file content (relevant files of the service dir = the reporting scope)
 * @param siblingDirs  repo-relative dirs of sibling modules whose sources were loaded (may be empty)
 * @param siblingFiles repo-relative path -> content of sibling-module java sources. They are indexed and given to
 *                     the type solver so lookups can see them, but nothing in them is ever reported by itself.
 */
public record Snapshot(String label, String ref, String commit, String serviceDir, Map<String, String> files,
                       List<String> siblingDirs, Map<String, String> siblingFiles) {
    public Snapshot(String label, String ref, String commit, String serviceDir, Map<String, String> files) {
        this(label, ref, commit, serviceDir, files, List.of(), Map.of());
    }
}
