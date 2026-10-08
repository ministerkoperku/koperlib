package com.koper.koper_lib.core;

import com.koper.koper_lib.kfx.KfxApi;
import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.runtime.KfxPlayRequest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

// one ring of every particle style, a sigil, a spiral and a beam, all facing the viewer. lets
// somebody judge the particle look side by side instead of casting spells one at a time
public final class KfxGallery {

    public static final String[] STYLES = {"sprite", "spark", "star", "ring", "shard", "cube", "tetra", "orb3d", "gem"};
    private static final int[] COLORS = {0xFF55CCFF, 0xFFFFB347, 0xFFFFE066, 0xFFB967FF, 0xFF66FFD9,
        0xFFFF5577, 0xFF88FF66, 0xFFEE66FF, 0xFF6699FF};
    public static final String[] BEAMS = {"tube", "square", "lightning", "helix", "pulse"};
    private static final int[] BEAM_COLORS = {0xFF55CCFF, 0xFFFF5577, 0xFFB9A0FF, 0xFF66FFB0, 0xFFFFAA33};
    private static final int LIFETIME = 1200;
    private static boolean declared;

    private KfxGallery() {}

    private static void declare() {
        if (declared) return;
        for (int i = 0; i < STYLES.length; i++) {
            KfxApi.declareGraph(KfxApi.graph("koperstuff:gallery_" + STYLES[i])
                .node("shape", "koper_lib:source/ring").number("radius", 0.7).number("spin", 0.35).number("depth", 0.12).end()
                .node("fx", "koper_lib:render/particles").link("source", "shape").integer("count", 12)
                    .number("size", 0.11).text("style", STYLES[i]).color("color", COLORS[i]).end()
                .output("fx").budget(64, LIFETIME).build());
        }
        KfxApi.declareGraph(KfxApi.graph("koperstuff:gallery_sigil")
            .node("shape", "koper_lib:source/sigil").number("radius", 1.4).integer("points", 5).integer("skip", 2).end()
            .node("fx", "koper_lib:render/particles").link("source", "shape").integer("count", 60)
                .number("size", 0.06).text("style", "orb3d").color("color", 0xFFB967FF).end()
            .output("fx").budget(96, LIFETIME).build());
        KfxApi.declareGraph(KfxApi.graph("koperstuff:gallery_spiral")
            .node("shape", "koper_lib:source/spiral").number("radius", 0.6).number("depth", 2.2).end()
            .node("fx", "koper_lib:render/particles").link("source", "shape").integer("count", 48)
                .number("size", 0.07).text("style", "spark").color("color", 0xFFFFB347).end()
            .output("fx").budget(64, LIFETIME).build());
        for (int i = 0; i < BEAMS.length; i++) {
            KfxApi.declareGraph(KfxApi.graph("koperstuff:gallery_beam_" + BEAMS[i])
                .node("beam", "koper_lib:render/beam").number("thickness", 0.12).text("style", BEAMS[i])
                    .color("color", BEAM_COLORS[i]).end()
                .output("beam").budget(8, LIFETIME).build());
        }
        declared = true;
    }

    // a 3x3 grid of styles in a wall `distance` blocks ahead, the extras to its left and right
    public static int spawn(ServerLevel level, Vec3 eye, Vec3 look, double distance) {
        declare();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        Vec3 right = new Vec3(-flat.z, 0, flat.x);
        Vec3 wall = eye.add(flat.scale(distance));
        Vec3 toViewer = flat.scale(-1);
        int spawned = 0;
        for (int i = 0; i < STYLES.length; i++) {
            Vec3 c = wall.add(right.scale((i % 3 - 1) * 1.9)).add(0, (1 - i / 3) * 1.9, 0);
            play(level, "koperstuff:gallery_" + STYLES[i], c, c.add(toViewer), i);
            spawned++;
        }
        Vec3 left = wall.add(right.scale(-4.6));
        play(level, "koperstuff:gallery_sigil", left, left.add(toViewer), 101);
        Vec3 spiral = wall.add(right.scale(4.4)).add(0, 0, 0);
        play(level, "koperstuff:gallery_spiral", spiral.add(0, -0.4, 0), spiral.add(0, 1.6, 0), 102);
        for (int i = 0; i < BEAMS.length; i++) {
            Vec3 a = wall.add(right.scale(-3.0)).add(0, -2.7 - i * 0.75, 0);
            play(level, "koperstuff:gallery_beam_" + BEAMS[i], a, a.add(right.scale(6.0)), 103 + i);
        }
        return spawned + 2 + BEAMS.length;
    }

    private static void play(ServerLevel level, String graph, Vec3 start, Vec3 end, long seed) {
        KfxApi.play(level, new KfxPlayRequest(graph, Map.of(),
            new KfxAnchor.World(start, new Vec3(0, 1, 0), KfxMissingPolicy.KILL),
            new KfxAnchor.World(end, new Vec3(0, 1, 0), KfxMissingPolicy.KILL), seed));
    }
}
