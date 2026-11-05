package com.branchdiff.conflict;

import com.branchdiff.conflict.ConflictModel.ConflictFile;
import com.branchdiff.conflict.ConflictModel.EntryPoint;
import com.branchdiff.conflict.ConflictModel.Event;
import com.branchdiff.conflict.ConflictModel.Hunk;
import com.branchdiff.conflict.ConflictModel.Need;
import com.branchdiff.conflict.ConflictModel.Suggestion;
import com.branchdiff.report.ContextModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Suggests how to resolve a conflict hunk from what the analysis found: whitespace-only differences, edits that sit next
 * to each other but do not overlap (combined line by line), the tenant's own edits, the symbols taking theirs needs, the
 * code of the pick that keeping ours leaves dangling, and the tenant callers of what theirs removes.
 */
public final class Suggestions {
    public static final String THEIRS = "THEIRS", OURS = "OURS", BOTH = "BOTH", MANUAL = "MANUAL";
    public static final String HIGH = "HIGH", MEDIUM = "MEDIUM", LOW = "LOW";

    private Suggestions() {
    }

    /**
     * What the analyzer knows beyond the hunk.
     *
     * @param mergedMissing      symbols the automatically merged lines of the file need that the tenant lacks (0 except on the
     *                           file's first hunk, so it is reported once)
     * @param theirsRemovesUsed  ours-side members of the hunk that theirs no longer has but tenant code still uses ("label (N users)")
     * @param fileOrigin         for a file the tenant lacks: ADDED_ON_MAINLINE, RENAMED_ON_MAINLINE or DELETED_BY_TENANT (else null)
     * @param tenantPath         where the tenant keeps the file when mainline renamed it (else null)
     */
    public record Facts(int mergedMissing, List<String> theirsRemovesUsed, String fileOrigin, String tenantPath) {
        public static final Facts NONE = new Facts(0, List.of(), null, null);
    }

    public static Suggestion suggest(Hunk h, ConflictFile f, Facts x) {
        Suggestion s = switch (f.kind == null ? "CONTENT" : f.kind) {
            case "DELETED_BY_US" -> deletedByUs(h, f, x);
            case "DELETED_BY_THEM" -> deletedByThem(h, f, x);
            default -> content(h, f, x);
        };
        if (x.mergedMissing() > 0) {
            s.followUps.add("Whatever you choose here, the lines git merged automatically in this file use " + x.mergedMissing()
                    + " symbol(s) the tenant lacks (see BRING / ADAPT).");
        }
        retest(h, s);
        return s;
    }

    // ------------------------------------------------------------------ file-level conflicts

    private static Suggestion deletedByUs(Hunk h, ConflictFile f, Facts x) {
        String origin = x.fileOrigin() == null ? "" : x.fileOrigin();
        switch (origin) {
            case "RENAMED_ON_MAINLINE" -> {
                Suggestion s = of(MANUAL, MEDIUM, "Port the pick's change to the tenant's " + (x.tenantPath() != null ? x.tenantPath() : "old path")
                        + " and leave this path deleted (git rm " + f.path + ").");
                s.reasons.add("Mainline renamed the file after the fork; the tenant still has it under the old name, so taking theirs "
                        + "would create a second copy.");
                return s;
            }
            case "DELETED_BY_TENANT" -> {
                Suggestion s = of(MANUAL, LOW, "The tenant deleted or moved this file on purpose: keep it deleted (git rm " + f.path
                        + ") and port the pick's change to where the code lives now, if it still matters.");
                s.reasons.add("The file existed at the fork and the tenant removed it since.");
                return s;
            }
            default -> {
                List<String> gaps = gaps(h);
                Suggestion s = of(THEIRS, gaps.isEmpty() ? MEDIUM : LOW, "Add the pick's version of the file (git add " + f.path + ").");
                s.reasons.add(origin.equals("ADDED_ON_MAINLINE") ? "Mainline added the file after the fork; the tenant never had it."
                        : "The tenant does not have the file.");
                s.reasons.add("This brings mainline's whole file as of the pick, not only the pick's change: check the file history "
                        + "for mainline work the tenant may not want.");
                gapFollowUps(h, gaps, s);
                return s;
            }
        }
    }

    private static Suggestion deletedByThem(Hunk h, ConflictFile f, Facts x) {
        List<String> users = x.theirsRemovesUsed();
        if (!users.isEmpty() || !h.takeOurs.entryPoints.isEmpty()) {
            Suggestion s = of(OURS, MEDIUM, "Keep the tenant's file (git add " + f.path + "): tenant code still uses it.");
            if (!users.isEmpty()) s.reasons.add("Still used in the tenant: " + String.join(", ", first(users, 6)) + ".");
            s.reasons.add("The pick deletes the file; deleting it would break the tenant code above unless you also port the pick's "
                    + "replacement to it.");
            return s;
        }
        if (!h.tenant.isEmpty()) {
            Suggestion s = of(MANUAL, LOW, "The pick deletes this file, but the tenant edited it since the fork (" + commits(h.tenant)
                    + "): check those edits are covered elsewhere before deleting it (git rm " + f.path + ").");
            s.reasons.add("No tenant code was found using the file's members.");
            return s;
        }
        Suggestion s = of(THEIRS, MEDIUM, "Delete the file as the pick does (git rm " + f.path + ").");
        s.reasons.add("No tenant code was found using the file's members and the tenant did not edit it since the fork; it differs "
                + "from the pick's parent only through mainline changes the tenant lacks.");
        return s;
    }

    // ------------------------------------------------------------------ content conflicts

    private static Suggestion content(Hunk h, ConflictFile f, Facts x) {
        List<String> o = h.oursText, b = h.baseText, t = h.theirsText;
        boolean bothAdd = f.kind.equals("ADDED_BY_BOTH");
        if (sameIgnoringWhitespace(o, t)) {
            Suggestion s = of(OURS, HIGH, "Keep ours: both sides have the same code apart from whitespace.");
            s.reasons.add("Ours and theirs differ only in spacing or blank lines.");
            s.resolution = new ArrayList<>(o);
            return s;
        }
        if (!bothAdd && sameIgnoringWhitespace(t, b)) {
            Suggestion s = of(OURS, HIGH, "Keep ours: theirs only reformats these lines.");
            s.reasons.add("Theirs differs from the pick's parent only in whitespace, so ours already has everything the pick means here.");
            s.resolution = new ArrayList<>(o);
            return s;
        }
        if (!h.takeTheirs.movedImports.isEmpty() && h.tenant.isEmpty()) {
            Suggestion s = of(OURS, HIGH, "Keep ours: theirs only moves these import(s) elsewhere in the file.");
            s.reasons.add("Moved: " + String.join(", ", h.takeTheirs.movedImports) + "; git already merged the moved copy.");
            if (x.mergedMissing() > 0) s.followUps.add("The moved copy merged automatically and uses what the tenant lacks: remove it or rewrite it (see ADAPT).");
            s.resolution = new ArrayList<>(o);
            return s;
        }
        List<String> gaps = gaps(h);
        if (!bothAdd && sameIgnoringWhitespace(o, b)) {
            Suggestion s = of(THEIRS, gaps.isEmpty() ? HIGH : MEDIUM, "Take theirs: ours differs from the pick's parent only in whitespace.");
            s.reasons.add("The tenant's side only reformats these lines, so the pick's version loses nothing of the tenant's.");
            gapFollowUps(h, gaps, s);
            s.resolution = new ArrayList<>(t);
            return s;
        }
        boolean imports = onlyImports(o) && onlyImports(t);
        // add/add has no real base: a union of two versions of the same code would not compile
        Combined c = bothAdd && !imports ? null : combine(b, o, t);
        if (c != null) {
            Suggestion s = of(BOTH, imports ? HIGH : MEDIUM, imports ? "Keep both: the union of the two import lists."
                    : c.sameSpot() ? "Combine both: each side adds its own lines here; keep both sets."
                    : "Combine both: the two sides change different lines of this hunk.");
            s.reasons.add(c.note);
            if (!imports && !c.sameSpot()) s.reasons.add("git treats edits on adjacent lines as one conflict; applied line by line they do not overlap. "
                    + "Read the combined lines once: neighbouring edits can still depend on each other.");
            if (!h.tenant.isEmpty()) s.reasons.add("Keeps the tenant's edits (" + commits(h.tenant) + ").");
            else if (!h.mainline.isEmpty()) s.reasons.add("Applies only the pick's change. Taking theirs instead would also bring the mainline "
                    + "edits around it that the tenant lacks (" + commits(h.mainline) + ").");
            gapFollowUps(h, gaps, s);
            s.resolution = c.lines;
            return s;
        }
        if (!h.tenant.isEmpty()) {
            Suggestion s = of(MANUAL, LOW, "Merge by hand: keep the tenant's edits and add the pick's change.");
            s.reasons.add("The tenant changed these same lines since the fork (" + commits(h.tenant) + "); either side alone loses work.");
            if (!h.takeOurs.lost.isEmpty()) s.reasons.add("The pick's change: " + String.join("; ", first(h.takeOurs.lost, 4)) + ".");
            gapFollowUps(h, gaps, s);
            return s;
        }
        if (!ConflictAnalyzer.analyzable(h.path)) {
            Suggestion s = of(THEIRS, LOW, "Take theirs, after comparing both sides: no symbol analysis for this file type.");
            s.reasons.add("The tenant did not edit these lines since the fork, so ours is the pre-fork code.");
            s.resolution = new ArrayList<>(t);
            return s;
        }
        String level = gaps.isEmpty() && !uncertain(h) ? HIGH : gaps.isEmpty() ? MEDIUM : gaps.size() > 3 ? LOW : MEDIUM;
        List<String> removed = x.theirsRemovesUsed();
        if (!removed.isEmpty()) level = LOW;
        Suggestion s = of(THEIRS, level, gaps.isEmpty() ? "Take theirs: it uses nothing the tenant lacks."
                : "Take theirs, after bringing over the " + gaps.size() + " symbol(s) it needs.");
        s.reasons.add("The tenant did not edit these lines since the fork: ours is the pre-fork code, and the conflict comes from mainline "
                + "changes the tenant lacks" + (h.mainline.isEmpty() ? "" : " (" + commits(h.mainline) + ")") + ".");
        if (!h.takeOurs.orphans.isEmpty()) {
            s.reasons.add("Keeping ours would leave " + h.takeOurs.orphans.size() + " place(s) of the pick using what only theirs' side declares.");
        }
        if (uncertain(h)) s.reasons.add("Some needed symbols could not be resolved with certainty (marked ?).");
        if (!removed.isEmpty()) {
            s.followUps.add("Theirs removes or renames what tenant code still uses: " + String.join(", ", first(removed, 6))
                    + ". Update those callers too (see the Keep ours tab).");
        }
        gapFollowUps(h, gaps, s);
        s.resolution = new ArrayList<>(t);
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private static Suggestion of(String choice, String confidence, String headline) {
        Suggestion s = new Suggestion();
        s.choice = choice;
        s.confidence = confidence;
        s.headline = headline;
        return s;
    }

    /** symbols taking theirs needs that the tenant lacks or has incompatibly (and the pick does not bring) */
    static List<String> gaps(Hunk h) {
        List<String> out = new ArrayList<>();
        for (Need n : h.takeTheirs.needs) {
            if (n.origin == null || n.origin.equals("IN_HUNK") || n.origin.equals("IN_PICK")) continue;
            if (ContextModel.MISSING.equals(n.ours) || ContextModel.CHANGED.equals(n.ours)) out.add(n.symbol);
        }
        return out;
    }

    private static boolean uncertain(Hunk h) {
        for (Need n : h.takeTheirs.needs) if (n.uncertain && !ContextModel.PRESENT.equals(n.ours)) return true;
        return false;
    }

    private static void gapFollowUps(Hunk h, List<String> gaps, Suggestion s) {
        if (gaps.isEmpty()) return;
        List<String> renamed = new ArrayList<>(), bring = new ArrayList<>();
        for (Need n : h.takeTheirs.needs) {
            if (!gaps.contains(n.symbol)) continue;
            if (n.tenantName != null) renamed.add(n.symbol + " (tenant: " + n.tenantName + ")");
            else bring.add(n.symbol);
        }
        if (!bring.isEmpty()) s.followUps.add("Bring over first: " + String.join(", ", first(dedupe(bring), 8)) + " (see BRING / CONSIDER COMMIT).");
        if (!renamed.isEmpty()) s.followUps.add("Renamed on mainline: " + String.join(", ", first(dedupe(renamed), 6))
                + "; rename the tenant code or rewrite the picked lines to the tenant name (see ADAPT).");
    }

    private static void retest(Hunk h, Suggestion s) {
        if (h.takeOurs.entryPoints.isEmpty()) return;
        List<String> routes = new ArrayList<>();
        for (EntryPoint e : h.takeOurs.entryPoints) if (routes.size() < 4 && !routes.contains(e.route)) routes.add(e.route);
        s.followUps.add("Retest " + String.join(", ", routes) + (h.takeOurs.entryPoints.size() > 4 ? " …" : "") + ".");
    }

    private static String commits(List<Event> es) {
        Set<String> out = new LinkedHashSet<>();
        for (Event e : es) out.add(e.commit.shortSha());
        return String.join(", ", first(new ArrayList<>(out), 4)) + (out.size() > 4 ? " +" + (out.size() - 4) : "");
    }

    private static List<String> first(List<String> xs, int n) {
        return xs.size() <= n ? xs : xs.subList(0, n);
    }

    private static List<String> dedupe(List<String> xs) {
        return new ArrayList<>(new LinkedHashSet<>(xs));
    }

    static boolean sameIgnoringWhitespace(List<String> a, List<String> b) {
        return squash(a).equals(squash(b));
    }

    private static List<String> squash(List<String> ls) {
        List<String> out = new ArrayList<>();
        for (String l : ls) {
            String s = l.strip().replaceAll("\\s+", " ");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static boolean onlyImports(List<String> ls) {
        boolean any = false;
        for (String l : ls) {
            String s = l.strip();
            if (s.isEmpty()) continue;
            if (!s.startsWith("import ")) return false;
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------ line-level combine

    record Combined(List<String> lines, String note, boolean sameSpot) {
    }

    /** one edit of a side relative to the base: base lines [from, to) replaced by {@code lines} (from == to: insertion) */
    record Edit(int from, int to, List<String> lines) {
        boolean insertion() {
            return from == to;
        }
    }

    /**
     * Merges ours' and theirs' edits of the hunk relative to the base line by line, accepting edits git rejects only for
     * being adjacent. Returns null when the edits overlap (a person has to decide). Both sides inserting at the same place
     * gives ours' lines, then theirs' lines ours does not already have.
     */
    static Combined combine(List<String> base, List<String> ours, List<String> theirs) {
        List<Edit> eo = edits(base, ours), et = edits(base, theirs);
        if (eo == null || et == null || eo.isEmpty() || et.isEmpty()) return null;
        List<Edit> all = new ArrayList<>();
        boolean sharedInsert = false;
        for (Edit a : eo) {
            for (Edit b : et) {
                if (a.equals(b)) continue;
                if (a.insertion() && b.insertion()) {
                    if (a.from() != b.from()) continue;
                    // two versions of the same new code (e.g. the same if-block with different bodies) are not additions to keep both of
                    if (alternatives(a.lines(), b.lines())) return null;
                    sharedInsert = true;
                    continue;
                }
                if (overlaps(a, b)) return null;
            }
        }
        all.addAll(eo);
        for (Edit b : et) if (!eo.contains(b)) all.add(b);
        // by position; at the same position insertions first, ours before theirs (stable sort keeps that order)
        all.sort(Comparator.comparingInt(Edit::from).thenComparing(e -> e.insertion() ? 0 : 1));
        List<String> out = new ArrayList<>();
        int pos = 0;
        java.util.Map<Integer, Set<String>> oursInserted = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Edit e = all.get(i);
            while (pos < e.from()) out.add(base.get(pos++));
            boolean fromOurs = eo.contains(e);
            if (e.insertion() && !fromOurs && sharedInsert) {
                // theirs inserting where ours also inserted: skip the imports ours already added
                Set<String> have = oursInserted.getOrDefault(e.from(), Set.of());
                for (String l : e.lines()) if (!(l.strip().startsWith("import ") && have.contains(l.strip()))) out.add(l);
            } else {
                out.addAll(e.lines());
                if (e.insertion() && fromOurs) for (String l : e.lines()) oursInserted.computeIfAbsent(e.from(), k -> new LinkedHashSet<>()).add(l.strip());
            }
            pos = Math.max(pos, e.to());
        }
        while (pos < base.size()) out.add(base.get(pos++));
        String note = base.isEmpty() || sharedInsert ? "Both sides add lines at the same place; the combination keeps ours' lines first, "
                + "then the lines theirs adds that ours does not have."
                : "Ours makes " + eo.size() + " edit(s) and theirs " + et.size() + " edit(s) of the pick's parent, on different lines.";
        return new Combined(out, note, base.isEmpty() || sharedInsert);
    }

    /** insertions sharing a line of real code (not only braces, blank lines or imports) are alternatives, not additions */
    private static boolean alternatives(List<String> a, List<String> b) {
        Set<String> mine = new java.util.HashSet<>();
        for (String l : a) if (substantial(l)) mine.add(l.strip());
        for (String l : b) if (substantial(l) && mine.contains(l.strip())) return true;
        return false;
    }

    private static boolean substantial(String line) {
        String s = line.strip();
        return !s.startsWith("import ") && s.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean overlaps(Edit a, Edit b) {
        if (a.insertion()) return b.from() < a.from() && a.from() < b.to();
        if (b.insertion()) return a.from() < b.from() && b.from() < a.to();
        return a.from() < b.to() && b.from() < a.to();
    }

    /**
     * The edits turning {@code base} into {@code side} (LCS line diff), or null when too large to diff. Lines are matched as
     * late as possible, so an inserted block keeps its own closing brace ("void a() {", "}") instead of borrowing the
     * base's and leaving a lone "}" after it.
     */
    static List<Edit> edits(List<String> base, List<String> side) {
        int n = base.size(), m = side.size();
        if ((long) (n + 1) * (m + 1) > 4_000_000L) return null;
        int[][] lcs = new int[n + 1][m + 1]; // over prefixes
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                lcs[i][j] = base.get(i - 1).equals(side.get(j - 1)) ? lcs[i - 1][j - 1] + 1 : Math.max(lcs[i - 1][j], lcs[i][j - 1]);
            }
        }
        // walk back from the ends: matched (base, side) pairs, latest first
        List<int[]> matches = new ArrayList<>();
        int i = n, j = m;
        while (i > 0 && j > 0) {
            if (base.get(i - 1).equals(side.get(j - 1))) {
                matches.add(new int[] {--i, --j});
            } else if (lcs[i][j - 1] >= lcs[i - 1][j]) {
                j--;
            } else {
                i--;
            }
        }
        java.util.Collections.reverse(matches);
        matches.add(new int[] {n, m}); // sentinel
        List<Edit> out = new ArrayList<>();
        int bi = 0, sj = 0;
        for (int[] mt : matches) {
            if (mt[0] > bi || mt[1] > sj) out.add(new Edit(bi, mt[0], new ArrayList<>(side.subList(sj, mt[1]))));
            bi = mt[0] + 1;
            sj = mt[1] + 1;
        }
        return out;
    }

    /** "THEIRS" -> "take theirs", for headlines and badges */
    public static String label(String choice) {
        return switch (choice == null ? "" : choice) {
            case THEIRS -> "take theirs";
            case OURS -> "keep ours";
            case BOTH -> "combine both";
            case MANUAL -> "merge by hand";
            default -> String.valueOf(choice).toLowerCase();
        };
    }
}
