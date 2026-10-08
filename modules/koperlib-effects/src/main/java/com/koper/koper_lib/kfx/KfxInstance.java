package com.koper.koper_lib.kfx;

public final class KfxInstance {
    public final long id;
    public final KfxDef.Kind kind;
    public int color;
    public int color2;
    public float sx, sy, sz;
    public float ex, ey, ez;
    public float radius;
    public float thickness;
    public final int lifetime;
    public final boolean loop;
    public final float spinY;
    public final float pulseSpeed;
    public final float pulseAmount;
    public final float fadeIn;
    public final float fadeOut;
    public final float emitterRate;
    public final int emitterBurst;
    public final int particleLifetime;
    public final float spread;
    public final float speed;
    public final float gravity;
    public final float drag;
    public final float sizeEnd;
    public final String emitterShape;
    public final String particleStyle;
    public final String particleMotion;
    public final float timelineWarmup;
    public final float timelineBeamTime;
    public final float ringScale;
    public final float sigilScale;
    public final String programJson;
    public final int maxParticles;
    public final float turbulence;
    public final String collisionResponse;
    public final int collisionRadius;
    public final boolean collisionFluids;
    public final float collisionRestitution;
    public final float collisionFriction;
    public final KfxLight light;
    public final float bornTick;
    public float anchorAlpha = 1.0f;
    public int attachEntityId = -1;
    public int attachEndEntityId = -1;
    public float attachOx, attachOy, attachOz;
    public float attachEx, attachEy, attachEz;
    public boolean attachEndRelative;

    public KfxInstance(long id, KfxDef.Kind kind, int color,
                       float sx, float sy, float sz, float ex, float ey, float ez,
                       float radius, float thickness, int lifetime, boolean loop, float spinY, KfxLight light) {
        this(id, kind, color, KfxDef.brighten(color), sx, sy, sz, ex, ey, ez, radius, thickness,
            lifetime, loop, spinY, 1.2f, 0.12f, 3.0f, 10.0f,
            0.0f, 0, 34, 0.45f, 0.12f, -0.006f, 0.965f, radius * 0.15f,
            "sphere", "star", "free", 34.0f, 10.0f, 2.8f, 0.72f, "", 700, 0.012f,
            "none", 8, false, 0.65f, 0.08f, light);
    }

    public KfxInstance(long id, KfxDef.Kind kind, int color, int color2,
                       float sx, float sy, float sz, float ex, float ey, float ez,
                       float radius, float thickness, int lifetime, boolean loop, float spinY,
                       float pulseSpeed, float pulseAmount, float fadeIn, float fadeOut,
                       float emitterRate, int emitterBurst, int particleLifetime, float spread,
                       float speed, float gravity, float drag, float sizeEnd,
                       String emitterShape, String particleStyle, String particleMotion,
                       float timelineWarmup, float timelineBeamTime, float ringScale, float sigilScale,
                       String programJson, int maxParticles, float turbulence,
                       String collisionResponse, int collisionRadius, boolean collisionFluids,
                       float collisionRestitution, float collisionFriction, KfxLight light) {
        this.id = id;
        this.kind = kind;
        this.color = color;
        this.color2 = color2;
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.ex = ex; this.ey = ey; this.ez = ez;
        this.radius = radius;
        this.thickness = thickness;
        this.lifetime = lifetime;
        this.loop = loop;
        this.spinY = spinY;
        this.pulseSpeed = pulseSpeed;
        this.pulseAmount = pulseAmount;
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
        this.emitterRate = emitterRate;
        this.emitterBurst = emitterBurst;
        this.particleLifetime = particleLifetime;
        this.spread = spread;
        this.speed = speed;
        this.gravity = gravity;
        this.drag = drag;
        this.sizeEnd = sizeEnd;
        this.emitterShape = emitterShape;
        this.particleStyle = particleStyle;
        this.particleMotion = particleMotion;
        this.timelineWarmup = timelineWarmup;
        this.timelineBeamTime = timelineBeamTime;
        this.ringScale = ringScale;
        this.sigilScale = sigilScale;
        this.programJson = programJson != null ? programJson : "";
        this.maxParticles = maxParticles;
        this.turbulence = turbulence;
        this.collisionResponse = collisionResponse == null ? "none" : collisionResponse;
        this.collisionRadius = Math.clamp(collisionRadius, 1, 16);
        this.collisionFluids = collisionFluids;
        this.collisionRestitution = Math.clamp(collisionRestitution, 0.0f, 1.0f);
        this.collisionFriction = Math.clamp(collisionFriction, 0.0f, 1.0f);
        this.light = light;
        this.bornTick = KfxClient.nowTicks();   // tick clock, frozen on pause
    }

    public void update(float sx, float sy, float sz, float ex, float ey, float ez) {
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.ex = ex; this.ey = ey; this.ez = ez;
    }

    public void updateProperties(int color, int color2, float radius, float thickness) {
        this.color = color;
        this.color2 = color2;
        this.radius = Math.max(0.0f, radius);
        this.thickness = Math.max(0.0f, thickness);
    }

    public void attachTo(int entityId, float ox, float oy, float oz, float ex, float ey, float ez, boolean endRelative) {
        this.attachEntityId = entityId;
        this.attachEndEntityId = -1;
        this.attachOx = ox; this.attachOy = oy; this.attachOz = oz;
        this.attachEx = ex; this.attachEy = ey; this.attachEz = ez;
        this.attachEndRelative = endRelative;
    }

    public void attachBetween(int startEntityId, float startOx, float startOy, float startOz,
                              int endEntityId, float endOx, float endOy, float endOz) {
        this.attachEntityId = startEntityId;
        this.attachEndEntityId = endEntityId;
        this.attachOx = startOx; this.attachOy = startOy; this.attachOz = startOz;
        this.attachEx = endOx; this.attachEy = endOy; this.attachEz = endOz;
        this.attachEndRelative = true;
    }

    public void detach() {
        this.attachEntityId = -1;
        this.attachEndEntityId = -1;
    }

    public float ageTicks() {
        return KfxClient.nowTicks() - bornTick;
    }

    public boolean dead() {
        return lifetime >= 0 && !loop && ageTicks() > lifetime;
    }
}
