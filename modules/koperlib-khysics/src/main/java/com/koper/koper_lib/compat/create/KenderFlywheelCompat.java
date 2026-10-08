package com.koper.koper_lib.compat.create;

import com.koper.koper_lib.api.render.KenderRenderAPI;
import com.koper.koper_lib.config.KoperLibConfig;
import com.koper.koper_lib.kender.KenderBridge;

import com.koper.koper_lib.kender.KenderVk;
import com.zurrtum.create.client.content.kinetics.base.RotatingInstance;
import com.zurrtum.create.client.content.kinetics.base.RotatingPivotInstance;
import com.zurrtum.create.client.content.fluids.FluidInstance;
import com.zurrtum.create.client.content.processing.burner.ScrollInstance;
import com.zurrtum.create.client.flywheel.api.backend.BackendManager;
import com.zurrtum.create.client.flywheel.api.backend.Engine;
import com.zurrtum.create.client.flywheel.api.backend.RenderContext;
import com.zurrtum.create.client.flywheel.api.instance.Instance;
import com.zurrtum.create.client.flywheel.api.instance.InstanceHandle;
import com.zurrtum.create.client.flywheel.api.instance.InstanceType;
import com.zurrtum.create.client.flywheel.api.instance.Instancer;
import com.zurrtum.create.client.flywheel.api.instance.InstancerProvider;
import com.zurrtum.create.client.flywheel.api.material.Transparency;
import com.zurrtum.create.client.flywheel.api.model.Model;
import com.zurrtum.create.client.flywheel.api.task.Plan;
import com.zurrtum.create.client.flywheel.api.vertex.MutableVertexList;
import com.zurrtum.create.client.flywheel.api.visualization.VisualEmbedding;
import com.zurrtum.create.client.flywheel.api.visualization.VisualizationContext;
import com.zurrtum.create.client.flywheel.lib.backend.SimpleBackend;
import com.zurrtum.create.client.flywheel.lib.instance.ColoredLitInstance;
import com.zurrtum.create.client.flywheel.lib.instance.OrientedInstance;
import com.zurrtum.create.client.flywheel.lib.instance.PosedInstance;
import com.zurrtum.create.client.flywheel.lib.instance.ShadowInstance;
import com.zurrtum.create.client.flywheel.lib.instance.TransformedInstance;
import com.zurrtum.create.client.flywheel.lib.task.RunnablePlan;
import com.zurrtum.create.client.flywheel.lib.task.functional.RunnableWithContext;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Create Fly 6.0.9 -> Kender adapter. No GL calls; instances land in Kender's open Vulkan pass. */
public final class KenderFlywheelCompat {
    private static final Identifier BACKEND_ID = Identifier.fromNamespaceAndPath("koper_lib", "kender");
    private static boolean registered;
    private static boolean flywheelWorkersUsed;
    // set from the device-shared hook, acted on next client tick — running it inline would re-enter
    // Flywheel's backend selection from inside isSupported()
    private static volatile boolean claimPending;

    // frame profile. rolling, /cr drains it — no frame-boundary hook needed and no cost when unread.
    private static final java.util.concurrent.atomic.AtomicLong SYNC_NANOS = new java.util.concurrent.atomic.AtomicLong();
    private static final AtomicInteger SYNC_FRAMES = new AtomicInteger();
    private static final AtomicInteger LAST_INSTANCERS = new AtomicInteger();
    private static final AtomicInteger LAST_REBUILT = new AtomicInteger();
    private static final AtomicInteger LAST_WRITTEN = new AtomicInteger();
    private static final AtomicInteger LAST_LIVE = new AtomicInteger();
    private static final AtomicInteger LAST_KINETIC = new AtomicInteger();

    private KenderFlywheelCompat() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        SimpleBackend.builder()
            .engineFactory(KenderEngine::new)
            // Create starts fallback search *after* its configured indirect backend.
            // Keep Kender directly behind it or forced mode gets skipped straight to off.
            .priority(() -> 900)
            .supported(KenderFlywheelCompat::supported)
            .register(BACKEND_ID);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopFlywheelWorkers());
        // Kender is meant to REPLACE Flywheel on Vulkan by itself. It couldn't: Flywheel picks its
        // backend at level load, before MC's main render target exists, so deviceShared() is still
        // false, Kender reports unsupported and Flywheel settles on off forever. Ask again the moment
        // the device actually lands.
        KenderVk.onDeviceShared(() -> claimPending = true);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (!claimPending) return;
            claimPending = false;
            claimBackend();
        });
        registerCommand();
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender/Create] Flywheel backend registered as {}", BACKEND_ID);
    }

    public static String status() {
        String current;
        try {
            var backend = BackendManager.currentBackend();
            current = String.valueOf(com.zurrtum.create.client.flywheel.api.backend.Backend.REGISTRY.getId(backend));
        } catch (Throwable t) { current = "?"; }
        var api = KenderRenderAPI.stats();
        int frames = Math.max(1, SYNC_FRAMES.getAndSet(0));
        double syncMs = SYNC_NANOS.getAndSet(0) / 1_000_000.0 / frames;
        String profile = String.format(
            " | sync=%.2fms/f instancers=%d rebuilt=%d/f wrote=%d/f live=%d kinetic=%d spinpipe=%b | %s",
            syncMs, LAST_INSTANCERS.get(), LAST_REBUILT.get(), LAST_WRITTEN.get(),
            LAST_LIVE.get(), LAST_KINETIC.get(), KenderBridge.geoSpinAvailable(),
            KenderRenderAPI.drainProfile());
        long cs = KenderBridge.geoCullStats();
        profile += String.format(" | cull: %d model(s) past 512, biggest=%d",
            (int) (cs >>> 32), (int) (cs & 0xFFFFFFFFL));
        String alarm = current.contains("off")
            ? "\n!! Flywheel backend is OFF -> Create renders on vanilla BE path, Kender draws nothing. run /cr kender\n"
            : "";
        return alarm + "mode=" + mode().name().toLowerCase() + " current=" + current
            + " vk=" + api.vulkan() + " meshes=" + api.uploadedMeshes() + "/" + api.meshes()
            + " instances=" + api.instances() + profile
            + " kfx=" + com.koper.koper_lib.kender.KenderBridge.kfxCount()
            + "+" + com.koper.koper_lib.kender.KenderBridge.emitterCount()
            + " gpu=" + com.koper.koper_lib.api.core.KenderEffectsBridge.gpuDrew()
            + "\nentities: " + com.koper.koper_lib.kender.KenderEntityBatch.stats();
    }

    /** Vulkan is up and Kender owns the Create path now — take the backend if nothing forbids it. */
    private static void claimBackend() {
        Mode mode = mode();
        if (mode == Mode.OFF || mode == Mode.FLYWHEEL) return;   // user said no, don't argue
        if (!KenderVk.deviceShared()) return;
        try {
            var cfg = com.zurrtum.create.client.flywheel.impl.FabricFlwConfig.INSTANCE;
            if (cfg.client == null) return;
            String cur = cfg.client.backend.get();
            // 'off' is always "supported", so chooseBackend takes it and never reaches the registry.
            // nothing short of rewriting it lets Kender in.
            boolean blocked = cur != null && (cur.equalsIgnoreCase("OFF") || cur.toLowerCase().endsWith(":off"));
            if (blocked) {
                String want = mode == Mode.KENDER ? BACKEND_ID.toString() : "DEFAULT";
                cfg.client.backend.set(want);
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender/Create] flywheel backend was '{}' and Vulkan is up — claiming it as '{}'",
                    cur, want);
            } else if (BACKEND_ID.toString().equals(cur) || BackendManager.isBackendOn()) {
                return; // already ours or already running something real
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null)
                com.zurrtum.create.client.flywheel.impl.BackendManagerImpl.onReloadLevelRenderer(mc.level);
        } catch (Throwable t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kender/Create] backend claim failed — set 'backend' in flywheel-client.json by hand", t);
        }
    }

    private static boolean supported() {
        Mode mode = mode();
        if (mode == Mode.OFF || mode == Mode.FLYWHEEL) return false;
        KenderVk.tryInit();
        return mode == Mode.KENDER ? KenderVk.deviceShared() : KenderVk.onVulkan() && KenderVk.deviceShared();
    }

    private static Mode mode() {
        String raw = com.koper.koper_lib.physics.KhysicsConfig.get().createRender;
        if (raw == null) return Mode.AUTO;
        try { return Mode.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return Mode.AUTO; }
    }

    private static void setMode(Mode mode) {
        com.koper.koper_lib.physics.KhysicsConfig.get().createRender = mode.name().toLowerCase();
        com.koper.koper_lib.physics.KhysicsConfig.save();
        // Flywheel's own config beats anything we register: BackendManagerImpl reads it first and, on
        // 'flywheel:off', never walks the registry at all. A stale off in flywheel-client.json left
        // Create on vanilla BE rendering with Kender sitting there doing nothing, silently.
        try {
            var cfg = com.zurrtum.create.client.flywheel.impl.FabricFlwConfig.INSTANCE;
            if (cfg.client != null) cfg.client.backend.set(switch (mode) {
                case KENDER -> BACKEND_ID.toString();
                case OFF -> "OFF";
                default -> "DEFAULT";
            });
        } catch (Throwable t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kender/Create] could not steer Flywheel's backend config — "
                + "set 'backend' in flywheel-client.json by hand", t);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null)
            com.zurrtum.create.client.flywheel.impl.BackendManagerImpl.onReloadLevelRenderer(mc.level);
    }

    private static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var root = ClientCommands.literal("cr")
                .executes(ctx -> feedback(ctx.getSource(), status()));
            for (Mode mode : Mode.values()) {
                root.then(ClientCommands.literal(mode.name().toLowerCase()).executes(ctx -> {
                    setMode(mode);
                    return feedback(ctx.getSource(), "set " + mode.name().toLowerCase() + "; renderer reload requested");
                }));
            }
            dispatcher.register(root);
        });
    }

    private static int feedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String text) {
        source.sendFeedback(Component.literal("[Create/Kender] ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
        return 1;
    }

    private enum Mode { AUTO, KENDER, FLYWHEEL, OFF }

    private static void stopFlywheelWorkers() {
        if (!flywheelWorkersUsed) return;
        try {
            var executor = com.zurrtum.create.client.flywheel.impl.task.FlwTaskExecutor.get();
            if (executor instanceof com.zurrtum.create.client.flywheel.impl.task.ParallelTaskExecutor parallel)
                parallel.stopWorkers();
        } catch (Throwable t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kender/Create] could not stop Flywheel workers cleanly", t);
        }
    }

    private static final class KenderEngine implements Engine {
        private final LevelAccessor level;
        private final List<KInstancer<?>> instancers = new ArrayList<>();
        private final RootContext root = new RootContext(this, null, null);
        private BlockPos origin = BlockPos.ZERO;
        private boolean deleted;

        KenderEngine(LevelAccessor level) {
            this.level = level;
            flywheelWorkersUsed = true;
        }

        @Override public VisualizationContext createVisualizationContext() { return root; }

        @Override public Plan<RenderContext> createFramePlan() {
            return RunnablePlan.of((RunnableWithContext<RenderContext>)ctx -> syncAll());
        }

        @Override public Vec3i renderOrigin() { return origin; }

        @Override public boolean updateRenderOrigin(CameraRenderState camera) {
            BlockPos cameraBlock = BlockPos.containing(camera.pos);
            BlockPos next = new BlockPos(
                Math.floorDiv(cameraBlock.getX(), 256) * 256,
                Math.floorDiv(cameraBlock.getY(), 256) * 256,
                Math.floorDiv(cameraBlock.getZ(), 256) * 256);
            if (next.equals(origin)) return false;
            origin = next;
            return true;
        }

        @Override public void lightSections(LongSet sections) {}
        @Override public void onLightUpdate(SectionPos pos, LightLayer layer) {}
        // createFramePlan already snapshots every instance; actual Vulkan draw happens later in Kender's world pass
        @Override public void render(RenderContext context) {}
        @Override public void renderCrumbling(RenderContext context) {}

        @Override public void delete() {
            deleted = true;
            for (KInstancer<?> instancer : instancers) instancer.delete();
            instancers.clear();
        }

        private void syncAll() {
            if (deleted) return;
            long t0 = System.nanoTime();
            int rebuilt = 0, written = 0, live = 0, kinetic = 0;
            for (int i = 0; i < instancers.size(); i++) {
                KInstancer<?> in = instancers.get(i);
                int w = in.sync();
                live += in.instances.size();
                if (Boolean.TRUE.equals(in.kinetic)) kinetic++;
                if (w >= 0) { rebuilt++; written += w; }
            }
            SYNC_NANOS.addAndGet(System.nanoTime() - t0);
            SYNC_FRAMES.incrementAndGet();
            LAST_INSTANCERS.set(instancers.size());
            LAST_REBUILT.set(rebuilt);
            LAST_WRITTEN.set(written);
            LAST_LIVE.set(live);
            LAST_KINETIC.set(kinetic);
        }
    }

    private static class RootContext implements VisualizationContext {
        final KenderEngine engine;
        final RootContext parent;
        final Vec3i fixedOrigin;
        final Matrix4f pose = new Matrix4f();
        final InstancerProvider provider;

        RootContext(KenderEngine engine, RootContext parent, Vec3i fixedOrigin) {
            this.engine = engine;
            this.parent = parent;
            this.fixedOrigin = fixedOrigin;
            this.provider = new KProvider(this);
        }

        @Override public InstancerProvider instancerProvider() { return provider; }
        @Override public Vec3i renderOrigin() { return fixedOrigin != null ? fixedOrigin : engine.origin; }
        @Override public VisualEmbedding createEmbedding(Vec3i pos) {
            return new KEmbedding(engine, this, pos);
        }
        Matrix4f composed(Matrix4f out) {
            if (parent == null) return out.set(pose);
            return parent.composed(out).mul(pose);
        }
        boolean active() { return parent == null || parent.active(); }
    }

    private static final class KEmbedding extends RootContext implements VisualEmbedding {
        private boolean deleted;
        KEmbedding(KenderEngine engine, RootContext parent, Vec3i origin) { super(engine, parent, origin); }
        @Override public void transforms(Matrix4fc pose, Matrix3fc normal) { if (!deleted) this.pose.set(pose); }
        @Override public void delete() { deleted = true; }
        @Override boolean active() { return !deleted && super.active(); }
    }

    private static final class KProvider implements InstancerProvider {
        private final RootContext context;
        private final List<KInstancer<?>> batches = new ArrayList<>();
        KProvider(RootContext context) { this.context = context; }

        @Override
        @SuppressWarnings("unchecked")
        public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
            for (KInstancer<?> batch : batches)
                if (batch.type == type && batch.model == model && batch.bias == bias)
                    return (Instancer<I>)batch;
            KInstancer<I> out = new KInstancer<>(context, type, model, bias);
            batches.add(out);
            context.engine.instancers.add(out);
            return out;
        }
    }

    private static final class KInstancer<I extends Instance> implements Instancer<I> {
        private static final AtomicInteger PART_IDS = new AtomicInteger();
        private final RootContext context;
        private final InstanceType<I> type;
        private final Model model;
        private final int bias;
        private final List<I> instances = new ArrayList<>();
        private final List<KenderRenderAPI.Mesh> parts = new ArrayList<>();
        private final Matrix4f tmp = new Matrix4f();
        private final Matrix4f worldTmp = new Matrix4f();
        private final Matrix4f embeddingTmp = new Matrix4f();
        private final Matrix4f modelTmp = new Matrix4f();
        private boolean tracedTransform;
        // rebuild gating. Flywheel tells us what moved via setChanged(); we used to throw that away and
        // rewrite every instance of every part every frame, which is where a spammed Create base died.
        private final Matrix4f lastComposed = new Matrix4f();
        private Vec3i lastOrigin;
        private boolean dirty = true;
        private boolean pendingRemoval;
        private boolean cleared;
        private Boolean animated;   // null until the first instance tells us the type
        private Boolean kinetic;    // rotating type riding the GPU spin shader
        private Boolean shadow;     // flywheel entity shadow, own shader
        private float[] shared = new float[0];

        void markDirty() { dirty = true; }

        KInstancer(RootContext context, InstanceType<I> type, Model model, int bias) {
            this.context = context;
            this.type = type;
            this.model = model;
            this.bias = bias;
            if (KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender/Create] instancer model={} meshes={} type={}",
                    model, model.meshes().size(), type.getClass().getSimpleName());
            for (Model.ConfiguredMesh configured : model.meshes()) {
                float[] verts = bake(configured.mesh());
                if (KoperLibConfig.get().debugMode)
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender/Create]   mesh verts={} tex={} transparency={}",
                        verts.length / KenderRenderAPI.VERTEX_FLOATS, configured.material().texture(),
                        configured.material().transparency());
                if (verts.length == 0) continue;
                Identifier id = Identifier.fromNamespaceAndPath("koper_lib", "create/part_" + PART_IDS.incrementAndGet());
                Transparency transparency = configured.material().transparency();
                KenderRenderAPI.Material material = transparency == Transparency.OPAQUE
                    ? KenderRenderAPI.Material.SOLID : KenderRenderAPI.Material.TRANSLUCENT;
                // STATIC, not DYNAMIC: DYNAMIC exists to opt a model OUT of the GPU cull, because the
                // cull used to be recorded before these instances were written and would compact stale
                // transforms. prepassCull fixed that ordering, so Create geometry can be culled now.
                parts.add(KenderRenderAPI.mesh(id, verts, configured.material().texture(), material,
                    KenderRenderAPI.Usage.STATIC));
            }
        }

        @Override public I createInstance() {
            KHandle handle = new KHandle(this);
            I instance = type.create(handle);
            handle.instance = instance;
            instances.add(instance);
            markDirty();
            return instance;
        }

        @Override public void stealInstance(I instance) {
            if (instance == null || !(instance.handle() instanceof KHandle handle)) return;
            if (handle.owner != null) { handle.owner.instances.remove(instance); handle.owner.markDirty(); }
            handle.owner = this;
            instances.add(instance);
            markDirty();
        }

        int sync() {
            // removeIf scans the whole list; only pay it on a frame where something actually died
            if (pendingRemoval) {
                instances.removeIf(i -> i.handle() instanceof KHandle h && h.deleted);
                pendingRemoval = false;
                dirty = true;
            }
            if (!context.active()) {
                if (!cleared) {
                    for (int p = 0; p < parts.size(); p++) parts.get(p).clearInstances();
                    cleared = true;
                }
                return -1;
            }
            if (cleared) { cleared = false; dirty = true; }

            // the embedding pose (moving contraption) and the render origin shift without any instance
            // ever calling setChanged, so diff them ourselves — 16 floats beats rebuilding everything
            Vec3i origin = context.engine.origin;
            Matrix4f composed = context.composed(embeddingTmp);
            if (!origin.equals(lastOrigin) || !composed.equals(lastComposed)) {
                lastOrigin = origin;
                lastComposed.set(composed);
                dirty = true;
            }

            int n = instances.size();
            if (kinetic == null && n > 0) {
                // kinetic instances spin off pc.time in the vertex shader, so their data is STATIC and
                // they stop counting as self-animating — that is the whole win here.
                //
                // DO NOT latch false just because the pipeline isn't up yet. Create builds its visuals as
                // chunks load, which can easily beat kender_vk_init, and latching there killed the spin
                // path for the entire session — every cogwheel silently fell back to the CPU.
                Instance first = instances.get(0);
                if (first instanceof ShadowInstance) {
                    kinetic = false;
                    shadow = true;
                    animated = true;   // follows its entity, no setChanged to rely on
                    for (int p = 0; p < parts.size(); p++) parts.get(p).markShadow();
                    dirty = true;
                } else if (!(first instanceof RotatingInstance)) {
                    kinetic = false;
                    animated = selfAnimating(first);
                } else if (KenderBridge.geoSpinAvailable()) {
                    kinetic = true;
                    animated = false;
                    for (int p = 0; p < parts.size(); p++) parts.get(p).markKinetic();
                    dirty = true;
                } else {
                    animated = true;   // can't answer yet — rebuild on CPU and ask again next frame
                }
            }
            boolean spin = Boolean.TRUE.equals(kinetic);
            boolean shadowed = Boolean.TRUE.equals(shadow);
            // nothing moved and nothing animates on its own clock -> GPU buffer is still correct
            if (!dirty && !Boolean.TRUE.equals(animated)) return -1;
            dirty = false;

            int fl = shadowed ? KenderRenderAPI.SHADOW_INSTANCE_FLOATS
                : spin ? KenderRenderAPI.SPIN_INSTANCE_FLOATS : KenderRenderAPI.INSTANCE_FLOATS;
            int need = n * fl;
            if (shared.length < need) shared = new float[Math.max(need, 64)];

            // origin + embedding are constant across the whole instancer this frame — fold them once
            worldTmp.translation(origin.getX(), origin.getY(), origin.getZ()).mul(composed);

            boolean trace = !tracedTransform && KoperLibConfig.get().debugMode;
            int at = 0;
            for (int idx = 0; idx < n; idx++) {
                I instance = instances.get(idx);
                if (instance.handle() instanceof KHandle h && !h.visible) continue;
                if (shadowed) {
                    if (!fillShadow(instance, shared, at)) continue;
                    at += fl;
                    continue;   // shadows carry no light or tint
                } else if (spin) {
                    if (!fillKinetic(instance, shared, at)) continue;
                } else {
                    Matrix4f local = transform(instance, tmp.identity());
                    if (local == null) continue;
                    Matrix4f model = modelTmp.set(worldTmp).mul(local);
                    if (trace) {
                        trace = false;
                        tracedTransform = true;
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender/Create] {} origin={},{},{} embedding={},{},{} local={},{},{} world={},{},{}",
                            instance.getClass().getSimpleName(), origin.getX(), origin.getY(), origin.getZ(),
                            embeddingTmp.m30(), embeddingTmp.m31(), embeddingTmp.m32(),
                            tmp.m30(), tmp.m31(), tmp.m32(), model.m30(), model.m31(), model.m32());
                    }
                    model.get(shared, at);
                }
                int light = instance instanceof ColoredLitInstance lit ? lit.light : 0x00F000F0;
                // flywheel's relight never runs on this engine — lightSections/onLightUpdate below are
                // stubs, so a visual nobody lit keeps light=0 and draws PITCH BLACK. that's the black
                // blaze burner head in the world (on a kontra the vanilla BE renderer draws it instead,
                // with its own light, which is why it looked fine there). sample the world ourselves.
                if (light == 0) light = worldLight(shared, at);
                int tint = instance instanceof ColoredLitInstance lit
                    ? 0xFF000000 | Byte.toUnsignedInt(lit.red) << 16 | Byte.toUnsignedInt(lit.green) << 8 | Byte.toUnsignedInt(lit.blue)
                    : 0xFFFFFFFF;
                shared[at + 16] = clamp15((light >> 4) & 15);
                shared[at + 17] = clamp15((light >> 20) & 15);
                shared[at + 18] = com.koper.koper_lib.kender.KenderFrame.tintEnc(tint);
                at += fl;
            }

            // every part of the model shares these transforms — computing them per part was the K-times waste
            int count = at / fl;
            for (int p = 0; p < parts.size(); p++) parts.get(p).setInstances(shared, count);
            return count;
        }

        // the matrix we just wrote is column major, so its translation sits at 12/13/14 — that IS the
        // instance's world spot (render origin is already folded into worldTmp)
        private static int worldLight(float[] buf, int at) {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return 0x00F000F0;
            return com.koper.koper_lib.kender.KenderFrame.packedLight(mc.level,
                BlockPos.containing(buf[at + 12], buf[at + 13], buf[at + 14]));
        }

        // static half of the kinetic chain. the CPU used to bake the angle in here every frame:
        //   T(c)·T(piv)·R(spin)·T(-piv)·R(rot)·T(-0.5)
        // only R(spin) depends on time, so everything else goes up once and the shader adds the spin.
        private boolean fillKinetic(I instance, float[] out, int at) {
            if (!(instance instanceof RotatingInstance r)) return false;
            modelTmp.set(worldTmp).translate(r.x + 0.5f, r.y + 0.5f, r.z + 0.5f);
            modelTmp.get(out, at);
            out[at + 19] = r.rotation.x();
            out[at + 20] = r.rotation.y();
            out[at + 21] = r.rotation.z();
            out[at + 22] = r.rotation.w();
            out[at + 23] = r.rotationAxisX / 127f;
            out[at + 24] = r.rotationAxisY / 127f;
            out[at + 25] = r.rotationAxisZ / 127f;
            out[at + 26] = r.rotationalSpeed;
            if (r instanceof RotatingPivotInstance p) {
                out[at + 27] = p.pivotX; out[at + 28] = p.pivotY; out[at + 29] = p.pivotZ;
            } else {
                out[at + 27] = 0f; out[at + 28] = 0f; out[at + 29] = 0f;
            }
            out[at + 30] = r.rotationOffset;
            out[at + 31] = 0f;
            return true;
        }

        // flywheel shadow.vert: pos scaled by size then translated, UV from the fragment's distance to
        // the entity. entityXZ goes in RELATIVE to the quad so a render-origin shift can't slide it.
        private boolean fillShadow(I instance, float[] out, int at) {
            if (!(instance instanceof ShadowInstance sh)) return false;
            modelTmp.set(worldTmp)
                .translate(sh.x, sh.y, sh.z)
                .scale(sh.sizeX, 1f, sh.sizeZ);
            modelTmp.get(out, at);
            out[at + 16] = 0f;
            out[at + 17] = 0f;
            out[at + 18] = sh.alpha;
            out[at + 19] = sh.entityX - sh.x;
            out[at + 20] = sh.entityZ - sh.z;
            out[at + 21] = sh.radius;
            out[at + 22] = 0f;
            out[at + 23] = 0f;
            return true;
        }

        private static float clamp15(int level) { return Math.max(0, Math.min(15, level)) / 15f; }

        void delete() {
            for (KenderRenderAPI.Mesh part : parts) part.close();
            parts.clear();
            instances.clear();
        }
    }

    private static final class KHandle implements InstanceHandle {
        KInstancer<?> owner;
        Instance instance;
        boolean visible = true;
        boolean deleted;
        KHandle(KInstancer<?> owner) { this.owner = owner; }
        // this used to be empty — Flywheel was telling us exactly what moved and we ignored it
        @Override public void setChanged() { if (owner != null) owner.markDirty(); }
        @Override public void setDeleted() {
            deleted = true;
            if (owner != null) { owner.pendingRemoval = true; owner.markDirty(); }
        }
        @Override public void setVisible(boolean visible) {
            if (this.visible == visible) return;
            this.visible = visible;
            if (owner != null) owner.markDirty();
        }
        @Override public boolean isVisible() { return visible && !deleted; }
    }

    // types whose transform moves on its own clock, so no setChanged ever fires for them.
    // Flywheel animates these in its own vertex shaders; until Kender does the same they cost a
    // CPU rebuild every frame.
    private static boolean selfAnimating(Instance i) {
        return i instanceof RotatingInstance || i instanceof FluidInstance || i instanceof ScrollInstance;
    }

    private static Matrix4f transform(Instance instance, Matrix4f out) {
        if (instance instanceof FluidInstance fluid)
            return out.set(fluid.pose).scale(1f, fluid.progress, 1f);
        if (instance instanceof TransformedInstance transformed) return out.set(transformed.pose);
        if (instance instanceof PosedInstance posed) return out.set(posed.pose);
        if (instance instanceof OrientedInstance oriented) {
            return out.translation(oriented.posX, oriented.posY, oriented.posZ)
                .translate(oriented.pivotX, oriented.pivotY, oriented.pivotZ)
                .rotate(oriented.rotation)
                .translate(-oriented.pivotX, -oriented.pivotY, -oriented.pivotZ);
        }
        if (instance instanceof RotatingInstance rotating) {
            float seconds = com.koper.koper_lib.api.core.KenderEffectsBridge.nowTicks() / 20f;
            float angle = (float)Math.toRadians(rotating.rotationOffset + rotating.rotationalSpeed * seconds);
            float ax = rotating.rotationAxisX / 127f, ay = rotating.rotationAxisY / 127f, az = rotating.rotationAxisZ / 127f;
            out.translation(rotating.x + 0.5f, rotating.y + 0.5f, rotating.z + 0.5f);
            if (rotating instanceof RotatingPivotInstance pivot)
                out.translate(pivot.pivotX, pivot.pivotY, pivot.pivotZ)
                    .rotate(angle, ax, ay, az)
                    .translate(-pivot.pivotX, -pivot.pivotY, -pivot.pivotZ);
            else out.rotate(angle, ax, ay, az);
            return out.rotate(rotating.rotation).translate(-0.5f, -0.5f, -0.5f);
        }
        if (instance instanceof ScrollInstance scroll)
            return out.translation(scroll.x + 0.5f, scroll.y + 0.5f, scroll.z + 0.5f)
                .rotate(scroll.rotation).translate(-0.5f, -0.5f, -0.5f);
        // silent null = invisible machine part. name the class so we know what to teach transform()
        if (KoperLibConfig.get().debugMode && UNKNOWN_INSTANCES.add(instance.getClass().getName()))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kender/Create] unhandled instance type {}", instance.getClass().getName());
        return null;
    }

    private static final java.util.Set<String> UNKNOWN_INSTANCES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static float[] bake(com.zurrtum.create.client.flywheel.api.model.Mesh mesh) {
        VertexSink sink = new VertexSink(mesh.vertexCount());
        mesh.write(sink);
        int indexCount = mesh.indexCount();
        if (indexCount < 3) return new float[0];
        long ptr = MemoryUtil.nmemAlloc((long)indexCount * Integer.BYTES);
        try {
            mesh.indexSequence().fill(ptr, indexCount);
            int tris = indexCount / 3;
            float[] out = new float[tris * 4 * KenderRenderAPI.VERTEX_FLOATS];
            int dst = 0;
            for (int tri = 0; tri < tris; tri++) {
                int a = MemoryUtil.memGetInt(ptr + (long)(tri * 3) * 4);
                int b = MemoryUtil.memGetInt(ptr + (long)(tri * 3 + 1) * 4);
                int c = MemoryUtil.memGetInt(ptr + (long)(tri * 3 + 2) * 4);
                dst = sink.copy(a, out, dst);
                dst = sink.copy(b, out, dst);
                dst = sink.copy(c, out, dst);
                dst = sink.copy(c, out, dst); // triangle -> degenerate quad, matches Kender's quad indexer
            }
            return out;
        } finally { MemoryUtil.nmemFree(ptr); }
    }

    private static final class VertexSink implements MutableVertexList {
        final float[] data;
        VertexSink(int count) { data = new float[count * KenderRenderAPI.VERTEX_FLOATS]; }
        int at(int i) { return i * KenderRenderAPI.VERTEX_FLOATS; }
        int copy(int i, float[] out, int dst) {
            if (i < 0 || at(i) + 7 >= data.length) return dst;
            System.arraycopy(data, at(i), out, dst, KenderRenderAPI.VERTEX_FLOATS);
            return dst + KenderRenderAPI.VERTEX_FLOATS;
        }
        @Override public int vertexCount() { return data.length / KenderRenderAPI.VERTEX_FLOATS; }
        @Override public float x(int i) { return data[at(i)]; }
        @Override public float y(int i) { return data[at(i)+1]; }
        @Override public float z(int i) { return data[at(i)+2]; }
        @Override public float u(int i) { return data[at(i)+3]; }
        @Override public float v(int i) { return data[at(i)+4]; }
        @Override public float normalX(int i) { return data[at(i)+5]; }
        @Override public float normalY(int i) { return data[at(i)+6]; }
        @Override public float normalZ(int i) { return data[at(i)+7]; }
        @Override public void x(int i,float v){data[at(i)]=v;} @Override public void y(int i,float v){data[at(i)+1]=v;}
        @Override public void z(int i,float v){data[at(i)+2]=v;} @Override public void u(int i,float v){data[at(i)+3]=v;}
        @Override public void v(int i,float v){data[at(i)+4]=v;} @Override public void normalX(int i,float v){data[at(i)+5]=v;}
        @Override public void normalY(int i,float v){data[at(i)+6]=v;} @Override public void normalZ(int i,float v){data[at(i)+7]=v;}
        @Override public float r(int i){return 1;} @Override public float g(int i){return 1;} @Override public float b(int i){return 1;}
        @Override public float a(int i){return 1;} @Override public int overlay(int i){return 0;} @Override public int light(int i){return 0;}
        @Override public void r(int i,float v){} @Override public void g(int i,float v){} @Override public void b(int i,float v){}
        @Override public void a(int i,float v){} @Override public void overlay(int i,int v){} @Override public void light(int i,int v){}
    }
}
