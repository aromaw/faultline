package com.branchdiff;

import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.MarkdownRenderer;
import com.branchdiff.report.Report;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.branchdiff.TestSupport.analyze;
import static com.branchdiff.TestSupport.dump;
import static com.branchdiff.TestSupport.of;
import static com.branchdiff.TestSupport.snap;
import static com.branchdiff.TestSupport.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Three-way checks of constant / field initializers and MyBatis XML statement bodies. */
class ValueChangeTest {
    static final String MIME = "src/main/java/a/util/Mime.java";
    static final String OTHER = "src/main/java/a/util/Other.java";

    static String mime(String exts, String limit, String status) {
        return """
                package a.util;
                public class Mime {
                    // allowed upload extensions
                    public static final String[] ALLOWED = { %s };
                    public static final int LIMIT = %s;
                    private int counter = 0;
                    public enum Kind { IMAGE("img"), DOC(%s) ; Kind(String s) { } }
                }
                """.formatted(exts, limit, status);
    }

    static final Snapshot BASE = snap("base",
            MIME, mime("\"png\", \"html\", \"txt\"", "10", "\"doc\""),
            OTHER, "package a.util; public class Other { public static final String X = \"x\"; }");

    @Test
    void theirsOnlyInitializerChangeIsLow() {
        Snapshot theirs = with(BASE, "theirs", MIME, mime("\"png\", \"txt\"", "10", "\"doc\""));
        Report r = analyze(BASE, BASE, theirs);
        List<Finding> fs = of(r, Category.CONSTANT_VALUE);
        assertEquals(1, fs.size(), dump(r));
        Finding f = fs.get(0);
        assertEquals("a.util.Mime.ALLOWED", f.symbol);
        assertEquals("CONSTANT", f.kind);
        assertEquals("THEIRS_CHANGED_VALUE", f.status);
        assertEquals(Finding.Severity.LOW, f.severity);
        assertEquals(MIME + ":4", f.theirsLocation);
        assertTrue(String.valueOf(f.details.get("diffBaseToTheirs")).contains("html"), f.details.toString());
        assertTrue(MarkdownRenderer.render(r).contains("## 4b."), "rendered in section 4b");
    }

    @Test
    void enumConstantArgumentChangeIsReported() {
        Snapshot theirs = with(BASE, "theirs", MIME, mime("\"png\", \"html\", \"txt\"", "10", "\"docx\""));
        Report r = analyze(BASE, BASE, theirs);
        List<Finding> fs = of(r, Category.CONSTANT_VALUE);
        assertEquals(1, fs.size(), dump(r));
        assertEquals("ENUM_CONSTANT", fs.get(0).kind);
        assertEquals("a.util.Mime.Kind.DOC", fs.get(0).symbol);
    }

    @Test
    void conflictingChangesAreHigh() {
        Snapshot ours = with(BASE, "ours", MIME, mime("\"png\", \"html\", \"txt\"", "20", "\"doc\""));
        Snapshot theirs = with(BASE, "theirs", MIME, mime("\"png\", \"html\", \"txt\"", "30", "\"doc\""));
        Report r = analyze(BASE, ours, theirs);
        List<Finding> fs = of(r, Category.CONSTANT_VALUE);
        assertEquals(1, fs.size(), dump(r));
        assertEquals("BOTH_CHANGED_VALUE", fs.get(0).status);
        assertEquals(Finding.Severity.HIGH, fs.get(0).severity);
        assertTrue(String.valueOf(fs.get(0).details.get("gitTextualConflict")).startsWith("YES"), fs.get(0).details.toString());
    }

    @Test
    void noChangeOursOnlyIdenticalAndFormattingOnlyAreNotReported() {
        // ours-only change, identical change on both sides (ALLOWED), comment/format-only change (Other)
        Snapshot ours = with(BASE, "ours", MIME, mime("\"png\", \"txt\"", "99", "\"doc\""));
        Snapshot theirs = with(BASE, "theirs", MIME, mime("\"png\",   \"txt\" /* no html */", "10", "\"doc\""),
                OTHER, "package a.util;\npublic class Other {\n    // the x\n    public static final String X =\n        \"x\";\n}\n");
        Report r = analyze(BASE, ours, theirs);
        assertTrue(of(r, Category.CONSTANT_VALUE).isEmpty(), dump(r));
        Report same = analyze(BASE, BASE, BASE);
        assertTrue(of(same, Category.CONSTANT_VALUE).isEmpty(), dump(same));
    }

    @Test
    void fieldRemovedByOursIsNotAValueFinding() {
        Snapshot ours = with(BASE, "ours", OTHER, "package a.util; public class Other { }");
        Snapshot theirs = with(BASE, "theirs", OTHER, "package a.util; public class Other { public static final String X = \"y\"; }");
        Report r = analyze(BASE, ours, theirs);
        assertTrue(of(r, Category.CONSTANT_VALUE).isEmpty(), dump(r));
    }

    // ---------------------------------------------------------------- MyBatis XML bodies

    static final String MAPPER = "src/main/java/a/mapper/ColMapper.java";
    static final String XML = "src/main/resources/mapper/ColMapper.xml";

    static String xml(String updateSet, String comment) {
        return """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="a.mapper.ColMapper">
                    <sql id="cols">select id, name from col</sql>
                    <select id="selectAll" resultType="map">
                        <include refid="cols"/>
                    </select>
                    %s
                    <update id="updateCol" parameterType="map">
                        update col
                        <set>
                %s
                        </set>
                        where id = #{id}
                    </update>
                </mapper>
                """.formatted(comment, updateSet);
    }

    static final String BASE_SET = """
                        <if test="name != null">name = #{name},</if>
                        <if test="javaType != null">java_type = #{javaType},</if>
            """;
    static final String THEIRS_SET = """
                        <if test="name != null">name = #{name},</if>
                        <if test="javaType != null">java_type = #{javaType},</if>
                        <if test="columnType != null">column_type = #{columnType},</if>
            """;

    static final Snapshot XBASE = snap("base",
            MAPPER, "package a.mapper; public interface ColMapper { java.util.List<Object> selectAll(); int updateCol(Object c); }",
            XML, xml(BASE_SET, ""));

    @Test
    void theirsOnlyStatementChangeIsLowWithIdAndLine() {
        Snapshot theirs = with(XBASE, "theirs", XML, xml(THEIRS_SET, ""));
        Report r = analyze(XBASE, XBASE, theirs);
        List<Finding> fs = r.findings.stream().filter(f -> f.status.equals("THEIRS_CHANGED_STATEMENT")).toList();
        assertEquals(1, fs.size(), dump(r));
        Finding f = fs.get(0);
        assertEquals(Category.MYBATIS, f.category);
        assertEquals(Finding.Severity.LOW, f.severity);
        assertEquals("MAPPER_UPDATE", f.kind);
        assertEquals("a.mapper.ColMapper#updateCol", f.symbol);
        assertEquals(XML + ":9", f.theirsLocation);
        assertEquals("update id=updateCol", f.details.get("statement"));
        assertTrue(String.valueOf(f.details.get("diffBaseToTheirs")).contains("column_type"), f.details.toString());
        assertEquals(1, of(r, Category.MYBATIS).size(), dump(r)); // nothing else (ids unchanged)
    }

    @Test
    void conflictingStatementChangesAreHigh() {
        Snapshot ours = with(XBASE, "ours", XML, xml(BASE_SET.replace("name = #{name}", "name = trim(#{name})"), ""));
        Snapshot theirs = with(XBASE, "theirs", XML, xml(THEIRS_SET, ""));
        Report r = analyze(XBASE, ours, theirs);
        List<Finding> fs = r.findings.stream().filter(f -> f.status.equals("BOTH_CHANGED_STATEMENT")).toList();
        assertEquals(1, fs.size(), dump(r));
        assertEquals(Finding.Severity.HIGH, fs.get(0).severity);
    }

    @Test
    void identicalFormattingAndCommentOnlyStatementChangesAreNotReported() {
        // both sides make the same change; theirs additionally reformats and adds an XML comment
        Snapshot ours = with(XBASE, "ours", XML, xml(THEIRS_SET, ""));
        Snapshot theirs = with(XBASE, "theirs", XML, xml(THEIRS_SET.replace("        <if", "  <if"), "<!-- columns -->"));
        Report r = analyze(XBASE, ours, theirs);
        assertTrue(of(r, Category.MYBATIS).isEmpty(), dump(r));
        Report none = analyze(XBASE, XBASE, XBASE);
        assertTrue(of(none, Category.MYBATIS).isEmpty(), dump(none));
        Report oursOnly = analyze(XBASE, ours, XBASE);
        assertTrue(of(oursOnly, Category.MYBATIS).isEmpty(), dump(oursOnly));
    }

    @Test
    void sqlFragmentChangeIsReported() {
        Snapshot theirs = with(XBASE, "theirs", XML, xml(BASE_SET, "").replace("select id, name from col", "select id, name, type from col"));
        Report r = analyze(XBASE, XBASE, theirs);
        List<Finding> fs = of(r, Category.MYBATIS);
        assertEquals(1, fs.size(), dump(r));
        assertEquals("MAPPER_SQL", fs.get(0).kind);
        assertEquals("a.mapper.ColMapper#cols", fs.get(0).symbol);
    }
}
