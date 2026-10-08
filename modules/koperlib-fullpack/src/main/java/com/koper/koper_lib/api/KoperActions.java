package com.koper.koper_lib.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperStackData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

public final class KoperActions {
    static {
        installBuiltins();
    }

    private KoperActions() {}

    public static void init() {
        // touching this class installs builtin calls
    }

    public static InteractionResult run(JsonElement actions, KoperContext ctx, String event, String ownerId) {
        if (actions == null || actions.isJsonNull()) return InteractionResult.PASS;
        if (actions.isJsonArray()) {
            InteractionResult result = InteractionResult.PASS;
            for (JsonElement el : actions.getAsJsonArray()) {
                result = run(el, ctx, event, ownerId);
                if (result != InteractionResult.PASS) return result;
            }
            return result;
        }
        if (actions.isJsonPrimitive()) {
            JsonObject o = new JsonObject();
            o.addProperty("call", actions.getAsString());
            return runObject(o, ctx, event, ownerId);
        }
        if (actions.isJsonObject()) return runObject(actions.getAsJsonObject(), ctx, event, ownerId);
        return InteractionResult.PASS;
    }

    private static InteractionResult runObject(JsonObject action, KoperContext ctx, String event, String ownerId) {
        if (hasCondition(action)) {
            if (!matchesCondition(action, ctx)) {
                return action.has("else") ? run(action.get("else"), ctx, event, ownerId) : InteractionResult.PASS;
            }
            if (action.has("then")) return run(action.get("then"), ctx, event, ownerId);
        }

        InteractionResult control = runControl(action, ctx, event, ownerId);
        if (control != null) return control;

        String call = findCall(action);
        if (call != null) return KoperCalls.run(call, new KoperCallContext(ctx, action, event, ownerId));

        InteractionResult result = InteractionResult.PASS;
        if (action.has("cooldown")) result = first(result, KoperCalls.run("item.cooldown", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("kfx") || action.has("kfx_cast")) result = first(result, KoperCalls.run("kfx.cast", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("kfx_at")) result = first(result, KoperCalls.run("kfx.at", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("script") || action.has("lua")) result = first(result, KoperCalls.run("lua", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("hook") || action.has("java")) result = first(result, KoperCalls.run("java", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("command")) result = first(result, KoperCalls.run("command", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("effect") || action.has("effects")) result = first(result, KoperCalls.run("effect.apply", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("sound")) result = first(result, KoperCalls.run("sound.play", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("damage") || action.has("damage_target")) result = first(result, KoperCalls.run("damage.target", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("heal") || action.has("heal_target")) result = first(result, KoperCalls.run("entity.heal", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("set_health")) result = first(result, KoperCalls.run("entity.health", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("velocity") || action.has("push") || action.has("set_velocity")) result = first(result, KoperCalls.run("entity.velocity", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("teleport") || action.has("tp") || action.has("offset")) result = first(result, KoperCalls.run("entity.teleport", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("fire") || action.has("freeze") || action.has("extinguish")) result = first(result, KoperCalls.run("entity.element", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("remove_effect") || action.has("clear_effect")) result = first(result, KoperCalls.run("effect.clear", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("attribute") || action.has("set_attribute") || action.has("add_attribute")) result = first(result, KoperCalls.run("entity.attribute", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("player_stats") || action.has("food_level") || action.has("set_food") || action.has("add_food")
                || action.has("saturation") || action.has("set_saturation") || action.has("add_exhaustion")
                || action.has("add_xp") || action.has("xp_points") || action.has("add_xp_levels") || action.has("set_xp_levels"))
            result = first(result, KoperCalls.run("player.stats", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("give")) result = first(result, KoperCalls.run("item.give", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("drop_item")) result = first(result, KoperCalls.run("item.drop", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("inventory") || action.has("take_item") || action.has("remove_item") || action.has("drop_inventory_item")
                || action.has("clear_inventory") || action.has("count_item") || action.has("set_slot"))
            result = first(result, KoperCalls.run("inventory", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("set_block") || action.has("block")) result = first(result, KoperCalls.run("block.set", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("set_state")) result = first(result, KoperCalls.run("block.state", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("toggle_state")) result = first(result, KoperCalls.run("block.toggle_state", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("consume_stack")) result = first(result, KoperCalls.run("item.consume", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("damage_item")) result = first(result, KoperCalls.run("item.damage", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("open_gui") || action.has("gui")) result = first(result, KoperCalls.run("kui.open", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("stack_data")) result = first(result, KoperCalls.run("item.stack_data", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("item_stack") || action.has("set_count") || action.has("add_count")
                || action.has("custom_name") || action.has("item_name") || action.has("lore")
                || action.has("set_lore") || action.has("glint") || action.has("custom_model_data"))
            result = first(result, KoperCalls.run("item.stack", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("state") || action.has("item_state")) result = first(result, KoperCalls.run("item.state", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("animation") || action.has("play_animation") || action.has("item_animation")) result = first(result, KoperCalls.run("item.animation", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("explosion") || action.has("explode")) result = first(result, KoperCalls.run("world.explosion", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("particle") || action.has("particles")) result = first(result, KoperCalls.run("world.particle", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("spawn_entity") || action.has("summon_entity") || action.has("summon")) result = first(result, KoperCalls.run("entity.spawn", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("shoot_projectile") || action.has("fire_projectile") || action.has("projectile")) result = first(result, KoperCalls.run("projectile.shoot", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("lightning") || action.has("strike_lightning")) result = first(result, KoperCalls.run("world.lightning", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("falling_block")) result = first(result, KoperCalls.run("world.falling_block", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("fill") || action.has("fill_block")) result = first(result, KoperCalls.run("world.fill", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("weather")) result = first(result, KoperCalls.run("world.weather", new KoperCallContext(ctx, action, event, ownerId)));
        if (action.has("time") || action.has("day_time")) result = first(result, KoperCalls.run("world.time", new KoperCallContext(ctx, action, event, ownerId)));
        return result;
    }

    private static String findCall(JsonObject action) {
        if (action.has("call")) return action.get("call").getAsString();
        if (action.has("type")) {
            String type = action.get("type").getAsString();
            if (type.contains(".") || type.contains(":")) return type;
        }
        return null;
    }

    private static InteractionResult first(InteractionResult current, InteractionResult next) {
        return current != InteractionResult.PASS ? current : next;
    }

    private static InteractionResult runControl(JsonObject action, KoperContext ctx, String event, String ownerId) {
        if (action.has("choose") || action.has("random")) {
            JsonElement choice = chooseAction(action.has("choose") ? action.get("choose") : action.get("random"));
            return choice != null ? run(choice, ctx, event, ownerId) : InteractionResult.PASS;
        }
        if (action.has("delay") || action.has("delay_ticks") || action.has("later")) {
            int ticks = action.has("delay_ticks") ? action.get("delay_ticks").getAsInt()
                    : action.has("delay") ? action.get("delay").getAsInt() : action.get("later").getAsInt();
            JsonElement payload = actionPayload(action);
            schedule(Math.max(1, ticks), payload, ctx, event, ownerId);
            return InteractionResult.PASS;
        }
        if (action.has("repeat")) {
            JsonObject data = action.get("repeat").isJsonObject() ? action.getAsJsonObject("repeat") : action;
            int times = data.has("times") ? data.get("times").getAsInt()
                    : action.get("repeat").isJsonPrimitive() ? action.get("repeat").getAsInt() : 1;
            int every = data.has("every") ? data.get("every").getAsInt()
                    : data.has("interval") ? data.get("interval").getAsInt()
                    : action.has("every") ? action.get("every").getAsInt() : 1;
            int delay = data.has("delay") ? data.get("delay").getAsInt()
                    : action.has("delay") ? action.get("delay").getAsInt() : 0;
            JsonElement payload = actionPayload(data);
            if (payload == null || payload.isJsonNull()) payload = actionPayload(action);
            int capped = Math.max(0, Math.min(times, 256));
            for (int i = 0; i < capped; i++) schedule(delay + i * Math.max(1, every), payload, ctx, event, ownerId);
            return InteractionResult.PASS;
        }
        return null;
    }

    private static boolean hasCondition(JsonObject action) {
        return action.has("if") || action.has("condition") || action.has("conditions") || action.has("chance");
    }

    private static JsonElement actionPayload(JsonObject action) {
        if (action == null) return null;
        for (String key : new String[]{"actions", "action", "run", "do"}) {
            if (action.has(key)) return action.get(key);
        }
        JsonObject copy = new JsonObject();
        for (var e : action.entrySet()) {
            String k = e.getKey();
            if (k.equals("delay") || k.equals("delay_ticks") || k.equals("later")
                    || k.equals("repeat") || k.equals("times") || k.equals("every") || k.equals("interval")
                    || k.equals("choose") || k.equals("random")) continue;
            copy.add(k, e.getValue());
        }
        return copy.size() > 0 ? copy : null;
    }

    private static void schedule(int ticks, JsonElement payload, KoperContext ctx, String event, String ownerId) {
        if (payload == null || payload.isJsonNull()) return;
        var server = com.koper.koper_lib.scripting.UniversalScriptEngine.getCurrentServer();
        com.koper.koper_lib.core.KoperTasks.later(server, Math.max(1, ticks), () -> run(payload, ctx, event, ownerId));
    }

    private static JsonElement chooseAction(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (!el.isJsonArray()) return el;
        JsonArray arr = el.getAsJsonArray();
        if (arr.isEmpty()) return null;
        double total = 0.0;
        for (JsonElement entry : arr) total += Math.max(0.0, choiceWeight(entry));
        if (total <= 0.0) return arr.get((int)(Math.random() * arr.size()));
        double pick = Math.random() * total;
        for (JsonElement entry : arr) {
            pick -= Math.max(0.0, choiceWeight(entry));
            if (pick <= 0.0) return choicePayload(entry);
        }
        return choicePayload(arr.get(arr.size() - 1));
    }

    private static double choiceWeight(JsonElement entry) {
        if (entry != null && entry.isJsonObject()) {
            JsonObject o = entry.getAsJsonObject();
            if ((o.has("action") || o.has("actions") || o.has("run")) && o.has("weight")) return o.get("weight").getAsDouble();
        }
        return 1.0;
    }

    private static JsonElement choicePayload(JsonElement entry) {
        if (entry != null && entry.isJsonObject()) {
            JsonObject o = entry.getAsJsonObject();
            for (String key : new String[]{"action", "actions", "run"}) if (o.has(key)) return o.get(key);
        }
        return entry;
    }

    private static boolean matchesCondition(JsonObject action, KoperContext ctx) {
        if (action.has("chance") && Math.random() > action.get("chance").getAsDouble()) return false;
        boolean ok = true;
        if (action.has("if")) ok &= conditionElement(action.get("if"), ctx);
        if (action.has("condition")) ok &= conditionElement(action.get("condition"), ctx);
        if (action.has("conditions")) ok &= conditionElement(action.get("conditions"), ctx);
        return ok;
    }

    private static boolean conditionElement(JsonElement el, KoperContext ctx) {
        if (el == null || el.isJsonNull()) return true;
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) if (!conditionElement(child, ctx)) return false;
            return true;
        }
        if (el.isJsonPrimitive()) return namedCondition(el.getAsString(), ctx);
        if (!el.isJsonObject()) return true;
        JsonObject c = el.getAsJsonObject();
        if (c.has("all") && !conditionElement(c.get("all"), ctx)) return false;
        if (c.has("any") && !anyCondition(c.get("any"), ctx)) return false;
        if (c.has("not") && conditionElement(c.get("not"), ctx)) return false;
        if (c.has("chance") && Math.random() > c.get("chance").getAsDouble()) return false;
        if (c.has("sneaking") && entitySneaking(ctx) != c.get("sneaking").getAsBoolean()) return false;
        if (c.has("sprinting") && entitySprinting(ctx) != c.get("sprinting").getAsBoolean()) return false;
        if (c.has("creative") && (ctx.player() == null || ctx.player().isCreative() != c.get("creative").getAsBoolean())) return false;
        if (c.has("state") && !stackStateIs(ctx, c.get("state").getAsString())) return false;
        if (c.has("item_state") && !stackStateIs(ctx, c.get("item_state").getAsString())) return false;
        if (c.has("animation") && !KoperStackData.getString(ctx.stack(), "animation", "").equals(c.get("animation").getAsString())) return false;
        if (c.has("stack_data") && !stackDataMatches(ctx.stack(), c.get("stack_data"))) return false;
        if (c.has("has_item") && !hasInventoryItem(ctx.player(), c.get("has_item"))) return false;
        if (c.has("count_item") && !inventoryCountMatches(ctx.player(), c.get("count_item"))) return false;
        if (c.has("health_below") && !(conditionLiving(ctx, false) != null && conditionLiving(ctx, false).getHealth() < c.get("health_below").getAsFloat())) return false;
        if (c.has("health_above") && !(conditionLiving(ctx, false) != null && conditionLiving(ctx, false).getHealth() > c.get("health_above").getAsFloat())) return false;
        if (c.has("target_health_below") && !(ctx.target() != null && ctx.target().getHealth() < c.get("target_health_below").getAsFloat())) return false;
        if (c.has("target_health_above") && !(ctx.target() != null && ctx.target().getHealth() > c.get("target_health_above").getAsFloat())) return false;
        if (c.has("dimension") && !dimensionIs(ctx, c.get("dimension").getAsString())) return false;
        if (c.has("block") && !blockIs(ctx, c.get("block").getAsString())) return false;
        if (c.has("block_state") && !blockStateMatches(ctx, c.get("block_state"))) return false;
        return true;
    }

    private static boolean anyCondition(JsonElement el, KoperContext ctx) {
        if (el == null || el.isJsonNull()) return true;
        if (!el.isJsonArray()) return conditionElement(el, ctx);
        for (JsonElement child : el.getAsJsonArray()) if (conditionElement(child, ctx)) return true;
        return false;
    }

    private static boolean namedCondition(String raw, KoperContext ctx) {
        return switch (raw == null ? "" : raw.toLowerCase()) {
            case "sneaking", "shift" -> entitySneaking(ctx);
            case "sprinting" -> entitySprinting(ctx);
            case "has_target" -> ctx != null && ctx.target() != null;
            case "has_player" -> ctx != null && ctx.player() != null;
            case "has_stack" -> ctx != null && !ctx.stack().isEmpty();
            default -> true;
        };
    }

    private static boolean entitySneaking(KoperContext ctx) {
        if (ctx == null) return false;
        Entity e = ctx.entity() != null ? ctx.entity() : ctx.player();
        return e != null && e.isShiftKeyDown();
    }

    private static boolean entitySprinting(KoperContext ctx) {
        if (ctx == null) return false;
        Entity e = ctx.entity() != null ? ctx.entity() : ctx.player();
        return e != null && e.isSprinting();
    }

    private static LivingEntity conditionLiving(KoperContext ctx, boolean preferTarget) {
        if (ctx == null) return null;
        if (preferTarget && ctx.target() != null) return ctx.target();
        if (ctx.entity() != null) return ctx.entity();
        return ctx.player();
    }

    private static boolean stackStateIs(KoperContext ctx, String state) {
        return ctx != null && !ctx.stack().isEmpty() && KoperStackData.getString(ctx.stack(), "state", "").equals(state);
    }

    private static boolean stackDataMatches(ItemStack stack, JsonElement el) {
        if (stack == null || stack.isEmpty() || el == null || !el.isJsonObject()) return false;
        JsonObject o = el.getAsJsonObject();
        if (o.has("key")) return stackDataValueMatches(stack, o.get("key").getAsString(), o.get("value"));
        for (var e : o.entrySet()) if (!stackDataValueMatches(stack, e.getKey(), e.getValue())) return false;
        return true;
    }

    private static boolean stackDataValueMatches(ItemStack stack, String key, JsonElement expected) {
        if (expected == null || expected.isJsonNull()) return KoperStackData.getString(stack, key, "").isBlank();
        if (expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isBoolean())
            return KoperStackData.getBool(stack, key, !expected.getAsBoolean()) == expected.getAsBoolean();
        if (expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isNumber())
            return Double.compare(KoperStackData.getDouble(stack, key, Double.NaN), expected.getAsDouble()) == 0;
        return KoperStackData.getString(stack, key, "").equals(expected.getAsString());
    }

    private static boolean hasInventoryItem(ServerPlayer player, JsonElement el) {
        JsonObject o = el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        String id = el != null && el.isJsonPrimitive() ? el.getAsString() : itemIdFrom(o, "");
        int need = o.has("count") ? o.get("count").getAsInt() : 1;
        return countInventoryItems(player, id) >= Math.max(1, need);
    }

    private static boolean inventoryCountMatches(ServerPlayer player, JsonElement el) {
        if (player == null || el == null) return false;
        JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        String id = el.isJsonPrimitive() ? el.getAsString() : itemIdFrom(o, "");
        int count = countInventoryItems(player, id);
        if (o.has("min") && count < o.get("min").getAsInt()) return false;
        if (o.has("max") && count > o.get("max").getAsInt()) return false;
        if (o.has("count") && count != o.get("count").getAsInt()) return false;
        return true;
    }

    private static boolean dimensionIs(KoperContext ctx, String dimension) {
        return ctx != null && ctx.world() != null && ctx.world().dimension().identifier().toString().equals(dimension);
    }

    private static boolean blockIs(KoperContext ctx, String blockId) {
        if (ctx == null || ctx.world() == null || ctx.pos() == null) return false;
        return BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(ctx.pos()).getBlock()).toString().equals(blockId);
    }

    private static boolean blockStateMatches(KoperContext ctx, JsonElement el) {
        if (ctx == null || ctx.world() == null || ctx.pos() == null || el == null || !el.isJsonObject()) return false;
        JsonObject o = el.getAsJsonObject();
        String property = o.has("property") ? o.get("property").getAsString() : o.has("name") ? o.get("name").getAsString() : "";
        if (property.isBlank() || !o.has("value")) return false;
        var state = ctx.world().getBlockState(ctx.pos());
        String want = o.get("value").getAsString();
        String propName = property.equals("horizontal_facing") ? "facing" : property;
        for (var prop : state.getProperties()) {
            if (prop.getName().equals(propName)) return state.getValue(prop).toString().equalsIgnoreCase(want);
        }
        return false;
    }

    private static void installBuiltins() {
        KoperCalls.register("java", ctx -> ctx.callJava(ctx.string("hook", ctx.string("java", ""))));
        KoperCalls.register("lua", ctx -> {
            ctx.callLua(ctx.string("script", ctx.string("lua", "")));
            return InteractionResult.PASS;
        });
        KoperCalls.register("kfx.cast", ctx -> {
            String id = ctx.string("kfx", ctx.string("kfx_cast", ctx.string("id", "")));
            ctx.kfxCast(id, ctx.number("range", 12.0f));
            return InteractionResult.PASS;
        });
        KoperCalls.register("kfx.at", ctx -> {
            String id = ctx.string("kfx_at", ctx.string("kfx", ctx.string("id", "")));
            ctx.kfxAt(id);
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.cooldown", ctx -> {
            ctx.cooldown(ctx.integer("cooldown", ctx.integer("ticks", 0)));
            return InteractionResult.PASS;
        });
        KoperCalls.register("command", ctx -> {
            ServerPlayer player = ctx.player();
            if (player == null) return InteractionResult.PASS;
            String cmd = expand(ctx.string("command", ""), ctx);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                // run AS the player, perms bumped — console execution swallowed every reply
                // (and /koperlib requires isPlayer!), so command items looked stone dead
                var server = player.level().getServer();
                if (server != null) server.getCommands().getDispatcher()
                        .execute(cmd, player.createCommandSourceStack()
                                .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS));
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[KoperAction] command failed '{}': {}", cmd, e.getMessage());
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("sound.play", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            String id = ctx.string("sound", ctx.string("id", ""));
            Identifier sid = Identifier.tryParse(id);
            if (sid == null) return InteractionResult.PASS;
            var sound = BuiltInRegistries.SOUND_EVENT.getValue(sid);
            if (sound == null) return InteractionResult.PASS;
            double x = ctx.ctx().pos() != null ? ctx.ctx().pos().getX() + 0.5 : (ctx.entity() != null ? ctx.entity().getX() : 0.0);
            double y = ctx.ctx().pos() != null ? ctx.ctx().pos().getY() + 0.5 : (ctx.entity() != null ? ctx.entity().getY() : 0.0);
            double z = ctx.ctx().pos() != null ? ctx.ctx().pos().getZ() + 0.5 : (ctx.entity() != null ? ctx.entity().getZ() : 0.0);
            level.playSound(null, x, y, z, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                SoundSource.MASTER, ctx.number("volume", 1.0f), ctx.number("pitch", 1.0f));
            return InteractionResult.PASS;
        });
        KoperCalls.register("effect.apply", ctx -> {
            JsonElement effects = ctx.json().has("effects") ? ctx.json().get("effects") : ctx.json().get("effect");
            LivingEntity target = ctx.target() != null ? ctx.target() : ctx.entity();
            applyEffects(effects, target);
            return InteractionResult.PASS;
        });
        KoperCalls.register("damage.target", ctx -> {
            LivingEntity target = livingTarget(ctx, true);
            if (target == null || !(target.level() instanceof ServerLevel level)) return InteractionResult.PASS;
            float amount = ctx.number("damage_target", ctx.number("damage", 0.0f));
            if (amount > 0) target.hurtServer(level, level.damageSources().generic(), amount);
            return InteractionResult.PASS;
        });
        KoperCalls.register("entity.heal", ctx -> {
            LivingEntity target = livingTarget(ctx, ctx.json().has("heal_target"));
            float amount = ctx.number("heal_target", ctx.number("heal", 0.0f));
            if (target != null && amount > 0) target.heal(amount);
            return InteractionResult.PASS;
        });
        KoperCalls.register("entity.health", ctx -> {
            LivingEntity target = livingTarget(ctx, false);
            if (target != null) target.setHealth(ctx.number("set_health", target.getHealth()));
            return InteractionResult.PASS;
        });
        KoperCalls.register("entity.velocity", ctx -> {
            Entity target = entityTarget(ctx, ctx.target() != null && !ctx.json().has("target"));
            if (target == null) return InteractionResult.PASS;
            JsonElement data = firstPresent(ctx.json(), "set_velocity", "velocity", "push");
            Vec3 vec = vector(data, 0.0, 0.0, 0.0);
            if (ctx.json().has("towards_target") && ctx.target() != null) {
                double power = ctx.number("power", ctx.number("strength", 1.0f));
                Vec3 delta = ctx.target().position().subtract(target.position());
                if (delta.lengthSqr() > 0.0001) vec = delta.normalize().scale(power);
            }
            if (ctx.json().has("set_velocity")) target.setDeltaMovement(vec);
            else target.setDeltaMovement(target.getDeltaMovement().add(vec));
            markVelocity(target);
            return InteractionResult.PASS;
        });
        KoperCalls.register("entity.teleport", ctx -> {
            Entity target = entityTarget(ctx, false);
            if (target == null) return InteractionResult.PASS;
            boolean offset = ctx.json().has("offset") || ctx.bool("relative", false);
            JsonElement data = firstPresent(ctx.json(), "teleport", "tp", "offset");
            Vec3 vec = vector(data, target.getX(), target.getY(), target.getZ());
            if (offset) vec = target.position().add(vec);
            target.teleportTo(vec.x, vec.y, vec.z);
            return InteractionResult.PASS;
        });
        KoperCalls.register("entity.element", ctx -> {
            Entity target = entityTarget(ctx, false);
            if (target == null) return InteractionResult.PASS;
            if (ctx.json().has("fire")) {
                JsonObject fire = objectFor(ctx.json(), "fire");
                int ticks = fire == ctx.json() ? ctx.integer("fire", ctx.integer("ticks", 0)) : (int)dataNumber(fire, "ticks", dataNumber(fire, "duration", 0));
                if (ticks > 0) target.igniteForSeconds(ticks / 20.0f);
            }
            if (ctx.json().has("freeze")) {
                JsonObject freeze = objectFor(ctx.json(), "freeze");
                int ticks = freeze == ctx.json() ? ctx.integer("freeze", ctx.integer("ticks", 0)) : (int)dataNumber(freeze, "ticks", dataNumber(freeze, "duration", 0));
                target.setTicksFrozen(ticks);
            }
            if (ctx.bool("extinguish", false)) target.extinguishFire();
            return InteractionResult.PASS;
        });
        KoperCalls.register("effect.clear", ctx -> {
            LivingEntity target = livingTarget(ctx, false);
            if (target == null) return InteractionResult.PASS;
            String id = ctx.string("remove_effect", ctx.string("clear_effect", ctx.string("id", "")));
            Identifier eid = Identifier.tryParse(id);
            if (eid == null) return InteractionResult.PASS;
            var effect = BuiltInRegistries.MOB_EFFECT.getValue(eid);
            if (effect != null) target.removeEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect));
            return InteractionResult.PASS;
        });
        KoperCalls.register("entity.attribute", ctx -> {
            LivingEntity target = livingTarget(ctx, false);
            if (target == null) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "set_attribute", "add_attribute", "attribute");
            String raw = data.has("id") ? data.get("id").getAsString()
                : data.has("attribute") ? data.get("attribute").getAsString()
                : ctx.json().has("set_attribute") && ctx.json().get("set_attribute").isJsonPrimitive() ? ctx.json().get("set_attribute").getAsString()
                : ctx.json().has("add_attribute") && ctx.json().get("add_attribute").isJsonPrimitive() ? ctx.json().get("add_attribute").getAsString()
                : ctx.string("attribute", "");
            Identifier attrId = normalizeAttributeId(raw);
            if (attrId == null) return InteractionResult.PASS;
            var attr = BuiltInRegistries.ATTRIBUTE.getOptional(attrId);
            if (attr.isEmpty()) return InteractionResult.PASS;
            AttributeInstance inst = target.getAttribute(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attr.get()));
            if (inst == null) return InteractionResult.PASS;
            if (data.has("set") || ctx.json().has("set_attribute")) inst.setBaseValue(data.has("set") ? data.get("set").getAsDouble() : dataNumber(data, "value", inst.getBaseValue()));
            else inst.setBaseValue(inst.getBaseValue() + dataNumber(data, "add", dataNumber(data, "amount", 0.0)));
            return InteractionResult.PASS;
        });
        KoperCalls.register("player.stats", ctx -> {
            ServerPlayer player = ctx.player();
            if (player == null) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "player_stats");
            var food = player.getFoodData();
            if (data.has("food_level") || data.has("set_food") || data.has("food")) {
                int value = (int)dataNumber(data, "food_level", dataNumber(data, "set_food", dataNumber(data, "food", food.getFoodLevel())));
                food.setFoodLevel(Math.max(0, Math.min(20, value)));
            }
            if (data.has("add_food")) {
                int value = food.getFoodLevel() + data.get("add_food").getAsInt();
                food.setFoodLevel(Math.max(0, Math.min(20, value)));
            }
            if (data.has("saturation") || data.has("set_saturation")) {
                float value = (float)dataNumber(data, "set_saturation", dataNumber(data, "saturation", food.getSaturationLevel()));
                food.setSaturation(Math.max(0.0f, value));
            }
            if (data.has("add_saturation")) {
                food.setSaturation(Math.max(0.0f, food.getSaturationLevel() + data.get("add_saturation").getAsFloat()));
            }
            if (data.has("add_exhaustion")) food.addExhaustion(Math.max(0.0f, data.get("add_exhaustion").getAsFloat()));
            if (data.has("add_xp") || data.has("xp_points")) player.giveExperiencePoints((int)dataNumber(data, "add_xp", dataNumber(data, "xp_points", 0)));
            if (data.has("add_xp_levels") || data.has("xp_levels")) player.giveExperienceLevels((int)dataNumber(data, "add_xp_levels", dataNumber(data, "xp_levels", 0)));
            if (data.has("set_xp_levels")) {
                int delta = data.get("set_xp_levels").getAsInt() - player.experienceLevel;
                if (delta != 0) player.giveExperienceLevels(delta);
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.give", ctx -> {
            ServerPlayer player = ctx.player();
            if (player == null) return InteractionResult.PASS;
            JsonObject give = ctx.json().has("give") && ctx.json().get("give").isJsonObject()
                ? ctx.json().getAsJsonObject("give") : ctx.json();
            String id = give.has("item") ? give.get("item").getAsString() : ctx.string("give", "");
            Identifier iid = Identifier.tryParse(id);
            if (iid == null) return InteractionResult.PASS;
            var item = BuiltInRegistries.ITEM.getValue(iid);
            if (item != null) player.getInventory().add(new ItemStack(item, give.has("count") ? give.get("count").getAsInt() : 1));
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.drop", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject drop = ctx.json().has("drop_item") && ctx.json().get("drop_item").isJsonObject()
                ? ctx.json().getAsJsonObject("drop_item") : ctx.json();
            String id = drop.has("item") ? drop.get("item").getAsString() : ctx.string("drop_item", "");
            Identifier iid = Identifier.tryParse(id);
            if (iid == null) return InteractionResult.PASS;
            var item = BuiltInRegistries.ITEM.getValue(iid);
            if (item == null) return InteractionResult.PASS;
            var pos = ctx.ctx().pos();
            double x = pos != null ? pos.getX() + 0.5 : ctx.entity() != null ? ctx.entity().getX() : 0.0;
            double y = pos != null ? pos.getY() + 0.5 : ctx.entity() != null ? ctx.entity().getY() : 0.0;
            double z = pos != null ? pos.getZ() + 0.5 : ctx.entity() != null ? ctx.entity().getZ() : 0.0;
            net.minecraft.world.Containers.dropItemStack(level, x, y, z,
                new ItemStack(item, drop.has("count") ? drop.get("count").getAsInt() : 1));
            return InteractionResult.PASS;
        });
        KoperCalls.register("inventory", ctx -> {
            ServerPlayer player = ctx.player();
            if (player == null) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "inventory");
            if (ctx.json().has("clear_inventory") || data.has("clear")) {
                if (ctx.bool("clear_inventory", data.has("clear") && data.get("clear").getAsBoolean())) player.getInventory().clearContent();
            }
            if (ctx.json().has("take_item") || ctx.json().has("remove_item") || data.has("take") || data.has("remove")) {
                JsonObject take = objectFor(ctx.json(), "take_item", "remove_item", "take", "remove");
                String id = itemIdFrom(take, ctx.string("take_item", ctx.string("remove_item", "")));
                int count = take.has("count") ? take.get("count").getAsInt() : ctx.integer("count", 1);
                removeInventoryItems(player, id, Math.max(1, count), false);
            }
            if (ctx.json().has("drop_inventory_item") || data.has("drop")) {
                JsonObject drop = objectFor(ctx.json(), "drop_inventory_item", "drop");
                String id = itemIdFrom(drop, ctx.string("drop_inventory_item", ""));
                int count = drop.has("count") ? drop.get("count").getAsInt() : ctx.integer("count", 1);
                removeInventoryItems(player, id, Math.max(1, count), true);
            }
            if (ctx.json().has("count_item") || data.has("count_item") || data.has("count")) {
                JsonObject countObj = objectFor(ctx.json(), "count_item", "count");
                String id = itemIdFrom(countObj, ctx.string("count_item", ""));
                int count = countInventoryItems(player, id);
                String key = countObj.has("store") ? countObj.get("store").getAsString()
                        : data.has("store") ? data.get("store").getAsString() : "inventory_count";
                if (!ctx.stack().isEmpty()) KoperStackData.setInt(ctx.stack(), key, count);
            }
            if (ctx.json().has("set_slot") || data.has("set_slot")) {
                JsonObject slot = objectFor(ctx.json(), "set_slot");
                int index = slot.has("slot") ? slot.get("slot").getAsInt() : data.has("slot") ? data.get("slot").getAsInt() : -1;
                String id = itemIdFrom(slot, data.has("item") ? data.get("item").getAsString() : "");
                Identifier iid = Identifier.tryParse(id);
                var item = iid != null ? BuiltInRegistries.ITEM.getValue(iid) : null;
                if (item != null && index >= 0 && index < player.getInventory().getContainerSize())
                    player.getInventory().setItem(index, new ItemStack(item, slot.has("count") ? slot.get("count").getAsInt() : 1));
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("block.set", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level) || ctx.ctx().pos() == null) return InteractionResult.PASS;
            String id = ctx.string("set_block", ctx.string("block", ""));
            Identifier bid = Identifier.tryParse(id);
            if (bid == null) return InteractionResult.PASS;
            var block = BuiltInRegistries.BLOCK.getValue(bid);
            if (block != null) level.setBlockAndUpdate(ctx.ctx().pos(), block.defaultBlockState());
            return InteractionResult.PASS;
        });
        KoperCalls.register("block.state", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level) || ctx.ctx().pos() == null) return InteractionResult.PASS;
            var state = level.getBlockState(ctx.ctx().pos());
            String prop = com.koper.koper_lib.factory.KoperBlockStates.propertyNameFromAction(ctx.json());
            String value = com.koper.koper_lib.factory.KoperBlockStates.propertyValueFromAction(ctx.json());
            if (!prop.isBlank() && !value.isBlank()) level.setBlockAndUpdate(ctx.ctx().pos(),
                com.koper.koper_lib.factory.KoperBlockStates.set(state, prop, value));
            return InteractionResult.PASS;
        });
        KoperCalls.register("block.toggle_state", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level) || ctx.ctx().pos() == null) return InteractionResult.PASS;
            String prop = com.koper.koper_lib.factory.KoperBlockStates.propertyNameFromAction(ctx.json());
            if (!prop.isBlank()) level.setBlockAndUpdate(ctx.ctx().pos(),
                com.koper.koper_lib.factory.KoperBlockStates.toggle(level.getBlockState(ctx.ctx().pos()), prop));
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.consume", ctx -> {
            if (!ctx.stack().isEmpty() && !ctx.bool("creative", false) && ctx.player() != null && !ctx.player().isCreative())
                ctx.stack().shrink(ctx.integer("consume_stack", 1));
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.damage", ctx -> {
            if (!ctx.stack().isEmpty() && ctx.player() != null)
                ctx.stack().hurtAndBreak(ctx.integer("damage_item", 1), ctx.player(), net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.stack_data", ctx -> {
            if (ctx.stack().isEmpty() || !ctx.json().has("stack_data") || !ctx.json().get("stack_data").isJsonObject()) return InteractionResult.PASS;
            for (var e : ctx.json().getAsJsonObject("stack_data").entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                    KoperStackData.setDouble(ctx.stack(), e.getKey(), e.getValue().getAsDouble());
                } else if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) {
                    KoperStackData.setBool(ctx.stack(), e.getKey(), e.getValue().getAsBoolean());
                } else {
                    KoperStackData.setString(ctx.stack(), e.getKey(), e.getValue().getAsString());
                }
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.stack", ctx -> {
            ItemStack stack = ctx.stack();
            if (stack.isEmpty()) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "item_stack");
            if (data.has("set_count")) stack.setCount(Math.max(0, data.get("set_count").getAsInt()));
            if (data.has("add_count")) stack.setCount(Math.max(0, stack.getCount() + data.get("add_count").getAsInt()));
            if (data.has("custom_name") || data.has("item_name")) {
                String name = data.has("custom_name") ? data.get("custom_name").getAsString() : data.get("item_name").getAsString();
                if (name.isBlank()) stack.remove(DataComponents.CUSTOM_NAME);
                else stack.set(DataComponents.CUSTOM_NAME, Component.literal(expand(name, ctx)));
            }
            if (data.has("clear_name") && data.get("clear_name").getAsBoolean()) stack.remove(DataComponents.CUSTOM_NAME);
            if (data.has("lore") || data.has("set_lore")) {
                JsonElement lore = data.has("set_lore") ? data.get("set_lore") : data.get("lore");
                stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(loreLines(lore, ctx)));
            }
            if (data.has("clear_lore") && data.get("clear_lore").getAsBoolean()) stack.remove(DataComponents.LORE);
            if (data.has("glint")) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, data.get("glint").getAsBoolean());
            if (data.has("clear_glint") && data.get("clear_glint").getAsBoolean()) stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
            if (data.has("custom_model_data")) {
                if (data.get("custom_model_data").isJsonNull()) stack.remove(DataComponents.CUSTOM_MODEL_DATA);
                else stack.set(DataComponents.CUSTOM_MODEL_DATA,
                        new net.minecraft.world.item.component.CustomModelData(
                                java.util.List.of(data.get("custom_model_data").getAsFloat()),
                                java.util.List.of(), java.util.List.of(), java.util.List.of()));
            }
            if (data.has("clear_custom_model_data") && data.get("clear_custom_model_data").getAsBoolean())
                stack.remove(DataComponents.CUSTOM_MODEL_DATA);
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.state", ctx -> {
            if (ctx.stack().isEmpty()) return InteractionResult.PASS;
            String stateName = ctx.string("state", ctx.string("item_state", ""));
            if (stateName.isBlank()) return InteractionResult.PASS;
            setItemState(ctx.stack(), stateName);
            int resetAfter = ctx.integer("reset_after", ctx.integer("reset_ticks", 0));
            String resetTo = ctx.string("reset_to", "");
            if (resetAfter > 0 && !resetTo.isBlank()) {
                var server = com.koper.koper_lib.scripting.UniversalScriptEngine.getCurrentServer();
                com.koper.koper_lib.core.KoperTasks.later(server, resetAfter, () -> setItemState(ctx.stack(), resetTo));
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("item.animation", ctx -> {
            if (ctx.stack().isEmpty()) return InteractionResult.PASS;
            String name = animationName(ctx.json());
            if (name.isBlank()) return InteractionResult.PASS;
            playItemAnimation(ctx.stack(), name, animationReset(ctx.json()));
            return InteractionResult.PASS;
        });
        KoperCalls.register("kui.open", ctx -> {
            ServerPlayer player = ctx.player();
            if (player != null) com.koper.koper_lib.kui.KuiOpen.open(player, ctx.string("open_gui", ctx.string("gui", ctx.string("id", ""))));
            return InteractionResult.PASS;
        });
        KoperCalls.register("xp.drop", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level) || ctx.entity() == null) return InteractionResult.PASS;
            int amount = ctx.integer("xp", ctx.integer("amount", 0));
            if (amount > 0) level.addFreshEntity(new ExperienceOrb(level, ctx.entity().getX(), ctx.entity().getY(), ctx.entity().getZ(), amount));
            return InteractionResult.PASS;
        });
        KoperCalls.register("message", ctx -> {
            if (ctx.player() != null) ctx.player().sendSystemMessage(Component.literal(expand(ctx.string("message", ""), ctx)));
            return InteractionResult.PASS;
        });
        KoperCalls.register("world.explosion", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "explosion", "explode");
            Vec3 pos = actionPos(ctx, data);
            float power = (float)dataNumber(data, "power", dataNumber(data, "strength", 2.0));
            boolean fire = data.has("fire") && data.get("fire").getAsBoolean();
            String mode = data.has("mode") ? data.get("mode").getAsString()
                : data.has("interaction") ? data.get("interaction").getAsString() : "block";
            level.explode(null, pos.x, pos.y, pos.z, power, fire, explosionMode(mode));
            return InteractionResult.PASS;
        });
        KoperCalls.register("world.particle", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "particles", "particle");
            String id = data.has("id") ? data.get("id").getAsString()
                : data.has("particle") && data.get("particle").isJsonPrimitive() ? data.get("particle").getAsString()
                : ctx.string("particle", "");
            Identifier pid = Identifier.tryParse(id);
            if (pid == null) return InteractionResult.PASS;
            var particle = BuiltInRegistries.PARTICLE_TYPE.getValue(pid);
            if (!(particle instanceof SimpleParticleType simple)) return InteractionResult.PASS;
            Vec3 pos = actionPos(ctx, data);
            double spread = dataNumber(data, "spread", 0.25);
            int count = (int)dataNumber(data, "count", 8);
            double speed = dataNumber(data, "speed", 0.0);
            level.sendParticles(simple, pos.x, pos.y, pos.z, count,
                    dataNumber(data, "dx", spread), dataNumber(data, "dy", spread), dataNumber(data, "dz", spread), speed);
            return InteractionResult.PASS;
        });
        KoperCalls.register("entity.spawn", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "spawn_entity", "summon_entity", "summon");
            String id = data.has("id") ? data.get("id").getAsString()
                    : data.has("entity") ? data.get("entity").getAsString()
                    : ctx.string("spawn_entity", ctx.string("summon_entity", ctx.string("summon", "")));
            Identifier eid = Identifier.tryParse(id);
            if (eid == null) return InteractionResult.PASS;
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(eid);
            if (type == null) return InteractionResult.PASS;
            Vec3 pos = actionPos(ctx, data);
            int count = Math.max(1, Math.min((int)dataNumber(data, "count", 1), 128));
            double spread = Math.max(0.0, dataNumber(data, "spread", 0.0));
            for (int i = 0; i < count; i++) {
                Vec3 spawnPos = spread > 0.0
                        ? pos.add((Math.random() - 0.5) * spread, 0.0, (Math.random() - 0.5) * spread)
                        : pos;
                type.spawn(level, null, null, BlockPos.containing(spawnPos), EntitySpawnReason.COMMAND, true, false);
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("projectile.shoot", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "shoot_projectile", "fire_projectile", "projectile");
            String id = data.has("id") ? data.get("id").getAsString()
                    : data.has("entity") ? data.get("entity").getAsString()
                    : ctx.string("shoot_projectile", ctx.string("fire_projectile", ctx.string("projectile", "minecraft:arrow")));
            Identifier eid = Identifier.tryParse(id);
            if (eid == null) return InteractionResult.PASS;
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(eid);
            if (type == null) return InteractionResult.PASS;
            Entity owner = ctx.entity() != null ? ctx.entity() : ctx.player();
            Entity spawned = type.create(level, EntitySpawnReason.COMMAND);
            if (spawned == null) return InteractionResult.PASS;

            Vec3 start = projectileStart(ctx, data, owner);
            Vec3 dir = projectileDirection(ctx, data, owner);
            double speed = dataNumber(data, "speed", dataNumber(data, "velocity", 1.5));
            double divergence = dataNumber(data, "divergence", dataNumber(data, "inaccuracy", 1.0));
            spawned.setPos(start.x, start.y, start.z);
            if (spawned instanceof Projectile projectile) {
                if (owner != null) projectile.setOwner(owner);
                projectile.shoot(dir.x, dir.y, dir.z, (float)speed, (float)divergence);
                if (projectile instanceof AbstractArrow arrow) {
                    if (data.has("damage")) arrow.setBaseDamage(data.get("damage").getAsDouble());
                    if (data.has("pickup") && !data.get("pickup").getAsBoolean()) {
                        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                    }
                }
            } else {
                spawned.setDeltaMovement(dir.normalize().scale(speed));
                markVelocity(spawned);
            }
            level.addFreshEntity(spawned);
            return InteractionResult.PASS;
        });
        KoperCalls.register("world.lightning", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "lightning", "strike_lightning");
            Vec3 pos = actionPos(ctx, data);
            try {
                String cmd = String.format(java.util.Locale.ROOT,
                        "summon minecraft:lightning_bolt %.3f %.3f %.3f", pos.x, pos.y, pos.z);
                level.getServer().getCommands().getDispatcher().execute(cmd, level.getServer().createCommandSourceStack());
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[KoperAction] lightning failed: {}", e.getMessage());
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("world.falling_block", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "falling_block");
            String id = data.has("block") ? data.get("block").getAsString()
                    : data.has("id") ? data.get("id").getAsString()
                    : ctx.string("falling_block", "");
            Block block = blockById(id);
            if (block == null) return InteractionResult.PASS;
            Vec3 pos = actionPos(ctx, data);
            FallingBlockEntity.fall(level, BlockPos.containing(pos), block.defaultBlockState());
            return InteractionResult.PASS;
        });
        KoperCalls.register("world.fill", ctx -> {
            if (ctx.ctx() == null || !(ctx.ctx().world() instanceof ServerLevel level)) return InteractionResult.PASS;
            JsonObject data = objectFor(ctx.json(), "fill", "fill_block");
            String id = data.has("block") ? data.get("block").getAsString()
                    : data.has("id") ? data.get("id").getAsString()
                    : ctx.string("fill_block", "");
            Block block = blockById(id);
            if (block == null) return InteractionResult.PASS;
            BlockPos from = blockPos(data.has("from") ? data.get("from") : null, BlockPos.containing(actionPos(ctx, data)));
            BlockPos to = blockPos(data.has("to") ? data.get("to") : null, from);
            int limit = Math.max(1, Math.min((int)dataNumber(data, "limit", 4096), 65536));
            int count = 0;
            for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()) && count < limit; x++) {
                for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()) && count < limit; y++) {
                    for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()) && count < limit; z++, count++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), block.defaultBlockState());
                    }
                }
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("world.weather", ctx -> {
            String weather = ctx.string("weather", ctx.string("type", ""));
            if (weather.isBlank()) return InteractionResult.PASS;
            var server = com.koper.koper_lib.scripting.UniversalScriptEngine.getCurrentServer();
            if (server == null) return InteractionResult.PASS;
            try {
                server.getCommands().getDispatcher().execute("weather " + weather, server.createCommandSourceStack());
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[KoperAction] weather '{}' failed: {}", weather, e.getMessage());
            }
            return InteractionResult.PASS;
        });
        KoperCalls.register("world.time", ctx -> {
            long time = ctx.json().has("day_time") ? ctx.json().get("day_time").getAsLong()
                    : ctx.json().has("time") ? ctx.json().get("time").getAsLong() : -1L;
            var server = com.koper.koper_lib.scripting.UniversalScriptEngine.getCurrentServer();
            if (time >= 0 && server != null) {
                try {
                    server.getCommands().getDispatcher().execute("time set " + time, server.createCommandSourceStack());
                } catch (Exception e) {
                    KoperLib.LOGGER.warn("[KoperAction] time set '{}' failed: {}", time, e.getMessage());
                }
            }
            return InteractionResult.PASS;
        });
    }

    private static void applyEffects(JsonElement effects, LivingEntity target) {
        if (effects == null || effects.isJsonNull() || target == null) return;
        if (effects.isJsonArray()) {
            JsonArray arr = effects.getAsJsonArray();
            for (JsonElement el : arr) applyEffects(el, target);
            return;
        }
        JsonObject obj = effects.isJsonObject() ? effects.getAsJsonObject() : new JsonObject();
        if (!effects.isJsonObject()) obj.addProperty("id", effects.getAsString());
        String id = obj.has("id") ? obj.get("id").getAsString() : "";
        Identifier eid = Identifier.tryParse(id);
        if (eid == null) return;
        var effect = BuiltInRegistries.MOB_EFFECT.getValue(eid);
        if (effect == null) return;
        float chance = obj.has("chance") ? obj.get("chance").getAsFloat() : 1.0f;
        if (Math.random() > chance) return;
        target.addEffect(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect),
            obj.has("ticks") ? obj.get("ticks").getAsInt() : 100,
            obj.has("amp") ? obj.get("amp").getAsInt() : obj.has("amplifier") ? obj.get("amplifier").getAsInt() : 0));
    }

    private static LivingEntity livingTarget(KoperCallContext ctx, boolean preferHitTarget) {
        Entity entity = entityTarget(ctx, preferHitTarget);
        return entity instanceof LivingEntity living ? living : null;
    }

    private static Entity entityTarget(KoperCallContext ctx, boolean preferHitTarget) {
        String target = ctx.string("target", "");
        if (target.equalsIgnoreCase("player")) return ctx.player();
        if (target.equalsIgnoreCase("hit") || target.equalsIgnoreCase("target")) return ctx.target();
        if (target.equalsIgnoreCase("self") || target.equalsIgnoreCase("entity")) return ctx.entity();
        if (preferHitTarget && ctx.target() != null) return ctx.target();
        if (ctx.entity() != null) return ctx.entity();
        return ctx.player();
    }

    private static JsonElement firstPresent(JsonObject json, String... keys) {
        if (json == null) return null;
        for (String key : keys) if (json.has(key)) return json.get(key);
        return null;
    }

    private static JsonObject objectFor(JsonObject json, String... keys) {
        JsonElement el = firstPresent(json, keys);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : json;
    }

    private static Vec3 vector(JsonElement el, double fallbackX, double fallbackY, double fallbackZ) {
        if (el != null && el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            return new Vec3(
                arr.size() > 0 ? arr.get(0).getAsDouble() : fallbackX,
                arr.size() > 1 ? arr.get(1).getAsDouble() : fallbackY,
                arr.size() > 2 ? arr.get(2).getAsDouble() : fallbackZ);
        }
        JsonObject o = el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        return new Vec3(
            dataNumber(o, "x", fallbackX),
            dataNumber(o, "y", fallbackY),
            dataNumber(o, "z", fallbackZ));
    }

    private static double dataNumber(JsonObject data, String key, double fallback) {
        return data != null && data.has(key) ? data.get(key).getAsDouble() : fallback;
    }

    private static java.util.List<Component> loreLines(JsonElement lore, KoperCallContext ctx) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        if (lore == null || lore.isJsonNull()) return lines;
        if (lore.isJsonArray()) {
            for (JsonElement el : lore.getAsJsonArray()) {
                if (!el.isJsonNull()) lines.add(Component.literal(expand(el.getAsString(), ctx)));
            }
            return lines;
        }
        lines.add(Component.literal(expand(lore.getAsString(), ctx)));
        return lines;
    }

    private static String itemIdFrom(JsonObject data, String fallback) {
        if (data == null) return fallback == null ? "" : fallback;
        if (data.has("item")) return data.get("item").getAsString();
        if (data.has("id")) return data.get("id").getAsString();
        return fallback == null ? "" : fallback;
    }

    private static int countInventoryItems(ServerPlayer player, String itemId) {
        if (player == null || itemId == null || itemId.isBlank()) return 0;
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) count += stack.getCount();
        }
        return count;
    }

    private static int removeInventoryItems(ServerPlayer player, String itemId, int count, boolean drop) {
        if (player == null || itemId == null || itemId.isBlank() || count <= 0) return 0;
        int removed = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && removed < count; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) continue;
            int take = Math.min(stack.getCount(), count - removed);
            ItemStack out = stack.copyWithCount(take);
            stack.shrink(take);
            if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            if (drop) com.koper.koper_lib.core.KoperWyrzucacz.drop(player, out, true);
            removed += take;
        }
        return removed;
    }

    private static Vec3 actionPos(KoperCallContext ctx, JsonObject data) {
        if (data != null && (data.has("x") || data.has("y") || data.has("z"))) {
            return vector(data, 0.0, 0.0, 0.0);
        }
        if (ctx.ctx() != null && ctx.ctx().pos() != null) {
            var pos = ctx.ctx().pos();
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        if (ctx.entity() != null) return ctx.entity().position();
        if (ctx.player() != null) return ctx.player().position();
        return Vec3.ZERO;
    }

    private static Vec3 projectileStart(KoperCallContext ctx, JsonObject data, Entity owner) {
        if (data != null && data.has("from")) return vector(data.get("from"), 0.0, 0.0, 0.0);
        if (data != null && (data.has("x") || data.has("y") || data.has("z"))) return vector(data, 0.0, 0.0, 0.0);
        if (owner != null) {
            Vec3 look = owner.getLookAngle();
            return owner.getEyePosition().add(look.scale(dataNumber(data, "forward", 0.6)));
        }
        return actionPos(ctx, data);
    }

    private static Vec3 projectileDirection(KoperCallContext ctx, JsonObject data, Entity owner) {
        if (data != null && data.has("direction")) {
            Vec3 dir = vector(data.get("direction"), 0.0, 0.0, 1.0);
            return dir.lengthSqr() > 0.0001 ? dir.normalize() : new Vec3(0.0, 0.0, 1.0);
        }
        if (data != null && data.has("to")) {
            Vec3 start = projectileStart(ctx, data, owner);
            Vec3 to = vector(data.get("to"), start.x, start.y, start.z + 1.0);
            Vec3 dir = to.subtract(start);
            return dir.lengthSqr() > 0.0001 ? dir.normalize() : new Vec3(0.0, 0.0, 1.0);
        }
        if (data != null && data.has("towards_target") && data.get("towards_target").getAsBoolean() && ctx.target() != null) {
            Vec3 start = projectileStart(ctx, data, owner);
            Vec3 aim = ctx.target().position().add(0.0, ctx.target().getBbHeight() * 0.5, 0.0);
            Vec3 dir = aim.subtract(start);
            return dir.lengthSqr() > 0.0001 ? dir.normalize() : new Vec3(0.0, 0.0, 1.0);
        }
        if (owner != null) return owner.getLookAngle().normalize();
        return new Vec3(0.0, 0.0, 1.0);
    }

    private static BlockPos blockPos(JsonElement el, BlockPos fallback) {
        if (el != null && el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            return new BlockPos(
                    arr.size() > 0 ? arr.get(0).getAsInt() : fallback.getX(),
                    arr.size() > 1 ? arr.get(1).getAsInt() : fallback.getY(),
                    arr.size() > 2 ? arr.get(2).getAsInt() : fallback.getZ());
        }
        if (el != null && el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            return new BlockPos(
                    o.has("x") ? o.get("x").getAsInt() : fallback.getX(),
                    o.has("y") ? o.get("y").getAsInt() : fallback.getY(),
                    o.has("z") ? o.get("z").getAsInt() : fallback.getZ());
        }
        return fallback;
    }

    private static Block blockById(String id) {
        Identifier bid = Identifier.tryParse(id == null ? "" : id);
        return bid != null ? BuiltInRegistries.BLOCK.getValue(bid) : null;
    }

    private static net.minecraft.world.level.Level.ExplosionInteraction explosionMode(String raw) {
        if (raw == null) return net.minecraft.world.level.Level.ExplosionInteraction.BLOCK;
        return switch (raw.toLowerCase()) {
            case "none", "keep", "no_blocks", "mob" -> net.minecraft.world.level.Level.ExplosionInteraction.MOB;
            case "tnt" -> net.minecraft.world.level.Level.ExplosionInteraction.TNT;
            default -> net.minecraft.world.level.Level.ExplosionInteraction.BLOCK;
        };
    }

    private static void markVelocity(Entity entity) {
        if (entity instanceof com.koper.koper_lib.mixin.EntityAccessor acc) acc.setVelocityModified(true);
    }

    private static Identifier normalizeAttributeId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase();
        if (!s.contains(":")) s = "minecraft:" + s;
        int colon = s.indexOf(':');
        String ns = s.substring(0, colon);
        String path = s.substring(colon + 1);
        if (path.startsWith("generic.")) path = path.substring("generic.".length());
        path = path.replace('.', '_');
        return Identifier.tryParse(ns + ":" + path);
    }

    private static String expand(String raw, KoperCallContext ctx) {
        if (raw == null) return "";
        String player = ctx.player() != null ? ctx.player().getScoreboardName() : "";
        String owner = ctx.ownerId() != null ? ctx.ownerId() : "";
        String target = ctx.target() != null ? ctx.target().getDisplayName().getString() : "";
        return raw.replace("{player}", player).replace("{owner}", owner).replace("{target}", target);
    }

    public static void setItemState(ItemStack stack, String stateName) {
        if (stack == null || stack.isEmpty() || stateName == null || stateName.isBlank()) return;
        KoperStackData.setString(stack, "state", stateName);
        var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        var data = com.koper.koper_lib.loader.ContentRegistry.getItemData(itemId);
        if (data != null && data.itemStates.containsKey(stateName)) {
            int cmd = data.itemStates.get(stateName).customModelData;
            stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new net.minecraft.world.item.component.CustomModelData(
                    java.util.List.of((float)cmd), java.util.List.of(), java.util.List.of(), java.util.List.of()));
        } else if (data != null && stateName.equals(data.defaultState)) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        }
    }

    public static void playItemAnimation(ItemStack stack, String animationName) {
        playItemAnimation(stack, animationName, "");
    }

    public static void playItemAnimation(ItemStack stack, String animationName, String resetOverride) {
        var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        var data = com.koper.koper_lib.loader.ContentRegistry.getItemData(itemId);
        if (data == null || data.itemAnimations == null) return;
        var anim = data.itemAnimations.get(animationName);
        if (anim == null || anim.frames.isEmpty()) return;
        if (!anim.interrupt && !KoperStackData.getString(stack, "animation", "").isBlank()) return;

        int token = (int)(System.nanoTime() & 0x7FFFFFFF);
        KoperStackData.setString(stack, "animation", animationName);
        KoperStackData.setInt(stack, "animation_token", token);

        var first = anim.frames.get(0);
        setItemState(stack, first.state);
        int at = Math.max(1, first.ticks);
        var server = com.koper.koper_lib.scripting.UniversalScriptEngine.getCurrentServer();
        for (int i = 1; i < anim.frames.size(); i++) {
            var frame = anim.frames.get(i);
            int tick = at;
            com.koper.koper_lib.core.KoperTasks.later(server, tick, () -> {
                if (KoperStackData.getInt(stack, "animation_token", -1) == token) setItemState(stack, frame.state);
            });
            at += Math.max(1, frame.ticks);
        }

        String resetTo = resetOverride != null && !resetOverride.isBlank()
                ? resetOverride
                : anim.resetTo != null && !anim.resetTo.isBlank() ? anim.resetTo : data.defaultState;
        if (resetTo != null && !resetTo.isBlank()) {
            int endTick = at;
            com.koper.koper_lib.core.KoperTasks.later(server, endTick, () -> {
                if (KoperStackData.getInt(stack, "animation_token", -1) != token) return;
                setItemState(stack, resetTo);
                KoperStackData.remove(stack, "animation");
                KoperStackData.remove(stack, "animation_token");
            });
        }
    }

    private static String animationName(JsonObject json) {
        if (json == null) return "";
        for (String key : new String[]{"animation", "play_animation", "item_animation"}) {
            if (!json.has(key)) continue;
            var el = json.get(key);
            if (el.isJsonPrimitive()) return el.getAsString();
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("name")) return o.get("name").getAsString();
                if (o.has("id")) return o.get("id").getAsString();
            }
        }
        return "";
    }

    private static String animationReset(JsonObject json) {
        if (json == null) return "";
        if (json.has("reset_to")) return json.get("reset_to").getAsString();
        for (String key : new String[]{"animation", "play_animation", "item_animation"}) {
            if (json.has(key) && json.get(key).isJsonObject()) {
                JsonObject o = json.getAsJsonObject(key);
                if (o.has("reset_to")) return o.get("reset_to").getAsString();
            }
        }
        return "";
    }
}
