package com.branchdiff.util;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class Ast {
    private Ast() {
    }

    private static final ThreadLocal<DefaultPrettyPrinter> PRINTER = ThreadLocal.withInitial(() -> {
        DefaultPrinterConfiguration cfg = new DefaultPrinterConfiguration();
        cfg.removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS));
        cfg.removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_JAVADOC));
        return new DefaultPrettyPrinter(cfg);
    });

    /** Pretty prints a node without comments/javadoc so formatting-only or comment-only edits compare equal. */
    public static String normalized(Node node) {
        return PRINTER.get().print(node);
    }

    public static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int line(Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(0);
    }

    public static int endLine(Node n) {
        return n.getRange().map(r -> r.end.line).orElse(0);
    }

    public static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> n, String simpleName) {
        for (AnnotationExpr a : n.getAnnotations()) {
            if (Names.simpleName(a.getNameAsString()).equals(simpleName)) return Optional.of(a);
        }
        return Optional.empty();
    }

    public static boolean hasAnnotation(NodeWithAnnotations<?> n, String... simpleNames) {
        for (String s : simpleNames) if (annotation(n, s).isPresent()) return true;
        return false;
    }

    /** Returns string values of an annotation attribute ("value" also covers single-member form). */
    public static List<String> stringValues(AnnotationExpr a, String... attrs) {
        List<String> res = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr s) {
            for (String attr : attrs) {
                if (attr.equals("value")) collectStrings(s.getMemberValue(), res);
            }
        } else if (a instanceof NormalAnnotationExpr na) {
            for (MemberValuePair p : na.getPairs()) {
                for (String attr : attrs) {
                    if (p.getNameAsString().equals(attr)) collectStrings(p.getValue(), res);
                }
            }
        }
        return res;
    }

    public static Optional<Expression> attr(AnnotationExpr a, String attr) {
        if (a instanceof SingleMemberAnnotationExpr s && attr.equals("value")) return Optional.of(s.getMemberValue());
        if (a instanceof NormalAnnotationExpr na) {
            for (MemberValuePair p : na.getPairs()) if (p.getNameAsString().equals(attr)) return Optional.of(p.getValue());
        }
        return Optional.empty();
    }

    private static void collectStrings(Expression e, List<String> out) {
        if (e instanceof StringLiteralExpr s) out.add(s.asString());
        else if (e instanceof TextBlockLiteralExpr t) out.add(t.asString());
        else if (e instanceof ArrayInitializerExpr arr) arr.getValues().forEach(v -> collectStrings(v, out));
        else if (e.isBinaryExpr()) {
            // "a" + "b" constant concatenation
            List<String> parts = new ArrayList<>();
            e.findAll(StringLiteralExpr.class).forEach(s -> parts.add(s.asString()));
            out.add(String.join("", parts));
        }
    }

    public static void removeComments(Node n) {
        for (Comment c : n.getAllContainedComments()) c.remove();
        n.getComment().ifPresent(Comment::remove);
    }
}
