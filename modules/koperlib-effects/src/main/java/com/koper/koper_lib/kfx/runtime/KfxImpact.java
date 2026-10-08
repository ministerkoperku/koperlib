package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxResolvedValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

public record KfxImpact(
    long handle,
    int nodeId,
    long sequence,
    UUID owner,
    UUID entity,
    BlockPos block,
    Vec3 position,
    Vec3 normal,
    Vec3 incomingVelocity,
    Vec3 outgoingVelocity,
    int bounce,
    long seed,
    Map<String, KfxResolvedValue> inputs
) {
    public KfxImpact {
        if (handle == 0 || owner == null || position == null || normal == null
            || incomingVelocity == null || outgoingVelocity == null) {
            throw new IllegalArgumentException("KFX impact is missing controller data");
        }
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public boolean hitEntity() { return entity != null; }
    public boolean hitBlock() { return block != null; }
}
