package com.branchdiff.index;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MyBatis mapper XML files: namespace -> statement id -> location. */
public final class MapperXmlIndex {
    public record Statement(String namespace, String id, String kind, String file, int line) {
        public String location() {
            return file + ":" + line;
        }
    }

    /** Normalized content of one statement-like element, used for three-way body comparison. */
    public record Body(String namespace, String id, String tag, String normalized, String file, int line) {
        public String key() {
            return tag + ":" + id;
        }

        public String location() {
            return file + ":" + line;
        }
    }

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    /** elements whose content is compared across versions (statements plus reusable fragments and result maps) */
    private static final Set<String> BODY_TAGS = Set.of("select", "insert", "update", "delete", "sql", "resultMap");
    /** namespace -> tag:id -> body */
    private final Map<String, Map<String, Body>> bodies = new LinkedHashMap<>();
    private final Map<String, Map<String, Statement>> byNamespace = new LinkedHashMap<>();
    private final Map<String, String> namespaceFile = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();

    public static boolean maybeMapperXml(String path, String content) {
        return path.endsWith(".xml") && !path.endsWith("pom.xml") && !path.contains("/src/test/")
                && content.contains("<mapper") && content.contains("namespace");
    }

    public void addFile(String path, String content) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setValidating(false);
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            Document doc = b.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            if (!"mapper".equals(root.getTagName())) return;
            String ns = root.getAttribute("namespace");
            if (ns == null || ns.isBlank()) return;
            namespaceFile.put(ns, path);
            Map<String, Statement> stmts = byNamespace.computeIfAbsent(ns, k -> new LinkedHashMap<>());
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n instanceof Element e && BODY_TAGS.contains(e.getTagName())) {
                    String id = e.getAttribute("id");
                    if (id.isEmpty()) continue;
                    int line = lineOf(content, e.getTagName(), id);
                    if (STATEMENT_TAGS.contains(e.getTagName())) stmts.put(id, new Statement(ns, id, e.getTagName(), path, line));
                    bodies.computeIfAbsent(ns, k -> new LinkedHashMap<>()).put(e.getTagName() + ":" + id,
                            new Body(ns, id, e.getTagName(), normalize(e), path, line));
                }
            }
        } catch (Exception e) {
            errors.add(path + ": " + e.getMessage());
        }
    }

    /**
     * Renders an element canonically: comments dropped, attributes sorted, whitespace in text collapsed, one line per
     * element or text run, so formatting-only and comment-only edits compare equal.
     */
    static String normalize(Element e) {
        StringBuilder sb = new StringBuilder();
        render(e, sb, 0);
        return sb.toString().strip();
    }

    private static void render(Element e, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent).append('<').append(e.getTagName());
        var attrs = e.getAttributes();
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
        for (int i = 0; i < attrs.getLength(); i++) sorted.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
        sorted.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(v.strip().replaceAll("\\s+", " ")).append('"'));
        sb.append(">\n");
        NodeList children = e.getChildNodes();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(' ').append(c.getNodeValue());
            } else if (c instanceof Element ce) {
                flushText(text, sb, depth + 1);
                render(ce, sb, depth + 1);
            }
            // comments and processing instructions are ignored
        }
        flushText(text, sb, depth + 1);
        sb.append(indent).append("</").append(e.getTagName()).append(">\n");
    }

    private static void flushText(StringBuilder text, StringBuilder sb, int depth) {
        String t = text.toString().strip().replaceAll("\\s+", " ");
        text.setLength(0);
        if (!t.isEmpty()) sb.append("  ".repeat(depth)).append(t).append('\n');
    }

    public Map<String, Body> bodies(String ns) {
        return bodies.getOrDefault(ns, Map.of());
    }

    public Set<String> bodyNamespaces() {
        return bodies.keySet();
    }

    private static int lineOf(String content, String tag, String id) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\bid\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"']").matcher(content);
        if (!m.find()) return 0;
        int line = 1;
        for (int i = 0; i < m.start(); i++) if (content.charAt(i) == '\n') line++;
        return line;
    }

    public boolean hasNamespace(String ns) {
        return byNamespace.containsKey(ns);
    }

    public Map<String, Statement> statements(String ns) {
        return byNamespace.getOrDefault(ns, Map.of());
    }

    public Set<String> namespaces() {
        return byNamespace.keySet();
    }

    public String fileOf(String ns) {
        return namespaceFile.get(ns);
    }

    public List<String> errors() {
        return errors;
    }
}
