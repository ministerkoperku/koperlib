package com.koper.koper_lib.api.companion;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Shared follow/stay/attack formation tick. Resource reservation remains addon-defined. */
public final class KoperArmy {
    private KoperArmy() {}

    public static void tick(MinecraftServer server, String group, double movementSpeed,
            BiConsumer<ServerPlayer, Double> reservationSink) {
        for (ServerPlayer owner : server.getPlayerList().getPlayers()) {
            List<Mob> army = KoperCompanions.owned(server, owner.getUUID(), group);
            double reserved = army.stream().mapToDouble(KoperCompanions::reservedResource).sum();
            if (reservationSink != null) reservationSink.accept(owner, reserved);
            for (int index = 0; index < army.size(); index++)
                tickMember(army.get(index), owner, index, movementSpeed);
        }
    }

    public static List<Mob> setMode(MinecraftServer server, UUID owner, String group,
            KoperCompanions.Mode mode) {
        List<Mob> changed = new ArrayList<>(KoperCompanions.owned(server, owner, group));
        for (Mob mob : changed) KoperCompanions.setMode(mob, mode);
        return changed;
    }

    private static void tickMember(Mob mob, ServerPlayer owner, int index, double movementSpeed) {
        if (mob.level() != owner.level()) return;
        KoperCompanions.Mode mode = KoperCompanions.mode(mob);
        if (mode == KoperCompanions.Mode.STAY) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            return;
        }
        if (mode == KoperCompanions.Mode.ATTACK && mob.getTarget() != null && mob.getTarget().isAlive()) return;
        KoperCompanions.setMode(mob, KoperCompanions.Mode.FOLLOW);
        Vec3 forward = owner.getLookAngle().multiply(1, 0, 1);
        if (forward.lengthSqr() < .01) forward = new Vec3(0, 0, 1);
        else forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        int row = index / 5;
        int column = index % 5;
        Vec3 position = owner.position().subtract(forward.scale(2.5 + row * 1.8))
                .add(right.scale((column - 2) * 1.7));
        if (mob.distanceToSqr(owner) > 1024) {
            mob.snapTo(position.x, owner.getY(), position.z, owner.getYRot(), 0);
            mob.getNavigation().stop();
        } else if (mob.position().distanceToSqr(position) > 4) {
            mob.getNavigation().moveTo(position.x, position.y, position.z, movementSpeed);
        }
    }
}
