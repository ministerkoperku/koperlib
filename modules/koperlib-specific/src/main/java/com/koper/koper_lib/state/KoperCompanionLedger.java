package com.koper.koper_lib.state;

import com.koper.koper_lib.coremod.KoperCore;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-persistent source of truth for companion ownership.
 *
 * <p>Entity tags remain as a cheap loaded-entity cache, but are not trusted as
 * the only copy. This ledger lets ownership, group, command mode and reserved
 * resource survive entity serialization changes, chunk unloads and restarts.</p>
 */
public final class KoperCompanionLedger extends SavedData {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("koper_lib", "companion_ledger");

    public record Entry(UUID owner, String group, String mode, double reservedResource) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(builder -> builder.group(
            Codec.STRING.fieldOf("owner").forGetter(entry -> entry.owner.toString()),
            Codec.STRING.fieldOf("group").orElse("default").forGetter(Entry::group),
            Codec.STRING.fieldOf("mode").orElse("follow").forGetter(Entry::mode),
            Codec.DOUBLE.fieldOf("reserved_resource").orElse(0d).forGetter(Entry::reservedResource)
        ).apply(builder, (owner, group, mode, reserved) ->
            new Entry(parseUuid(owner), group, mode, Math.max(0, reserved))));

        private static UUID parseUuid(String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                return new UUID(0, 0);
            }
        }
    }

    private static final Codec<Map<String, Entry>> RAW_CODEC = Codec.unboundedMap(Codec.STRING, Entry.CODEC);
    private static final Codec<KoperCompanionLedger> CODEC = RecordCodecBuilder.create(builder -> builder.group(
        RAW_CODEC.fieldOf("companions").forGetter(KoperCompanionLedger::export)
    ).apply(builder, KoperCompanionLedger::new));

    @SuppressWarnings("DataFlowIssue")
    private static final SavedDataType<KoperCompanionLedger> TYPE =
        new SavedDataType<>(ID, KoperCompanionLedger::new, CODEC, null);

    private final Map<UUID, Entry> companions;

    private KoperCompanionLedger() {
        companions = new LinkedHashMap<>();
    }

    private KoperCompanionLedger(Map<String, Entry> raw) {
        companions = new LinkedHashMap<>(raw.size());
        for (var entry : raw.entrySet()) {
            try {
                UUID entityId = UUID.fromString(entry.getKey());
                Entry value = entry.getValue();
                if (!value.owner().equals(new UUID(0, 0))) companions.put(entityId, value);
            } catch (IllegalArgumentException badId) {
                KoperCore.LOGGER.warn("[Companions] dropping ledger entry with bad entity uuid '{}'", entry.getKey());
            }
        }
    }

    private Map<String, Entry> export() {
        Map<String, Entry> result = new LinkedHashMap<>(companions.size());
        for (var entry : companions.entrySet()) result.put(entry.getKey().toString(), entry.getValue());
        return result;
    }

    private static KoperCompanionLedger ledger(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static Entry get(MinecraftServer server, UUID entityId) {
        return ledger(server).companions.get(entityId);
    }

    public static void put(MinecraftServer server, UUID entityId, Entry entry) {
        KoperCompanionLedger ledger = ledger(server);
        if (entry.equals(ledger.companions.put(entityId, entry))) return;
        ledger.setDirty();
    }

    public static void remove(MinecraftServer server, UUID entityId) {
        KoperCompanionLedger ledger = ledger(server);
        if (ledger.companions.remove(entityId) != null) ledger.setDirty();
    }
}
