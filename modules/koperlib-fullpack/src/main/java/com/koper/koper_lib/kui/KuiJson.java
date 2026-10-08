package com.koper.koper_lib.kui;

import java.util.ArrayList;
import java.util.List;

// writes a layout file from code. built in screens have to live on disk because kui reads layouts
// when a screen opens, and hand rolling that json got ugly fast
public final class KuiJson {

    private final List<String> widgets = new ArrayList<>();

    public KuiJson panel(String id, int x, int y, int w, int h, Integer color) {
        return add("panel", id, x, y, w, h, null, color);
    }

    public KuiJson rule(String id, int x, int y, int w, int h, Integer color) {
        return add("rule", id, x, y, w, h, null, color);
    }

    public KuiJson label(String id, int x, int y, int w, String text, String align, Integer color) {
        StringBuilder sb = head("label", id, x, y);
        if (w > 0) sb.append(",\"w\":").append(w);
        sb.append(",\"text\":\"").append(esc(text)).append('"');
        if (align != null && !align.equals("left")) sb.append(",\"align\":\"").append(align).append('"');
        paint(sb, color);
        return push(sb);
    }

    public KuiJson list(String id, int x, int y, int w, int h) {
        return add("list", id, x, y, w, h, null, null);
    }

    public KuiJson progress(String id, int x, int y, int w, int h) {
        return add("progress", id, x, y, w, h, null, null);
    }

    public KuiJson button(String id, int x, int y, int w, int h, String text, Integer color) {
        return add("button", id, x, y, w, h, text, color);
    }

    public KuiJson item(String id, int x, int y) {
        StringBuilder sb = head("item", id, x, y);
        sb.append(",\"item\":\"minecraft:air\"");
        return push(sb);
    }

    public KuiJson input(String id, int x, int y, int w, int h, String placeholder) {
        StringBuilder sb = head("input", id, x, y);
        sb.append(",\"w\":").append(w).append(",\"h\":").append(h);
        sb.append(",\"placeholder\":\"").append(esc(placeholder)).append('"');
        return push(sb);
    }

    /** An input that reports every keystroke, for search boxes that should filter while typing. */
    public KuiJson liveInput(String id, int x, int y, int w, int h, String placeholder) {
        StringBuilder sb = head("input", id, x, y);
        sb.append(",\"w\":").append(w).append(",\"h\":").append(h);
        sb.append(",\"placeholder\":\"").append(esc(placeholder)).append('"');
        sb.append(",\"live\":true");
        return push(sb);
    }

    public KuiJson toggle(String id, int x, int y, int w, int h, String text, boolean checked) {
        StringBuilder sb = head("toggle", id, x, y);
        sb.append(",\"w\":").append(w).append(",\"h\":").append(h);
        sb.append(",\"text\":\"").append(esc(text)).append('"');
        sb.append(",\"checked\":").append(checked);
        return push(sb);
    }

    public KuiJson selector(String id, int x, int y, int w, int h, List<String> options, String selected) {
        StringBuilder sb = head("selector", id, x, y);
        sb.append(",\"w\":").append(w).append(",\"h\":").append(h);
        sb.append(",\"options\":[");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(options.get(i))).append('"');
        }
        sb.append(']');
        if (selected != null) sb.append(",\"selected\":\"").append(esc(selected)).append('"');
        return push(sb);
    }

    public String done() {
        StringBuilder sb = new StringBuilder("{\n  \"widgets\": [\n");
        for (int i = 0; i < widgets.size(); i++) {
            sb.append("    ").append(widgets.get(i));
            sb.append(i == widgets.size() - 1 ? "\n" : ",\n");
        }
        return sb.append("  ]\n}\n").toString();
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private KuiJson add(String type, String id, int x, int y, int w, int h, String text, Integer color) {
        StringBuilder sb = head(type, id, x, y);
        if (w > 0) sb.append(",\"w\":").append(w);
        if (h > 0) sb.append(",\"h\":").append(h);
        if (text != null) sb.append(",\"text\":\"").append(esc(text)).append('"');
        paint(sb, color);
        return push(sb);
    }

    private StringBuilder head(String type, String id, int x, int y) {
        return new StringBuilder("{\"type\":\"").append(type).append("\",\"id\":\"").append(id)
            .append("\",\"x\":").append(x).append(",\"y\":").append(y);
    }

    private void paint(StringBuilder sb, Integer color) {
        if (color != null) sb.append(",\"color\":\"#").append(String.format("%08X", color)).append('"');
    }

    private KuiJson push(StringBuilder sb) {
        widgets.add(sb.append('}').toString());
        return this;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
