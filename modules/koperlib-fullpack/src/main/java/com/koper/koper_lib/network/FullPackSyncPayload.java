package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server-declared manifest. Data is informational; clients never auto-download its links. */
public record FullPackSyncPayload(List<PackEntry> packs) implements CustomPacketPayload {
    public record PackEntry(String id, String name, String version, String sha256, long bytes,
                            List<String> requiredMods, List<String> requiredPacks,
                            List<String> links, boolean executableContent) {
        public PackEntry {
            requiredMods = List.copyOf(requiredMods);
            requiredPacks = List.copyOf(requiredPacks);
            links = List.copyOf(links);
        }
    }

    public FullPackSyncPayload { packs = List.copyOf(packs); }

    public static final Type<FullPackSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "sync_packs"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FullPackSyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarInt(payload.packs().size());
            for (PackEntry entry : payload.packs()) {
                buf.writeUtf(entry.id(), 128);
                buf.writeUtf(entry.name(), 256);
                buf.writeUtf(entry.version(), 64);
                buf.writeUtf(entry.sha256(), 64);
                buf.writeVarLong(entry.bytes());
                writeList(buf, entry.requiredMods(), 128);
                writeList(buf, entry.requiredPacks(), 128);
                writeList(buf, entry.links(), 512);
                buf.writeBoolean(entry.executableContent());
            }
        },
        buf -> {
            int size = bounded(buf.readVarInt(), 0, 1024, "pack count");
            List<PackEntry> packs = new ArrayList<>(size);
            for (int i = 0; i < size; i++) packs.add(new PackEntry(
                buf.readUtf(128), buf.readUtf(256), buf.readUtf(64), buf.readUtf(64), buf.readVarLong(),
                readList(buf, 128, 128), readList(buf, 128, 128), readList(buf, 8, 512), buf.readBoolean()));
            return new FullPackSyncPayload(packs);
        }
    );

    private static void writeList(RegistryFriendlyByteBuf buf, List<String> values, int maxChars) {
        buf.writeVarInt(values.size());
        for (String value : values) buf.writeUtf(value, maxChars);
    }

    private static List<String> readList(RegistryFriendlyByteBuf buf, int maxItems, int maxChars) {
        int size = bounded(buf.readVarInt(), 0, maxItems, "manifest list");
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) values.add(buf.readUtf(maxChars));
        return List.copyOf(values);
    }

    private static int bounded(int value, int min, int max, String label) {
        if (value < min || value > max) throw new IllegalArgumentException(label + " outside " + min + ".." + max);
        return value;
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
