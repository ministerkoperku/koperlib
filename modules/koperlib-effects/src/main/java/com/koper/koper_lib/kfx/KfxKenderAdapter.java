package com.koper.koper_lib.kfx;

import com.koper.koper_lib.kender.KenderBridge;

/** Effects-owned conversion into Core Kender's generic emitter ABI. */
final class KfxKenderAdapter {
    private static final java.util.Map<Long, net.minecraft.core.BlockPos> COLLISION_CENTERS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private KfxKenderAdapter() {}

    static boolean spawn(KfxInstance fx) {
        float[] parameters = {
            fx.sx, fx.sy, fx.sz, fx.ex, fx.ey, fx.ez,
            fx.radius, fx.emitterRate, fx.emitterBurst, fx.particleLifetime, fx.spread, fx.speed,
            fx.gravity, fx.drag, fx.sizeEnd, fx.turbulence, fx.maxParticles,
            shape(fx.emitterShape), motion(fx.particleMotion),
            fx.lifetime, fx.fadeIn, fx.fadeOut, fx.loop ? 1f : 0f, KfxClient.bornTicks(fx),
            KfxStyles.codeFor(fx.particleStyle)
        };
        if (!KenderBridge.emitterSpawn(fx.id, fx.color, fx.color2, parameters)) return false;
        return refreshCollision(fx);
    }

    static boolean refreshCollision(KfxInstance fx) {
        var response = response(fx.collisionResponse);
        if (response == null) return true;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return false;
        var center = net.minecraft.core.BlockPos.containing(fx.sx, fx.sy, fx.sz);
        var previous = COLLISION_CENTERS.get(fx.id);
        int threshold = Math.max(1, fx.collisionRadius / 2);
        if (previous != null && Math.abs(center.getX() - previous.getX()) < threshold
            && Math.abs(center.getY() - previous.getY()) < threshold
            && Math.abs(center.getZ() - previous.getZ()) < threshold) return true;
        var field = com.koper.koper_lib.kfx.render.KfxCollisionField.capture(level,
            center, fx.collisionRadius, fx.collisionFluids);
        boolean uploaded = field.upload(fx.id, response, fx.collisionRestitution, fx.collisionFriction);
        if (uploaded) COLLISION_CENTERS.put(fx.id, center.immutable());
        return uploaded;
    }

    static void forget(long id) { COLLISION_CENTERS.remove(id); }
    static void clear() { COLLISION_CENTERS.clear(); }

    private static float shape(String value) {
        if (value == null) return 0;
        return switch (value.toLowerCase()) {
            case "point" -> 1; case "ring" -> 2; case "beam" -> 3; case "cone" -> 4;
            default -> 0;
        };
    }

    private static float motion(String value) {
        if (value == null) return 0;
        return switch (value.toLowerCase()) {
            case "orbit", "circle" -> 1;
            case "inward", "implode", "center" -> 2;
            case "swirl", "vortex" -> 3;
            default -> 0;
        };
    }

    private static com.koper.koper_lib.kfx.render.KfxParticleResponse response(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) return null;
        try { return com.koper.koper_lib.kfx.render.KfxParticleResponse.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { return null; }
    }
}
