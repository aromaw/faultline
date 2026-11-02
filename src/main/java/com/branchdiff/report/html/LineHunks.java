package com.branchdiff.report.html;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Line-level hunks for the HTML diff view. All ranges are 0-based, end-exclusive line indices.
 * Two-way hunks pair a left and a right range; three-way hunks follow the diff3 model: deltas of base->ours and
 * base->theirs that overlap or touch on the base side are grouped into one hunk.
 */
public final class LineHunks {
    private LineHunks() {
    }

    /** One changed region between two texts. */
    public record Hunk2(int leftStart, int leftEnd, int rightStart, int rightEnd) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("l", new int[]{leftStart, leftEnd});
            m.put("r", new int[]{rightStart, rightEnd});
            return m;
        }
    }

    /** One diff3 region; {@code type} is "ours", "theirs", "both" (identical change) or "conflict". */
    public record Hunk3(int baseStart, int baseEnd, int oursStart, int oursEnd, int theirsStart, int theirsEnd,
                        String type) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("b", new int[]{baseStart, baseEnd});
            m.put("o", new int[]{oursStart, oursEnd});
            m.put("t", new int[]{theirsStart, theirsEnd});
            m.put("type", type);
            return m;
        }
    }

    public static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        return List.of(t.split("\n", -1));
    }

    public static List<Hunk2> twoWay(List<String> left, List<String> right) {
        List<Hunk2> res = new ArrayList<>();
        for (AbstractDelta<String> d : DiffUtils.diff(left, right).getDeltas()) {
            int ls = d.getSource().getPosition(), rs = d.getTarget().getPosition();
            res.add(new Hunk2(ls, ls + d.getSource().size(), rs, rs + d.getTarget().size()));
        }
        res.sort(Comparator.comparingInt(Hunk2::leftStart));
        return res;
    }

    private record Delta(boolean ours, int bs, int be, int ts, int te) {
        int growth() {
            return (te - ts) - (be - bs);
        }
    }

    public static List<Hunk3> threeWay(List<String> base, List<String> ours, List<String> theirs) {
        List<Delta> all = new ArrayList<>();
        for (Hunk2 h : twoWay(base, ours)) all.add(new Delta(true, h.leftStart, h.leftEnd, h.rightStart, h.rightEnd));
        for (Hunk2 h : twoWay(base, theirs)) all.add(new Delta(false, h.leftStart, h.leftEnd, h.rightStart, h.rightEnd));
        all.sort(Comparator.comparingInt(Delta::bs).thenComparingInt(Delta::be));
        List<Hunk3> res = new ArrayList<>();
        int oOff = 0, tOff = 0;
        int i = 0;
        while (i < all.size()) {
            int bStart = all.get(i).bs, bEnd = all.get(i).be;
            List<Delta> group = new ArrayList<>();
            group.add(all.get(i));
            int j = i + 1;
            // touching regions are grouped too (like diff3), so adjacent edits from both sides surface together
            while (j < all.size() && all.get(j).bs <= bEnd) {
                bEnd = Math.max(bEnd, all.get(j).be);
                group.add(all.get(j));
                j++;
            }
            int oGrowth = 0, tGrowth = 0;
            boolean hasOurs = false, hasTheirs = false;
            for (Delta d : group) {
                if (d.ours) {
                    hasOurs = true;
                    oGrowth += d.growth();
                } else {
                    hasTheirs = true;
                    tGrowth += d.growth();
                }
            }
            int oStart = bStart + oOff, tStart = bStart + tOff;
            oOff += oGrowth;
            tOff += tGrowth;
            int oEnd = bEnd + oOff, tEnd = bEnd + tOff;
            String type;
            if (hasOurs && hasTheirs) {
                type = ours.subList(oStart, oEnd).equals(theirs.subList(tStart, tEnd)) ? "both" : "conflict";
            } else type = hasOurs ? "ours" : "theirs";
            res.add(new Hunk3(bStart, bEnd, oStart, oEnd, tStart, tEnd, type));
            i = j;
        }
        return res;
    }
}
