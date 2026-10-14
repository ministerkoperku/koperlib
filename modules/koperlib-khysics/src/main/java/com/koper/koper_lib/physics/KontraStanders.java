package com.koper.koper_lib.physics;

import com.koper.koper_lib.network.KontraStandersPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

// who stands on which deck, in the deck's frame, measured when the carry put them there. clients draw
// those entities on the deck as they draw it, instead of where vanilla's lerp is, ticks behind a fast ship
public final class KontraStanders {
    private record Stand(Entity entity, float x, float y, float z) {}
    private static final java.util.Map<Long, java.util.List<Stand>> THIS_TICK = new java.util.HashMap<>();

    private KontraStanders() {}

    static void note(Entity entity, net.minecraft.world.phys.Vec3 at, long deckId, float[] pose) {
        if (entity.level().isClientSide() || pose == null) return;
        var frame = new KontraFrame(new net.minecraft.world.phys.Vec3(pose[0], pose[1], pose[2]),
            new org.joml.Quaterniond(pose[3], pose[4], pose[5], pose[6]),
            net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO, 0);
        var local = frame.toLocal(at);
        THIS_TICK.computeIfAbsent(deckId, k -> new java.util.ArrayList<>())
            .add(new Stand(entity, (float)local.x, (float)local.y, (float)local.z));
    }

    public static void tick(MinecraftServer server) {
        if (THIS_TICK.isEmpty()) return;
        for (var e : THIS_TICK.entrySet()) {
            var stands = e.getValue();
            if (stands.isEmpty() || !(stands.get(0).entity().level() instanceof ServerLevel level)) continue;
            int[] ids = new int[stands.size()];
            float[] locals = new float[stands.size() * 3];
            for (int i = 0; i < stands.size(); i++) {
                Stand s = stands.get(i);
                ids[i] = s.entity().getId();
                locals[i * 3] = s.x(); locals[i * 3 + 1] = s.y(); locals[i * 3 + 2] = s.z();
            }
            com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level, new KontraStandersPayload(e.getKey(), ids, locals));
        }
        THIS_TICK.clear();
    }
}
