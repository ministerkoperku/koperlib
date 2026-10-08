package com.koper.koper_lib.kui;

// one registered gui screen. main json picks mode + paths, rest is filled by GuiFactory.
// mode "json" = layout drives the look, mode "texture" = a baked/custom png does
public final class KuiPage {
    public String id;             // full id e.g. mypack:forge_menu
    public String namespace;      // owning pack namespace (for resolving layout/texture/script)
    public String mode = "json";  // "json" | "texture"
    public String title = "";
    public int w = 176;
    public int h = 166;

    public String layout;     // path to *.layout.json (mode json) — relative, as written in the json
    public String layoutFile; // resolved absolute path on disk (server reads it at open time)
    public String texture;     // path to baked/custom png (mode texture)
    public String textureFile; // resolved absolute png path on disk
    public String regionsFile; // resolved absolute <base>.regions.json path
    public String script;      // lua script id for logic — wired in later phase

    public java.util.List<KuiRecipe> recipes = new java.util.ArrayList<>(); // shapeless recipes for this gui
    public java.util.Set<String> craftButtons = new java.util.HashSet<>();  // button ids that try a craft on click
    public int hudX = 4, hudY = 4; // screen anchor when this layout is shown as a hud overlay

    public boolean isTexture() { return "texture".equalsIgnoreCase(mode); }

    @Override public String toString() {
        return "KuiPage[" + id + " mode=" + mode + " " + w + "x" + h + "]";
    }
}
