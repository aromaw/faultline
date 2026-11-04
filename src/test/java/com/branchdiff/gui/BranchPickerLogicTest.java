package com.branchdiff.gui;

import com.branchdiff.gui.BranchFilter.Row;
import com.branchdiff.gui.GitQueries.Branch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Branch search of the branch popup, the out-of-memory advice and the bundled app icon. */
class BranchPickerLogicTest {
    private static final List<Branch> BRANCHES = List.of(new Branch("master", false), new Branch("tenant/acme", false),
            new Branch("feature/Login-Fix", false), new Branch("origin/master", true), new Branch("origin/tenant/acme", true),
            new Branch("upstream/release-2.1", true));

    private static List<String> names(List<Row> rows) {
        return rows.stream().map(r -> r.isHeader() ? "# " + r.header() : r.branch().name()).toList();
    }

    @Test
    void emptyQueryListsLocalThenRemoteUnderHeaders() {
        assertEquals(List.of("# Local branches", "master", "tenant/acme", "feature/Login-Fix", "# Remote branches", "origin/master",
                "origin/tenant/acme", "upstream/release-2.1"), names(BranchFilter.rows(BRANCHES, "")));
    }

    @Test
    void substringCaseInsensitiveAndEveryWordMustMatch() {
        assertEquals(List.of("# Local branches", "feature/Login-Fix"), names(BranchFilter.rows(BRANCHES, "LOGIN")));
        assertEquals(List.of("# Local branches", "tenant/acme", "# Remote branches", "origin/tenant/acme"),
                names(BranchFilter.rows(BRANCHES, "acme")));
        assertEquals(List.of("# Remote branches", "origin/tenant/acme"), names(BranchFilter.rows(BRANCHES, " origin   acme ")));
        assertEquals(List.of("# Remote branches", "upstream/release-2.1"), names(BranchFilter.rows(BRANCHES, "2.1")));
        assertTrue(BranchFilter.rows(BRANCHES, "nope").isEmpty());
    }

    @Test
    void keyboardNavigationSkipsHeaders() {
        List<Row> rows = BranchFilter.rows(BRANCHES, "acme"); // header, tenant/acme, header, origin/tenant/acme
        assertEquals(1, BranchFilter.initialRow(rows, null));
        assertEquals(3, BranchFilter.initialRow(rows, "origin/tenant/acme"));
        assertEquals(3, BranchFilter.nextBranchRow(rows, 1, 1));
        assertEquals(-1, BranchFilter.nextBranchRow(rows, 3, 1));
        assertEquals(1, BranchFilter.nextBranchRow(rows, 3, -1));
        assertEquals(-1, BranchFilter.nextBranchRow(rows, 1, -1));
        assertEquals(-1, BranchFilter.initialRow(List.of(), "x"));
    }

    @Test
    void highlightMarksEveryMatchedPart() {
        String h = BranchPicker.highlight("origin/tenant/acme", "TEN acme", true);
        assertEquals("<html>origin/<b><font>ten</font></b>ant/<b><font>acme</font></b></html>", h);
        assertEquals("master", BranchPicker.highlight("master", "  ", false));
        assertTrue(BranchPicker.highlight("a<b", "<", true).contains("&lt;"));
    }

    @Test
    void outOfMemoryAdviceSuggestsAtLeastTwiceTheHeap() {
        assertEquals("2g", MemoryAdvice.suggestedXmx(1L << 30));
        assertEquals("2g", MemoryAdvice.suggestedXmx(256L << 20));
        assertEquals("8g", MemoryAdvice.suggestedXmx(4L << 30));
        assertEquals("3g", MemoryAdvice.suggestedXmx(1200L << 20));
        assertEquals("1.0 GB", MemoryAdvice.limitText(1L << 30));
        assertEquals("512 MB", MemoryAdvice.limitText(512L << 20));
        String msg = MemoryAdvice.outOfMemory(1L << 30);
        assertTrue(msg.contains("FAULTLINE_XMX=2g scripts/faultline gui"), msg);
        assertTrue(msg.contains("java -Xmx2g -jar"), msg);
        assertTrue(msg.contains("limit 1.0 GB"), msg);
        assertNull(MemoryAdvice.smallHeapHint(1L << 30));
        assertNotNull(MemoryAdvice.smallHeapHint(512L << 20));
    }

    @Test
    void appIconIsBundledInEverySize() {
        for (int s : AppIcon.SIZES) {
            java.awt.Image i = AppIcon.load(s);
            assertNotNull(i, "icon " + s);
            assertEquals(s, new javax.swing.ImageIcon(i).getIconWidth()); // waits for the lazy decode
        }
        assertFalse(AppIcon.images().isEmpty());
    }

    @Test
    void macDockIconSitsOnApplesGridOtherPlatformsGetTheFullBleedOne() throws Exception {
        java.awt.Image mac = AppIcon.taskbarImage("Mac OS X");
        assertNotNull(mac);
        assertEquals(512, new javax.swing.ImageIcon(mac).getIconWidth());
        assertTrue(AppIcon.taskbarImage("Linux") == AppIcon.images().get(AppIcon.images().size() - 1));
        // Apple's grid: the 824/1024 square is centred, so a 512 px image is transparent outside about 50..462
        java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(AppIcon.class.getResource("icons/faultline-mac-512.png"));
        assertEquals(0, b.getRGB(256, 30) >>> 24, "transparent margin above the icon");
        assertEquals(0, b.getRGB(30, 256) >>> 24, "transparent margin left of the icon");
        assertEquals(255, b.getRGB(256, 256) >>> 24, "opaque centre");
        assertEquals(255, b.getRGB(60, 256) >>> 24, "the square starts at about 50 px");
        // the full-bleed icon reaches much closer to the edge
        java.awt.image.BufferedImage full = javax.imageio.ImageIO.read(AppIcon.class.getResource("icons/faultline-512.png"));
        assertEquals(255, full.getRGB(30, 256) >>> 24);
    }
}
