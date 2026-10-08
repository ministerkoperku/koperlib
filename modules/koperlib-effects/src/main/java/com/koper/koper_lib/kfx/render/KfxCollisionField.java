package com.koper.koper_lib.kfx.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.Optional;

/** Compact local cosmetic occupancy. It has no server/gameplay event surface by design. */
public final class KfxCollisionField {
    public static final byte SOLID = 1;
    public static final byte FLUID = 2;
    public static final int HARD_MAX_SIDE = 33;

    private final BlockPos origin;
    private final int side;
    private final byte[] cells;
    private final long fingerprint;

    public KfxCollisionField(BlockPos origin, int side, byte[] cells) {
        if (origin == null || side < 1 || side > HARD_MAX_SIDE) {
            throw new IllegalArgumentException("KFX collision field side must be 1.." + HARD_MAX_SIDE);
        }
        int expected = Math.multiplyExact(side, Math.multiplyExact(side, side));
        if (cells == null || cells.length != expected) throw new IllegalArgumentException("KFX collision field cell count mismatch");
        this.origin = origin.immutable();
        this.side = side;
        this.cells = cells.clone();
        this.fingerprint = fingerprint(this.cells);
    }

    public static KfxCollisionField empty(BlockPos origin, int side) {
        return new KfxCollisionField(origin, side, new byte[side * side * side]);
    }

    /** Captures only loaded blocks. Call on the client thread around an opted-in emitter. */
    public static KfxCollisionField capture(Level level, BlockPos center, int radius, boolean fluids) {
        if (level == null || center == null || radius < 1 || radius > 16) {
            throw new IllegalArgumentException("KFX collision capture radius must be 1..16");
        }
        int side = radius * 2 + 1;
        BlockPos origin = center.offset(-radius, -radius, -radius);
        byte[] cells = new byte[side * side * side];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (!level.hasChunkAt(cursor)) continue;
            var state = level.getBlockState(cursor);
            byte value = state.getCollisionShape(level, cursor).isEmpty() ? 0 : SOLID;
            if (fluids && !state.getFluidState().isEmpty()) value |= FLUID;
            cells[(y * side + z) * side + x] = value;
        }
        return new KfxCollisionField(origin, side, cells);
    }

    public boolean upload(long handle, KfxParticleResponse response, float restitution, float friction) {
        if (response == null) throw new IllegalArgumentException("KFX collision response is required");
        boolean ok = com.koper.koper_lib.kender.KenderBridge.emitterCollision(handle, cells,
            origin.getX(), origin.getY(), origin.getZ(), side, response.ordinal() + 1, restitution, friction);
        if (ok) com.koper.koper_lib.kfx.KfxDiagnostics.collision(handle, cellCount(),
            response.name().toLowerCase(), fingerprint);
        return ok;
    }

    public BlockPos origin() { return origin; }
    public int side() { return side; }
    public int cellCount() { return cells.length; }
    public long fingerprint() { return fingerprint; }
    public byte[] cells() { return cells.clone(); }

    public byte cell(int x, int y, int z) {
        int localX = x - origin.getX(), localY = y - origin.getY(), localZ = z - origin.getZ();
        if (localX < 0 || localY < 0 || localZ < 0 || localX >= side || localY >= side || localZ >= side) return 0;
        return cells[(localY * side + localZ) * side + localX];
    }

    public Optional<Contact> sweep(Vec3 from, Vec3 to, int maxSamples) {
        if (from == null || to == null || maxSamples < 1) return Optional.empty();
        double length = from.distanceTo(to);
        int samples = Math.min(maxSamples, Math.max(1, (int)Math.ceil(length * 4.0)));
        BlockPos previous = BlockPos.containing(from);
        for (int step = 1; step <= samples; step++) {
            Vec3 point = from.lerp(to, step / (double)samples);
            BlockPos block = BlockPos.containing(point);
            byte occupancy = cell(block.getX(), block.getY(), block.getZ());
            if ((occupancy & (SOLID | FLUID)) != 0) {
                Vec3 normal = new Vec3(previous.getX() - block.getX(), previous.getY() - block.getY(),
                    previous.getZ() - block.getZ());
                if (normal.lengthSqr() < 0.5) normal = from.subtract(to).normalize();
                else normal = normal.normalize();
                return Optional.of(new Contact(point, normal, occupancy));
            }
            previous = block;
        }
        return Optional.empty();
    }

    private static long fingerprint(byte[] cells) {
        long hash = 0xcbf29ce484222325L;
        for (byte cell : cells) { hash ^= cell & 0xffL; hash *= 0x100000001b3L; }
        return hash;
    }

    public record Contact(Vec3 position, Vec3 normal, byte occupancy) {}

    @Override public boolean equals(Object other) {
        return other instanceof KfxCollisionField field && side == field.side && origin.equals(field.origin)
            && Arrays.equals(cells, field.cells);
    }
    @Override public int hashCode() { return 31 * (31 * origin.hashCode() + side) + Arrays.hashCode(cells); }
}
