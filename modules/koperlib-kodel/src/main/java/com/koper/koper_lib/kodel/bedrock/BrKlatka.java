package com.koper.koper_lib.kodel.bedrock;

import net.minecraft.resources.Identifier;

// one mob's pose for one frame: what the native tick wrote, plus what the draw needs
public final class BrKlatka {
    public final int bones;
    public final float[] mats;
    // the same matrices before the base controller's part_visibility zeroed some. the other render
    // controllers on this geometry hide by their own visibility only (A&S: an all hidden helper first)
    public float[] surowe;
    public final byte[] vis;
    public final float[] local;
    public final int[] info = new int[8];
    public final int[] events = new int[16];
    // filled by the renderer glue after the tick
    public BrTyp.Geo geo;
    public Identifier texture;
    public boolean translucent;
    public float bodyYaw;
    public float sx = 1f, sy = 1f, sz = 1f;
    public int tint = 0xFFFFFFFF;
    // what change_color materials multiply their masked pixels by: the mob's color (sheep wool...)
    public int maskColor = 0xFFFFFFFF;
    public boolean hurt;
    // player style: offsets go onto the vanilla model instead of drawing our own
    public boolean onVanillaModel;
    public BrTyp.Malowanie malowanie;
    // the other render controllers that drew this frame: own geometry, texture, materials
    // wlasna: same geometry as the main frame, so an attachable still has to mirror these like its own
    public record Warstwa(BrTyp.Geo geo, float[] mats, Identifier tex, BrTyp.Malowanie m, boolean wlasna) {}
    public final java.util.List<Warstwa> warstwy = new java.util.ArrayList<>();
    // pack leans on bedrock's own player anims we dont have: java's walk / java's head look stand in
    public boolean vanillaRuch, vanillaGlowa;

    BrKlatka(int bones) {
        this.bones = bones;
        this.mats = new float[bones * 16];
        this.vis = new byte[bones];
        this.local = new float[bones * 9];
    }
}
