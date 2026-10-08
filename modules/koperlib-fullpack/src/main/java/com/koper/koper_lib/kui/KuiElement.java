package com.koper.koper_lib.kui;

// one widget from the layout json. flat bag of fields — not every type uses every field,
// keeps parsing dumb and the screen switch readable. coords are relative to the panel top-left.
public final class KuiElement {
    public String type = "label";
    public String id = "";
    public int x, y, w, h;
    public String text = "";
    public String tooltip = "";
    public int color = 0xFF404040;
    public boolean tinted = false;      // json actually said "color", so panels/buttons stop using the theme grey
    public String align = "left";       // label/rule: left | center | right

    public int cols = 1, rows = 1, gap = 0; // grid
    public float value = 0f;                // progress / slider (0..1)
    public float min = 0f, max = 0f, step = 0f; // slider: max>min shows the real number, step snaps
    public boolean checked = false;         // toggle
    public boolean container = false;       // grid/slot: real synced item slots, not just a drawing
    public boolean craft = false;           // button: tries a recipe against the open container on click
    public String role = "";                // slot/grid role: "" / "input" / "output" (take-only result slot)

    public String placeholder = "";         // input
    public boolean live = false;            // input: send on every keystroke, not only on enter
    public java.util.List<String> frames = java.util.List.of(); // image: animation frame texture ids
    public java.util.List<String> options = java.util.List.of(); // selector/radio/list/segmented
    public String selected = "";
    public int fps = 0;                     // image frame rate / auto-progress period seconds
    public String anim = "";                // "auto" (progress auto-fill), etc.
	public float previewZoom = 1.0f;         // entity preview multiplier after automatic fit

    // text/options given as chat component json, the client turns them into strings with its own lang
    public String textJson = "";
    public java.util.List<String> optionsJson = java.util.List.of();
    public int selectedIndex = -1;
    public boolean flow = false;            // stacked top to bottom by the client, y = gap above, labels wrap
    public transient java.util.List<String> lines; // wrapped label

    // live state, mutated by interaction
    public String inputText = "";
    public boolean focused = false;

    // resolved hit box in absolute screen space — filled by the screen each layout pass
    public transient int absX, absY, absW, absH;
    public transient int scroll;

    public boolean hit(double mx, double my) {
        // a label is usually given no height, which left it no area to hover and its tooltip unreachable
        int height = absH > 0 ? absH : type.equals("label") ? 10 : 0;
        return mx >= absX && mx < absX + absW && my >= absY && my < absY + height;
    }
}
