package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.List;

// behavior.nearest_attackable_target with its entity_types: zombies go for villagers and iron golems,
// wolves for sheep, a boss for anything with the "monster" family. every entry's filters run through
// BedrockFiltr with the candidate as "other", max_dist and must_see per entry. java's own target goal
// only knows one class, so this is a small goal of its own, built on nothing that moves between versions
public final class BedrockCelownik extends Goal {

    private final Mob mob;
    private final boolean mustSeeDefault;
    private LivingEntity found;
    private int cooldown;

    public BedrockCelownik(Mob mob, boolean mustSee) {
        this.mob = mob;
        this.mustSeeDefault = mustSee;
        setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
        if (--cooldown > 0) return false;
        cooldown = 10 + mob.getRandom().nextInt(10);
        found = szukaj();
        return found != null;
    }

    @Override
    public void start() {
        mob.setTarget(found);
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive() || t.isRemoved()) return false;
        if (t instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        double range = zasieg();
        return mob.distanceToSqr(t) < range * range * 2.25;
    }

    @Override
    public void stop() {
        found = null;
    }

    private double zasieg() {
        var a = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        return a != null ? Math.max(4, a.getValue()) : 16;
    }

    private LivingEntity szukaj() {
        List<JsonObject> types = BedrockZachowanie.celTypy(mob);
        if (types.isEmpty()) return null;
        double range = zasieg();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity c : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(range), x -> x != mob && x.isAlive())) {
            if (c instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            double d = mob.distanceToSqr(c);
            if (d >= bestD) continue;
            for (JsonObject t : types) {
                double max = t.has("max_dist") ? t.get("max_dist").getAsDouble() : range;
                if (d > max * max) continue;
                JsonElement f = t.get("filters");
                if (!BedrockFiltr.test(f, new BedrockFiltr.Kontekst(mob, c, null, null, null))) continue;
                boolean see = t.has("must_see") ? t.get("must_see").getAsBoolean() : mustSeeDefault;
                if (see && !mob.getSensing().hasLineOfSight(c)) continue;
                best = c;
                bestD = d;
                break;
            }
        }
        return best;
    }
}
