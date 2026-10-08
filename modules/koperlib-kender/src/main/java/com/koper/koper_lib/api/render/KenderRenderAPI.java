package com.koper.koper_lib.api.render;

import com.koper.koper_lib.kender.KenderBridge;

import com.koper.koper_lib.kender.KenderVk;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Public block/BE/Flywheel mesh path. Meshes are quads: position3, uv2, normal3. */
@Environment(EnvType.CLIENT)
public final class KenderRenderAPI {
    public static final int VERTEX_FLOATS = 8;
    public static final int INSTANCE_FLOATS = 19;
    /** Kinetic stride: mat4 + light2 + tint + rotQuat4 + axis3 + speed + pivot3 + offset + pad. */
    public static final int SPIN_INSTANCE_FLOATS = 32;
    /** Shadow stride: mat4 + light2 + alpha + entityXZ + radius + pad. */
    public static final int SHADOW_INSTANCE_FLOATS = 24;

    public enum Material {
        CUTOUT(0), TRANSLUCENT(1), SOLID(2);
        final int nativeId;
        Material(int nativeId) { this.nativeId = nativeId; }
    }

    public enum Usage { STATIC, DYNAMIC }
    public enum Topology { QUADS, TRIANGLES }

    // upload profile, drained by /cr
    private static long flushNanos;
    private static int flushFrames, flushUploads;
    private static long flushFloats;

    /** Drains the upload profile. us per frame, meshes re-uploaded, floats pushed. */
    public static synchronized String drainProfile() {
        int fr = Math.max(1, flushFrames);
        String out = String.format("flush=%.2fms/f uploads=%d floats=%d",
            flushNanos / 1_000_000.0 / fr, flushUploads / fr, flushFloats / fr);
        flushNanos = 0; flushFrames = 0; flushUploads = 0; flushFloats = 0;
        return out;
    }

    private static final AtomicLong IDS = new AtomicLong(-1);
    private static final Map<Identifier, Mesh> MESHES = new LinkedHashMap<>();

    private KenderRenderAPI() {}

    public static synchronized Mesh mesh(Identifier key, float[] vertices, Identifier texture,
                                         Material material, Usage usage) {
        return mesh(key, vertices, texture, material, usage, Topology.QUADS);
    }

    public static synchronized Mesh mesh(Identifier key, float[] vertices, Identifier texture,
                                         Material material, Usage usage, Topology topology) {
        float[] quads = topology == Topology.TRIANGLES ? trianglesToQuads(vertices) : vertices;
        if (key == null || texture == null || quads == null || quads.length == 0
                || quads.length % (VERTEX_FLOATS * 4) != 0)
            throw new IllegalArgumentException("Kender mesh must contain complete stride-8 quads");
        Mesh old = MESHES.get(key);
        if (old != null) old.close();
        Mesh mesh = new Mesh(IDS.getAndDecrement(), key, quads.clone(), texture,
            material == null ? Material.CUTOUT : material, usage == null ? Usage.STATIC : usage);
        MESHES.put(key, mesh);
        return mesh;
    }

    private static float[] trianglesToQuads(float[] triangles) {
        if (triangles == null || triangles.length == 0
                || triangles.length % (VERTEX_FLOATS * 3) != 0)
            throw new IllegalArgumentException("Kender triangle mesh must contain complete stride-8 triangles");
        int triangleCount = triangles.length / (VERTEX_FLOATS * 3);
        float[] out = new float[triangleCount * VERTEX_FLOATS * 4];
        for (int tri = 0; tri < triangleCount; tri++) {
            int src = tri * VERTEX_FLOATS * 3;
            int dst = tri * VERTEX_FLOATS * 4;
            System.arraycopy(triangles, src, out, dst, VERTEX_FLOATS * 3);
            System.arraycopy(triangles, src + VERTEX_FLOATS * 2,
                out, dst + VERTEX_FLOATS * 3, VERTEX_FLOATS);
        }
        return out;
    }

    public static synchronized Mesh get(Identifier key) { return MESHES.get(key); }
    public static boolean available() { return com.koper.koper_lib.kender.KenderFrame.vulkanActive(); }

    public static boolean submitVanillaScaled(BlockState state, byte faceMask,
                                               float bodyX, float bodyY, float bodyZ,
                                               Quaternionf rotation,
                                               float blockX, float blockY, float blockZ,
                                               float pieceX, float pieceY, float pieceZ, float scale,
                                               BlockPos worldPos, int packedLight) {
        return com.koper.koper_lib.kender.VanillaBlockKender.submitScaled(state, faceMask,
            bodyX, bodyY, bodyZ, rotation, blockX, blockY, blockZ,
            pieceX, pieceY, pieceZ, scale, worldPos, packedLight);
    }

    public static synchronized Stats stats() {
        int instances = 0, uploaded = 0;
        for (Mesh mesh : MESHES.values()) {
            instances += mesh.count;
            if (mesh.uploaded) uploaded++;
        }
        return new Stats(MESHES.size(), uploaded, instances, available());
    }

    public record Stats(int meshes, int uploadedMeshes, int instances, boolean vulkan) {}

    public static final class Mesh implements AutoCloseable {
        private final long nativeId;
        private final Identifier key;
        private final Identifier texture;
        private final Material material;
        private final Usage usage;
        private final Writer writer = new Writer(this);
        private float[] vertices;
        private float[] worldInstances = new float[0];
        private float[] uploadScratch = new float[0];
        private int count;
        private boolean spin;
        private boolean spinDeclared;
        private boolean shadowMesh;
        private boolean shadowDeclared;
        private boolean instanceDirty = true;
        private boolean uploaded;
        private boolean closed;
        private long textureView;

        private Mesh(long nativeId, Identifier key, float[] vertices, Identifier texture,
                     Material material, Usage usage) {
            this.nativeId = nativeId;
            this.key = key;
            this.vertices = vertices;
            this.texture = texture;
            this.material = material;
            this.usage = usage;
        }

        public Identifier key() { return key; }
        public Material material() { return material; }
        public Usage usage() { return usage; }
        public synchronized int instanceCount() { return count; }
        public synchronized boolean gpuReady() { return uploaded && textureView != 0 && available(); }

        public synchronized void updateVertices(float[] newVertices, Topology topology) {
            checkOpen();
            float[] quads = topology == Topology.TRIANGLES ? trianglesToQuads(newVertices) : newVertices;
            if (quads == null || quads.length == 0 || quads.length % (VERTEX_FLOATS * 4) != 0)
                throw new IllegalArgumentException("Kender mesh must contain complete stride-8 quads");
            if (uploaded) KenderBridge.geoRemove(nativeId);
            vertices = quads.clone();
            uploaded = false;
            spinDeclared = false;
            shadowDeclared = false;
            textureView = 0;
            instanceDirty = true;
        }

        public void updateVertices(float[] newVertices) {
            updateVertices(newVertices, Topology.QUADS);
        }

        /** Reuses one writer and its backing arrays. Call end() after filling this frame/batch. */
        public synchronized Writer begin(int expectedInstances) {
            checkOpen();
            ensure(Math.max(0, expectedInstances));
            writer.cursor = 0;
            writer.open = true;
            return writer;
        }

        public synchronized void clearInstances() {
            checkOpen();
            count = 0;
            instanceDirty = true;
        }

        /**
         * Kinetic mesh: instances carry axis/speed/offset/pivot and the vertex shader resolves the
         * angle from a per-frame clock, so a spinning instance never needs a CPU rewrite. Call before
         * the first flush — it picks the instance stride.
         */
        public synchronized void markKinetic() {
            checkOpen();
            if (spin) return;
            spin = true;
            count = 0;
            instanceDirty = true;
        }

        public synchronized boolean kinetic() { return spin; }

        /** Flywheel entity shadow: UV is derived from world position, so it needs its own shader. */
        public synchronized void markShadow() {
            checkOpen();
            if (shadowMesh) return;
            shadowMesh = true;
            count = 0;
            instanceDirty = true;
        }

        int instanceFloats() {
            if (shadowMesh) return SHADOW_INSTANCE_FLOATS;
            return spin ? SPIN_INSTANCE_FLOATS : INSTANCE_FLOATS;
        }

        /**
         * Bulk instance set — {@code data} holds {@link #INSTANCE_FLOATS} floats per instance, world space,
         * same layout {@link Writer} writes. For callers that already built the array and would otherwise
         * recompute identical transforms once per mesh part.
         */
        public synchronized void setInstances(float[] data, int instances) {
            checkOpen();
            int fl = instanceFloats();
            int n = Math.max(0, Math.min(instances, data == null ? 0 : data.length / fl));
            ensure(n);
            if (n > 0) System.arraycopy(data, 0, worldInstances, 0, n * fl);
            count = n;
            instanceDirty = true;
        }

        private void ensure(int instances) {
            int need = instances * instanceFloats();
            if (worldInstances.length >= need) return;
            int grown = Math.max(need, Math.max(64, worldInstances.length * 2));
            worldInstances = java.util.Arrays.copyOf(worldInstances, grown);
        }

        private void checkOpen() {
            if (closed) throw new IllegalStateException("Kender mesh is closed: " + key);
        }

        @Override
        public void close() {
            // flush takes API -> mesh, so keep close in that same order for addon worker threads
            synchronized (KenderRenderAPI.class) {
                synchronized (this) {
                    if (closed) return;
                    closed = true;
                    count = 0;
                    instanceDirty = true;
                    MESHES.remove(key, this);
                    if (uploaded) KenderBridge.geoRemove(nativeId);
                }
            }
        }
    }

    public static final class Writer {
        private final Mesh mesh;
        private int cursor;
        private boolean open;

        private Writer(Mesh mesh) { this.mesh = mesh; }

        public Writer instance(Matrix4fc worldTransform, int blockLight, int skyLight, int argbTint) {
            if (worldTransform == null) return this;
            synchronized (mesh) {
                if (!open) throw new IllegalStateException("begin() was not called");
                mesh.ensure(cursor + 1);
                int at = cursor * INSTANCE_FLOATS;
                worldTransform.get(mesh.worldInstances, at);
                mesh.worldInstances[at + 16] = clampLight(blockLight);
                mesh.worldInstances[at + 17] = clampLight(skyLight);
                mesh.worldInstances[at + 18] = com.koper.koper_lib.kender.KenderFrame.tintEnc(argbTint);
                cursor++;
            }
            return this;
        }

        public void end() {
            synchronized (mesh) {
                if (!open) return;
                mesh.count = cursor;
                mesh.instanceDirty = true;
                open = false;
            }
        }
    }

    private static float clampLight(int light) {
        return Math.max(0, Math.min(15, light)) / 15f;
    }

    /** Render-thread flush, called by KenderFrame before the open Vulkan pass. */
    public static synchronized void flush(long originX, long originY, long originZ) {
        if (!available()) return;
        long t0 = System.nanoTime();
        for (Mesh mesh : MESHES.values()) flush(mesh, originX, originY, originZ);
        flushNanos += System.nanoTime() - t0;
        flushFrames++;
    }

    private static void flush(Mesh mesh, long originX, long originY, long originZ) {
        synchronized (mesh) {
            if (mesh.closed) return;
            if (!mesh.uploaded) {
                if (KenderBridge.geoUploadModel(mesh.nativeId, mesh.vertices) != 0) return;
                KenderBridge.geoSetMaterial(mesh.nativeId, mesh.material.nativeId);
                if (mesh.usage == Usage.DYNAMIC) KenderBridge.geoSetDynamic(mesh.nativeId);
                mesh.uploaded = true;
                mesh.vertices = null;
            }
            // must land before any instances go up — it picks the stride natively. not inside the
            // upload block: markKinetic is allowed to arrive a frame later than the model itself.
            if (mesh.shadowMesh && !mesh.shadowDeclared && mesh.uploaded) {
                if (KenderBridge.geoSetShadow(mesh.nativeId) != 0) { mesh.shadowMesh = false; mesh.count = 0; }
                mesh.shadowDeclared = true;
            }
            if (mesh.spin && !mesh.spinDeclared && mesh.uploaded) {
                if (KenderBridge.geoSetSpin(mesh.nativeId) != 0) {
                    mesh.spin = false;      // native refused -> fall back to the plain stride
                    mesh.count = 0;
                }
                mesh.spinDeclared = true;
            }
            long view = KenderVk.textureImageView(mesh.texture);
            if (view != 0 && view != mesh.textureView) {
                KenderBridge.geoSetTexture(mesh.nativeId, view);
                mesh.textureView = view;
            }
            if (!mesh.instanceDirty) return;
            int fl = mesh.instanceFloats();
            int floats = mesh.count * fl;
            if (mesh.uploadScratch.length < floats) mesh.uploadScratch = new float[floats];
            System.arraycopy(mesh.worldInstances, 0, mesh.uploadScratch, 0, floats);
            for (int at = 0; at < floats; at += fl) {
                mesh.uploadScratch[at + 12] -= originX;
                mesh.uploadScratch[at + 13] -= originY;
                mesh.uploadScratch[at + 14] -= originZ;
            }
            if (KenderBridge.geoSetInstances(mesh.nativeId, mesh.uploadScratch, floats) == 0)
                mesh.instanceDirty = false;
            flushUploads++;
            flushFloats += floats;
            com.koper.koper_lib.kender.KontraLagSniffer.uploaded(floats);
        }
    }

    public static synchronized void originChanged() {
        for (Mesh mesh : MESHES.values()) mesh.instanceDirty = true;
    }
}
