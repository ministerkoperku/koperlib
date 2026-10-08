package com.koper.koper_lib.kender;

import com.koper.koper_lib.api.render.KenderRenderAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

// Vanilla block models on moving grids. Unsupported/special models simply stay on submitMovingBlock.
public final class VanillaBlockKender {
    // state -> baked-per-facemask. mask is 6 bits so a flat 64 slot array beats hashing a record,
    // and blockstates are interned by MC so reference equality is the right compare here.
    private static final it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<BlockState, Baked[]> CACHE =
        new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Matrix4f MODEL_TMP = new Matrix4f();
    private static final Set<Part> LIVE = new HashSet<>();
    private static final Set<Part> TOUCHED = new HashSet<>();
    private static boolean frameOpen;

    private VanillaBlockKender() {}

    public static void begin() {
        frameOpen = true;
        TOUCHED.clear();
    }

    public static void end() {
        for (Part part : TOUCHED) {
            part.writer.end();
            part.writer = null;
        }
        for (Part part : LIVE) if (!TOUCHED.contains(part)) part.mesh.clearInstances();
        LIVE.clear();
        LIVE.addAll(TOUCHED);
        frameOpen = false;
    }

    // no alloc, no hashing — this runs once per block per frame until section baking lands
    // context-aware bakes (create's connected textures and friends) can NOT share one slot per
    // blockstate — the geometry depends on who the neighbours are. one tank baked first was handing
    // its walls to every other tank in the world. keyed per kontra cell, nuked whenever blocks change.
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Baked> CTX_CACHE =
        new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<BlockState> CTX_MODELS =
        new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
    private static boolean lastBakeUsedContext;

    // keyed by WHAT the model was baked against — state, visible faces, and the six neighbours.
    // two tanks with the same surroundings bake identical geometry and share one mesh, and a changed
    // neighbourhood simply produces a different key. no eviction, so meshes stop being destroyed and
    // rebuilt every time create nudges a blockstate. that churn was the lag AND the flicker: a mesh
    // that isn't uploaded yet makes submit() bail, the caller draws the block on the CPU path instead,
    // and the block flips between two differently lit copies frame to frame.
    private static long ctxKey(byte mask, BlockState block, long neighbourSig) {
        long h = (long)net.minecraft.world.level.block.Block.getId(block) * 0x9E3779B97F4A7C15L;
        h ^= (long)(mask & 63) << 56;
        h ^= neighbourSig * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        return h;
    }

    private static Baked cached(BlockState block, byte mask, net.minecraft.core.BlockPos pos) {
        return cached(block, mask, pos, null);
    }

    private static Baked cached(BlockState block, byte mask, net.minecraft.core.BlockPos pos,
                                com.koper.koper_lib.api.core.KenderMovingBlockContext ctx) {
        int m = mask & 63;
        if (ctx != null && CTX_MODELS.contains(block)) {
            long key = ctxKey(mask, block, ctx.kenderNeighbourSignature());
            Baked baked = CTX_CACHE.get(key);
            if (baked == null) {
                if (CTX_CACHE.size() > 4096) forgetContext();  // pathological pack, start over
                CTX_CACHE.put(key, baked = bake(block, mask, pos, ctx));
            }
            return baked;
        }
        Baked[] byMask = CACHE.get(block);
        if (byMask == null) CACHE.put(block, byMask = new Baked[64]);
        Baked baked = byMask[m];
        if (baked != null) return baked;
        baked = bake(block, mask, pos, ctx);
        if (ctx != null && lastBakeUsedContext) {
            CTX_MODELS.add(block);
            CTX_CACHE.put(ctxKey(mask, block, ctx.kenderNeighbourSignature()), baked);
            return baked;
        }
        byMask[m] = baked;
        return baked;
    }

    public static void forgetContext() {
        for (Baked baked : CTX_CACHE.values())
            for (Part part : baked.parts) {
                LIVE.remove(part); TOUCHED.remove(part);
                part.mesh.clearInstances(); part.mesh.close();
            }
        CTX_CACHE.clear();
    }

    // this used to walk CACHE only and leave CTX_CACHE standing. that is where every create block
    // lives, so leaving a world left their meshes open with last frame's instances still on them —
    // and LIVE had just been emptied, so end() could never clear those instances again either.
    // that is the see-through contraption from the PREVIOUS world hanging in the sky of the new one.
    public static void clear() {
        forgetContext();
        CTX_MODELS.clear();
        for (Baked[] byMask : CACHE.values())
            for (Baked baked : byMask)
                if (baked != null) for (Part part : baked.parts) { part.mesh.clearInstances(); part.mesh.close(); }
        CACHE.clear();
        LIVE.clear();
        TOUCHED.clear();
        frameOpen = false;
    }

    // why does this block keep missing the gpu path — one line per reason per block, not per frame
    private static final java.util.HashSet<String> WHY_LOGGED = new java.util.HashSet<>();
    private static void why(BlockState block, String reason) {
        if (!com.koper.koper_lib.config.KoperLibConfig.get().debugMode) return;
        if (!WHY_LOGGED.add(block.getBlock() + "|" + reason)) return;
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg-C] kender ODRZUCIL {} -> {}", block.getBlock(), reason);
    }

    public static boolean submit(com.koper.koper_lib.api.core.KenderMovingBlockContext state, BlockState block, byte faceMask,
                                 float px, float py, float pz, Quaternionf rotation,
                                 float ox, float oy, float oz, int packedLight) {
        if (!frameOpen || !com.koper.koper_lib.kender.KenderFrame.vulkanActive()) return false;
        if (block.getRenderShape() != RenderShape.MODEL) { why(block, "renderShape=" + block.getRenderShape()); return false; }
        // bake needs the block's world position: create's wrapper models decide their geometry
        // from context, and without it a cogwheel hands back the full model it hides in the world
        Baked baked = cached(block, faceMask, state.kenderWorldPos(), state);
        if (baked.parts.isEmpty()) { why(block, "bake dal 0 czesci, uzyl ctx=" + lastBakeUsedContext); return false; }

        var modelOffset = block.getOffset(state.kenderWorldPos());
        Matrix4f model = MODEL_TMP.translation(px, py, pz).rotate(rotation)
            .translate(ox - 0.5f + (float)modelOffset.x,
                       oy - 0.5f + (float)modelOffset.y,
                       oz - 0.5f + (float)modelOffset.z);
        int blockLight = (packedLight >> 4) & 15;
        int skyLight = (packedLight >> 20) & 15;
        // we used to write the instance and THEN report not-ready, so the caller drew the block a
        // second time on the CPU path — two coplanar copies fighting = the shimmer. ask first.
        for (Part part : baked.parts) if (!part.mesh.gpuReady()) return false;
        for (Part part : baked.parts) {
            if (TOUCHED.add(part)) part.writer = part.mesh.begin(32);
            int tint = 0xFFFFFFFF;
            if (part.tintIndex >= 0) {
                try {
                    var tintSource = Minecraft.getInstance().getBlockColors().getTintSource(block, part.tintIndex);
                    if (tintSource != null) tint = 0xFF000000 | tintSource.colorInWorld(block, state, state.kenderWorldPos());
                } catch (Throwable ignored) {}
            }
            part.writer.instance(model, blockLight, skyLight, tint);
        }
        return true;
    }

    public static boolean submitScaled(BlockState block, byte faceMask,
                                       float px, float py, float pz, Quaternionf rotation,
                                       float ox, float oy, float oz,
                                       float pieceX, float pieceY, float pieceZ, float scale,
                                       net.minecraft.core.BlockPos worldPos, int packedLight) {
        if (!frameOpen || !com.koper.koper_lib.kender.KenderFrame.vulkanActive() || block.getRenderShape() != RenderShape.MODEL)
            return false;
        Baked baked = cached(block, faceMask, worldPos);
        if (baked.parts.isEmpty()) return false;

        Matrix4f model = MODEL_TMP.translation(px, py, pz).rotate(rotation)
            .translate(ox - 0.5f + pieceX, oy - 0.5f + pieceY, oz - 0.5f + pieceZ)
            .scale(scale);
        int blockLight = (packedLight >> 4) & 15;
        int skyLight = (packedLight >> 20) & 15;
        boolean ready = true;
        for (Part part : baked.parts) {
            if (TOUCHED.add(part)) part.writer = part.mesh.begin(32);
            int tint = 0xFFFFFFFF;
            if (part.tintIndex >= 0) {
                try {
                    var mc = Minecraft.getInstance();
                    var tintSource = mc.getBlockColors().getTintSource(block, part.tintIndex);
                    if (tintSource != null && mc.level != null)
                        tint = 0xFF000000 | tintSource.colorInWorld(block, mc.level, worldPos);
                } catch (Throwable ignored) {}
            }
            part.writer.instance(model, blockLight, skyLight, tint);
            if (!part.mesh.gpuReady()) why(block, "mesh jeszcze nie na gpu");
            ready &= part.mesh.gpuReady();
        }
        return ready;
    }

    private static Baked bake(BlockState state, byte mask, net.minecraft.core.BlockPos pos,
                              com.koper.koper_lib.api.core.KenderMovingBlockContext ctx) {
        try {
            var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> modelParts = new ArrayList<>();
            RandomSource rng = RandomSource.create(42);
            // on a kontra the block's world neighbours are whatever it happens to be flying over —
            // asking mc.level there is how create's tanks never saw each other. ctx answers with the
            // kontra's own grid (it already translates world deltas back into locals).
            net.minecraft.client.renderer.block.BlockAndTintGetter level = ctx != null
                ? ctx : Minecraft.getInstance().level;
            // context aware first — an empty answer here is a real answer, not a failure
            lastBakeUsedContext = com.koper.koper_lib.compat.create.KenderModelContext
                    .collectWithContext(model, level, pos, state, rng, modelParts);
            if (!lastBakeUsedContext) model.collectParts(rng, modelParts);
            Map<PartKey, List<BakedQuad>> groups = new HashMap<>();
            for (BlockStateModelPart modelPart : modelParts) {
                add(groups, modelPart.getQuads(null));
                for (Direction direction : Direction.values()) {
                    if (visible(mask, direction)) add(groups, modelPart.getQuads(direction));
                }
            }
            List<Part> parts = new ArrayList<>();
            for (var entry : groups.entrySet()) {
                PartKey pk = entry.getKey();
                float[] verts = quads(entry.getValue());
                if (verts.length == 0) continue;
                Identifier id = Identifier.fromNamespaceAndPath("koper_lib", "vanilla_block/part_" + IDS.incrementAndGet());
                var material = pk.layer.translucent() ? KenderRenderAPI.Material.TRANSLUCENT
                    : pk.layer == ChunkSectionLayer.SOLID ? KenderRenderAPI.Material.SOLID : KenderRenderAPI.Material.CUTOUT;
                parts.add(new Part(KenderRenderAPI.mesh(id, verts, pk.atlas, material, KenderRenderAPI.Usage.DYNAMIC), pk.tintIndex));
            }
            return new Baked(parts);
        } catch (Throwable t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.debug("[Kender/block] vanilla bake refused {}: {}", state, t.toString());
            return new Baked(List.of());
        }
    }

    private static void add(Map<PartKey, List<BakedQuad>> groups, List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            var info = quad.materialInfo();
            PartKey key = new PartKey(info.sprite().atlasLocation(), info.layer(), info.tintIndex());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(quad);
        }
    }

    private static float[] quads(List<BakedQuad> quads) {
        float[] out = new float[quads.size() * 4 * KenderRenderAPI.VERTEX_FLOATS];
        int at = 0;
        for (BakedQuad quad : quads) {
            Vector3fc normal = quad.direction().getUnitVec3f();
            for (int i = 0; i < 4; i++) {
                Vector3fc pos = quad.position(i);
                long uv = quad.packedUV(i);
                out[at++] = pos.x(); out[at++] = pos.y(); out[at++] = pos.z();
                out[at++] = UVPair.unpackU(uv); out[at++] = UVPair.unpackV(uv);
                out[at++] = normal.x(); out[at++] = normal.y(); out[at++] = normal.z();
            }
        }
        return out;
    }

    private static boolean visible(byte mask, Direction direction) {
        int bit = switch (direction) {
            case EAST -> 0; case WEST -> 1; case UP -> 2; case DOWN -> 3; case SOUTH -> 4; case NORTH -> 5;
        };
        return (mask & 1 << bit) != 0;
    }

    private record PartKey(Identifier atlas, ChunkSectionLayer layer, int tintIndex) {}
    private record Baked(List<Part> parts) {}
    private static final class Part {
        final KenderRenderAPI.Mesh mesh;
        final int tintIndex;
        KenderRenderAPI.Writer writer;
        Part(KenderRenderAPI.Mesh mesh, int tintIndex) { this.mesh = mesh; this.tintIndex = tintIndex; }
    }
}
