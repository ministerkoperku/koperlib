package com.koper.koper_lib.kodel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// S2C — play one clip on an entity's model, overriding the state driven pick for holdTicks.
// the renderer normally chooses from death/attack/walk/idle, so a script asking for a clip
// needs somewhere to say so
public record KodelClipPayload(int entityId, String clip, int holdTicks) implements CustomPacketPayload {

    public static final Type<KodelClipPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kodel_clip"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KodelClipPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId());
            buf.writeUtf(p.clip(), 128);
            buf.writeVarInt(p.holdTicks());
        },
        buf -> new KodelClipPayload(buf.readVarInt(), buf.readUtf(128), buf.readVarInt())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
