package com.koper.koper_lib.api.attachment;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KoperPlacementRotation {

    public record State(int turns, int axisTurns) {
        public State {
            turns = Math.floorMod(turns, 4);
            axisTurns = Math.floorMod(axisTurns, 6);
        }
    }

    private static final Map<UUID, State> SERVER = new ConcurrentHashMap<>();

    private KoperPlacementRotation() {}

    public static void set(ServerPlayer player, int turns, int axisTurns) {
        SERVER.put(player.getUUID(), new State(turns, axisTurns));
    }

    public static State get(ServerPlayer player) {
        return SERVER.getOrDefault(player.getUUID(), new State(0, 0));
    }

    public static void clear(ServerPlayer player) {
        SERVER.remove(player.getUUID());
    }

    public static BlockState apply(BlockState state, State rotation) {
        if (state == null) return null;
        for (int i = 0; i < rotation.turns(); i++) state = turnY(state);
        if (rotation.axisTurns() != 0 && state.hasProperty(BlockStateProperties.FACING)) {
            Direction[] directions = Direction.values();
            Direction current = state.getValue(BlockStateProperties.FACING);
            state = state.setValue(BlockStateProperties.FACING,
                    directions[Math.floorMod(current.ordinal() + rotation.axisTurns(), directions.length)]);
        } else if (rotation.axisTurns() != 0 && state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis[] axes = Direction.Axis.values();
            Direction.Axis current = state.getValue(BlockStateProperties.AXIS);
            state = state.setValue(BlockStateProperties.AXIS,
                    axes[Math.floorMod(current.ordinal() + rotation.axisTurns(), axes.length)]);
        }
        return state;
    }

    private static BlockState turnY(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction current = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, clockwise(current));
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction current = state.getValue(BlockStateProperties.FACING);
            return state.setValue(BlockStateProperties.FACING, clockwise(current));
        }
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
            if (axis == Direction.Axis.X) return state.setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
            if (axis == Direction.Axis.Z) return state.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        }
        return state;
    }

    private static Direction clockwise(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> direction;
        };
    }
}
