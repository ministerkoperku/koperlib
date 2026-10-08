package com.koper.koper_lib.kender;

import com.koper.koper_lib.config.KoperLibConfig;
import com.koper.koper_lib.mixin.EntityAccessor;
import com.koper.koper_lib.physics.shape.KhysShapeCache;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

// submits kontraktion blocks via COLLECT_SUBMITS — goes through MC's Vulkan render graph
// no raw GL, no raw Vulkan handles — MC handles the backend (Vulkan when "Prefer Vulkan" is set)
public final class KenderRenderer {

    private static volatile boolean capsInit = false;
    private static final java.util.Map<net.minecraft.world.level.block.entity.BlockEntity, BlockEntityRenderState> BE_STATES =
        new java.util.WeakHashMap<>();
    private static final java.util.Set<String> BE_RENDER_ERRORS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Quaternionf ROT_TMP = new Quaternionf();
    private static final Quaternionf INV_ROT_TMP = new Quaternionf();
    private static final Vector3f CAMERA_LOCAL_TMP = new Vector3f();
    private static final Vector3f ROT_OFF_TMP = new Vector3f();
    private static final BlockPos.MutableBlockPos LOCAL_POS_TMP = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos WORLD_POS_TMP = new BlockPos.MutableBlockPos();
    private static final KontraMovingBlockState GPU_BLOCK_STATE = new KontraMovingBlockState();
    // the collector holds these until the pass runs, so they can't be one shared scratch — but they
    // CAN be recycled next frame, by which point last frame's pass is long recorded
    private static KontraMovingBlockState[] STATE_POOL = new KontraMovingBlockState[512];
    private static int statePoolAt;

    private static KontraMovingBlockState pooledState() {
        if (statePoolAt >= STATE_POOL.length)
            STATE_POOL = java.util.Arrays.copyOf(STATE_POOL, STATE_POOL.length * 2);
        KontraMovingBlockState s = STATE_POOL[statePoolAt];
        if (s == null) STATE_POOL[statePoolAt] = s = new KontraMovingBlockState();
        statePoolAt++;
        return s;
    }
    private static final RandomSource BREAK_RANDOM = RandomSource.create();
    private static final java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>
        BREAK_PARTS = new java.util.ArrayList<>();

    // 12 edges of a box (bottom face, top face, 4 vertical pillars) — indexes into 8 corners
    private static final int[][] OBB_EDGES = {
        {0,1},{1,2},{2,3},{3,0},
        {4,5},{5,6},{6,7},{7,4},
        {0,4},{1,5},{2,6},{3,7}
    };

    private KenderRenderer() {}

    public static void init() {
        int rc = KenderBridge.init();
        KenderBridge.markReady(rc == 0);
        if (rc != 0) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kender] Rust engine missing — kontraktion rendering unavailable");
            return;
        }

        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
            // batch + flush bracket the WHOLE event (finally!) — a frame with zero kontras must still
            // flush, or the last kontra's GPU instances ghost around forever after it despawns
            com.koper.koper_lib.api.core.KenderGeoBridge.beginMovingFrame();
            try { collectSubmits(ctx); } finally {
                long profFlush = KontraLagSniffer.mark();
                com.koper.koper_lib.api.core.KenderGeoBridge.endMovingFrame();
                KontraLagSniffer.flushDone(profFlush);
            }
        });
        registerOutlineHook();
    }

    // which path actually drew each block, once per block. no string building per frame — that log
    // was allocating for every block of every kontra every frame and it showed.
    private static final java.util.HashSet<String> PATH_LOGGED = new java.util.HashSet<>();
    private static final java.util.Set<net.minecraft.world.level.block.Block> LOCAL_ON_CPU = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // not broken, just slow and worth knowing: the direct gpu path said no (opengl, or the mesh isn't
    // uploaded yet) so this local-data block goes through the cpu model with its own data
    private static void localDataOnCpu(net.minecraft.world.level.block.state.BlockState bs) {
        if (!LOCAL_ON_CPU.add(bs.getBlock())) return;
        com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
            "[kender] {} on a kontra is drawn by the CPU fallback, the direct path declined it (vulkan active: {})",
            bs.getBlock(), com.koper.koper_lib.api.core.KenderGeoBridge.vulkanActive());
    }

    private static void pathOnce(net.minecraft.world.level.block.state.BlockState bs, String path, byte mask) {
        if (!com.koper.koper_lib.config.KoperLibConfig.get().debugMode) return;
        if (!PATH_LOGGED.add(bs.getBlock().toString() + path)) return;
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg-C] render {} -> {} maska={}", bs.getBlock(), path, Integer.toBinaryString(mask & 63));
    }

    private static void collectSubmits(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext ctx) {
        {
            if (!KenderBridge.isOk() || KenderClientState.isEmpty()) return;

            // block changes only flagged themselves — ship them now, before anything reads the
            // Rust mesh this frame. same frame, so nothing is ever drawn from stale geometry.
            KenderClientState.flushRustBlocks();

            if (!capsInit) {
                capsInit = true;
                KenderBridge.detectAndUploadCaps();
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender] ready (mesh shaders: {})", KenderBridge.hasMeshShaders());
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            var collector = ctx.submitNodeCollector();
            var poseStack = ctx.poseStack();
            var cam = mc.gameRenderer.mainCamera().position();
            var lightEngine = mc.level.getLightEngine();
            long nowNs = System.nanoTime();
            KontraLagSniffer.frame();
            KontraLightBook.nextFrame();
            statePoolAt = 0;

            for (var k : KenderClientState.all()) {
                float[] rp = KenderClientState.renderPos(k, nowNs);
                float[] rr = KenderClientState.renderRot(k, nowNs);
                if (rp == null || rr == null) continue;
                KontraLagSniffer.kontra();
                float px = rp[0], py = rp[1], pz = rp[2];

                // whole-kontra reject before we touch a single block. the bound is a sphere so it
                // survives any rotation — no need to rebuild an OBB every frame just to cull.
                var cullFrustum = com.koper.koper_lib.api.core.KenderGeoBridge.frustum();
                if (cullFrustum != null) {
                    float br = (float)Math.sqrt(k.obbHalfX*k.obbHalfX + k.obbHalfY*k.obbHalfY + k.obbHalfZ*k.obbHalfZ);
                    if (!cullFrustum.isVisible(new AABB(px-br, py-br, pz-br, px+br, py+br, pz+br))) {
                        KontraLagSniffer.frustCull(k.states.length);
                        continue;
                    }
                }

                var rot = ROT_TMP.set(rr[0], rr[1], rr[2], rr[3]);
                var cameraLocal = CAMERA_LOCAL_TMP.set((float)(cam.x - px), (float)(cam.y - py), (float)(cam.z - pz));
                INV_ROT_TMP.set(rot).conjugate().transform(cameraLocal);

                byte[] masks = k.renderMasks;
                var biome = mc.level.getBiome(new BlockPos(Math.round(px), Math.round(py), Math.round(pz)));
                double rx = px - cam.x, ry = py - cam.y, rz = pz - cam.z;

                var cfg = com.koper.koper_lib.physics.KhysicsConfig.get();
                float cullDistSq = cfg.kenderBlockCullDistance > 0
                    ? cfg.kenderBlockCullDistance * cfg.kenderBlockCullDistance : Float.MAX_VALUE;
                int maxBlocks = cfg.kenderMaxBlocksPerKontraktion;
                int renderedCount = 0;
                var rotOff = ROT_OFF_TMP;
                long profLoop = KontraLagSniffer.mark();
                KontraLagSniffer.seen(k.states.length);

                for (int i = 0; i < k.states.length; i++) {
                    BlockState bs = k.states[i];
                    if (bs == null || bs.isAir()) continue;
                    if (masks != null && i < masks.length && masks[i] == 0 && bs.canOcclude()) {
                        KontraLagSniffer.maskCull();
                        continue;
                    }
                    if (maxBlocks > 0 && renderedCount >= maxBlocks) break;

                    float ox = k.offsets[i*3], oy = k.offsets[i*3+1], oz = k.offsets[i*3+2];
                    var localPos = LOCAL_POS_TMP.set(Math.round(ox), Math.round(oy), Math.round(oz));
                    int breakStage = KenderMiningClient.stage(k.id, localPos);

                    rotOff.set(ox, oy, oz);
                    rot.transform(rotOff);

                    // distance culling — skip blocks too far from camera to prevent Vulkan VBO overflow
                    double bwx = px + rotOff.x - cam.x;
                    double bwy = py + rotOff.y - cam.y;
                    double bwz = pz + rotOff.z - cam.z;
                    if (bwx*bwx + bwy*bwy + bwz*bwz > cullDistSq) {
                        KontraLagSniffer.distCull();
                        continue;
                    }

                    var worldBlockPos = WORLD_POS_TMP.set(
                        (int)Math.floor(px + rotOff.x),
                        (int)Math.floor(py + rotOff.y),
                        (int)Math.floor(pz + rotOff.z));

                    // one lookup for both the geo path below and the vanilla path further down
                    Object bind = com.koper.koper_lib.api.core.KenderGeoBridge.binding(bs);
                    boolean geoOnKontra = com.koper.koper_lib.api.core.KenderGeoBridge.rendersOnMovingGrid(bind);
                    // sampled once here for every path below — cached, so most frames this is an array read
                    int packedLight = KontraLightBook.sample(k, i, lightEngine, worldBlockPos);

                    // fluids before anything else. a water cell is RenderShape.INVISIBLE so every
                    // path below throws it away, and a waterlogged block wants its water drawn no
                    // matter which path eats the solid part.
                    if (k.fluidMasks[i] != 0) {
                        var wet = pooledState().resetOwned(localPos, worldBlockPos, bs, k.localMap, k.blockEntities);
                        wet.lightEngine = lightEngine;
                        wet.biome       = biome;
                        wet.cardinalLighting = CardinalLighting.DEFAULT;
                        poseStack.pushPose();
                        poseStack.translate(rx, ry, rz);
                        poseStack.rotate(rot);
                        poseStack.translate(ox - 0.5f, oy - 0.5f, oz - 0.5f);
                        if (KontraWodaKender.submit(collector, poseStack, wet, bs)) pathOnce(bs, "woda", (byte)0);
                        poseStack.popPose();
                    }

                    // static geo blocks on the kontra go through the instanced Vulkan path — one batched
                    // draw per model instead of a per-block submit. false = CPU keeps it (loads textures too)
                    if (breakStage < 0
                            && com.koper.koper_lib.api.core.KenderGeoBridge.submit(
                                mc.level, bind, bs, px, py, pz, rot, ox, oy, oz, worldBlockPos, packedLight)) {
                        pathOnce(bs, "geo", (byte)0);
                        renderedCount++;
                        KontraLagSniffer.gpu();
                        continue;
                    }

                    // isSolidRender() gated this, so anything that isn't a full opaque cube — a create
                    // tank, glass, any multiblock with a window — got 0x3F and drew all six faces,
                    // including the ones buried against its own neighbours. shouldRenderFace already
                    // knows better: it asks the block via skipRendering, which is how two tanks hide
                    // their shared wall in the world. let it decide for everyone.
                    byte faceMask = masks != null && i < masks.length ? masks[i] : 0x3F;
                    net.minecraft.nbt.CompoundTag localData = k.localData.get(localPos);
                    // a block that draws itself from local data can't share the gpu mesh cache below,
                    // that cache keys on neighbours only, so two different micro grids would get one mesh
                    boolean ownsLocal = localData != null
                            && com.koper.koper_lib.api.render.KenderLocalBlockRenderer.handles(bs.getBlock());
                    if (breakStage < 0 && localData != null
                            && com.koper.koper_lib.api.render.KenderLocalBlockRenderer.submit(
                                new com.koper.koper_lib.api.render.KenderLocalBlockRenderer.Context(
                                    bs, localData, localPos.immutable(), worldBlockPos.immutable(),
                                    px, py, pz, new org.joml.Quaternionf(rot), ox, oy, oz, packedLight))) {
                        renderedCount++;
                        continue;
                    }
                    // Legacy invisible BE models still render in the BE pass. Local-data geometry got
                    // its chance above, so only now is it safe to skip the normal full-block path.
                    if (bs.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) continue;
                    if (ownsLocal) localDataOnCpu(bs);
                    var gpuState = GPU_BLOCK_STATE.reset(localPos, worldBlockPos, bs, k.localMap, k.blockEntities).kontra(k.id);
                    gpuState.lightEngine = lightEngine;
                    gpuState.biome = biome;
                    gpuState.cardinalLighting = CardinalLighting.DEFAULT;
                    if (breakStage < 0 && !geoOnKontra && !ownsLocal
                            && com.koper.koper_lib.api.core.KenderGeoBridge.submitVanilla(
                                gpuState, bs, faceMask, px, py, pz, rot, ox, oy, oz, packedLight)) {
                        pathOnce(bs, "vanilla-kender", faceMask);
                        renderedCount++;
                        KontraLagSniffer.inst();
                        continue;
                    }
                    pathOnce(bs, "fallback-cpu", faceMask);

                    var state = pooledState().resetOwned(localPos, worldBlockPos, bs, k.localMap, k.blockEntities).data(localData);
                    state.lightEngine = lightEngine;
                    state.biome       = biome;
                    state.cardinalLighting = CardinalLighting.DEFAULT;

                    poseStack.pushPose();
                    poseStack.translate(rx, ry, rz);
                    poseStack.rotate(rot);
                    poseStack.translate(ox - 0.5f, oy - 0.5f, oz - 0.5f);
                    // a geo block on a kontraktion renders its own model; everything else uses the normal moving block
                    if (!com.koper.koper_lib.api.core.KenderGeoBridge.submitFallbackGeo(
                            collector, poseStack, bs, k.id, localPos)) {
                        collector.submitMovingBlock(poseStack, state, 0);
                        if (breakStage >= 0) {
                            BREAK_PARTS.clear();
                            BREAK_RANDOM.setSeed(bs.getSeed(localPos));
                            var breakModel = mc.getModelManager().getBlockStateModelSet().get(bs);
                            breakModel.collectParts(BREAK_RANDOM, BREAK_PARTS);
                            // flag 1 = translucent, the same check LevelRenderer makes for world blocks
                            collector.submitBreakingBlockModel(
                                poseStack, java.util.List.copyOf(BREAK_PARTS), breakStage, breakModel.hasMaterialFlag(1));
                        }
                    }
                    poseStack.popPose();
                    renderedCount++;
                    KontraLagSniffer.cpu();
                }
                KontraLagSniffer.loopDone(profLoop);

                long profBe = KontraLagSniffer.mark();
                var beDispatcher = mc.getBlockEntityRenderDispatcher();
                float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                for (var beEntry : k.blockEntities.entrySet()) {
                    var be = beEntry.getValue();
                    float[] off = k.offsetsByLocal.get(beEntry.getKey());
                    if (be == null || off == null) continue;
                    @SuppressWarnings("rawtypes")
                    BlockEntityRenderer renderer = beDispatcher.getRenderer(be);
                    if (renderer == null) continue;

                    var beWorldOff = rotOff.set(off[0], off[1], off[2]);
                    rot.transform(beWorldOff);
                    double beDx=px+beWorldOff.x-cam.x, beDy=py+beWorldOff.y-cam.y, beDz=pz+beWorldOff.z-cam.z;
                    double beDistSq=beDx*beDx+beDy*beDy+beDz*beDz;
                    double viewDistance=renderer.getViewDistance();
                    if (beDistSq > cullDistSq || beDistSq > viewDistance*viewDistance) {
                        KontraLagSniffer.be(false);
                        continue;
                    }
                    KontraLagSniffer.be(true);

                    try {
                        BlockEntityRenderState renderState = KenderClientState.inGrid(k, () -> {
                            BlockEntityRenderState state = BE_STATES.get(be);
                            if (state == null) {
                                state = (BlockEntityRenderState)renderer.createRenderState();
                                BE_STATES.put(be, state);
                            }
                            BlockPos gridPos = be.getBlockPos();
                            Vec3 gridCamera = new Vec3(
                                gridPos.getX() + 0.5 + cameraLocal.x - off[0],
                                gridPos.getY() + 0.5 + cameraLocal.y - off[1],
                                gridPos.getZ() + 0.5 + cameraLocal.z - off[2]);
                            renderer.extractRenderState(be, state, partialTick, gridCamera, null);
                            return state;
                        });
                        BlockPos beWorldPos = BlockPos.containing(px + beWorldOff.x, py + beWorldOff.y, pz + beWorldOff.z);
                        int blockLight = lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK).getLightValue(beWorldPos);
                        int skyLight = lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getLightValue(beWorldPos);
                        renderState.lightCoords = (blockLight << 4) | (skyLight << 20);

                        poseStack.pushPose();
                        poseStack.translate(rx, ry, rz);
                        poseStack.rotate(rot);
                        poseStack.translate(off[0] - 0.5f, off[1] - 0.5f, off[2] - 0.5f);
                        try {
                            KenderClientState.inGrid(k, () -> {
                                renderer.submit(renderState, poseStack, collector, mc.levelRenderer.levelRenderState.cameraRenderState);
                                return null;
                            });
                        } finally { poseStack.popPose(); }
                    } catch (Throwable ex) {
                        // one puking BE renderer (looking at you ArmRenderer) must not kill the render thread
                        if (BE_RENDER_ERRORS.add(be.getType().toString()))
                            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kender] kontra BE renderer failed for {} — frame skipped (logged once)", be.getType(), ex);
                    }
                }
                KontraLagSniffer.beDone(profBe);

                // rotated OBB outline for the targeted block on this kontraktion
                KenderTargeting.PhysHit hit = KenderTargeting.getHit();
                if (hit != null && hit.kontraId() == k.id) {
                    int idx = hit.blockIndex();
                    if (idx * 3 + 2 < k.offsets.length) {
                        float ox = k.offsets[idx*3], oy = k.offsets[idx*3+1], oz = k.offsets[idx*3+2];
                        var outlineOff = rotOff.set(ox, oy, oz);
                        rot.transform(outlineOff);

                        // outline matches the block's ACTUAL shape (slab/stairs), not always a full cube
                        BlockState tbs = (k.states != null && idx < k.states.length) ? k.states[idx] : null;
                        List<AABB> outlineShapes = tbs != null
                            ? KhysShapeCache.get(tbs, k.localData.get(hit.localPos()))
                            : KhysShapeCache.FULL_CUBE;

                        poseStack.pushPose();
                        poseStack.translate(px + outlineOff.x - cam.x, py + outlineOff.y - cam.y, pz + outlineOff.z - cam.z);
                        poseStack.rotate(rot);
                        // raw VertexConsumer — draw the shape's box edges in local space, rotated per-vertex by the pose
                        collector.submitCustomGeometry(poseStack, RenderTypes.lines(),
                            (pose, consumer) -> drawShapeLines(pose, consumer, outlineShapes));
                        poseStack.popPose();
                    }
                }
            }

            LocalPlayer player = mc.player;
            if (player != null && shouldDrawPlayerObb(mc)) {
                float[] pr = KontraRideClient.collisionRideRot(player);
                if (pr != null && pr.length >= 4) {
                    AABB box = player.getBoundingBox();
                    EntityAccessor acc = (EntityAccessor)(Object)player;
                    float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                    double rx = Mth.lerp(pt, acc.getXOld(), player.getX());
                    double ry = Mth.lerp(pt, acc.getYOld(), player.getY());
                    double rz = Mth.lerp(pt, acc.getZOld(), player.getZ());
                    double cx = rx + (box.minX + box.maxX) * 0.5 - player.getX();
                    double cy = ry + (box.minY + box.maxY) * 0.5 - player.getY();
                    double cz = rz + (box.minZ + box.maxZ) * 0.5 - player.getZ();
                    float hx = (float)((box.maxX - box.minX) * 0.5);
                    float hy = (float)((box.maxY - box.minY) * 0.5);
                    float hz = (float)((box.maxZ - box.minZ) * 0.5);
                    poseStack.pushPose();
                    poseStack.translate(cx - cam.x, cy - cam.y, cz - cam.z);
                    poseStack.rotate(ROT_TMP.set(pr[0], pr[1], pr[2], pr[3]).normalize());
                    collector.submitCustomGeometry(poseStack, RenderTypes.lines(),
                        (pose, consumer) -> drawCenteredBoxLines(pose, consumer, hx, hy, hz, 0xCCFF2020, 4.0f));
                    poseStack.popPose();
                }
            }
        }
    }

    private static void registerOutlineHook() {
        // cancel vanilla axis-aligned selection box while we're drawing our own rotated one
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, state) -> !KenderTargeting.isTargeting());
    }

    // draw the target outline in block-local shape space; pose already carries kontra rotation.
    private static void drawShapeLines(PoseStack.Pose pose, VertexConsumer consumer, List<AABB> shapes) {
        int color = 0x66000000; // semi-transparent black
        for (AABB s : shapes) {
            // 0-1 shape space → center on the block (−0.5), nudge 2mm outwards so lines sit just off the faces
            float x0 = (float)s.minX - 0.502f, y0 = (float)s.minY - 0.502f, z0 = (float)s.minZ - 0.502f;
            float x1 = (float)s.maxX - 0.498f, y1 = (float)s.maxY - 0.498f, z1 = (float)s.maxZ - 0.498f;
            float[][] c = {
                {x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},
                {x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}
            };
            for (int[] e : OBB_EDGES) {
                float[] a = c[e[0]], b = c[e[1]];
                float nx = b[0]-a[0], ny = b[1]-a[1], nz = b[2]-a[2];
                float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                if (len > 1e-6f) { nx/=len; ny/=len; nz/=len; }
                consumer.addVertex(pose, a[0], a[1], a[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(2.5f);
                consumer.addVertex(pose, b[0], b[1], b[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(2.5f);
            }
        }
    }

    private static void drawCenteredBoxLines(PoseStack.Pose pose, VertexConsumer consumer,
                                             float hx, float hy, float hz, int color, float width) {
        float[][] c = {
            {-hx,-hy,-hz},{ hx,-hy,-hz},{ hx, hy,-hz},{-hx, hy,-hz},
            {-hx,-hy, hz},{ hx,-hy, hz},{ hx, hy, hz},{-hx, hy, hz}
        };
        for (int[] e : OBB_EDGES) {
            float[] a = c[e[0]], b = c[e[1]];
            float nx = b[0]-a[0], ny = b[1]-a[1], nz = b[2]-a[2];
            float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
            if (len > 1e-6f) { nx/=len; ny/=len; nz/=len; }
            consumer.addVertex(pose, a[0], a[1], a[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
            consumer.addVertex(pose, b[0], b[1], b[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        }
    }

    private static boolean shouldDrawPlayerObb(Minecraft mc) {
        return KoperLibConfig.get().debugMode;
    }

    private static boolean identityRot(float[] q) {
        return q == null || q.length < 4
                || (Math.abs(q[0]) + Math.abs(q[1]) + Math.abs(q[2]) < 1.0e-5f && Math.abs(q[3] - 1.0f) < 1.0e-5f);
    }

    // Gribb/Hartmann frustum — 4 side planes, used by debug commands / future frustum pre-cull
    public static boolean sphereInFrustum(float[] m, float cx, float cy, float cz, float r) {
        float[] pl = {
            m[3]+m[0], m[7]+m[4], m[11]+m[8],  m[15]+m[12],
            m[3]-m[0], m[7]-m[4], m[11]-m[8],  m[15]-m[12],
            m[3]+m[1], m[7]+m[5], m[11]+m[9],  m[15]+m[13],
            m[3]-m[1], m[7]-m[5], m[11]-m[9],  m[15]-m[13],
        };
        for (int p = 0; p < 4; p++) {
            float a=pl[p*4], b=pl[p*4+1], c=pl[p*4+2], d=pl[p*4+3];
            if (a*cx + b*cy + c*cz + d < -r*(float)Math.sqrt(a*a+b*b+c*c)) return false;
        }
        return true;
    }
}
