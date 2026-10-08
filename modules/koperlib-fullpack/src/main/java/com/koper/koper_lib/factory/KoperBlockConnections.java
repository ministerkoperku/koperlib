package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.data.KoperBlockData;
import com.koper.koper_lib.loader.ContentRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public final class KoperBlockConnections {
    private KoperBlockConnections() {}

    public record CubeInfo(boolean valid, int size, int count, BlockPos min, BlockPos max) {
        public String keyPart() {
            return min == null ? "none" : min.getX() + "_" + min.getY() + "_" + min.getZ() + "_" + size;
        }

        public String keyPart(int forcedSize) {
            return min == null ? "none" : min.getX() + "_" + min.getY() + "_" + min.getZ() + "_" + forcedSize;
        }

        public int spanSize() {
            if (min == null || max == null) return 0;
            int sx = max.getX() - min.getX() + 1;
            int sy = max.getY() - min.getY() + 1;
            int sz = max.getZ() - min.getZ() + 1;
            return Math.max(sx, Math.max(sy, sz));
        }
    }

    public static boolean enabled(KoperBlockData data) {
        return data != null && data.connectGroup != null && !data.connectGroup.isBlank();
    }

    public static BlockState stateFor(Level level, BlockPos pos, BlockState state, KoperBlockData data) {
        if (!enabled(data)) return state;
        state = setDir(state, Direction.NORTH, connects(level, pos, data, Direction.NORTH));
        state = setDir(state, Direction.EAST, connects(level, pos, data, Direction.EAST));
        state = setDir(state, Direction.SOUTH, connects(level, pos, data, Direction.SOUTH));
        state = setDir(state, Direction.WEST, connects(level, pos, data, Direction.WEST));
        if (!Boolean.FALSE.equals(data.connectVertical)) {
            state = setDir(state, Direction.UP, connects(level, pos, data, Direction.UP));
            state = setDir(state, Direction.DOWN, connects(level, pos, data, Direction.DOWN));
        }
        return state;
    }

    // the vanilla route: EVERY neighbour change lands in updateShape, whatever caused it — piston,
    // explosion, /fill, worldgen, another mod's setBlock, a kontraption eating the block next door.
    // neighborState is handed to us already, so no world read and no ordering games.
    public static BlockState connectTo(BlockState state, Direction dir, BlockState neighborState,
            KoperBlockData data) {
        if (!enabled(data)) return state;
        if (dir.getAxis().isVertical() && Boolean.FALSE.equals(data.connectVertical)) return state;
        KoperBlockData other = data(neighborState);
        return setDir(state, dir, enabled(other) && data.connectGroup.equals(other.connectGroup));
    }

    public static void refreshSelfAndNeighbors(Level level, BlockPos pos, KoperBlockData data) {
        if (level == null || level.isClientSide() || !enabled(data)) return;
        refresh(level, pos);
        for (Direction dir : Direction.values()) refresh(level, pos.relative(dir));
    }

    public static JsonObject info(Level level, BlockPos pos) {
        JsonObject out = new JsonObject();
        out.addProperty("valid", false);
        if (level == null || pos == null) return out;
        KoperBlockData data = data(level.getBlockState(pos));
        if (!enabled(data)) return out;

        JsonArray dirs = new JsonArray();
        int count = 0;
        for (Direction dir : Direction.values()) {
            if (Boolean.FALSE.equals(data.connectVertical) && dir.getAxis().isVertical()) continue;
            if (!connects(level, pos, data, dir)) continue;
            dirs.add(dir.getName());
            count++;
        }
        out.addProperty("valid", true);
        out.addProperty("group", data.connectGroup);
        out.add("dirs", dirs);
        out.addProperty("count", count);
        return out;
    }

    public static int count(Level level, BlockPos pos) {
        JsonObject info = info(level, pos);
        return info.has("count") ? info.get("count").getAsInt() : 0;
    }

    public static CubeInfo cubeInfo(Level level, BlockPos start, int maxBlocks) {
        if (level == null || start == null) return new CubeInfo(false, 0, 0, null, null);
        KoperBlockData root = data(level.getBlockState(start));
        if (!enabled(root)) return new CubeInfo(false, 0, 0, null, null);

        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos startCopy = start.immutable();
        seen.add(startCopy);
        queue.add(startCopy);

        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = start.getX(), maxY = start.getY(), maxZ = start.getZ();
        int limit = Math.max(1, maxBlocks);

        while (!queue.isEmpty() && seen.size() <= limit) {
            BlockPos pos = queue.removeFirst();
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());

            for (Direction dir : Direction.values()) {
                if (Boolean.FALSE.equals(root.connectVertical) && dir.getAxis().isVertical()) continue;
                BlockPos next = pos.relative(dir).immutable();
                if (seen.contains(next)) continue;
                KoperBlockData other = data(level.getBlockState(next));
                if (!enabled(other) || !root.connectGroup.equals(other.connectGroup)) continue;
                seen.add(next);
                queue.add(next);
            }
        }

        if (seen.size() > limit) return new CubeInfo(false, 0, seen.size(), null, null);
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        int count = seen.size();
        boolean cube = sx == sy && sy == sz && count == sx * sy * sz;
        return new CubeInfo(cube, cube ? sx : 0, count,
            new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    private static void refresh(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        KoperBlockData data = data(state);
        if (!enabled(data)) return;
        BlockState next = stateFor(level, pos, state, data);
        if (next != state) level.setBlockAndUpdate(pos, next);
    }

    private static boolean connects(Level level, BlockPos pos, KoperBlockData data, Direction dir) {
        KoperBlockData other = data(level.getBlockState(pos.relative(dir)));
        return enabled(other) && data.connectGroup.equals(other.connectGroup);
    }

    private static KoperBlockData data(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return ContentRegistry.getBlockData(id);
    }

    private static BlockState setDir(BlockState state, Direction dir, boolean value) {
        return switch (dir) {
            case NORTH -> state.hasProperty(BlockStateProperties.NORTH) ? state.setValue(BlockStateProperties.NORTH, value) : state;
            case EAST -> state.hasProperty(BlockStateProperties.EAST) ? state.setValue(BlockStateProperties.EAST, value) : state;
            case SOUTH -> state.hasProperty(BlockStateProperties.SOUTH) ? state.setValue(BlockStateProperties.SOUTH, value) : state;
            case WEST -> state.hasProperty(BlockStateProperties.WEST) ? state.setValue(BlockStateProperties.WEST, value) : state;
            case UP -> state.hasProperty(BlockStateProperties.UP) ? state.setValue(BlockStateProperties.UP, value) : state;
            case DOWN -> state.hasProperty(BlockStateProperties.DOWN) ? state.setValue(BlockStateProperties.DOWN, value) : state;
        };
    }
}
