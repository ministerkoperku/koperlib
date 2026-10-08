package com.koper.koper_lib.kender;

import com.koper.koper_lib.config.KoperLibConfig;
import com.koper.koper_lib.mixin.LevelRendererSubmitAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

// one body draw per model+texture instead of one MC submit per entity. armor/items/nameplates stay as layers.
public final class KenderEntityBatch {
    /** Floats per vertex of a skinned mesh: position, uv, normal, bone. Kodel bakes in this layout. */
    public static final int SKIN_STRIDE = 9;
    public static final int OPAQUE_PASS = 1;
    public static final int TRANSLUCENT_PASS = 2;

    @FunctionalInterface
    public interface BoneWriter { void append(FloatArrayList out); }

    // An empty callback gets optimized out, so no MC pass exists until some unrelated held item uses
    // the same RenderType. Four identical vertices create the pass but rasterize zero pixels.
    private static final SubmitNodeCollector.CustomGeometryRenderer PASS_MARKER = (pose, consumer) -> {
        for (int i = 0; i < 4; i++)
            consumer.addVertex(0, 0, 0, 0, 0, 0, OverlayTexture.NO_OVERLAY, 0, 0, 1, 0);
    };
    private static final Map<Object, List<Batch>> BATCHES_BY_MESH = new IdentityHashMap<>();
    private static final List<Batch> BATCHES = new ArrayList<>();
    private static final Map<OrderedSubmitNodeCollector, long[]> COLLECTOR_MARKERS = new IdentityHashMap<>();
    private static final Map<OrderedSubmitNodeCollector, Integer> WORLD_ORDERS = new IdentityHashMap<>();
    private static final Map<Object, GpuPose> GPU_POSES = new IdentityHashMap<>();
    private static final List<GpuPose> GPU_POSE_POOL = new ArrayList<>();
    private static final long[] MARKER_FRAME = {-1, -1, -1};
    private static final long[] DRAWN_FRAME = {-1, -1, -1};
    private static long frame;
    private static long flushedFrame = -1;
    private static long nextId = 1L << 60;
    private static int submitted;
    private static int rejected;
    private static final boolean[] PASS_DREW = new boolean[3];
    private static int gpuPosePoolUsed;
    private static long[] frameIds = new long[16];
    private static int[] frameMeta = new int[64];
    private static Batch[] frameBatches = new Batch[16];
    private static float[] frameBones = new float[1024];
    private static float[] frameInstances = new float[1024];

    private KenderEntityBatch() {}

    public static synchronized void beginFrame() {
        frame++;
        submitted = 0;
        rejected = 0;
        PASS_DREW[0] = PASS_DREW[1] = PASS_DREW[2] = false;
        COLLECTOR_MARKERS.clear();
        GPU_POSES.clear();
        gpuPosePoolUsed = 0;
    }

    public static boolean available() {
        return com.koper.koper_lib.kender.KenderConfig.get().kenderEntityRender && com.koper.koper_lib.kender.KenderFrame.vulkanActive()
            && KenderBridge.geoEntityPassAvailable();
    }

    public static boolean worldCollector(OrderedSubmitNodeCollector collector) {
        var levelRenderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
        return levelRenderer != null
            && (((LevelRendererSubmitAccessor)(Object)levelRenderer).koperlib$submitNodeStorage() == collector
                || WORLD_ORDERS.containsKey(collector));
    }

    public static synchronized void registerWorldOrder(Object owner, OrderedSubmitNodeCollector collector, int order) {
        var levelRenderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
        if (levelRenderer != null
                && ((LevelRendererSubmitAccessor)(Object)levelRenderer).koperlib$submitNodeStorage() == owner)
            WORLD_ORDERS.put(collector, order);
    }

    public static synchronized int worldOrder(OrderedSubmitNodeCollector collector) {
        var levelRenderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
        if (levelRenderer != null
                && ((LevelRendererSubmitAccessor)(Object)levelRenderer).koperlib$submitNodeStorage() == collector)
            return 0;
        return WORLD_ORDERS.getOrDefault(collector, Integer.MIN_VALUE);
    }

    public static boolean supportedLayerType(RenderType renderType) {
        if (renderType == null || renderType.isOutline()) return false;
        var pipeline = renderType.pipeline();
        return pipeline == RenderPipelines.ENTITY_SOLID
            || pipeline == RenderPipelines.ENTITY_CUTOUT
            || pipeline == RenderPipelines.ENTITY_CUTOUT_CULL
            || pipeline == RenderPipelines.ENTITY_TRANSLUCENT
            || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL
            || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
            || pipeline == RenderPipelines.EYES
            || pipeline == RenderPipelines.ENERGY_SWIRL
            || pipeline == RenderPipelines.BREEZE_WIND;
    }

    public static int material(RenderType renderType) {
        var pipeline = renderType.pipeline();
        if (pipeline == RenderPipelines.ENERGY_SWIRL) return 4;
        if (pipeline == RenderPipelines.BREEZE_WIND) return 5;
        if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE || pipeline == RenderPipelines.EYES) return 3;
        if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL) return 1;
        if (pipeline == RenderPipelines.ENTITY_SOLID) return 2;
        return 0;
    }

    public static int pass(RenderType renderType) {
        return renderType != null && renderType.hasBlending() ? TRANSLUCENT_PASS : OPAQUE_PASS;
    }

    public static Matrix4f originRelative(Matrix4fc relativeModel, CameraRenderState camera, Matrix4f out) {
        out.set(relativeModel);
        if (camera != null) {
            out.m30(out.m30() + (float)(camera.pos.x - com.koper.koper_lib.kender.KenderFrame.renderOriginX()));
            out.m31(out.m31() + (float)(camera.pos.y - com.koper.koper_lib.kender.KenderFrame.renderOriginY()));
            out.m32(out.m32() + (float)(camera.pos.z - com.koper.koper_lib.kender.KenderFrame.renderOriginZ()));
        }
        return out;
    }

    public static synchronized boolean submit(Object meshIdentity, String debugName, Supplier<float[]> meshBake,
                                              Identifier texture, RenderType renderType, Matrix4fc transform,
                                              int light, int tint, int boneCount, BoneWriter boneWriter) {
        return submit(meshIdentity, debugName, meshBake, texture, renderType, transform,
            light, tint, boneCount, boneWriter, 0);
    }

    public static synchronized boolean submit(Object meshIdentity, String debugName, Supplier<float[]> meshBake,
                                              Identifier texture, RenderType renderType, Matrix4fc transform,
                                              int light, int tint, int boneCount, BoneWriter boneWriter, int order) {
        return submit(meshIdentity, debugName, meshBake, texture, renderType, transform,
            light, tint, boneCount, boneWriter, order, null);
    }

    public static synchronized boolean submit(Object meshIdentity, String debugName, Supplier<float[]> meshBake,
                                              Identifier texture, RenderType renderType, Matrix4fc transform,
                                              int light, int tint, int boneCount, BoneWriter boneWriter, int order,
                                              Object stateKey) {
        return submit(meshIdentity, debugName, meshBake, texture, renderType, transform,
            light, tint, boneCount, boneWriter, order, stateKey, 0f, 0f);
    }

    public static synchronized boolean submit(Object meshIdentity, String debugName, Supplier<float[]> meshBake,
                                              Identifier texture, RenderType renderType, Matrix4fc transform,
                                              int light, int tint, int boneCount, BoneWriter boneWriter, int order,
                                              Object stateKey, float uOffset, float vOffset) {
        if (!available() || meshIdentity == null || texture == null || renderType == null || transform == null
                || boneCount <= 0 || boneWriter == null || renderType.isOutline()) return false;
        int pass = pass(renderType);
        int material = material(renderType);
        Batch batch = batch(meshIdentity, debugName, meshBake, texture, pass, order, material, 0);
        if (batch == null) return false;
        if (batch.failed || !batch.ensureReady()) return false;
        batch.touch();

        int boneStart = batch.bones.size();
        try { boneWriter.append(batch.bones); }
        catch (Throwable t) {
            batch.bones.removeElements(boneStart, batch.bones.size());
            return false;
        }
        int boneFloats = batch.bones.size() - boneStart;
        if (boneFloats != boneCount * 16) {
            batch.bones.removeElements(boneStart, batch.bones.size());
            return false;
        }

        transform.get(batch.matrix);
        int at = batch.instances.size();
        batch.instances.addElements(at, batch.matrix);
        batch.instances.add(light4(light));
        batch.instances.add(sky4(light));
        batch.instances.add(boneStart / 16f);
        batch.instances.add(com.koper.koper_lib.kender.KenderFrame.tintEnc(tint));
        batch.instances.add(uOffset);
        batch.instances.add(vOffset);
        if (stateKey != null) putGpuPose(stateKey, meshIdentity, batch, boneStart / 16);
        submitted++;
        return true;
    }

    public static synchronized boolean submitSharedLayer(Object stateKey, Object meshIdentity, String debugName,
                                                          Supplier<float[]> meshBake, Identifier texture,
                                                          RenderType renderType, Matrix4fc transform,
                                                          int light, int tint, int order,
                                                          float uOffset, float vOffset) {
        GpuPose pose = GPU_POSES.get(stateKey);
        if (pose == null || pose.meshIdentity != meshIdentity || !available()
                || texture == null || renderType == null || transform == null || renderType.isOutline()) return false;
        int pass = pass(renderType);
        int material = material(renderType);
        Batch batch = batch(meshIdentity, debugName, meshBake, texture, pass, order, material, pose.batch.id);
        if (batch == null || batch.failed || !batch.ensureReady()) return false;
        batch.touch();
        transform.get(batch.matrix);
        int at = batch.instances.size();
        batch.instances.addElements(at, batch.matrix);
        batch.instances.add(light4(light));
        batch.instances.add(sky4(light));
        batch.instances.add(pose.boneBase);
        batch.instances.add(com.koper.koper_lib.kender.KenderFrame.tintEnc(tint));
        batch.instances.add(uOffset);
        batch.instances.add(vOffset);
        submitted++;
        return true;
    }

    private static Batch batch(Object meshIdentity, String debugName, Supplier<float[]> meshBake,
                               Identifier texture, int pass, int order, int material, long boneSource) {
        List<Batch> variants = BATCHES_BY_MESH.get(meshIdentity);
        if (variants != null) {
            for (int i = 0; i < variants.size(); i++) {
                Batch candidate = variants.get(i);
                boolean sourceMatches = boneSource == 0
                    ? candidate.boneSource == candidate.id : candidate.boneSource == boneSource;
                if (candidate.pass == pass && candidate.order == order && candidate.material == material && sourceMatches
                        && candidate.texture.equals(texture)) return candidate;
            }
        }
        float[] mesh;
        try { mesh = meshBake.get(); }
        catch (Throwable t) { return null; }
        if (mesh == null || mesh.length == 0 || mesh.length % (SKIN_STRIDE * 4) != 0)
            return null;
        long id = nextId++;
        Batch created = new Batch(id, debugName, texture, pass, order, material,
            boneSource == 0 ? id : boneSource, mesh);
        if (variants == null) {
            variants = new ArrayList<>(2);
            BATCHES_BY_MESH.put(meshIdentity, variants);
        }
        variants.add(created);
        BATCHES.add(created);
        return created;
    }

    public static synchronized void ensurePass(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                                               RenderType renderType, Identifier texture) {
        int pass = pass(renderType);
        MARKER_FRAME[pass] = frame;
        long[] markers = COLLECTOR_MARKERS.computeIfAbsent(collector, ignored -> new long[]{-1, -1, -1});
        if (markers[pass] == frame) return;
        markers[pass] = frame;
        RenderType markerType = pass == TRANSLUCENT_PASS
            ? RenderTypes.entityTranslucent(texture, false)
            : RenderTypes.entityCutout(texture, false);
        collector.submitCustomGeometry(poseStack, markerType, PASS_MARKER);
    }

    public static synchronized void markGpuState(Object state) {
        putGpuPose(state, null, null, 0);
    }
    public static synchronized boolean hasGpuState(Object state) { return GPU_POSES.containsKey(state); }

    public static synchronized int claimLayerOrder(Object state, int requested) {
        GpuPose pose = GPU_POSES.get(state);
        if (pose == null) return -1;
        int order = Math.max(requested, pose.nextOrder);
        pose.nextOrder = order + 1;
        return order;
    }

    private static void putGpuPose(Object state, Object meshIdentity, Batch batch, int boneBase) {
        GpuPose pose;
        if (gpuPosePoolUsed == GPU_POSE_POOL.size()) {
            pose = new GpuPose();
            GPU_POSE_POOL.add(pose);
        } else pose = GPU_POSE_POOL.get(gpuPosePoolUsed);
        gpuPosePoolUsed++;
        pose.meshIdentity = meshIdentity;
        pose.batch = batch;
        pose.boneBase = boneBase;
        pose.nextOrder = 1;
        GPU_POSES.put(state, pose);
    }

    public static synchronized boolean frameArmed(int pass) {
        return pass >= OPAQUE_PASS && pass <= TRANSLUCENT_PASS && MARKER_FRAME[pass] == frame;
    }

    public static synchronized boolean consumePass(int pass) {
        if (!frameArmed(pass) || DRAWN_FRAME[pass] == frame) return false;
        DRAWN_FRAME[pass] = frame;
        return true;
    }

    public static synchronized void flush() {
        if (flushedFrame == frame) return;
        flushedFrame = frame;
        int records = 0, boneFloats = 0, instanceFloats = 0;
        for (Batch batch : BATCHES) {
            boolean active = batch.touchedFrame == frame && !batch.instances.isEmpty();
            if (!active && !batch.live) continue;
            records++;
            if (active) {
                if (batch.boneSource == batch.id) boneFloats += batch.bones.size();
                instanceFloats += batch.instances.size();
            }
        }
        if (records == 0) return;
        ensureFrameCapacity(records, boneFloats, instanceFloats);
        int r = 0, bo = 0, io = 0;
        for (Batch batch : BATCHES) {
            boolean active = batch.touchedFrame == frame && !batch.instances.isEmpty();
            if (!active && !batch.live) continue;
            int bc = active && batch.boneSource == batch.id ? batch.bones.size() : 0;
            int ic = active ? batch.instances.size() : 0;
            frameIds[r] = batch.id;
            frameBatches[r] = batch;
            int m = r * 4;
            frameMeta[m] = bo; frameMeta[m + 1] = bc;
            frameMeta[m + 2] = io; frameMeta[m + 3] = ic;
            if (bc != 0) System.arraycopy(batch.bones.elements(), 0, frameBones, bo, bc);
            if (ic != 0) System.arraycopy(batch.instances.elements(), 0, frameInstances, io, ic);
            bo += bc; io += ic; r++;
        }
        int rc = KenderBridge.geoSetSkinnedFrame(frameIds, frameMeta, records,
            frameBones, boneFloats, frameInstances, instanceFloats);
        if (rc == 0) {
            for (int i = 0; i < records; i++)
                frameBatches[i].live = frameMeta[i * 4 + 3] != 0;
        } else {
            flushLegacy();
            if (KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-entity] frame upload failed rc={}, used legacy path", rc);
        }
    }

    private static void ensureFrameCapacity(int records, int bones, int instances) {
        if (frameIds.length < records) {
            int n = Integer.highestOneBit(records - 1) << 1;
            frameIds = java.util.Arrays.copyOf(frameIds, n);
            frameBatches = java.util.Arrays.copyOf(frameBatches, n);
            frameMeta = java.util.Arrays.copyOf(frameMeta, n * 4);
        }
        if (frameBones.length < bones)
            frameBones = java.util.Arrays.copyOf(frameBones, Integer.highestOneBit(bones - 1) << 1);
        if (frameInstances.length < instances)
            frameInstances = java.util.Arrays.copyOf(frameInstances, Integer.highestOneBit(instances - 1) << 1);
    }

    private static void flushLegacy() {
        for (Batch batch : BATCHES) {
            if (batch.touchedFrame != frame || batch.instances.isEmpty()) {
                if (batch.live) KenderBridge.geoSetInstancesSkinned(batch.id, null);
                batch.live = false;
                continue;
            }
            int brc = batch.boneSource == batch.id
                ? KenderBridge.geoSetBones(batch.id, batch.bones.elements(), batch.bones.size()) : 0;
            int irc = KenderBridge.geoSetInstancesSkinned(batch.id, batch.instances.elements(), batch.instances.size());
            batch.live = brc == 0 && irc == 0;
        }
    }

    public static synchronized void clear() {
        for (Batch batch : BATCHES) KenderBridge.geoRemove(batch.id);
        BATCHES.clear();
        BATCHES_BY_MESH.clear();
        GPU_POSES.clear();
        gpuPosePoolUsed = 0;
        COLLECTOR_MARKERS.clear();
        MARKER_FRAME[OPAQUE_PASS] = MARKER_FRAME[TRANSLUCENT_PASS] = -1;
        DRAWN_FRAME[OPAQUE_PASS] = DRAWN_FRAME[TRANSLUCENT_PASS] = -1;
        flushedFrame = -1;
    }

    /** A model that Kender refused — it went back to MC's per-entity submit. */
    public static void reject() { rejected++; }

    public static void markDrew(int pass) { if (pass >= 0 && pass < 3) PASS_DREW[pass] = true; }

    public static synchronized Stats stats() {
        int liveBatches = 0;
        for (Batch batch : BATCHES) if (batch.live) liveBatches++;
        return new Stats(BATCHES.size(), liveBatches, submitted, rejected,
            PASS_DREW[OPAQUE_PASS], PASS_DREW[TRANSLUCENT_PASS], available());
    }

    public record Stats(int batches, int liveBatches, int submittedEntities, int rejectedModels,
                        boolean drewOpaque, boolean drewTranslucent, boolean available) {
        @Override public String toString() {
            return "batches=" + batches + "/" + liveBatches + " gpu=" + submittedEntities
                + " cpu=" + rejectedModels + " drew=" + (drewOpaque ? "O" : "-") + (drewTranslucent ? "T" : "-")
                + " avail=" + available;
        }
    }

    private static final class GpuPose {
        Object meshIdentity;
        Batch batch;
        int boneBase;
        int nextOrder;
    }

    private static float light4(int packed) { return ((packed >> 4) & 15) / 15f; }
    private static float sky4(int packed) { return ((packed >> 20) & 15) / 15f; }

    private static final class Batch {
        final long id;
        final String name;
        final Identifier texture;
        final int pass;
        final int order;
        final int material;
        final long boneSource;
        final float[] mesh;
        final FloatArrayList instances = new FloatArrayList();
        final FloatArrayList bones = new FloatArrayList();
        final float[] matrix = new float[16];
        long touchedFrame = -1;
        long textureView;
        boolean uploaded;
        boolean failed;
        boolean live;

        Batch(long id, String name, Identifier texture, int pass, int order, int material, long boneSource, float[] mesh) {
            this.id = id;
            this.name = name;
            this.texture = texture;
            this.pass = pass;
            this.order = order;
            this.material = material;
            this.boneSource = boneSource;
            this.mesh = mesh;
        }

        void touch() {
            if (touchedFrame == frame) return;
            touchedFrame = frame;
            instances.clear();
            bones.clear();
        }

        boolean ensureReady() {
            if (!uploaded) {
                int upload = KenderBridge.geoUploadSkinned(id, mesh);
                int nativeMaterial = upload == 0 ? KenderBridge.geoSetMaterial(id, material) : -1;
                int nativePass = nativeMaterial == 0 ? KenderBridge.geoSetPass(id, pass) : -1;
                int nativeOrder = nativePass == 0 ? KenderBridge.geoSetOrder(id, order) : -1;
                int nativeBones = nativeOrder == 0 ? KenderBridge.geoSetBoneSource(id, boneSource) : -1;
                if (upload != 0 || nativeMaterial != 0 || nativePass != 0 || nativeOrder != 0 || nativeBones != 0) {
                    KenderBridge.geoRemove(id);
                    failed = true;
                    if (KoperLibConfig.get().debugMode)
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-entity] native setup failed {} upload={} material={} pass={} order={} bones={}; vanilla stays on",
                            name, upload, nativeMaterial, nativePass, nativeOrder, nativeBones);
                    return false;
                }
                uploaded = true;
                if (KoperLibConfig.get().debugMode)
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-entity] {} -> GPU skinning pass={} order={} sharedBones={}",
                        name, pass, order, boneSource != id);
            }
            if (textureView == 0L) {
                long view = KenderVk.textureImageView(texture);
                if (view == 0L || KenderBridge.geoSetTexture(id, view) != 0) return false;
                textureView = view;
            }
            return true;
        }
    }

}
