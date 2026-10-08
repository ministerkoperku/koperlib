package com.koper.koper_lib.kfx;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

public final class KfxRenderer {
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("koper_lib", "kfx/white");
    private static final Map<Long, EmitterState> EMITTERS = new HashMap<>();
    private static final Map<Long, KfxProgram> PROGRAMS = new HashMap<>();
    private static final Map<Long, Boolean> NATIVE_READY = new HashMap<>();
    // when GPU draws the particles, effects with only native-batch ops need zero CPU submit. cache the verdict
    private static final Map<Long, Boolean> NEEDS_CPU = new HashMap<>();
    private static final int STYLE_SPARK = 1;
    private static final int STYLE_STAR = 2;
    private static final int STYLE_RING = 3;
    private static final int STYLE_SHARD = 4;
    private static final int STYLE_CUBE = 5;
    private static final int STYLE_TETRA = 6;
    private static final int STYLE_ORB3D = 7;
    private static final int MOTION_FREE = 0;
    private static final int MOTION_ORBIT = 1;
    private static final int MOTION_INWARD = 2;
    private static final int MOTION_SWIRL = 3;
    private static final Particle TMP_PARTICLE = new Particle();
    private static boolean whiteUploaded;

    // data-driven 2D/3D pick per op/effect: auto+3d = real mesh, 2d = cheap billboard. set before particles draw
    private static final int DIM_AUTO = 0, DIM_2D = 1, DIM_3D = 2;
    private static int dimMode = DIM_AUTO;

    // set by the render mixin after a successful GPU particle draw. floor = CPU path always renders until proven.
    public static volatile boolean gpuDrewLastFrame = false;

    private KfxRenderer() {}

    // builtin ops wire their draw lambdas into the open registry. addons append their own.
    static {
        KfxOps.registerDraw("ring_particles", (ctx, op) ->
            drawProgramRingParticles(ctx.pose, ctx.consumer, ctx.fx, ctx.basis, op, ctx.eased, ctx.spin, ctx.opColor));
        KfxOps.registerDraw("pentagram_particles", (ctx, op) ->
            drawProgramPentagram(ctx.pose, ctx.consumer, ctx.fx, ctx.basis, op, ctx.eased, ctx.spin, ctx.fade));
        KfxOps.registerDraw("ring_band", (ctx, op) ->
            drawProgramRingBand(ctx.pose, ctx.consumer, ctx.basis, op, ctx.eased, ctx.spin, ctx.opColor));
        KfxOps.registerDraw("orb", (ctx, op) ->
            particleAt(ctx.pose, ctx.consumer, op.style, ctx.basis, op.x, op.y, op.z,
                op.size > 0.0f ? op.size : ctx.fx.thickness, ctx.opColor, ctx.fx.id + op.seed));
        KfxOps.registerDraw("beam", (ctx, op) -> {
            if (ctx.age >= op.from) drawBeam3D(ctx.pose, ctx.consumer, ctx.fx,
                ctx.pulse * Math.max(0.05f, op.thickness <= 0.0f ? 1.0f : op.thickness / Math.max(0.01f, ctx.fx.thickness)),
                ctx.fade * ctx.eased * op.alpha, squareBeam(op.style));
        });
        KfxOps.registerDraw("burst_ring", (ctx, op) ->
            drawProgramBurstRing(ctx.pose, ctx.consumer, ctx.fx, ctx.basis, op, ctx.eased, ctx.spin, ctx.opColor));
        KfxOps.registerDraw("stream", (ctx, op) ->
            drawProgramStream(ctx.pose, ctx.consumer, ctx.fx, ctx.basis, op, ctx.eased, ctx.opColor));
        KfxOps.registerDraw("spiral", (ctx, op) ->
            drawProgramSpiral(ctx.pose, ctx.consumer, ctx.fx, ctx.basis, op, ctx.eased, ctx.spin, ctx.opColor));
        KfxOps.registerDraw("ribbon", (ctx, op) -> {
            if (ctx.age >= op.from) drawBeam3D(ctx.pose, ctx.consumer, ctx.fx,
                Math.max(0.35f, op.thickness / Math.max(0.01f, ctx.fx.thickness)), ctx.fade * op.alpha, false);
        });
        KfxOps.registerDraw("trail", (ctx, op) -> {
            if (ctx.age >= op.from) drawBeam3D(ctx.pose, ctx.consumer, ctx.fx,
                Math.max(0.2f, op.thickness / Math.max(0.01f, ctx.fx.thickness)), ctx.fade * op.alpha, false);
        });
        KfxOps.registerDraw("mesh", (ctx, op) ->
            particleAt(ctx.pose, ctx.consumer, STYLE_ORB3D, ctx.basis, op.x, op.y, op.z,
                op.size > 0 ? op.size : Math.max(0.2f, ctx.fx.radius), ctx.opColor, ctx.fx.id + op.seed));
        KfxOps.registerDraw("decal", (ctx, op) ->
            drawProgramRingBand(ctx.pose, ctx.consumer, ctx.basis, op, ctx.eased, ctx.spin, ctx.opColor));
        KfxOps.registerDraw("light", (ctx, op) -> {});
        KfxOps.registerDraw("group", (ctx, op) -> {});
    }

    public static void clearRuntime() {
        EMITTERS.clear();
        PROGRAMS.clear();
        NATIVE_READY.clear();
        NEEDS_CPU.clear();
        gpuDrewLastFrame = false;
    }

    // drop the parsed program + emitter state, but NOT native-ready (spawn sets that a moment earlier)
    static void dropProgram(long id) {
        EMITTERS.remove(id);
        PROGRAMS.remove(id);
        NEEDS_CPU.remove(id);
    }

    static void dropRuntime(long id) {
        EMITTERS.remove(id);
        PROGRAMS.remove(id);
        NATIVE_READY.remove(id);
        NEEDS_CPU.remove(id);
    }

    // The portable path renders the tier the client selected, using the same core/decorative decision
    // the native backend applies through its particle budget.
    private static KfxProgram parseForQuality(KfxInstance fx) {
        return KfxProgram.parse(com.koper.koper_lib.kfx.render.KfxQualityPlan.apply(
            fx.programJson, com.koper.koper_lib.kfx.render.KfxQuality.configured()));
    }

    // effect has CPU work only if it's not a pure native-batch program (emitter/demo kinds, or beam/ring_band ops)
    private static boolean needsCpuDraw(KfxInstance fx) {
        if (fx.programJson.isBlank()) return true;
        Boolean cached = NEEDS_CPU.get(fx.id);
        if (cached != null) return cached;
        KfxProgram p = PROGRAMS.computeIfAbsent(fx.id, id -> parseForQuality(fx));
        boolean needs = false;
        for (KfxProgram.Op op : p.ops) if (!KfxOps.nativeBatch(op.op)) { needs = true; break; }
        NEEDS_CPU.put(fx.id, needs);
        return needs;
    }

    private static boolean gpuOwnsNativeParticles() {
        return gpuDrewLastFrame
            && com.koper.koper_lib.kfx.EffectsConfig.get().kenderVulkanParticles
            && com.koper.koper_lib.api.core.KenderGeoBridge.vulkanActive();
    }

    public static void nativeReady(long id, boolean ready) {
        if (ready) NATIVE_READY.put(id, true);
        else NATIVE_READY.remove(id);
    }

    private static boolean nativeReady(long id) {
        return NATIVE_READY.getOrDefault(id, false);
    }

    public static void init() {
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (!uploadWhiteTexture()) return;
            Vec3 cam = mc.gameRenderer.mainCamera().position();
            PoseStack poseStack = ctx.poseStack();
            // The previous pass has to prove it drew. Device-ready alone is not enough or native ops vanish.
            boolean gpu = gpuOwnsNativeParticles();

            for (KfxInstance fx : KfxClient.live()) {
                if (gpu && nativeReady(fx.id)) {
                    // emitter sim + draw runs fully in Rust (rayon); program particles in the Rust gather. nothing for CPU
                    if (fx.kind == KfxDef.Kind.EMITTER) continue;
                    if (!needsCpuDraw(fx)) continue;
                }
                poseStack.pushPose();
                poseStack.translate(fx.sx - cam.x, fx.sy - cam.y, fx.sz - cam.z);
                ctx.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(WHITE),
                    (pose, consumer) -> drawFx(pose, consumer, fx));
                poseStack.popPose();
            }
        });
    }

    private static boolean uploadWhiteTexture() {
        if (whiteUploaded) return true;
        try {
            NativeImage img = new NativeImage(1, 1, false);
            img.setPixel(0, 0, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager().register(WHITE, new DynamicTexture(() -> "kfx/white", img));
            whiteUploaded = true;
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static void drawFx(PoseStack.Pose pose, VertexConsumer consumer, KfxInstance fx) {
        dimMode = DIM_AUTO;
        float age = fx.ageTicks();
        float spin = (float)Math.toRadians(age * fx.spinY);
        float pulse = 1.0f + (float)Math.sin(age * 0.31415927f * fx.pulseSpeed) * fx.pulseAmount;
        float fade = fade(fx, age);
        if (!fx.programJson.isBlank()) {
            drawProgram(pose, consumer, fx, age, spin, pulse, fade);
            return;
        }
        switch (fx.kind) {
            case BEAM -> drawBeam(pose, consumer, fx, pulse, fade);
            case SPHERE -> drawSphere(pose, consumer, fx, spin, pulse, fade);
            case PARTICLE -> drawParticle(pose, consumer, fx, spin, pulse, fade);
            case VORTEX -> drawVortex(pose, consumer, fx, spin, pulse, fade);
            case EMITTER -> drawEmitter(pose, consumer, fx, age, fade);
            case CHARGE_BEAM -> drawChargeBeam(pose, consumer, fx, age, spin, pulse, fade);
            default -> drawRing(pose, consumer, fx, spin, pulse, fade);
        }
    }

    static void drawBeam(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float widthMul, float fade) {
        drawBeam3D(pose, c, fx, widthMul, fade, false);
    }

    // real 3D laser: oriented tube (round) or prism (square) start->end, variable width/length + bright core
    static void drawBeam3D(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float widthMul, float fade, boolean square) {
        float lx = fx.ex - fx.sx, ly = fx.ey - fx.sy, lz = fx.ez - fx.sz;
        float len = (float)Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 1.0e-4f) return;
        float fxn = lx / len, fyn = ly / len, fzn = lz / len;
        float r = Math.max(0.01f, fx.thickness * widthMul);
        int segs = square ? 4 : 14;
        tubeMesh(pose, c, fxn, fyn, fzn, len, r, segs, square, alpha(fx.color, fade * 0.72f));
        tubeMesh(pose, c, fxn, fyn, fzn, len, r * 0.45f, segs, square, alpha(fx.color2, fade));
    }

    private static boolean squareBeam(String s) {
        if (s == null) return false;
        return switch (s.toLowerCase()) {
            case "box", "square", "cube", "rect", "prism" -> true;
            default -> false;
        };
    }

    private static void drawRing(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float spin, float pulse, float fade) {
        int steps = 64;
        float r = fx.radius * pulse;
        float half = Math.max(0.035f, fx.thickness * 0.5f);
        int edge = alpha(fx.color, fade * 0.74f);
        int core = alpha(fx.color2, fade);
        for (int i = 0; i < steps; i++) {
            float a0 = spin + (float)(Math.PI * 2.0 * i / steps);
            float a1 = spin + (float)(Math.PI * 2.0 * (i + 1) / steps);
            ringBand(pose, c, a0, a1, r - half, r + half, edge);
            verticalBandX(pose, c, a0 + 0.7f, a1 + 0.7f, r - half * 0.72f, r + half * 0.72f, alpha(fx.color, fade * 0.46f));
            if ((i & 1) == 0) ringBand(pose, c, a0, a1, r - half * 0.32f, r + half * 0.32f, core);
        }
    }

    private static void drawSphere(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float spin, float pulse, float fade) {
        float r = fx.radius * pulse;
        sphereMesh(pose, c, 0, 0, 0, r, 18, 26, alpha(fx.color, fade * 0.88f));
        sphereMesh(pose, c, 0, 0, 0, r * 0.6f, 10, 16, alpha(fx.color2, fade * 0.5f));
    }

    private static void drawParticle(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float spin, float pulse, float fade) {
        float r = fx.radius * pulse;
        sphereMesh(pose, c, 0, 0, 0, r, 12, 18, alpha(fx.color, fade));
    }

    private static void drawEmitter(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float age, float fade) {
        EmitterState st = EMITTERS.computeIfAbsent(fx.id, id -> new EmitterState(id));
        st.tick(fx, age);
        st.prune();
        int style = styleCode(fx.particleStyle);
        for (Particle p : st.particles) {
            float t = p.age / Math.max(1.0f, p.life);
            float size = lerp(p.size, fx.sizeEnd, t);
            float alpha = fade * (1.0f - t) * Math.min(1.0f, p.age / 4.0f);
            int color = mix(fx.color2, fx.color, t);
            color = alpha(color, alpha);
            drawParticleStyle(pose, c, style, p, size, color);
        }
    }


    private static void drawParticleStyle(PoseStack.Pose pose, VertexConsumer c, int style, Particle p, float size, int color) {
        boolean d3 = want3D();
        switch (style) {
            case STYLE_SPARK -> sparkParticle(pose, c, p, size, color);
            case STYLE_STAR -> { if (d3) starParticle(pose, c, p.x, p.y, p.z, size, color); else starFlat(pose, c, p.x, p.y, p.z, size, color); }
            case STYLE_RING -> ringParticle(pose, c, p.x, p.y, p.z, size, color);
            case STYLE_SHARD -> shardParticle(pose, c, p.x, p.y, p.z, size, color, p.seed);
            case STYLE_CUBE -> { if (d3) cubeParticle(pose, c, p.x, p.y, p.z, size, color); else billboardParticle(pose, c, p.x, p.y, p.z, size, color); }
            case STYLE_TETRA -> { if (d3) tetraParticle(pose, c, p.x, p.y, p.z, size, color, p.seed); else billboardParticle(pose, c, p.x, p.y, p.z, size, color); }
            case STYLE_ORB3D -> { if (d3) octaParticle(pose, c, p.x, p.y, p.z, size, color); else billboardParticle(pose, c, p.x, p.y, p.z, size, color); }
            default -> {
                KfxStyle addon = KfxStyles.drawer(style);
                if (addon != null) addon.draw(pose, c, p.x, p.y, p.z, size, color, p.seed);
                else spriteParticle(pose, c, p.x, p.y, p.z, size, color);
            }
        }
    }

    // CPU path defaults to cheap billboards; 3D mesh only when an op/effect explicitly asks "dim":"3d".
    // (the GPU instancer path draws real 3D octahedra cheaply — that's where 3D belongs by default.)
    private static boolean want3D() {
        // auto means 3d, like the docs say. it used to mean flat, so every orb3d/cube/tetra
        // that didn't spell out dim="3d" came out as a billboard square
        return dimMode != DIM_2D;
    }

    private static int dimCode(String dim) {
        if (dim == null) return DIM_AUTO;
        return switch (dim.toLowerCase()) {
            case "2d", "flat", "billboard", "sprite" -> DIM_2D;
            case "3d", "mesh", "solid" -> DIM_3D;
            default -> DIM_AUTO;
        };
    }

    // cheap 8-tri octahedron — the 3D orb. reads as a faceted gem at particle scale, ~6x lighter than a UV sphere
    private static void octaParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color) {
        float t = s * 1.25f;
        float[] eq = {1, 0, 0, 1, -1, 0, 0, -1};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            float ax = eq[i * 2] * t, az = eq[i * 2 + 1] * t;
            float bx = eq[j * 2] * t, bz = eq[j * 2 + 1] * t;
            tri(pose, c, x, y + t, z, x + ax, y, z + az, x + bx, y, z + bz, shade(color, ax + bx, t, az + bz));
            tri(pose, c, x, y - t, z, x + bx, y, z + bz, x + ax, y, z + az, shade(color, ax + bx, -t, az + bz));
        }
    }

    // single flat quad — cheapest particle, the 2D fallback for mesh styles
    private static void billboardParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color) {
        quad(pose, c, x - s, y - s, z, x + s, y - s, z, x + s, y + s, z, x - s, y + s, z, color);
    }

    // flat 4-spoke star, the 2D star
    private static void starFlat(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color) {
        float a = s * 1.8f, b = s * 0.28f;
        quad(pose, c, x - a, y - b, z, x + a, y - b, z, x + a, y + b, z, x - a, y + b, z, color);
        quad(pose, c, x - b, y - a, z, x + b, y - a, z, x + b, y + a, z, x - b, y + a, z, alpha(color, 0.85f));
    }

    private static int styleCode(String raw) {
        return KfxStyles.codeFor(raw);
    }

    private static int motionCode(String raw) {
        if (raw == null) return MOTION_FREE;
        return switch (raw.toLowerCase()) {
            case "orbit", "circle" -> MOTION_ORBIT;
            case "inward", "implode", "center" -> MOTION_INWARD;
            case "swirl", "vortex" -> MOTION_SWIRL;
            default -> MOTION_FREE;
        };
    }

    static void spriteParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color) {
        quad(pose, c, x - s, y - s, z, x + s, y - s, z, x + s, y + s, z, x - s, y + s, z, color);
        quad(pose, c, x, y - s, z - s, x, y - s, z + s, x, y + s, z + s, x, y + s, z - s, alpha(color, 0.72f));
    }

    private static void sparkParticle(PoseStack.Pose pose, VertexConsumer c, Particle p, float s, int color) {
        Vector3f dir = new Vector3f(p.x - p.px, p.y - p.py, p.z - p.pz);
        if (dir.lengthSquared() < 1.0e-5f) dir.set(p.vx, p.vy, p.vz);
        if (dir.lengthSquared() < 1.0e-5f) dir.set(0, 1, 0);
        dir.normalize(Math.max(s * 4.0f, 0.08f));
        Vector3f side = new Vector3f(-dir.z, 0.0f, dir.x);
        if (side.lengthSquared() < 1.0e-5f) side.set(1, 0, 0);
        side.normalize(Math.max(s * 0.34f, 0.012f));
        float tx = p.x - dir.x, ty = p.y - dir.y, tz = p.z - dir.z;
        float hx = p.x + dir.x * 0.45f, hy = p.y + dir.y * 0.45f, hz = p.z + dir.z * 0.45f;
        quad(pose, c,
            tx - side.x, ty - side.y, tz - side.z,
            tx + side.x, ty + side.y, tz + side.z,
            hx + side.x, hy + side.y, hz + side.z,
            hx - side.x, hy - side.y, hz - side.z, color);
        spriteParticle(pose, c, p.x, p.y, p.z, s * 0.42f, alpha(color, 0.82f));
    }

    private static void starParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color) {
        beamlet(pose, c, x, y, z, s * 2.6f, s * 0.20f, 1, 0, 0, color);
        beamlet(pose, c, x, y, z, s * 2.2f, s * 0.18f, 0, 1, 0, alpha(color, 0.84f));
        beamlet(pose, c, x, y, z, s * 2.2f, s * 0.18f, 0, 0, 1, alpha(color, 0.76f));
        spriteParticle(pose, c, x, y, z, s * 0.38f, color);
    }

    private static void beamlet(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z,
                                float len, float half, float ax, float ay, float az, int color) {
        float bx = ay != 0.0f ? 1.0f : 0.0f;
        float by = ax != 0.0f || az != 0.0f ? 1.0f : 0.0f;
        quad(pose, c,
            x - ax * len - bx * half, y - ay * len - by * half, z - az * len,
            x - ax * len + bx * half, y - ay * len + by * half, z - az * len,
            x + ax * len + bx * half, y + ay * len + by * half, z + az * len,
            x + ax * len - bx * half, y + ay * len - by * half, z + az * len, color);
    }

    private static void ringParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color) {
        int steps = 12;
        float inner = s * 1.15f;
        float outer = s * 1.55f;
        for (int i = 0; i < steps; i++) {
            float a0 = (float)(Math.PI * 2.0 * i / steps);
            float a1 = (float)(Math.PI * 2.0 * (i + 1) / steps);
            quad(pose, c,
                x + (float)Math.cos(a0) * inner, y, z + (float)Math.sin(a0) * inner,
                x + (float)Math.cos(a0) * outer, y, z + (float)Math.sin(a0) * outer,
                x + (float)Math.cos(a1) * outer, y, z + (float)Math.sin(a1) * outer,
                x + (float)Math.cos(a1) * inner, y, z + (float)Math.sin(a1) * inner, color);
        }
        spriteParticle(pose, c, x, y, z, s * 0.32f, alpha(color, 0.65f));
    }

    private static void shardParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color, float seed) {
        float twist = seed * 0.017453292f;
        float ax = (float)Math.cos(twist) * s * 1.7f;
        float az = (float)Math.sin(twist) * s * 1.7f;
        float h = s * 2.4f;
        quad(pose, c, x, y + h, z, x + ax, y, z + az, x, y - h, z, x - ax, y, z - az, color);
        quad(pose, c, x, y + h * 0.72f, z, x - az * 0.65f, y, z + ax * 0.65f,
            x, y - h * 0.72f, z, x + az * 0.65f, y, z - ax * 0.65f, alpha(color, 0.72f));
    }

    private static void cubeParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color) {
        float a = s * 0.9f;
        int side = alpha(color, 0.62f);
        quad(pose, c, x - a, y - a, z - a, x + a, y - a, z - a, x + a, y + a, z - a, x - a, y + a, z - a, side);
        quad(pose, c, x + a, y - a, z + a, x - a, y - a, z + a, x - a, y + a, z + a, x + a, y + a, z + a, color);
        quad(pose, c, x - a, y - a, z + a, x - a, y - a, z - a, x - a, y + a, z - a, x - a, y + a, z + a, side);
        quad(pose, c, x + a, y - a, z - a, x + a, y - a, z + a, x + a, y + a, z + a, x + a, y + a, z - a, color);
        quad(pose, c, x - a, y + a, z - a, x + a, y + a, z - a, x + a, y + a, z + a, x - a, y + a, z + a, alpha(color, 0.82f));
        quad(pose, c, x - a, y - a, z + a, x + a, y - a, z + a, x + a, y - a, z - a, x - a, y - a, z - a, alpha(color, 0.42f));
    }

    private static void tetraParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color, float seed) {
        float r = s * 1.55f;
        float twist = seed * 0.011f;
        float ax = (float)Math.cos(twist) * r, az = (float)Math.sin(twist) * r;
        float bx = (float)Math.cos(twist + 2.0943952f) * r, bz = (float)Math.sin(twist + 2.0943952f) * r;
        float cx = (float)Math.cos(twist + 4.1887903f) * r, cz = (float)Math.sin(twist + 4.1887903f) * r;
        float top = y + s * 1.8f;
        float bot = y - s * 0.85f;
        tri(pose, c, x, top, z, x + ax, bot, z + az, x + bx, bot, z + bz, color);
        tri(pose, c, x, top, z, x + bx, bot, z + bz, x + cx, bot, z + cz, alpha(color, 0.82f));
        tri(pose, c, x, top, z, x + cx, bot, z + cz, x + ax, bot, z + az, alpha(color, 0.68f));
        tri(pose, c, x + ax, bot, z + az, x + cx, bot, z + cz, x + bx, bot, z + bz, alpha(color, 0.52f));
    }

    private static void orb3dParticle(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float s, int color, float seed) {
        sphereMesh(pose, c, x, y, z, s * 1.2f, 4, 6, color);
    }

    private static void drawProgram(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float age, float spin, float pulse, float fade) {
        KfxProgram program = PROGRAMS.computeIfAbsent(fx.id, id -> parseForQuality(fx));
        KfxBasis basis = ritualBasis(fx);
        // GPU path draws all native-batch particle ops via Kender instancing — skip the CPU draw entirely.
        // beam/ring_band are geometry, not in the particle batch, so they still draw here.
        boolean gpu = gpuOwnsNativeParticles();
        boolean nativeParticles = gpu || drawNativeProgramParticles(pose, c, fx, age, fade);
        KfxDrawCtx ctx = new KfxDrawCtx(pose, c, fx, basis, age, spin, pulse, fade);
        for (KfxProgram.Op op : program.ops) {
            if (nativeParticles && KfxOps.nativeBatch(op.op)) continue;
            float t = op.progress(age);
            if (t <= 0.0f && age < op.from) continue;
            if (!op.hold && age > op.to) continue;
            ctx.eased = ease(t, op.ease);
            ctx.opColor = alpha(op.color != 0 ? op.color : fx.color, fade * op.alpha);
            dimMode = dimCode(op.dim);
            KfxOp handler = KfxOps.draw(op.op);
            if (handler != null) handler.draw(ctx, op);
        }
    }

    private static boolean drawNativeProgramParticles(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float age, float fade) {
        MemorySegment batch = com.koper.koper_lib.kender.KenderBridge.kfxEvalDirect(fx.id, age);
        if (batch == null) return false;
        long floats = batch.byteSize() / Float.BYTES;
        if (floats < 10) return false;
        long count = floats / 10;
        for (long i = 0; i < count; i++) {
            long base = i * 10L;
            float x = batch.get(JAVA_FLOAT, (base) * Float.BYTES);
            float y = batch.get(JAVA_FLOAT, (base + 1) * Float.BYTES);
            float z = batch.get(JAVA_FLOAT, (base + 2) * Float.BYTES);
            float size = batch.get(JAVA_FLOAT, (base + 3) * Float.BYTES);
            float r = batch.get(JAVA_FLOAT, (base + 4) * Float.BYTES);
            float g = batch.get(JAVA_FLOAT, (base + 5) * Float.BYTES);
            float b = batch.get(JAVA_FLOAT, (base + 6) * Float.BYTES);
            float a = batch.get(JAVA_FLOAT, (base + 7) * Float.BYTES);
            int style = Math.round(batch.get(JAVA_FLOAT, (base + 8) * Float.BYTES));
            float seed = batch.get(JAVA_FLOAT, (base + 9) * Float.BYTES);
            drawParticleStyle(pose, c, style, TMP_PARTICLE.set(x, y, z, seed), size, rgba(r, g, b, a * fade));
        }
        return true;
    }

    private static void drawProgramRingParticles(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, KfxBasis basis,
                                                 KfxProgram.Op op, float progress, float spin, int color) {
        int count = Math.max(1, op.count > 0 ? op.count : Math.max(64, fx.emitterBurst));
        int visible = Math.max(1, Math.min(count, (int)(count * progress)));
        float r = op.radius > 0.0f ? op.radius : fx.radius * fx.ringScale;
        float size = op.size > 0.0f ? op.size : Math.max(0.025f, fx.thickness * 0.34f);
        int style = styleCode(op.style);
        for (int i = 0; i < visible; i++) {
            float lane = i / (float)count;
            float appear = "center_out".equals(op.build) ? smooth(Math.max(0.0f, Math.min(1.0f, progress * count - i))) : progress;
            float a = lane * (float)Math.PI * 2.0f + spin * op.spin;
            float wobble = (float)Math.sin(i * 1.7f + spin * 2.0f) * op.wobble;
            float x = (float)Math.cos(a) * (r + wobble) * appear;
            float z = (float)Math.sin(a) * (r + wobble) * appear;
            float y = (float)Math.sin(i * 0.61f + spin) * op.depth * appear;
            particleAt(pose, c, style, basis, x, y, z, size * (0.7f + appear * 0.5f), color, fx.id + i * 31.0f + op.seed);
        }
    }

    private static void drawProgramPentagram(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, KfxBasis basis,
                                             KfxProgram.Op op, float progress, float spin, float fade) {
        float r = op.radius > 0.0f ? op.radius : fx.radius * fx.ringScale * fx.sigilScale;
        int corners = Math.clamp(op.points, 3, 16);
        int step = Math.clamp(op.skip, 1, Math.max(1, corners / 2));
        float[][] p = new float[corners][2];
        float rot = spin * op.spin - (float)Math.PI * 0.5f;
        for (int i = 0; i < corners; i++) {
            float a = rot + i * (float)Math.PI * 2.0f / corners;
            p[i][0] = (float)Math.cos(a) * r;
            p[i][1] = (float)Math.sin(a) * r;
        }
        // A star polygon {corners/step}: every vertex hands off to the one `step` places along.
        int[][] lines = new int[corners][2];
        for (int i = 0; i < corners; i++) lines[i] = new int[]{i, (i + step) % corners};
        int totalDots = Math.max(15, op.count > 0 ? op.count : 90);
        int visibleDots = Math.max(1, (int)(totalDots * progress));
        int style = styleCode(op.style);
        int drawn = 0;
        for (int[] line : lines) {
            int dots = Math.max(1, totalDots / lines.length);
            for (int i = 0; i < dots && drawn < visibleDots; i++, drawn++) {
                float t = dots <= 1 ? 0.0f : i / (float)(dots - 1);
                float x = lerp(p[line[0]][0], p[line[1]][0], t);
                float z = lerp(p[line[0]][1], p[line[1]][1], t);
                int color = alpha(op.color != 0 ? op.color : fx.color2, fade * op.alpha);
                particleAt(pose, c, style, basis, x, op.depth, z,
                    op.size > 0.0f ? op.size : Math.max(0.025f, fx.thickness * 0.32f),
                    color, fx.id + drawn * 13.0f + op.seed);
            }
        }
    }

    private static void drawProgramRingBand(PoseStack.Pose pose, VertexConsumer c, KfxBasis basis,
                                            KfxProgram.Op op, float progress, float spin, int color) {
        int steps = Math.max(8, op.count > 0 ? op.count : 96);
        float r = op.radius;
        float half = Math.max(0.005f, op.thickness * 0.5f);
        for (int i = 0; i < steps; i++) {
            float a0 = spin * op.spin + (float)(Math.PI * 2.0 * i / steps);
            float a1 = spin * op.spin + (float)(Math.PI * 2.0 * (i + 1) / steps);
            orientedRingBand(pose, c, basis, a0, a1, r - half, r + half, alpha(color, progress));
        }
    }

    private static void drawProgramBurstRing(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, KfxBasis basis,
                                             KfxProgram.Op op, float progress, float spin, int color) {
        int count = Math.max(8, op.count > 0 ? op.count : 96);
        float start = op.radius > 0.0f ? op.radius : fx.radius * 0.35f;
        float end = op.radiusTo > 0.0f ? op.radiusTo : Math.max(start + 0.1f, fx.radius * fx.ringScale);
        float r = lerp(start, end, progress);
        float size = op.size > 0.0f ? op.size : Math.max(0.02f, fx.thickness * (1.0f - progress * 0.55f));
        int style = styleCode(op.style);
        for (int i = 0; i < count; i++) {
            float lane = i / (float)count;
            float a = lane * (float)Math.PI * 2.0f + spin * op.spin;
            float wave = (float)Math.sin(progress * 9.0f + i * 1.31f + op.seed) * op.wobble;
            particleAt(pose, c, style, basis,
                (float)Math.cos(a) * (r + wave),
                op.depth * (progress - 0.5f),
                (float)Math.sin(a) * (r + wave),
                size, alpha(color, 1.0f - progress * 0.45f), fx.id + i * 17.0f + op.seed);
        }
    }

    private static void drawProgramStream(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, KfxBasis basis,
                                          KfxProgram.Op op, float progress, int color) {
        int count = Math.max(2, op.count > 0 ? op.count : 48);
        int visible = Math.max(1, Math.min(count, (int)(count * progress)));
        int style = styleCode(op.style);
        float size = op.size > 0.0f ? op.size : Math.max(0.018f, fx.thickness * 0.28f);
        for (int i = 0; i < visible; i++) {
            float t = i / (float)Math.max(1, count - 1);
            float wob = (float)Math.sin(i * 1.43f + op.seed) * op.wobble;
            particleAt(pose, c, style, basis,
                op.x + wob,
                lerp(0.0f, 1.0f, t) * op.speed,
                op.z + (float)Math.cos(i * 1.17f + op.seed) * op.wobble,
                size * (1.0f - t * 0.35f), alpha(color, 1.0f - t * 0.6f), fx.id + i * 23.0f + op.seed);
        }
    }

    private static void drawProgramSpiral(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, KfxBasis basis,
                                          KfxProgram.Op op, float progress, float spin, int color) {
        int count = Math.max(8, op.count > 0 ? op.count : 120);
        int visible = Math.max(1, Math.min(count, (int)(count * progress)));
        int style = styleCode(op.style);
        float radius = op.radius > 0.0f ? op.radius : fx.radius * fx.ringScale;
        float height = op.depth != 0.0f ? op.depth : fx.radius * 1.5f;
        float size = op.size > 0.0f ? op.size : Math.max(0.018f, fx.thickness * 0.30f);
        for (int i = 0; i < visible; i++) {
            float t = i / (float)Math.max(1, count - 1);
            float a = t * (float)Math.PI * 2.0f * Math.max(1.0f, op.speed) + spin * op.spin;
            float r = radius * ("center_out".equals(op.build) ? t : 1.0f);
            particleAt(pose, c, style, basis,
                (float)Math.cos(a) * r,
                (t - 0.5f) * height,
                (float)Math.sin(a) * r,
                size, alpha(color, 0.4f + 0.6f * progress), fx.id + i * 29.0f + op.seed);
        }
    }

    private static void drawChargeBeam(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float age, float spin, float pulse, float fade) {
        KfxBasis basis = ritualBasis(fx);
        float warmup = Math.max(8.0f, fx.timelineWarmup > 0.0f ? fx.timelineWarmup : fx.particleLifetime);
        float circleT = smooth(Math.min(1.0f, age / (warmup * 0.58f)));
        float sigilT = smooth(Math.max(0.0f, Math.min(1.0f, (age - warmup * 0.34f) / (warmup * 0.42f))));
        float beamT = smooth(Math.max(0.0f, Math.min(1.0f, (age - warmup) / Math.max(1.0f, fx.timelineBeamTime))));
        int count = Math.max(48, fx.emitterBurst > 0 ? fx.emitterBurst : 120);
        float ringR = fx.radius * (Math.max(0.4f, fx.ringScale) + pulse * 0.08f);
        float dotSize = Math.max(0.035f, fx.thickness * 0.34f);
        int visible = Math.max(1, Math.min(count, (int)(count * circleT)));

        particleAt(pose, c, fx.particleStyle, basis, 0, 0, 0,
            dotSize * (1.2f + circleT * 0.8f), alpha(fx.color2, fade * (1.0f - circleT * 0.45f)), fx.id);

        for (int i = 0; i < visible; i++) {
            float lane = i / (float)count;
            float appear = smooth(Math.max(0.0f, Math.min(1.0f, circleT * count - i)));
            float a = lane * (float)Math.PI * 2.0f + spin * 0.18f;
            float wobble = (float)Math.sin(age * 0.15f + i * 1.7f) * fx.thickness * 0.5f;
            float tx = (float)Math.cos(a) * (ringR + wobble);
            float tz = (float)Math.sin(a) * (ringR + wobble);
            float x = tx * appear;
            float z = tz * appear;
            float y = (float)Math.sin(i * 0.61f + age * 0.08f) * fx.thickness * 0.35f * appear;
            int color = alpha(mix(fx.color2, fx.color, lane), fade * (0.28f + appear * 0.72f));
            particleAt(pose, c, fx.particleStyle, basis, x, y, z, dotSize * (0.72f + appear * 0.58f), color, fx.id + i * 31.0f);
        }

        if (sigilT > 0.0f) {
            drawPentagramParticles(pose, c, fx, basis, ringR * Math.max(0.1f, fx.sigilScale), sigilT, spin, fade);
        }

        if (circleT > 0.82f) {
            int steps = 96;
            for (int i = 0; i < steps; i++) {
                float a0 = spin * -0.35f + (float)(Math.PI * 2.0 * i / steps);
                float a1 = spin * -0.35f + (float)(Math.PI * 2.0 * (i + 1) / steps);
                orientedRingBand(pose, c, basis, a0, a1, ringR - fx.thickness * 0.16f, ringR + fx.thickness * 0.16f,
                    alpha(fx.color, fade * 0.18f * circleT));
            }
        }

        if (beamT > 0.0f) {
            drawBeam(pose, c, fx, pulse * (0.62f + beamT * 0.9f), fade * beamT);
            particleAt(pose, c, "orb3d", basis, 0, 0, 0, fx.thickness * (2.4f + beamT * 1.8f),
                alpha(fx.color2, fade * (0.92f - beamT * 0.2f)), fx.id + age);
        }
    }

    private static void drawPentagramParticles(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx,
                                               KfxBasis basis, float r, float progress, float spin, float fade) {
        float[][] p = new float[5][2];
        float rot = spin * 0.24f - (float)Math.PI * 0.5f;
        for (int i = 0; i < 5; i++) {
            float a = rot + i * (float)Math.PI * 2.0f / 5.0f;
            p[i][0] = (float)Math.cos(a) * r;
            p[i][1] = (float)Math.sin(a) * r;
        }
        int[][] lines = {{0, 2}, {2, 4}, {4, 1}, {1, 3}, {3, 0}};
        int totalDots = 90;
        int visibleDots = Math.max(1, (int)(totalDots * progress));
        int drawn = 0;
        for (int[] line : lines) {
            int dots = totalDots / lines.length;
            for (int i = 0; i < dots && drawn < visibleDots; i++, drawn++) {
                float t = dots <= 1 ? 0.0f : i / (float)(dots - 1);
                float x = lerp(p[line[0]][0], p[line[1]][0], t);
                float z = lerp(p[line[0]][1], p[line[1]][1], t);
                float s = Math.max(0.025f, fx.thickness * (0.22f + 0.20f * progress));
                int color = alpha(fx.color2, fade * (0.32f + progress * 0.62f));
                particleAt(pose, c, "star", basis, x, 0.018f, z, s, color, fx.id + drawn * 13.0f);
            }
        }
        particleAt(pose, c, "orb3d", basis, 0, 0.02f, 0, fx.thickness * (0.8f + progress * 0.9f),
            alpha(fx.color2, fade * progress), fx.id + 7.0f);
    }

    private static KfxBasis ritualBasis(KfxInstance fx) {
        Vector3f forward = new Vector3f(fx.ex - fx.sx, fx.ey - fx.sy, fx.ez - fx.sz);
        if (forward.lengthSquared() < 1.0e-5f) forward.set(0, 0, 1);
        forward.normalize();
        Vector3f side = new Vector3f();
        new Vector3f(0, 1, 0).cross(forward, side);
        if (side.lengthSquared() < 1.0e-5f) side.set(1, 0, 0);
        side.normalize();
        Vector3f up = new Vector3f();
        forward.cross(side, up).normalize();
        return new KfxBasis(side, up, forward);
    }

    private static void orientedRingBand(PoseStack.Pose pose, VertexConsumer c, KfxBasis b,
                                         float a0, float a1, float inner, float outer, int color) {
        float c0 = (float)Math.cos(a0), s0 = (float)Math.sin(a0);
        float c1 = (float)Math.cos(a1), s1 = (float)Math.sin(a1);
        float x0 = c0 * inner, z0 = s0 * inner;
        float x1 = c0 * outer, z1 = s0 * outer;
        float x2 = c1 * outer, z2 = s1 * outer;
        float x3 = c1 * inner, z3 = s1 * inner;
        quad(pose, c,
            rx(b, x0, 0, z0), ry(b, x0, 0, z0), rz(b, x0, 0, z0),
            rx(b, x1, 0, z1), ry(b, x1, 0, z1), rz(b, x1, 0, z1),
            rx(b, x2, 0, z2), ry(b, x2, 0, z2), rz(b, x2, 0, z2),
            rx(b, x3, 0, z3), ry(b, x3, 0, z3), rz(b, x3, 0, z3), color);
    }

    private static void particleAt(PoseStack.Pose pose, VertexConsumer c, String style, float x, float y, float z,
                                   float size, int color, float seed) {
        particleAt(pose, c, styleCode(style), x, y, z, size, color, seed);
    }

    private static void particleAt(PoseStack.Pose pose, VertexConsumer c, int style, float x, float y, float z,
                                   float size, int color, float seed) {
        TMP_PARTICLE.x = x; TMP_PARTICLE.y = y; TMP_PARTICLE.z = z;
        TMP_PARTICLE.px = x; TMP_PARTICLE.py = y; TMP_PARTICLE.pz = z;
        TMP_PARTICLE.vx = TMP_PARTICLE.vy = TMP_PARTICLE.vz = 0.0f;
        TMP_PARTICLE.seed = seed;
        drawParticleStyle(pose, c, style, TMP_PARTICLE, size, color);
    }

    private static void particleAt(PoseStack.Pose pose, VertexConsumer c, String style, KfxBasis basis,
                                   float x, float y, float z, float size, int color, float seed) {
        particleAt(pose, c, style,
            rx(basis, x, y, z), ry(basis, x, y, z), rz(basis, x, y, z),
            size, color, seed);
    }

    static void particleAt(PoseStack.Pose pose, VertexConsumer c, int style, KfxBasis basis,
                                   float x, float y, float z, float size, int color, float seed) {
        particleAt(pose, c, style,
            rx(basis, x, y, z), ry(basis, x, y, z), rz(basis, x, y, z),
            size, color, seed);
    }

    private static float rx(KfxBasis b, float x, float y, float z) {
        return b.side().x * x + b.forward().x * y + b.up().x * z;
    }

    private static float ry(KfxBasis b, float x, float y, float z) {
        return b.side().y * x + b.forward().y * y + b.up().y * z;
    }

    private static float rz(KfxBasis b, float x, float y, float z) {
        return b.side().z * x + b.forward().z * y + b.up().z * z;
    }

    static float smooth(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

    static float ease(float t, String ease) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        if ("linear".equals(ease)) return t;
        if ("in".equals(ease) || "quad_in".equals(ease)) return t * t;
        if ("out".equals(ease) || "quad_out".equals(ease)) return 1.0f - (1.0f - t) * (1.0f - t);
        return smooth(t);
    }

    private static void drawVortex(PoseStack.Pose pose, VertexConsumer c, KfxInstance fx, float spin, float pulse, float fade) {
        int steps = 96;
        int arms = 4;
        float height = Math.max(2.4f, fx.radius * 2.2f);
        float baseR = fx.radius * pulse;
        float band = Math.max(0.06f, fx.thickness * 0.45f);
        int edge = alpha(fx.color, fade * 0.68f);
        int core = alpha(fx.color2, fade * 0.9f);

        for (int arm = 0; arm < arms; arm++) {
            float phase = spin + (float)(Math.PI * 2.0 * arm / arms);
            for (int i = 0; i < steps; i++) {
                float t0 = i / (float)steps;
                float t1 = (i + 1) / (float)steps;
                float a0 = phase + t0 * (float)Math.PI * 5.0f;
                float a1 = phase + t1 * (float)Math.PI * 5.0f;
                float y0 = (t0 - 0.5f) * height;
                float y1 = (t1 - 0.5f) * height;
                float r0 = baseR * (0.28f + 0.72f * t0);
                float r1 = baseR * (0.28f + 0.72f * t1);
                helixBand(pose, c, a0, a1, y0, y1, r0, r1, band, (i & 3) == 0 ? core : edge);
            }
        }

        for (int i = 0; i < steps; i++) {
            float a0 = spin * -0.7f + (float)(Math.PI * 2.0 * i / steps);
            float a1 = spin * -0.7f + (float)(Math.PI * 2.0 * (i + 1) / steps);
            ringBand(pose, c, a0, a1, baseR * 0.18f, baseR * 0.34f, alpha(fx.color2, fade * 0.72f));
        }

        float coreW = Math.max(0.08f, fx.thickness * 0.65f);
        quad(pose, c, -coreW, -height * 0.48f, 0, coreW, -height * 0.48f, 0,
            coreW, height * 0.48f, 0, -coreW, height * 0.48f, 0, alpha(fx.color2, fade * 0.55f));
        quad(pose, c, 0, -height * 0.48f, -coreW, 0, -height * 0.48f, coreW,
            0, height * 0.48f, coreW, 0, height * 0.48f, -coreW, alpha(fx.color2, fade * 0.55f));
    }

    private static void ringBand(PoseStack.Pose pose, VertexConsumer c, float a0, float a1, float inner, float outer, int color) {
        quad(pose, c,
            (float)Math.cos(a0) * inner, 0, (float)Math.sin(a0) * inner,
            (float)Math.cos(a0) * outer, 0, (float)Math.sin(a0) * outer,
            (float)Math.cos(a1) * outer, 0, (float)Math.sin(a1) * outer,
            (float)Math.cos(a1) * inner, 0, (float)Math.sin(a1) * inner,
            color);
    }

    private static void verticalBandX(PoseStack.Pose pose, VertexConsumer c, float a0, float a1, float inner, float outer, int color) {
        quad(pose, c,
            (float)Math.cos(a0) * inner, (float)Math.sin(a0) * inner, 0,
            (float)Math.cos(a0) * outer, (float)Math.sin(a0) * outer, 0,
            (float)Math.cos(a1) * outer, (float)Math.sin(a1) * outer, 0,
            (float)Math.cos(a1) * inner, (float)Math.sin(a1) * inner, 0,
            color);
    }


    private static void helixBand(PoseStack.Pose pose, VertexConsumer c, float a0, float a1, float y0, float y1,
                                  float r0, float r1, float band, int color) {
        float x00 = (float)Math.cos(a0) * (r0 - band), z00 = (float)Math.sin(a0) * (r0 - band);
        float x01 = (float)Math.cos(a0) * (r0 + band), z01 = (float)Math.sin(a0) * (r0 + band);
        float x11 = (float)Math.cos(a1) * (r1 + band), z11 = (float)Math.sin(a1) * (r1 + band);
        float x10 = (float)Math.cos(a1) * (r1 - band), z10 = (float)Math.sin(a1) * (r1 - band);
        quad(pose, c, x00, y0, z00, x01, y0, z01, x11, y1, z11, x10, y1, z10, color);
    }

    // fake directional shading baked into vertex color so fullbright translucent meshes still read as 3D
    private static int shade(int color, float nx, float ny, float nz) {
        float inv = invLen(nx, ny, nz);
        float d = (nx * 0.32f + ny * 0.86f + nz * 0.36f) * inv;
        float b = 0.5f + 0.5f * Math.max(0.0f, d);
        int a = (color >>> 24) & 255;
        int r = Math.min(255, (int)(((color >> 16) & 255) * b));
        int g = Math.min(255, (int)(((color >> 8) & 255) * b));
        int bl = Math.min(255, (int)((color & 255) * b));
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }

    // solid UV sphere — real 3D triangles, per-face shaded. low ring/seg = cheap particle ball
    private static void sphereMesh(PoseStack.Pose pose, VertexConsumer c, float cx, float cy, float cz,
                                   float r, int rings, int segs, int color) {
        for (int i = 0; i < rings; i++) {
            float phi0 = (float)(Math.PI * i / rings), phi1 = (float)(Math.PI * (i + 1) / rings);
            float y0 = (float)Math.cos(phi0), y1 = (float)Math.cos(phi1);
            float rr0 = (float)Math.sin(phi0), rr1 = (float)Math.sin(phi1);
            for (int j = 0; j < segs; j++) {
                float a0 = (float)(Math.PI * 2.0 * j / segs), a1 = (float)(Math.PI * 2.0 * (j + 1) / segs);
                float c0 = (float)Math.cos(a0), s0 = (float)Math.sin(a0);
                float c1 = (float)Math.cos(a1), s1 = (float)Math.sin(a1);
                float n0x = rr0 * c0, n0z = rr0 * s0;
                float n1x = rr0 * c1, n1z = rr0 * s1;
                float n2x = rr1 * c1, n2z = rr1 * s1;
                float n3x = rr1 * c0, n3z = rr1 * s0;
                int col = shade(color, n0x + n1x + n2x + n3x, y0 + y0 + y1 + y1, n0z + n1z + n2z + n3z);
                quad(pose, c,
                    cx + n0x * r, cy + y0 * r, cz + n0z * r,
                    cx + n1x * r, cy + y0 * r, cz + n1z * r,
                    cx + n2x * r, cy + y1 * r, cz + n2z * r,
                    cx + n3x * r, cy + y1 * r, cz + n3z * r, col);
            }
        }
    }

    // oriented tube along (fxn,fyn,fzn)*len from local origin. segs=4+square = box laser, else round
    private static void tubeMesh(PoseStack.Pose pose, VertexConsumer c,
                                 float fxn, float fyn, float fzn, float len, float r, int segs, boolean square, int color) {
        float ux = -fzn, uy = 0.0f, uz = fxn;
        if (ux * ux + uy * uy + uz * uz < 1.0e-6f) { ux = 0.0f; uy = fzn; uz = -fyn; }
        float ul = invLen(ux, uy, uz); ux *= ul; uy *= ul; uz *= ul;
        float vx = fyn * uz - fzn * uy, vy = fzn * ux - fxn * uz, vz = fxn * uy - fyn * ux;
        float vl = invLen(vx, vy, vz); vx *= vl; vy *= vl; vz *= vl;
        float off = square ? 0.7853982f : 0.0f;
        float tipx = fxn * len, tipy = fyn * len, tipz = fzn * len;
        for (int i = 0; i < segs; i++) {
            float a0 = off + (float)(Math.PI * 2.0 * i / segs);
            float a1 = off + (float)(Math.PI * 2.0 * (i + 1) / segs);
            float c0 = (float)Math.cos(a0), s0 = (float)Math.sin(a0);
            float c1 = (float)Math.cos(a1), s1 = (float)Math.sin(a1);
            float d0x = (ux * c0 + vx * s0) * r, d0y = (uy * c0 + vy * s0) * r, d0z = (uz * c0 + vz * s0) * r;
            float d1x = (ux * c1 + vx * s1) * r, d1y = (uy * c1 + vy * s1) * r, d1z = (uz * c1 + vz * s1) * r;
            int col = shade(color, d0x + d1x, d0y + d1y, d0z + d1z);
            quad(pose, c,
                d0x, d0y, d0z,
                d1x, d1y, d1z,
                tipx + d1x, tipy + d1y, tipz + d1z,
                tipx + d0x, tipy + d0y, tipz + d0z, col);
        }
    }

    static void quad(PoseStack.Pose pose, VertexConsumer c,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, int color) {
        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float inv = invLen(nx, ny, nz);
        nx *= inv; ny *= inv; nz *= inv;
        vertex(pose, c, x0, y0, z0, color, 0, 0, nx, ny, nz);
        vertex(pose, c, x1, y1, z1, color, 1, 0, nx, ny, nz);
        vertex(pose, c, x2, y2, z2, color, 1, 1, nx, ny, nz);
        vertex(pose, c, x3, y3, z3, color, 0, 1, nx, ny, nz);
    }

    static void tri(PoseStack.Pose pose, VertexConsumer c,
                            float x0, float y0, float z0, float x1, float y1, float z1,
                            float x2, float y2, float z2, int color) {
        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float inv = invLen(nx, ny, nz);
        nx *= inv; ny *= inv; nz *= inv;
        vertex(pose, c, x0, y0, z0, color, 0.5f, 0, nx, ny, nz);
        vertex(pose, c, x1, y1, z1, color, 0, 1, nx, ny, nz);
        vertex(pose, c, x2, y2, z2, color, 1, 1, nx, ny, nz);
        vertex(pose, c, x2, y2, z2, color, 1, 1, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z,
                               int color, float u, float v, float nx, float ny, float nz) {
        c.addVertex(pose, x, y, z).setColor(color).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
            .setNormal(pose, nx, ny, nz);
    }

    private static float invLen(float x, float y, float z) {
        float len = x * x + y * y + z * z;
        return len > 1.0e-7f ? 1.0f / (float)Math.sqrt(len) : 1.0f;
    }

    private static float fade(KfxInstance fx, float age) {
        float f = 1.0f;
        if (fx.fadeIn > 0.0f) f = Math.min(f, age / fx.fadeIn);
        if (fx.lifetime >= 0 && fx.fadeOut > 0.0f) f = Math.min(f, Math.max(0.0f, (fx.lifetime - age) / fx.fadeOut));
        return Math.max(0.0f, Math.min(1.0f, f * fx.anchorAlpha));
    }

    static int alpha(int color, float mul) {
        int a = (color >>> 24) & 255;
        a = Math.max(0, Math.min(255, Math.round(a * mul)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    static int rgba(float r, float g, float b, float a) {
        int ir = Math.max(0, Math.min(255, Math.round(r * 255.0f)));
        int ig = Math.max(0, Math.min(255, Math.round(g * 255.0f)));
        int ib = Math.max(0, Math.min(255, Math.round(b * 255.0f)));
        int ia = Math.max(0, Math.min(255, Math.round(a * 255.0f)));
        return (ia << 24) | (ir << 16) | (ig << 8) | ib;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static int mix(int a, int b, float t) {
        int aa = (a >>> 24) & 255, ar = (a >>> 16) & 255, ag = (a >>> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >>> 16) & 255, bg = (b >>> 8) & 255, bb = b & 255;
        int ca = Math.round(lerp(aa, ba, t));
        int cr = Math.round(lerp(ar, br, t));
        int cg = Math.round(lerp(ag, bg, t));
        int cb = Math.round(lerp(ab, bb, t));
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }

    private static final class EmitterState {
        final Random random;
        final ArrayList<Particle> particles = new ArrayList<>();
        float lastAge = -1.0f;
        float carry;
        boolean burstDone;

        EmitterState(long id) {
            this.random = new Random(id ^ 0x51F15EEDL);
        }

        void tick(KfxInstance fx, float age) {
            float dt = lastAge < 0.0f ? 1.0f : Math.max(0.0f, Math.min(4.0f, age - lastAge));
            lastAge = age;
            int motion = motionCode(fx.particleMotion);
            for (Particle p : particles) {
                p.age += dt;
                p.px = p.x; p.py = p.y; p.pz = p.z;
                float wave = (float)Math.sin((p.seed + p.age) * 0.37f) * fx.turbulence * dt;
                p.vx += wave;
                p.vz += (float)Math.cos((p.seed - p.age) * 0.31f) * fx.turbulence * dt;
                if (motion != MOTION_FREE) applyMotion(fx, p, motion, dt);
                p.vy += fx.gravity * dt;
                float drag = (float)Math.pow(fx.drag, dt);
                p.vx *= drag; p.vy *= drag; p.vz *= drag;
                p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            }
            if (fx.dead()) return;
            if (!burstDone) {
                burstDone = true;
                spawn(fx, Math.max(0, fx.emitterBurst));
            }
            carry += fx.emitterRate * dt / 20.0f;
            int n = Math.min(96, (int)carry);
            if (n > 0) {
                carry -= n;
                spawn(fx, n);
            }
        }

        void spawn(KfxInstance fx, int count) {
            int cap = Math.max(0, fx.maxParticles);
            for (int i = 0; i < count && particles.size() < cap; i++) {
                Particle p = new Particle();
                p.seed = random.nextFloat() * 1024.0f;
                p.orbit = random.nextFloat() * (float)Math.PI * 2.0f;
                p.orbitRadius = fx.spread * (0.45f + random.nextFloat() * 0.65f);
                float[] dir = direction();
                float rx = dir[0], ry = dir[1], rz = dir[2];
                float len = Math.max(0.001f, (float)Math.sqrt(rx * rx + ry * ry + rz * rz));
                rx /= len; ry /= len; rz /= len;
                placeParticle(fx, p, rx, ry, rz);
                p.px = p.x; p.py = p.y; p.pz = p.z;
                float speed = fx.speed * (0.45f + random.nextFloat() * 0.9f);
                p.vx = rx * speed + rnd(-0.012f, 0.012f);
                p.vy = ry * speed + 0.035f + rnd(-0.006f, 0.018f);
                p.vz = rz * speed + rnd(-0.012f, 0.012f);
                p.life = Math.max(4, fx.particleLifetime + (int)rnd(-6, 8));
                p.size = Math.max(0.015f, fx.radius * (0.35f + random.nextFloat() * 0.9f));
                particles.add(p);
            }
        }

        void placeParticle(KfxInstance fx, Particle p, float rx, float ry, float rz) {
            String shape = fx.emitterShape == null ? "sphere" : fx.emitterShape.toLowerCase();
            float shell = random.nextFloat() * fx.spread;
            switch (shape) {
                case "point" -> {
                    p.x = rnd(-0.025f, 0.025f); p.y = rnd(-0.025f, 0.025f); p.z = rnd(-0.025f, 0.025f);
                }
                case "ring" -> {
                    float a = random.nextFloat() * (float)Math.PI * 2.0f;
                    float r = fx.spread * (0.82f + random.nextFloat() * 0.18f);
                    p.x = (float)Math.cos(a) * r;
                    p.y = rnd(-0.04f, 0.04f);
                    p.z = (float)Math.sin(a) * r;
                }
                case "beam" -> {
                    float t = random.nextFloat();
                    float bx = fx.ex - fx.sx, by = fx.ey - fx.sy, bz = fx.ez - fx.sz;
                    p.x = bx * t + rnd(-fx.spread, fx.spread) * 0.12f;
                    p.y = by * t + rnd(-fx.spread, fx.spread) * 0.12f;
                    p.z = bz * t + rnd(-fx.spread, fx.spread) * 0.12f;
                }
                case "cone" -> {
                    p.x = rx * shell * 0.55f;
                    p.y = random.nextFloat() * fx.spread * 0.25f;
                    p.z = rz * shell * 0.55f;
                }
                default -> {
                    p.x = rx * shell; p.y = ry * shell; p.z = rz * shell;
                }
            }
        }

        float[] direction() {
            return new float[]{rnd(-1, 1), rnd(-0.45f, 0.85f), rnd(-1, 1)};
        }

        float rnd(float min, float max) {
            return min + random.nextFloat() * (max - min);
        }

        void prune() {
            int w = 0;
            for (int r = 0; r < particles.size(); r++) {
                Particle p = particles.get(r);
                if (p.age < p.life) {
                    if (w != r) particles.set(w, p);
                    w++;
                }
            }
            while (particles.size() > w) particles.remove(particles.size() - 1);
        }

        void applyMotion(KfxInstance fx, Particle p, int motion, float dt) {
            if (motion == MOTION_INWARD) {
                float len = Math.max(0.001f, (float)Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z));
                float pull = fx.speed * 0.08f * dt;
                p.vx -= p.x / len * pull;
                p.vy -= p.y / len * pull;
                p.vz -= p.z / len * pull;
            } else {
                float spin = (motion == MOTION_SWIRL ? 0.18f : 0.11f) * dt;
                p.orbit += spin + fx.turbulence * 0.3f;
                float targetX = (float)Math.cos(p.orbit) * p.orbitRadius;
                float targetZ = (float)Math.sin(p.orbit) * p.orbitRadius;
                float pull = motion == MOTION_SWIRL ? 0.045f : 0.028f;
                p.vx += (targetX - p.x) * pull * dt;
                p.vz += (targetZ - p.z) * pull * dt;
                if (motion == MOTION_SWIRL) p.vy += (float)Math.sin(p.orbit * 1.7f + p.seed) * 0.0025f * dt;
            }
        }
    }

    private static final class Particle {
        float x, y, z, px, py, pz, vx, vy, vz, age, life, size, seed, orbit, orbitRadius;

        Particle set(float x, float y, float z, float seed) {
            this.x = x; this.y = y; this.z = z;
            this.px = x; this.py = y - 0.025f; this.pz = z;
            this.vx = 0.0f; this.vy = 0.025f; this.vz = 0.0f;
            this.seed = seed;
            return this;
        }
    }

}
