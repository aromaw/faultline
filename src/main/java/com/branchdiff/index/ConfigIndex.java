package com.branchdiff.index;

import com.branchdiff.util.Names;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** All keys of application*.yml / bootstrap*.yml / *.properties of one version (all profiles merged). */
public final class ConfigIndex {
    public record KeyLoc(String key, String file, int line, String profile) {
        public String location() {
            return file + ":" + line;
        }
    }

    private static final Pattern CONFIG_FILE =
            Pattern.compile("(^|.*/)(application|bootstrap)([-_.][^/]*)?\\.(yml|yaml|properties)$");

    /** relaxed key -> locations */
    private final Map<String, List<KeyLoc>> keys = new LinkedHashMap<>();
    private final List<String> files = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public static boolean isConfigFile(String path) {
        return CONFIG_FILE.matcher(path).matches() && !path.contains("/src/test/");
    }

    public void addFile(String path, String content) {
        files.add(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        String profile = profileOf(name);
        try {
            if (name.endsWith(".properties")) parseProperties(path, content, profile);
            else parseYaml(path, content, profile);
        } catch (RuntimeException e) {
            errors.add(path + ": " + e.getMessage());
        }
    }

    private static String profileOf(String fileName) {
        String base = fileName.replaceAll("\\.(yml|yaml|properties)$", "");
        int dash = base.indexOf('-');
        return dash < 0 ? "default" : base.substring(dash + 1);
    }

    private void parseProperties(String path, String content, String profile) {
        String[] lines = content.split("\r?\n", -1);
        StringBuilder logical = null;
        int startLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (logical == null) {
                String t = line.stripLeading();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
                logical = new StringBuilder();
                startLine = i + 1;
            }
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1);
                continue;
            }
            logical.append(line);
            String l = logical.toString().stripLeading();
            logical = null;
            int sep = -1;
            for (int j = 0; j < l.length(); j++) {
                char c = l.charAt(j);
                if (c == '\\') {
                    j++;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    sep = j;
                    break;
                }
            }
            String key = (sep < 0 ? l : l.substring(0, sep)).replace("\\", "");
            if (!key.isEmpty()) put(key, path, startLine, profile);
        }
    }

    private record Raw(String key, int line, String value) {
    }

    private void parseYaml(String path, String content, String profile) {
        Yaml yaml = new Yaml(new LoaderOptions());
        for (Node doc : yaml.composeAll(new StringReader(content))) {
            if (doc == null) continue;
            List<Raw> collected = new ArrayList<>();
            walk(doc, "", collected);
            String docProfile = profile;
            for (Raw r : collected) {
                String rk = Names.relaxedKey(r.key());
                if ((rk.equals("spring.profiles") || rk.equals("spring.config.activate.onprofile")) && r.value() != null) {
                    docProfile = r.value();
                }
            }
            for (Raw r : collected) put(r.key(), path, r.line(), docProfile);
        }
    }

    private void walk(Node node, String prefix, List<Raw> out) {
        if (node instanceof MappingNode m) {
            for (NodeTuple t : m.getValue()) {
                if (!(t.getKeyNode() instanceof ScalarNode k)) continue;
                String kk = k.getValue();
                if (kk.startsWith("[") && kk.endsWith("]")) kk = kk.substring(1, kk.length() - 1);
                String child = prefix.isEmpty() ? kk : prefix + "." + kk;
                Node v = t.getValueNode();
                out.add(new Raw(child, k.getStartMark().getLine() + 1, v instanceof ScalarNode s ? s.getValue() : null));
                walk(v, child, out);
            }
        } else if (node instanceof SequenceNode seq) {
            int idx = 0;
            for (Node item : seq.getValue()) {
                String ik = prefix + "[" + idx++ + "]";
                out.add(new Raw(ik, item.getStartMark().getLine() + 1, item instanceof ScalarNode s ? s.getValue() : null));
                walk(item, ik, out);
            }
        }
    }

    private void put(String key, String file, int line, String profile) {
        keys.computeIfAbsent(Names.relaxedKey(key), k -> new ArrayList<>())
                .add(new KeyLoc(key, file, line, profile == null ? "default" : profile));
    }

    /** True if the key itself, or any child of it (map/list), is defined in any config file of this version. */
    public boolean has(String key) {
        return !find(key).isEmpty();
    }

    public List<KeyLoc> find(String key) {
        String rk = Names.relaxedKey(key);
        List<KeyLoc> exact = keys.get(rk);
        if (exact != null) return exact;
        List<KeyLoc> res = new ArrayList<>();
        for (Map.Entry<String, List<KeyLoc>> e : keys.entrySet()) {
            if (e.getKey().startsWith(rk + ".") || e.getKey().startsWith(rk + "[")) {
                res.addAll(e.getValue());
                break;
            }
        }
        return res;
    }

    /** All keys defined under {@code prefix.} (relaxed) */
    public List<KeyLoc> under(String prefix) {
        String rk = Names.relaxedKey(prefix) + ".";
        List<KeyLoc> res = new ArrayList<>();
        for (Map.Entry<String, List<KeyLoc>> e : keys.entrySet()) {
            if (e.getKey().startsWith(rk)) res.addAll(e.getValue());
        }
        return res;
    }

    public List<String> files() {
        return files;
    }

    public List<String> errors() {
        return errors;
    }

    public int size() {
        return keys.size();
    }
}
