package com.koper.koper_lib.kui;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// remembers what each player set on each gui (slider %, toggle, typed text) so reopening shows it again
// instead of snapping back to the layout defaults. lives only in memory — good enough for a session.
public final class KuiSessions {
    // player -> guiId -> widget -> serialized value
    private static final Map<UUID, Map<String, Map<String, String>>> STATE = new ConcurrentHashMap<>();

    private KuiSessions() {}

    public static void put(UUID player, String guiId, String widget, String value) {
        if (widget == null || widget.isEmpty()) return;
        STATE.computeIfAbsent(player, p -> new ConcurrentHashMap<>())
             .computeIfAbsent(guiId, g -> new ConcurrentHashMap<>())
             .put(widget, value);
    }

    // current widget values as a flat json object, "" if the player never touched this gui
    public static String stateJson(UUID player, String guiId) {
        Map<String, Map<String, String>> byGui = STATE.get(player);
        if (byGui == null) return "";
        Map<String, String> widgets = byGui.get(guiId);
        if (widgets == null || widgets.isEmpty()) return "";
        JsonObject o = new JsonObject();
        widgets.forEach(o::addProperty);
        return o.toString();
    }

    public static void clear(UUID player) { STATE.remove(player); }
}
