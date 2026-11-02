package com.branchdiff.git;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the sibling modules of the service directory inside the same repository (other Maven modules, or other
 * directories with {@code src/main/java}) whose sources should be visible to symbol resolution and lookups.
 * <ul>
 *   <li>{@link Mode#DEPS} (default): only modules the service pom depends on (transitively through other sibling
 *   modules), matched by groupId:artifactId (artifactId alone when the groupId is a property or inherited from
 *   an unknown parent).</li>
 *   <li>{@link Mode#ALL}: every other {@code <dir>/src/main/java} in the repository (Maven modules listed in
 *   aggregator poms as well as plain directories), whether the service depends on it or not.</li>
 *   <li>{@link Mode#NONE}: no siblings (behaviour before this feature).</li>
 * </ul>
 * Discovery runs on every given commit and returns the union, so a module that exists or is depended on in only
 * one version is still loaded from all versions (where it is absent, it is simply empty).
 */
public final class SiblingModules {
    public enum Mode {
        NONE, DEPS, ALL;

        public static Mode parse(String s) {
            return switch (s.trim().toLowerCase()) {
                case "none", "off", "false", "no" -> NONE;
                case "deps", "dependencies", "on", "true", "yes" -> DEPS;
                case "all" -> ALL;
                default -> throw new IllegalArgumentException("--include-siblings must be deps, all or none (got '" + s + "')");
            };
        }
    }

    public static final String MAIN_JAVA = "src/main/java/";

    /** groupId/artifactId/deps of one pom. */
    public record Pom(String dir, String groupId, String artifactId, List<String[]> deps) {
    }

    private SiblingModules() {
    }

    /**
     * @return repo-relative directories (no trailing slash, "" = repo root) whose {@code src/main/java} should be
     * loaded as sibling sources, sorted.
     */
    public static List<String> discover(GitRunner git, List<String> commits, String serviceDir, Mode mode) {
        if (mode == Mode.NONE || serviceDir.isEmpty()) return List.of();
        Set<String> res = new TreeSet<>();
        for (String c : new LinkedHashSet<>(commits)) {
            Map<String, String> blobs = git.listBlobs(c, "");
            Map<String, String> pomBlobs = new LinkedHashMap<>();
            for (var e : blobs.entrySet()) {
                String p = e.getKey();
                if (p.equals("pom.xml") || p.endsWith("/pom.xml")) pomBlobs.put(p, e.getValue());
            }
            Map<String, String> poms = git.readBlobs(pomBlobs);
            res.addAll(select(blobs.keySet(), poms, serviceDir, mode));
        }
        return new ArrayList<>(res);
    }

    /** Pure selection logic (testable without git). */
    public static Set<String> select(Set<String> paths, Map<String, String> pomFiles, String serviceDir, Mode mode) {
        Set<String> javaDirs = new TreeSet<>();
        for (String p : paths) {
            if (!p.endsWith(".java")) continue;
            int i = p.startsWith(MAIN_JAVA) ? 0 : p.indexOf("/" + MAIN_JAVA);
            if (i < 0) continue;
            String dir = i == 0 ? "" : p.substring(0, i);
            if (!excluded(dir, serviceDir)) javaDirs.add(dir);
        }
        Map<String, Pom> poms = new LinkedHashMap<>();
        for (var e : pomFiles.entrySet()) {
            Pom pom = parse(e.getKey(), e.getValue());
            if (pom != null) poms.put(pom.dir(), pom);
        }
        Set<String> res = new TreeSet<>();
        if (mode == Mode.ALL) {
            res.addAll(javaDirs);
            return res;
        }
        Pom service = poms.get(serviceDir);
        if (service == null) return res;
        Deque<String[]> q = new ArrayDeque<>(service.deps());
        Set<String> seen = new LinkedHashSet<>();
        while (!q.isEmpty()) {
            String[] ga = q.poll();
            if (!seen.add(ga[0] + ":" + ga[1])) continue;
            for (Pom m : match(poms.values(), ga[0], ga[1])) {
                if (m.dir().equals(serviceDir) || excluded(m.dir(), serviceDir)) continue;
                if (javaDirs.contains(m.dir())) res.add(m.dir());
                q.addAll(m.deps());
            }
        }
        return res;
    }

    private static boolean excluded(String dir, String serviceDir) {
        return dir.equals(serviceDir) || dir.startsWith(serviceDir + "/");
    }

    private static List<Pom> match(Iterable<Pom> poms, String groupId, String artifactId) {
        List<Pom> exact = new ArrayList<>();
        List<Pom> byArtifact = new ArrayList<>();
        for (Pom p : poms) {
            if (!p.artifactId().equals(artifactId)) continue;
            byArtifact.add(p);
            if (p.groupId().equals(groupId)) exact.add(p);
        }
        if (!exact.isEmpty()) return exact;
        // groupId written as ${project.groupId} / inherited and unknown: accept a unique artifactId match
        return byArtifact.size() == 1 ? byArtifact : List.of();
    }

    public static Pom parse(String path, String content) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            Element project = doc.getDocumentElement();
            String dir = path.equals("pom.xml") ? "" : path.substring(0, path.length() - "/pom.xml".length());
            String artifactId = child(project, "artifactId");
            String groupId = child(project, "groupId");
            Element parent = childEl(project, "parent");
            if (groupId == null && parent != null) groupId = child(parent, "groupId");
            List<String[]> deps = new ArrayList<>();
            NodeList list = doc.getElementsByTagName("dependency");
            for (int i = 0; i < list.getLength(); i++) {
                Element d = (Element) list.item(i);
                Node owner = d.getParentNode() == null ? null : d.getParentNode().getParentNode();
                if (owner == null || !("project".equals(owner.getNodeName()) || "profile".equals(owner.getNodeName()))) continue;
                String scope = child(d, "scope");
                if ("test".equals(scope)) continue;
                deps.add(new String[]{String.valueOf(child(d, "groupId")), String.valueOf(child(d, "artifactId"))});
            }
            if (artifactId == null) return null;
            return new Pom(dir, groupId == null ? "?" : groupId, artifactId, deps);
        } catch (Exception e) {
            return null;
        }
    }

    private static Element childEl(Element e, String tag) {
        List<Element> l = children(e, tag);
        return l.isEmpty() ? null : l.get(0);
    }

    private static String child(Element e, String tag) {
        Element c = childEl(e, tag);
        return c == null ? null : c.getTextContent().trim();
    }

    private static List<Element> children(Element e, String tag) {
        List<Element> res = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && el.getTagName().equals(tag)) res.add(el);
        }
        return res;
    }
}
