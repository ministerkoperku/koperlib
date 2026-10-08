package com.koper.koper_lib.kender;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

// woda na kontrakcji. LiquidBlock is RenderShape.INVISIBLE — vanilla draws fluids from the chunk
// section compiler, never from a block model, so the block loop skipped every water cell and a
// bucket poured on deck was simply nothing on screen.
//
// FluidRenderer does all the hard parts already (corner heights, flow angle, the overlay sprite on
// walls). All it wants is a BlockAndTintGetter that answers in grid space, and KontraMovingBlockState
// has been one this whole time.
public final class KontraWodaKender {

    private KontraWodaKender() {}

    // rebuilt only when the atlas reloads — FluidRenderer just wraps the model set
    private static FluidStateModelSet models;
    private static FluidRenderer renderer;

    // the geometry lambda runs later, in the pass, so this can't be one plain static — threadlocal
    // costs nothing here and means a parallel pass can never braid two fluids into each other
    private static final ThreadLocal<Posed> POSED = ThreadLocal.withInitial(Posed::new);

    public static boolean submit(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                                 KontraMovingBlockState state, BlockState block) {
        FluidState fluid = block.getFluidState();
        if (fluid.isEmpty()) return false;

        FluidStateModelSet set = Minecraft.getInstance().getModelManager().getFluidStateModelSet();
        if (set != models) { models = set; renderer = new FluidRenderer(set); }
        FluidRenderer fr = renderer;

        // MUST be the state's own pos — the getter turns (asked - blockPos) into a grid offset, so
        // handing tesselate any other pos makes every neighbour lookup land in the wrong cell
        BlockPos pos = state.blockPos;

        poseStack.pushPose();
        // tesselate() emits at (pos & 15). not a guess and not something you need the game to tell
        // you — it's the three iand's at the top of the method, left over from living inside the
        // section compiler. our pose already sits on the block, so cancel the section offset out.
        poseStack.translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
        RenderType type = typeFor(set.get(fluid).layer());
        collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> {
            Posed posed = POSED.get();
            posed.aim(pose, consumer);
            try { fr.tesselate(state, pos, posed.output(), block, fluid); }
            catch (Throwable t) { moan(block, t); }
            finally { posed.aim(null, null); }
        });
        poseStack.popPose();
        return true;
    }

    private static RenderType typeFor(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> RenderTypes.solidMovingBlock();
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
        };
    }

    private static final java.util.Set<String> MOANED = new java.util.HashSet<>();
    private static void moan(BlockState block, Throwable t) {
        if (MOANED.add(block.getBlock().toString()))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kender/woda] fluid tesselate died on {}", block.getBlock(), t);
    }

    // FluidRenderer writes raw section coords and never touches a pose — it was never meant to run
    // on something that rotates. submitCustomGeometry hands the pose over separately, so somebody
    // has to do the multiply, and every method returns `this` because the packed addVertex chains
    // setColor/setUv/setLight/setNormal onto whatever the last call handed back. return the delegate
    // once and the normals slip through untransformed.
    private static final class Posed implements VertexConsumer {
        private PoseStack.Pose pose;
        private VertexConsumer out;
        private final FluidRenderer.Output output = layer -> this;

        void aim(PoseStack.Pose pose, VertexConsumer out) { this.pose = pose; this.out = out; }
        FluidRenderer.Output output() { return output; }

        @Override public VertexConsumer addVertex(float x, float y, float z) { out.addVertex(pose, x, y, z); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { out.setNormal(pose, x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { out.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setColor(int argb) { out.setColor(argb); return this; }
        @Override public VertexConsumer setUv(float u, float v) { out.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { out.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { out.setUv2(u, v); return this; }
        @Override public VertexConsumer setUv3(float u, float v) { out.setUv3(u, v); return this; }
        @Override public VertexConsumer setLineWidth(float width) { out.setLineWidth(width); return this; }
    }
}
