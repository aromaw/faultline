package com.branchdiff.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Code-context view of a report: a graph of the symbols the findings touch (methods, fields, types, beans, config
 * keys, mapper statements, library types, Maven dependencies), per-finding dependency trees (what the finding needs,
 * transitively) and reverse-impact trees (what in ours depends on it), and chains that group findings belonging to
 * the same dependency chain together with an ordered port checklist. Public fields so Jackson serializes it as-is.
 */
public final class ContextModel {
    /** Node kinds. */
    public static final String CLASS = "class", METHOD = "method", CONSTRUCTOR = "constructor", FIELD = "field",
            CONSTANT = "constant", BEAN = "bean", CONFIG = "config", MAPPER = "mapper", LIBRARY = "library", MAVEN = "maven";
    /** Status of a node in ours, from the point of view of theirs' code that needs it. */
    public static final String PRESENT = "PRESENT", DIFFERS = "DIFFERS", CHANGED = "CHANGED", MISSING = "MISSING",
            UNKNOWN = "UNKNOWN";

    /** Source range of a node in one version; lines are 1-based and inclusive. */
    public record Loc(String path, int start, int end, int line) {
        public String location() {
            return path + ":" + line;
        }
    }

    /** One symbol. {@code key} is stable across versions, e.g. {@code M:a.b.C#m(String)}. */
    public static final class Node {
        public String key;
        public String kind;
        /** short display name, e.g. {@code TokenService#refreshPermissionByRoleId(Long,SysPermissionService)} */
        public String label;
        /** full symbol */
        public String symbol;
        /** status in ours: PRESENT / DIFFERS (theirs changed it) / CHANGED (incompatible) / MISSING / UNKNOWN */
        public String ours;
        /** what happened since the merge-base, e.g. "theirs added", "theirs changed", "both changed", "unchanged" */
        public String change;
        public String note;
        /** "ours" / "theirs" / "base" -> range of the declaration in that version */
        public Map<String, Loc> loc = new LinkedHashMap<>();
        /** ids of the findings whose subject is this node */
        public List<Integer> findings = new ArrayList<>();

        public boolean needsPort() {
            return MISSING.equals(ours) || CHANGED.equals(ours) || DIFFERS.equals(ours);
        }
    }

    /** One level of a dependency or impact tree. */
    public static final class TreeNode {
        public String node;
        /** where the parent uses this node (forward trees) or where this node uses the parent (impact trees) */
        public String at;
        public boolean uncertain;
        /** this node already appears on the path from the root: not expanded again */
        public boolean cycle;
        /** expansion stopped at the depth limit although the node has further dependencies */
        public boolean truncated;
        /** children dropped because of the per-node fan-out limit */
        public int omitted;
        public List<TreeNode> children = new ArrayList<>();

        public TreeNode() {
        }

        public TreeNode(String node, String at) {
            this.node = node;
            this.at = at;
        }
    }

    public record ChecklistItem(int step, String node, String action, String why) {
    }

    /** Findings that belong to the same dependency chain. */
    public static final class Chain {
        public String id;
        /** "port": theirs' code and what it needs from ours; "impact": ours' code affected by a change in theirs */
        public String direction;
        public String root;
        public List<String> roots = new ArrayList<>();
        public List<Integer> findings = new ArrayList<>();
        /** every node of the chain's trees */
        public List<String> nodes = new ArrayList<>();
        /** dependency edges [from, to] between the chain's nodes: "from" needs / uses "to" */
        public List<List<String>> edges = new ArrayList<>();
        public List<ChecklistItem> checklist = new ArrayList<>();
    }

    /** Context of one finding. */
    public static final class FindingContext {
        public String node;
        public String chain;
        /** what the finding needs (rooted at the theirs code that needs the subject, when known) */
        public List<TreeNode> dependencies = new ArrayList<>();
        /** who in ours depends on the subject */
        public TreeNode impact;
    }

    public int maxDepth;
    public int maxImpactDepth;
    public Map<String, Node> nodes = new LinkedHashMap<>();
    /** finding id -> context */
    public Map<Integer, FindingContext> findings = new LinkedHashMap<>();
    public List<Chain> chains = new ArrayList<>();
    public List<String> limitations = new ArrayList<>();
}
