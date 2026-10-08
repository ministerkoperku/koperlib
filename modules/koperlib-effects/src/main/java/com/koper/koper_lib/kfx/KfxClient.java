package com.koper.koper_lib.kfx;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class KfxClient {
    private static final Map<Long, KfxInstance> LIVE = new ConcurrentHashMap<>();
    private static final Map<Long, com.koper.koper_lib.kfx.runtime.KfxClientGraphInstance> ANCHORED = new ConcurrentHashMap<>();
    private static final Map<Long, com.koper.koper_lib.kfx.runtime.KfxLossFade> LOSS_FADES = new ConcurrentHashMap<>();
    private static final Map<Long, com.koper.koper_lib.kfx.runtime.KfxMotionLerp> MOTIONS = new ConcurrentHashMap<>();
    private static final com.koper.koper_lib.kfx.runtime.KfxImpactInbox IMPACTS =
        new com.koper.koper_lib.kfx.runtime.KfxImpactInbox();
    private static final java.util.concurrent.atomic.AtomicLong LOCAL_IDS =
        new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    private static final java.util.ArrayDeque<Long> IMPACT_VISUALS = new java.util.ArrayDeque<>();
    private static final KfxImpactVisual.Gate IMPACT_GATE = new KfxImpactVisual.Gate();

    // effect clock = client ticks (+ partial), NOT wall-clock — so pausing the game freezes every effect.
    // small floats by construction, GPU-safe.
    private static int ticks = 0;
    private static long anchorFrame = Long.MIN_VALUE;
    private static boolean hadAnchors;

    public static void initClock() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!mc.isPaused()) ticks++;
        });
    }

    public static float nowTicks() {
        float partial = 0.0f;
        try { partial = net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false); }
        catch (Throwable ignored) {}
        return ticks + partial;
    }

    public static float bornTicks(KfxInstance fx) {
        return fx.bornTick;
    }

    private KfxClient() {}

    public static void spawn(KfxInstance fx) {
        // Replacing a live id (a typed input change) leaves the old parsed program cached, so the
        // portable path would keep drawing the previous one. Native state is keyed by id and replaces itself.
        if (LIVE.put(fx.id, fx) != null) KfxRenderer.dropProgram(fx.id);
    }

    public static void update(long id, float sx, float sy, float sz, float ex, float ey, float ez) {
        KfxInstance fx = LIVE.get(id);
        if (fx != null) {
            var motion = MOTIONS.computeIfAbsent(id, ignored -> new com.koper.koper_lib.kfx.runtime.KfxMotionLerp(
                new net.minecraft.world.phys.Vec3(fx.sx, fx.sy, fx.sz),
                new net.minecraft.world.phys.Vec3(fx.ex, fx.ey, fx.ez)));
            motion.retarget(nowTicks(), new net.minecraft.world.phys.Vec3(sx, sy, sz),
                new net.minecraft.world.phys.Vec3(ex, ey, ez));
        }
    }

    public static void updateProperties(long id, int color, int color2, float radius, float thickness) {
        KfxInstance fx = LIVE.get(id);
        if (fx == null) return;
        fx.updateProperties(color, color2, radius, thickness);
        com.koper.koper_lib.kender.KenderBridge.kfxRemove(id);
        com.koper.koper_lib.kender.KenderBridge.emitterRemove(id);
        KfxKenderAdapter.forget(id);
        boolean nativeReady = fx.kind == KfxDef.Kind.EMITTER
            ? KfxKenderAdapter.spawn(fx)
            : KfxBackend.KENDER_NATIVE.submit(fx);
        KfxRenderer.nativeReady(id, nativeReady);
    }

    public static void stop(long id) {
        LIVE.remove(id);
        ANCHORED.remove(id);
        LOSS_FADES.remove(id);
        MOTIONS.remove(id);
        IMPACTS.forget(id);
        KfxRenderer.dropRuntime(id);
        com.koper.koper_lib.kender.KenderBridge.kfxRemove(id);
        com.koper.koper_lib.kender.KenderBridge.emitterRemove(id);
        KfxKenderAdapter.forget(id);
    }

    public static void attach(long id, int entityId, float ox, float oy, float oz, float ex, float ey, float ez, boolean endRelative) {
        KfxInstance fx = LIVE.get(id);
        if (fx != null) {
            MOTIONS.remove(id);
            fx.attachTo(entityId, ox, oy, oz, ex, ey, ez, endRelative);
        }
    }

    public static void attachBetween(long id,
                                     int startEntityId, float startOx, float startOy, float startOz,
                                     int endEntityId, float endOx, float endOy, float endOz) {
        KfxInstance fx = LIVE.get(id);
        if (fx != null) {
            MOTIONS.remove(id);
            fx.attachBetween(startEntityId, startOx, startOy, startOz,
                endEntityId, endOx, endOy, endOz);
        }
    }

    public static void anchor(long id, com.koper.koper_lib.kfx.graph.KfxAnchor start,
                              com.koper.koper_lib.kfx.graph.KfxAnchor end) {
        KfxInstance fx = LIVE.get(id);
        if (fx == null) return;
        MOTIONS.remove(id);
        fx.detach();
        var anchored = ANCHORED.get(id);
        if (anchored != null) {
            anchored.reanchor(start, end);
        } else {
            var startAt = com.koper.koper_lib.kfx.runtime.KfxTransform.at(
                new net.minecraft.world.phys.Vec3(fx.sx, fx.sy, fx.sz));
            var endAt = com.koper.koper_lib.kfx.runtime.KfxTransform.at(
                new net.minecraft.world.phys.Vec3(fx.ex, fx.ey, fx.ez));
            ANCHORED.put(id, new com.koper.koper_lib.kfx.runtime.KfxClientGraphInstance(
                id, start, end, startAt, endAt));
        }
        LOSS_FADES.remove(id);
        fx.anchorAlpha = 1.0f;
    }

    public static void detach(long id) {
        var anchored = ANCHORED.get(id);
        if (anchored == null) return;
        anchored.detach();
        var fade = LOSS_FADES.remove(id);
        if (fade != null) fade.cancel();
        KfxInstance fx = LIVE.get(id);
        if (fx != null) fx.anchorAlpha = 1.0f;
    }

    public static void impact(com.koper.koper_lib.network.KfxImpactPayload impact) {
        if (!IMPACTS.accept(impact)) return;
        KfxInstance source = LIVE.get(impact.handle());
        if (source == null) return;
        while (!IMPACT_VISUALS.isEmpty() && !LIVE.containsKey(IMPACT_VISUALS.peekFirst())) {
            IMPACT_VISUALS.removeFirst();
        }
        if (!IMPACT_GATE.allow(ticks, IMPACT_VISUALS.size())) return;
        var burst = KfxImpactVisual.burst(impact, source.color, source.color2, source.thickness);
        var point = burst.position();
        var tip = point.add(burst.normal().scale(0.15));
        var fx = new KfxInstance(LOCAL_IDS.getAndIncrement(), KfxDef.Kind.RING, burst.color(),
            (float)point.x, (float)point.y, (float)point.z,
            (float)tip.x, (float)tip.y, (float)tip.z,
            burst.radius(), burst.thickness(), burst.lifetime(), false, 28.0f, KfxLight.NONE);
        KfxRenderer.nativeReady(fx.id, false);
        KfxBackend.MC_PATH.submit(fx);
        IMPACT_VISUALS.addLast(fx.id);
    }

    public static void clear() {
        LIVE.clear();
        ANCHORED.clear();
        LOSS_FADES.clear();
        MOTIONS.clear();
        IMPACTS.clear();
        IMPACT_VISUALS.clear();
        KfxRenderer.clearRuntime();
        com.koper.koper_lib.kender.KenderBridge.kfxClear();
        com.koper.koper_lib.kender.KenderBridge.emitterClear();
        KfxKenderAdapter.clear();
        anchorFrame = Long.MIN_VALUE;
        hadAnchors = false;
    }

    public static Collection<KfxInstance> live() {
        refreshMotions();
        refreshAnchored();
        refreshAttached();
        LIVE.values().removeIf(fx -> {
            boolean dead = fx.dead();
            if (dead) {
                ANCHORED.remove(fx.id);
                LOSS_FADES.remove(fx.id);
                MOTIONS.remove(fx.id);
                IMPACTS.forget(fx.id);
                KfxRenderer.dropRuntime(fx.id);
                com.koper.koper_lib.kender.KenderBridge.kfxRemove(fx.id);
                com.koper.koper_lib.kender.KenderBridge.emitterRemove(fx.id);
                KfxKenderAdapter.forget(fx.id);
            }
            return dead;
        });
        return LIVE.values();
    }

    private static void refreshMotions() {
        if (MOTIONS.isEmpty()) return;
        float now = nowTicks();
        for (var entry : MOTIONS.entrySet()) {
            KfxInstance fx = LIVE.get(entry.getKey());
            if (fx == null) {
                MOTIONS.remove(entry.getKey());
                continue;
            }
            var frame = entry.getValue().sample(now);
            float sx = (float)frame.start().x;
            float sy = (float)frame.start().y;
            float sz = (float)frame.start().z;
            float ex = (float)frame.end().x;
            float ey = (float)frame.end().y;
            float ez = (float)frame.end().z;
            if (sx == fx.sx && sy == fx.sy && sz == fx.sz && ex == fx.ex && ey == fx.ey && ez == fx.ez) continue;
            fx.update(sx, sy, sz, ex, ey, ez);
            com.koper.koper_lib.kender.KenderBridge.kfxUpdate(fx.id, sx, sy, sz, ex, ey, ez);
            KfxKenderAdapter.refreshCollision(fx);
        }
    }

    private static void refreshAttached() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        for (KfxInstance fx : LIVE.values()) {
            if (ANCHORED.containsKey(fx.id)) continue;
            if (fx.attachEntityId < 0) continue;
            var e = mc.level.getEntity(fx.attachEntityId);
            if (e == null) continue;
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            double x = net.minecraft.util.Mth.lerp(partial, e.xOld, e.getX());
            double y = net.minecraft.util.Mth.lerp(partial, e.yOld, e.getY());
            double z = net.minecraft.util.Mth.lerp(partial, e.zOld, e.getZ());
            float sx = (float)x + fx.attachOx;
            float sy = (float)y + fx.attachOy;
            float sz = (float)z + fx.attachOz;
            float ex, ey, ez;
            if (fx.attachEndEntityId >= 0) {
                var endEntity = mc.level.getEntity(fx.attachEndEntityId);
                if (endEntity == null) continue;
                ex = (float)net.minecraft.util.Mth.lerp(partial, endEntity.xOld, endEntity.getX()) + fx.attachEx;
                ey = (float)net.minecraft.util.Mth.lerp(partial, endEntity.yOld, endEntity.getY()) + fx.attachEy;
                ez = (float)net.minecraft.util.Mth.lerp(partial, endEntity.zOld, endEntity.getZ()) + fx.attachEz;
            } else if (fx.attachEndRelative) {
                ex = (float)x + fx.attachEx;
                ey = (float)y + fx.attachEy;
                ez = (float)z + fx.attachEz;
            } else {
                ex = sx + fx.attachEx;
                ey = sy + fx.attachEy;
                ez = sz + fx.attachEz;
            }
            if (sx != fx.sx || sy != fx.sy || sz != fx.sz || ex != fx.ex || ey != fx.ey || ez != fx.ez) {
                fx.update(sx, sy, sz, ex, ey, ez);
                com.koper.koper_lib.kender.KenderBridge.kfxUpdate(fx.id, sx, sy, sz, ex, ey, ez);
                KfxKenderAdapter.refreshCollision(fx);
            }
        }
    }

    private static void refreshAnchored() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        if (ANCHORED.isEmpty()) {
            if (hadAnchors) {
                com.koper.koper_lib.api.core.KoperBoneAnchors.beginFrame();
                com.koper.koper_lib.api.core.KoperBoneAnchors.endFrame();
                hadAnchors = false;
            }
            return;
        }
        hadAnchors = true;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long frame = ((long)ticks << 32) ^ Integer.toUnsignedLong(Float.floatToRawIntBits(partial));
        if (frame == anchorFrame) return;
        anchorFrame = frame;
        var poses = new com.koper.koper_lib.kfx.runtime.KfxAnchorResolver.PoseSource() {
            @Override public com.koper.koper_lib.kfx.runtime.KfxAnchorResolver.EntityPose entity(int id) {
                var entity = mc.level.getEntity(id);
                if (entity == null) return null;
                float oldYaw = entity.yRotO;
                float yaw = entity.getYRot();
                boolean right = true;
                if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                    oldYaw = living.yBodyRotO;
                    yaw = living.yBodyRot;
                    right = living.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
                }
                return new com.koper.koper_lib.kfx.runtime.KfxAnchorResolver.EntityPose(
                    new net.minecraft.world.phys.Vec3(entity.xOld, entity.yOld, entity.zOld), entity.position(),
                    oldYaw, yaw, entity.yRotO, entity.getYRot(), entity.xRotO, entity.getXRot(),
                    entity.getBbHeight(), entity.getEyeHeight(), right
                );
            }

            @Override public Optional<com.koper.koper_lib.kfx.runtime.KfxTransform> bone(
                    int id, String bone, float partialTick) {
                return com.koper.koper_lib.api.core.KoperBoneAnchors.resolve(id, bone, partialTick)
                    .map(it -> new com.koper.koper_lib.kfx.runtime.KfxTransform(
                        new net.minecraft.world.phys.Vec3(it.x(), it.y(), it.z()),
                        new net.minecraft.world.phys.Vec3(it.forwardX(), it.forwardY(), it.forwardZ()),
                        new net.minecraft.world.phys.Vec3(it.normalX(), it.normalY(), it.normalZ())));
            }
        };

        com.koper.koper_lib.api.core.KoperBoneAnchors.beginFrame();
        try {
            for (var anchored : ANCHORED.values()) {
                KfxInstance fx = LIVE.get(anchored.id());
                if (fx == null) {
                    ANCHORED.remove(anchored.id());
                    continue;
                }
                var state = anchored.resolveFrame(poses, partial);
                if (state == com.koper.koper_lib.kfx.runtime.KfxAnchorState.KILL) {
                    stop(fx.id);
                    continue;
                }
                if (state == com.koper.koper_lib.kfx.runtime.KfxAnchorState.FADE) beginLossFade(fx);
                var fade = LOSS_FADES.get(fx.id);
                if (fade != null) {
                    float now = nowTicks();
                    fx.anchorAlpha = fade.alpha(now);
                    if (fade.done(now)) {
                        stop(fx.id);
                        continue;
                    }
                }
                float sx = (float)anchored.start().position().x;
                float sy = (float)anchored.start().position().y;
                float sz = (float)anchored.start().position().z;
                float ex = (float)anchored.end().position().x;
                float ey = (float)anchored.end().position().y;
                float ez = (float)anchored.end().position().z;
                if (sx != fx.sx || sy != fx.sy || sz != fx.sz || ex != fx.ex || ey != fx.ey || ez != fx.ez) {
                    fx.update(sx, sy, sz, ex, ey, ez);
                    com.koper.koper_lib.kender.KenderBridge.kfxUpdate(fx.id, sx, sy, sz, ex, ey, ez);
                    KfxKenderAdapter.refreshCollision(fx);
                }
            }
        } finally {
            com.koper.koper_lib.api.core.KoperBoneAnchors.endFrame();
        }
    }

    private static void beginLossFade(KfxInstance fx) {
        if (LOSS_FADES.containsKey(fx.id)) return;
        var fade = new com.koper.koper_lib.kfx.runtime.KfxLossFade();
        fade.begin(nowTicks(), fx.fadeOut);
        LOSS_FADES.put(fx.id, fade);
        KfxRenderer.nativeReady(fx.id, false);
        com.koper.koper_lib.kender.KenderBridge.kfxRemove(fx.id);
        com.koper.koper_lib.kender.KenderBridge.emitterRemove(fx.id);
    }
}
