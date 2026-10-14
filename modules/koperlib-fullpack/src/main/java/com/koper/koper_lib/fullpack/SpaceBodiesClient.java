package com.koper.koper_lib.fullpack;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.network.SpaceBodiesPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// draws the space bodies of the dimension the player is in. a body past most of the view distance is
// drawn nearer and smaller by the same factor, so it keeps its size on screen without being clipped
// by the far plane. the cost: something between the drawn and the real distance hides behind it
public final class SpaceBodiesClient {
    private SpaceBodiesClient() {}

    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("koper_lib", "textures/misc/white.png");
    private static final int LIGHT = 0xF000F0;
    private static final int STACKS = 24, SLICES = 48;
    private static volatile List<SpaceBodiesPayload.Body> bodies = List.of();
    private static final Set<String> FAILED = new HashSet<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SpaceBodiesPayload.TYPE, (payload, context) -> bodies = payload.bodies());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> bodies = List.of());
        LevelRenderEvents.COLLECT_SUBMITS.register(SpaceBodiesClient::collect);
    }

    private static void collect(LevelRenderContext ctx) {
        var list = bodies;
        Minecraft mc = Minecraft.getInstance();
        if (list.isEmpty() || mc.level == null) return;
        String dimension = mc.level.dimension().identifier().toString();
        var cam = mc.gameRenderer.mainCamera().position();
        double far = Math.max(mc.options.getEffectiveRenderDistance() * 64.0, mc.options.cloudRange().get() * 16.0) * 0.8;
        float time = (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20f;
        for (var body : list) {
            if (!body.space().equals(dimension)) continue;
            double rx = body.x() - cam.x, ry = body.y() - cam.y, rz = body.z() - cam.z;
            double distance = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (distance <= body.radius()) continue;
            double scale = distance > far ? far / distance : 1;
            float cx = (float) (rx * scale), cy = (float) (ry * scale), cz = (float) (rz * scale);
            float spin = (float) Math.toRadians(body.spin() * time);
            try {
                Identifier texture = Identifier.parse(body.texture());
                ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.entitySolid(texture), (pose, consumer) ->
                    sphere(pose, consumer, cx, cy, cz, (float) (body.radius() * scale), spin, body.color()));
                if ((body.glow() >>> 24) != 0 && body.atmosphere() > 0)
                    ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.entityTranslucentEmissive(WHITE), (pose, consumer) ->
                        sphere(pose, consumer, cx, cy, cz, (float) ((body.radius() + body.atmosphere()) * scale), 0, body.glow()));
            } catch (RuntimeException e) {
                if (FAILED.add(body.id())) KoperLib.LOGGER.error("[Space] space body {} does not draw", body.id(), e);
            }
        }
    }

    private static void sphere(PoseStack.Pose pose, VertexConsumer out, float cx, float cy, float cz,
                               float r, float spin, int color) {
        for (int i = 0; i < STACKS; i++) {
            double t0 = Math.PI * i / STACKS, t1 = Math.PI * (i + 1) / STACKS;
            for (int j = 0; j < SLICES; j++) {
                double p0 = 2 * Math.PI * j / SLICES + spin, p1 = 2 * Math.PI * (j + 1) / SLICES + spin;
                float u0 = (float) j / SLICES, u1 = (float) (j + 1) / SLICES;
                float v0 = (float) i / STACKS, v1 = (float) (i + 1) / STACKS;
                // counter clockwise seen from outside
                vertex(pose, out, cx, cy, cz, r, t0, p0, u0, v0, color);
                vertex(pose, out, cx, cy, cz, r, t0, p1, u1, v0, color);
                vertex(pose, out, cx, cy, cz, r, t1, p1, u1, v1, color);
                vertex(pose, out, cx, cy, cz, r, t1, p0, u0, v1, color);
            }
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer out, float cx, float cy, float cz, float r,
                               double theta, double phi, float u, float v, int color) {
        float nx = (float) (Math.sin(theta) * Math.cos(phi));
        float ny = (float) Math.cos(theta);
        float nz = (float) (Math.sin(theta) * Math.sin(phi));
        out.addVertex(pose, cx + nx * r, cy + ny * r, cz + nz * r).setColor(color).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LIGHT).setNormal(pose, nx, ny, nz);
    }
}
