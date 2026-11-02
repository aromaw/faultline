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

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
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
                if (n instanceof Element e && STATEMENT_TAGS.contains(e.getTagName())) {
                    String id = e.getAttribute("id");
                    if (id.isEmpty()) continue;
                    stmts.put(id, new Statement(ns, id, e.getTagName(), path, lineOf(content, e.getTagName(), id)));
                }
            }
        } catch (Exception e) {
            errors.add(path + ": " + e.getMessage());
        }
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
