package com.koper.koper_lib.kodel;

import com.koper.koper_lib.coremod.KoperCore;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.Map;

// the player's own skin on another model. the body drives the bones the same way it drives
// worn armour, and an optional clip runs underneath
public class KodelPlayerModelLayer<S extends HumanoidRenderState, M extends HumanoidModel<S>>
        extends RenderLayer<S, M> {

    private static volatile String warnedMissing;

    public KodelPlayerModelLayer(RenderLayerParent<S, M> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack ps, SubmitNodeCollector tasks, int light, S state, float yRot, float xRot) {
        String name = KodelPlayerModel.model();
        if (name == null || !(state instanceof AvatarRenderState avatar) || avatar.skin == null) return;
        KodelBook.Entry entry = KodelBook.get(name);
        if (entry == null) {
            if (!name.equals(warnedMissing)) {
                warnedMissing = name;
                KoperCore.LOGGER.error("[kodel] player model '{}' is not in any enabled pack, players stay vanilla-less", name);
            }
            return;
        }
        Identifier skin = avatar.skin.body().texturePath();
        KodelModel worn = entry.model();
        HumanoidModel<?> body = getParentModel();

        KodelAnimation anim = entry.clip(KodelPlayerModel.clip());
        KodelSampler.ResolvedTracks tracks = anim == null ? null : KodelSampler.ResolvedTracks.of(worn, anim);
        float t = anim == null ? 0f : KodelBook.fold(anim, state.ageInTicks / 20f);
        float[] pose = new float[worn.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePoseFollowing(worn, tracks, t,
            bone -> KodelZbrojaLayer.delta(bone, body, Map.of(), null), pose);

        float[] off = KodelPlayerModel.offset();
        float s = KodelPlayerModel.scale() <= 0 ? 1f : KodelPlayerModel.scale();
        ps.pushPose();
        ps.translate(off[0], off[1], off[2]);
        ps.scale(-s, -s, s);
        tasks.submitCustomGeometry(ps, RenderTypes.entityCutout(skin), (snap, consumer) ->
            KodelModelRender.render(worn, pose, snap.pose(), consumer, light,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0xFFFFFFFF));
        ps.popPose();
    }
}
