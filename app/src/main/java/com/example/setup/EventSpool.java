package com.example.setup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, crash-safe on-disk queue. Used when the control panel is offline
 * (Wi-Fi dropped, phone away from home) so typing/alert events are not lost:
 * the next successful connection drains the spool in order.
 *
 * Plain java.io only — unit-testable without Android.
 */
public final class EventSpool {

    private final File file;
    private final int maxLines;
    private final long maxBytes;

    public EventSpool(File file, int maxLines, long maxBytes) {
        this.file = file;
        this.maxLines = Math.max(1, maxLines);
        this.maxBytes = Math.max(256, maxBytes);
    }

    /** Append one event (newlines are flattened so one event = one line). */
    public synchronized void add(String line) {
        if (line == null) return;
        String clean = line.replace('\n', ' ').replace('\r', ' ');
        if (clean.trim().isEmpty()) return;
        List<String> lines = readAll();
        lines.add(clean);
        trim(lines);
        writeAll(lines);
    }

    /** Return every queued event and empty the spool. Order is preserved. */
    public synchronized List<String> drain() {
        List<String> lines = readAll();
        if (!lines.isEmpty()) writeAll(new ArrayList<>());
        return lines;
    }

    public synchronized int size() {
        return readAll().size();
    }

    private List<String> readAll() {
        List<String> out = new ArrayList<>();
        if (!file.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isEmpty()) out.add(l);
            }
        } catch (Exception ignored) {
            // unreadable spool -> treat as empty rather than crash the service
        }
        return out;
    }

    private void trim(List<String> lines) {
        while (lines.size() > maxLines) lines.remove(0);
        long bytes = 0;
        for (String l : lines) bytes += l.getBytes(StandardCharsets.UTF_8).length + 1L;
        while (lines.size() > 1 && bytes > maxBytes) {
            bytes -= lines.remove(0).getBytes(StandardCharsets.UTF_8).length + 1L;
        }
    }

    private void writeAll(List<String> lines) {
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (String l : lines) {
                    w.write(l);
                    w.write('\n');
                }
            }
            if (file.exists() && !file.delete()) {
                // fall through to copy
            }
            if (!tmp.renameTo(file)) {
                try (InputStream in = new FileInputStream(tmp);
                     OutputStream out = new FileOutputStream(file)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
