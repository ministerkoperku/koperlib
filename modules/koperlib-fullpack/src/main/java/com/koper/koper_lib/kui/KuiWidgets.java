package com.koper.koper_lib.kui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.koper.koper_lib.network.KuiActionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.List;

// the interactive brain shared by both kui screens: hit-testing, focus, slider drag, typing,
// and firing KuiActionPayload to the server. the screens just forward raw input events here.
@Environment(EnvType.CLIENT)
public final class KuiWidgets {
    private final String guiId;
    private final List<KuiElement> elements;
    private String status = "";
    private KuiElement draggingSlider;

    public KuiWidgets(String guiId, List<KuiElement> elements) {
        this.guiId = guiId;
        this.elements = elements;
    }

    public List<KuiElement> elements() { return elements; }
    public String status() { return status; }

    public void layout(int left, int top) {
        for (KuiElement e : elements) {
            e.absX = left + e.x;
            e.absY = top + e.y;
            e.absW = e.w;
            e.absH = e.h;
        }
    }

    public void applyState(String stateJson) {
        if (stateJson == null || stateJson.isBlank() || elements.isEmpty()) return;
        try {
            JsonObject o = new Gson().fromJson(stateJson, JsonObject.class);
            if (o == null) return;
            for (KuiElement e : elements) {
                if (e.id.isEmpty() || !o.has(e.id)) continue;
                String v = o.get(e.id).getAsString();
                switch (e.type) {
                    case "slider", "progress" -> { try { e.value = Float.parseFloat(v); } catch (Exception ignored) {} }
                    case "toggle" -> e.checked = Boolean.parseBoolean(v);
                    case "input"  -> e.inputText = v;
                    default -> {}
                }
            }
        } catch (Exception ignored) {}
    }

    // ── input ───────────────────────────────────────────────────────────────────

    public boolean mouseClicked(double mx, double my) {
        KuiElement prevFocused = focusedInput();
        KuiElement clickedInput = null;
        for (KuiElement e : elements)
            if (e.type.equals("input") && e.hit(mx, my)) clickedInput = e;
        for (KuiElement e : elements)
            if (e.type.equals("input")) setFocused(e, e == clickedInput);
        if (prevFocused != null && prevFocused != clickedInput) commitInput(prevFocused);
        if (clickedInput != null) { status = "input " + label(clickedInput); return true; }

        for (KuiElement e : elements) {
            if (!e.hit(mx, my)) continue;
            switch (e.type) {
                case "button" -> { status = "clicked " + (e.text.isEmpty() ? label(e) : e.text); send(e.id, "click", 0f, "", false); return true; }
                case "toggle" -> { e.checked = !e.checked; status = "toggle " + label(e) + " = " + e.checked;
                                   send(e.id, "toggle", 0f, "", e.checked); return true; }
                case "slider" -> { draggingSlider = e; setSlider(e, mx); return true; }
                case "selector", "segmented", "radio", "list" -> {
                    String picked = pickOption(e, mx, my);
                    if (picked == null) return true;
                    e.selected = picked;
                    status = "selected " + label(e) + " = " + picked;
                    send(e.id, "select", optionIndex(e, picked), picked, false);
                    return true;
                }
                default -> {}
            }
        }
        return false;
    }

    public boolean mouseDragged(double mx) {
        if (draggingSlider != null) { setSlider(draggingSlider, mx); return true; }
        return false;
    }

    public boolean mouseReleased() {
        if (draggingSlider != null) {
            send(draggingSlider.id, "slider", draggingSlider.value, "", false);
            draggingSlider = null;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double dy) {
        for (KuiElement e : elements) {
            if (!e.type.equals("list") || !e.hit(mx, my)) continue;
            int visible = Math.max(1, e.absH / 14);
            int max = Math.max(0, e.options.size() - visible);
            e.scroll = Mth.clamp(e.scroll + (dy > 0 ? -1 : dy < 0 ? 1 : 0), 0, max);
            return true;
        }
        return false;
    }

    public boolean keyPressed(int key) {
        KuiElement inp = focusedInput();
        if (inp == null) return false;
        switch (key) {
            case InputConstants.KEY_ESCAPE -> { setFocused(inp, false); commitInput(inp); return true; }
            case InputConstants.KEY_BACKSPACE -> {
                if (!inp.inputText.isEmpty()) {
                    inp.inputText = inp.inputText.substring(0, inp.inputText.length() - 1);
                    if (inp.live) commitInput(inp);
                }
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> { setFocused(inp, false); commitInput(inp); return true; }
            default -> { return true; }
        }
    }

    public boolean charTyped(int cp) {
        KuiElement inp = focusedInput();
        if (inp == null) return false;
        if (cp >= 32 && cp != 127 && inp.inputText.length() < 256) {
            inp.inputText += new String(Character.toChars(cp));
            if (inp.live) commitInput(inp);
        }
        return true;
    }

    public void onClosed() {
        KuiElement inp = focusedInput();
        if (inp != null) { setFocused(inp, false); commitInput(inp); }
        send("", "close", 0f, "", false); // server stops pushing live values into a screen nobody sees
    }

    // koper.gui.set(widget, value) from the server — push a new value into a widget on the open screen/hud
    public boolean update(String widget, String value) {
        boolean any = false;
        if (value.startsWith(KuiPoke.TIP_PREFIX)) {
            String packed = value.substring(KuiPoke.TIP_PREFIX.length());
            int split = packed.indexOf('\u001f');
            String full = split < 0 ? "" : packed.substring(0, split);
            String shown = split < 0 ? packed : packed.substring(split + 1);
            boolean found = update(widget, shown);
            for (KuiElement e : elements)
                if (e.id.equals(widget)) e.tooltip = full;
            return found;
        }
        for (KuiElement e : elements) {
            if (!e.id.equals(widget)) continue;
            if ((e.type.equals("selector") || e.type.equals("segmented")
                    || e.type.equals("radio") || e.type.equals("list"))
                    && value.startsWith(KuiPoke.OPTIONS_PREFIX)) {
                String packed = value.substring(KuiPoke.OPTIONS_PREFIX.length());
                e.options = packed.isEmpty()
                    ? java.util.List.of()
                    : java.util.List.of(packed.split("\u001f", -1));
                if (!e.options.contains(e.selected))
                    e.selected = e.options.isEmpty() ? "" : e.options.getFirst();
                int visible = Math.max(1, e.absH / 14);
                e.scroll = Mth.clamp(e.scroll, 0, Math.max(0, e.options.size() - visible));
                any = true;
                continue;
            }
            switch (e.type) {
                case "slider", "progress" -> { try { e.value = Float.parseFloat(value); } catch (Exception ignored) {} }
                case "toggle" -> e.checked = Boolean.parseBoolean(value);
                case "input"  -> e.inputText = value;
                case "selector", "segmented", "radio", "list" -> {
                    e.selected = value;
                    if (e.type.equals("list")) {
                        int index = e.options.indexOf(value);
                        int visible = Math.max(1, e.absH / 14);
                        if (index >= 0 && index < e.scroll) e.scroll = index;
                        else if (index >= e.scroll + visible) e.scroll = index - visible + 1;
                    }
                }
                default -> e.text = value; // label / button / item etc.
            }
            any = true;
        }
        return any;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void commitInput(KuiElement e) {
        send(e.id, "input", 0f, e.inputText, false);
        status = "entered " + label(e) + ": " + e.inputText;
    }

    private void setSlider(KuiElement e, double mx) {
        e.value = Mth.clamp((float) ((mx - (e.absX + 4)) / (e.absW - 8)), 0f, 1f);
        if (e.max > e.min && e.step > 0f) {
            float snapped = e.min + Math.round((e.value * (e.max - e.min)) / e.step) * e.step;
            e.value = Mth.clamp((snapped - e.min) / (e.max - e.min), 0f, 1f);
        }
        status = "slider " + label(e) + " = " + KuiPaint.sliderText(e);
    }

    private void send(String widget, String action, float value, String text, boolean checked) {
        ClientPlayNetworking.send(new KuiActionPayload(guiId, widget, action, value, text, checked));
    }

    private static String pickOption(KuiElement e, double mx, double my) {
        if (e.options.isEmpty()) return null;
        if (e.type.equals("selector") || e.type.equals("segmented")) {
            // narrow ones draw as a cycler, so the edges step and the middle does nothing
            if (KuiPaint.cramped(e)) {
                int at = Math.max(0, e.options.indexOf(e.selected.isEmpty() ? e.options.getFirst() : e.selected));
                int step = mx < e.absX + 10 ? -1 : mx >= e.absX + e.absW - 10 ? 1 : 0;
                if (step == 0) return null;
                return e.options.get(Math.floorMod(at + step, e.options.size()));
            }
            int idx = Mth.clamp((int)((mx - e.absX) * e.options.size() / Math.max(1, e.absW)), 0, e.options.size() - 1);
            return e.options.get(idx);
        }
        int rowH = e.type.equals("list") ? 14 : Math.max(12, e.absH / Math.max(1, e.options.size()));
        int idx = (e.type.equals("list") ? e.scroll : 0)
            + Mth.clamp((int)((my - e.absY) / rowH), 0, e.options.size() - 1);
        idx = Mth.clamp(idx, 0, e.options.size() - 1);
        return e.options.get(idx);
    }

    private static int optionIndex(KuiElement e, String picked) {
        int i = e.options.indexOf(picked);
        return i < 0 ? 0 : i;
    }

    // SDL only delivers typed characters while text input is started, so every focus change on an
    // input box has to go through the TextInputManager or charTyped never fires
    private static void setFocused(KuiElement e, boolean focused) {
        if (e.focused == focused) return;
        e.focused = focused;
        Minecraft.getInstance().textInputManager().onTextInputFocusChange(e, focused);
    }

    private KuiElement focusedInput() {
        for (KuiElement e : elements)
            if (e.type.equals("input") && e.focused) return e;
        return null;
    }

    private static String label(KuiElement e) {
        return e.id.isEmpty() ? ("<" + e.type + ">") : e.id;
    }
}
