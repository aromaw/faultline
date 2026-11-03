package com.branchdiff;

import com.branchdiff.git.SiblingModules;

import java.nio.file.Path;

/** Command-line options. */
public final class Options {
    public Path repo = Path.of(".");
    public String ours = "HEAD";
    public String theirs;
    public String serviceDir = "";
    public String format = "md";
    public Path output;
    public boolean useMaven = true;
    public String mvnCmd = "mvn";
    public int mavenTimeoutSeconds = 600;
    public boolean includeTests = false;
    public boolean keepTemp = false;
    public boolean verbose = false;
    public boolean failOnFindings = false;
    /** which sibling modules of the repo to index for lookups (never reported by themselves) */
    public SiblingModules.Mode siblings = SiblingModules.Mode.DEPS;
    /** "conflicts" (cherry-pick / merge conflict analysis) or "branch" (whole-branch comparison) */
    public String command;
    /** conflict mode, offline: tenant ref to simulate the pick onto */
    public String onto;
    /** conflict mode, offline: commit, comma separated commits, or range A..B to pick */
    public String pick;
    /** conflict mode: mainline ref; the tenant's fork point is merge-base(tenant, mainline) */
    public String mainline;
    /** progress messages of a long analysis (the GUI shows them); null = none */
    public java.util.function.Consumer<String> progress;
    /** polled between analysis phases; when it returns true the analysis stops with a CancellationException */
    public java.util.function.BooleanSupplier cancelled;

    /** reports a phase and stops the analysis if it was cancelled */
    public void phase(String what) {
        if (cancelled != null && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("analysis cancelled");
        if (progress != null) progress.accept(what);
    }
}
