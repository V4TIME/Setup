package com.example.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TypingPayloadTest {

    @Test
    public void joinsAccessibilityTextParts() {
        assertEquals("hello world",
                TypingPayload.normalize(Arrays.asList("hello", "world")));
    }

    @Test
    public void collapsesWhitespaceAndTrims() {
        assertEquals("hello world",
                TypingPayload.normalize("  hello \n\n world  "));
    }

    @Test
    public void keepsUserBrackets() {
        // v1.2 stripped every '[' and ']' — a search for "[test]" became "test".
        assertEquals("[test]", TypingPayload.normalize("[test]"));
        assertEquals("a [b] c", TypingPayload.normalize(" a [b]\tc "));
    }

    @Test
    public void boundsRunawayText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append('x');
        assertEquals(TypingPayload.MAX_CHARS,
                TypingPayload.normalize(sb.toString()).length());
    }

    @Test
    public void passwordFieldsAreNeverSent() {
        assertTrue(TypingPayload.isPassword(true, null));
        assertTrue(TypingPayload.isPassword(false, Boolean.TRUE));
        assertFalse(TypingPayload.isPassword(false, Boolean.FALSE));
        assertFalse(TypingPayload.isPassword(false, null));

        assertFalse(TypingPayload.shouldSend("hunter2", "com.whatsapp", true, null));
    }

    @Test
    public void ignoresEmptyAndExactRepeats() {
        assertFalse(TypingPayload.shouldSend("", "com.android.chrome", false, null));
        assertFalse(TypingPayload.shouldSend("   ", "com.android.chrome", false, null));

        String key = TypingPayload.key("com.android.chrome", "cats");
        assertFalse(TypingPayload.shouldSend("cats", "com.android.chrome", false, key));
        // same text in another app is a new event
        assertTrue(TypingPayload.shouldSend("cats", "com.whatsapp", false, key));
        // different text in the same app is a new event
        assertTrue(TypingPayload.shouldSend("dogs", "com.android.chrome", false, key));
    }

    @Test
    public void keyIsStableAcrossWhitespace() {
        assertEquals(TypingPayload.key("p", "a  b"),
                TypingPayload.key("p", " a b "));
    }

    @Test
    public void nullInputsAreSafe() {
        assertEquals("", TypingPayload.normalize((String) null));
        assertEquals("", TypingPayload.normalize((java.util.List<?>) null));
        assertEquals("", TypingPayload.normalize(Collections.emptyList()));
        assertFalse(TypingPayload.shouldSend(null, null, false, null));
    }
}
