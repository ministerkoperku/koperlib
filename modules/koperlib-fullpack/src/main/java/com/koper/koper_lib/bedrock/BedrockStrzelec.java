package com.koper.koper_lib.bedrock;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.EnumSet;

// behavior.ranged_attack + minecraft:shooter: walk until the target is in attack_radius and in sight,
// stand, look, shoot "def" every attack interval. the projectile is made from its entity type and
// thrown with Projectile.shoot, no per class constructors (those move around between versions).
// a pack's own projectile that converted to a plain mob just gets pushed the same way
public final class BedrockStrzelec extends Goal {

    private final Mob mob;
    private final double speed;
    private final int interval;
    private final float radius;
    private int reload;
    private int seen;

    public BedrockStrzelec(Mob mob, double speed, int interval, float radius) {
        this.mob = mob;
        this.speed = speed;
        this.interval = Math.max(1, interval);
        this.radius = Math.max(1f, radius);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        return t != null && t.isAlive();
    }

    @Override
    public void stop() {
        seen = 0;
        reload = interval / 2;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        boolean sees = mob.getSensing().hasLineOfSight(t);
        seen = sees ? seen + 1 : 0;
        double d = mob.distanceToSqr(t);
        if (d > radius * radius || seen < 5) mob.getNavigation().moveTo(t, speed);
        else mob.getNavigation().stop();
        mob.getLookControl().setLookAt(t, 30f, 30f);
        if (--reload > 0 || !sees || d > radius * radius) return;
        reload = interval;
        shoot(mob, t);
    }

    static void shoot(Mob mob, LivingEntity t) {
        if (!(mob.level() instanceof ServerLevel sl)) return;
        JsonObject sh = BedrockZachowanie.strzelba(mob);
        String def = sh != null ? BedrockTlumacz.str(sh, "def", "minecraft:arrow") : "minecraft:arrow";
        Identifier id = Identifier.tryParse(com.koper.koper_lib.api.core.BedrockNazwy.doJavy(def));
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) { BedrockZachowanie.once("shooter def " + def + " is no entity type here"); return; }
        Entity e = type.create(sl, EntitySpawnReason.MOB_SUMMONED);
        if (e == null) return;
        double y = mob.getEyeY() - 0.1;
        e.snapTo(mob.getX(), y, mob.getZ(), mob.getYRot(), mob.getXRot());
        double dx = t.getX() - mob.getX(), dz = t.getZ() - mob.getZ();
        double dy = t.getY(0.3333) - y;
        double h = Math.sqrt(dx * dx + dz * dz);
        float power = sh != null && sh.has("power") ? sh.get("power").getAsFloat() : 1.6f;
        float spread = 14 - sl.getDifficulty().getId() * 4;
        if (e instanceof Projectile p) {
            p.setOwner(mob);
            p.shoot(dx, dy + h * 0.2, dz, power, spread);
        } else {
            BedrockPocisk.wlasciciel(e, mob);
            double len = Math.max(1e-4, Math.sqrt(dx * dx + dy * dy + dz * dz));
            e.setDeltaMovement(dx / len * power, dy / len * power + 0.1, dz / len * power);
        }
        sl.addFreshEntity(e);
        if (sh != null && sh.has("sound")) BedrockZachowanie.dzwiek(mob, BedrockTlumacz.str(sh, "sound", ""));
    }
}
