package com.koper.koper_lib.kodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Set;

// wears a .kodel on a body. the pack says which item wears which model and which of the
// model's bones follows which limb; every bone then moves by the same amount its limb
// moved away from rest, so at rest the armour sits exactly where it was authored and
// nothing doubles up.
//
// the flip, offsets and limb deltas are the ones the old geo armour layer used in game; armour
// authored against it sits the same here
public class KodelZbrojaLayer<S extends HumanoidRenderState, M extends HumanoidModel<S>>
        extends RenderLayer<S, M> {

    private static final Set<KodelModel> BROKEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public KodelZbrojaLayer(RenderLayerParent<S, M> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack ps, SubmitNodeCollector tasks, int light, S state, float yRot, float xRot) {
        HumanoidModel<?> model = getParentModel();
        slot(ps, tasks, light, state.headEquipment, model);
        slot(ps, tasks, light, state.chestEquipment, model);
        slot(ps, tasks, light, state.legsEquipment, model);
        slot(ps, tasks, light, state.feetEquipment, model);
    }

    private void slot(PoseStack ps, SubmitNodeCollector tasks, int light, ItemStack stack,
                      HumanoidModel<?> body) {
        if (stack == null || stack.isEmpty()) return;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        KodelZbrojaBook.Zbroja bind = KodelZbrojaBook.of(itemId);
        if (bind == null) return;
        KodelBook.Entry entry = KodelBook.get(bind.model());
        if (entry == null) return;

        KodelModel worn = entry.model();
        float[] pose = new float[worn.bones.size() * KodelSampler.MAT4_FLOATS];
        Map<String, String> boneMap = bind.bones();
        Map<String, float[]> placement = bind.placement();
        // an armour piece may run its own clip (a pulsing chestplate); the body still moves it
        KodelAnimation anim = entry.clip(bind.animation());
        KodelSampler.ResolvedTracks tracks = anim == null ? null : KodelSampler.ResolvedTracks.of(worn, anim);
        float t = anim == null ? 0f : KodelBook.fold(anim, (float) (System.nanoTime() / 1.0e9));
        KodelSampler.samplePoseFollowing(worn, tracks, t,
            bone -> delta(bone, body, boneMap, placement), pose);

        float s = bind.scale() <= 0 ? 1f : bind.scale();
        float[] off = bind.offset();
        float[] rot = bind.rotate();

        ps.pushPose();
        if (off[0] != 0 || off[1] != 0 || off[2] != 0) ps.translate(off[0], off[1], off[2]);
        // the same base flip the geo layer applies, scaled by armor_scale
        ps.scale(-s, -s, s);
        if (rot[0] != 0) ps.rotate(Axis.XP.rotationDegrees(rot[0]));
        if (rot[1] != 0) ps.rotate(Axis.YP.rotationDegrees(rot[1]));
        if (rot[2] != 0) ps.rotate(Axis.ZP.rotationDegrees(rot[2]));

        int tint = bind.tint();
        if (bind.dyeable()) {
            tint = 0xFF000000 | (net.minecraft.world.item.component.DyedItemColor
                .getOrDefault(stack, tint & 0xFFFFFF) & 0xFFFFFF);
        }
        final int color = tint;
        final Set<String> visible = bind.renderBones();

        RenderType type = RenderTypes.entityCutout(bind.texture());
        tasks.submitCustomGeometry(ps, type, (snap, consumer) ->
            draw(worn, pose, snap.pose(), consumer, light, color, visible));

        if (stack.hasFoil()) {
            tasks.submitCustomGeometry(ps, RenderTypes.trimmedArmorGlint(), (snap, consumer) ->
                draw(worn, pose, snap.pose(), consumer, light, 0xFFFFFFFF, visible));
        }
        ps.popPose();
    }

    private static void draw(KodelModel model, float[] pose, Matrix4f base,
                             com.mojang.blaze3d.vertex.VertexConsumer consumer,
                             int light, int color, Set<String> visible) {
        try {
            float[] mesh = KodelModelRender.bake(model, pose, new Matrix4f(base), visible);
            for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
                consumer.addVertex(mesh[i], mesh[i + 1], mesh[i + 2], color,
                    mesh[i + 3], mesh[i + 4], OverlayTexture.NO_OVERLAY, light,
                    mesh[i + 5], mesh[i + 6], mesh[i + 7]);
            }
        } catch (RuntimeException broken) {
            // a malformed armour model must not take the wearer's whole render with it, but it has to say so
            if (BROKEN.add(model)) com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                "[kodel] armour model does not draw: {}", broken.toString());
        }
    }

    /// how far this bone's limb has moved from rest. rotation in radians, position in
    /// pixels, in the sign convention the flip above expects
    static float[] delta(String bone, HumanoidModel<?> body,
                         Map<String, String> map, Map<String, float[]> placement) {
        float[] out = new float[6];
        ModelPart part = partFor(bone, body, map);
        if (part != null) {
            var rest = part.getInitialPose();
            out[0] = -(part.xRot - rest.xRot());
            out[1] = -(part.yRot - rest.yRot());
            out[2] = part.zRot - rest.zRot();
            out[3] = -(part.x - rest.x());
            out[4] = -(part.y - rest.y());
            out[5] = part.z - rest.z();
        }
        if (placement != null) {
            float[] fine = placement.get(bone);
            if (fine != null && fine.length >= 3) {
                out[3] += fine[0];
                out[4] += fine[1];
                out[5] += fine[2];
            }
        }
        return out;
    }

    /// armor_bones wins; otherwise guess from the bone's own name. "none" pins it
    static ModelPart partFor(String bone, HumanoidModel<?> body, Map<String, String> map) {
        String key = map != null && map.containsKey(bone) ? map.get(bone) : bone;
        String b = key.toLowerCase(java.util.Locale.ROOT);
        if (b.equals("none") || b.equals("root") || b.isBlank()) return null;
        if (b.contains("head") || b.contains("helmet")) return body.head;
        if (b.contains("body") || b.contains("chest") || b.contains("torso")) return body.body;
        if (b.contains("rightarm") || b.contains("right_arm") || b.contains("arm_right")) return body.rightArm;
        if (b.contains("leftarm") || b.contains("left_arm") || b.contains("arm_left")) return body.leftArm;
        if (b.contains("rightleg") || b.contains("right_leg") || b.contains("leg_right")) return body.rightLeg;
        if (b.contains("leftleg") || b.contains("left_leg") || b.contains("leg_left")) return body.leftLeg;
        return null;
    }
}
