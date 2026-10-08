package com.koper.koper_lib.kodel;

import com.koper.koper_lib.kender.KenderBridge;
import com.koper.koper_lib.kender.KenderFrame;
import com.koper.koper_lib.kender.KenderVk;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

// a block drawn from a .kodel, with no block entity: vanilla draws nothing (empty model) and this does.
// static blocks are instanced on the Kender Vulkan path, animated ones skinned on the GPU, and anything
// the GPU cannot take goes through COLLECT_SUBMITS. the position index is built on chunk load, so
// rendering never scans the world per frame
public final class KodelBlockRenderer {

    private KodelBlockRenderer() {}

    // per-placed-block render state. curClip/clipStart give each block its own animation timeline,
    // so a play_once / hold_on_last_frame clip (door, lever) restarts from frame 0 when the trigger flips
    private static final class Placed {
        final BlockPos pos;
        final KodelBlockBook.Binding bind;
        final BlockState state;    // for rotate_by facing + state triggers ("lit" etc)
        final net.minecraft.world.phys.AABB cullBox; // precomputed once (blocks don't move) — no per-frame alloc
        final boolean animated;    // cached — the per-frame loop must not re-derive this per block
        final boolean gpuEligible; // not translucent -> can ride the raw Vulkan path (tint rides an instance attr now)
        final float tintEnc;       // tint as VALUE r + g*256 + b*65536 — raw rgba8 bits in a float are NaN food
        final MState mst;          // shared per-model GPU state: 2 field reads instead of map lookups per block per frame
        int lightCache = -1;       // packed light, refreshed staggered / by the light watchdog
        long trigAt = -1;          // trigger cache — redstone is 6 world reads, NOT per-frame material
        java.util.Set<String> trigSet = java.util.Set.of();
        String trigKey = "";       // stable joined form of trigSet, goes into pose-share keys
        String curClip;     // clip currently playing (null = none)
        double clipStart;   // time (s) the current clip began
        Placed(BlockPos pos, KodelBlockBook.Binding bind, BlockState state) {
            this.pos = pos; this.bind = bind; this.state = state;
            this.animated = !bind.anim().isEmpty() || bind.animation() != null || !bind.clips().isEmpty();
            this.gpuEligible = bind.bones().isEmpty() && bind.boneWhen().isEmpty();
            this.tintEnc = KenderFrame.tintEnc(bind.tint());
            this.mst = state(bind.model());
            if (!this.gpuEligible) this.mst.cpuOnly = true;
            // a stretch/shift op can send a bone far above its own cell (a lift mast climbs 5 blocks),
            // and a model culled by its cell just pops out of view. give those room
            double reach = bind.anim().stream().anyMatch(op ->
                op.kind() == KodelBlockBook.SHIFT || op.kind() == KodelBlockBook.STRETCH) ? 8.0 : 3.0;
            this.cullBox = new net.minecraft.world.phys.AABB(
                pos.getX() - 1, pos.getY() - 0.5, pos.getZ() - 1,
                pos.getX() + 2, pos.getY() + reach, pos.getZ() + 2); // margin for tall/wide models
        }
    }

    // per-model GPU-path status, shared by every Placed of that model
    static final class MState {
        volatile boolean texReady;
        volatile boolean gpuAnim;
        volatile boolean cpuOnly;
        final Map<String, Integer> groupIdx = new HashMap<>();
        final List<String> groupOrder = new ArrayList<>();
        final List<AnimArgs> groupArgs = new ArrayList<>();
        final it.unimi.dsi.fastutil.floats.FloatArrayList bones = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        float[] animInstances = new float[0];
    }
    private record AnimArgs(KodelBlockBook.Binding bind, String target, double clipTime,
                            java.util.Set<String> active, BlockState state) {}
    private static final Map<String, MState> MODEL_STATE = new ConcurrentHashMap<>();
    static MState state(String model) { return MODEL_STATE.computeIfAbsent(model, k -> new MState()); }
    private static final ThreadLocal<Map<String, float[]>> POSE_SCRATCH =
        ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Map<String, float[]>> CPU_BAKE_SCRATCH =
        ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<List<List<Placed>>> CPU_LIST_SCRATCH =
        ThreadLocal.withInitial(ArrayList::new);

    // chunkPos.pack() -> list of koperblocks in that chunk
    private static final Map<Long, List<Placed>> INDEX = new ConcurrentHashMap<>();
    // dirty rebuilds work from this index. Scanning every loaded chunk again was the real 100k-block tax.
    private static final Map<String, List<Placed>> STATIC_BY_MODEL = new HashMap<>();
    private static final List<Placed> STATIC_WATCH = new ArrayList<>();
    private static int staticWatchCursor;
    // model -> animated placed blocks (flat, for the GPU skinning feeder — no full-INDEX walk per frame)
    private static final Map<String, List<Placed>> ANIM_BY_MODEL = new ConcurrentHashMap<>();

    private static void animAdd(Placed p) {
        ANIM_BY_MODEL.computeIfAbsent(p.bind.model(), k -> new CopyOnWriteArrayList<>()).add(p);
    }
    private static void animRemove(Placed p) {
        List<Placed> l = ANIM_BY_MODEL.get(p.bind.model());
        // keep the empty entry — prepAnimated must see it once more to clear the GPU instances (no ghosts)
        if (l != null) l.remove(p);
    }

    private static void staticAdd(Placed p) {
        STATIC_BY_MODEL.computeIfAbsent(p.bind.model(), k -> new ArrayList<>()).add(p);
        STATIC_WATCH.add(p);
    }

    private static void staticRemove(Placed p) {
        List<Placed> l = STATIC_BY_MODEL.get(p.bind.model());
        if (l != null) {
            l.remove(p);
            if (l.isEmpty()) STATIC_BY_MODEL.remove(p.bind.model());
        }
        int at = STATIC_WATCH.indexOf(p);
        if (at >= 0) {
            STATIC_WATCH.remove(at);
            if (at < staticWatchCursor) staticWatchCursor--;
        }
    }

    private static void placedAdd(Placed p) {
        if (p.animated) animAdd(p); else staticAdd(p);
    }

    private static void placedRemove(Placed p) {
        if (p.animated) animRemove(p); else staticRemove(p);
    }

    public static void init() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> indexChunk(world, chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> unindexChunk(chunk.getPos().pack()));
        LevelRenderEvents.COLLECT_SUBMITS.register(KodelBlockRenderer::onCollect);
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
            .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                @Override public net.minecraft.resources.Identifier getFabricId() {
                    return net.minecraft.resources.Identifier.fromNamespaceAndPath("koper_lib", "kender_block_reload");
                }

                @Override
                public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                    Minecraft.getInstance().execute(KodelBlockRenderer::reindexLoadedChunks);
                }
            });
        KenderFrame.register(new KenderFrame.Contributor() {
            @Override public void prepareFrame() {
                prepInstances();
                prepAnimated();
            }

            @Override public void originChanged() { ALL_DIRTY = true; }

            @Override public void shaderFallback(boolean on) { if (on) clearIndex(); }

            @Override public void reset() { clearIndex(); }
        });
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kodel] model block renderer up");
    }

    static void unindexChunk(long key) {
        List<Placed> old = INDEX.remove(key);
        if (old == null) return;
        for (Placed p : old) {
            placedRemove(p);
            DIRTY.add(p.bind.model());
        }
    }

    public static void clearIndex() {
        // these ids belong only to model blocks and kontra. Create and public API use their own negative id space.
        for (long id : MODEL_IDS.values()) KenderBridge.geoRemove(id);
        MODEL_IDS.clear();
        INDEX.clear();
        ANIM_BY_MODEL.clear();
        STATIC_BY_MODEL.clear();
        STATIC_WATCH.clear();
        MODEL_STATE.clear();
        staticWatchCursor = 0;
        DIRTY.clear();
        ALL_DIRTY = true;
        TEX_WAIT.clear();
        UPLOADED.clear();
        SKIN_UPLOADED.clear();
        SKIN_FALLBACK.clear();
        LAST_TEX.clear();
        BONE_ORDER.clear();
        LOG_NOTEX.clear();
        LOG_TEX.clear();
        LOG_SKIN.clear();
        KodelTriggerBox.clear();
        STATIC_MESH.clear();
        SKIN_MESH.clear();
        KONTRA_MATERIAL.clear();
        KONTRA_TEXID.clear();
        KONTRA_BATCH.clear();
        KONTRA_LIVE.clear();
        KONTRA_TEX_OK.clear();
        KONTRA_SKIN_BIND.clear();
        KONTRA_SKIN_BATCH.clear();
        KONTRA_SKIN_BONES.clear();
        KONTRA_SKIN_LIVE.clear();
        KONTRA_SKIN_OK.clear();
    }

    private static void reindexLoadedChunks() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        clearIndex();
        if (world == null || mc.player == null || KodelBlockBook.isEmpty()) return;

        BlockPos playerPos = mc.player.blockPosition();
        ChunkPos center = new ChunkPos(playerPos.getX() >> 4, playerPos.getZ() >> 4);
        int radius = mc.options.getEffectiveRenderDistance() + 2;
        int indexed = 0;
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                LevelChunk chunk = world.getChunkSource().getChunk(
                    x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (chunk == null) continue;
                indexChunk(world, chunk);
                indexed++;
            }
        }
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kodel] resource reload reindexed {} loaded chunks", indexed);
    }

    // ── culling + pause-aware clock (real perf, not Vulkan) ──────────────────────
    // MC's OWN camera cull-frustum (CameraRenderState.cullFrustum), captured each frame from the render mixin.
    // already camera-prepared -> isVisible(worldAABB) is the EXACT test MC uses for chunks/entities. zero guessing.

    // a clock that FREEZES while the game is paused (singleplayer) so animations stop with the world
    private static double clockSec;
    private static long lastNanos;
    private static double clock(Minecraft mc) {
        long now = System.nanoTime();
        if (lastNanos != 0 && !mc.isPaused()) clockSec += (now - lastNanos) / 1.0e9;
        lastNanos = now;
        return clockSec;
    }

    // palette check rejects almost every section without touching all its positions; only a section
    // that can actually contain a bound block pays the 4096-state walk.
    static void indexChunk(ClientLevel world, LevelChunk chunk) {
        if (KodelBlockBook.isEmpty()) return;

        ChunkPos cp = chunk.getPos();
        int baseX = cp.getMinBlockX(), baseZ = cp.getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<Placed> found = new ArrayList<>();

        var sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()
                    || !section.maybeHas(s -> KodelBlockBook.binding(s.getBlock()) != null)) continue;
            int baseY = world.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState s = section.getBlockState(x, y, z);
                        KodelBlockBook.Binding b = KodelBlockBook.binding(s.getBlock());
                        if (b == null) continue;
                        m.set(baseX + x, baseY + y, baseZ + z);
                        found.add(new Placed(m.immutable(), b, s));
                    }
                }
            }
        }

        List<Placed> old = found.isEmpty() ? INDEX.remove(cp.pack())
                                           : INDEX.put(cp.pack(), new CopyOnWriteArrayList<>(found));
        if (old != null) for (Placed p : old) { placedRemove(p); DIRTY.add(p.bind.model()); }
        for (Placed p : found) { placedAdd(p); DIRTY.add(p.bind.model()); }
    }

    // called from KodelBlockUpdateMixin on EVERY client block change — keeps the index live
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        if (KodelBlockBook.isEmpty()) return;
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        BlockPos ip = pos.immutable();
        KodelBlockBook.Binding b = KodelBlockBook.binding(state.getBlock());
        List<Placed> list = INDEX.get(key);
        if (list != null) {
            for (Placed p : list) {
                if (p.pos.equals(ip)) {
                    list.remove(p);
                    placedRemove(p);
                    DIRTY.add(p.bind.model()); // only THIS model rebuilds, not the whole world
                    break;
                }
            }
            if (list.isEmpty() && b == null) INDEX.remove(key);
        }
        if (b != null) {
            if (list == null) { list = new CopyOnWriteArrayList<>(); INDEX.put(key, list); }
            Placed np = new Placed(ip, b, state);
            list.add(np);
            placedAdd(np);
            DIRTY.add(b.model());
        }
    }

    private static void onCollect(LevelRenderContext ctx) {
        renderGeo(ctx.submitNodeCollector(), ctx.poseStack());
    }

    // second entry point: runs the geo pass without going through COLLECT_SUBMITS. Left over from a 26.2
    // snapshot where a renderer swallowed the event; nothing calls it today (Sodium fires it normally).
    // Kept public on purpose — it is the hook to reach for if some renderer ever eats the event again.
    public static void renderGeo(OrderedSubmitNodeCollector collector, PoseStack poseStack) {
        KenderVk.tryInit(); // share MC's Vulkan device once, on the render thread
        if (INDEX.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var cam = mc.gameRenderer.mainCamera().position();

        var cfg = com.koper.koper_lib.kender.KenderConfig.get();
        // render up to the world render distance (frustum culls off-screen) — never the old tiny 128 cap.
        // an explicit config value larger than the render distance still wins
        float renderDist = (mc.options.getEffectiveRenderDistance() + 2) * 16f;
        float cullDist = cfg.kenderBlockCullDistance > 0 ? Math.max(cfg.kenderBlockCullDistance, renderDist) : renderDist;
        float cullSq = cullDist * cullDist;

        // shared clock for spins (seconds) — freezes on pause
        double timeSec = clock(mc);
        boolean vkGeo = KenderFrame.vulkanActive(); // static blocks go to the Vulkan instanced path -> skip them in the MC loop

        // MC's real cull-frustum (captured from the render mixin): skip ONLY what the camera can't see
        var frustum = KenderFrame.frustum();

        // per-frame cache: identical (model+clip+frame+powered) animated blocks share ONE baked posed mesh,
        // so 40 synced kapokas = 1 bone-tree walk + 40 cheap static draws instead of 40 walks.
        Map<String, float[]> bakeCache = CPU_BAKE_SCRATCH.get();
        bakeCache.clear();

        Iterable<List<Placed>> renderLists = INDEX.values();
        if (vkGeo) {
            List<List<Placed>> cpuLists = CPU_LIST_SCRATCH.get();
            cpuLists.clear();
            for (List<Placed> list : STATIC_BY_MODEL.values()) {
                if (!list.isEmpty() && (list.get(0).mst.cpuOnly || !list.get(0).mst.texReady)) cpuLists.add(list);
            }
            for (List<Placed> list : ANIM_BY_MODEL.values()) {
                if (!list.isEmpty() && (list.get(0).mst.cpuOnly || !list.get(0).mst.gpuAnim)) cpuLists.add(list);
            }
            renderLists = cpuLists;
        }

        for (List<Placed> list : renderLists) {
            for (Placed p : list) {
                // GPU-owned blocks bail on two field reads — this loop must stay ~free at 100k blocks.
                // static: skip once the texture is on the Vulkan path (MC's draw is what loads it first).
                // animated: skip once the skinning feeder went live for the model.
                if (vkGeo && p.gpuEligible && (p.animated ? p.mst.gpuAnim : p.mst.texReady)) continue;

                KodelBlockBook.Binding bind = p.bind;
                double dx = p.pos.getX() + 0.5 - cam.x;
                double dy = p.pos.getY() - cam.y;
                double dz = p.pos.getZ() + 0.5 - cam.z;
                if (dx * dx + dy * dy + dz * dz > cullSq) continue;
                // off-screen cull with MC's real frustum + the block's precomputed box (no per-frame alloc)
                if (frustum != null && !frustum.isVisible(p.cullBox)) continue;

                boolean animated = p.animated;

                // resolve a FLAT mesh for this frame. animated -> bake the pose once per shared key. static -> cached mesh.
                float[] mesh;
                if (animated) {
                    var act = triggers(p, mc.level);
                    String target = selectClip(bind, act);
                    double ct = clipTime(p, target, timeSec);
                    long frame = Math.round(ct * 60.0); // 60fps anim resolution; synced blocks share the key
                    // shift/stretch read a NUMBER off the blockstate, so two blocks on the same clip and
                    // frame can still be posed differently. without this in the key they all share
                    // whichever mesh got baked first and nothing ever appears to move
                    java.util.Set<String> visible = visibleOf(bind, p.state);
                    String key = bind.model() + '|' + target + '|' + frame + '|' + p.trigKey
                            + stateKey(bind, p.state)
                            + (visible == null ? "" : "|" + new java.util.TreeSet<>(visible));
                    mesh = bakeCache.get(key);
                    if (mesh == null) {
                        KodelBook.Entry entry = KodelBook.get(bind.model());
                        if (entry == null) continue;
                        var pose = buildPoseScratch(bind, target, ct, timeSec, act, p.state);
                        mesh = KodelModelRender.bake(entry.model(), worldOf(entry, pose), null, visible);
                        bakeCache.put(key, mesh);
                    }
                } else {
                    java.util.Set<String> visible = visibleOf(bind, p.state);
                    mesh = staticMesh(bind.model(), visible);
                }
                if (mesh == null) continue;

                RenderType rt = renderType(bind);
                int light = KenderFrame.packedLight(mc.level, p.pos); // real world light, not full-bright
                int tint = bind.tint();                   // procedural color (white = untinted)
                final float[] fm = mesh;

                poseStack.pushPose();
                poseStack.translate(dx, dy, dz);
                applyFacing(poseStack, bind, p.state);
                applyBaseTransform(poseStack, bind);
                collector.submitCustomGeometry(poseStack, rt, (snap, consumer) -> {
                    try { // a malformed model must never crash the game
                        renderStatic(fm, snap, consumer, light, OverlayTexture.NO_OVERLAY, tint);
                    } catch (Throwable ignored) {}
                });
                poseStack.popPose();
            }
        }
    }

    // render a geo model ON a kontraktion — called from KenderRenderer for each contraption block
    // poseStack is already at the block corner (after translate ox-0.5..). returns true = this is a geo block, skip vanilla submit
    public static boolean submitGeoOnKontra(OrderedSubmitNodeCollector collector, PoseStack poseStack, BlockState state) {
        return submitGeoOnKontra(collector, poseStack, state, 0L, null);
    }

    public static boolean submitGeoOnKontra(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                                            BlockState state, long kontraId, BlockPos local) {
        KodelBlockBook.Binding bind = KodelBlockBook.binding(state.getBlock());
        if (bind == null || !bind.onKontra()) return false;

        boolean animated = !bind.anim().isEmpty() || bind.animation() != null || !bind.clips().isEmpty();
        java.util.Set<String> visible = visibleOf(bind, state);
        KodelBook.Entry entry = KodelBook.get(bind.model());
        if (entry == null) return true; // a model block: suppress vanilla even if the model is missing

        RenderType rt = renderType(bind);
        // no fixed world position on a moving kontra, so no redstone. state triggers ("lit") and
        // code triggers keyed by (kontraId, local) still fire: the engine glows while driving
        final float[] mesh;
        if (animated) {
            double now = System.nanoTime() / 1_000_000_000.0;
            var act = stateTriggers(bind, state, kontraId, local);
            // baked now, not inside the lambda: the pose scratch map is reused by the next block
            mesh = KodelModelRender.bake(entry.model(),
                worldOf(entry, buildPoseScratch(bind, selectClip(bind, act), now, now, act, state)), null, visible);
        } else {
            mesh = staticMesh(bind.model(), visible);
        }
        if (mesh == null) return true;

        poseStack.pushPose();
        poseStack.translate(0.5, 0, 0.5); // corner -> center-bottom (same as the static path)
        applyFacing(poseStack, bind, state);
        applyBaseTransform(poseStack, bind);
        collector.submitCustomGeometry(poseStack, rt, (snap, consumer) -> {
            try {
                renderStatic(mesh, snap, consumer, 0x00F000F0, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            } catch (Throwable ignored) {}
        });
        poseStack.popPose();
        return true;
    }

    // ── 2b: direct Vulkan instancing (static + GPU-skinned animated) ──────────
    private static final Map<String, Long> MODEL_IDS = new ConcurrentHashMap<>();
    private static final java.util.Set<Long> UPLOADED = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> LOG_NOTEX = ConcurrentHashMap.newKeySet(); // log "no texture" once per model
    private static final java.util.Set<String> LOG_TEX = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_MODEL_ID = new java.util.concurrent.atomic.AtomicLong(1);

    // per-model dirty tracking — a placed block rebuilds ONE model's instances, not the whole world.
    // ALL_DIRTY only on origin shift / reload. models waiting for a texture retry on a slow heartbeat
    // instead of rebuilding every frame (that every-frame rebuild was a silent fps eater).
    private static final java.util.Set<String> DIRTY = ConcurrentHashMap.newKeySet();
    private static volatile boolean ALL_DIRTY = true;
    private static final java.util.Set<String> TEX_WAIT = ConcurrentHashMap.newKeySet();

    // GPU skinning bookkeeping: models that refused the GPU path stay on CPU forever (no retry spam)
    private static final java.util.Set<String> SKIN_FALLBACK = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Long> SKIN_UPLOADED = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> LOG_SKIN = ConcurrentHashMap.newKeySet();
    // per-frame upload dedup: texture push only on change; bone SSBO only when the anim-state set moved
    // (a 60fps-bucketed clip at 300fps repeats the same bones ~5 frames in a row — don't re-walk/re-upload)
    private static final Map<Long, Long> LAST_TEX = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> BONE_ORDER = new ConcurrentHashMap<>();

    // safety net: if NO world pass consumed the armed frame for 60 frames straight, our label match is
    // dead (renamed pass / unknown renderer) — latch to the CPU path so blocks stay VISIBLE, not gone

    // only when the user opted in AND MC shared its Vulkan device with us; else stay on COLLECT_SUBMITS
    // resolved once per frame in armFrame — KenderFrame.vulkanActive() runs per block, and asking a shader mod
    // over reflection that often would cost more than it saves



    // per-frame handoff between the LevelRenderer HEAD mixin (arms + hands us the viewProj) and the
    // submitRenderPass mixin (consumes it exactly once, on the first world pass). keeps the draw single.





    // trigger + clip-timeline helpers shared by the GPU feeder and the CPU fallback (MUST stay in sync).
    // a trigger is: "powered" = redstone at the block, anything else = a true boolean blockstate
    // property of that name ("lit", "open"...) OR a KodelTriggerBox flip from code/Lua. cached 4 frames.
    private static java.util.Set<String> triggers(Placed p, Level level) {
        if (p.trigAt >= 0 && KenderFrame.frameNo() - p.trigAt < 4) return p.trigSet;
        p.trigAt = KenderFrame.frameNo();
        var wanted = p.bind.triggers();
        if (wanted.isEmpty()) return p.trigSet;
        java.util.Set<String> act = null;
        for (String t : wanted) {
            boolean on = "powered".equals(t) ? level.hasNeighborSignal(p.pos)
                : stateTrigger(p.state, t) || KodelTriggerBox.has(p.pos, t);
            if (on) { if (act == null) act = new java.util.HashSet<>(4); act.add(t); }
        }
        p.trigSet = act == null ? java.util.Set.of() : act;
        if (p.trigSet.isEmpty()) { p.trigKey = ""; return p.trigSet; }
        StringBuilder sb = new StringBuilder();
        for (String t : wanted) if (p.trigSet.contains(t)) sb.append(t).append('|'); // stable order
        p.trigKey = sb.toString();
        return p.trigSet;
    }

    // a boolean blockstate property matching the trigger's name, true = on
    static boolean stateTrigger(BlockState state, String name) {
        if (state == null) return false;
        var prop = state.getBlock().getStateDefinition().getProperty(name);
        return prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty bp
            && state.getValue(bp);
    }

    // state-only trigger scan for kontra blocks (no world pos → no redstone). code triggers keyed
    // by (kontraId, local) still count when the caller knows them
    private static java.util.Set<String> stateTriggers(KodelBlockBook.Binding b, BlockState state, long kontraId, BlockPos local) {
        if (b.triggers().isEmpty()) return java.util.Set.of();
        java.util.Set<String> act = null;
        for (String t : b.triggers()) {
            boolean on = stateTrigger(state, t)
                || (local != null && KodelTriggerBox.hasKontra(kontraId, local, t));
            if (on) { if (act == null) act = new java.util.HashSet<>(4); act.add(t); }
        }
        return act == null ? java.util.Set.of() : act;
    }

    // the default looping clip rides the SHARED clock, so every block of a model is phase-synced ->
    // ONE bone walk per model per frame instead of one per block (that per-block walk was the anim lag).
    // trigger clips (powered) keep their own timeline so doors restart from frame 0.
    private static double clipTime(Placed p, String target, double timeSec) {
        if (!java.util.Objects.equals(p.curClip, target)) { p.curClip = target; p.clipStart = timeSec; }
        return java.util.Objects.equals(target, p.bind.animation()) ? timeSec : timeSec - p.clipStart;
    }

    // floating origin: instances are stored RELATIVE to this point near the camera, and the viewProj bakes
    // only the (origin - camPos) fraction. QUANTIZED to a 256 grid: numbers stay small enough for float
    // (offsets < ~768 -> sub-0.0001 precision) and a full instance re-upload happens once per 256 blocks
    // walked instead of EVERY block crossed (that per-block rebuild was the walk-around fps eater).






    // instance model matrix: block position (origin-relative) + the binding's base offset/scale/rotate —
    // same order as the CPU path's applyBaseTransform, so both paths agree on where the model sits
    private static void instMatrix(org.joml.Matrix4f m, Placed p, KodelBlockBook.Binding b) {
        m.translation((float) (p.pos.getX() + 0.5 - KenderFrame.renderOriginX()),
                      (float) (p.pos.getY() - KenderFrame.renderOriginY()),
                      (float) (p.pos.getZ() + 0.5 - KenderFrame.renderOriginZ()));
        applyFacing(m, b, p.state);
        applyBase(m, b);
    }

    private static void applyBase(org.joml.Matrix4f m, KodelBlockBook.Binding b) {
        float[] off = b.offset(); float sc = b.scale(); float[] rot = b.rotate();
        if (off[0] != 0 || off[1] != 0 || off[2] != 0) m.translate(off[0] / 16f, off[1] / 16f, off[2] / 16f);
        if (sc != 1f) m.scale(sc);
        if (rot[2] != 0) m.rotateZ((float) Math.toRadians(rot[2]));
        if (rot[1] != 0) m.rotateY((float) Math.toRadians(rot[1]));
        if (rot[0] != 0) m.rotateX((float) Math.toRadians(rot[0]));
    }

    // ── kontra feeder: geo blocks on MOVING kontraktions ride the same instanced pipeline ──────────
    // per frame: KenderRenderer begins a batch, each geo block appends ONE 19-float instance (kontra
    // transform x local offset), flush uploads per model. separate "|kontra" ids so the dirty-based
    // world-static path stays untouched; Rust marks them dynamic so the GPU cull never eats them.
    // render thread only — plain collections + reused temps are fine here.
    private static final Map<String, it.unimi.dsi.fastutil.floats.FloatArrayList> KONTRA_BATCH = new HashMap<>();
    private static final Map<String, net.minecraft.resources.Identifier> KONTRA_TEXID = new HashMap<>();
    private static final Map<String, Integer> KONTRA_MATERIAL = new HashMap<>();
    private static final java.util.Set<String> KONTRA_TEX_OK = new java.util.HashSet<>();
    private static final java.util.Set<String> KONTRA_LIVE = new java.util.HashSet<>();
    private static final Map<String, KodelBlockBook.Binding> KONTRA_SKIN_BIND = new HashMap<>();
    private static final Map<String, it.unimi.dsi.fastutil.floats.FloatArrayList> KONTRA_SKIN_BATCH = new HashMap<>();
    private static final Map<String, it.unimi.dsi.fastutil.floats.FloatArrayList> KONTRA_SKIN_BONES = new HashMap<>();
    private static final java.util.Set<String> KONTRA_SKIN_LIVE = new java.util.HashSet<>();
    private static final java.util.Set<String> KONTRA_SKIN_OK = new java.util.HashSet<>();
    private static final org.joml.Matrix4f KONTRA_MAT = new org.joml.Matrix4f();
    private static final float[] KONTRA_TMP = new float[19];

    public static void kontraBegin() {
        for (var l : KONTRA_BATCH.values()) l.clear(); // keep the lists, drop the data — no per-frame alloc churn
        for (var l : KONTRA_SKIN_BATCH.values()) l.clear();
    }

    // true = the GPU path owns this block (skip the CPU submit). false = let the CPU draw it
    // (not geo / animated / translucent / texture not on the Vulkan path yet).
    public static boolean kontraSubmit(Level level, BlockState bs, float kx, float ky, float kz,
                                       org.joml.Quaternionf rot, float ox, float oy, float oz, BlockPos worldBlockPos) {
        return kontraSubmit(level, KodelBlockBook.binding(bs.getBlock()), bs, kx, ky, kz, rot, ox, oy, oz,
            worldBlockPos, KenderFrame.packedLight(level, worldBlockPos));
    }

    // binding + light resolved by the caller — the render loop already needs both, and resolving
    // them again per block per frame was pure waste
    public static boolean kontraSubmit(Level level, KodelBlockBook.Binding b, BlockState bs, float kx, float ky, float kz,
                                       org.joml.Quaternionf rot, float ox, float oy, float oz,
                                       BlockPos worldBlockPos, int light) {
        if (b == null || !b.onKontra() || !KenderFrame.vulkanActive()) return false;
        // the GPU feeder uploads ONE mesh per model name, so a bone-filtered binding ("bones": [...])
        // would draw the whole file — a lift platform showing the foot and mast too. same rule the
        // static path already applies through Placed.gpuEligible: filtered bindings take the CPU submit
        if (!b.bones().isEmpty() || !b.boneWhen().isEmpty()) return false;
        boolean animated = !b.anim().isEmpty() || b.animation() != null || !b.clips().isEmpty();
        // instanced kontra anim shares ONE bone set per model — per-block trigger states can't ride
        // it, so triggered bindings take the CPU submit (counts are small, it's fine)
        if (animated && !b.triggers().isEmpty()) return false;

        String model = b.model();
        KONTRA_TEXID.putIfAbsent(model, b.texture());
        KONTRA_MATERIAL.putIfAbsent(model, b.renderKind());
        var l = (animated ? KONTRA_SKIN_BATCH : KONTRA_BATCH)
            .computeIfAbsent(model, k -> new it.unimi.dsi.fastutil.floats.FloatArrayList());
        if (animated) KONTRA_SKIN_BIND.putIfAbsent(model, b);
        // world = kontra origin (origin-relative) x rotation x block local. the CPU chain is
        // T(local-0.5) then T(0.5,0,0.5) inside submitGeoOnKontra -> combined T(ox, oy-0.5, oz)
        org.joml.Matrix4f m = KONTRA_MAT;
        m.translation((float) (kx - KenderFrame.renderOriginX()), (float) (ky - KenderFrame.renderOriginY()), (float) (kz - KenderFrame.renderOriginZ()))
         .rotate(rot)
         .translate(ox, oy - 0.5f, oz);
        applyFacing(m, b, bs);
        applyBase(m, b);
        float[] t = KONTRA_TMP;
        m.get(t, 0);
        t[16] = ((light >> 4)  & 0xF) / 15f;
        t[17] = ((light >> 20) & 0xF) / 15f;
        if (animated) {
            l.addElements(l.size(), t, 0, 18);
            l.add(0.0f); // shared bone set for this model/frame
            l.add(KenderFrame.tintEnc(b.tint()));
        } else {
            t[18] = KenderFrame.tintEnc(b.tint());
            l.addElements(l.size(), t, 0, 19);
        }
        // until the texture is on the Vulkan path the CPU keeps drawing (and thereby loads it);
        // the GPU draw skips texture-less models, so no double-draw either way
        return animated ? KONTRA_SKIN_OK.contains(model) : KONTRA_TEX_OK.contains(model);
    }

    public static boolean isGeoOnKontra(BlockState state) {
        KodelBlockBook.Binding bind = KodelBlockBook.binding(state.getBlock());
        return bind != null && bind.onKontra();
    }

    public static void kontraFlush() {
        for (Map.Entry<String, it.unimi.dsi.fastutil.floats.FloatArrayList> e : KONTRA_BATCH.entrySet()) {
            String model = e.getKey();
            var data = e.getValue();
            if (data.isEmpty()) continue;
            long id = MODEL_IDS.computeIfAbsent(model + "|kontra", k -> NEXT_MODEL_ID.getAndIncrement());
            if (UPLOADED.add(id)) {
                float[] mesh = staticMesh(model, null);
                if (mesh == null) { UPLOADED.remove(id); continue; }
                if (KenderBridge.geoUploadModel(id, mesh) != 0) {
                    UPLOADED.remove(id);
                    continue;
                }
                KenderBridge.geoSetDynamic(id); // moves every frame -> never GPU-culled
                KenderBridge.geoSetMaterial(id, KONTRA_MATERIAL.getOrDefault(model, 0));
            }
            if (!KONTRA_TEX_OK.contains(model)) {
                long texView = KenderVk.textureImageView(KodelTextures.of(model, KONTRA_TEXID.get(model)));
                if (texView != 0L) {
                    KenderBridge.geoSetTexture(id, texView);
                    KONTRA_TEX_OK.add(model);
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kodel-blocks] {} -> Vulkan (kontra feeder)", model);
                }
            }
            KenderBridge.geoSetInstances(id, data.elements(), data.size());
            com.koper.koper_lib.kender.KontraLagSniffer.uploaded(data.size());
            KONTRA_LIVE.add(model);
        }
        flushKontraSkinned();
        // models fed last frame but empty now (kontra gone / left range) -> clear or the ghost keeps drawing
        KONTRA_LIVE.removeIf(m -> {
            var data = KONTRA_BATCH.get(m);
            if (data != null && !data.isEmpty()) return false;
            Long id = MODEL_IDS.get(m + "|kontra");
            if (id != null) KenderBridge.geoSetInstances(id, null);
            return true;
        });
    }

    private static void flushKontraSkinned() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double now = clock(mc);
        for (var e : KONTRA_SKIN_BATCH.entrySet()) {
            String model = e.getKey();
            var data = e.getValue();
            if (data.isEmpty()) continue;
            KodelBlockBook.Binding bind = KONTRA_SKIN_BIND.get(model);
            KodelBook.Entry entry = KodelBook.get(model);
            if (bind == null || entry == null) { KONTRA_SKIN_OK.remove(model); continue; }

            long id = MODEL_IDS.computeIfAbsent(model + "|kontra|skin", k -> NEXT_MODEL_ID.getAndIncrement());
            if (!SKIN_UPLOADED.contains(id)) {
                float[] mesh = skinnedMesh(model);
                if (mesh == null || mesh.length == 0
                        || KenderBridge.geoUploadSkinned(id, mesh) != 0) {
                    KONTRA_SKIN_OK.remove(model);
                    continue;
                }
                SKIN_UPLOADED.add(id);
                KenderBridge.geoSetDynamic(id);
                KenderBridge.geoSetMaterial(id, bind.renderKind());
            }

            long texView = KenderVk.textureImageView(texture(bind));
            if (texView == 0L) { KONTRA_SKIN_OK.remove(model); continue; }
            KenderBridge.geoSetTexture(id, texView);

            var bones = KONTRA_SKIN_BONES.computeIfAbsent(model,
                k -> new it.unimi.dsi.fastutil.floats.FloatArrayList());
            bones.clear();
            // triggered bindings never reach this feeder (kontraSubmit bails them to CPU) — default clip only
            var pose = buildPoseScratch(bind, bind.animation(), now, now, java.util.Set.of(), null);
            appendBones(entry, pose, bones);
            if (bones.isEmpty()
                    || KenderBridge.geoSetBones(id, bones.elements(), bones.size()) != 0
                    || KenderBridge.geoSetInstancesSkinned(id, data.elements(), data.size()) != 0) {
                KONTRA_SKIN_OK.remove(model);
                continue;
            }
            if (KONTRA_SKIN_OK.add(model))
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kodel-blocks] {} -> Vulkan (animated kontra feeder)", model);
            KONTRA_SKIN_LIVE.add(model);
        }
        KONTRA_SKIN_LIVE.removeIf(model -> {
            var data = KONTRA_SKIN_BATCH.get(model);
            if (data != null && !data.isEmpty()) return false;
            Long id = MODEL_IDS.get(model + "|kontra|skin");
            if (id != null) KenderBridge.geoSetInstancesSkinned(id, null);
            return true;
        });
    }

    // upload models + set instances/textures for STATIC blocks. host-mapped writes only — safe anywhere.
    private static void prepInstances() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // texture-retry heartbeat (every 30 frames, not every frame)
        if (!TEX_WAIT.isEmpty() && KenderFrame.frameNo() % 30 == 0) DIRTY.addAll(TEX_WAIT);

        // light watchdog: a torch placed/broken NEXT TO a koperblock never dirties its model, so the baked
        // per-instance light went stale. every block re-checks its light once per 32 frames (staggered);
        // an actual change re-feeds just that model. the walk itself is field reads — light queries are 1/32.
        int watchSize = STATIC_WATCH.size();
        int watchChecks = (watchSize + 31) >>> 5;
        for (int i = 0; i < watchChecks && watchSize > 0; i++) {
            if (staticWatchCursor >= watchSize) staticWatchCursor = 0;
            Placed p = STATIC_WATCH.get(staticWatchCursor++);
            if (!p.gpuEligible || !p.mst.texReady) continue;
            int l = KenderFrame.packedLight(mc.level, p.pos);
            if (l != p.lightCache) { p.lightCache = l; DIRTY.add(p.bind.model()); }
        }

        boolean all = ALL_DIRTY;
        java.util.Set<String> dirty = null;
        if (all) { ALL_DIRTY = false; DIRTY.clear(); }
        else {
            if (DIRTY.isEmpty()) return;
            dirty = new java.util.HashSet<>(DIRTY);
            DIRTY.removeAll(dirty);
        }

        Map<String, List<Placed>> byModel = new HashMap<>();
        if (all) {
            byModel.putAll(STATIC_BY_MODEL);
        } else {
            for (String model : dirty) {
                List<Placed> blocks = STATIC_BY_MODEL.get(model);
                if (blocks != null) byModel.put(model, blocks);
            }
        }
        byModel.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().get(0).mst.cpuOnly);
        // a dirty model with zero remaining blocks must clear its GPU instances or ghosts keep drawing.
        // on a full rebuild (reload/origin) also zero the skinned sets — prepAnimated re-feeds the live ones.
        for (String m : (all ? MODEL_IDS.keySet() : dirty)) {
            if (byModel.containsKey(m)) continue;
            Long id = MODEL_IDS.get(m);
            if (id == null) continue;
            if (m.endsWith("|skin")) { if (all) KenderBridge.geoSetInstancesSkinned(id, null); }
            else if (UPLOADED.contains(id)) KenderBridge.geoSetInstances(id, null);
        }

        org.joml.Matrix4f im = new org.joml.Matrix4f();
        for (Map.Entry<String, List<Placed>> e : byModel.entrySet()) {
            String model = e.getKey();
            long id = MODEL_IDS.computeIfAbsent(model, k -> NEXT_MODEL_ID.getAndIncrement());
            List<Placed> blocks = e.getValue();
            if (UPLOADED.add(id)) {
                float[] mesh = staticMesh(model, null);
                if (mesh != null) {
                    KenderBridge.geoUploadModel(id, mesh);
                    KenderBridge.geoSetMaterial(id, blocks.get(0).bind.renderKind());
                }
            }
            // bind the model's texture (may not be loaded yet -> heartbeat retries until it is)
            MState st = state(model);
            long texView = KenderVk.textureImageView(texture(blocks.get(0).bind));
            if (texView != 0L) {
                KenderBridge.geoSetTexture(id, texView);
                TEX_WAIT.remove(model);
                if (LOG_TEX.add(model))
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kodel-blocks] {} -> Vulkan ({} instances, tex=0x{})",
                        model, blocks.size(), Long.toHexString(texView));
            } else {
                TEX_WAIT.add(model);
                st.texReady = false;
                if (LOG_NOTEX.add(model))
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kodel-blocks] {} texture '{}' NOT loaded -> staying on MC", model, blocks.get(0).bind.texture());
            }
            float[] inst = new float[blocks.size() * 19];
            int o = 0;
            for (Placed p : blocks) {
                instMatrix(im, p, p.bind);
                im.get(inst, o);
                int light = KenderFrame.packedLight(mc.level, p.pos);
                p.lightCache = light; // watchdog compares against what's actually baked
                inst[o+16] = ((light >> 4)  & 0xF) / 15f;
                inst[o+17] = ((light >> 20) & 0xF) / 15f;
                inst[o+18] = p.tintEnc;
                o += 19;
            }
            KenderBridge.geoSetInstances(id, inst);
            if (texView != 0L) st.texReady = true; // flip AFTER instances exist so MC never drops a block early
        }
    }

    // GPU skinning feeder — runs every frame for animated blocks. per UNIQUE anim state: one bone-tree
    // walk -> bone matrices into the SSBO. per block: 20 floats. no mesh bake, no per-block submit.
    // the same-state sharing means 100 synced kapokas = 1 walk + 100 cheap instances.
    private static void prepAnimated() {
        if (ANIM_BY_MODEL.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double timeSec = clock(mc);
        var frustum = KenderFrame.frustum();
        var camPos = mc.gameRenderer.mainCamera().position();
        float renderDist = (mc.options.getEffectiveRenderDistance() + 2) * 16f;
        double cullSq = (double) renderDist * renderDist;
        org.joml.Matrix4f im = new org.joml.Matrix4f();

        for (Map.Entry<String, List<Placed>> e : ANIM_BY_MODEL.entrySet()) {
            String model = e.getKey();
            MState st = state(model);
            if (SKIN_FALLBACK.contains(model)) { st.gpuAnim = false; continue; }
            List<Placed> list = e.getValue();
            if (list.isEmpty()) {
                // last block of this model got removed — clear the GPU instances or the ghost keeps drawing
                Long gone = MODEL_IDS.get(model + "|skin");
                if (gone != null) KenderBridge.geoSetInstancesSkinned(gone, null);
                st.gpuAnim = false;
                continue;
            }

            long id = MODEL_IDS.computeIfAbsent(model + "|skin", k -> NEXT_MODEL_ID.getAndIncrement());
            if (!SKIN_UPLOADED.contains(id)) {
                float[] mesh = skinnedMesh(model);
                if (mesh == null || mesh.length == 0
                        || KenderBridge.geoUploadSkinned(id, mesh) != 0) {
                    SKIN_FALLBACK.add(model); st.gpuAnim = false;
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kodel-blocks] {} skinned upload refused -> CPU anim", model);
                    continue;
                }
                SKIN_UPLOADED.add(id);
                KenderBridge.geoSetMaterial(id, list.get(0).bind.renderKind());
            }
            KodelBlockBook.Binding bind0 = list.get(0).bind;
            long texView = KenderVk.textureImageView(texture(bind0));
            if (texView == 0L) { st.gpuAnim = false; continue; } // MC keeps drawing — that's what loads the texture
            Long lastTex = LAST_TEX.get(id);
            if (lastTex == null || lastTex != texView) {
                KenderBridge.geoSetTexture(id, texView);
                LAST_TEX.put(id, texView);
            }

            KodelBook.Entry entry = KodelBook.get(model);
            if (entry == null) { SKIN_FALLBACK.add(model); st.gpuAnim = false; continue; }
            int boneCount = KodelModelRender.boneCount(entry.model());

            // pass 1: group blocks by anim state, fill instances. base = groupIdx * boneCount, so the
            // expensive bone walks can be skipped entirely when the state set matches last frame's upload
            Map<String, Integer> groupIdx = st.groupIdx;
            List<String> order = st.groupOrder;
            List<AnimArgs> groupArgs = st.groupArgs;
            groupIdx.clear(); order.clear(); groupArgs.clear();
            int needed = list.size() * 20;
            if (st.animInstances.length < needed)
                st.animInstances = new float[Math.max(needed, Math.max(64, st.animInstances.length * 2))];
            float[] inst = st.animInstances;
            int n = 0;
            for (Placed p : list) {
                if (!p.gpuEligible) continue;
                double ddx = p.pos.getX() + 0.5 - camPos.x, ddy = p.pos.getY() - camPos.y, ddz = p.pos.getZ() + 0.5 - camPos.z;
                if (ddx * ddx + ddy * ddy + ddz * ddz > cullSq) continue;
                if (frustum != null && !frustum.isVisible(p.cullBox)) continue; // CPU frustum for animated (counts are small)
                var act = triggers(p, mc.level);
                String target = selectClip(p.bind, act);
                double ct = clipTime(p, target, timeSec); // default clips share the clock -> whole model = ONE walk
                long frame = Math.round(ct * 60.0);
                // + the state numbers, same as the cpu bake cache. without them every suspension on
                // screen shared the first one's compression
                String key = target + '|' + frame + '|' + p.trigKey + stateKey(p.bind, p.state);
                Integer gi = groupIdx.get(key);
                if (gi == null) {
                    gi = order.size();
                    groupIdx.put(key, gi);
                    order.add(key);
                    groupArgs.add(new AnimArgs(p.bind, target, ct, act, p.state));
                }
                // light: cached + staggered refresh, NOT a light-engine query per block per frame
                if (p.lightCache < 0 || ((KenderFrame.frameNo() + (p.pos.hashCode() & 15)) & 15) == 0)
                    p.lightCache = KenderFrame.packedLight(mc.level, p.pos);
                int o = n * 20;
                instMatrix(im, p, p.bind);
                im.get(inst, o);
                inst[o+16] = ((p.lightCache >> 4)  & 0xF) / 15f;
                inst[o+17] = ((p.lightCache >> 20) & 0xF) / 15f;
                inst[o+18] = gi * boneCount;
                inst[o+19] = p.tintEnc; // the old pad float — tint rides here, stride unchanged
                n++;
            }
            if (n == 0) { KenderBridge.geoSetInstancesSkinned(id, null); st.gpuAnim = true; continue; }

            // pass 2: bone walks + SSBO upload — ONLY when the state set actually moved. a 60fps-bucketed
            // clip at 300fps = ~80% skips. procedural ops (spin/sway) use continuous time -> never skip.
            boolean procedural = !bind0.anim().isEmpty();
            if (procedural || !order.equals(BONE_ORDER.get(model))) {
                var bones = st.bones;
                bones.clear();
                for (AnimArgs ga : groupArgs) {
                    var pose = buildPoseScratch(ga.bind, ga.target, ga.clipTime, timeSec, ga.active, ga.state());
                    appendBones(entry, pose, bones);
                }
                if (KenderBridge.geoSetBones(id, bones.elements(), bones.size()) != 0) {
                    SKIN_FALLBACK.add(model); st.gpuAnim = false;
                    BONE_ORDER.remove(model);
                    continue;
                }
                BONE_ORDER.put(model, List.copyOf(order));
            }
            if (KenderBridge.geoSetInstancesSkinned(id, inst, n * 20) != 0) {
                SKIN_FALLBACK.add(model); st.gpuAnim = false;
                continue;
            }
            if (LOG_SKIN.add(model))
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kodel-blocks] {} -> GPU skinning ({} inst, {} anim states, {} bones)", model, n, order.size(), boneCount);
            st.gpuAnim = true;
        }
    }



    private static RenderType renderType(KodelBlockBook.Binding b) {
        return switch (b.renderKind()) {
            case 1 -> RenderTypes.entityTranslucent(texture(b));
            case 2 -> RenderTypes.entitySolid(texture(b));
            default -> RenderTypes.entityCutout(texture(b));
        };
    }

    // rotate_by: spin the model to the blockstate's facing (models are authored facing NORTH).
    // pivot = block center so wall/ceiling bearings sit right; yaw-only cases don't care about the lift
    private static void applyFacing(PoseStack ps, KodelBlockBook.Binding b, BlockState state) {
        // KoperStateOffset is already expressed in the final block-local axes. Apply it before the
        // authored-model facing transform, exactly like KodelPhysicsShapes does. Applying it after the
        // rotation rotates the mount offset a second time: UP looked fine by accident, while wall and
        // ceiling bearings rendered on the opposite side of their real hitbox.
        if (state.getBlock() instanceof com.koper.koper_lib.api.core.KoperStateOffset provider) {
            var offset = provider.koperStateOffset(state);
            ps.translate(offset.x, offset.y, offset.z);
        }
        org.joml.Quaternionf q = facingQuat(b, state);
        if (q != null) {
            ps.translate(0, 0.5f, 0);
            ps.rotate(q);
            ps.translate(0, -0.5f, 0);
        }
    }

    private static void applyFacing(org.joml.Matrix4f m, KodelBlockBook.Binding b, BlockState state) {
        if (state.getBlock() instanceof com.koper.koper_lib.api.core.KoperStateOffset provider) {
            var offset = provider.koperStateOffset(state);
            m.translate((float)offset.x, (float)offset.y, (float)offset.z);
        }
        org.joml.Quaternionf q = facingQuat(b, state);
        if (q != null) m.translate(0, 0.5f, 0).rotate(q).translate(0, -0.5f, 0);
    }

    private static org.joml.Quaternionf facingQuat(KodelBlockBook.Binding b, BlockState state) {
        return KodelBlockBook.facingQuat(b, state); // shared with the rotated-hitbox path — one source of truth
    }

    // data-driven base correction: scale + offset(px) + rotate(deg). lets you tune the model without touching the .geo
    private static void applyBaseTransform(PoseStack ps, KodelBlockBook.Binding b) {
        float[] off = b.offset(), rot = b.rotate();
        if (off[0] != 0 || off[1] != 0 || off[2] != 0) ps.translate(off[0] / 16f, off[1] / 16f, off[2] / 16f);
        float sc = b.scale();
        if (sc != 1f) ps.scale(sc, sc, sc);
        if (rot[2] != 0) ps.rotate(new org.joml.Quaternionf().rotationXYZ(0, 0, (float) Math.toRadians(rot[2])));
        if (rot[1] != 0) ps.rotate(new org.joml.Quaternionf().rotationXYZ(0, (float) Math.toRadians(rot[1]), 0));
        if (rot[0] != 0) ps.rotate(new org.joml.Quaternionf().rotationXYZ((float) Math.toRadians(rot[0]), 0, 0));
    }

    // builds the pose: bone name -> {rx,ry,rz, tx,ty,tz} (rot rad, trans blocks). null = static model
    // animation clip + procedural ops merged. a trigger gates an op / swaps the clip — "powered" reads
    // redstone, any other name reads a boolean blockstate property or a KodelTriggerBox code flip.
    // first ACTIVE trigger in the clips' JSON order wins; nothing active = the default animation
    // TODO more client-checkable triggers (day/night/weather) once the 26.2 time API is pinned down
    private static String selectClip(KodelBlockBook.Binding b, java.util.Set<String> active) {
        if (!active.isEmpty()) {
            for (var e : b.clips().entrySet())
                if (active.contains(e.getKey())) return e.getValue();
        }
        return b.animation();
    }

    /** A clip on its own timeline, plus the procedural ops (bone -> delta) on the shared clock. */
    private record Pose(String clip, double clipTime, Function<String, float[]> ops) {}

    // the ops map is a per-thread scratch: turn the pose into matrices or a mesh before the next block
    private static Pose buildPoseScratch(KodelBlockBook.Binding b, String clip, double clipTime,
                                         double t, java.util.Set<String> active, BlockState state) {
        if (b.anim().isEmpty() && clip == null) return null;
        Map<String, float[]> map = POSE_SCRATCH.get();
        zeroPose(map);
        fillPose(map, b, t, active, state);
        return new Pose(clip, clipTime, map.isEmpty() ? null : map::get);
    }

    private static float[] worldOf(KodelBook.Entry entry, Pose pose) {
        return pose == null ? entry.restPose() : KodelBlockPose.world(entry, pose.clip(), pose.clipTime(), pose.ops());
    }

    private static void appendBones(KodelBook.Entry entry, Pose pose, it.unimi.dsi.fastutil.floats.FloatArrayList out) {
        float[] matrices = KodelModelRender.boneMatricesForKender(worldOf(entry, pose), null);
        out.addElements(out.size(), matrices);
    }

    private static final Map<String, float[]> STATIC_MESH = new ConcurrentHashMap<>();
    private static final Map<String, float[]> SKIN_MESH = new ConcurrentHashMap<>();

    // rest-pose mesh in model space, stride 8, baked once per model (and bone selection)
    private static float[] staticMesh(String model, java.util.Set<String> visible) {
        String key = visible == null ? model : model + '|' + new java.util.TreeSet<>(visible);
        float[] cached = STATIC_MESH.get(key);
        if (cached != null) return cached;
        KodelBook.Entry entry = KodelBook.get(model);
        if (entry == null) return null;
        float[] mesh = KodelModelRender.bake(entry.model(), entry.restPose(), null, visible);
        STATIC_MESH.put(key, mesh);
        return mesh;
    }

    // stride 9 with a bone id per vertex, for GPU skinning
    private static float[] skinnedMesh(String model) {
        float[] cached = SKIN_MESH.get(model);
        if (cached != null) return cached;
        KodelBook.Entry entry = KodelBook.get(model);
        if (entry == null) return null;
        float[] mesh = KodelModelRender.bakeSkinned(entry.model());
        SKIN_MESH.put(model, mesh);
        return mesh;
    }

    private static net.minecraft.resources.Identifier texture(KodelBlockBook.Binding b) {
        return KodelTextures.of(b.model(), b.texture());
    }

    // a model-space mesh (stride 8: x y z u v nx ny nz) drawn through the pose it was submitted with
    private static void renderStatic(float[] mesh, PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc,
                                     int light, int overlay, int tint) {
        org.joml.Matrix4f m = pose.pose();
        org.joml.Vector3f p = new org.joml.Vector3f();
        org.joml.Vector3f n = new org.joml.Vector3f();
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            m.transformPosition(mesh[i], mesh[i + 1], mesh[i + 2], p);
            pose.transformNormal(mesh[i + 5], mesh[i + 6], mesh[i + 7], n);
            vc.addVertex(p.x, p.y, p.z, tint, mesh[i + 3], mesh[i + 4], overlay, light, n.x, n.y, n.z);
        }
    }

    private static void zeroPose(Map<String, float[]> map) {
        for (float[] v : map.values()) {
            if (v == null) continue;
            java.util.Arrays.fill(v, 0f); // scale slots too, or last frame's stretch sticks forever
        }
    }

    private static void fillPose(Map<String, float[]> map, KodelBlockBook.Binding b, double t,
                                 java.util.Set<String> active, BlockState state) {
        // procedural ops, additive on top of whatever the clip does to the bone
        for (KodelBlockBook.AnimOp op : b.anim()) {
            if (!op.when().isEmpty() && !active.contains(op.when())) continue;
            float[] v = map.computeIfAbsent(op.bone(), k -> new float[9]);
            if (v.length < 9) { // a clip got here first and sized it for rot+translation only
                v = java.util.Arrays.copyOf(v, 9);
                map.put(op.bone(), v);
            }
            float ph = (float) Math.toRadians(op.phase());
            switch (op.kind()) {
                case KodelBlockBook.SHIFT -> v[3 + op.axis()] += (op.amp() / 16f) * stateNumber(state, op.by());
                case KodelBlockBook.STRETCH -> v[6 + op.axis()] = 1f + op.amp() * stateNumber(state, op.by());
                // "by" makes the speed a blockstate number: a bearing plate turns as fast as its engine
                case KodelBlockBook.SPIN -> v[op.axis()] += (float) Math.toRadians((op.speed()
                        * (op.by() == null || op.by().isEmpty() ? 1f : stateNumber(state, op.by())) * t) % 360.0);
                case KodelBlockBook.HOLD -> v[op.axis()] += (float) Math.toRadians(op.amp());
                case KodelBlockBook.SWAY -> v[op.axis()] += (float) Math.toRadians(op.amp())
                        * (float) Math.sin(2 * Math.PI * op.speed() * t + ph);
                case KodelBlockBook.BOB  -> v[3 + op.axis()] += (op.amp() / 16f)
                        * (float) Math.sin(2 * Math.PI * op.speed() * t + ph);
            }
        }
    }

    private static java.util.Set<String> visibleOf(KodelBlockBook.Binding bind, BlockState state) {
        if (bind.bones().isEmpty()) return null;
        if (bind.boneWhen().isEmpty()) return bind.bones();
        java.util.Set<String> visible = new java.util.HashSet<>(bind.bones());
        for (var condition : bind.boneWhen().entrySet())
            if (!stateTrigger(state, condition.getValue())) visible.remove(condition.getKey());
        return visible;
    }

    // every state-driven number this binding poses with, so bake caches can tell two heights apart
    private static String stateKey(KodelBlockBook.Binding bind, BlockState state) {
        if (state == null) return "";
        StringBuilder out = null;
        for (KodelBlockBook.AnimOp op : bind.anim()) {
            if (op.by() == null || op.by().isEmpty()) continue;
            if (out == null) out = new StringBuilder();
            out.append('|').append(stateNumber(state, op.by()));
        }
        for (String property : new java.util.TreeSet<>(bind.boneWhen().values())) {
            if (out == null) out = new StringBuilder();
            out.append('|').append(property).append('=').append(stateTrigger(state, property));
        }
        return out == null ? "" : out.toString();
    }

    // the number behind "by": an int property as itself, a boolean as 0/1. missing = 0, so a block
    // that lost the property just renders its rest pose instead of exploding
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static float stateNumber(BlockState state, String property) {
        if (state == null || property == null || property.isEmpty()) return 0f;
        var prop = state.getBlock().getStateDefinition().getProperty(property);
        if (prop == null) return 0f;
        Object value = state.getValue((net.minecraft.world.level.block.state.properties.Property) prop);
        if (value instanceof Integer i) return i;
        if (value instanceof Boolean bool) return bool ? 1f : 0f;
        return 0f;
    }
}
