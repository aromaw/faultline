package com.branchdiff.index;

import java.util.Set;

/** Records describing Spring / config facts found in one version. */
public final class SpringModel {
    private SpringModel() {
    }

    public record BeanDef(String typeFqn, Set<String> names, String kind, String file, int line) {
        public String location() {
            return file + ":" + line;
        }
    }

    /**
     * An injection point. {@code key()} identifies it across versions.
     *
     * @param via field / constructor / lombok-constructor / setter / bean-method-param
     */
    public record InjectionPoint(String ownerFqn, String member, String typeRaw, String typeFqn, String qualifier,
                                 boolean optional, String via, String file, int line) {
        public String key() {
            return ownerFqn + "|" + member + "|" + typeFqn + "|" + qualifier;
        }

        public String location() {
            return file + ":" + line;
        }
    }

    /** A property placeholder reference such as {@code @Value("${a.b}")} or {@code env.getProperty("a.b")}. */
    public record ValueRef(String ownerFqn, String member, String key, String defaultValue, String raw, String via,
                           String file, int line) {
        public String identity() {
            return ownerFqn + "|" + key;
        }

        public String location() {
            return file + ":" + line;
        }
    }

    public record ConfigPropsClass(String classFqn, String prefix, String file, int line) {
    }
}
