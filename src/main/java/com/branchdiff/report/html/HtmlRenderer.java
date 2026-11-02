package com.branchdiff.report.html;

import com.branchdiff.git.GitRunner;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.ContextModel;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Report;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a {@link Report} as one self-contained HTML file (inline CSS/JS, no network access) with an IDE-like
 * merge view: findings tree on the left, ours | merge-base | theirs diff of the referenced files on the right.
 * The analysis data, the three versions of every referenced file and their line hunks are embedded as JSON.
 */
public final class HtmlRenderer {
    /** files larger than this (per version) are not embedded */
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    static final String DATA_MARKER = "\"__FAULTLINE_DATA__\"";
    private static final Pattern LOC = Pattern.compile("^(.+?):(\\d+)(?:-(\\d+))?$");

    /** Reads a file of one version ("base", "ours" or "theirs"); returns null when the file does not exist there. */
    public interface FileSource {
        String read(String side, String path);
    }

    private HtmlRenderer() {
    }

    /** Files of the analyzed snapshots first, then (when available) any other path straight from git objects. */
    public static FileSource source(Snapshot base, Snapshot ours, Snapshot theirs, GitRunner git) {
        return (side, path) -> {
            Snapshot s = switch (side) {
                case "base" -> base;
                case "ours" -> ours;
                default -> theirs;
            };
            if (s == null) return null;
            String c = s.files().get(path);
            if (c == null) c = s.siblingFiles().get(path);
            if (c != null || git == null) return c;
            try {
                return git.run("cat-file", "blob", s.commit() + ":" + path);
            } catch (RuntimeException e) {
                return null; // not present in that version
            }
        };
    }

    public static String render(Report r, FileSource src) {
        Map<String, Object> data = data(r, src);
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // keep the JSON inert inside <script>: "<" only occurs inside JSON strings, where the
        // JSON unicode escape for it is equivalent, so no "</script>" or "<!--" sequence can appear
        json = json.replace("<", "\\u003c");
        String template = template();
        int at = template.indexOf(DATA_MARKER);
        if (at < 0) throw new IllegalStateException("HTML template has no data marker");
        return template.substring(0, at) + json + template.substring(at + DATA_MARKER.length());
    }

    static String template() {
        try (InputStream in = HtmlRenderer.class.getResourceAsStream("/html/report-template.html")) {
            if (in == null) throw new IllegalStateException("missing resource /html/report-template.html");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ data model

    static Map<String, Object> data(Report r, FileSource src) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", r.tool);
        meta.put("generatedAt", r.generatedAt);
        meta.put("repo", r.repo);
        meta.put("serviceDir", r.serviceDir);
        meta.put("refs", r.refs);
        meta.put("summary", r.summary);
        meta.put("warnings", r.warnings);
        meta.put("predictedTextualConflicts", r.predictedTextualConflicts);

        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        int id = 1;
        for (Finding f : r.findings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id++);
            m.put("severity", f.severity.name());
            m.put("confidence", f.confidence.name());
            m.put("group", f.uncertain() ? "UNCERTAIN" : f.severity.name());
            m.put("category", f.category.name());
            m.put("status", f.status);
            m.put("kind", f.kind);
            m.put("symbol", f.symbol);
            m.put("origin", f.origin);
            m.put("message", f.message);
            Map<String, Object> details = new LinkedHashMap<>();
            f.details.forEach((k, v) -> details.put(k, v instanceof String || v instanceof Number ? v : String.valueOf(v)));
            m.put("details", details);
            List<Map<String, Object>> used = new ArrayList<>();
            for (Finding.Usage u : f.neededBy) used.add(Map.of("by", u.by(), "location", u.location()));
            m.put("neededBy", used);
            List<Map<String, Object>> targets = targets(f, src, files);
            m.put("targets", targets);
            findings.add(m);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("meta", meta);
        d.put("findings", findings);
        d.put("files", files);
        d.put("context", r.context);
        if (r.context != null) members(r.context, src, files);
        return d;
    }

    /** Jump targets of a finding, most relevant first; registers every referenced file in {@code files}. */
    static List<Map<String, Object>> targets(Finding f, FileSource src, Map<String, Map<String, Object>> files) {
        List<Map<String, Object>> res = new ArrayList<>();
        for (Finding.Usage u : f.neededBy) {
            String side = u.by().contains("[in ours]") ? "ours" : "theirs";
            add(res, (side.equals("ours") ? "used at (ours)" : "needed by (theirs)") + ": " + shortBy(u.by()), side,
                    u.location(), src, files, f);
        }
        add(res, "ours", "ours", f.oursLocation, src, files, f);
        add(res, "theirs", "theirs", f.theirsLocation, src, files, f);
        Object base = f.details.get("base");
        if (base instanceof String b) add(res, "merge-base", "base", b, src, files, f);
        return res;
    }

    private static String shortBy(String by) {
        String s = by.replaceAll("\\s*\\[[^]]*]$", "");
        int hash = s.indexOf('#');
        int dot = s.lastIndexOf('.', hash < 0 ? s.length() - 1 : hash);
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static void add(List<Map<String, Object>> res, String label, String side, String loc, FileSource src,
                            Map<String, Map<String, Object>> files, Finding f) {
        if (loc == null || loc.isBlank()) return;
        String path = loc;
        int line = 0, end = 0;
        Matcher m = LOC.matcher(loc.trim());
        if (m.matches()) {
            path = m.group(1);
            line = Integer.parseInt(m.group(2));
            end = m.group(3) == null ? line : Integer.parseInt(m.group(3));
        }
        if (path.contains(" ") && !path.contains("/")) return; // not a file location
        Map<String, Object> file = file(path, src, files);
        if (line == 0 && f.category == Finding.Category.MAVEN_DEPENDENCY) {
            // pom locations have no line: point at the dependency's artifactId
            line = lineOf(fileText(file, side), "<artifactId>" + f.symbol.substring(f.symbol.indexOf(':') + 1) + "</artifactId>");
            end = line;
        }
        for (Map<String, Object> t : res) {
            if (t.get("path").equals(path) && t.get("side").equals(side) && t.get("line").equals(line)) return;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", label);
        t.put("side", side);
        t.put("path", path);
        t.put("line", line);
        t.put("endLine", end);
        res.add(t);
    }

    private static String fileText(Map<String, Object> file, String side) {
        Object o = file.get(side);
        return o instanceof String s ? s : null;
    }

    static int lineOf(String text, String needle) {
        if (text == null) return 0;
        int i = text.indexOf(needle);
        if (i < 0) return 0;
        int line = 1;
        for (int k = 0; k < i; k++) if (text.charAt(k) == '\n') line++;
        return line;
    }

    private static Map<String, Object> file(String path, FileSource src, Map<String, Map<String, Object>> files) {
        Map<String, Object> existing = files.get(path);
        if (existing != null) return existing;
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("lang", lang(path));
        List<String> notes = new ArrayList<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (String side : List.of("ours", "base", "theirs")) {
            String c = src.read(side, path);
            if (c != null && c.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                notes.add(side + " version larger than 1 MB, not embedded");
                c = null;
            }
            file.put(side, c);
            lines.put(side, LineHunks.lines(c));
        }
        file.put("notes", notes);
        List<Map<String, Object>> h3 = new ArrayList<>();
        for (LineHunks.Hunk3 h : LineHunks.threeWay(lines.get("base"), lines.get("ours"), lines.get("theirs"))) h3.add(h.json());
        List<Map<String, Object>> h2 = new ArrayList<>();
        for (LineHunks.Hunk2 h : LineHunks.twoWay(lines.get("ours"), lines.get("theirs"))) h2.add(h.json());
        file.put("hunks3", h3);
        file.put("hunks2", h2);
        files.put(path, file);
        return file;
    }

    // ------------------------------------------------------------------ member views of context nodes

    /** prefix of the pseudo-file that holds one context node's declaration in each version */
    public static final String MEMBER_PREFIX = "@";
    static final int MAX_MEMBERS = 1500;
    static final int MAX_MEMBER_LINES = 400;

    /**
     * For every context node with a source range, embeds a pseudo-file {@code "@" + nodeKey} holding just that
     * declaration (method, field, class header, config block, mapper statement, pom dependency) of each version, with
     * the line offset of each slice, so the viewer shows the enclosing member side by side without switching files.
     */
    static void members(ContextModel ctx, FileSource src, Map<String, Map<String, Object>> files) {
        Map<String, List<String>> cache = new LinkedHashMap<>();
        int n = 0;
        for (ContextModel.Node node : ctx.nodes.values()) {
            if (node.loc.isEmpty()) continue;
            if (n++ >= MAX_MEMBERS) break;
            Map<String, Object> file = new LinkedHashMap<>();
            String path = node.loc.values().iterator().next().path();
            file.put("lang", lang(path));
            file.put("member", true);
            Map<String, Integer> offset = new LinkedHashMap<>();
            Map<String, String> paths = new LinkedHashMap<>();
            Map<String, List<String>> lines = new LinkedHashMap<>();
            for (String side : List.of("ours", "base", "theirs")) {
                ContextModel.Loc loc = node.loc.get(side);
                List<String> all = loc == null ? null : cache.computeIfAbsent(side + "\u0000" + loc.path(),
                        k -> LineHunks.lines(src.read(side, loc.path())));
                if (loc == null || all == null || all.isEmpty()) {
                    file.put(side, null);
                    lines.put(side, List.of());
                    continue;
                }
                int from = Math.max(1, Math.min(loc.start(), all.size())), to = Math.min(all.size(), Math.max(from, loc.end()));
                to = Math.min(to, from + MAX_MEMBER_LINES - 1);
                List<String> slice = all.subList(from - 1, to);
                file.put(side, String.join("\n", slice) + "\n");
                lines.put(side, slice);
                offset.put(side, from - 1);
                paths.put(side, loc.path());
            }
            file.put("offset", offset);
            file.put("paths", paths);
            file.put("notes", List.of());
            List<Map<String, Object>> h3 = new ArrayList<>();
            for (LineHunks.Hunk3 h : LineHunks.threeWay(lines.get("base"), lines.get("ours"), lines.get("theirs"))) h3.add(h.json());
            // a member that exists in exactly two versions is compared between those two
            List<String> present = new ArrayList<>(paths.keySet());
            List<String> pair = present.size() == 2 ? present : List.of("ours", "theirs");
            List<Map<String, Object>> h2 = new ArrayList<>();
            for (LineHunks.Hunk2 h : LineHunks.twoWay(lines.get(pair.get(0)), lines.get(pair.get(1)))) h2.add(h.json());
            file.put("pair", pair);
            file.put("hunks3", h3);
            file.put("hunks2", h2);
            files.put(MEMBER_PREFIX + node.key, file);
        }
    }

    static String lang(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".java")) return "java";
        if (p.endsWith(".xml")) return "xml";
        if (p.endsWith(".yml") || p.endsWith(".yaml")) return "yaml";
        if (p.endsWith(".properties")) return "properties";
        return "text";
    }
}
