package com.koper.koper_lib.kodel;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// draws a .kodel on a block entity. register it like any other:
//   BlockEntityRenderers.register(MY_TYPE, ctx -> new KodelKlocRender<>());
// and have the block entity implement KodelKlocowy.
//
// everything that costs anything (parse, track resolve, rest pose) is cached in
// KodelBook, so this stays a lookup plus a sample per frame
public class KodelKlocRender<T extends BlockEntity & KodelKlocowy>
        implements BlockEntityRenderer<T, KodelKlocRender.Stan> {

    // the vanilla state has pos, light and break progress but no blockstate, and we
    // need the blockstate for the facing turn
    public static class Stan extends BlockEntityRenderState {
        public String model;
        public String clip;
        public Identifier texture;
        public BlockState state;
        public float seconds;
        public float scale = 1f;
        public float[] offset;
        public int tint = 0xFFFFFFFF;
        public boolean hidden;
    }

    private final int order;

    public KodelKlocRender() {
        this(0);
    }

    /// higher order draws later, same knob every other submit path uses
    public KodelKlocRender(int order) {
        this.order = order;
    }

    @Override
    public Stan createRenderState() {
        return new Stan();
    }

    @Override
    public void extractRenderState(T block, Stan stan, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay damage) {
        BlockEntityRenderer.super.extractRenderState(block, stan, partialTick, cameraPos, damage);
        stan.hidden = block.kodelHidden();
        if (stan.hidden) return;
        stan.model = block.kodelModel();
        stan.clip = block.kodelClip();
        stan.texture = block.kodelTexture();
        stan.state = block.getBlockState();
        stan.seconds = block.kodelSeconds(partialTick);
        stan.scale = block.kodelScale();
        stan.offset = block.kodelOffset();
        stan.tint = block.kodelTint();
    }

    @Override
    public void submit(Stan stan, PoseStack pose, SubmitNodeCollector tasks, CameraRenderState camera) {
        if (stan.hidden || stan.model == null || stan.texture == null) return;
        KodelBook.Entry entry = KodelBook.get(stan.model);
        if (entry == null) return;
        float[] world = KodelBook.pose(stan.model, stan.clip, stan.seconds, null);
        if (world == null) return;

        RenderType type = renderType(stan.texture);
        pose.pushPose();
        KodelKloc.place(pose, stan.state, stan.scale, stan.offset);
        KodelKloc.submit(tasks.order(this.order), pose, type, entry.model(), world,
            stan.lightCoords, OverlayTexture.NO_OVERLAY, stan.tint);
        pose.popPose();
    }

    /// override for translucent or glowing models
    protected RenderType renderType(Identifier texture) {
        return RenderTypes.entityCutout(texture);
    }

    // a kodel model is normally much bigger than the one block it sits on, so the
    // hitbox bounds decide how far away it stays visible
    @Override
    public int getViewDistance() {
        return 128;
    }
}
