package com.branchdiff.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A 128-bit digest (the first 16 bytes of SHA-1 of the UTF-8 text) plus the text's length in chars: equal texts have
 * equal fingerprints, and two different texts collide with a probability of about 2^-128. The index keeps
 * fingerprints of the normalized member text instead of the text itself, which is most of a summary's size, and
 * prints the text again from the AST when a report needs it.
 */
public record Fingerprint(long high, long low, int length) {
    private static final ThreadLocal<MessageDigest> SHA1 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    public static final Fingerprint EMPTY = of("");

    public static Fingerprint of(String text) {
        MessageDigest md = SHA1.get();
        md.reset();
        ByteBuffer b = ByteBuffer.wrap(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        return new Fingerprint(b.getLong(), b.getLong(), text.length());
    }

    /** whether {@code text} has this fingerprint */
    public boolean matches(String text) {
        return text != null && text.length() == length && equals(of(text));
    }
}
