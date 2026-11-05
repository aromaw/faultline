package com.branchdiff.conflict;

import com.branchdiff.report.ContextModel.TreeNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of the cherry-pick / merge conflict analysis. Public fields so Jackson serializes it as-is.
 * <p>
 * Sides: {@code ours} = the tenant HEAD the pick is applied to, {@code theirs} = the picked commit, {@code base} =
 * the picked commit's parent (for a merge: the merge-base). {@code fork} = merge-base of the tenant and the mainline,
 * i.e. where the tenant branched off; "mainline history" is fork..base, "tenant history" is fork..ours.
 */
public final class ConflictModel {
    /** "cherry-pick", "merge", "revert" (live repository state) or "simulated" (--onto/--pick) */
    public String mode;
    public String repo;
    public Commit ours;
    public Commit theirs;
    public Commit base;
    public Commit fork;
    public String mainline;
    /** commits being picked, in application order */
    public List<Commit> picks = new ArrayList<>();
    /** commits still to be applied after the current one (sequencer todo) */
    public List<String> pending = new ArrayList<>();
    public int mainlineCommitsInWindow;
    public int tenantCommitsSinceFork;
    public List<ConflictFile> files = new ArrayList<>();
    /** files the pick changed that merged without textual conflict */
    public List<String> cleanFiles = new ArrayList<>();
    /** what the cleanly merged files of the pick need from the tenant (only files with something missing) */
    public Map<String, TakeTheirs> cleanNeeds = new LinkedHashMap<>();
    /** finding ids (of the regular report) located in files that merged cleanly */
    public List<Integer> cleanFileFindings = new ArrayList<>();
    public List<ChecklistItem> checklist = new ArrayList<>();
    /** node key -> where a symbol the pick needs comes from (every MISSING / CHANGED symbol of the take-theirs trees) */
    public Map<String, Need> symbols = new LinkedHashMap<>();
    /** commits from mainline history the pick depends on, oldest first */
    public List<CommitNeed> bringAlong = new ArrayList<>();
    public List<String> messages = new ArrayList<>();
    public List<String> limitations = new ArrayList<>();
    public Map<String, Object> summary = new LinkedHashMap<>();

    public record Commit(String sha, String date, String author, String subject) {
        public String shortSha() {
            return sha == null ? "" : sha.substring(0, Math.min(10, sha.length()));
        }
    }

    /** A 1-based inclusive line range; {@code end = start - 1} means empty (insertion point before {@code start}). */
    public record Range(int start, int end) {
        public boolean empty() {
            return end < start;
        }

        public int size() {
            return Math.max(0, end - start + 1);
        }

        public boolean overlaps(int s, int e) {
            if (empty()) return start >= s && start <= e + 1;
            return s <= end && e >= start;
        }

        @Override
        public String toString() {
            return empty() ? "(empty before " + start + ")" : start == end ? String.valueOf(start) : start + "-" + end;
        }
    }

    public static final class ConflictFile {
        public String path;
        /** path of the file in each side when it differs (renames) */
        public Map<String, String> paths = new LinkedHashMap<>();
        /** CONTENT, DELETED_BY_US (absent in ours), DELETED_BY_THEM, ADDED_BY_BOTH, ADDED_BY_US, ADDED_BY_THEM */
        public String kind;
        /** step (1-based index into picks) whose application produced this conflict */
        public int step = 1;
        /** stage -> blob sha (1 = base, 2 = ours, 3 = theirs) */
        public Map<String, String> stages = new LinkedHashMap<>();
        /** plain-language explanation of the file-level situation (e.g. "file added on mainline by abc (date)") */
        public String explanation;
        /** set when the versions use different line endings (hunks are then computed ignoring CRs) */
        public String eol;
        /** what the lines of the pick that git merged automatically in this file need (outside every hunk) */
        public TakeTheirs merged;
        public List<Event> fileHistory = new ArrayList<>();
        public List<Hunk> hunks = new ArrayList<>();
        /** key of the embedded file in the HTML report */
        public String viewKey;
    }

    /** A member (class / method / field / import block / xml statement / config key / pom dependency ...) of one side. */
    public record Member(String kind, String id, String label, String path, int start, int end) {
    }

    public static final class Hunk {
        public String id;
        public String path;
        public int index;
        public int step = 1;
        public Range ours, base, theirs;
        public List<String> oursText = new ArrayList<>(), baseText = new ArrayList<>(), theirsText = new ArrayList<>();
        public Map<String, List<Member>> members = new LinkedHashMap<>();
        /** one-paragraph answer to "why does this conflict" */
        public String why;
        /** what mainline did to this code between the fork and the picked commit's parent */
        public List<Event> mainline = new ArrayList<>();
        /** what the tenant did to this code since the fork */
        public List<Event> tenant = new ArrayList<>();
        /** per-line blame of the base side (mainline commits that wrote those lines) */
        public List<BlameRun> baseBlame = new ArrayList<>();
        public List<BlameRun> oursBlame = new ArrayList<>();
        public TakeTheirs takeTheirs = new TakeTheirs();
        public TakeOurs takeOurs = new TakeOurs();
        /** regular findings related to this hunk */
        public List<Integer> findings = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
        /** how to resolve this hunk, derived from everything above */
        public Suggestion suggestion;
    }

    /**
     * A suggested resolution of one hunk. {@code choice}: THEIRS (take the pick's side), OURS (keep the tenant's side),
     * BOTH (a combination of the two, given in {@code resolution}) or MANUAL (needs a person: both sides changed the
     * same lines). For a file-level conflict (deleted on one side) THEIRS / OURS mean the pick's / the tenant's state of
     * the whole file, and {@code resolution} is null.
     */
    public static final class Suggestion {
        public String choice;
        /** HIGH (nothing found against it), MEDIUM (works after the follow-ups, or a heuristic), LOW (a starting point) */
        public String confidence;
        /** one sentence: what to do */
        public String headline;
        /** why this choice */
        public List<String> reasons = new ArrayList<>();
        /** what else to do for the choice to build and run (bring symbols, update callers, retest routes) */
        public List<String> followUps = new ArrayList<>();
        /** the lines to put in place of the conflict, when they follow mechanically from the choice (null otherwise) */
        public List<String> resolution;
    }

    /** One change to a member (or file) in one commit. */
    public static final class Event {
        public Commit commit;
        /** ADDED / DELETED / MODIFIED / SIGNATURE_CHANGED / RENAMED / MOVED / FILE_ADDED / FILE_DELETED / FILE_RENAMED */
        public String kind;
        public String member;
        public String from;
        public String to;
        public String detail;

        public Event() {
        }

        public Event(Commit commit, String kind, String member, String from, String to, String detail) {
            this.commit = commit;
            this.kind = kind;
            this.member = member;
            this.from = from;
            this.to = to;
            this.detail = detail;
        }
    }

    /** consecutive lines of one side written by the same commit */
    public record BlameRun(int start, int end, Commit commit, boolean boundary) {
    }

    /** One symbol the theirs side of a hunk needs, and its state in ours. */
    public static final class Need {
        /** context node key (M:/F:/T:/SQL:/CFG:/MVN:/BEAN:) */
        public String node;
        public String kind;
        public String symbol;
        /** line in theirs where the hunk uses it */
        public int line;
        /** PRESENT / MISSING / CHANGED / DIFFERS / UNKNOWN */
        public String ours;
        public String oursLocation;
        public String theirsLocation;
        /** ADDED_ON_MAINLINE / RENAMED_ON_MAINLINE / SIGNATURE_CHANGED_ON_MAINLINE / DELETED_BY_TENANT / IN_PICK / ... */
        public String origin;
        /** the name/signature the tenant still has, when mainline renamed or re-signed it */
        public String tenantName;
        public String tenantLocation;
        public List<Commit> introducedBy = new ArrayList<>();
        public String detail;
        public boolean uncertain;
    }

    public static final class TakeTheirs {
        public List<Need> needs = new ArrayList<>();
        /** imports theirs removes from the hunk but re-adds elsewhere in the file ("old -> new @line") */
        public List<String> movedImports = new ArrayList<>();
        /** transitive dependency trees (depth 4) of the theirs members of the hunk; nodes are in the report's context */
        public List<TreeNode> trees = new ArrayList<>();
        public String summary;
    }

    public static final class EntryPoint {
        /** HTTP / SCHEDULED / LISTENER / STARTUP / MAIN */
        public String type;
        /** e.g. "PUT /system/role" or "cron=0 0 1 * * ?" */
        public String route;
        public String node;
        public String location;
        public String permission;
        /** node keys from the entry point down to the affected symbol */
        public List<String> path = new ArrayList<>();
    }

    /** theirs code outside this hunk (in the picked change) that uses something only the theirs side of the hunk provides */
    public record Orphan(String node, String usedBy, String location, String note) {
    }

    public static final class TakeOurs {
        /** ours-side symbols of the hunk */
        public List<String> symbols = new ArrayList<>();
        /** reverse impact trees in ours (callers / users) */
        public List<TreeNode> impact = new ArrayList<>();
        public List<EntryPoint> entryPoints = new ArrayList<>();
        public List<Orphan> orphans = new ArrayList<>();
        /** what the theirs side of the hunk changes that would be lost */
        public List<String> lost = new ArrayList<>();
        public String summary;
    }

    public record ChecklistItem(int step, String action, String target, String node, String why, List<String> hunks,
                                List<String> commits) {
    }

    /** a mainline commit to bring along (or to port by hand) because the pick depends on what it introduced */
    public record CommitNeed(Commit commit, List<String> provides, List<String> neededBy, boolean inTenant) {
    }
}
