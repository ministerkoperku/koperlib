package com.koper.koper_lib.network;

import com.koper.koper_lib.kfx.KfxDef;
import com.koper.koper_lib.kfx.KfxInstance;
import com.koper.koper_lib.kfx.KfxLight;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxSpawnPayload(
    long id,
    String kind,
    int color,
    int color2,
    float sx, float sy, float sz,
    float ex, float ey, float ez,
    float radius,
    float thickness,
    int lifetime,
    boolean loop,
    float spinY,
    float pulseSpeed,
    float pulseAmount,
    float fadeIn,
    float fadeOut,
    float emitterRate,
    int emitterBurst,
    int particleLifetime,
    float spread,
    float speed,
    float gravity,
    float drag,
    float sizeEnd,
    String emitterShape,
    String particleStyle,
    String particleMotion,
    float timelineWarmup,
    float timelineBeamTime,
    float ringScale,
    float sigilScale,
    String programJson,
    int maxParticles,
    float turbulence,
    String collisionResponse,
    int collisionRadius,
    boolean collisionFluids,
    float collisionRestitution,
    float collisionFriction,
    float lightRadius,
    float lightIntensity,
    int lightColor
) implements CustomPacketPayload {
    public static final Type<KfxSpawnPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_spawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxSpawnPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.id);
            buf.writeUtf(p.kind);
            buf.writeInt(p.color);
            buf.writeInt(p.color2);
            buf.writeFloat(p.sx); buf.writeFloat(p.sy); buf.writeFloat(p.sz);
            buf.writeFloat(p.ex); buf.writeFloat(p.ey); buf.writeFloat(p.ez);
            buf.writeFloat(p.radius);
            buf.writeFloat(p.thickness);
            buf.writeInt(p.lifetime);
            buf.writeBoolean(p.loop);
            buf.writeFloat(p.spinY);
            buf.writeFloat(p.pulseSpeed);
            buf.writeFloat(p.pulseAmount);
            buf.writeFloat(p.fadeIn);
            buf.writeFloat(p.fadeOut);
            buf.writeFloat(p.emitterRate);
            buf.writeInt(p.emitterBurst);
            buf.writeInt(p.particleLifetime);
            buf.writeFloat(p.spread);
            buf.writeFloat(p.speed);
            buf.writeFloat(p.gravity);
            buf.writeFloat(p.drag);
            buf.writeFloat(p.sizeEnd);
            buf.writeUtf(p.emitterShape);
            buf.writeUtf(p.particleStyle);
            buf.writeUtf(p.particleMotion);
            buf.writeFloat(p.timelineWarmup);
            buf.writeFloat(p.timelineBeamTime);
            buf.writeFloat(p.ringScale);
            buf.writeFloat(p.sigilScale);
            buf.writeUtf(p.programJson);
            buf.writeInt(p.maxParticles);
            buf.writeFloat(p.turbulence);
            buf.writeUtf(p.collisionResponse);
            buf.writeInt(p.collisionRadius);
            buf.writeBoolean(p.collisionFluids);
            buf.writeFloat(p.collisionRestitution);
            buf.writeFloat(p.collisionFriction);
            buf.writeFloat(p.lightRadius);
            buf.writeFloat(p.lightIntensity);
            buf.writeInt(p.lightColor);
        },
        buf -> new KfxSpawnPayload(buf.readLong(), buf.readUtf(), buf.readInt(), buf.readInt(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readInt(), buf.readBoolean(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readInt(), buf.readInt(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readUtf(), buf.readUtf(), buf.readUtf(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readUtf(), buf.readInt(), buf.readFloat(),
            buf.readUtf(), buf.readInt(), buf.readBoolean(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readInt())
    );

    public static KfxSpawnPayload of(KfxInstance fx) {
        return new KfxSpawnPayload(fx.id, fx.kind.name(), fx.color, fx.color2,
            fx.sx, fx.sy, fx.sz, fx.ex, fx.ey, fx.ez,
            fx.radius, fx.thickness, fx.lifetime, fx.loop, fx.spinY,
            fx.pulseSpeed, fx.pulseAmount, fx.fadeIn, fx.fadeOut,
            fx.emitterRate, fx.emitterBurst, fx.particleLifetime, fx.spread,
            fx.speed, fx.gravity, fx.drag, fx.sizeEnd,
            fx.emitterShape, fx.particleStyle, fx.particleMotion,
            fx.timelineWarmup, fx.timelineBeamTime, fx.ringScale, fx.sigilScale,
            fx.programJson, fx.maxParticles, fx.turbulence, fx.collisionResponse, fx.collisionRadius,
            fx.collisionFluids, fx.collisionRestitution, fx.collisionFriction,
            fx.light.radius(), fx.light.intensity(), fx.light.color());
    }

    public KfxInstance toInstance() {
        KfxDef.Kind parsed = KfxDef.Kind.valueOf(kind);
        return new KfxInstance(id, parsed, color, color2, sx, sy, sz, ex, ey, ez, radius, thickness,
            lifetime, loop, spinY, pulseSpeed, pulseAmount, fadeIn, fadeOut,
            emitterRate, emitterBurst, particleLifetime, spread, speed, gravity, drag, sizeEnd,
            emitterShape, particleStyle, particleMotion, timelineWarmup, timelineBeamTime, ringScale, sigilScale,
            programJson, maxParticles, turbulence, collisionResponse, collisionRadius, collisionFluids,
            collisionRestitution, collisionFriction,
            new KfxLight(lightRadius, lightIntensity, lightColor));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
