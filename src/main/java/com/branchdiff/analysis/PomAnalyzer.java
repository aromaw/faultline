package com.branchdiff.analysis;

import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Finding;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maven dependencies (groupId:artifactId) declared in theirs' service pom.xml files but not in ours'. */
public final class PomAnalyzer {
    private PomAnalyzer() {
    }

    static Map<String, String> deps(Snapshot s) {
        Map<String, String> res = new LinkedHashMap<>();
        String prefix = s.serviceDir().isEmpty() ? "" : s.serviceDir() + "/";
        for (var e : s.files().entrySet()) {
            if (!e.getKey().endsWith("pom.xml") || !e.getKey().startsWith(prefix)) continue;
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));
                NodeList list = doc.getElementsByTagName("dependency");
                for (int i = 0; i < list.getLength(); i++) {
                    Element d = (Element) list.item(i);
                    Node parent = d.getParentNode().getParentNode();
                    if (parent != null && "dependencyManagement".equals(parent.getNodeName())) continue;
                    if (parent != null && !"project".equals(parent.getNodeName()) && !"profile".equals(parent.getNodeName())) continue;
                    String ga = text(d, "groupId") + ":" + text(d, "artifactId");
                    res.putIfAbsent(ga, e.getKey());
                }
            } catch (Exception ignored) {
                // unparsable pom: skip
            }
        }
        return res;
    }

    private static String text(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? "?" : l.item(0).getTextContent().trim();
    }

    public static List<Finding> analyze(Snapshot base, Snapshot ours, Snapshot theirs) {
        Map<String, String> b = deps(base), o = deps(ours), t = deps(theirs);
        List<Finding> res = new ArrayList<>();
        for (var e : t.entrySet()) {
            // only dependencies theirs ADDED; ones ours deliberately dropped (present in base) are ours' decision
            if (o.containsKey(e.getKey()) || b.containsKey(e.getKey())) continue;
            String origin = "THEIRS_NEW";
            res.add(new Finding(Finding.Category.MAVEN_DEPENDENCY, Finding.Severity.MEDIUM, Finding.Confidence.CERTAIN,
                    "MISSING", "MAVEN_DEPENDENCY", e.getKey(), origin,
                    "Dependency declared in theirs' pom but not in ours'").theirsAt(e.getValue()));
        }
        return res;
    }
}
