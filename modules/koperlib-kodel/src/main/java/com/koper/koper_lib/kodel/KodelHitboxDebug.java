package com.koper.koper_lib.kodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

// own OBB hitbox debug view — toggle with /koperlib obb. draws per-bone OBBs for model entities with obb_hitboxes on
// entity-only (mc only shows entity hitboxes on F3+B too). keybind module isn't on cp, so a command toggle it is
public final class KodelHitboxDebug {

    private KodelHitboxDebug() {}

    private static volatile boolean show = false;

    public static boolean toggle() { show = !show; return show; }

    // 8 corner sign combos + the 12 edges connecting them
    private static final int[][] EDGES = {
        {0,1},{0,2},{0,4},{1,3},{1,5},{2,3},{2,6},{3,7},{4,5},{4,6},{5,7},{6,7}
    };

    public static void init() {
        LevelRenderEvents.COLLECT_SUBMITS.register(KodelHitboxDebug::onCollect);
    }

    private static void onCollect(LevelRenderContext ctx) {
        if (!show) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var collector = ctx.submitNodeCollector();
        var poseStack = ctx.poseStack();
        var cam = mc.gameRenderer.mainCamera().position();

        try {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!KodelEntities.obb(e.getType())) continue;
            KodelBook.Entry entry = KodelBook.get(KodelEntities.model(e.getType()));
            if (entry == null) continue;
            List<KodelHitboxer.Obb> obbs = entry.hitboxes();
            if (obbs.isEmpty()) continue;

            double ex = e.getX(), ey = e.getY(), ez = e.getZ();
            float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
            float rad = (float) Math.toRadians(180f - yaw);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);

            for (KodelHitboxer.Obb o : obbs) {
                // model space -> rotate around Y by (180-yaw) -> world (scale 1, matches renderer)
                float[] c = place(o.cx() / 16f, o.cy() / 16f + 0.01f, o.cz() / 16f, cos, sin, ex, ey, ez, cam.x, cam.y, cam.z);
                float[] ax = dir(o.axx() / 16f, o.axy() / 16f, o.axz() / 16f, cos, sin);
                float[] ay = dir(o.ayx() / 16f, o.ayy() / 16f, o.ayz() / 16f, cos, sin);
                float[] az = dir(o.azx() / 16f, o.azy() / 16f, o.azz() / 16f, cos, sin);

                float[][] corners = new float[8][3];
                for (int i = 0; i < 8; i++) {
                    float sx = (i & 1) != 0 ? 1 : -1, sy = (i & 2) != 0 ? 1 : -1, sz = (i & 4) != 0 ? 1 : -1;
                    corners[i][0] = c[0] + sx * ax[0] + sy * ay[0] + sz * az[0];
                    corners[i][1] = c[1] + sx * ax[1] + sy * ay[1] + sz * az[1];
                    corners[i][2] = c[2] + sx * ax[2] + sy * ay[2] + sz * az[2];
                }

                poseStack.pushPose();
                collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, consumer) -> {
                    drawBox(pose, consumer, corners);
                });
                poseStack.popPose();
            }
        }
        } catch (RuntimeException t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[kodel] obb debug view does not draw: {}", t.toString());
        }
    }

    // rotate a model-space point around Y by the entity yaw, then offset to world, relative to camera
    private static float[] place(float x, float y, float z, float cos, float sin,
                                 double ex, double ey, double ez, double cx, double cy, double cz) {
        float rx = x * cos + z * sin;
        float rz = -x * sin + z * cos;
        return new float[]{(float) (ex + rx - cx), (float) (ey + y - cy), (float) (ez + rz - cz)};
    }

    // rotate a direction (no translation)
    private static float[] dir(float x, float y, float z, float cos, float sin) {
        return new float[]{x * cos + z * sin, y, -x * sin + z * cos};
    }

    private static void drawBox(PoseStack.Pose pose, VertexConsumer vc, float[][] c) {
        int color = 0xFFFF3030; // red
        for (int[] e : EDGES) {
            float[] a = c[e[0]], b = c[e[1]];
            float nx = b[0] - a[0], ny = b[1] - a[1], nz = b[2] - a[2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; }
            // lines render type REQUIRES a per-vertex line width — missing it = "Missing elements: LineWidth" crash
            vc.addVertex(pose, a[0], a[1], a[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(2.5f);
            vc.addVertex(pose, b[0], b[1], b[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(2.5f);
        }
    }
}
