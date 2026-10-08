package com.koper.koper_lib.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.api.LuaModuleContext;
import com.koper.koper_lib.scripting.LuaAddonRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

// the reads scripts never had. these used to be rust stubs returning air and empty tables;
// they go through the same synchronous query bridge pstate uses, so a read is a real read.
//
// registering a module named "world" or "math" makes the generated lua overwrite whatever
// rust put there, which is how get_block stops lying without touching the engine.
public final class KoperPeekLua {

    private KoperPeekLua() {}

    // the query buffer on the rust side is 8k, so a fat entity list would be truncated mid json
    private static final int MAX_ENTITIES = 64;

    public static void register() {
        LuaAddonRegistry.register("world", m -> m
            .function("get_block", KoperPeekLua::getBlock)
            .function("get_block_state", KoperPeekLua::getBlockState)
            .function("is_air", KoperPeekLua::isAir)
            .function("entities_raw", KoperPeekLua::entitiesRaw)
        );
        LuaAddonRegistry.register("math", m -> m
            .function("raycast", KoperPeekLua::raycast)
        );
        LuaAddonRegistry.register("mob", m -> m
            .function("has_effect", KoperPeekLua::hasEffect)
            .function("has_tag", KoperPeekLua::hasTag)
            .function("target", KoperPeekLua::target)
            .function("held", KoperPeekLua::held)
            .function("inventory", KoperPeekLua::inventory)
            .function("by_uuid", KoperPeekLua::byUuid)
            .function("bones", KoperPeekLua::bones)
        );
    }

    // ── world ─────────────────────────────────────────────────────────────────

    private static JsonElement getBlock(LuaModuleContext ctx, JsonArray a) {
        ServerLevel level = level(ctx, a, 3);
        BlockPos pos = pos(a, 0);
        if (level == null || pos == null) return JsonNull.INSTANCE;

        BlockState state = level.getBlockState(pos);
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        JsonObject out = new JsonObject();
        out.addProperty("id", id != null ? id.toString() : "minecraft:air");
        out.addProperty("air", state.isAir());
        out.addProperty("solid", state.isSolidRender());
        out.addProperty("hardness", state.getDestroySpeed(level, pos));
        out.addProperty("light", state.getLightEmission());
        out.addProperty("x", pos.getX());
        out.addProperty("y", pos.getY());
        out.addProperty("z", pos.getZ());
        return out;
    }

    // blockstate properties as a flat table, so a script can read facing/lit/whatever
    private static JsonElement getBlockState(LuaModuleContext ctx, JsonArray a) {
        ServerLevel level = level(ctx, a, 3);
        BlockPos pos = pos(a, 0);
        if (level == null || pos == null) return JsonNull.INSTANCE;

        JsonObject out = new JsonObject();
        BlockState state = level.getBlockState(pos);
        for (var prop : state.getProperties()) {
            out.addProperty(prop.getName(), stringify(state, prop));
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String stringify(BlockState state, net.minecraft.world.level.block.state.properties.Property prop) {
        return prop.getName(state.getValue(prop));
    }

    private static JsonElement isAir(LuaModuleContext ctx, JsonArray a) {
        ServerLevel level = level(ctx, a, 3);
        BlockPos pos = pos(a, 0);
        return new JsonPrimitive(level == null || pos == null || level.getBlockState(pos).isAir());
    }

    // raw data only — rust wraps these back into proper entity objects
    private static JsonElement entitiesRaw(LuaModuleContext ctx, JsonArray a) {
        JsonArray out = new JsonArray();
        ServerLevel level = level(ctx, a, 4);
        if (level == null) return out;

        final double x = num(a, 0, 0), y = num(a, 1, 64), z = num(a, 2, 0);
        double asked = num(a, 3, 8);
        if (asked <= 0) return out;
        // a 512 block sweep would stall the tick, and nobody meant to ask for that
        final double r = Math.min(asked, 128);

        AABB box = new AABB(x - r, y - r, z - r, x + r, y + r, z + r);
        List<Entity> found = level.getEntities((Entity) null, box, e -> e.isAlive() && e.distanceToSqr(x, y, z) <= r * r);

        int n = 0;
        for (Entity e : found) {
            if (n++ >= MAX_ENTITIES) break;
            out.add(describe(e));
        }
        return out;
    }

    private static JsonObject describe(Entity e) {
        JsonObject o = new JsonObject();
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        o.addProperty("id", type != null ? type.toString() : "minecraft:pig");
        o.addProperty("uuid", e.getUUID().toString());
        o.addProperty("name", e.getName().getString());
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        o.addProperty("player", e instanceof net.minecraft.server.level.ServerPlayer);
        if (e instanceof LivingEntity le) {
            o.addProperty("health", le.getHealth());
            o.addProperty("max_health", le.getMaxHealth());
        } else {
            o.addProperty("health", 0);
            o.addProperty("max_health", 0);
        }
        return o;
    }

    // ── raycast ───────────────────────────────────────────────────────────────

    // raycast({x=,y=,z=, dx=,dy=,dz=, range=, fluids=}) or raycast(entity, range)
    private static JsonElement raycast(LuaModuleContext ctx, JsonArray a) {
        JsonObject miss = new JsonObject();
        miss.addProperty("hit", false);

        MinecraftServer server = ctx.server();
        if (server == null) return miss;

        JsonElement first = at(a, 0);
        Vec3 from, dir;
        double range;
        ServerLevel level;

        LivingEntity shooter = ctx.entity(first);
        if (shooter != null) {
            level = (ServerLevel) shooter.level();
            from = shooter.getEyePosition();
            dir = shooter.getLookAngle();
            range = num(a, 1, 32);
        } else if (first != null && first.isJsonObject()) {
            JsonObject o = first.getAsJsonObject();
            level = level(ctx, a, 1);
            if (level == null) return miss;
            from = new Vec3(d(o, "x", 0), d(o, "y", 64), d(o, "z", 0));
            dir = new Vec3(d(o, "dx", 0), d(o, "dy", 0), d(o, "dz", 1)).normalize();
            range = d(o, "range", 32);
        } else {
            return miss;
        }

        range = Math.max(0.1, Math.min(range, 256));
        Vec3 to = from.add(dir.scale(range));

        BlockHitResult hit = level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));

        if (hit.getType() == HitResult.Type.MISS) return miss;

        BlockPos pos = hit.getBlockPos();
        Identifier id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        JsonObject out = new JsonObject();
        out.addProperty("hit", true);
        out.addProperty("block", id != null ? id.toString() : "minecraft:air");
        out.addProperty("x", pos.getX());
        out.addProperty("y", pos.getY());
        out.addProperty("z", pos.getZ());
        out.addProperty("face", hit.getDirection().getName());
        out.addProperty("hit_x", hit.getLocation().x);
        out.addProperty("hit_y", hit.getLocation().y);
        out.addProperty("hit_z", hit.getLocation().z);
        out.addProperty("distance", from.distanceTo(hit.getLocation()));
        return out;
    }

    // ── entity queries ────────────────────────────────────────────────────────

    private static JsonElement hasEffect(LuaModuleContext ctx, JsonArray a) {
        LivingEntity e = ctx.entity(at(a, 0));
        if (e == null) return new JsonPrimitive(false);
        Identifier id = Identifier.tryParse(str(a, 1));
        if (id == null) return new JsonPrimitive(false);
        var holder = BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);
        return new JsonPrimitive(holder != null && e.hasEffect(holder));
    }

    private static JsonElement hasTag(LuaModuleContext ctx, JsonArray a) {
        LivingEntity e = ctx.entity(at(a, 0));
        return new JsonPrimitive(e != null && e.entityTags().contains(str(a, 1)));
    }

    private static JsonElement target(LuaModuleContext ctx, JsonArray a) {
        LivingEntity e = ctx.entity(at(a, 0));
        if (!(e instanceof net.minecraft.world.entity.Mob mob)) return JsonNull.INSTANCE;
        LivingEntity t = mob.getTarget();
        return t == null ? JsonNull.INSTANCE : describe(t);
    }

    // held(entity, "main"|"off")
    private static JsonElement held(LuaModuleContext ctx, JsonArray a) {
        LivingEntity e = ctx.entity(at(a, 0));
        if (e == null) return JsonNull.INSTANCE;
        boolean off = "off".equalsIgnoreCase(str(a, 1));
        return stackJson(off ? e.getOffhandItem() : e.getMainHandItem());
    }

    private static JsonElement inventory(LuaModuleContext ctx, JsonArray a) {
        JsonArray out = new JsonArray();
        if (!(ctx.entity(at(a, 0)) instanceof net.minecraft.server.level.ServerPlayer p)) return out;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            JsonObject o = stackJson(s);
            o.addProperty("slot", i);
            out.add(o);
        }
        return out;
    }

    private static JsonElement byUuid(LuaModuleContext ctx, JsonArray a) {
        LivingEntity e = ctx.entity(at(a, 0));
        return e == null ? JsonNull.INSTANCE : describe(e);
    }

    // bone transforms are baked client side by the renderer, so the server can only report
    // which bones the bound model has. enough to drive logic off, not a live pose.
    private static JsonElement bones(LuaModuleContext ctx, JsonArray a) {
        JsonArray out = new JsonArray();
        LivingEntity e = ctx.entity(at(a, 0));
        if (e == null) return out;
        for (String name : com.koper.koper_lib.api.FullpackAddons.entityBones(e)) out.add(name);
        return out;
    }

    private static JsonObject stackJson(ItemStack s) {
        JsonObject o = new JsonObject();
        Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
        o.addProperty("id", id != null ? id.toString() : "minecraft:air");
        o.addProperty("count", s.getCount());
        o.addProperty("durability", s.getMaxDamage() - s.getDamageValue());
        o.addProperty("max_durability", s.getMaxDamage());
        o.addProperty("empty", s.isEmpty());
        return o;
    }

    // ── arg plumbing ──────────────────────────────────────────────────────────

    // level from an explicit dimension arg, the calling player's world, or the overworld
    private static ServerLevel level(LuaModuleContext ctx, JsonArray a, int dimIndex) {
        MinecraftServer server = ctx.server();
        if (server == null) return null;

        JsonElement el = at(a, dimIndex);
        if (el != null && el.isJsonPrimitive()) {
            Identifier want = Identifier.tryParse(el.getAsString());
            if (want != null)
                for (ServerLevel l : server.getAllLevels())
                    if (l.dimension().identifier().equals(want)) return l;
        }
        ServerLevel guess = ctx.level(null);
        return guess != null ? guess : server.overworld();
    }

    private static BlockPos pos(JsonArray a, int i) {
        JsonElement e = at(a, i);
        if (e == null) return null;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("x") || !o.has("y") || !o.has("z")) return null;
            return BlockPos.containing(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
        }
        return BlockPos.containing(num(a, i, 0), num(a, i + 1, 64), num(a, i + 2, 0));
    }

    private static double d(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    private static double num(JsonArray a, int i, double def) {
        JsonElement e = at(a, i);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
    }

    private static String str(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonElement at(JsonArray a, int i) {
        return a != null && i < a.size() ? a.get(i) : null;
    }
}
