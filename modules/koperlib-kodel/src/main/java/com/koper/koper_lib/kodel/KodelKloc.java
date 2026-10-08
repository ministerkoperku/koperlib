package com.koper.koper_lib.kodel;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// kodel models on blocks. the model is authored in pixels around its own origin,
// blocks want it standing on the middle of the block and turned to face whatever
// the blockstate says.
//
// kept static so a block entity renderer, an item icon or a kontraption can all
// go through the same transform instead of each inventing its own
public final class KodelKloc {
    public static final int FULLBRIGHT = 0x00F000F0;

    private KodelKloc() {}

    /// centre-bottom of the block, then the facing turn. call inside a pushPose
    public static void place(PoseStack pose, BlockState state, float scale, float[] offsetPixels) {
        pose.translate(0.5, 0, 0.5);
        Quaternionf facing = facingQuat(state);
        if (facing != null) {
            // turn around the middle of the block, not around its floor, or a wall
            // mounted model swings out of its own hitbox
            pose.translate(0, 0.5f, 0);
            pose.rotate(facing);
            pose.translate(0, -0.5f, 0);
        }
        if (offsetPixels != null && offsetPixels.length >= 3) {
            pose.translate(offsetPixels[0] / 16f, offsetPixels[1] / 16f, offsetPixels[2] / 16f);
        }
        if (scale != 1f && scale > 0f) pose.scale(scale, scale, scale);
    }

    /// same transform without a PoseStack, for hitboxes and anything off the render thread
    public static Matrix4f placement(BlockState state, float scale, float[] offsetPixels) {
        Matrix4f m = new Matrix4f().translation(0.5f, 0f, 0.5f);
        Quaternionf facing = facingQuat(state);
        if (facing != null) m.translate(0, 0.5f, 0).rotate(facing).translate(0, -0.5f, 0);
        if (offsetPixels != null && offsetPixels.length >= 3) {
            m.translate(offsetPixels[0] / 16f, offsetPixels[1] / 16f, offsetPixels[2] / 16f);
        }
        if (scale != 1f && scale > 0f) m.scale(scale);
        return m;
    }

    /// null when the state has no facing at all, which means leave it as authored
    public static Quaternionf facingQuat(BlockState state) {
        if (state == null) return null;
        Direction dir = horizontal(state);
        if (dir != null) {
            // models are authored looking north, same as vanilla block models
            return new Quaternionf().rotateY((float) Math.toRadians(180f - dir.toYRot()));
        }
        Direction any = anyFacing(state);
        if (any == null) return null;
        return switch (any) {
            case UP -> new Quaternionf();
            case DOWN -> new Quaternionf().rotateX((float) Math.PI);
            default -> new Quaternionf()
                .rotateY((float) Math.toRadians(180f - any.toYRot()))
                .rotateX((float) Math.toRadians(90f));
        };
    }

    private static Direction horizontal(BlockState state) {
        EnumProperty<Direction> prop = BlockStateProperties.HORIZONTAL_FACING;
        return state.hasProperty(prop) ? state.getValue(prop) : null;
    }

    private static Direction anyFacing(BlockState state) {
        EnumProperty<Direction> prop = BlockStateProperties.FACING;
        return state.hasProperty(prop) ? state.getValue(prop) : null;
    }

    /// draws a kodel model at the pose already on the stack. boneWorld comes from
    /// KodelBook.pose, so a static block just hands over the cached rest pose
    public static void submit(OrderedSubmitNodeCollector collector, PoseStack pose, RenderType type,
                              KodelModel model, float[] boneWorld, int light, int overlay, int color) {
        if (collector == null || model == null || boneWorld == null) return;
        collector.submitCustomGeometry(pose, type, (snap, consumer) -> {
            try {
                KodelModelRender.render(model, boneWorld, new Matrix4f(snap.pose()),
                    consumer, light, overlay, color);
            } catch (Throwable broken) {
                // one bad model must not take the whole chunk's render pass with it
            }
        });
    }

    /// the whole thing: look the model up, pose it, place it on the block, draw it
    public static boolean submitModel(OrderedSubmitNodeCollector collector, PoseStack pose, RenderType type,
                                      String modelName, String clip, float seconds,
                                      BlockState state, float scale, int light, int color) {
        KodelBook.Entry entry = KodelBook.get(modelName);
        if (entry == null) return false;
        float[] world = KodelBook.pose(modelName, clip, seconds, null);
        if (world == null) return false;
        pose.pushPose();
        place(pose, state, scale, null);
        submit(collector, pose, type, entry.model(), world, light, OverlayTexture.NO_OVERLAY, color);
        pose.popPose();
        return true;
    }

    /// draws whatever the pack bound to this blockstate. false when nothing is bound,
    /// so the caller keeps doing what it did before
    public static boolean submitBound(OrderedSubmitNodeCollector collector, PoseStack pose,
                                      BlockState state, int light) {
        KodelKlocBook.Wiazanie bind = KodelKlocBook.of(state);
        if (bind == null || collector == null) return false;
        KodelBook.Entry entry = KodelBook.get(bind.model());
        if (entry == null) return false;

        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        // before the turn, the offset is already in block axes (kgecko learned that one the hard way)
        if (state.getBlock() instanceof com.koper.koper_lib.api.core.KoperStateOffset koperShove) {
            var shove = koperShove.koperStateOffset(state);
            pose.translate(shove.x, shove.y, shove.z);
        }
        Quaternionf facing = KodelKlocBook.facingQuat(bind, state);
        if (facing != null) {
            // turn around the middle of the block, not its floor, or a wall mounted
            // model swings out of its own hitbox
            pose.translate(0, 0.5f, 0);
            pose.rotate(facing);
            pose.translate(0, -0.5f, 0);
        }
        float[] off = bind.offset();
        if (off[0] != 0 || off[1] != 0 || off[2] != 0) {
            pose.translate(off[0] / 16f, off[1] / 16f, off[2] / 16f);
        }
        if (bind.scale() != 1f && bind.scale() > 0f) pose.scale(bind.scale(), bind.scale(), bind.scale());

        RenderType type = renderType(bind);
        KodelModel model = entry.model();
        float[] world = entry.restPose();
        java.util.Set<String> bones = bind.bones();
        collector.submitCustomGeometry(pose, type, (snap, consumer) -> {
            try {
                float[] mesh = KodelModelRender.bake(model, world, new Matrix4f(snap.pose()), bones);
                for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
                    consumer.addVertex(mesh[i], mesh[i + 1], mesh[i + 2], bind.tint(),
                        mesh[i + 3], mesh[i + 4], OverlayTexture.NO_OVERLAY, light,
                        mesh[i + 5], mesh[i + 6], mesh[i + 7]);
                }
            } catch (Throwable broken) {
                // one bad block must not take the chunk's render pass with it
            }
        });
        pose.popPose();
        return true;
    }

    // kender already placed the stack like its geo mesh (offset, facing, base transform) and
    // posed the bones. we just swap the mesh. doing our own placement here put the bearing
    // half a block off and ignored R + suspension anims, only on jars with kodel in them lol
    public static boolean submitPosed(OrderedSubmitNodeCollector collector, PoseStack pose, BlockState state,
                                      int light, int tint, Function<String, float[]> bonePose,
                                      java.util.Set<String> visible) {
        KodelKlocBook.Wiazanie bind = KodelKlocBook.of(state);
        if (bind == null || collector == null) return false;
        KodelBook.Entry entry = KodelBook.get(bind.model());
        if (entry == null) return false;

        KodelModel model = entry.model();
        // baked now, not inside the lambda: kender hands over a scratch map it wipes for the next block
        float[] world = bonePose == null ? entry.restPose() : koperPosedBones(model, entry.restPose(), bonePose);
        java.util.Set<String> bones = visible != null ? visible : bind.bones();
        RenderType type = renderType(bind);
        collector.submitCustomGeometry(pose, type, (snap, consumer) -> {
            try {
                float[] mesh = KodelModelRender.bake(model, world, new Matrix4f(snap.pose()), bones);
                for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
                    consumer.addVertex(mesh[i], mesh[i + 1], mesh[i + 2], tint,
                        mesh[i + 3], mesh[i + 4], OverlayTexture.NO_OVERLAY, light,
                        mesh[i + 5], mesh[i + 6], mesh[i + 7]);
                }
            } catch (Throwable broken) {
                // one bad block must not take the chunk's render pass with it
            }
        });
        return true;
    }

    // rest pose + kender's bone pose, stacked like kgecko does it: move (blocks -> px) before the
    // pivot, euler ADDED z*y*x, scale inside the pivot. both mirror x so the numbers just carry over
    static float[] koperPosedBones(KodelModel model, float[] rest, Function<String, float[]> bonePose) {
        int n = model.bones.size();
        float[] world = new float[n * KodelSampler.MAT4_FLOATS];
        boolean touched = false;
        Matrix4f local = new Matrix4f();
        Matrix4f parent = new Matrix4f();
        Quaternionf q = new Quaternionf();
        org.joml.Vector3f euler = new org.joml.Vector3f();
        for (int i = 0; i < n; i++) {
            KodelModel.KodelBone b = model.bones.get(i);
            float[] ov = bonePose.apply(b.name);
            int at = i * KodelSampler.MAT4_FLOATS;
            if (ov == null && (b.parent < 0 || !touched)) {
                // nothing moved this bone or anything above it, the cached rest matrix is still right
                System.arraycopy(rest, at, world, at, KodelSampler.MAT4_FLOATS);
                continue;
            }
            touched = true;
            q.set(b.rotation[0], b.rotation[1], b.rotation[2], b.rotation[3]);
            float px = b.position[0], py = b.position[1], pz = b.position[2];
            float sx = b.scale[0], sy = b.scale[1], sz = b.scale[2];
            if (ov != null) {
                px += ov[3] * 16f;
                py += ov[4] * 16f;
                pz += ov[5] * 16f;
                if (ov[0] != 0 || ov[1] != 0 || ov[2] != 0) {
                    q.getEulerAnglesZYX(euler);
                    q.rotationZYX(euler.z + ov[2], euler.y + ov[1], euler.x + ov[0]);
                }
                if (ov.length >= 9) {
                    if (ov[6] != 0) sx *= ov[6];
                    if (ov[7] != 0) sy *= ov[7];
                    if (ov[8] != 0) sz *= ov[8];
                }
            }
            local.translation(px, py, pz)
                .translate(b.pivot[0], b.pivot[1], b.pivot[2])
                .rotate(q)
                .scale(sx, sy, sz)
                .translate(-b.pivot[0], -b.pivot[1], -b.pivot[2]);
            if (b.parent >= 0) {
                parent.set(world, b.parent * KodelSampler.MAT4_FLOATS).mul(local).get(world, at);
            } else {
                local.get(world, at);
            }
        }
        return world;
    }

    private static RenderType renderType(KodelKlocBook.Wiazanie bind) {
        return "translucent".equals(bind.renderType())
            ? RenderTypes.entityTranslucent(bind.texture())
            : RenderTypes.entityCutout(bind.texture());
    }

    /// hitbox bones of a block-mounted model, moved into world space around
    /// {@code at}. pixels become blocks here, so these are directly comparable with
    /// a player's look vector
    public static List<KodelHitboxer.Obb> worldHitboxes(String modelName, String clip, float seconds,
                                                        BlockState state, float scale, BlockPos at) {
        KodelBook.Entry entry = KodelBook.get(modelName);
        if (entry == null || at == null) return List.of();
        float[] world = KodelBook.pose(modelName, clip, seconds, null);
        List<KodelHitboxer.Obb> local = KodelBook.hitboxes(modelName, world);
        if (local.isEmpty()) return List.of();

        Matrix4f place = placement(state, scale, null);
        List<KodelHitboxer.Obb> out = new ArrayList<>(local.size());
        for (KodelHitboxer.Obb box : local) {
            out.add(toWorld(box, place, at));
        }
        return out;
    }

    private static KodelHitboxer.Obb toWorld(KodelHitboxer.Obb box, Matrix4f place, BlockPos at) {
        org.joml.Vector3f c = place.transformPosition(new org.joml.Vector3f(
            box.cx() / 16f, box.cy() / 16f, box.cz() / 16f));
        org.joml.Vector3f ax = place.transformDirection(new org.joml.Vector3f(
            box.axx() / 16f, box.axy() / 16f, box.axz() / 16f));
        org.joml.Vector3f ay = place.transformDirection(new org.joml.Vector3f(
            box.ayx() / 16f, box.ayy() / 16f, box.ayz() / 16f));
        org.joml.Vector3f az = place.transformDirection(new org.joml.Vector3f(
            box.azx() / 16f, box.azy() / 16f, box.azz() / 16f));
        return new KodelHitboxer.Obb(box.bone(),
            c.x + at.getX(), c.y + at.getY(), c.z + at.getZ(),
            ax.x, ax.y, ax.z,
            ay.x, ay.y, ay.z,
            az.x, az.y, az.z);
    }
}
