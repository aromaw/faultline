package com.branchdiff.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One reported problem. Public fields so Jackson can serialize it as-is. */
public final class Finding {
    public enum Category {
        MISSING_DEPENDENCY, SPRING_BEAN, SPRING_CONFIG, INTERFACE_IMPL, MYBATIS, MAVEN_DEPENDENCY,
        SIGNATURE_IMPACT, BOTH_MODIFIED
    }

    public enum Severity {HIGH, MEDIUM, LOW}

    public enum Confidence {CERTAIN, UNCERTAIN}

    public record Usage(String by, String location) {
    }

    public Category category;
    public Severity severity;
    public Confidence confidence;
    /** MISSING / INCONSISTENT / STILL_USES_OLD_SIGNATURE / BOTH_MODIFIED / ... */
    public String status;
    /** TYPE / METHOD / FIELD / CONSTANT / CONSTRUCTOR / BEAN / PROPERTY / MAPPER_STATEMENT / ... */
    public String kind;
    public String symbol;
    /** THEIRS_NEW (theirs introduced it), OURS_REMOVED / OURS_CHANGED (ours dropped/changed something base had) */
    public String origin;
    public String message;
    public String theirsLocation;
    public String oursLocation;
    public List<Usage> neededBy = new ArrayList<>();
    public Map<String, Object> details = new LinkedHashMap<>();

    public Finding(Category category, Severity severity, Confidence confidence, String status, String kind,
                   String symbol, String origin, String message) {
        this.category = category;
        this.severity = severity;
        this.confidence = confidence;
        this.status = status;
        this.kind = kind;
        this.symbol = symbol;
        this.origin = origin;
        this.message = message;
    }

    public Finding theirsAt(String loc) {
        this.theirsLocation = loc;
        return this;
    }

    public Finding oursAt(String loc) {
        this.oursLocation = loc;
        return this;
    }

    public Finding neededBy(String by, String location) {
        for (Usage u : neededBy) if (u.by().equals(by) && u.location().equals(location)) return this;
        neededBy.add(new Usage(by, location));
        return this;
    }

    public Finding detail(String k, Object v) {
        details.put(k, v);
        return this;
    }

    public boolean uncertain() {
        return confidence == Confidence.UNCERTAIN;
    }
}
