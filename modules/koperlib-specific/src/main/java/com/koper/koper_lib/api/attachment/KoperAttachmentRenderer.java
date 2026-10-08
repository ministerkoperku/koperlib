package com.koper.koper_lib.api.attachment;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

@Environment(EnvType.CLIENT)
public final class KoperAttachmentRenderer {
    private static final int PREVIEW_OK = 0xff45e07b;
    private static final int PREVIEW_BLOCKED = 0xffff4050;
    private static final java.util.Map<BlockState, BlockModelRenderState> PREVIEW_MODELS =
        new java.util.HashMap<>();
    private static BlockModelResolver blockModels;

    private KoperAttachmentRenderer() {}

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            var held = mc.player.getMainHandItem();
            if (KoperAttachments.hasPlacementPreview(held.getItem())
                    && held.getItem() instanceof BlockItem blockItem
                    && mc.hitResult instanceof BlockHitResult hit) {
                var moving = com.koper.koper_lib.kender.KenderTargeting.getHit();
                BlockHitResult placementHit = moving == null ? hit : movingPlacementHit(moving);
                BlockPlaceContext placement = new BlockPlaceContext(
                    mc.player, InteractionHand.MAIN_HAND, held, placementHit);
                var state = blockItem.getBlock().getStateForPlacement(placement);
                if (state != null && KoperAttachments.rotatesPlacement(held.getItem()))
                    state = KoperPlacementClient.apply(state);
                if (state != null) {
                    if (moving != null) drawMovingPreview(context, mc, moving, state);
                    else drawWorldPreview(context, mc, placement.getClickedPos(), state);
                }
            }

            if (!KoperAttachments.isTool(held.getItem())) return;

            for (var spot : KoperAttachmentScan.world(mc, 10, 7)) {
                KoperAttachmentPoint point = spot.point();
                point(context, spot.pos().getX() + point.position().x,
                    spot.pos().getY() + point.position().y,
                    spot.pos().getZ() + point.position().z,
                    new Vector3f(point.normal().getStepX(), point.normal().getStepY(),
                        point.normal().getStepZ()), point.color());
            }

            long now = System.nanoTime();
            for (var kontra : com.koper.koper_lib.api.core.KenderMovingGrids.snapshot(now)) {
                float[] bodyPos = kontra.position();
                float[] bodyRot = kontra.rotation();
                if (bodyPos == null || bodyRot == null) continue;
                var rotation = new org.joml.Quaternionf(bodyRot[0], bodyRot[1], bodyRot[2], bodyRot[3]);
                int count = Math.min(kontra.states().length, kontra.offsets().length / 3);
                for (int i = 0; i < count; i++) {
                    var points = KoperAttachments.points(kontra.states()[i]);
                    if (points.isEmpty()) continue;
                    for (KoperAttachmentPoint attachment : points) {
                        var local = new org.joml.Vector3f(
                            kontra.offsets()[i * 3] + (float)attachment.position().x - 0.5f,
                            kontra.offsets()[i * 3 + 1] + (float)attachment.position().y - 0.5f,
                            kontra.offsets()[i * 3 + 2] + (float)attachment.position().z - 0.5f);
                        rotation.transform(local);
                        var normal = new Vector3f(
                            attachment.normal().getStepX(), attachment.normal().getStepY(),
                            attachment.normal().getStepZ());
                        rotation.transform(normal);
                        point(context, bodyPos[0] + local.x, bodyPos[1] + local.y,
                            bodyPos[2] + local.z, normal, attachment.color());
                    }
                }
            }

            for (var links : KoperAttachmentOverlayClient.all()) {
                for (var link : links) {
                    segment(context, link.from(), link.to(), link.color(), 0.025);
                    if (link.direction() != 0) arrow(context, link);
                }
            }
        });
    }

    // The projected mc.hitResult is deliberately in world axes so vanilla can draw cracks and an
    // outline. Placement on a moving grid does not use those axes: the server reconstructs a hit at
    // the grid's hidden logical position with the exact local face/contact point. Preview must feed
    // getStateForPlacement the same hit or FACING and micro mount U/V are mirrored after rotation.
    private static BlockHitResult movingPlacementHit(
            com.koper.koper_lib.kender.KenderTargeting.PhysHit hit) {
        BlockPos logical = hit.logicalPos();
        Vec3 point = hit.blockPoint();
        return new BlockHitResult(new Vec3(
            logical.getX() + point.x,
            logical.getY() + point.y,
            logical.getZ() + point.z),
            hit.localFace(), logical, false);
    }

    private static void drawWorldPreview(
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
            Minecraft mc, BlockPos target, BlockState state) {
        boolean valid = mc.level.getBlockState(target).canBeReplaced()
            && state.canSurvive(mc.level, target);
        Vec3 camera = context.levelState().cameraRenderState.pos;
        var pose = context.poseStack();
        pose.pushPose();
        pose.translate(target.getX() - camera.x, target.getY() - camera.y,
            target.getZ() - camera.z);
        submitPreviewModel(context, mc, pose, state, target, -1L, target);
        submitPreviewEdges(context, mc, pose, state, target, valid);
        pose.popPose();
    }

    private static void drawMovingPreview(
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
            Minecraft mc, com.koper.koper_lib.kender.KenderTargeting.PhysHit hit,
            BlockState state) {
        var data = com.koper.koper_lib.kender.KenderClientState.getById(hit.kontraId());
        if (data == null) return;
        long now = System.nanoTime();
        float[] body = com.koper.koper_lib.kender.KenderClientState.renderPos(data, now);
        float[] rotation = com.koper.koper_lib.kender.KenderClientState.renderRot(data, now);
        if (body == null || rotation == null) return;
        BlockPos local = hit.localPos().relative(hit.localFace());
        BlockPos logical = data.toGrid(local);
        boolean valid = !data.localMap.containsKey(local)
            && state.canSurvive(mc.level, logical);
        Vec3 camera = context.levelState().cameraRenderState.pos;
        var pose = context.poseStack();
        pose.pushPose();
        pose.translate(body[0] - camera.x, body[1] - camera.y, body[2] - camera.z);
        pose.rotate(new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]));
        // newOff is the exact body-local centre used by the placement packet. Keeping the raw
        // half-cell offsets is what makes this stay glued to even-sized and rotated physics grids.
        pose.translate(hit.newOffX() - 0.5f, hit.newOffY() - 0.5f, hit.newOffZ() - 0.5f);
        BlockPos lightPos = BlockPos.containing(body[0], body[1], body[2]);
        submitPreviewModel(context, mc, pose, state, lightPos, hit.kontraId(), local);
        submitPreviewEdges(context, mc, pose, state, lightPos, valid);
        pose.popPose();
    }

    private static void submitPreviewModel(
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
            Minecraft mc, com.mojang.blaze3d.vertex.PoseStack pose, BlockState state,
            BlockPos lightPos, long body, BlockPos local) {
        if (com.koper.koper_lib.api.core.KenderGeoBridge.submitFallbackGeo(
                context.submitNodeCollector(), pose, state, body, local)) return;
        if (blockModels == null) blockModels = new BlockModelResolver(mc.getModelManager());
        BlockModelRenderState model = PREVIEW_MODELS.computeIfAbsent(state, key -> {
            BlockModelRenderState made = new BlockModelRenderState();
            blockModels.update(made, key, BlockDisplayContext.create());
            return made;
        });
        int light = LightCoordsUtil.getLightCoords(mc.level, lightPos);
        model.submit(pose, context.submitNodeCollector(), light,
            OverlayTexture.NO_OVERLAY, 0);
    }

    private static void submitPreviewEdges(
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
            Minecraft mc, com.mojang.blaze3d.vertex.PoseStack pose, BlockState state,
            BlockPos target, boolean valid) {
        var shape = com.koper.koper_lib.api.core.KoperBlockShapes.shape(state);
        if (shape == null || shape.isEmpty()) shape = Shapes.block();
        context.submitNodeCollector().submitShapeOutline(
            pose, shape, RenderTypes.linesTranslucent(),
            valid ? PREVIEW_OK : PREVIEW_BLOCKED, valid ? 1.15f : 2.3f, false);
        var facing = previewFacing(state);
        if (facing != null) {
            context.submitNodeCollector().submitShapeOutline(
                pose, facingMarker(facing), RenderTypes.linesTranslucent(),
                0xffffc040, 1.6f, false);
        }
    }

    private static net.minecraft.core.Direction previewFacing(
            net.minecraft.world.level.block.state.BlockState state) {
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING))
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING))
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
        return null;
    }

    private static net.minecraft.world.phys.shapes.VoxelShape facingMarker(
            net.minecraft.core.Direction facing) {
        double x = 0.5 + facing.getStepX() * 0.32;
        double y = 0.5 + facing.getStepY() * 0.32;
        double z = 0.5 + facing.getStepZ() * 0.32;
        return Shapes.or(
            Shapes.box(Math.min(0.46, x - 0.055), Math.min(0.46, y - 0.055),
                Math.min(0.46, z - 0.055), Math.max(0.54, x + 0.055),
                Math.max(0.54, y + 0.055), Math.max(0.54, z + 0.055)),
            Shapes.box(x - 0.11, y - 0.11, z - 0.11,
                x + 0.11, y + 0.11, z + 0.11));
    }

    private static final int DISC_SEGMENTS = 20;
    private static final float DISC_RADIUS = 0.105f;

    private static void point(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
                              double x, double y, double z, Vector3f normal, int color) {
        var pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate(x - camera.x, y - camera.y, z - camera.z);
        pose.rotate(new Quaternionf().rotationTo(new Vector3f(0f, 0f, 1f), normal.normalize()));
        pose.translate(0f, 0f, 0.006f); // a hair off the face or it z-fights with it

        var collector = context.submitNodeCollector().order(1000);
        var seeThrough = com.koper.koper_lib.api.core.KoperOverlayTypes.seeThroughQuads();
        if (seeThrough != null) {
            // dimmed copy that ignores depth — a port buried under a wheel is still findable
            fan(collector, pose, seeThrough, 0f, DISC_RADIUS, dim(color, 0.4f));
            fan(collector, pose, seeThrough, DISC_RADIUS * 0.82f, DISC_RADIUS, dim(color, 0.6f));
        }
        var solid = RenderTypes.debugFilledBox(); // POSITION_COLOR quads, depth-tested
        fan(collector, pose, solid, 0f, DISC_RADIUS, color);
        fan(collector, pose, solid, DISC_RADIUS * 0.82f, DISC_RADIUS, brighten(color));
        pose.popPose();
    }

    // circle (inner 0) or ring, as quads: one slice = inner0, inner1, outer1, outer0
    private static void fan(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector,
                            com.mojang.blaze3d.vertex.PoseStack pose,
                            net.minecraft.client.renderer.rendertype.RenderType type,
                            float inner, float outer, int color) {
        collector.submitCustomGeometry(pose, type, (snap, consumer) -> {
            for (int i = 0; i < DISC_SEGMENTS; i++) {
                double a0 = Math.PI * 2 * i / DISC_SEGMENTS;
                double a1 = Math.PI * 2 * (i + 1) / DISC_SEGMENTS;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                consumer.addVertex(snap, inner * c0, inner * s0, 0f).setColor(color);
                consumer.addVertex(snap, inner * c1, inner * s1, 0f).setColor(color);
                consumer.addVertex(snap, outer * c1, outer * s1, 0f).setColor(color);
                consumer.addVertex(snap, outer * c0, outer * s0, 0f).setColor(color);
            }
        });
    }

    private static int dim(int argb, float factor) {
        int a = (int) (((argb >>> 24) & 0xFF) * factor);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int brighten(int argb) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * 1.3f + 45));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * 1.3f + 45));
        int b = Math.min(255, (int) ((argb & 0xFF) * 1.3f + 45));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static void segment(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
                                Vec3 from, Vec3 to, int color, double width) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0e-4) return;
        Vec3 mid = from.add(to).scale(0.5);
        Vector3f direction = new Vector3f((float)delta.x, (float)delta.y, (float)delta.z).normalize();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(1f, 0f, 0f), direction);
        var pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate(mid.x - camera.x, mid.y - camera.y, mid.z - camera.z);
        pose.rotate(rotation);
        var seeThrough = com.koper.koper_lib.api.core.KoperOverlayTypes.seeThroughQuads();
        if (seeThrough != null) {
            // a wire that vanishes the moment it passes behind a fender is a wire you cannot follow
            ribbon(context, pose, seeThrough, (float)(length * 0.5), (float) width, dim(color, 0.4f));
        }
        context.submitNodeCollector().order(900).submitShapeOutline(
            pose, Shapes.box(-length * 0.5, -width, -width, length * 0.5, width, width),
            RenderTypes.linesTranslucent(), color, 3.0f, false);
        pose.popPose();
    }

    // two crossed quads along local +X — a cable you can see from any angle without billboarding
    private static void ribbon(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
                               com.mojang.blaze3d.vertex.PoseStack pose,
                               net.minecraft.client.renderer.rendertype.RenderType type,
                               float half, float width, int color) {
        context.submitNodeCollector().order(899).submitCustomGeometry(pose, type, (snap, consumer) -> {
            consumer.addVertex(snap, -half, -width, 0f).setColor(color);
            consumer.addVertex(snap, half, -width, 0f).setColor(color);
            consumer.addVertex(snap, half, width, 0f).setColor(color);
            consumer.addVertex(snap, -half, width, 0f).setColor(color);
            consumer.addVertex(snap, -half, 0f, -width).setColor(color);
            consumer.addVertex(snap, half, 0f, -width).setColor(color);
            consumer.addVertex(snap, half, 0f, width).setColor(color);
            consumer.addVertex(snap, -half, 0f, width).setColor(color);
        });
    }

    private static void arrow(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
                              KoperAttachmentOverlayClient.Link link) {
        Vec3 delta = link.to().subtract(link.from());
        double length = delta.length();
        if (length < 0.3) return;
        Vec3 forward = delta.scale(link.direction() / length);
        Vec3 tip = link.from().add(delta.scale(link.direction() > 0 ? 0.62 : 0.38));
        Vec3 side = Math.abs(forward.y) < 0.85
            ? forward.cross(new Vec3(0, 1, 0)).normalize()
            : forward.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 back = tip.subtract(forward.scale(0.18));
        segment(context, tip, back.add(side.scale(0.11)), link.color(), 0.035);
        segment(context, tip, back.subtract(side.scale(0.11)), link.color(), 0.035);
    }
}
