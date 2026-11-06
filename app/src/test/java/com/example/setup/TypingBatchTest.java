package com.example.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TypingBatchTest {

    private TypingBatch.Event ev(String text, String pkg, String app, long t, int seq) {
        return new TypingBatch.Event(text, pkg, app, t, seq);
    }

    @Test
    public void fillsAtThresholdAndDrainsInOrder() {
        TypingBatch b = new TypingBatch(3);
        assertFalse(b.add(ev("a", "p", "A", 1, 1)));
        assertFalse(b.add(ev("b", "p", "A", 2, 2)));
        assertTrue(b.add(ev("c", "p", "A", 3, 3)));
        assertEquals(3, b.size());

        List<TypingBatch.Event> out = b.drain();
        assertEquals(3, out.size());
        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
        assertEquals("c", out.get(2).text);
        assertEquals(0, b.size());
        assertTrue(b.drain().isEmpty());
    }

    @Test
    public void nullsAreIgnoredAndMinimumSizeIsOne() {
        TypingBatch b = new TypingBatch(0);
        assertFalse(b.add(null));
        assertTrue(b.add(ev("x", null, null, 0, 0)));
    }

    @Test
    public void eventJsonContainsContextAndNumbers() {
        String json = ev("buy milk", "com.whatsapp", "WhatsApp", 1793000000000L, 7).toJson();
        assertEquals("{\"query\":\"buy milk\",\"pkg\":\"com.whatsapp\",\"app\":\"WhatsApp\""
                + ",\"time\":1793000000000,\"seq\":7}", json);
    }

    @Test
    public void escapesQuotesBackslashesAndControlChars() {
        assertEquals("a\\\"b", TypingBatch.escape("a\"b"));
        assertEquals("a\\\\b", TypingBatch.escape("a\\b"));
        assertEquals("a\\nb", TypingBatch.escape("a\nb"));
        assertEquals("a\\tb", TypingBatch.escape("a\tb"));
        assertEquals("\\u0001", TypingBatch.escape("\u0001"));
        assertEquals("[test]", TypingBatch.escape("[test]"));
        assertEquals("naïve ✓", TypingBatch.escape("naïve ✓"));
        assertEquals("", TypingBatch.escape(null));
    }

    @Test
    public void batchEnvelopeIsWellFormed() {
        List<TypingBatch.Event> list = Arrays.asList(
                ev("one", "p1", "App1", 10, 1),
                ev("two", null, null, 20, 2));
        String json = TypingBatch.toJson("samsung SM-M356B", list);
        assertEquals("{\"device\":\"samsung SM-M356B\",\"events\":["
                + "{\"query\":\"one\",\"pkg\":\"p1\",\"app\":\"App1\",\"time\":10,\"seq\":1},"
                + "{\"query\":\"two\",\"time\":20,\"seq\":2}]}", json);
    }

    @Test
    public void emptyBatchEnvelope() {
        assertEquals("{\"device\":\"d\",\"events\":[]}",
                TypingBatch.toJson("d", new ArrayList<>()));
    }

    @Test
    public void wrapsSpooledJsonWithoutReEncoding() {
        List<String> raw = Arrays.asList(
                "{\"query\":\"a \\\"quoted\\\"\",\"time\":1,\"seq\":1}",
                "{\"query\":\"b\",\"time\":2,\"seq\":2}");
        String json = TypingBatch.wrap("Pixel 7", raw);
        assertEquals("{\"device\":\"Pixel 7\",\"events\":[" + raw.get(0) + "," + raw.get(1) + "]}", json);
    }

    @Test
    public void deviceIsEscaped() {
        assertEquals("{\"device\":\"a\\\"b\",\"events\":[]}",
                TypingBatch.toJson("a\"b", new ArrayList<>()));
    }
}
