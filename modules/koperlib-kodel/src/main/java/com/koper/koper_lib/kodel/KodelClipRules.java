package com.koper.koper_lib.kodel;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;

// evaluates the entity json "animation_conditions" list client side, because every state it
// asks about (health, fire, water, swinging) is already on the client entity.
// first match wins, so put "always" last.
public final class KodelClipRules {

    private KodelClipRules() {}

    public record Rule(String when, double value, String play) {}

    // null = no rule matched, let the state machine pick
    public static String pick(List<Rule> rules, LivingEntity e) {
        if (rules.isEmpty()) return null;
        for (Rule r : rules) {
            if (matches(r, e)) return r.play();
        }
        return null;
    }

    private static boolean matches(Rule r, LivingEntity e) {
        return switch (r.when()) {
            case "always"            -> true;
            case "health_below"      -> e.getHealth() < r.value();
            case "health_above"      -> e.getHealth() > r.value();
            case "health_percent_below" -> e.getMaxHealth() > 0 && (e.getHealth() / e.getMaxHealth()) * 100.0 < r.value();
            case "on_fire"           -> e.isOnFire();
            case "in_water"          -> e.isInWater();
            case "in_lava"           -> e.isInLava();
            case "on_ground"         -> e.onGround();
            case "in_air"            -> !e.onGround();
            case "sneaking"          -> e.isCrouching();
            case "sprinting"         -> e.isSprinting();
            case "attacking"         -> e.isSwinging();
            case "dying"             -> e.deathTime > 0;
            case "moving"            -> e.walkAnimation.speed() > 0.05f;
            case "still"             -> e.walkAnimation.speed() <= 0.05f;
            case "baby"              -> e.isBaby();
            case "invisible"         -> e.isInvisible();
            case "riding"            -> e.isPassenger();
            case "just_spawned"      -> e.tickCount < r.value();
            default -> {
                // unknown condition behaves like "always" so a typo shows as a stuck pose
                // rather than a mob that silently ignores the whole list
                yield true;
            }
        };
    }
}
