package com.example.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class EventSpoolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EventSpool spool(int lines, long bytes) throws Exception {
        return new EventSpool(new File(tmp.getRoot(), "pending.log"), lines, bytes);
    }

    @Test
    public void preservesOrderAndDrainsOnce() throws Exception {
        EventSpool s = spool(10, 64 * 1024);
        s.add("first");
        s.add("second");
        s.add("third");
        assertEquals(3, s.size());

        List<String> out = s.drain();
        assertEquals(3, out.size());
        assertEquals("first", out.get(0));
        assertEquals("second", out.get(1));
        assertEquals("third", out.get(2));
        assertEquals(0, s.size());
        assertTrue(s.drain().isEmpty());
    }

    @Test
    public void keepsOnlyTheNewestLinesWhenFull() throws Exception {
        EventSpool s = spool(3, 64 * 1024);
        for (int i = 1; i <= 5; i++) s.add("event-" + i);
        List<String> out = s.drain();
        assertEquals(3, out.size());
        assertEquals("event-3", out.get(0));
        assertEquals("event-4", out.get(1));
        assertEquals("event-5", out.get(2));
    }

    @Test
    public void flattensNewlinesSoOneEventIsOneLine() throws Exception {
        EventSpool s = spool(10, 64 * 1024);
        s.add("line1\nline2\r\nline3");
        List<String> out = s.drain();
        assertEquals(1, out.size());
        assertEquals("line1 line2  line3", out.get(0));
    }

    @Test
    public void enforcesByteBudgetByDroppingOldest() throws Exception {
        EventSpool s = spool(100, 256);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append('a');
        s.add("old-" + big);
        s.add("new-" + big);
        List<String> out = s.drain();
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("new-"));
    }

    @Test
    public void emptyAndNullAddsAreIgnored() throws Exception {
        EventSpool s = spool(10, 64 * 1024);
        s.add(null);
        s.add("");
        s.add("   ");
        assertEquals(0, s.size());
    }

    @Test
    public void missingFileDrainsEmpty() throws Exception {
        EventSpool s = spool(10, 64 * 1024);
        assertTrue(s.drain().isEmpty());
    }
}
