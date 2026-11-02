package com.branchdiff.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Whole analysis result (serialized to JSON as-is, rendered to Markdown by {@link MarkdownRenderer}). */
public final class Report {
    public String tool = "branch-diff-tool 0.1.0";
    public String generatedAt;
    public String repo;
    public String serviceDir;
    public Map<String, String> refs = new LinkedHashMap<>();
    public Map<String, Object> summary = new LinkedHashMap<>();
    public Map<String, Object> analysisNotes = new LinkedHashMap<>();
    public List<String> warnings = new ArrayList<>();
    public List<Finding> findings = new ArrayList<>();
    /** method change sets: side -> list of {kind, symbol, location} */
    public Map<String, List<Map<String, String>>> changeSets = new LinkedHashMap<>();
    public List<String> predictedTextualConflicts = new ArrayList<>();
    /** dependency / impact trees, chains and port checklists (null when context building failed) */
    public ContextModel context;
}
