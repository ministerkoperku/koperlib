package com.koper.koper_lib.compat.create;

import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import com.zurrtum.create.api.stress.BlockStressValues;
import com.zurrtum.create.content.kinetics.base.IRotate;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// External engines can drive a real Create relay without pretending the whole block is a joint.
public final class KoperCreateKinetics {

    public record Drive(float rpm, float capacitySu, Direction sourceSide) {
        public Drive(float rpm, float capacitySu) {
            this(rpm, capacitySu, null);
        }

        public float capacityPerRpm() {
            float speed = Math.abs(rpm);
            return speed < 1.0e-4f ? 0f : capacitySu / speed;
        }
    }

    private record Key(Level level, BlockPos pos) {}

    private static final Map<Key, Drive> DRIVES = new ConcurrentHashMap<>();

    private KoperCreateKinetics() {}

    public static boolean isPassiveTransmission(LevelReader level, BlockPos pos,
                                                BlockState state, Direction bearingSide) {
        if (!hasShaftTowards(level, pos, state, bearingSide)) return false;
        return BlockStressValues.getImpact(state.getBlock()) <= 0
            && BlockStressValues.getCapacity(state.getBlock()) <= 0;
    }

    public static boolean hasShaftTowards(LevelReader level, BlockPos pos,
                                         BlockState state, Direction side) {
        return state.getBlock() instanceof IRotate rotate
            && rotate.hasShaftTowards(level, pos, state, side);
    }

    public static Drive driveAt(KineticBlockEntity kinetic) {
        Level level = kinetic.getLevel();
        return level == null ? null : DRIVES.get(new Key(level, kinetic.getBlockPos()));
    }

    public static boolean drive(ServerLevel level, BlockPos pos, float rpm, float capacitySu) {
        return drive(level, pos, rpm, capacitySu, null);
    }

    public static boolean drive(ServerLevel level, BlockPos pos, float rpm, float capacitySu,
                                Direction sourceSide) {
        Key key = new Key(level, pos.immutable());
        Drive next = new Drive(rpm, Math.max(0f, capacitySu), sourceSide);
        Drive old = DRIVES.put(key, next);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof KineticBlockEntity kinetic)) {
            DRIVES.remove(key);
            return false;
        }

        if (old == null || Math.abs(old.rpm() - next.rpm()) > 1.0e-4f
                || old.sourceSide() != next.sourceSide()) {
            rebuildSource(kinetic, rpm);
        } else if (kinetic.hasNetwork()) {
            var network = kinetic.getOrCreateNetwork();
            // Create multiplies this coefficient by source RPM when it calculates network SU.
            network.updateCapacityFor(kinetic, next.capacityPerRpm());
            network.updateStress();
        }
        return true;
    }

    public static void setVisualSpeed(BlockEntity blockEntity, float rpm) {
        if (blockEntity instanceof KineticBlockEntity kinetic) kinetic.setSpeed(rpm);
    }

    public static void stop(ServerLevel level, BlockPos pos) {
        Key key = new Key(level, pos.immutable());
        Drive old = DRIVES.remove(key);
        if (old == null) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof KineticBlockEntity kinetic)
            rebuildSource(kinetic, 0f);
    }

    public static float networkStress(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof KineticBlockEntity kinetic) || !kinetic.hasNetwork()) return 0f;
        return kinetic.getOrCreateNetwork().calculateStress();
    }

    private static void rebuildSource(KineticBlockEntity kinetic, float rpm) {
        Level level = kinetic.getLevel();
        if (level == null || level.isClientSide()) return;
        KontraGrid grid = level instanceof ServerLevel serverLevel
            ? KoperPhys.gridAtLogical(serverLevel, kinetic.getBlockPos()) : null;
        Runnable rebuild = () -> {
            float oldSpeed = kinetic.getTheoreticalSpeed();
            if (oldSpeed != 0f || kinetic.hasNetwork()) kinetic.detachKinetics();
            kinetic.source = null;
            kinetic.setNetwork(null);
            kinetic.setSpeed(rpm);
            if (rpm != 0f) {
                kinetic.setNetwork(kinetic.getBlockPos().asLong());
                kinetic.attachKinetics();
            }
            kinetic.onSpeedChanged(oldSpeed);
            kinetic.sendData();
        };
        if (grid == null) rebuild.run();
        else KontraGridContext.run(grid, rebuild);
    }
}
