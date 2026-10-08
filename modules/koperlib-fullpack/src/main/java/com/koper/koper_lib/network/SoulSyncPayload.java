package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// server -> client soul snapshot for one player. entries are "ns:key" -> tagged value (see KoperSoulCodec).
// a value of "x" is a tombstone -> client drops that key. used for both single set deltas and full join dumps.
public record SoulSyncPayload(UUID player, Map<String, String> entries) implements CustomPacketPayload {

    public static final Type<SoulSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "soul_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SoulSyncPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.player.getMostSignificantBits());
            buf.writeLong(p.player.getLeastSignificantBits());
            buf.writeVarInt(p.entries.size());
            for (var e : p.entries.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeUtf(e.getValue());
            }
        },
        buf -> {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            int n = buf.readVarInt();
            Map<String, String> m = new LinkedHashMap<>(Math.max(4, n));
            for (int i = 0; i < n; i++) m.put(buf.readUtf(), buf.readUtf());
            return new SoulSyncPayload(id, m);
        }
    );

    public static SoulSyncPayload one(UUID player, String nsKey, String tagged) {
        Map<String, String> m = new LinkedHashMap<>(1);
        m.put(nsKey, tagged);
        return new SoulSyncPayload(player, m);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
