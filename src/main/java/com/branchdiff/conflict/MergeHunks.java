package com.branchdiff.conflict;

import com.branchdiff.conflict.ConflictModel.Range;
import com.branchdiff.report.html.LineHunks;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Conflict hunks of one file from its three stages. The conflict regions are the ones {@code git merge-file --diff3}
 * reports (the same xdiff merge the cherry-pick ran); each region's text is then located in the ours, base and theirs
 * versions so every hunk carries a line range on all three sides.
 */
public final class MergeHunks {
    private MergeHunks() {
    }

    /** One conflict region; ranges are 1-based inclusive (empty: end = start - 1). */
    public record Region(Range ours, Range base, Range theirs) {
    }

    record Block(List<String> ours, List<String> base, List<String> theirs) {
    }

    public static List<Region> regions(String base, String ours, String theirs) {
        List<String> b = LineHunks.lines(base), o = LineHunks.lines(ours), t = LineHunks.lines(theirs);
        List<Block> blocks = gitBlocks(base, ours, theirs);
        if (blocks == null) return fallback(b, o, t);
        List<Region> res = new ArrayList<>();
        int co = 0, cb = 0, ct = 0;
        for (Block bl : blocks) {
            int io = find(o, bl.ours, co), ib = find(b, bl.base, cb), it = find(t, bl.theirs, ct);
            // an empty side (or text not found) is placed by aligning it with a side that was found
            if (ib < 0 && io >= 0) ib = map(o, b, io);
            if (ib < 0 && it >= 0) ib = map(t, b, it);
            if (ib < 0) ib = cb;
            if (io < 0) io = map(b, o, ib);
            if (it < 0) it = map(b, t, ib);
            Region r = new Region(new Range(io + 1, io + bl.ours.size()), new Range(ib + 1, ib + bl.base.size()),
                    new Range(it + 1, it + bl.theirs.size()));
            res.add(r);
            co = io + bl.ours.size();
            cb = ib + bl.base.size();
            ct = it + bl.theirs.size();
        }
        return res;
    }

    /** 0-based index of {@code needle} in {@code hay} at or after {@code from}; -1 when absent or empty */
    static int find(List<String> hay, List<String> needle, int from) {
        if (needle.isEmpty()) return -1;
        outer:
        for (int i = Math.max(0, from); i + needle.size() <= hay.size(); i++) {
            for (int k = 0; k < needle.size(); k++) if (!hay.get(i + k).equals(needle.get(k))) continue outer;
            return i;
        }
        return -1;
    }

    /** maps a 0-based line position of {@code from} to the corresponding position in {@code to} (line diff) */
    static int map(List<String> from, List<String> to, int pos) {
        int offset = 0;
        for (AbstractDelta<String> d : DiffUtils.diff(from, to).getDeltas()) {
            int s = d.getSource().getPosition(), e = s + d.getSource().size();
            if (e <= pos && !(d.getSource().size() == 0 && s == pos)) {
                offset += d.getTarget().size() - d.getSource().size();
            } else if (s < pos) {
                return d.getTarget().getPosition() + Math.min(pos - s, d.getTarget().size());
            } else {
                break;
            }
        }
        return Math.max(0, Math.min(to.size(), pos + offset));
    }

    /** conflict blocks from {@code git merge-file -p --diff3}; null when git is unavailable */
    static List<Block> gitBlocks(String base, String ours, String theirs) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("faultline-merge");
            Path pb = dir.resolve("base"), po = dir.resolve("ours"), pt = dir.resolve("theirs");
            Files.writeString(pb, base == null ? "" : base, StandardCharsets.UTF_8);
            Files.writeString(po, ours == null ? "" : ours, StandardCharsets.UTF_8);
            Files.writeString(pt, theirs == null ? "" : theirs, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("git", "merge-file", "-p", "--diff3", "-L", "ours", "-L", "base", "-L", "theirs",
                    po.toString(), pb.toString(), pt.toString()).redirectErrorStream(false).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.getErrorStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() < 0 || p.exitValue() >= 128) return null;
            return parseDiff3(new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (dir != null) {
                for (String n : new String[] {"base", "ours", "theirs"}) dir.resolve(n).toFile().delete();
                dir.toFile().delete();
            }
        }
    }

    /** Parses diff3-style conflict markers (labels "ours", "base", "theirs" or anything else). */
    static List<Block> parseDiff3(String merged) {
        List<Block> res = new ArrayList<>();
        List<String> lines = LineHunks.lines(merged);
        int i = 0;
        while (i < lines.size()) {
            if (!lines.get(i).startsWith("<<<<<<<")) {
                i++;
                continue;
            }
            List<String> o = new ArrayList<>(), b = new ArrayList<>(), t = new ArrayList<>();
            List<String> cur = o;
            boolean sawBase = false;
            i++;
            for (; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.startsWith("|||||||")) {
                    cur = b;
                    sawBase = true;
                } else if (l.equals("=======") || l.startsWith("======= ")) {
                    cur = t;
                } else if (l.startsWith(">>>>>>>")) {
                    break;
                } else {
                    cur.add(l);
                }
            }
            i++;
            res.add(new Block(o, sawBase ? b : List.of(), t));
        }
        return res;
    }

    /** conflict hunks of the line-level three-way diff, when git merge-file cannot run */
    static List<Region> fallback(List<String> b, List<String> o, List<String> t) {
        List<Region> res = new ArrayList<>();
        for (LineHunks.Hunk3 h : LineHunks.threeWay(b, o, t)) {
            if (!h.type().equals("conflict")) continue;
            res.add(new Region(new Range(h.oursStart() + 1, h.oursEnd()), new Range(h.baseStart() + 1, h.baseEnd()),
                    new Range(h.theirsStart() + 1, h.theirsEnd())));
        }
        return res;
    }

    /** Changed regions base -> theirs (for files ours does not have: every change of the pick is a "hunk"). */
    public static List<Region> pickChanges(String base, String theirs, int mergeGap) {
        List<String> b = LineHunks.lines(base), t = LineHunks.lines(theirs);
        List<Region> res = new ArrayList<>();
        int bs = -1, be = -1, ts = -1, te = -1;
        for (LineHunks.Hunk2 h : LineHunks.twoWay(b, t)) {
            if (bs >= 0 && h.leftStart() - be <= mergeGap) {
                be = h.leftEnd();
                te = h.rightEnd();
                continue;
            }
            if (bs >= 0) res.add(new Region(new Range(1, 0), new Range(bs + 1, be), new Range(ts + 1, te)));
            bs = h.leftStart();
            be = h.leftEnd();
            ts = h.rightStart();
            te = h.rightEnd();
        }
        if (bs >= 0) res.add(new Region(new Range(1, 0), new Range(bs + 1, be), new Range(ts + 1, te)));
        return res;
    }
}
