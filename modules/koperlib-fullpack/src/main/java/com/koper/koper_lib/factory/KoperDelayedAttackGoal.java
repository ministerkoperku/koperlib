package com.koper.koper_lib.factory;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

// bedrock's minecraft:behavior.delayed_attack: walk up at speed_multiplier, stop when in reach, wind up for
// attack_duration seconds and land the hit at hit_delay_pct of it (once with attack_once). the mob stands
// still the whole swing, that is the opening bosses like mowzie's ferrous give you. java's melee goal swung
// instantly on the run instead, at full speed
public class KoperDelayedAttackGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private final int duration;
    private final int hitAt;
    private final double reach;
    private final boolean once;
    private final boolean track;
    private int swing = -1;
    private boolean hit;
    private int repath;

    public KoperDelayedAttackGoal(PathfinderMob mob, double speed, float seconds, float hitPct, double reach, boolean once, boolean track) {
        this.mob = mob;
        this.speed = speed;
        this.duration = Math.max(1, Math.round(seconds * 20));
        this.hitAt = Math.max(0, Math.min(this.duration - 1, Math.round(this.duration * hitPct)));
        this.reach = reach;
        this.once = once;
        this.track = track;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        return t != null && t.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return swing >= 0 || canUse();
    }

    @Override
    public void stop() {
        swing = -1;
        hit = false;
        setAttacking(false);
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (swing >= 0) {
            if (track && t != null) mob.getLookControl().setLookAt(t, 30f, 30f);
            if (!hit && swing >= hitAt) {
                hit = true;
                if (t != null && t.isAlive() && inReach(t)) mob.doHurtTarget((net.minecraft.server.level.ServerLevel) mob.level(), t);
            }
            if (++swing >= duration) {
                swing = -1;
                setAttacking(false);
                // attack_once: one swing per time the group is on, the pack's timer moves the mob on
                if (once) hit = true;
                else hit = false;
            }
            return;
        }
        if (t == null || !t.isAlive()) return;
        if (once && hit) return;
        mob.getLookControl().setLookAt(t, 30f, 30f);
        if (inReach(t)) {
            mob.getNavigation().stop();
            swing = 0;
            hit = false;
            setAttacking(true);
            mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
        } else if (speed > 0 && --repath <= 0) {
            repath = 10;
            mob.getNavigation().moveTo(t, speed);
        }
    }

    // bedrock's reach is the mob's own width times two, scaled
    private boolean inReach(LivingEntity t) {
        if (reach <= 0) return false;
        double r = mob.getBbWidth() * 2.0 * reach + t.getBbWidth();
        return mob.distanceToSqr(t) <= r * r;
    }

    private void setAttacking(boolean on) {
        if (mob instanceof EntityFactory.KoperMobEntity km) km.setDelayedAttacking(on);
    }
}
