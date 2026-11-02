package com.branchdiff.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small helpers for type names, bean names and Spring relaxed property names. */
public final class Names {
    private Names() {
    }

    /** Removes generic arguments: {@code java.util.List<java.lang.String>} -> {@code java.util.List}. */
    public static String eraseGenerics(String type) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : type.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * Normalizes a type for signature comparison: erases generics, keeps the simple name, normalizes varargs to arrays.
     * {@code java.util.Map<K,V>} -> {@code Map}; {@code java.lang.String...} -> {@code String[]}; {@code Outer.Inner} -> {@code Inner}.
     */
    public static String simpleErased(String type) {
        String t = eraseGenerics(type).replace("...", "[]").replace(" ", "");
        // annotations inside type use (rare)
        while (t.startsWith("@")) {
            int sp = t.indexOf(' ');
            if (sp < 0) break;
            t = t.substring(sp + 1);
        }
        String dims = "";
        while (t.endsWith("[]")) {
            dims += "[]";
            t = t.substring(0, t.length() - 2);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        if (t.startsWith("?")) t = "Object";
        return t + dims;
    }

    public static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    public static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /** Same algorithm as {@code java.beans.Introspector.decapitalize}, used by Spring for default bean names. */
    public static String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** camelCase -> kebab-case (Spring canonical property form). */
    public static String kebab(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Relaxed-binding comparison form of a property key: lower-case, '-' and '_' removed, list indexes kept.
     * {@code order.maxItems}, {@code order.max-items}, {@code ORDER_MAX_ITEMS} (partially) all map closely.
     */
    public static String relaxedKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").trim();
    }

    /** Parses one placeholder body list from an @Value string: "${a.b:def}-${c}" -> [a.b:def, c]. SpEL #{..} ignored. */
    public static List<String> placeholderBodies(String value) {
        List<String> res = new ArrayList<>();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) break;
            int depth = 0;
            int j = start;
            for (; j < value.length(); j++) {
                if (value.startsWith("${", j)) {
                    depth++;
                    j++;
                } else if (value.charAt(j) == '}') {
                    depth--;
                    if (depth == 0) break;
                }
            }
            if (j >= value.length()) break;
            res.add(value.substring(start + 2, j));
            i = j + 1;
        }
        return res;
    }

    /** Splits "key:default" at the first top-level ':' . Returns [key, default-or-null]. */
    public static String[] splitPlaceholder(String body) {
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.startsWith("${", i)) {
                depth++;
                i++;
            } else if (body.charAt(i) == '}') {
                depth--;
            } else if (body.charAt(i) == ':' && depth == 0) {
                return new String[]{body.substring(0, i).trim(), body.substring(i + 1)};
            }
        }
        return new String[]{body.trim(), null};
    }
}
