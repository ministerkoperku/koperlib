package com.koper.koper_lib.api.local;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// Per-block addon data that follows a block through world, kontra, lift and dimension storage.
// This is deliberately not a BlockEntity substitute: providers own compact payloads.
public final class KoperLocalData {
    public static final String TAG_KEY = "koper_local_data";

    public interface Provider {
        CompoundTag capture(ServerLevel level, BlockPos pos, BlockState state);
        void restore(ServerLevel level, BlockPos pos, BlockState state, CompoundTag data);

        default Physics physics(BlockState state, CompoundTag data, float fallbackMass) {
            return null;
        }
    }

    public record Physics(int resolution, long occupiedCells, float mass) {}

    private record Entry(String key, Provider provider) {}
    private static final Map<Block, List<Entry>> PROVIDERS = new IdentityHashMap<>();

    private KoperLocalData() {}

    public static synchronized void register(Block block, Identifier id, Provider provider) {
        if (block == null || id == null || provider == null) return;
        List<Entry> entries = PROVIDERS.computeIfAbsent(block, ignored -> new ArrayList<>());
        entries.removeIf(entry -> entry.key().equals(id.toString()));
        entries.add(new Entry(id.toString(), provider));
    }

    public static CompoundTag capture(ServerLevel level, BlockPos pos, BlockState state) {
        List<Entry> entries = PROVIDERS.get(state.getBlock());
        if (entries == null || entries.isEmpty()) return null;
        CompoundTag out = new CompoundTag();
        for (Entry entry : entries) {
            try {
                CompoundTag value = entry.provider().capture(level, pos, state);
                if (value != null && !value.isEmpty()) out.put(entry.key(), value);
            } catch (Throwable error) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                    "[KoperLocalData] capture {} failed at {}: {}", entry.key(), pos, error.getMessage());
            }
        }
        return out.isEmpty() ? null : out;
    }

    public static void restore(ServerLevel level, BlockPos pos, BlockState state, CompoundTag data) {
        if (data == null || data.isEmpty()) return;
        List<Entry> entries = PROVIDERS.get(state.getBlock());
        if (entries == null) return;
        for (Entry entry : entries) {
            CompoundTag value = data.getCompound(entry.key()).orElse(null);
            if (value == null) continue;
            try {
                entry.provider().restore(level, pos, state, value.copy());
            } catch (Throwable error) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                    "[KoperLocalData] restore {} failed at {}: {}", entry.key(), pos, error.getMessage());
            }
        }
    }

    public static CompoundTag unpack(CompoundTag carrier) {
        return carrier == null ? null : carrier.getCompound(TAG_KEY).map(CompoundTag::copy).orElse(null);
    }

    public static CompoundTag pack(CompoundTag carrier, CompoundTag localData) {
        if (localData == null || localData.isEmpty()) return carrier;
        CompoundTag out = carrier == null ? new CompoundTag() : carrier;
        out.put(TAG_KEY, localData.copy());
        return out;
    }

    public static Physics physics(BlockState state, CompoundTag data, float fallbackMass) {
        if (state == null || data == null || data.isEmpty()) return null;
        List<Entry> entries = PROVIDERS.get(state.getBlock());
        if (entries == null) return null;
        for (Entry entry : entries) {
            CompoundTag value = data.getCompound(entry.key()).orElse(null);
            if (value == null) continue;
            try {
                Physics physics = entry.provider().physics(state, value, fallbackMass);
                if (physics != null) return physics;
            } catch (Throwable error) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                    "[KoperLocalData] physics {} failed: {}", entry.key(), error.getMessage());
            }
        }
        return null;
    }
}
