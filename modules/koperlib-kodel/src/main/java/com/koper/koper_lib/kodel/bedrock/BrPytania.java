package com.koper.koper_lib.kodel.bedrock;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// molang query slots -> real entity values. the def hands us every query it compiled as a key
// like "is_on_ground" or "is_name_any('baby','piglet')", we turn each into a small reader once.
// names and meanings are the ones in the creator docs query table; anything java has no twin
// for answers 0, which is what bedrock does for a query that does not apply to an entity
final class BrPytania {

    static final class Stan {
        Entity e;
        LivingEntity le;
        float pt;
        double lifeTime;
        Vec3 lastPos;
        double walked;
        int walkedTick = Integer.MIN_VALUE;
        boolean ui;
        // an attachable worn or held by someone: q.is_attached. a&s only runs the moves that put an item in the
        // hand while that is true, at 0 every held item stood upright where its model was drawn (over the head)
        boolean attached;
        // ticked for the first person view: the camera is the head, nothing to turn towards
        boolean fp;
        double lastStep;
        float lastYaw;
        float yawSpeed;
    }

    static float cialo(Stan s) {
        return s.le != null ? Mth.rotLerp(s.pt, s.le.yBodyRotO, s.le.yBodyRot) : s.e.getYRot(s.pt);
    }

    // metres walked, counted once per game tick, plus the part of this tick already done
    static double przeszedl(Stan s) {
        // walked = up to xo, the drawn position sits step*pt past it
        double step = Math.hypot(s.e.getX() - s.e.xo, s.e.getZ() - s.e.zo);
        if (s.walkedTick != s.e.tickCount) {
            s.walked += s.lastStep;
            s.walkedTick = s.e.tickCount;
            s.lastStep = step;
        }
        return s.walked + step * s.pt;
    }

    abstract static class P {
        float num(Stan s) { return 0f; }
        // non null = this slot answers with a string
        String str(Stan s) { return null; }
    }

    private static final P ZERO = new P() {};

    private static final java.util.Set<String> NIEZNANE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static P[] compile(List<String> keys) {
        P[] out = new P[keys.size()];
        for (int i = 0; i < out.length; i++) {
            String k = keys.get(i);
            int paren = k.indexOf('(');
            String name = paren < 0 ? k : k.substring(0, paren);
            // a struct field: "bone_origin('leftarm').y", the molang parser spread the struct into these
            int close = k.lastIndexOf(')');
            String pole = close >= 0 && close + 1 < k.length() && k.charAt(close + 1) == '.' ? k.substring(close + 2) : null;
            List<Object> args = paren < 0 ? List.of() : args(k.substring(paren + 1, close < 0 ? k.length() : close));
            P p = pole != null ? struktura(name, args, pole) : make(name, args);
            // the struct as a whole: only its fields mean anything, those got slots of their own
            if (p == null && pole == null && struktura(name, args, "x") != null) p = ZERO;
            if (p == null && NIEZNANE.add(name))
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kodel/Bedrock] molang q.{} is not implemented, it answers 0 and whatever reads it is wrong ({})", name, k);
            out[i] = p == null ? ZERO : p;
        }
        return out;
    }

    private static P struktura(String n, List<Object> a, String pole) {
        int os = switch (pole.charAt(pole.length() - 1)) { case 'y' -> 1; case 'z' -> 2; default -> 0; };
        return switch (n) {
            case "bone_origin", "bone_pivot" -> f(s -> {
                float[] o = BrAktorzy.boneOrigin(s.e, sarg(a, 0));
                return o == null ? 0 : o[os];
            });
            case "bone_orientation_trs" -> {
                int grupa = pole.startsWith("r") ? 3 : pole.startsWith("s") ? 6 : 0;
                yield f(s -> {
                    float[] trs = BrAktorzy.boneTrs(s.e, sarg(a, 0));
                    return trs == null ? (grupa == 6 ? 1 : 0) : trs[grupa + os];
                });
            }
            default -> null;
        };
    }

    // ItemCooldowns keeps start and end ticks private. read once by reflection, loud if that ever breaks
    private static java.lang.reflect.Field cdMapa, cdTick;
    private static java.lang.reflect.Method cdStart, cdEnd;
    private static boolean cdZepsute;

    // {remaining seconds, total seconds} of the cooldown group the stack is in, null = none
    private static float[] cooldown(Stan s, ItemStack st) {
        if (!(s.e instanceof Player pl) || st.isEmpty() || cdZepsute) return null;
        var cds = pl.getCooldowns();
        try {
            if (cdMapa == null) {
                cdMapa = net.minecraft.world.item.ItemCooldowns.class.getDeclaredField("cooldowns");
                cdTick = net.minecraft.world.item.ItemCooldowns.class.getDeclaredField("tickCount");
                cdMapa.setAccessible(true);
                cdTick.setAccessible(true);
            }
            Object inst = ((java.util.Map<?, ?>) cdMapa.get(cds)).get(cds.getCooldownGroup(st));
            if (inst == null) return null;
            if (cdStart == null) {
                cdStart = inst.getClass().getDeclaredMethod("startTime");
                cdEnd = inst.getClass().getDeclaredMethod("endTime");
                cdStart.setAccessible(true);
                cdEnd.setAccessible(true);
            }
            int start = (int) cdStart.invoke(inst), end = (int) cdEnd.invoke(inst);
            float now = cdTick.getInt(cds) + s.pt;
            return new float[] {Math.max(0, end - now) / 20f, (end - start) / 20f};
        } catch (ReflectiveOperationException | RuntimeException e) {
            cdZepsute = true;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kodel/Bedrock] q.cooldown_time* can not read item cooldowns any more, they read 0 from now on", e);
            return null;
        }
    }

    private static EquipmentSlot zbrojaSlot(int i) {
        return switch (i) { case 0 -> EquipmentSlot.HEAD; case 1 -> EquipmentSlot.CHEST; case 2 -> EquipmentSlot.LEGS; case 3 -> EquipmentSlot.FEET; default -> EquipmentSlot.BODY; };
    }

    // bedrock's armor texture types: none, leather, chain, iron, diamond, gold, elytra, turtle, netherite
    private static int zbroja(ItemStack st) {
        if (st.isEmpty()) return 0;
        var eq = st.get(DataComponents.EQUIPPABLE);
        String asset = eq == null || eq.assetId().isEmpty() ? "" : eq.assetId().get().identifier().getPath();
        return switch (asset) {
            case "leather" -> 1;
            case "chainmail" -> 2;
            case "iron" -> 3;
            case "diamond" -> 4;
            case "gold" -> 5;
            case "elytra" -> 6;
            case "turtle_scute" -> 7;
            case "netherite" -> 8;
            default -> 0;
        };
    }

    static List<Object> args(String s) {
        List<Object> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ',' || c == ' ') { i++; continue; }
            if (c == '\'') {
                int end = s.indexOf('\'', i + 1);
                if (end < 0) end = s.length();
                out.add(s.substring(i + 1, end));
                i = end + 1;
            } else {
                int end = s.indexOf(',', i);
                if (end < 0) end = s.length();
                String t = s.substring(i, end).trim();
                try { out.add(Float.parseFloat(t)); } catch (NumberFormatException e) { out.add(t); }
                i = end;
            }
        }
        return out;
    }

    private static P f(java.util.function.ToDoubleFunction<Stan> fn) {
        return new P() { @Override float num(Stan s) { return (float) fn.applyAsDouble(s); } };
    }

    private static P b(java.util.function.Predicate<Stan> fn) {
        return new P() { @Override float num(Stan s) { return fn.test(s) ? 1f : 0f; } };
    }

    private static P living(java.util.function.ToDoubleFunction<LivingEntity> fn) {
        return new P() { @Override float num(Stan s) { return s.le == null ? 0f : (float) fn.applyAsDouble(s.le); } };
    }

    private static P pose(Pose p) {
        return b(s -> s.e.hasPose(p));
    }

    private static float arg(List<Object> a, int i) {
        return i < a.size() && a.get(i) instanceof Float f ? f : 0f;
    }

    private static String sarg(List<Object> a, int i) {
        return i < a.size() ? String.valueOf(a.get(i)) : "";
    }

    private static EquipmentSlot slot(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "slot.weapon.offhand", "off_hand", "offhand", "1" -> EquipmentSlot.OFFHAND;
            case "slot.armor.head", "head" -> EquipmentSlot.HEAD;
            case "slot.armor.chest", "chest" -> EquipmentSlot.CHEST;
            case "slot.armor.legs", "legs" -> EquipmentSlot.LEGS;
            case "slot.armor.feet", "feet" -> EquipmentSlot.FEET;
            case "slot.armor.body", "body" -> EquipmentSlot.BODY;
            default -> EquipmentSlot.MAINHAND;
        };
    }

    private static ItemStack held(Stan s, String slotName) {
        return s.le == null ? ItemStack.EMPTY : s.le.getItemBySlot(slot(slotName));
    }

    private static String itemId(ItemStack st) {
        return st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    private static String nameOf(Entity e) {
        return e.getCustomName() != null ? e.getCustomName().getString() : e instanceof Player p ? p.getGameProfile().name() : "";
    }

    private static List<DataComponentType<?>> variants;

    // built on first use, touching DataComponents in a static init drags the registries in too early
    private static List<DataComponentType<?>> variantTypes() {
        if (variants == null) variants = List.of(
        DataComponents.PIG_VARIANT, DataComponents.COW_VARIANT, DataComponents.CHICKEN_VARIANT, DataComponents.WOLF_VARIANT,
        DataComponents.CAT_VARIANT, DataComponents.FROG_VARIANT, DataComponents.HORSE_VARIANT, DataComponents.LLAMA_VARIANT,
        DataComponents.AXOLOTL_VARIANT, DataComponents.FOX_VARIANT, DataComponents.RABBIT_VARIANT, DataComponents.PARROT_VARIANT,
        DataComponents.MOOSHROOM_VARIANT, DataComponents.VILLAGER_VARIANT, DataComponents.ZOMBIE_NAUTILUS_VARIANT);
        return variants;
    }

    private static Object variant(Entity e) {
        for (DataComponentType<?> t : variantTypes()) {
            Object v = e.get(t);
            if (v != null) return v;
        }
        return null;
    }

    private static float variantNum(Entity e) {
        // bedrock's slime and magma cube keep their size in variant (1, 2, 4), packs scale by it.
        // 0 here = scale 0 = the whole slime vanished
        if (e instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob sl) return sl.getSize();
        Object v = variant(e);
        if (v instanceof Enum<?> en) return en.ordinal();
        if (v instanceof Holder<?> h && h.unwrapKey().isPresent()) {
            // registry backed: stable order of the registry the key lives in
            var key = h.unwrapKey().get();
            var reg = e.level().registryAccess().lookup(key.registryKey());
            if (reg.isPresent()) {
                int i = 0;
                for (var k : reg.get().listElementIds().toList()) {
                    if (k.equals(key)) return i;
                    i++;
                }
            }
        }
        return 0f;
    }

    private static String variantName(Entity e) {
        Object v = variant(e);
        if (v instanceof Holder<?> h) return h.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (v instanceof Enum<?> en) return en.name().toLowerCase(Locale.ROOT);
        return null;
    }

    private static boolean isFirstPerson(Stan s) {
        Minecraft mc = Minecraft.getInstance();
        return mc.getCameraEntity() == s.e && mc.options.getCameraType().isFirstPerson();
    }

    private static P make(String n, List<Object> a) {
        return switch (n) {
            case "life_time" -> f(s -> s.lifeTime);
            case "frame_alpha" -> f(s -> s.pt);
            case "is_on_ground" -> b(s -> s.e.onGround());
            case "is_moving" -> b(s -> s.le != null ? s.le.walkAnimation.isMoving() : s.e.getKnownMovement().horizontalDistanceSqr() > 1e-4);
            case "modified_move_speed" -> living(le -> le.walkAnimation.speed(0f));
            // bedrock counts real metres walked. java's walkAnimation position runs ~5x faster
            case "modified_distance_moved", "walk_distance" -> f(s -> przeszedl(s));
            // what really moved last tick. delta movement keeps gravity while standing (-1.6 m/s),
            // so the pack thought we were falling all the time and never left its jump state
            case "ground_speed" -> f(s -> Math.hypot(s.e.getX() - s.e.xo, s.e.getZ() - s.e.zo) * 20);
            case "vertical_speed" -> f(s -> (s.e.getY() - s.e.yo) * 20);
            case "yaw_speed" -> f(s -> s.yawSpeed);
            // all lerped by the frame's partial tick and the head wrapped into -180..180 around the body.
            // raw tick values snapped 20 times a second and head-body flipped by 360 crossing north
            case "body_y_rotation" -> f(BrPytania::cialo);
            case "body_x_rotation", "head_x_rotation", "target_x_rotation" -> f(s -> s.e.getXRot(s.pt));
            // head_y_rotation(n) is the n-th head's world yaw, like body_y_rotation. packs subtract the two
            // and turn world movement into the player's own axes with it: as head-minus-body it made A&S's
            // root turn sideways and its strafe anims snap left and right
            case "head_y_rotation" -> f(s -> s.le != null ? Mth.rotLerp(s.pt, s.le.yHeadRotO, s.le.yHeadRot) : s.e.getYRot(s.pt));
            // target_y_rotation is the head turned against the body. first person draws in camera space:
            // the pack's "turn the body to the camera" is already done there
            case "target_y_rotation" -> f(s -> s.le != null && !s.fp
                ? Mth.wrapDegrees(Mth.rotLerp(s.pt, s.le.yHeadRotO, s.le.yHeadRot) - cialo(s)) : 0);
            case "cardinal_facing_2d" -> f(s -> s.e.getDirection().get3DDataValue());
            case "health" -> living(LivingEntity::getHealth);
            case "max_health" -> living(LivingEntity::getMaxHealth);
            case "is_alive" -> b(s -> s.e.isAlive());
            case "is_baby" -> b(s -> s.le != null && s.le.isBaby() || BrStany.flag(s.e.getId(), "is_baby"));
            case "is_sneaking" -> b(s -> s.e.isCrouching());
            case "is_sprinting" -> b(s -> s.e.isSprinting());
            case "is_swimming" -> b(s -> s.e.isVisuallySwimming());
            case "is_in_water" -> b(s -> s.e.isInWater());
            case "is_in_water_or_rain" -> b(s -> s.e.isInWaterOrRain());
            case "is_in_lava" -> b(s -> s.e.isInLava());
            case "is_in_contact_with_water" -> b(s -> s.e.isInWater() || s.e.isInWaterOrRain());
            case "head_is_in_water" -> b(s -> s.e.isUnderWater());
            case "is_on_fire", "is_onfire" -> b(s -> s.e.isOnFire());
            case "is_riding" -> b(s -> s.e.isPassenger());
            case "has_rider" -> b(s -> s.e.isVehicle());
            case "has_player_rider" -> b(s -> s.e.getPassengers().stream().anyMatch(x -> x instanceof Player));
            case "is_sleeping" -> pose(Pose.SLEEPING);
            case "is_gliding" -> b(s -> s.le != null && s.le.isFallFlying());
            case "is_using_item" -> b(s -> s.le != null && s.le.isUsingItem());
            case "is_invisible" -> b(s -> s.e.isInvisible());
            case "is_spectator" -> b(s -> s.e.isSpectator());
            case "is_sitting" -> b(s -> s.e.hasPose(Pose.SITTING) || s.e instanceof net.minecraft.world.entity.TamableAnimal t && t.isInSittingPose());
            case "is_tamed" -> b(s -> BrStany.flag(s.e.getId(), "is_tamed") || s.e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame());
            case "is_chested", "is_illager_captain", "is_ignited" -> b(s -> BrStany.flag(s.e.getId(), n));
            case "has_owner" -> b(s -> s.e instanceof net.minecraft.world.entity.TamableAnimal t && t.getOwnerReference() != null);
            case "is_angry" -> b(s -> s.e instanceof net.minecraft.world.entity.NeutralMob m && m.isAngry() || s.e instanceof Mob m2 && m2.isAggressive());
            case "has_target" -> b(s -> s.e instanceof Mob m && m.getTarget() != null);
            case "is_roaring" -> pose(Pose.ROARING);
            case "is_sniffing" -> pose(Pose.SNIFFING);
            case "is_emerging" -> pose(Pose.EMERGING);
            case "is_digging" -> pose(Pose.DIGGING);
            case "is_croaking" -> pose(Pose.CROAKING);
            case "is_crawling" -> b(s -> s.e.hasPose(Pose.SWIMMING) && !s.e.isInWater());
            case "is_saddled" -> b(s -> BrStany.flag(s.e.getId(), "is_saddled") || s.le != null && !s.le.getItemBySlot(EquipmentSlot.SADDLE).isEmpty());
            // bedrock calls a pumpkinless snow golem sheared too
            case "is_sheared" -> b(s -> BrStany.flag(s.e.getId(), "is_sheared")
                || s.e instanceof net.minecraft.world.entity.animal.sheep.Sheep sh && sh.isSheared()
                || s.e instanceof net.minecraft.world.entity.animal.golem.SnowGolem sg && !sg.hasPumpkin()
                || s.e instanceof net.minecraft.world.entity.monster.skeleton.Bogged bg && bg.isSheared());
            case "is_powered", "is_charged" -> b(s -> BrStany.flag(s.e.getId(), n) || s.e instanceof net.minecraft.world.entity.monster.Creeper c && c.isPowered());
            case "swell_amount" -> f(s -> s.e instanceof net.minecraft.world.entity.monster.Creeper c ? c.getSwelling(s.pt) : 0);
            case "is_carrying_block" -> b(s -> s.e instanceof net.minecraft.world.entity.monster.Enderman m && m.getCarriedBlock() != null);
            case "is_wall_climbing" -> b(s -> s.e instanceof net.minecraft.world.entity.monster.spider.Spider sp && sp.isClimbing());
            case "is_casting" -> b(s -> s.e instanceof net.minecraft.world.entity.monster.illager.SpellcasterIllager sc && sc.isCastingSpell());
            case "is_charging" -> b(s -> s.e instanceof Mob m && m.isAggressive());
            case "is_eating" -> b(s -> s.le != null && s.le.isUsingItem() && s.le.getUseItem().has(DataComponents.FOOD));
            case "is_stunned" -> b(s -> BrStany.flag(s.e.getId(), "is_stunned"));
            case "hurt_time" -> living(le -> le.hurtTime);
            case "death_ticks" -> living(le -> le.deathTime);
            case "invulnerable_ticks" -> f(s -> s.le != null ? s.le.damageCooldownTime : s.e.getInvulnerableTime());
            case "is_first_person" -> b(BrPytania::isFirstPerson);
            case "is_local_player" -> b(s -> s.e == Minecraft.getInstance().player);
            case "is_in_ui" -> b(s -> s.ui);
            case "is_attached" -> b(s -> s.attached || s.e instanceof Mob mob && mob.isLeashed());
            case "is_ghost", "is_emoting", "is_persona_or_premium_skin", "is_playing_dead" -> ZERO;
            case "is_on_screen", "is_alive_client" -> f(s -> 1);
            case "distance_from_camera" -> f(s -> Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(s.e.position()));
            case "camera_distance_range_lerp" -> f(s -> {
                double d = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(s.e.position());
                float lo = arg(a, 0), hi = arg(a, 1);
                return hi == lo ? 0 : Math.max(0, Math.min(1, (d - lo) / (hi - lo)));
            });
            case "position" -> f(s -> { int ax = (int) arg(a, 0); return ax == 0 ? s.e.getX() : ax == 1 ? s.e.getY() : s.e.getZ(); });
            case "position_delta" -> f(s -> { int ax = (int) arg(a, 0); Vec3 m = s.e.getKnownMovement(); return ax == 0 ? m.x : ax == 1 ? m.y : m.z; });
            case "time_of_day" -> f(s -> (s.e.level().getOverworldClockTime() % 24000) / 24000.0);
            case "day" -> f(s -> s.e.level().getOverworldClockTime() / 24000);
            case "moon_phase" -> f(s -> (s.e.level().getOverworldClockTime() / 24000) % 8);
            case "model_scale" -> living(LivingEntity::getScale);
            // how far up on its hind legs (polar bear warning pose), 0 on all fours. it read the model scale
            // before, 1, and every bear from a pack stood up for good
            case "standing_scale" -> f(s -> s.e instanceof net.minecraft.world.entity.animal.polarbear.PolarBear pb ? pb.getStandingAnimationScale(1f)
                : BrStany.flag(s.e.getId(), "is_standing") ? 1 : 0);
            case "variant", "mark_variant", "skin_id" -> f(s -> {
                Float v = BrStany.num(s.e.getId(), n);
                if (v != null) return v;
                Integer w = com.koper.koper_lib.api.core.BedrockWiesniak.liczba(s.e, n);
                return w != null ? (float) w : variantNum(s.e);
            });
            case "has_any_family" -> b(s -> { for (Object o : a) if (BrStany.family(s.e.getId(), String.valueOf(o))) return true; return false; });
            case "property" -> {
                String prop = sarg(a, 0);
                yield new P() {
                    @Override float num(Stan s) {
                        var v = BrStany.prop(s.e.getId(), prop);
                        if (v != null && v.isJsonPrimitive()) return v.getAsJsonPrimitive().isBoolean() ? (v.getAsBoolean() ? 1f : 0f) : v.getAsJsonPrimitive().isNumber() ? v.getAsFloat() : 0f;
                        return 0f;
                    }

                    @Override String str(Stan s) {
                        var v = BrStany.prop(s.e.getId(), prop);
                        if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) return v.getAsString();
                        if (v != null) return null;
                        return prop.endsWith("climate_variant") || prop.endsWith("variant") ? variantName(s.e) : null;
                    }
                };
            }
            case "has_property" -> b(s -> BrStany.prop(s.e.getId(), sarg(a, 0)) != null);
            case "get_name" -> new P() { @Override String str(Stan s) { return nameOf(s.e); } };
            case "is_name_any" -> b(s -> { String nm = nameOf(s.e); for (Object o : a) if (nm.equals(String.valueOf(o))) return true; return false; });
            case "get_equipped_item_name" -> {
                String sl = sarg(a, 0);
                yield new P() { @Override String str(Stan s) { String id = itemId(held(s, sl)); return id.isEmpty() ? "" : id.substring(id.indexOf(':') + 1); } };
            }
            case "is_item_equipped" -> b(s -> !held(s, a.isEmpty() ? "main_hand" : sarg(a, 0)).isEmpty());
            // (slot, [slot index,] names...). the index is optional: a string right after the slot is
            // already a name, '' being the empty hand. starting at 2 skipped A&S's '' and first boots
            case "is_item_name_any" -> b(s -> {
                String id = itemId(held(s, sarg(a, 0)));
                for (int i = a.size() > 1 && a.get(1) instanceof Float ? 2 : 1; i < a.size(); i++) {
                    String want = sarg(a, i);
                    if (id.equals(want) || id.equals("minecraft:" + want)) return true;
                }
                return false;
            });
            case "equipped_item_any_tag", "equipped_item_all_tags" -> b(s -> {
                ItemStack st = held(s, sarg(a, 0));
                if (st.isEmpty()) return false;
                boolean all = n.endsWith("all_tags");
                for (int i = a.size() > 1 && a.get(1) instanceof Float ? 2 : 1; i < a.size(); i++) {
                    boolean has = BrTagi.ma(st, sarg(a, i));
                    if (all && !has) return false;
                    if (!all && has) return true;
                }
                return all;
            });
            case "has_armor_slot" -> b(s -> s.le != null && !s.le.getItemBySlot(armorSlot((int) arg(a, 0))).isEmpty());
            case "has_head_gear" -> b(s -> s.le != null && !s.le.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
            case "equipment_count" -> living(le -> {
                int c = 0;
                for (EquipmentSlot sl : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
                    if (!le.getItemBySlot(sl).isEmpty()) c++;
                return c;
            });
            case "item_in_use_duration", "main_hand_item_use_duration" -> living(le -> le.getTicksUsingItem() / 20f);
            case "item_remaining_use_duration" -> living(le -> le.getUseItemRemainingTicks() / 20f);
            case "main_hand_item_max_duration", "item_max_use_duration" -> living(le -> le.getUseItem().getUseDuration(le) / 20f);
            case "is_item_charged", "item_is_charged" -> b(s -> s.le != null && net.minecraft.world.item.CrossbowItem.isCharged(s.le.getMainHandItem()));
            case "graphics_mode_is_any" -> {
                // java draws "fancy" by default, answer yes when that is on the list
                boolean fancy = a.stream().anyMatch(x -> String.valueOf(x).equalsIgnoreCase("fancy"));
                yield f(s -> fancy ? 1 : 0);
            }
            case "entity_biome_has_any_identifier" -> b(s -> {
                var key = s.e.level().getBiome(s.e.blockPosition()).unwrapKey();
                if (key.isEmpty()) return false;
                String id = key.get().identifier().toString();
                for (Object o : a) {
                    String w = String.valueOf(o);
                    if (id.equals(w) || id.equals("minecraft:" + w)) return true;
                }
                return false;
            });
            case "entity_biome_has_any_tags", "has_biome_tag", "has_any_biome_tags", "entity_biome_has_all_tags" -> b(s -> {
                var biome = s.e.level().getBiome(s.e.blockPosition());
                for (Object o : a) {
                    Identifier id = Identifier.tryParse(String.valueOf(o));
                    if (id != null && biome.is(TagKey.create(Registries.BIOME, id))) return true;
                }
                return false;
            });
            case "relative_block_has_any_tag", "relative_block_has_all_tags" -> b(s -> {
                BlockPos p = s.e.blockPosition().offset((int) arg(a, 0), (int) arg(a, 1), (int) arg(a, 2));
                var st = s.e.level().getBlockState(p);
                for (int i = 3; i < a.size(); i++) {
                    String t = sarg(a, i);
                    // bedrock block tags like "water" are ids here, match the obvious ones by name too
                    if (t.equals("water") && !st.getFluidState().isEmpty()) return true;
                    Identifier id = Identifier.tryParse(t);
                    if (id != null && st.is(TagKey.create(Registries.BLOCK, id))) return true;
                }
                return false;
            });
            case "is_owner_identifier_any", "is_riding_any_entity_of_type" -> b(s -> {
                // owner = whoever holds or wears the attachable, the stan's entity. this answered false forever
                Entity other = n.startsWith("is_riding") ? s.e.getVehicle() : s.e;
                if (other == null) return false;
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(other.getType()).toString();
                for (Object o : a) if (id.equals(String.valueOf(o))) return true;
                return false;
            });
            case "has_any_leashed_entity_of_type", "has_cape", "has_dash_cooldown", "blocking" -> ZERO;
            case "is_shaking", "is_shaking_wetness" -> b(s -> s.e instanceof net.minecraft.world.entity.animal.wolf.Wolf w && w.getShakeAnim(s.pt) > 0);
            case "tail_angle" -> f(s -> s.e instanceof net.minecraft.world.entity.animal.wolf.Wolf w ? w.getTailAngle() : 0);
            case "is_admiring" -> b(s -> s.e instanceof net.minecraft.world.entity.monster.piglin.Piglin p && p.getOffhandItem().is(net.minecraft.world.item.Items.GOLD_INGOT));
            case "is_dancing" -> b(s -> s.e instanceof net.minecraft.world.entity.monster.piglin.Piglin p && p.isDancing());
            case "is_resting" -> b(s -> s.e instanceof net.minecraft.world.entity.ambient.Bat bat && bat.isResting());
            case "is_interested" -> b(s -> s.e instanceof net.minecraft.world.entity.animal.wolf.Wolf w && w.isInterested());
            case "is_jumping", "is_jump_goal_jumping" -> b(s -> !s.e.onGround() && s.e.getKnownMovement().y > 0);
            case "is_grazing" -> b(s -> s.e instanceof net.minecraft.world.entity.animal.sheep.Sheep sh && sh.getHeadEatPositionScale(s.pt) > 0);
            case "cardinal_facing" -> f(s -> s.e.getDirection().get3DDataValue());
            // from what really moved, standing still is 0 0 0 (not gravity pointing down)
            case "movement_direction" -> f(s -> {
                int ax = (int) arg(a, 0);
                Vec3 m = new Vec3(s.e.getX() - s.e.xo, s.e.getY() - s.e.yo, s.e.getZ() - s.e.zo);
                if (m.lengthSqr() < 1e-6) return 0;
                m = m.normalize();
                return ax == 0 ? m.x : ax == 1 ? m.y : m.z;
            });
            case "cooldown_time" -> f(s -> { float[] c = cooldown(s, held(s, a.isEmpty() ? "slot.weapon.mainhand" : sarg(a, 0))); return c == null ? 0 : c[1]; });
            case "cooldown_time_remaining" -> f(s -> { float[] c = cooldown(s, held(s, a.isEmpty() ? "slot.weapon.mainhand" : sarg(a, 0))); return c == null ? 0 : c[0]; });
            // 0 head 1 chest 2 legs 3 feet 4 body
            case "armor_texture_slot" -> f(s -> {
                if (s.le == null) return 0;
                return zbroja(s.le.getItemBySlot(zbrojaSlot((int) arg(a, 0))));
            });
            case "ride_body_y_rotation" -> f(s -> {
                Entity v = s.e.getVehicle();
                if (v == null) return 0;
                return v instanceof LivingEntity lv ? Mth.rotLerp(s.pt, lv.yBodyRotO, lv.yBodyRot) : v.getYRot(s.pt);
            });
            // the angles that would point from the entity to the camera: 0 = pitch, 1 = yaw
            case "rotation_to_camera" -> f(s -> {
                Vec3 d = Minecraft.getInstance().gameRenderer.mainCamera().position().subtract(s.e.getPosition(s.pt));
                if (arg(a, 0) == 0) return -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
                return Math.toDegrees(Math.atan2(-d.x, d.z));
            });
            // the frame an animated item shows: a cast rod 1, a bow 0..3 while drawn
            case "get_animation_frame" -> f(s -> {
                if (s.e instanceof Player pl && pl.fishing != null) return 1;
                if (s.le != null && s.le.isUsingItem() && s.le.getUseItem().getItem() instanceof net.minecraft.world.item.BowItem) {
                    float pull = (s.le.getUseItem().getUseDuration(s.le) - s.le.getUseItemRemainingTicks() + s.pt) / 20f;
                    return pull >= 0.9f ? 3 : pull >= 0.65f ? 2 : pull > 0 ? 1 : 0;
                }
                return 0;
            });
            case "time_stamp" -> f(s -> s.e.level().getGameTime());
            case "texture_frame_index" -> f(s -> s.e instanceof net.minecraft.world.entity.ExperienceOrb o ? o.getIcon() : 0);
            case "hurt_direction" -> f(s -> s.e instanceof net.minecraft.world.entity.vehicle.VehicleEntity v ? v.getHurtDir() : 0);
            case "is_sonic_boom" -> b(s -> s.e instanceof net.minecraft.world.entity.monster.warden.Warden w && w.sonicBoomAnimationState.isStarted());
            case "heartbeat_phase" -> f(s -> s.e instanceof net.minecraft.world.entity.monster.warden.Warden w ? w.getHeartAnimation(s.pt) : 0);
            // the tendrils light up at 1 when it hears something and fade over 10 ticks: seconds since = how far they faded
            case "time_since_last_vibration_detection" -> f(s -> {
                if (!(s.e instanceof net.minecraft.world.entity.monster.warden.Warden w)) return 0;
                float t = w.getTendrilAnimation(s.pt);
                return t > 0 ? (1 - t) * 0.5 : 1000;
            });
            case "is_shield_powered" -> b(s -> s.e instanceof net.minecraft.world.entity.boss.wither.WitherBoss w && w.isPowered());
            case "is_searching" -> b(s -> s.e instanceof net.minecraft.world.entity.animal.fox.Fox fx && fx.isInterested());
            case "is_stalking" -> b(s -> s.e instanceof net.minecraft.world.entity.animal.fox.Fox fx && fx.isCrouching());
            case "sneeze_counter" -> f(s -> s.e instanceof net.minecraft.world.entity.animal.panda.Panda pa ? pa.getSneezeCounter() : 0);
            case "roll_counter" -> f(s -> s.e instanceof net.minecraft.world.entity.animal.panda.Panda pa ? pa.getRollAmount(s.pt) : 0);
            case "sit_amount" -> f(s -> s.e instanceof net.minecraft.world.entity.animal.panda.Panda pa ? pa.getSitAmount(s.pt) : 0);
            case "lie_amount" -> f(s -> s.e instanceof net.minecraft.world.entity.animal.panda.Panda pa ? pa.getLieOnBackAmount(s.pt) : 0);
            // java bends the cape by 6 + lean/2 + flap degrees, bedrock lerps 0..-126 on this, same thing backwards
            case "cape_flap_amount" -> f(s -> {
                if (!(s.e instanceof net.minecraft.client.entity.ClientAvatarEntity av) || !(s.e instanceof LivingEntity le)) return 0;
                var st = av.avatarState();
                float pt = s.pt;
                double dx = st.getInterpolatedCloakX(pt) - Mth.lerp(pt, le.xo, le.getX());
                double dy = st.getInterpolatedCloakY(pt) - Mth.lerp(pt, le.yo, le.getY());
                double dz = st.getInterpolatedCloakZ(pt) - Mth.lerp(pt, le.zo, le.getZ());
                float yb = Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot) * (float) (Math.PI / 180.0);
                float flap = Mth.clamp((float) dy * 10f, -6f, 32f)
                    + Mth.sin(st.getInterpolatedWalkDistance(pt) * 6f) * 32f * st.getInterpolatedBob(pt);
                float lean = Mth.clamp((float) (dx * Mth.sin(yb) - dz * Mth.cos(yb)) * 100f, 0f, 150f);
                if (le.isFallFlying()) lean = 0;
                return Mth.clamp((lean / 2f + flap) / 126f, 0f, 1f);
            });
            // degrees of the bed it lies in, 0 = head to the south like a yaw. TODO check against a real bed shot
            case "sleep_rotation" -> f(s -> s.le != null && s.le.getBedOrientation() != null ? s.le.getBedOrientation().toYRot() : 0);
            // the whole wind up and swing, synced from KoperDelayedAttackGoal. java mobs only have the arm swing to go on
            case "is_delayed_attacking" -> b(s -> s.e instanceof com.koper.koper_lib.api.core.KoperDelayedAttacker d ? d.koperDelayedAttacking() : s.le != null && s.le.isSwinging());
            case "can_fly" -> b(s -> BrStany.flag(s.e.getId(), "can_fly") || s.e instanceof Mob m && m.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.FlyingPathNavigation);
            // java has one input mode as far as a pack can tell, touch launchers on phones still feed it keys and mouse
            case "last_input_mode_is_any" -> {
                boolean km = false;
                for (int i = 0; i < a.size(); i++) if ("keyboard_and_mouse".equals(sarg(a, i))) km = true;
                boolean wynik = km;
                yield b(s -> wynik);
            }
            case "is_stackable" -> b(s -> BrStany.flag(s.e.getId(), "is_stackable"));
            case "ticks_since_last_kinetic_weapon_hit" -> f(s -> s.le == null ? 0 : s.le.getTicksSinceLastKineticHitFeedback(s.pt));
            // the client never runs the flee goal, so: is something hostile close and are we moving away from it.
            // villagers run from zombies inside 8 blocks, that's the number
            case "is_avoiding_mobs" -> b(s -> {
                if (!(s.e instanceof net.minecraft.world.entity.PathfinderMob m)) return false;
                var v = m.getDeltaMovement();
                if (v.horizontalDistanceSqr() < 1.0E-4) return false;
                for (var z : m.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, m.getBoundingBox().inflate(8))) {
                    double ox = m.getX() - z.getX(), oz = m.getZ() - z.getZ();
                    if (ox * v.x + oz * v.z > 0) return true;
                }
                return false;
            });
            // 0 head 1 chest 2 legs 3 feet 4 body, then the color channel r g b a, 0..1
            case "armor_color_slot" -> f(s -> {
                if (s.le == null) return 0;
                ItemStack st = s.le.getItemBySlot(zbrojaSlot((int) arg(a, 0)));
                if (st.isEmpty()) return 0;
                int c = net.minecraft.world.item.component.DyedItemColor.getOrDefault(st, 0xFFA06540);
                int ch = (int) arg(a, 1);
                return ch == 3 ? 1 : ((c >> (16 - ch * 8)) & 255) / 255.0;
            });
            case "armor_damage_slot" -> f(s -> s.le == null ? 0 : s.le.getItemBySlot(zbrojaSlot((int) arg(a, 0))).getDamageValue());
            // +1 puffing up, -1 calming down: the creeper's fuse animation picks its direction from this
            case "swelling_dir" -> f(s -> s.e instanceof net.minecraft.world.entity.monster.Creeper c ? c.getSwellDir() : 0);
            case "camera_rotation" -> f(s -> {
                var cam = Minecraft.getInstance().gameRenderer.mainCamera();
                return arg(a, 0) == 0 ? cam.xRot() : cam.yRot();
            });
            // the bow raised at a target: a skeleton aims while it is aggressive with a ranged weapon in hand
            case "facing_target_to_range_attack" -> b(s -> s.e instanceof Mob m && m.isAggressive()
                && (m.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem
                    || m.getOffhandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem));
            // a locator's resting offset in the geometry of the entity at the root (the wearer, for armor)
            case "get_root_locator_offset" -> f(s -> {
                float[] l = BrAktorzy.rootLocator(s.e, sarg(a, 0));
                int ax = a.size() > 1 ? (int) arg(a, 1) : 0;
                return l == null ? 0 : l[Math.clamp(ax, 0, 2)];
            });
            case "trade_tier" -> f(s -> s.e instanceof net.minecraft.world.entity.npc.villager.Villager v ? v.getVillagerData().level() - 1 : 0);
            default -> null;
        };
    }

    private static EquipmentSlot armorSlot(int i) {
        return switch (i) {
            case 0 -> EquipmentSlot.HEAD;
            case 1 -> EquipmentSlot.CHEST;
            case 2 -> EquipmentSlot.LEGS;
            case 3 -> EquipmentSlot.FEET;
            default -> EquipmentSlot.BODY;
        };
    }
}
