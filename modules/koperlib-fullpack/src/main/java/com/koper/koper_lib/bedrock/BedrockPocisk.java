package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

// minecraft:projectile on an addon mob (fireballs, spears, spells). the converter makes it a mob with no
// ai, so java neither walks nor drops it; this flies it: gravity, inertia (liquid_inertia in water),
// the first block or entity on the way, then on_hit. the shooter is remembered in a "bowner:<uuid>" tag
public final class BedrockPocisk {

    private BedrockPocisk() {}

    static final String OWNER = "bowner:";

    public static void wlasciciel(Entity pocisk, Entity owner) {
        if (owner != null) pocisk.addTag(OWNER + owner.getStringUUID());
    }

    static Entity wlasciciel(Entity pocisk) {
        if (!(pocisk.level() instanceof ServerLevel sl)) return null;
        for (String t : pocisk.entityTags()) {
            if (!t.startsWith(OWNER)) continue;
            try { return sl.getEntity(UUID.fromString(t.substring(OWNER.length()))); } catch (IllegalArgumentException bad) { return null; }
        }
        return null;
    }

    // throw an entity from a living thing's eyes along its view (minecraft:throwable items, scripts)
    public static Entity rzuc(LivingEntity who, String entity, float power) {
        if (!(who.level() instanceof ServerLevel sl) || entity == null || entity.isBlank()) return null;
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(com.koper.koper_lib.api.core.BedrockNazwy.doJavy(entity));
        var type = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) { BedrockZachowanie.once("throwable entity " + entity + " is no entity type here"); return null; }
        Entity e = type.create(sl, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (e == null) return null;
        Vec3 look = who.getViewVector(1f);
        e.snapTo(who.getX() + look.x * 0.5, who.getEyeY() - 0.1, who.getZ() + look.z * 0.5, who.getYRot(), who.getXRot());
        if (e instanceof net.minecraft.world.entity.projectile.Projectile p) {
            p.setOwner(who);
            p.shoot(look.x, look.y, look.z, power, 1f);
        } else {
            wlasciciel(e, who);
            e.setDeltaMovement(look.x * power, look.y * power, look.z * power);
        }
        sl.addFreshEntity(e);
        return e;
    }

    private static double d(JsonObject o, String k, double def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    // one tick of flight. v = the velocity kept in the behavior state, null on the first tick (then the
    // mob's own delta movement, what the shooter gave it). returns the velocity for the next tick
    static double[] lec(Mob m, JsonObject pr, double[] v) {
        if (v == null) {
            Vec3 dm = m.getDeltaMovement();
            v = new double[] {dm.x, dm.y, dm.z};
        }
        if (m.tickCount > 1200) { m.discard(); return v; }
        if (v[0] == 0 && v[1] == 0 && v[2] == 0) return v; // stuck in the ground or never shot
        Vec3 from = m.position(), to = from.add(v[0], v[1], v[2]);
        BlockHitResult bh = m.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, m));
        if (bh.getType() != HitResult.Type.MISS) to = bh.getLocation();
        Entity owner = wlasciciel(m);
        Entity hit = null;
        double best = Double.MAX_VALUE;
        AABB path = m.getBoundingBox().expandTowards(v[0], v[1], v[2]).inflate(0.3);
        for (Entity e : m.level().getEntities(m, path, x -> x.isPickable() && x.isAlive() && x != owner && !x.isSpectator())) {
            Optional<Vec3> at = e.getBoundingBox().inflate(0.3).clip(from, to);
            if (at.isEmpty()) continue;
            double dd = from.distanceToSqr(at.get());
            if (dd < best) { best = dd; hit = e; }
        }
        JsonObject on = BedrockTlumacz.obj(pr, "on_hit");
        if (hit != null) {
            trafiony(m, pr, on, hit, owner, v);
            return v;
        }
        m.setPos(to.x, to.y, to.z);
        double h = Math.sqrt(v[0] * v[0] + v[2] * v[2]);
        m.setYRot((float) (Math.atan2(v[0], v[2]) * (180 / Math.PI)));
        m.setXRot((float) (Math.atan2(v[1], h) * (180 / Math.PI)));
        if (bh.getType() != HitResult.Type.MISS) {
            wydarzenie(m, on, null, owner);
            if (on != null && on.has("stick_in_ground")) return new double[3];
            m.discard();
            return v;
        }
        double k = m.isInWater() ? d(pr, "liquid_inertia", 0.6) : d(pr, "inertia", 0.99);
        return new double[] {v[0] * k, (v[1] - d(pr, "gravity", 0.05)) * k, v[2] * k};
    }

    private static void trafiony(Mob m, JsonObject pr, JsonObject on, Entity hit, Entity owner, double[] v) {
        if (!(m.level() instanceof ServerLevel sl)) return;
        JsonObject dmg = BedrockTlumacz.obj(on, "impact_damage");
        if (dmg != null) {
            float amount;
            JsonElement de = dmg.get("damage");
            if (de != null && de.isJsonArray() && de.getAsJsonArray().size() == 2) {
                float lo = de.getAsJsonArray().get(0).getAsFloat(), hi = de.getAsJsonArray().get(1).getAsFloat();
                amount = lo + m.getRandom().nextFloat() * (hi - lo);
            } else amount = (float) d(dmg, "damage", 1);
            // bedrock arrows hit harder the faster they fly when power scales it; plain value otherwise
            if (dmg.has("power_multiplier")) amount *= (float) (Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) * dmg.get("power_multiplier").getAsDouble());
            var zrodlo = sl.damageSources().thrown(m, owner);
            boolean ok = hit.hurtServer(sl, zrodlo, amount);
            if (ok && hit instanceof LivingEntity le && (!dmg.has("knockback") || dmg.get("knockback").getAsBoolean()))
                le.knockback(0.4, -v[0], -v[2], zrodlo, 0.0F);
            if (dmg.has("catch_fire") && dmg.get("catch_fire").getAsBoolean()) hit.igniteForSeconds(5);
        }
        if (on != null && on.has("catch_fire") && hit.isAlive()) hit.igniteForSeconds(5);
        JsonObject fx = BedrockTlumacz.obj(on, "mob_effect");
        if (fx != null && hit instanceof LivingEntity le) {
            try {
                String id = BedrockTlumacz.str(fx, "effect", "");
                int ticks = (int) d(fx, "duration", d(fx, "durationeasy", 100));
                le.addEffect(new MobEffectInstance(BedrockPytajnik.effect(id), ticks, (int) d(fx, "amplifier", 0)));
            } catch (RuntimeException unknownEffect) {
                BedrockZachowanie.once("projectile mob_effect: " + unknownEffect.getMessage());
            }
        }
        JsonObject tp = BedrockTlumacz.obj(on, "teleport_owner");
        if (tp != null && owner != null) owner.teleportTo(m.getX(), m.getY(), m.getZ());
        wydarzenie(m, on, hit, owner);
        boolean zostaje = on != null && !on.has("remove_on_hit") && on.has("stick_in_ground");
        if (!zostaje) m.discard();
    }

    // definition_event.event_trigger and particle_on_hit, for entity and block hits alike
    private static void wydarzenie(Mob m, JsonObject on, Entity hit, Entity owner) {
        if (on == null) return;
        JsonObject def = BedrockTlumacz.obj(on, "definition_event");
        JsonObject trig = BedrockTlumacz.obj(def, "event_trigger");
        if (trig != null) {
            String ev = BedrockTlumacz.str(trig, "event", null);
            String target = BedrockTlumacz.str(trig, "target", "self");
            Entity who = switch (target) {
                case "other" -> hit;
                case "parent", "owner" -> owner;
                default -> m;
            };
            if (ev != null && who != null) BedrockZachowanie.event(who, ev, who == m ? hit : m);
        }
        JsonObject part = BedrockTlumacz.obj(on, "particle_on_hit");
        if (part != null && m.level() instanceof ServerLevel sl && part.has("particle_type")) {
            var pkt = new com.koper.koper_lib.api.core.BedrockCzastkaPayload(BedrockTlumacz.str(part, "particle_type", ""), m.getX(), m.getY(), m.getZ());
            for (var p : sl.players()) if (p.distanceToSqr(m) < 96 * 96) com.koper.koper_lib.api.core.KoperNetwork.send(p, pkt);
        }
    }
}
