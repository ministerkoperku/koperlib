package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

// behavior pack filters: {"test", "subject", "operator", "value", "domain"} and the all_of /
// any_of / none_of trees around them. test names and meanings from the creator docs filter pages
final class BedrockFiltr {

    // who the filter is about: the mob itself, the other party of whatever happened, and so on
    // the hit a damage sensor is judging right now: bedrock cause name and whether it kills. has_damage reads it
    static final ThreadLocal<Object[]> OBRAZENIA = new ThreadLocal<>();

    record Kontekst(Entity self, Entity other, Entity damager, Entity target, BlockPos block) {
        Entity subject(String s) {
            return switch (s == null ? "self" : s.toLowerCase(Locale.ROOT)) {
                case "other" -> other;
                case "damager" -> damager != null ? damager : other;
                case "target" -> target != null ? target : self instanceof Mob m ? m.getTarget() : null;
                case "player" -> other instanceof Player ? other : self.level().getNearestPlayer(self, 64);
                case "parent", "baby" -> null;
                default -> self;
            };
        }
    }

    private BedrockFiltr() {}

    static boolean test(JsonElement f, Kontekst k) {
        if (f == null || f.isJsonNull()) return true;
        if (f.isJsonArray()) {
            for (JsonElement x : f.getAsJsonArray()) if (!test(x, k)) return false;
            return true;
        }
        if (!f.isJsonObject()) return true;
        JsonObject o = f.getAsJsonObject();
        if (o.has("all_of")) return test(o.get("all_of"), k);
        if (o.has("any_of")) {
            JsonElement any = o.get("any_of");
            if (!any.isJsonArray()) return test(any, k);
            for (JsonElement x : any.getAsJsonArray()) if (test(x, k)) return true;
            return any.getAsJsonArray().isEmpty();
        }
        if (o.has("none_of")) {
            JsonElement none = o.get("none_of");
            if (!none.isJsonArray()) return !test(none, k);
            for (JsonElement x : none.getAsJsonArray()) if (test(x, k)) return false;
            return true;
        }
        if (!o.has("test")) return true;
        return one(o, k);
    }

    private static boolean one(JsonObject o, Kontekst k) {
        String test = o.get("test").getAsString();
        String op = o.has("operator") ? o.get("operator").getAsString() : "equals";
        JsonElement value = o.get("value");
        String domain = o.has("domain") ? o.get("domain").getAsString() : null;
        Entity e = k.subject(o.has("subject") ? o.get("subject").getAsString() : "self");
        if (e == null && !test.equals("random_chance")) return false;
        LivingEntity le = e instanceof LivingEntity l ? l : null;
        Level lvl = e != null ? e.level() : k.self().level();
        return switch (test) {
            case "has_tag" -> cmpBool(e.entityTags().contains(str(value)), op);
            case "is_family" -> cmpBool(BedrockZachowanie.families(e).contains(str(value)), op);
            case "has_component" -> cmpBool(BedrockZachowanie.hasComponent(e, str(value)), op);
            case "is_daytime" -> cmpBool(lvl.getOverworldClockTime() % 24000 < 12000, op, value);
            case "is_underwater" -> cmpBool(e.isUnderWater(), op, value);
            case "in_water" -> cmpBool(e.isInWater(), op, value);
            case "in_water_or_rain" -> cmpBool(e.isInWaterOrRain(), op, value);
            case "in_contact_with_water" -> cmpBool(e.isInWater() || e.isInWaterOrRain(), op, value);
            case "in_lava" -> cmpBool(e.isInLava(), op, value);
            case "on_ground" -> cmpBool(e.onGround(), op, value);
            case "on_fire" -> cmpBool(e.isOnFire(), op, value);
            case "is_sneaking", "is_sneak_held" -> cmpBool(e.isShiftKeyDown(), op, value);
            case "is_sprinting" -> cmpBool(e.isSprinting(), op, value);
            case "is_moving" -> cmpBool(e.getDeltaMovement().horizontalDistanceSqr() > 1e-5, op, value);
            case "is_riding" -> cmpBool(e.isPassenger(), op, value);
            case "rider_count" -> cmpNum(e.getPassengers().size(), op, value);
            case "has_target" -> cmpBool(e instanceof Mob m && m.getTarget() != null, op, value);
            case "is_target" -> cmpBool(k.self() instanceof Mob m && m.getTarget() == k.other() && k.other() != null, op, value);
            case "is_baby" -> cmpBool(le != null && le.isBaby(), op, value);
            case "is_sleeping" -> cmpBool(le != null && le.isSleeping(), op, value);
            case "is_climbing", "on_ladder" -> cmpBool(le != null && le.onClimbable(), op, value);
            case "is_persistent" -> cmpBool(e instanceof Mob m && m.isPersistenceRequired(), op, value);
            case "is_leashed" -> cmpBool(e instanceof Mob m && m.isLeashed(), op, value);
            case "has_nametag" -> cmpBool(e.hasCustomName(), op, value);
            case "is_tamed" -> cmpBool(BedrockZachowanie.hasComponent(e, "minecraft:is_tamed")
                || e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame(), op, value);
            case "is_sitting" -> cmpBool(e instanceof net.minecraft.world.entity.TamableAnimal t && t.isInSittingPose(), op, value);
            case "is_variant" -> cmpNum(BedrockZachowanie.intState(e, "variant"), op, value);
            case "is_mark_variant" -> cmpNum(BedrockZachowanie.intState(e, "mark_variant"), op, value);
            case "is_skin_id" -> cmpNum(BedrockZachowanie.intState(e, "skin_id"), op, value);
            case "actor_health" -> cmpNum(le != null ? le.getHealth() : 0, op, value);
            case "is_missing_health" -> cmpBool(le != null && le.getHealth() < le.getMaxHealth(), op, value);
            case "random_chance" -> {
                int n = value != null ? Math.max(1, value.getAsInt()) : 2;
                yield cmpBool(k.self().getRandom().nextInt(n) == 0, op);
            }
            case "distance_to_nearest_player" -> {
                Player p = lvl.getNearestPlayer(e, 256);
                yield cmpNum(p == null ? Double.MAX_VALUE : p.distanceTo(e), op, value);
            }
            case "target_distance" -> cmpNum(e instanceof Mob m && m.getTarget() != null ? m.distanceTo(m.getTarget()) : Double.MAX_VALUE, op, value);
            case "has_equipment" -> cmpBool(le != null && hasEquipment(le, domain, str(value)), op);
            case "has_mob_effect" -> cmpBool(le != null && hasEffect(le, str(value)), op);
            case "is_biome", "has_biome_tag" -> cmpBool(biome(e, str(value)), op);
            case "is_underground" -> cmpBool(!lvl.canSeeSky(e.blockPosition()), op, value);
            case "is_altitude" -> cmpNum(e.getY(), op, value);
            case "y_rotation" -> cmpNum(e.getYRot(), op, value);
            case "moon_phase" -> cmpNum((lvl.getOverworldClockTime() / 24000) % 8, op, value);
            case "hourly_clock_time" -> cmpNum(lvl.getOverworldClockTime() % 24000, op, value);
            case "clock_time" -> cmpNum((lvl.getOverworldClockTime() % 24000) / 24000.0, op, value);
            case "is_brightness", "light_level" -> cmpNum(test.equals("light_level") ? lvl.getMaxLocalRawBrightness(e.blockPosition()) : lvl.getMaxLocalRawBrightness(e.blockPosition()) / 15.0, op, value);
            case "weather", "weather_at_position", "is_weather" -> {
                String w = lvl.isThundering() ? "thunderstorm" : lvl.isRaining() ? "rain" : "clear";
                String want = str(value);
                yield cmpBool(w.equals(want) || (want.equals("precipitation") && lvl.isRaining()), op);
            }
            case "is_difficulty" -> cmpBool(lvl.getDifficulty().getSerializedName().equals(str(value)), op);
            case "in_nether" -> cmpBool(lvl.dimension() == Level.NETHER, op, value);
            case "in_overworld" -> cmpBool(lvl.dimension() == Level.OVERWORLD, op, value);
            case "is_temperature_value" -> cmpNum(lvl.getBiome(e.blockPosition()).value().getBaseTemperature(), op, value);
            case "is_temperature_type" -> {
                float t = lvl.getBiome(e.blockPosition()).value().getBaseTemperature();
                String type = t < 0.2f ? "cold" : t < 1.0f ? "mild" : t < 1.5f ? "warm" : "ocean";
                yield cmpBool(type.equals(str(value)), op);
            }
            case "is_owner" -> cmpBool(k.self() instanceof net.minecraft.world.entity.TamableAnimal t && t.getOwner() == e, op, value);
            case "owner_distance" -> cmpNum(k.self() instanceof net.minecraft.world.entity.TamableAnimal t && t.getOwner() != null ? t.distanceTo(t.getOwner()) : Double.MAX_VALUE, op, value);
            case "has_property" -> cmpBool(BedrockZachowanie.property(e, str(value)) != null, op);
            case "bool_property", "int_property", "float_property", "enum_property", "property" -> {
                Object v = BedrockZachowanie.property(e, domain);
                if (v == null) yield false;
                if (v instanceof String s) yield cmpStr(s, op, str(value));
                if (v instanceof Boolean b) yield cmpBool(b, op, value);
                yield cmpNum(((Number) v).doubleValue(), op, value);
            }
            case "is_block", "in_block" -> {
                BlockPos p = k.block() != null ? k.block() : e.blockPosition();
                String id = BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(p).getBlock()).toString();
                String want = str(value);
                yield cmpBool(id.equals(want) || id.equals("minecraft:" + want), op);
            }
            case "was_last_hurt_by" -> cmpBool(le != null && le.getLastHurtByMob() == k.other(), op, value);
            case "is_visible" -> cmpBool(!e.isInvisible(), op, value);
            case "is_panicking" -> cmpBool(le != null && le.getLastHurtByMob() != null && le.tickCount - le.getLastHurtByMobTimestamp() < 100, op, value);
            // villager brain in panic, or walking away from a monster inside 8 blocks (the flee goals do just that)
            case "is_avoiding_mobs" -> cmpBool(e instanceof Mob m && (m.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC)
                || m.getNavigation().isInProgress() && isFleeing(m)), op, value);
            // domain = the rule, value = what it should be (bool, or a number for the int ones)
            case "is_game_rule" -> {
                Object r = lvl.getServer() == null || domain == null ? null : BedrockGameRules.read(lvl.getServer(), domain);
                yield r instanceof Integer n ? cmpNum(n, op, value) : cmpBool(Boolean.TRUE.equals(r), op, value);
            }
            case "is_in_village" -> cmpBool(lvl instanceof net.minecraft.server.level.ServerLevel sl && sl.isVillage(e.blockPosition()), op, value);
            // bedrock: it would snow here, not that snow lies on the ground
            case "is_snow_covered" -> cmpBool(lvl.getBiome(e.blockPosition()).value().coldEnoughToSnow(e.blockPosition(), lvl.getSeaLevel()), op, value);
            case "has_trade_supply" -> cmpBool(e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager v
                && v.getOffers().stream().anyMatch(t -> !t.isOutOfStock()), op, value);
            case "is_vehicle_family" -> cmpBool(e.getVehicle() != null && BedrockZachowanie.families(e.getVehicle()).contains(str(value)), op);
            case "has_ranged_weapon" -> cmpBool(le != null && (le.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem
                || le.getMainHandItem().getItem() instanceof net.minecraft.world.item.TridentItem), op, value);
            case "has_damage" -> {
                Object[] hit = OBRAZENIA.get();
                String want = str(value);
                boolean ma = hit != null && (want.equals("fatal") ? (Boolean) hit[1] : want.equals(hit[0]) || want.equals("all") || want.equals("any"));
                yield cmpBool(ma, op);
            }
            default -> {
                BedrockZachowanie.once("filter test '" + test + "' has no java twin, treated as false");
                yield false;
            }
        };
    }

    private static boolean isFleeing(Mob m) {
        var v = m.getDeltaMovement();
        if (v.horizontalDistanceSqr() < 1.0E-4) return false;
        for (var z : m.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, m.getBoundingBox().inflate(8))) {
            if ((m.getX() - z.getX()) * v.x + (m.getZ() - z.getZ()) * v.z > 0) return true;
        }
        return false;
    }

    private static String str(JsonElement v) {
        return v == null || v.isJsonNull() ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }

    private static boolean cmpBool(boolean actual, String op) {
        return cmpBool(actual, op, null);
    }

    // "value": false flips it, a missing value means true
    private static boolean cmpBool(boolean actual, String op, JsonElement value) {
        boolean want = value == null || value.isJsonNull() || !value.isJsonPrimitive() || (value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : !value.getAsString().equals("false"));
        boolean eq = actual == want;
        return switch (op) {
            case "!=", "<>", "not" -> !eq;
            default -> eq;
        };
    }

    private static boolean cmpStr(String actual, String op, String want) {
        boolean eq = actual.equals(want);
        return switch (op) {
            case "!=", "<>", "not" -> !eq;
            default -> eq;
        };
    }

    private static boolean cmpNum(double actual, String op, JsonElement value) {
        double want = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble()
            : value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? (value.getAsBoolean() ? 1 : 0) : 0;
        return switch (op) {
            case "!=", "<>", "not" -> actual != want;
            case "<" -> actual < want;
            case "<=" -> actual <= want;
            case ">" -> actual > want;
            case ">=" -> actual >= want;
            default -> Math.abs(actual - want) < 1e-6;
        };
    }

    private static boolean hasEquipment(LivingEntity le, String domain, String item) {
        String want = item.contains(":") ? item : "minecraft:" + item;
        List<EquipmentSlot> slots = switch (domain == null ? "any" : domain) {
            case "hand" -> List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);
            case "main_hand" -> List.of(EquipmentSlot.MAINHAND);
            case "head" -> List.of(EquipmentSlot.HEAD);
            case "torso" -> List.of(EquipmentSlot.CHEST);
            case "leg" -> List.of(EquipmentSlot.LEGS);
            case "feet" -> List.of(EquipmentSlot.FEET);
            case "body" -> List.of(EquipmentSlot.BODY);
            case "armor" -> List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
            default -> List.of(EquipmentSlot.values());
        };
        for (EquipmentSlot s : slots) {
            ItemStack st = le.getItemBySlot(s);
            if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(want)) return true;
        }
        if ("inventory".equals(domain) && le instanceof Player p) {
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(want)) return true;
            }
        }
        return false;
    }

    private static boolean hasEffect(LivingEntity le, String id) {
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) return false;
        return BuiltInRegistries.MOB_EFFECT.get(rl).map(le::hasEffect).orElse(false);
    }

    // bedrock biome words ("ocean", "jungle", "the_nether") against java biome tags, then the id itself
    private static boolean biome(Entity e, String what) {
        return biomeTag(e.level().getBiome(e.blockPosition()), what);
    }

    // also the natural spawner's. bedrock tags biomes with words java has no tag for ("monster" is where
    // monsters may spawn, "animal" where animals do, "cold", "mesa", "roofed"...): those by what they mean
    public static boolean biomeTag(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b, String what) {
        String w = what.toLowerCase(Locale.ROOT);
        String path = b.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        float temp = b.value().getBaseTemperature();
        java.util.function.Predicate<String> tagged = t -> {
            Identifier id = Identifier.tryParse("minecraft:" + t);
            return id != null && b.is(TagKey.create(Registries.BIOME, id));
        };
        boolean water = tagged.test("is_ocean") || tagged.test("is_river") || path.contains("ocean") || path.contains("river");
        Boolean known = switch (w) {
            case "monster" -> !path.equals("mushroom_fields") && !path.equals("deep_dark");
            case "animal" -> tagged.test("is_overworld") && !water && !path.contains("desert") && !tagged.test("is_badlands") && !path.equals("mushroom_fields");
            case "overworld", "overworld_generation" -> tagged.test("is_overworld");
            case "nether" -> tagged.test("is_nether");
            case "the_end" -> tagged.test("is_end");
            case "cold" -> temp < 0.3f;
            case "frozen" -> temp < 0.15f || path.contains("frozen") || path.contains("snowy") || path.contains("ice");
            case "warm", "hot" -> temp > 0.9f;
            case "lukewarm" -> path.contains("lukewarm");
            case "mesa" -> tagged.test("is_badlands");
            case "roofed" -> path.contains("dark_forest");
            case "mega" -> path.contains("old_growth");
            case "extreme_hills", "mountain", "mountains" -> tagged.test("is_mountain") || path.contains("windswept");
            case "hills" -> tagged.test("is_hill") || path.contains("hills");
            case "mooshroom_island" -> path.equals("mushroom_fields");
            case "ice" -> path.contains("ice");
            case "stone" -> path.contains("stony");
            case "deep" -> path.startsWith("deep_");
            case "birch" -> path.contains("birch");
            case "flower_forest" -> path.equals("flower_forest");
            case "sunflower_plains" -> path.equals("sunflower_plains");
            case "caves" -> path.contains("caves") || path.equals("deep_dark");
            default -> null;
        };
        if (known != null) return known;
        Identifier tag = Identifier.tryParse("minecraft:is_" + w);
        if (tag != null && b.is(TagKey.create(Registries.BIOME, tag))) return true;
        Identifier plain = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        if (plain != null && b.is(TagKey.create(Registries.BIOME, plain))) return true;
        return path.contains(w);
    }
}
