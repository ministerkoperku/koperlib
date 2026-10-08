package com.koper.koper_lib.kui;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// java side of kui: lua had koper.gui events since forever, addons had nothing. now a mod can grab
// button/slider/toggle pokes from a gui it registered and push values back into the open screen.
public final class KuiPoke {

    public static final String OPTIONS_PREFIX = "\u001eoptions\u001f";
    public static final String TIP_PREFIX = "\u001etip\u001f";

    // action is what the client called it: "click" | "slider" | "toggle" | "input" | "select"
    public record Poke(ServerPlayer player, String guiId, String widget, String action,
                       float value, String text, boolean checked) {

        public boolean is(String widgetId) { return widget.equals(widgetId); }

        // sliders arrive 0..1, everybody wants it in their own range
        public float scaled(float min, float max) { return min + (max - min) * Math.clamp(value, 0f, 1f); }
    }

    @FunctionalInterface
    public interface Handler {
        // true = eaten, no further handler for this gui runs
        boolean poked(Poke poke);
    }

    private static final Map<String, List<Handler>> BY_GUI = new ConcurrentHashMap<>();
    private static final List<Handler> ANY = new CopyOnWriteArrayList<>();
    private static final Map<java.util.UUID, String> OPEN = new ConcurrentHashMap<>();

    private KuiPoke() {}

    // gui id as registered, instance suffix ("engine#3") is stripped before matching
    public static void on(String guiId, Handler handler) {
        BY_GUI.computeIfAbsent(strip(guiId), ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public static void onAny(Handler handler) {
        ANY.add(handler);
    }

    public static boolean fire(ServerPlayer player, String guiId, String widget, String action,
                               float value, String text, boolean checked) {
        Poke poke = new Poke(player, guiId, widget, action, value, text, checked);
        for (Handler handler : BY_GUI.getOrDefault(strip(guiId), List.of()))
            if (handler.poked(poke)) return true;
        for (Handler handler : ANY)
            if (handler.poked(poke)) return true;
        return false;
    }

    // ── open screen bookkeeping ──────────────────────────────────────────────
    // KuiOpen tells us who is looking at what, so a mod can push live numbers only while it matters

    public static void opened(ServerPlayer player, String rawId) {
        OPEN.put(player.getUUID(), rawId);
    }

    public static void closed(ServerPlayer player) {
        OPEN.remove(player.getUUID());
    }

    // swapping pages makes the client report the OLD screen closing after the new one was already
    // recorded. taking that at face value wiped the open page and left every later poke dead
    public static void closed(ServerPlayer player, String guiId) {
        String open = OPEN.get(player.getUUID());
        if (open == null || strip(open).equals(strip(guiId))) OPEN.remove(player.getUUID());
    }

    public static String openGui(ServerPlayer player) {
        return OPEN.get(player.getUUID());
    }

    public static boolean looking(ServerPlayer player, String guiId) {
        String open = OPEN.get(player.getUUID());
        return open != null && strip(open).equals(strip(guiId));
    }

    // ── pushing values back ──────────────────────────────────────────────────

    // set a widget on the screen the player has open. labels take text, sliders/progress take 0..1,
    // toggles take true/false. also remembered in the session so a reopen shows the same thing
    public static void set(ServerPlayer player, String guiId, String widget, String value) {
        KuiSessions.put(player.getUUID(), strip(guiId), widget, value);
        ServerPlayNetworking.send(player,
            new com.koper.koper_lib.network.KuiWidgetUpdatePayload(widget, value));
    }

    public static void set(ServerPlayer player, String guiId, String widget, float value) {
        set(player, guiId, widget, Float.toString(value));
    }

    public static void set(ServerPlayer player, String guiId, String widget, boolean value) {
        set(player, guiId, widget, Boolean.toString(value));
    }

    // no session write — for readouts (rpm, load) that would be stale the moment the screen reopens
    public static void text(ServerPlayer player, String widget, String value) {
        ServerPlayNetworking.send(player,
            new com.koper.koper_lib.network.KuiWidgetUpdatePayload(widget, value));
    }

    /**
     * Text plus the full version behind it: the client draws {@code shown} and puts {@code full}
     * in the hover tooltip. For rows the server has to shorten itself, like a goal with a counter
     * glued on the end. An empty {@code full} clears the tooltip.
     */
    public static void text(ServerPlayer player, String widget, String shown, String full) {
        ServerPlayNetworking.send(player,
            new com.koper.koper_lib.network.KuiWidgetUpdatePayload(widget, TIP_PREFIX + full + "\u001f" + shown));
    }

    public static void options(ServerPlayer player, String widget, java.util.List<String> values) {
        String packed = OPTIONS_PREFIX + String.join("\u001f", values);
        ServerPlayNetworking.send(player,
            new com.koper.koper_lib.network.KuiWidgetUpdatePayload(widget, packed));
    }

    // handlers are registered once at mod init — a /koperlib reload must NOT drop them, only the
    // "who has what open" bookkeeping
    public static void clear() {
        OPEN.clear();
    }

    private static String strip(String rawId) {
        int hash = rawId.indexOf('#');
        return hash >= 0 ? rawId.substring(0, hash) : rawId;
    }
}
