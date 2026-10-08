package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonObject;
import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.kodel.KodelNative;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.*;

// bedrock particle effects on screen. the simulation is kodel's native (czastki.rs), this keeps
// the emitters, feeds them where their entity is, and turns the quads into vertices
public final class BrCzastki {

    private static final MethodHandle DEFINE = KodelNative.function("kodel_px_define", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle DESCRIBE = KodelNative.function("kodel_px_describe", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle UNDEFINE = KodelNative.function("kodel_px_undefine", FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle SPAWN = KodelNative.function("kodel_px_spawn", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT));
    private static final MethodHandle MOVE = KodelNative.function("kodel_px_move", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS));
    private static final MethodHandle EXPIRE = KodelNative.function("kodel_px_expire", FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle FREE = KodelNative.function("kodel_px_free", FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle TICK = KodelNative.function("kodel_px_tick", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS, JAVA_INT));
    private static final MethodHandle REQUESTS = KodelNative.function("kodel_px_requests", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static final int QF = 19;
    // an addon spamming emitters must not eat the frame
    private static final int MAX_EMITTERS = 512;
    private static final int MAX_QUADS = 16384;

    private record Efekt(long def, Identifier texture, RenderType type, boolean lit, String ns) {}

    private static final class Emiter {
        final long handle;
        final Efekt fx;
        final int entity;       // -1 = free standing
        final String locator;
        long last = System.nanoTime();
        int quads;
        float[] data = new float[0];

        Emiter(long handle, Efekt fx, int entity, String locator) {
            this.handle = handle;
            this.fx = fx;
            this.entity = entity;
            this.locator = locator;
        }
    }

    private static final Map<String, Efekt> EFEKTY = new HashMap<>();
    private static final List<Emiter> LIVE = new ArrayList<>();
    private static MemorySegment out = MemorySegment.NULL;
    private static final MemorySegment AABB_SEG = Arena.global().allocate(24, 4);
    private static final MemorySegment REQ = Arena.global().allocate(8192);

    private BrCzastki() {}

    static boolean ready() {
        return KodelNative.isLoaded() && TICK != null;
    }

    public static void przeladuj() {
        for (Emiter e : LIVE) free(e.handle);
        LIVE.clear();
        for (Efekt f : EFEKTY.values()) if (f != null) try { UNDEFINE.invoke(f.def); } catch (Throwable ignored) {}
        EFEKTY.clear();
    }

    private static void free(long h) {
        try { FREE.invoke(h); } catch (Throwable ignored) {}
    }

    private static Efekt efekt(String id) {
        if (EFEKTY.containsKey(id)) return EFEKTY.get(id);
        Efekt fx = null;
        BrPaczki.Indeks idx = BrPaczki.indeks();
        BrPaczki.Paczka p = idx == null ? null : idx.particleOwner.get(id);
        if (p != null && ready()) {
            try (Arena a = Arena.ofConfined()) {
                byte[] b = p.particles.get(id).toString().getBytes(StandardCharsets.UTF_8);
                MemorySegment seg = a.allocate(b.length);
                MemorySegment.copy(b, 0, seg, JAVA_BYTE, 0, b.length);
                long def = (long) DEFINE.invoke(seg, b.length);
                if (def != 0) {
                    MemorySegment d = a.allocate(8192);
                    int n = (int) DESCRIBE.invoke(def, d, 8192);
                    JsonObject desc = n > 0 && n <= 8192
                        ? com.google.gson.JsonParser.parseString(new String(d.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8)).getAsJsonObject()
                        : new JsonObject();
                    String tex = desc.has("texture") ? desc.get("texture").getAsString() : "";
                    String mat = desc.has("material") ? desc.get("material").getAsString() : "particles_alpha";
                    Identifier texId = BrTyp.textureId(p.ns, tex.isEmpty() ? "textures/particle/particles" : tex);
                    // alpha = cutout, blend = see through, add = glowy
                    RenderType type = mat.contains("add") ? RenderTypes.eyes(texId)
                        : mat.contains("blend") ? RenderTypes.entityTranslucent(texId) : RenderTypes.entityCutout(texId);
                    fx = new Efekt(def, texId, type, desc.has("lit") && desc.get("lit").getAsBoolean(), p.ns);
                    if (desc.has("errors") && !desc.getAsJsonArray("errors").isEmpty())
                        KoperCore.LOGGER.debug("[Kodel/Bedrock] particle {} molang: {}", id, desc.getAsJsonArray("errors").get(0));
                }
            } catch (Throwable t) {
                KoperCore.LOGGER.warn("[Kodel/Bedrock] particle {} did not build: {}", id, t.toString());
            }
        }
        EFEKTY.put(id, fx);
        return fx;
    }

    /// free standing effect at a world position
    public static boolean spawn(String id, double x, double y, double z) {
        return spawn(id, x, y, z, -1, null);
    }

    static boolean spawn(String id, double x, double y, double z, int entity, String locator) {
        Efekt fx = efekt(id);
        if (fx == null || LIVE.size() >= MAX_EMITTERS) return false;
        try {
            long h = (long) SPAWN.invoke(fx.def, (float) x, (float) y, (float) z, (int) System.nanoTime());
            if (h == 0) return false;
            LIVE.add(new Emiter(h, fx, entity, locator));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /// once per frame from the level render: step every emitter and draw what they have
    public static void submit(SubmitNodeCollector collector, CameraRenderState camera) {
        if (LIVE.isEmpty() || camera == null || camera.pos == null) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;
        Vec3 cam = camera.pos;
        int budget = MAX_QUADS;
        List<String[]> requests = new ArrayList<>();
        for (int i = LIVE.size() - 1; i >= 0; i--) {
            Emiter e = LIVE.get(i);
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - e.last) / 1e9f);
            e.last = now;
            try {
                if (e.entity >= 0) {
                    Entity ent = level.getEntity(e.entity);
                    if (ent == null || ent.isRemoved()) {
                        EXPIRE.invoke(e.handle);
                    } else {
                        Vec3 at = BrAktorzy.locatorWorld(ent, e.locator);
                        AABB bb = ent.getBoundingBox();
                        AABB_SEG.setAtIndex(JAVA_FLOAT, 0, (float) bb.minX);
                        AABB_SEG.setAtIndex(JAVA_FLOAT, 1, (float) bb.minY);
                        AABB_SEG.setAtIndex(JAVA_FLOAT, 2, (float) bb.minZ);
                        AABB_SEG.setAtIndex(JAVA_FLOAT, 3, (float) bb.maxX);
                        AABB_SEG.setAtIndex(JAVA_FLOAT, 4, (float) bb.maxY);
                        AABB_SEG.setAtIndex(JAVA_FLOAT, 5, (float) bb.maxZ);
                        MOVE.invoke(e.handle, (float) at.x, (float) at.y, (float) at.z, AABB_SEG);
                    }
                }
                long need = 4L * QF * 4096;
                if (out.byteSize() < need) out = Arena.global().allocate(need, 4);
                int n = (int) TICK.invoke(e.handle, dt, (float) cam.x, (float) cam.y, (float) cam.z, out, (int) (out.byteSize() / 4));
                int rq = (int) REQUESTS.invoke(REQ, 8192);
                if (rq > 0 && rq <= 8192) {
                    for (String line : new String(REQ.asSlice(0, rq).toArray(JAVA_BYTE), StandardCharsets.UTF_8).split("\n")) {
                        String[] parts = line.split("\\|");
                        if (parts.length == 5) requests.add(new String[] {parts[0], parts[1], parts[2], parts[3], parts[4], e.fx.ns});
                    }
                }
                if (n < 0) {
                    free(e.handle);
                    LIVE.remove(i);
                    continue;
                }
                int take = Math.min(n, Math.min(4096, budget));
                budget -= take;
                e.quads = take;
                if (e.data.length < take * QF) e.data = new float[take * QF];
                MemorySegment.copy(out, JAVA_FLOAT, 0, e.data, 0, take * QF);
            } catch (Throwable t) {
                free(e.handle);
                LIVE.remove(i);
            }
        }
        for (String[] r : requests) {
            double x = Double.parseDouble(r[2]), y = Double.parseDouble(r[3]), z = Double.parseDouble(r[4]);
            if (r[0].equals("0")) spawn(r[1], x, y, z);
            else playSound(r[1], r[5], x, y, z);
        }

        Quaternionf camRot = camera.orientation != null ? camera.orientation : new Quaternionf();
        PoseStack pose = new PoseStack();
        for (Emiter e : LIVE) {
            if (e.quads <= 0) continue;
            float[] d = e.data;
            int count = e.quads;
            boolean lit = e.fx.lit;
            collector.submitCustomGeometry(pose, e.fx.type, (snapshot, vc) -> {
                Vector3f a = new Vector3f(), b = new Vector3f(), r = new Vector3f(), u = new Vector3f();
                for (int q = 0; q < count; q++) {
                    int o = q * QF;
                    float x = (float) (d[o] - cam.x), y = (float) (d[o + 1] - cam.y), z = (float) (d[o + 2] - cam.z);
                    float hw = d[o + 3] / 1f, hh = d[o + 4] / 1f;
                    basis((int) d[o + 14], x, y, z, d[o + 15], d[o + 16], d[o + 17], camRot, r, u);
                    float rot = d[o + 5] * (float) (Math.PI / 180.0);
                    if (rot != 0f) {
                        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
                        a.set(r).mul(c).add(b.set(u).mul(s));
                        u.mul(c).sub(r.mul(s));
                        r.set(a);
                    }
                    int col = ((int) (Math.max(0, Math.min(1, d[o + 13])) * 255) << 24) | ((int) (Math.max(0, Math.min(1, d[o + 10])) * 255) << 16)
                        | ((int) (Math.max(0, Math.min(1, d[o + 11])) * 255) << 8) | (int) (Math.max(0, Math.min(1, d[o + 12])) * 255);
                    int light = lit ? LightCoordsUtil.getLightCoords(level, BlockPos.containing(d[o], d[o + 1], d[o + 2])) : LightCoordsUtil.FULL_BRIGHT;
                    float u0 = d[o + 6], v0 = d[o + 7], u1 = d[o + 8], v1 = d[o + 9];
                    vertex(snapshot.pose(), vc, x - r.x * hw - u.x * hh, y - r.y * hw - u.y * hh, z - r.z * hw - u.z * hh, col, u0, v1, light);
                    vertex(snapshot.pose(), vc, x + r.x * hw - u.x * hh, y + r.y * hw - u.y * hh, z + r.z * hw - u.z * hh, col, u1, v1, light);
                    vertex(snapshot.pose(), vc, x + r.x * hw + u.x * hh, y + r.y * hw + u.y * hh, z + r.z * hw + u.z * hh, col, u1, v0, light);
                    vertex(snapshot.pose(), vc, x - r.x * hw + u.x * hh, y - r.y * hw + u.y * hh, z - r.z * hw + u.z * hh, col, u0, v0, light);
                }
            });
        }
    }

    private static void vertex(org.joml.Matrix4f m, com.mojang.blaze3d.vertex.VertexConsumer vc, float x, float y, float z, int col, float u, float v, int light) {
        Vector3f p = m.transformPosition(x, y, z, new Vector3f());
        vc.addVertex(p.x, p.y, p.z, col, u, v, OverlayTexture.NO_OVERLAY, light, 0f, 1f, 0f);
    }

    // right/up axes of the quad for each facing mode from the particle docs
    private static void basis(int mode, float x, float y, float z, float dx, float dy, float dz, Quaternionf cam, Vector3f r, Vector3f u) {
        Vector3f toCam = new Vector3f(-x, -y, -z);
        if (toCam.lengthSquared() < 1e-8f) toCam.set(0, 0, 1);
        toCam.normalize();
        Vector3f dir = new Vector3f(dx, dy, dz);
        if (dir.lengthSquared() < 1e-8f) dir.set(0, 1, 0);
        dir.normalize();
        Vector3f up = new Vector3f(0, 1, 0);
        switch (mode) {
            case 1 -> { // rotate_y: camera yaw only
                cam.transform(r.set(1, 0, 0));
                r.y = 0;
                if (r.lengthSquared() < 1e-8f) r.set(1, 0, 0);
                r.normalize();
                u.set(0, 1, 0);
            }
            case 2 -> { // lookat_xyz
                r.set(up).cross(toCam);
                if (r.lengthSquared() < 1e-8f) r.set(1, 0, 0);
                r.normalize();
                u.set(toCam).cross(r).normalize();
            }
            case 3 -> { // lookat_y
                Vector3f flat = new Vector3f(toCam.x, 0, toCam.z);
                if (flat.lengthSquared() < 1e-8f) flat.set(0, 0, 1);
                flat.normalize();
                r.set(up).cross(flat).normalize();
                u.set(0, 1, 0);
            }
            case 4 -> { // direction_x
                r.set(dir);
                u.set(up).sub(new Vector3f(dir).mul(dir.dot(up)));
                if (u.lengthSquared() < 1e-8f) u.set(0, 0, 1);
                u.normalize();
            }
            case 5 -> { // direction_y
                u.set(dir);
                r.set(up).cross(dir);
                if (r.lengthSquared() < 1e-8f) r.set(1, 0, 0);
                r.normalize();
            }
            case 6 -> { // direction_z: face along the direction
                r.set(up).cross(dir);
                if (r.lengthSquared() < 1e-8f) r.set(1, 0, 0);
                r.normalize();
                u.set(dir).cross(r).normalize();
            }
            case 7 -> { r.set(1, 0, 0); u.set(0, 1, 0); }  // emitter xy
            case 8 -> { r.set(1, 0, 0); u.set(0, 0, 1); }  // emitter xz
            case 9 -> { r.set(0, 0, 1); u.set(0, 1, 0); }  // emitter yz
            case 10 -> { // lookat_direction: along the direction, turned toward the camera
                r.set(dir);
                u.set(dir).cross(toCam);
                if (u.lengthSquared() < 1e-8f) u.set(0, 1, 0);
                u.normalize();
            }
            default -> { // rotate_xyz: flat to the camera
                cam.transform(r.set(1, 0, 0));
                cam.transform(u.set(0, 1, 0));
            }
        }
    }

    private static void playSound(String name, String ns, double x, double y, double z) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        Identifier id = Identifier.tryParse(name.contains(":") ? name : ns + ":" + name.toLowerCase(java.util.Locale.ROOT));
        if (id == null) return;
        level.playLocalSound(x, y, z, SoundEvent.createVariableRangeEvent(id), SoundSource.NEUTRAL, 1f, 1f, false);
    }
}
