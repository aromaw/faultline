package com.branchdiff;

import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Report;
import com.branchdiff.report.html.HtmlRenderer;
import com.branchdiff.report.html.LineHunks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.branchdiff.TestSupport.analyze;
import static com.branchdiff.TestSupport.snap;
import static com.branchdiff.TestSupport.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTML report is one self-contained page whose embedded JSON matches the report. */
class HtmlRendererTest {
    static final String MIME = "src/main/java/a/util/Mime.java";
    static final String USER = "src/main/java/a/web/UserController.java";
    static final String HELPER = "src/main/java/a/web/Helper.java";
    static final String MAPPER = "src/main/resources/mapper/UserMapper.xml";

    static String mime(String exts) {
        return "package a.util;\npublic class Mime {\n    // allowed </script> <!-- extensions\n"
                + "    public static final String[] ALLOWED = { " + exts + " };\n}\n";
    }

    static String controller(String body) {
        return "package a.web;\npublic class UserController {\n    public String list() {\n        " + body + "\n    }\n}\n";
    }

    static String mapper(String set) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mapper namespace=\"a.mapper.UserMapper\">\n"
                + "    <update id=\"updateUser\">\n        update sys_user set " + set + " where id = #{id}\n    </update>\n</mapper>\n";
    }

    static final Snapshot BASE = snap("base",
            MIME, mime("\"png\", \"html\""),
            USER, controller("return \"a\";"),
            MAPPER, mapper("name = #{name}"));

    static Snapshot[] scenario() {
        Snapshot ours = with(BASE, "ours", USER, controller("return \"ours\";"));
        Snapshot theirs = with(BASE, "theirs",
                MIME, mime("\"png\""),
                USER, controller("return Helper.name();"),
                HELPER, "package a.web;\npublic class Helper {\n    public static String name() { return \"h\"; }\n}\n",
                MAPPER, mapper("name = #{name}, status = #{status}"));
        return new Snapshot[] {BASE, ours, theirs};
    }

    static JsonNode embedded(String html) throws Exception {
        Matcher m = Pattern.compile("<script type=\"application/json\" id=\"faultline-data\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        assertTrue(m.find(), "data script present");
        return new ObjectMapper().readTree(m.group(1));
    }

    @Test
    void rendersSelfContainedPageWithValidData() throws Exception {
        Snapshot[] s = scenario();
        Report r = analyze(s[0], s[1], s[2]);
        assertTrue(r.findings.size() >= 3, "scenario should produce findings");
        String html = HtmlRenderer.render(r, HtmlRenderer.source(s[0], s[1], s[2], null));

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        for (String id : List.of("id=\"tree\"", "id=\"card\"", "id=\"diff\"", "id=\"modeSeg\"", "id=\"themeBtn\"", "id=\"search\"")) {
            assertTrue(html.contains(id), id);
        }
        assertFalse(html.contains("__FAULTLINE_DATA__"), "placeholder replaced");
        // offline: no external scripts, styles, fonts or images
        assertFalse(Pattern.compile("(src|href)\\s*=\\s*\"(https?:)?//").matcher(html).find(), "no external resources");
        assertFalse(html.contains("@import"));
        // the embedded file text contains "</script>" and "<!--"; only the template's own closing tags may remain
        assertEquals(2, html.split("</script>", -1).length - 1, "exactly the two template </script> tags");

        JsonNode d = embedded(html);
        assertEquals(r.findings.size(), d.get("findings").size());
        assertEquals("ours-sha", d.get("meta").get("refs").get("ours").asText().replaceAll(".*\\((.*)\\)$", "$1"));
        for (int i = 0; i < r.findings.size(); i++) {
            JsonNode f = d.get("findings").get(i);
            assertEquals(i + 1, f.get("id").asInt());
            assertEquals(r.findings.get(i).symbol, f.get("symbol").asText());
            for (JsonNode t : f.get("targets")) {
                assertTrue(d.get("files").has(t.get("path").asText()), "target file embedded: " + t);
            }
        }
        JsonNode files = d.get("files");
        assertTrue(files.has(MIME) && files.has(MAPPER) && files.has(USER), files.fieldNames().toString());
        assertEquals(mime("\"png\""), files.get(MIME).get("theirs").asText());
        assertEquals(mime("\"png\", \"html\""), files.get(MIME).get("base").asText());
        assertEquals("java", files.get(MIME).get("lang").asText());
        assertEquals("xml", files.get(MAPPER).get("lang").asText());
        // theirs-only change of the initializer line (0-based line 3)
        JsonNode h = files.get(MIME).get("hunks3").get(0);
        assertEquals("theirs", h.get("type").asText());
        assertEquals(3, h.get("b").get(0).asInt());
        // both sides edited the same line of UserController differently
        boolean conflict = false;
        for (JsonNode x : files.get(USER).get("hunks3")) conflict |= "conflict".equals(x.get("type").asText());
        assertTrue(conflict, files.get(USER).toString());
    }

    @Test
    void skipsFilesOverOneMegabyte() throws Exception {
        String big = "package a.util;\npublic class Mime {\n    // " + "x".repeat(HtmlRenderer.MAX_FILE_BYTES) + "\n"
                + "    public static final String[] ALLOWED = { \"png\" };\n}\n";
        Snapshot theirs = with(BASE, "theirs", MIME, big);
        Report r = analyze(BASE, BASE, theirs);
        assertFalse(r.findings.isEmpty());
        JsonNode file = embedded(HtmlRenderer.render(r, HtmlRenderer.source(BASE, BASE, theirs, null))).get("files").get(MIME);
        assertNotNull(file);
        assertTrue(file.get("theirs").isNull());
        assertFalse(file.get("base").isNull());
        assertTrue(file.get("notes").get(0).asText().contains("1 MB"), file.get("notes").toString());
    }

    @Test
    void threeWayHunksClassifyEachSide() {
        List<String> base = List.of("a", "b", "c", "d", "e");
        List<String> ours = List.of("a", "B", "c", "d", "e", "f");
        List<String> theirs = List.of("a", "b", "c", "D", "e", "f");
        List<LineHunks.Hunk3> hs = LineHunks.threeWay(base, ours, theirs);
        assertEquals(List.of("ours", "theirs", "both"), hs.stream().map(LineHunks.Hunk3::type).toList());
        assertEquals(new LineHunks.Hunk3(5, 5, 5, 6, 5, 6, "both"), hs.get(2));

        List<LineHunks.Hunk3> c = LineHunks.threeWay(base, List.of("a", "X", "c", "d", "e"), List.of("a", "Y", "c", "d", "e"));
        assertEquals(List.of(new LineHunks.Hunk3(1, 2, 1, 2, 1, 2, "conflict")), c);

        assertEquals(List.of(new LineHunks.Hunk2(1, 2, 1, 2), new LineHunks.Hunk2(3, 4, 3, 4)),
                LineHunks.twoWay(ours, theirs));
        assertTrue(LineHunks.lines(null).isEmpty());
        assertEquals(List.of("x", "y"), LineHunks.lines("x\r\ny\n"));
    }

    @Test
    void missingVersionIsNull() {
        assertNull(HtmlRenderer.source(BASE, BASE, BASE, null).read("ours", "nope/Missing.java"));
    }
}
