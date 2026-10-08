package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.KoperLib;
import com.mojang.brigadier.StringReader;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

// the other end of __kq. js asks {"op": "...", ...}, we answer with json. anything that throws
// here goes back as {"err": "..."} and becomes a real js exception on the script side
public final class BedrockPytajnik {

    private static byte[] schowek;
    private static final Map<String, Long> LAST_SHOUT = new HashMap<>();

    private BedrockPytajnik() {}

    public static int dispatchQuery(MemorySegment in, int inLen, MemorySegment out, int outLen) {
        byte[] ans;
        if (inLen < 0) {
            ans = schowek;
            schowek = null;
            if (ans == null) return -1;
        } else {
            JsonElement res;
            try {
                String text = new String(in.reinterpret(inLen).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                res = handle(JsonParser.parseString(text).getAsJsonObject());
            } catch (Oops o) {
                res = err(o.getMessage());
            } catch (Throwable t) {
                KoperLib.LOGGER.debug("[Bedrock] query blew up", t);
                res = err(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            ans = (res == null ? "null" : res.toString()).getBytes(StandardCharsets.UTF_8);
        }
        if (ans.length > outLen) {
            schowek = ans;
            return ans.length;
        }
        MemorySegment.copy(ans, 0, out.reinterpret(outLen), ValueLayout.JAVA_BYTE, 0, ans.length);
        return ans.length;
    }

    // thrown on purpose, message goes to the script as is
    static final class Oops extends RuntimeException {
        Oops(String m) { super(m, null, false, false); }
    }

    private static JsonObject err(String m) {
        JsonObject o = new JsonObject();
        o.addProperty("err", m == null ? "error" : m);
        return o;
    }

    private static MinecraftServer srv() {
        MinecraftServer s = BedrockSkrypciarz.server();
        if (s == null) throw new Oops("no world is running");
        return s;
    }

    // ── the big switch ───────────────────────────────────────────────────────

    static JsonElement handle(JsonObject q) {
        String op = q.get("op").getAsString();
        return switch (op) {
            case "log" -> log(q);
            case "sub" -> {
                var a = BedrockSkrypciarz.teraz();
                if (a != null) BedrockSkrypciarz.subscribe(a, s(q, "n"), q.get("b").getAsBoolean(), q.get("on").getAsBoolean());
                yield null;
            }
            case "players" -> players(q);
            case "ents" -> ents(q);
            case "ent.ref" -> { Entity e = find(s(q, "e")); yield e == null ? null : ref(e); }
            // every block the feet box rests on, a mob on the seam of four blocks stands on four
            case "ent.onAll" -> {
                Entity e = needEnt(q);
                JsonArray out = new JsonArray();
                if (!e.onGround()) yield out;
                var bb = e.getBoundingBox();
                var feet = new net.minecraft.world.phys.AABB(bb.minX, bb.minY - 0.05, bb.minZ, bb.maxX, bb.minY + 0.01, bb.maxZ);
                for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(feet.minX, feet.minY, feet.minZ),
                        BlockPos.containing(feet.maxX - 1e-7, feet.maxY, feet.maxZ - 1e-7))) {
                    var shape = e.level().getBlockState(p).getCollisionShape(e.level(), p);
                    if (shape.isEmpty() || !shape.bounds().move(p).intersects(feet)) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("dim", dimName(e.level()));
                    o.addProperty("x", p.getX()); o.addProperty("y", p.getY()); o.addProperty("z", p.getZ());
                    out.add(o);
                }
                yield out;
            }
            // the block under the feet the way java's own movement picks it: fences, edges, beds
            case "ent.on" -> {
                Entity e = needEnt(q);
                if (!e.onGround()) yield null;
                BlockPos on = e.getOnPos();
                JsonObject o = new JsonObject();
                o.addProperty("dim", dimName(e.level()));
                o.addProperty("x", on.getX()); o.addProperty("y", on.getY()); o.addProperty("z", on.getZ());
                yield o;
            }
            case "ent" -> snap(s(q, "e"));
            case "ent.set" -> entSet(q);
            case "ent.tp" -> tp(q);
            case "ent.dmg" -> dmg(q);
            case "ent.fx+" -> fxAdd(q);
            case "ent.fx-" -> fxRemove(q);
            case "ent.fx" -> fxList(q);
            case "ent.attr" -> attr(q);
            case "ent.match" -> new JsonPrimitive(matches(needEnt(q), q.getAsJsonObject("q")));
            case "ent.msg" -> { player(q).sendSystemMessage(comp(q.get("m"))); yield null; }
            case "ent.title" -> title(q);
            case "ent.sound" -> sound(q, true);
            case "ent.cool" -> cooldown(q);
            case "ent.spawn" -> null;
            case "ent.event" -> {
                Entity e = needEnt(q);
                if (BedrockZachowanie.ma(e)) BedrockZachowanie.event(e, s(q, "ev"), null);
                else once("triggerEvent('" + s(q, "ev") + "') on " + typeId(e) + ", which has no behavior pack definition");
                yield null;
            }
            case "ent.anim" -> anim(q);
            // ProjectileComponent.shoot(velocity, {owner}): java projectiles through Projectile.shoot,
            // a pack's projectile (an addon mob, BedrockPocisk flies it) gets the velocity and its owner
            case "ent.shoot" -> {
                Entity e = needEnt(q);
                Entity owner = q.has("owner") && !q.get("owner").isJsonNull() ? find(s(q, "owner")) : null;
                if (q.has("ownerOnly")) {
                    if (e instanceof net.minecraft.world.entity.projectile.Projectile p) p.setOwner(owner);
                    else if (owner != null) BedrockPocisk.wlasciciel(e, owner);
                    yield null;
                }
                double x = d(q, "x"), y = d(q, "y"), z = d(q, "z");
                if (x == 0 && y == 0 && z == 0) yield null;
                if (e instanceof net.minecraft.world.entity.projectile.Projectile p) {
                    if (owner != null) p.setOwner(owner);
                    p.shoot(x, y, z, (float) Math.sqrt(x * x + y * y + z * z), 0f);
                } else {
                    if (owner != null) BedrockPocisk.wlasciciel(e, owner);
                    e.setDeltaMovement(x, y, z);
                    BedrockZachowanie.wystrzel(e);
                }
                yield null;
            }
            case "ents.ray" -> entsRay(q);
            case "inv" -> inv(q);
            case "eq" -> eq(q);
            case "dyn" -> BedrockSkrzynka.handle(srv(), BedrockSkrypciarz.teraz(), q);
            case "cmd" -> cmd(q);
            case "select" -> select(q);
            case "world" -> world(q);
            case "ticking" -> BedrockStrefy.handle(q);
            case "camera" -> BedrockKamerzysta.handle(q);
            case "world.msg" -> { srv().getPlayerList().broadcastSystemMessage(comp(q.get("m")), false); yield null; }
            case "dim.height" -> { ServerLevel l = level(s(q, "dim")); JsonObject o = new JsonObject(); o.addProperty("min", l.getMinY()); o.addProperty("max", l.getMaxY() + 1); yield o; }
            case "dim.loaded" -> new JsonPrimitive(level(s(q, "dim")).isLoaded(BlockPos.containing(d(q, "x"), 64, d(q, "z"))));
            case "dim.light" -> dimLight(q);
            case "dim.biome" -> { var b = level(s(q, "dim")).getBiome(pos(q)).unwrapKey(); yield b.isPresent() ? new JsonPrimitive(b.get().identifier().toString()) : null; }
            case "blk" -> blk(level(s(q, "dim")), pos(q));
            case "blk.set" -> blkSet(q);
            case "blk.fill" -> fill(q);
            case "blk.find" -> szukajBlokow(q);
            case "struct" -> struktura(q);
            case "blk.top" -> top(q);
            case "input" -> {
                if (!(needEnt(q) instanceof ServerPlayer ip)) yield null;
                if (q.has("on")) yield new JsonPrimitive(BedrockKomendy.setInputPermission(ip, s(q, "cat"), q.get("on").getAsBoolean()));
                yield new JsonPrimitive(BedrockKomendy.inputPermission(ip, s(q, "cat")));
            }
            case "blk.ray" -> blkRay(q);
            case "blk.tags" -> blockTags(parseState(s(q, "ty"), null));
            case "blk.sign" -> once("sign text is not wired yet");
            case "perm" -> perm(q);
            case "spawn" -> spawn(q);
            case "spawnItem" -> spawnItem(q);
            case "particle" -> particle(q);
            case "sound" -> sound(q, false);
            case "boom" -> boom(q);
            case "item.info" -> itemInfo(s(q, "ty"));
            case "hasType" -> new JsonPrimitive(hasType(s(q, "kind"), s(q, "id")));
            case "types" -> types(s(q, "kind"));
            case "blockStates" -> blockStateBook();
            case "sb" -> BedrockTabela.handle(srv(), q);
            case "form" -> BedrockFormy.show(player(q), q.getAsJsonObject("f"), BedrockSkrypciarz.teraz());
            case "form.close" -> { BedrockFormy.close(player(q)); yield null; }
            case "cmd.reg" -> { BedrockKomendy.register(BedrockSkrypciarz.teraz(), q); yield null; }
            case "cc.block" -> null; // koper blocks already tick/step through their json events
            case "scriptevent" -> { BedrockKomendy.scriptEvent(s(q, "id"), s(q, "m"), null); yield null; }
            case "rule" -> rule(q);
            case "packSettings" -> new JsonObject();
            case "prop.default" -> BedrockSkrypciarz.propDefault(s(q, "ty"), s(q, "k"));
            // properties of a mob that runs a behavior definition live in its behavior state, which the
            // resource pack reads on the client. anything else falls back to dynamic properties in js
            case "prop.get" -> {
                Object v = BedrockZachowanie.property(needEnt(q), s(q, "k"));
                JsonObject o = new JsonObject();
                o.addProperty("has", v != null);
                if (v instanceof Boolean b) o.addProperty("v", b);
                else if (v instanceof Number n) o.addProperty("v", n.doubleValue() == Math.rint(n.doubleValue()) ? (Number) n.longValue() : n);
                else if (v != null) o.addProperty("v", v.toString());
                yield o;
            }
            case "prop.set" -> new JsonPrimitive(BedrockZachowanie.ustawZeSkryptu(needEnt(q), s(q, "k"), q.get("v")));
            case "prop.reset" -> new JsonPrimitive(BedrockZachowanie.resetZeSkryptu(needEnt(q), s(q, "k")));
            default -> throw new Oops("koperlib bedrock does not know '" + op + "' yet");
        };
    }

    // ── small helpers ────────────────────────────────────────────────────────

    static String s(JsonObject q, String k) {
        JsonElement e = q.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static double d(JsonObject q, String k) {
        JsonElement e = q.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }

    private static BlockPos pos(JsonObject q) {
        return BlockPos.containing(d(q, "x"), d(q, "y"), d(q, "z"));
    }

    private static Vec3 vec(JsonObject o) {
        return new Vec3(d(o, "x"), d(o, "y"), d(o, "z"));
    }

    private static final Map<String, Boolean> SAID = new HashMap<>();

    private static JsonElement once(String what) {
        if (SAID.putIfAbsent(what, true) == null) KoperLib.LOGGER.warn("[Bedrock] {}", what);
        return null;
    }

    private static JsonElement log(JsonObject q) {
        var a = BedrockSkrypciarz.teraz();
        String who = a == null ? "?" : a.ns;
        String lvl = s(q, "lvl");
        String msg = s(q, "msg");
        switch (lvl == null ? "info" : lvl) {
            case "error" -> {
                KoperLib.LOGGER.error("[Bedrock/{}] {}", who, msg);
                // bedrock shows script errors in the content log, ops here see them in chat instead
                long now = System.currentTimeMillis();
                String head = msg.length() > 160 ? msg.substring(0, 160) : msg;
                Long last = LAST_SHOUT.get(head);
                MinecraftServer s = BedrockSkrypciarz.server();
                if (s != null && (last == null || now - last > 5000)) {
                    LAST_SHOUT.put(head, now);
                    Component line = Component.literal("§c[" + who + " script] §f" + head.lines().findFirst().orElse(head));
                    for (ServerPlayer p : s.getPlayerList().getPlayers())
                        if (s.getPlayerList().isOp(p.nameAndId())) p.sendSystemMessage(line);
                }
            }
            case "warn" -> KoperLib.LOGGER.warn("[Bedrock/{}] {}", who, msg);
            case "debug" -> KoperLib.LOGGER.debug("[Bedrock/{}] {}", who, msg);
            default -> KoperLib.LOGGER.info("[Bedrock/{}] {}", who, msg);
        }
        return null;
    }

    static Component comp(JsonElement j) {
        if (j == null || j.isJsonNull()) return Component.empty();
        if (j.isJsonPrimitive()) return Component.literal(j.getAsString());
        return ComponentSerialization.CODEC.parse(srv().registryAccess().createSerializationContext(JsonOps.INSTANCE), j)
            .result().orElse(Component.literal(j.toString()));
    }

    // ── dimensions ───────────────────────────────────────────────────────────

    static String dimName(Level l) {
        String id = l.dimension().identifier().toString();
        return id.equals("minecraft:the_nether") ? "minecraft:nether" : id;
    }

    static ServerLevel level(String dim) {
        MinecraftServer s = srv();
        if (dim == null) return s.overworld();
        String id = dim.contains(":") ? dim : "minecraft:" + dim;
        if (id.equals("minecraft:nether")) id = "minecraft:the_nether";
        ServerLevel l = s.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(id)));
        if (l == null) throw new Oops("no dimension " + dim);
        return l;
    }

    static JsonObject dimRef(Level l) {
        JsonObject o = new JsonObject();
        o.addProperty("$d", dimName(l));
        return o;
    }

    // ── entities ─────────────────────────────────────────────────────────────

    static final java.util.Map<UUID, Entity> ZEGNANY = new java.util.HashMap<>();
    // removed during this tick: bedrock takes entities out at the end of the tick, so events about a mob that
    // died or converted this tick still find it valid. java drops it at once and villager news' hurt and
    // interact handlers threw "entity is gone". emptied when the next tick starts (BedrockSkrypciarz.tick)
    static final java.util.Map<UUID, Entity> REMOVED_THIS_TICK = new java.util.HashMap<>();

    static Entity find(String id) {
        if (id == null) return null;
        UUID uuid;
        try { uuid = UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
        // the one being said goodbye to: bedrock's beforeEvents.entityRemove hands over an entity that is
        // still valid for the whole handler, java has already taken it off the lookup by then
        Entity zegnany = ZEGNANY.get(uuid);
        if (zegnany != null) return zegnany;
        Entity justGone = REMOVED_THIS_TICK.get(uuid);
        if (justGone != null) return justGone;
        for (ServerLevel l : srv().getAllLevels()) {
            Entity e = l.getEntity(uuid);
            if (e != null && !e.isRemoved()) return e;
        }
        // a player between levels (joining, respawning, changing dimension) is in no level's lookup yet
        // but is very much alive. scripts got "entity is gone" for the player the event was about
        ServerPlayer p = srv().getPlayerList().getPlayer(uuid);
        return p != null && !p.hasDisconnected() ? p : null;
    }

    private static Entity needEnt(JsonObject q) {
        Entity e = find(s(q, "e"));
        if (e == null) throw new Oops("entity is not valid any more");
        return e;
    }

    private static ServerPlayer player(JsonObject q) {
        if (!(needEnt(q) instanceof ServerPlayer p)) throw new Oops("that entity is not a player");
        return p;
    }

    // what a script sees: bedrock's name for the mob (minecraft:villager_v2), scripts compare against those
    static String typeId(Entity e) {
        return com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka(javaTypeId(e));
    }

    static String javaTypeId(Entity e) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? "minecraft:unknown" : key.toString();
    }

    static JsonObject ref(Entity e) {
        JsonObject r = new JsonObject();
        r.addProperty("e", e.getStringUUID());
        r.addProperty("ty", typeId(e));
        if (e instanceof ServerPlayer p) {
            r.addProperty("p", 1);
            r.addProperty("n", p.getGameProfile().name());
        }
        return r;
    }

    // {"$e": ref} — what event payloads carry so the js side builds a real Entity from it
    public static JsonElement entRef(Entity e) {
        if (e == null) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.add("$e", ref(e));
        return o;
    }

    private static JsonObject snap(String id) {
        Entity e = find(id);
        JsonObject o = new JsonObject();
        if (e == null) {
            o.addProperty("ok", false);
            return o;
        }
        o.addProperty("ok", true);
        o.addProperty("dim", dimName(e.level()));
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        o.addProperty("rx", e.getXRot());
        o.addProperty("ry", e.getYRot());
        Vec3 v = e.getDeltaMovement();
        o.addProperty("vx", v.x);
        o.addProperty("vy", v.y);
        o.addProperty("vz", v.z);
        Vec3 eye = e.getEyePosition();
        JsonObject head = new JsonObject();
        head.addProperty("x", eye.x); head.addProperty("y", eye.y); head.addProperty("z", eye.z);
        o.add("head", head);
        Vec3 look = e.getViewVector(1f);
        JsonObject lk = new JsonObject();
        lk.addProperty("x", look.x); lk.addProperty("y", look.y); lk.addProperty("z", look.z);
        o.add("look", lk);
        o.addProperty("w", e.getBbWidth());
        o.addProperty("h", e.getBbHeight());
        if (e.getCustomName() != null) o.addProperty("tag", e.getCustomName().getString());
        o.addProperty("sneak", e.isShiftKeyDown());
        o.addProperty("sprint", e.isSprinting());
        o.addProperty("ground", e.onGround());
        o.addProperty("water", e.isInWater());
        o.addProperty("swim", e.isSwimming());
        o.addProperty("fall", !e.onGround() && v.y < 0);
        o.addProperty("fd", e.fallDistance);
        o.addProperty("fire", e.getRemainingFireTicks());
        JsonArray tags = new JsonArray();
        e.entityTags().forEach(tags::add);
        o.add("tags", tags);
        JsonArray fam = new JsonArray();
        BedrockZachowanie.families(e).forEach(fam::add);
        o.add("fam", fam);
        if (e.getVehicle() != null) o.add("vehicle", ref(e.getVehicle()));
        if (!e.getPassengers().isEmpty()) {
            JsonArray riders = new JsonArray();
            e.getPassengers().forEach(r -> riders.add(ref(r)));
            o.add("riders", riders);
        }
        if (e instanceof LivingEntity le) {
            o.addProperty("hp", le.getHealth());
            o.addProperty("mhp", le.getMaxHealth());
            o.addProperty("climb", le.onClimbable());
            o.addProperty("sleep", le.isSleeping());
            o.addProperty("glide", le.isFallFlying());
            o.addProperty("baby", le.isBaby());
        }
        if (e instanceof Mob m && m.getTarget() != null) o.add("target", ref(m.getTarget()));
        if (e instanceof ItemEntity ie) o.add("item", itemJson(ie.getItem()));
        if (e instanceof ServerPlayer p) {
            o.addProperty("gm", p.gameMode().getName());
            o.addProperty("lvl", p.experienceLevel);
            o.addProperty("xpNext", p.getXpNeededForNextLevel());
            o.addProperty("xp", Math.round(p.experienceProgress * p.getXpNeededForNextLevel()));
            o.addProperty("xpTotal", p.totalExperience);
            o.addProperty("slot", p.getInventory().getSelectedSlot());
            o.addProperty("op", srv().getPlayerList().isOp(p.nameAndId()));
            o.addProperty("fly", p.getAbilities().flying);
        }
        // variant / mark_variant / skin_id: the behavior pack's when it runs this mob, java's villager data otherwise
        if (BedrockZachowanie.ma(e) || com.koper.koper_lib.api.core.BedrockWiesniak.jest(e)) {
            o.addProperty("variant", BedrockZachowanie.intState(e, "variant"));
            o.addProperty("mark", BedrockZachowanie.intState(e, "mark_variant"));
            o.addProperty("skin", BedrockZachowanie.intState(e, "skin_id"));
        }
        return o;
    }

    // entities that came from vanilla have no bedrock json, give the obvious families at least
    private static List<String> vanillaFamilies(Entity e) {
        List<String> out = new ArrayList<>();
        String path = javaTypeId(e).substring(javaTypeId(e).indexOf(':') + 1);
        out.add(path);
        if (e instanceof Player) out.add("player");
        if (e instanceof net.minecraft.world.entity.monster.Enemy) out.add("monster");
        if (e instanceof Mob) out.add("mob");
        if (e instanceof net.minecraft.world.entity.animal.Animal) out.add("animal");
        return out;
    }

    private static JsonArray players(JsonObject q) {
        JsonArray out = new JsonArray();
        JsonObject f = q.has("q") ? q.getAsJsonObject("q") : new JsonObject();
        List<Entity> hits = new ArrayList<>(srv().getPlayerList().getPlayers());
        for (Entity e : sortAndCut(hits.stream().filter(e -> matches(e, f)).toList(), f)) out.add(ref(e));
        return out;
    }

    private static JsonArray ents(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        JsonObject f = q.has("q") ? q.getAsJsonObject("q") : new JsonObject();
        List<Entity> hits = new ArrayList<>();
        if (f.has("loc") && (f.has("max") || f.has("vol"))) {
            Vec3 c = vec(f.getAsJsonObject("loc"));
            AABB box;
            if (f.has("vol")) {
                Vec3 v = vec(f.getAsJsonObject("vol"));
                box = new AABB(c.x, c.y, c.z, c.x + v.x, c.y + v.y, c.z + v.z);
            } else {
                double r = f.get("max").getAsDouble();
                box = new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
            }
            hits.addAll(l.getEntities((Entity) null, box, e -> matches(e, f)));
        } else {
            for (Entity e : l.getAllEntities()) if (matches(e, f)) hits.add(e);
        }
        JsonArray out = new JsonArray();
        for (Entity e : sortAndCut(hits, f)) out.add(ref(e));
        return out;
    }

    private static List<Entity> sortAndCut(List<Entity> in, JsonObject f) {
        if (!f.has("loc")) {
            if (f.has("closest") || f.has("farthest")) {
                int n = f.has("closest") ? f.get("closest").getAsInt() : f.get("farthest").getAsInt();
                return in.subList(0, Math.min(n, in.size()));
            }
            return in;
        }
        Vec3 c = vec(f.getAsJsonObject("loc"));
        List<Entity> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(c)));
        if (f.has("farthest")) {
            java.util.Collections.reverse(sorted);
            return sorted.subList(0, Math.min(f.get("farthest").getAsInt(), sorted.size()));
        }
        if (f.has("closest")) return sorted.subList(0, Math.min(f.get("closest").getAsInt(), sorted.size()));
        return sorted;
    }

    static boolean matches(Entity e, JsonObject f) {
        if (f == null || e.isRemoved()) return !e.isRemoved();
        String ty = typeId(e);
        // either naming matches: "villager_v2", "minecraft:villager", "zombie"
        if (f.has("type") && !com.koper.koper_lib.api.core.BedrockNazwy.ten(f.get("type").getAsString(), ty)) return false;
        if (f.has("notType")) for (JsonElement x : f.getAsJsonArray("notType")) if (com.koper.koper_lib.api.core.BedrockNazwy.ten(x.getAsString(), ty)) return false;
        if (f.has("tags")) for (JsonElement t : f.getAsJsonArray("tags")) if (!e.entityTags().contains(t.getAsString())) return false;
        if (f.has("notTags")) for (JsonElement t : f.getAsJsonArray("notTags")) if (e.entityTags().contains(t.getAsString())) return false;
        String name = e instanceof ServerPlayer p ? p.getGameProfile().name() : e.getCustomName() != null ? e.getCustomName().getString() : null;
        if (f.has("name") && !f.get("name").getAsString().equals(name)) return false;
        if (f.has("notName") && name != null && contains(f.getAsJsonArray("notName"), name)) return false;
        if (f.has("fam") || f.has("notFam")) {
            var fams = BedrockZachowanie.families(e);
            if (f.has("fam")) for (JsonElement x : f.getAsJsonArray("fam")) if (!fams.contains(x.getAsString())) return false;
            if (f.has("notFam")) for (JsonElement x : f.getAsJsonArray("notFam")) if (fams.contains(x.getAsString())) return false;
        }
        if (f.has("loc")) {
            double dist = e.position().distanceTo(vec(f.getAsJsonObject("loc")));
            if (f.has("max") && dist > f.get("max").getAsDouble()) return false;
            if (f.has("min") && dist < f.get("min").getAsDouble()) return false;
        }
        if (e instanceof ServerPlayer p) {
            String gm = p.gameMode().getName();
            if (f.has("gm") && !f.get("gm").getAsString().equalsIgnoreCase(gm)) return false;
            if (f.has("notGm")) for (JsonElement x : f.getAsJsonArray("notGm")) if (x.getAsString().equalsIgnoreCase(gm)) return false;
            if (f.has("minLvl") && p.experienceLevel < f.get("minLvl").getAsInt()) return false;
            if (f.has("maxLvl") && p.experienceLevel > f.get("maxLvl").getAsInt()) return false;
        } else if (f.has("gm") || f.has("minLvl") || f.has("maxLvl")) {
            return false;
        }
        if (f.has("scores")) {
            var board = srv().getScoreboard();
            ScoreHolder holder = e instanceof ServerPlayer p ? ScoreHolder.forNameOnly(p.getGameProfile().name()) : ScoreHolder.forNameOnly(e.getStringUUID());
            for (JsonElement so : f.getAsJsonArray("scores")) {
                JsonObject sc = so.getAsJsonObject();
                Objective obj = board.getObjective(s(sc, "objective"));
                if (obj == null) return false;
                var info = board.getPlayerScoreInfo(holder, obj);
                if (info == null) return false;
                int v = info.value();
                boolean in = (!sc.has("minScore") || v >= sc.get("minScore").getAsInt()) && (!sc.has("maxScore") || v <= sc.get("maxScore").getAsInt());
                boolean exclude = sc.has("exclude") && sc.get("exclude").getAsBoolean();
                if (in == exclude) return false;
            }
        }
        return true;
    }

    private static boolean contains(JsonArray a, String v) {
        for (JsonElement x : a) if (x.getAsString().equals(v)) return true;
        return false;
    }

    private static JsonElement entSet(JsonObject q) {
        Entity e = needEnt(q);
        String k = s(q, "k");
        switch (k) {
            case "nameTag" -> { e.setCustomName(Component.literal(s(q, "v"))); e.setCustomNameVisible(!s(q, "v").isEmpty()); }
            case "sneak" -> e.setShiftKeyDown(q.get("v").getAsBoolean());
            case "hp" -> { if (e instanceof LivingEntity le) le.setHealth((float) d(q, "v")); }
            case "rot" -> { e.setXRot((float) d(q, "x")); e.setYRot((float) d(q, "y")); if (e instanceof LivingEntity le) le.setYHeadRot((float) d(q, "y")); e.syncVelocity = true; }
            case "vel" -> { e.setDeltaMovement(d(q, "x"), d(q, "y"), d(q, "z")); e.syncVelocity = true; }
            case "impulse" -> { e.push(d(q, "x"), d(q, "y"), d(q, "z")); e.syncVelocity = true; }
            case "look" -> e.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new Vec3(d(q, "x"), d(q, "y"), d(q, "z")));
            case "kill" -> {
                if (e instanceof LivingEntity le && e.level() instanceof ServerLevel l) le.hurtServer(l, l.damageSources().genericKill(), Float.MAX_VALUE);
                else e.kill((ServerLevel) e.level());
                return new JsonPrimitive(true);
            }
            case "remove" -> e.discard();
            case "tag+" -> { return new JsonPrimitive(e.addTag(s(q, "v"))); }
            case "tag-" -> { return new JsonPrimitive(e.removeTag(s(q, "v"))); }
            case "fire" -> {
                double secs = d(q, "v");
                if (secs <= 0) e.clearFire(); else e.igniteForSeconds((float) secs);
                return new JsonPrimitive(true);
            }
            case "ride" -> { Entity v = find(s(q, "v")); return new JsonPrimitive(v != null && e.startRiding(v)); }
            case "unride" -> e.stopRiding();
            case "xp+" -> { if (e instanceof Player p) p.giveExperiencePoints((int) d(q, "v")); }
            case "lvl+" -> { if (e instanceof Player p) p.giveExperienceLevels((int) d(q, "v")); }
            case "lvl0" -> { if (e instanceof ServerPlayer p) { p.giveExperienceLevels(-p.experienceLevel); p.experienceProgress = 0; } }
            case "slot" -> { if (e instanceof ServerPlayer p) { p.getInventory().setSelectedSlot((int) d(q, "v")); runQuiet(p, "item replace entity @s weapon.mainhand from entity @s weapon.mainhand"); } }
            case "gm" -> { if (e instanceof ServerPlayer p) p.setGameMode(gameType(s(q, "v"), p)); }
            case "op" -> { if (e instanceof ServerPlayer p) { if (q.get("v").getAsBoolean()) srv().getPlayerList().op(p.nameAndId()); else srv().getPlayerList().deop(p.nameAndId()); } }
            case "cool" -> {
                if (e instanceof ServerPlayer p) {
                    Item it = item(s(q, "cat"));
                    if (it != null) p.getCooldowns().addCooldown(new ItemStack(it), (int) d(q, "v"));
                }
            }
            case "spawn" -> {
                if (e instanceof ServerPlayer p) {
                    JsonElement v = q.get("v");
                    if (v == null || v.isJsonNull()) runQuiet(p, "spawnpoint @s ~ ~ ~");
                    else {
                        JsonObject o = v.getAsJsonObject();
                        runQuiet(p, "spawnpoint @s " + (int) d(o, "x") + " " + (int) d(o, "y") + " " + (int) d(o, "z"));
                    }
                }
            }
            case "tame" -> once("tameable.tame() is not wired yet");
            default -> throw new Oops("can't set " + k + " on an entity");
        }
        return null;
    }

    private static GameType gameType(String v, ServerPlayer p) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "creative", "c", "1" -> GameType.CREATIVE;
            case "adventure", "a", "2" -> GameType.ADVENTURE;
            case "spectator", "sp", "6" -> GameType.SPECTATOR;
            case "default", "d", "5" -> srv().getDefaultGameType();
            default -> GameType.SURVIVAL;
        };
    }

    private static void runQuiet(Entity as, String command) {
        MinecraftServer s = srv();
        CommandSourceStack src = s.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS)
            .withEntity(as).withPosition(as.position()).withLevel((ServerLevel) as.level());
        try {
            BedrockKomendy.runQuietly(s, src, command);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException bad) {
            if (BedrockKomendy.isUnparseable(bad)) KoperLib.LOGGER.error("[Bedrock] koperlib's own command '{}' does not parse: {}", command, bad.getMessage());
        }
    }

    private static JsonElement tp(JsonObject q) {
        Entity e = needEnt(q);
        ServerLevel to = q.has("dim") && !q.get("dim").isJsonNull() ? level(s(q, "dim")) : (ServerLevel) e.level();
        double x = d(q, "x"), y = d(q, "y"), z = d(q, "z");
        if (q.has("check") && q.get("check").getAsBoolean()) {
            AABB moved = e.getBoundingBox().move(x - e.getX(), y - e.getY(), z - e.getZ());
            if (!to.noCollision(e, moved)) return new JsonPrimitive(false);
        }
        float yaw = e.getYRot(), pitch = e.getXRot();
        if (q.has("rx") && !q.get("rx").isJsonNull()) pitch = (float) d(q, "rx");
        if (q.has("ry") && !q.get("ry").isJsonNull()) yaw = (float) d(q, "ry");
        if (q.has("face") && !q.get("face").isJsonNull()) {
            Vec3 f = vec(q.getAsJsonObject("face"));
            double dx = f.x - x, dy = f.y - (y + e.getEyeHeight()), dz = f.z - z;
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        Vec3 keep = e.getDeltaMovement();
        boolean ok = e.teleportTo(to, x, y, z, Set.of(), yaw, pitch, true);
        if (q.has("keep") && q.get("keep").getAsBoolean()) {
            Entity after = find(s(q, "e"));
            if (after != null) { after.setDeltaMovement(keep); after.syncVelocity = true; }
        }
        return new JsonPrimitive(ok);
    }

    private static JsonElement dmg(JsonObject q) {
        Entity e = needEnt(q);
        if (!(e.level() instanceof ServerLevel l)) return new JsonPrimitive(false);
        Entity by = find(s(q, "by"));
        var src = l.damageSources();
        String cause = s(q, "cause");
        DamageSource ds = switch (cause == null ? "none" : cause) {
            case "fall" -> src.fall();
            case "fire" -> src.inFire();
            case "fireTick" -> src.onFire();
            case "lava" -> src.lava();
            case "drowning" -> src.drown();
            case "magic" -> src.magic();
            case "starve" -> src.starve();
            case "wither" -> src.wither();
            case "void" -> src.fellOutOfWorld();
            case "lightning" -> src.lightningBolt();
            case "freezing" -> src.freeze();
            case "contact" -> src.cactus();
            case "suffocation" -> src.inWall();
            case "flyIntoWall" -> src.flyIntoWall();
            case "stalagmite" -> src.stalagmite();
            default -> by instanceof Player p ? src.playerAttack(p) : by instanceof LivingEntity le ? src.mobAttack(le) : src.generic();
        };
        return new JsonPrimitive(e.hurtServer(l, ds, (float) d(q, "amt")));
    }

    static Holder<MobEffect> effect(String id) {
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        var h = rl == null ? Optional.<Holder.Reference<MobEffect>>empty() : BuiltInRegistries.MOB_EFFECT.get(rl);
        if (h.isEmpty()) throw new Oops("unknown effect " + id);
        return h.get();
    }

    private static JsonElement fxAdd(JsonObject q) {
        if (!(needEnt(q) instanceof LivingEntity le)) throw new Oops("only living entities take effects");
        boolean particles = !q.has("part") || q.get("part").getAsBoolean();
        le.addEffect(new MobEffectInstance(effect(s(q, "id")), (int) d(q, "dur"), (int) d(q, "amp"), false, particles));
        return null;
    }

    private static JsonElement fxRemove(JsonObject q) {
        if (!(needEnt(q) instanceof LivingEntity le)) return new JsonPrimitive(false);
        return new JsonPrimitive(le.removeEffect(effect(s(q, "id"))));
    }

    private static JsonElement fxList(JsonObject q) {
        JsonArray out = new JsonArray();
        if (!(needEnt(q) instanceof LivingEntity le)) return out;
        for (MobEffectInstance fx : le.getActiveEffects()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", fx.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
            o.addProperty("amp", fx.getAmplifier());
            o.addProperty("dur", fx.getDuration());
            o.addProperty("name", fx.getEffect().value().getDisplayName().getString());
            out.add(o);
        }
        return out;
    }

    private static JsonElement attr(JsonObject q) {
        if (!(needEnt(q) instanceof LivingEntity le)) return null;
        var h = BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(s(q, "a")));
        if (h.isEmpty()) return null;
        var inst = le.getAttribute((Holder<Attribute>) h.get());
        if (inst == null) return null;
        if (q.has("v")) { inst.setBaseValue(d(q, "v")); return null; }
        return new JsonPrimitive(q.has("base") ? inst.getBaseValue() : inst.getValue());
    }

    private static JsonElement title(JsonObject q) {
        ServerPlayer p = player(q);
        switch (s(q, "a")) {
            case "title" -> p.connection.send(new ClientboundSetTitleTextPacket(comp(q.get("m"))));
            case "sub" -> p.connection.send(new ClientboundSetSubtitleTextPacket(comp(q.get("m"))));
            case "bar" -> p.connection.send(new ClientboundSetActionBarTextPacket(comp(q.get("m"))));
            case "times" -> p.connection.send(new ClientboundSetTitlesAnimationPacket((int) d(q, "fi"), (int) d(q, "st"), (int) d(q, "fo")));
            case "clear" -> p.connection.send(new ClientboundClearTitlesPacket(true));
            default -> {}
        }
        return null;
    }

    private static JsonElement cooldown(JsonObject q) {
        if (!(needEnt(q) instanceof Player p)) return new JsonPrimitive(0);
        Item it = item(s(q, "cat"));
        return new JsonPrimitive(it != null && p.getCooldowns().isOnCooldown(new ItemStack(it)) ? 1 : 0);
    }

    // ── sounds and particles ─────────────────────────────────────────────────

    private static Holder<SoundEvent> soundOf(String id) {
        // same rules as a /playsound the command translator writes: bedrock's name, java's own, the pack's
        String out = BedrockSkladnia.sound(id);
        return Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(out.toLowerCase(Locale.ROOT))));
    }

    private static JsonElement sound(JsonObject q, boolean onePlayer) {
        Holder<SoundEvent> snd = soundOf(s(q, "id"));
        float vol = q.has("vol") ? (float) d(q, "vol") : 1f, pitch = q.has("pitch") ? (float) d(q, "pitch") : 1f;
        if (onePlayer) {
            ServerPlayer p = player(q);
            boolean at = q.has("x") && !q.get("x").isJsonNull();
            p.connection.send(new ClientboundSoundPacket(snd, SoundSource.MASTER,
                at ? d(q, "x") : p.getX(), at ? d(q, "y") : p.getY(), at ? d(q, "z") : p.getZ(), vol, pitch, p.getRandom().nextLong()));
            return null;
        }
        level(s(q, "dim")).playSound(null, d(q, "x"), d(q, "y"), d(q, "z"), snd, SoundSource.MASTER, vol, pitch);
        return null;
    }

    private static final Map<String, String> PARTICLES = Map.ofEntries(
        Map.entry("villager_happy", "happy_villager"), Map.entry("villager_angry", "angry_villager"),
        Map.entry("heart_particle", "heart"), Map.entry("basic_flame_particle", "flame"),
        Map.entry("critical_hit_emitter", "crit"), Map.entry("endrod", "end_rod"),
        Map.entry("huge_explosion_emitter", "explosion_emitter"), Map.entry("large_explosion", "explosion"),
        Map.entry("huge_explosion_lab_misc_emitter", "explosion_emitter"), Map.entry("note_particle", "note"),
        Map.entry("portal_directional", "portal"), Map.entry("portal_reverse_particle", "reverse_portal"),
        Map.entry("totem_particle", "totem_of_undying"), Map.entry("water_splash_particle", "splash"),
        Map.entry("lava_particle", "lava"), Map.entry("basic_smoke_particle", "smoke"),
        Map.entry("soul_particle", "soul"), Map.entry("dragon_breath_trail", "dragon_breath"),
        Map.entry("basic_crit_particle", "crit"), Map.entry("enchanting_table_particle", "enchant"),
        Map.entry("evocation_fang_particle", "enchanted_hit"), Map.entry("knockback_roar_particle", "sonic_boom"),
        Map.entry("sonic_explosion", "sonic_boom"), Map.entry("snowflake_particle", "snowflake"),
        Map.entry("electric_spark_particle", "electric_spark"), Map.entry("wax_particle", "wax_on"),
        Map.entry("cauldron_explosion_emitter", "explosion"), Map.entry("witchspell_emitter", "witch"),
        Map.entry("mobflame_single", "flame"), Map.entry("glow_particle", "glow"), Map.entry("bubble_column_up_particle", "bubble_column_up"));

    private static JsonElement particle(JsonObject q) {
        String raw = s(q, "id");
        String path = raw.startsWith("minecraft:") ? raw.substring(10) : raw.contains(":") ? null : raw;
        String javaId = path != null ? "minecraft:" + PARTICLES.getOrDefault(path, path) : raw;
        var type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.tryParse(javaId));
        ServerLevel l = level(s(q, "dim"));
        if (!(type instanceof SimpleParticleType simple)) {
            // not a java particle: a bedrock effect from some resource pack, kodel plays those on the client
            var pkt = new com.koper.koper_lib.api.core.BedrockCzastkaPayload(raw.contains(":") ? raw : "minecraft:" + raw, d(q, "x"), d(q, "y"), d(q, "z"));
            Entity only = q.has("only") ? find(s(q, "only")) : null;
            if (only instanceof ServerPlayer p) com.koper.koper_lib.api.core.KoperNetwork.send(p, pkt);
            else for (ServerPlayer p : l.players()) {
                if (p.distanceToSqr(d(q, "x"), d(q, "y"), d(q, "z")) < 128 * 128) com.koper.koper_lib.api.core.KoperNetwork.send(p, pkt);
            }
            return null;
        }
        if (q.has("only")) {
            Entity who = find(s(q, "only"));
            if (who instanceof ServerPlayer p) l.sendParticles(p, (ParticleOptions) simple, true, false, d(q, "x"), d(q, "y"), d(q, "z"), 1, 0, 0, 0, 0);
            return null;
        }
        l.sendParticles(simple, d(q, "x"), d(q, "y"), d(q, "z"), 1, 0, 0, 0, 0);
        return null;
    }

    private static JsonElement boom(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        Entity src = find(s(q, "src"));
        boolean breaks = !q.has("breaks") || q.get("breaks").getAsBoolean();
        l.explode(src, d(q, "x"), d(q, "y"), d(q, "z"), (float) d(q, "r"), q.has("fire") && q.get("fire").getAsBoolean(),
            breaks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        return null;
    }

    // ── items ────────────────────────────────────────────────────────────────

    static Item item(String id) {
        if (id == null) return null;
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) return null;
        Item it = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (it != null) return it;
        // bedrock's own name for it (muttonRaw, wooden_pressure_plate, slime)
        Identifier jb = Identifier.tryParse(com.koper.koper_lib.api.core.BedrockNazwy.przedmiotDoJavy(id));
        return jb == null ? null : BuiltInRegistries.ITEM.getOptional(jb).orElse(null);
    }

    public static JsonElement itemRef(ItemStack st) {
        if (st == null || st.isEmpty()) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.add("$i", itemJson(st));
        return o;
    }

    static JsonObject itemJson(ItemStack st) {
        JsonObject o = new JsonObject();
        o.addProperty("typeId", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        o.addProperty("amount", st.getCount());
        Component name = st.get(DataComponents.CUSTOM_NAME);
        if (name != null) o.addProperty("nameTag", name.getString());
        ItemLore lore = st.get(DataComponents.LORE);
        JsonArray lines = new JsonArray();
        if (lore != null) lore.lines().forEach(l -> lines.add(l.getString()));
        o.add("lore", lines);
        o.addProperty("damage", st.getDamageValue());
        JsonArray ench = new JsonArray();
        ItemEnchantments en = st.get(DataComponents.ENCHANTMENTS);
        if (en != null) for (var e : en.entrySet()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", e.getKey().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
            x.addProperty("level", e.getIntValue());
            ench.add(x);
        }
        o.add("ench", ench);
        CustomData cd = st.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? null : cd.copyTag();
        String dyn = tag == null ? null : tag.getStringOr("bedrock_dyn", null);
        o.add("dyn", dyn == null ? new JsonObject() : JsonParser.parseString(dyn));
        o.addProperty("lock", tag == null ? "none" : tag.getStringOr("bedrock_lock", "none"));
        o.addProperty("keep", tag != null && "1".equals(tag.getStringOr("bedrock_keep", "0")));
        return o;
    }

    static ItemStack stackOf(JsonElement j) {
        if (j == null || j.isJsonNull()) return ItemStack.EMPTY;
        JsonObject o = j.getAsJsonObject();
        Item it = item(s(o, "typeId"));
        if (it == null) throw new Oops("unknown item " + s(o, "typeId"));
        ItemStack st = new ItemStack(it, Math.max(1, (int) d(o, "amount")));
        if (o.has("nameTag") && !o.get("nameTag").isJsonNull()) st.set(DataComponents.CUSTOM_NAME, Component.literal(s(o, "nameTag")));
        if (o.has("lore") && !o.getAsJsonArray("lore").isEmpty()) {
            List<Component> lines = new ArrayList<>();
            o.getAsJsonArray("lore").forEach(l -> lines.add(Component.literal(l.getAsString())));
            st.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (o.has("damage") && d(o, "damage") > 0 && st.isDamageableItem()) st.setDamageValue((int) d(o, "damage"));
        if (o.has("ench") && !o.getAsJsonArray("ench").isEmpty()) {
            var reg = srv().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            for (JsonElement e : o.getAsJsonArray("ench")) {
                JsonObject x = e.getAsJsonObject();
                Identifier rl = Identifier.tryParse(s(x, "id"));
                if (rl == null) continue;
                Optional<Holder.Reference<Enchantment>> h = reg.get(ResourceKey.create(Registries.ENCHANTMENT, rl));
                h.ifPresent(ref -> m.set(ref, (int) d(x, "level")));
            }
            st.set(DataComponents.ENCHANTMENTS, m.toImmutable());
        }
        boolean hasDyn = o.has("dyn") && o.get("dyn").isJsonObject() && !o.getAsJsonObject("dyn").entrySet().isEmpty();
        String lock = s(o, "lock");
        boolean keep = o.has("keep") && o.get("keep").getAsBoolean();
        if (hasDyn || (lock != null && !lock.equals("none")) || keep) {
            CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> {
                if (hasDyn) tag.putString("bedrock_dyn", o.get("dyn").toString());
                if (lock != null && !lock.equals("none")) tag.putString("bedrock_lock", lock);
                if (keep) tag.putString("bedrock_keep", "1");
            });
        }
        return st;
    }

    private static JsonElement itemInfo(String ty) {
        Item it = item(ty);
        JsonObject o = new JsonObject();
        if (it == null) { o.addProperty("max", 64); o.add("tags", new JsonArray()); return o; }
        ItemStack st = new ItemStack(it);
        o.addProperty("max", st.getMaxStackSize());
        o.addProperty("dur", st.getMaxDamage());
        JsonArray tags = new JsonArray();
        it.builtInRegistryHolder().tags().forEach(t -> tags.add(t.location().toString()));
        o.add("tags", tags);
        var food = st.get(DataComponents.FOOD);
        if (food != null) {
            JsonObject f = new JsonObject();
            f.addProperty("n", food.nutrition());
            f.addProperty("s", food.saturation());
            f.addProperty("a", food.canAlwaysEat());
            o.add("food", f);
        }
        return o;
    }

    private static boolean hasType(String kind, String id) {
        Identifier rl = Identifier.tryParse(kind.equals("entity") ? com.koper.koper_lib.api.core.BedrockNazwy.doJavy(id) : id);
        if (rl == null) return false;
        return switch (kind) {
            case "item" -> BuiltInRegistries.ITEM.containsKey(rl);
            case "block" -> BuiltInRegistries.BLOCK.containsKey(rl);
            case "entity" -> BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
            case "effect" -> BuiltInRegistries.MOB_EFFECT.containsKey(rl);
            case "ench" -> srv().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(ResourceKey.create(Registries.ENCHANTMENT, rl)).isPresent();
            case "dim" -> types("dim").contains(new JsonPrimitive(rl.toString()));
            case "biome" -> srv().registryAccess().lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME, rl)).isPresent();
            case "potion" -> BuiltInRegistries.POTION.containsKey(rl);
            default -> throw new IllegalArgumentException("koperlib bedrock: no type book called '" + kind + "'");
        };
    }

    private static JsonArray types(String kind) {
        JsonArray out = new JsonArray();
        switch (kind) {
            case "item" -> BuiltInRegistries.ITEM.keySet().forEach(k -> out.add(k.toString()));
            case "block" -> BuiltInRegistries.BLOCK.keySet().forEach(k -> out.add(k.toString()));
            case "entity" -> BuiltInRegistries.ENTITY_TYPE.keySet().forEach(k -> out.add(com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka(k.toString())));
            case "effect" -> BuiltInRegistries.MOB_EFFECT.keySet().forEach(k -> out.add(k.toString()));
            case "ench" -> srv().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElementIds().forEach(k -> out.add(k.identifier().toString()));
            // bedrock calls the nether minecraft:nether, dimensionOf on the js side maps it back
            case "dim" -> srv().levelKeys().forEach(k -> out.add(k == Level.NETHER ? "minecraft:nether" : k.identifier().toString()));
            case "biome" -> srv().registryAccess().lookupOrThrow(Registries.BIOME).listElementIds().forEach(k -> out.add(k.identifier().toString()));
            case "potion" -> BuiltInRegistries.POTION.keySet().forEach(k -> out.add(k.toString()));
            default -> throw new IllegalArgumentException("koperlib bedrock: no type book called '" + kind + "'");
        }
        return out;
    }

    // every block state name any block has, with every value it can take. java names, not bedrock's
    // (facing, not minecraft:cardinal_direction) because those are the ones Block.permutation takes here
    private static JsonObject blockStateBook() {
        java.util.Map<String, java.util.LinkedHashSet<String>> book = new java.util.TreeMap<>();
        for (var block : BuiltInRegistries.BLOCK)
            for (var prop : block.getStateDefinition().getProperties())
                for (Object v : prop.getPossibleValues())
                    book.computeIfAbsent(prop.getName(), k -> new java.util.LinkedHashSet<>()).add(nameOf(prop, v));
        JsonObject out = new JsonObject();
        book.forEach((k, vals) -> { JsonArray a = new JsonArray(); vals.forEach(a::add); out.add(k, a); });
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String nameOf(net.minecraft.world.level.block.state.properties.Property prop, Object v) {
        return prop.getName((Comparable) v);
    }

    // ── containers ───────────────────────────────────────────────────────────

    private static Container containerOf(JsonObject owner) {
        if (owner.has("e")) {
            Entity e = find(s(owner, "e"));
            if (e instanceof Player p) return p.getInventory();
            if (e instanceof Container c) return c;
            if (e instanceof net.minecraft.world.entity.npc.InventoryCarrier ic) return ic.getInventory();
            return null;
        }
        JsonObject b = owner.getAsJsonObject("blk");
        ServerLevel l = level(s(b, "dim"));
        var be = l.getBlockEntity(pos(b));
        return be instanceof Container c ? c : null;
    }

    // a player's container is the 36 slot bedrock one: hotbar 0-8 then the backpack, same order java uses
    private static int sizeOf(Container c) {
        return c instanceof net.minecraft.world.entity.player.Inventory ? 36 : c.getContainerSize();
    }

    private static JsonElement inv(JsonObject q) {
        Container c = containerOf(q.getAsJsonObject("o"));
        String a = s(q, "a");
        if (c == null) {
            if (a.equals("valid")) return new JsonPrimitive(false);
            throw new Oops("that has no inventory");
        }
        int size = sizeOf(c);
        switch (a) {
            case "valid": return new JsonPrimitive(true);
            case "size": return new JsonPrimitive(size);
            case "empty": {
                int n = 0;
                for (int i = 0; i < size; i++) if (c.getItem(i).isEmpty()) n++;
                return new JsonPrimitive(n);
            }
            case "get": {
                int slot = (int) d(q, "slot");
                if (slot < 0 || slot >= size) throw new Oops("slot " + slot + " out of range");
                ItemStack st = c.getItem(slot);
                return st.isEmpty() ? null : itemJson(st);
            }
            case "set": {
                int slot = (int) d(q, "slot");
                if (slot < 0 || slot >= size) throw new Oops("slot " + slot + " out of range");
                c.setItem(slot, stackOf(q.get("item")));
                c.setChanged();
                return null;
            }
            case "add": {
                ItemStack st = stackOf(q.get("item"));
                for (int i = 0; i < size && !st.isEmpty(); i++) {
                    ItemStack in = c.getItem(i);
                    if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, st) && in.getCount() < in.getMaxStackSize()) {
                        int moved = Math.min(st.getCount(), in.getMaxStackSize() - in.getCount());
                        in.grow(moved);
                        st.shrink(moved);
                    }
                }
                for (int i = 0; i < size && !st.isEmpty(); i++) {
                    if (c.getItem(i).isEmpty()) {
                        c.setItem(i, st.copy());
                        st.setCount(0);
                    }
                }
                c.setChanged();
                return st.isEmpty() ? null : itemJson(st);
            }
            case "clear": {
                for (int i = 0; i < size; i++) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                return null;
            }
            default: throw new Oops("container can't " + a);
        }
    }

    private static EquipmentSlot slotOf(String s) {
        return switch (s == null ? "" : s.toLowerCase(Locale.ROOT)) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "body" -> EquipmentSlot.BODY;
            default -> EquipmentSlot.MAINHAND;
        };
    }

    private static JsonElement eq(JsonObject q) {
        if (!(needEnt(q) instanceof LivingEntity le)) throw new Oops("that entity has no equipment");
        EquipmentSlot slot = slotOf(s(q, "slot"));
        if (q.has("item")) {
            le.setItemSlot(slot, stackOf(q.get("item")));
            return new JsonPrimitive(true);
        }
        ItemStack st = le.getItemBySlot(slot);
        return st.isEmpty() ? null : itemJson(st);
    }

    // ── commands ─────────────────────────────────────────────────────────────

    private static JsonElement cmd(JsonObject q) {
        MinecraftServer s = srv();
        CommandSourceStack src = s.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS);
        if (q.has("e")) {
            Entity e = needEnt(q);
            src = src.withEntity(e).withPosition(e.position()).withLevel((ServerLevel) e.level());
        } else if (q.has("dim")) {
            src = src.withLevel(level(s(q, "dim")));
        }
        String command = BedrockKomendy.przetlumacz(s(q, "cmd"));
        JsonObject out = new JsonObject();
        try {
            int n = BedrockKomendy.runQuietly(s, src, command);
            out.addProperty("n", n);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException bad) {
            out.addProperty("error", bad.getMessage());
        }
        return out;
    }

    private static JsonElement select(JsonObject q) {
        JsonArray out = new JsonArray();
        MinecraftServer s = srv();
        CommandSourceStack src = s.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.ALL_PERMISSIONS);
        Entity from = q.has("src") && !q.get("src").isJsonNull() ? find(q.get("src").isJsonObject() ? s(q.getAsJsonObject("src"), "e") : s(q, "src")) : null;
        if (from != null) src = src.withEntity(from).withPosition(from.position()).withLevel((ServerLevel) from.level());
        try {
            var sel = EntityArgument.entities().parse(new StringReader(BedrockKomendy.przetlumacz(s(q, "sel"))));
            for (Entity e : sel.findEntities(src)) out.add(ref(e));
        } catch (Exception bad) {
            // plain player name falls through to here too
            ServerPlayer p = s.getPlayerList().getPlayerByName(s(q, "sel"));
            if (p != null) out.add(ref(p));
        }
        return out;
    }

    private static JsonElement rule(JsonObject q) {
        String k = s(q, "k");
        if (q.has("v")) {
            BedrockGameRules.set(srv(), k, q.get("v").getAsString());
            return null;
        }
        Object v = BedrockGameRules.read(srv(), k);
        return v instanceof Boolean b ? new JsonPrimitive(b) : v instanceof Integer n ? new JsonPrimitive(n) : null;
    }

    // ── world ────────────────────────────────────────────────────────────────

    private static JsonElement world(JsonObject q) {
        MinecraftServer s = srv();
        ServerLevel ow = s.overworld();
        CommandSourceStack src = s.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.ALL_PERMISSIONS);
        try {
            switch (s(q, "a")) {
                case "time": return new JsonPrimitive(ow.getDefaultClockTime() % 24000);
                case "abs": return new JsonPrimitive(ow.getGameTime());
                case "day": return new JsonPrimitive(ow.getDefaultClockTime() / 24000);
                case "moon": return new JsonPrimitive((ow.getDefaultClockTime() / 24000) % 8);
                case "settime": s.getCommands().getDispatcher().execute("time set " + (long) d(q, "v"), src); return null;
                case "setabs": return once("setAbsoluteTime can't move the game clock here");
                case "weather": return new JsonPrimitive(ow.isThundering() ? "Thunder" : ow.isRaining() ? "Rain" : "Clear");
                case "setweather": {
                    String w = s(q, "v").toLowerCase(Locale.ROOT);
                    String dur = q.has("d") && !q.get("d").isJsonNull() ? " " + Math.max(1, (int) d(q, "d") / 20) : "";
                    s.getCommands().getDispatcher().execute("weather " + (w.equals("thunder") ? "thunder" : w.equals("rain") ? "rain" : "clear") + dur, src);
                    return null;
                }
                case "diff": {
                    String n = s.getWorldData().getDifficulty().getSerializedName();
                    return new JsonPrimitive(Character.toUpperCase(n.charAt(0)) + n.substring(1));
                }
                case "setdiff": s.getCommands().getDispatcher().execute("difficulty " + s(q, "v").toLowerCase(Locale.ROOT), src); return null;
                case "spawn": {
                    BlockPos p = ow.getRespawnData().pos();
                    JsonObject o = new JsonObject();
                    o.addProperty("x", p.getX()); o.addProperty("y", p.getY()); o.addProperty("z", p.getZ());
                    return o;
                }
                case "setspawn": {
                    JsonObject v = q.getAsJsonObject("v");
                    s.getCommands().getDispatcher().execute("setworldspawn " + (int) d(v, "x") + " " + (int) d(v, "y") + " " + (int) d(v, "z"), src);
                    return null;
                }
                default: throw new Oops("world can't " + s(q, "a"));
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException bad) {
            throw new Oops(bad.getMessage());
        }
    }

    private static JsonElement dimLight(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        BlockPos p = pos(q);
        if (q.has("sky")) return new JsonPrimitive(l.getBrightness(net.minecraft.world.level.LightLayer.SKY, p));
        return new JsonPrimitive(l.getMaxLocalRawBrightness(p));
    }

    // ── blocks ───────────────────────────────────────────────────────────────

    // bedrock state names people use in scripts -> the java property they mean
    private static final Map<String, String> STATE_NAMES = Map.of(
        "minecraft:cardinal_direction", "facing", "minecraft:facing_direction", "facing", "minecraft:block_face", "facing",
        "minecraft:vertical_half", "half", "open_bit", "open", "growth", "age", "redstone_signal", "power",
        "powered_bit", "powered", "upside_down_bit", "half", "persistent_bit", "persistent");

    public static JsonElement blockRef(Level l, BlockPos p) {
        JsonObject r = new JsonObject();
        r.addProperty("dim", dimName(l));
        r.addProperty("x", p.getX());
        r.addProperty("y", p.getY());
        r.addProperty("z", p.getZ());
        JsonObject o = new JsonObject();
        o.add("$b", r);
        return o;
    }

    static String blockId(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    static JsonObject states(BlockState st) {
        JsonObject o = new JsonObject();
        for (Property<?> p : st.getProperties()) {
            Comparable<?> v = st.getValue(p);
            JsonPrimitive val = v instanceof Boolean b ? new JsonPrimitive(b) : v instanceof Integer i ? new JsonPrimitive(i)
                : new JsonPrimitive(p.getName(cast(v)));
            o.add(p.getName(), val);
        }
        // mirror the common ones under their bedrock names so either spelling reads fine
        for (var e : STATE_NAMES.entrySet()) {
            if (o.has(e.getValue()) && !o.has(e.getKey()) && !e.getKey().endsWith("_bit")) o.add(e.getKey(), o.get(e.getValue()));
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> T cast(Comparable<?> v) { return (T) v; }

    private static JsonElement blk(ServerLevel l, BlockPos p) {
        JsonObject o = new JsonObject();
        if (!l.isLoaded(p)) { o.addProperty("loaded", false); o.addProperty("ty", "minecraft:air"); o.add("st", new JsonObject()); return o; }
        BlockState st = l.getBlockState(p);
        o.addProperty("ty", blockId(st));
        o.add("st", states(st));
        o.addProperty("air", st.isAir());
        o.addProperty("liq", st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
        o.addProperty("solid", st.isSolidRender());
        o.addProperty("wl", st.hasProperty(BlockStateProperties.WATERLOGGED) && st.getValue(BlockStateProperties.WATERLOGGED));
        o.addProperty("rs", l.getBestNeighborSignal(p));
        o.add("tags", blockTags(st));
        return o;
    }

    // Dimension.getBlocks: every cell in the box (or in the list) that passes the filter, flat x,y,z,x,y,z...
    // a villager asks for a 15x9x15 box around itself every few seconds, so this stays java side
    private static final int FIND_CAP = 1 << 21;

    // world.structureManager: get (size or null), place, save (createFromWorld), del, ids
    private static JsonElement struktura(JsonObject q) {
        String a = s(q, "a");
        switch (a) {
            case "get" -> {
                int[] r = BedrockStruktury.rozmiar(srv(), s(q, "id"));
                if (r == null) return null;
                JsonObject o = new JsonObject();
                o.addProperty("x", r[0]); o.addProperty("y", r[1]); o.addProperty("z", r[2]);
                return o;
            }
            case "place" -> {
                JsonObject op = q.has("o") && q.get("o").isJsonObject() ? q.getAsJsonObject("o") : new JsonObject();
                net.minecraft.world.level.block.Rotation rot = switch (op.has("rotation") ? op.get("rotation").getAsString() : "None") {
                    case "Rotate90" -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
                    case "Rotate180" -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
                    case "Rotate270" -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                    default -> net.minecraft.world.level.block.Rotation.NONE;
                };
                net.minecraft.world.level.block.Mirror mir = switch (op.has("mirror") ? op.get("mirror").getAsString() : "None") {
                    case "X" -> net.minecraft.world.level.block.Mirror.FRONT_BACK;
                    case "Z" -> net.minecraft.world.level.block.Mirror.LEFT_RIGHT;
                    default -> net.minecraft.world.level.block.Mirror.NONE;
                };
                if ("XZ".equals(op.has("mirror") ? op.get("mirror").getAsString() : "")) {
                    // both mirrors = a half turn
                    rot = rot.getRotated(net.minecraft.world.level.block.Rotation.CLOCKWISE_180);
                }
                var o = new BedrockStruktury.Opcje(rot, mir,
                    !op.has("includeBlocks") || op.get("includeBlocks").getAsBoolean(),
                    !op.has("includeEntities") || op.get("includeEntities").getAsBoolean(),
                    op.has("waterlogged") && op.get("waterlogged").getAsBoolean(),
                    op.has("integrity") ? op.get("integrity").getAsFloat() : 1f,
                    op.has("integritySeed") ? op.get("integritySeed").getAsString().hashCode() : System.nanoTime(),
                    op.has("animationMode") ? op.get("animationMode").getAsString().toLowerCase(Locale.ROOT) : "none",
                    op.has("animationSeconds") ? op.get("animationSeconds").getAsFloat() : 0f);
                if (!BedrockStruktury.postaw(level(s(q, "dim")), s(q, "id"), pos(q), o))
                    throw new Oops("no structure named " + s(q, "id"));
                return null;
            }
            case "save" -> {
                BlockPos from = BlockPos.containing(d(q, "x0"), d(q, "y0"), d(q, "z0"));
                BlockPos to = BlockPos.containing(d(q, "x1"), d(q, "y1"), d(q, "z1"));
                BedrockStruktury.zapisz(level(s(q, "dim")), s(q, "id"), from, to,
                    !q.has("blocks") || q.get("blocks").getAsBoolean(), !q.has("ents") || q.get("ents").getAsBoolean(),
                    "World".equalsIgnoreCase(q.has("mode") ? q.get("mode").getAsString() : "Memory"));
                return null;
            }
            case "del" -> { return new JsonPrimitive(BedrockStruktury.usun(s(q, "id"))); }
            case "ids" -> {
                JsonArray out = new JsonArray();
                (q.has("pack") && q.get("pack").getAsBoolean() ? BedrockStruktury.idPaczek() : BedrockStruktury.idSwiata()).forEach(out::add);
                return out;
            }
            default -> throw new Oops("structureManager has no '" + a + "'");
        }
    }

    private static JsonArray szukajBlokow(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        boolean unloaded = q.has("unl") && q.get("unl").getAsBoolean();
        JsonObject f = q.has("f") && q.get("f").isJsonObject() ? q.getAsJsonObject("f") : new JsonObject();
        java.util.Set<String> inc = ids(f, "includeTypes"), exc = ids(f, "excludeTypes");
        java.util.Set<String> incTags = ids(f, "includeTags"), excTags = ids(f, "excludeTags");
        JsonArray out = new JsonArray();
        java.util.function.Consumer<BlockPos> test = p -> {
            if (!l.isLoaded(p)) {
                if (!unloaded) throw new Oops("Dimension.getBlocks touched an unloaded chunk at " + p.toShortString());
                return;
            }
            BlockState st = l.getBlockState(p);
            String id = blockId(st);
            if (!inc.isEmpty() && !inc.contains(id)) return;
            if (exc.contains(id)) return;
            if (!incTags.isEmpty() || !excTags.isEmpty()) {
                java.util.Set<String> tags = new java.util.HashSet<>();
                st.typeHolder().tags().forEach(t -> { tags.add(t.location().toString()); tags.add(t.location().getPath()); });
                if (!incTags.isEmpty() && java.util.Collections.disjoint(tags, incTags)) return;
                if (!java.util.Collections.disjoint(tags, excTags)) return;
            }
            out.add(p.getX()); out.add(p.getY()); out.add(p.getZ());
        };
        if (q.has("list")) {
            JsonArray list = q.getAsJsonArray("list");
            if (list.size() / 3 > FIND_CAP) throw new Oops("Dimension.getBlocks: " + list.size() / 3 + " locations, the cap is " + FIND_CAP);
            for (int i = 0; i + 2 < list.size(); i += 3)
                test.accept(new BlockPos(list.get(i).getAsInt(), list.get(i + 1).getAsInt(), list.get(i + 2).getAsInt()));
            return out;
        }
        int x0 = (int) Math.floor(d(q, "x0")), y0 = (int) Math.floor(d(q, "y0")), z0 = (int) Math.floor(d(q, "z0"));
        int x1 = (int) Math.floor(d(q, "x1")), y1 = (int) Math.floor(d(q, "y1")), z1 = (int) Math.floor(d(q, "z1"));
        y0 = Math.max(y0, l.getMinY()); y1 = Math.min(y1, l.getMaxY());
        long cells = (long) (x1 - x0 + 1) * Math.max(0, y1 - y0 + 1) * (z1 - z0 + 1);
        if (cells > FIND_CAP) throw new Oops("Dimension.getBlocks: a box of " + cells + " blocks, the cap is " + FIND_CAP);
        for (BlockPos p : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) test.accept(p);
        return out;
    }

    private static java.util.Set<String> ids(JsonObject f, String key) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!f.has(key) || !f.get(key).isJsonArray()) return out;
        for (var e : f.getAsJsonArray(key)) {
            String v = e.getAsString();
            out.add(key.endsWith("Tags") || v.contains(":") ? v : "minecraft:" + v);
        }
        return out;
    }

    private static JsonArray blockTags(BlockState st) {
        JsonArray tags = new JsonArray();
        st.typeHolder().tags().forEach(t -> tags.add(t.location().toString()));
        return tags;
    }

    static BlockState parseState(String ty, JsonObject states) {
        if (ty == null) throw new Oops("block type missing");
        StringBuilder sb = new StringBuilder(ty.contains(":") ? ty : "minecraft:" + ty);
        BlockState base;
        try {
            base = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, sb.toString(), false).blockState();
        } catch (Exception e) {
            // bedrock calls a few blocks differently (wooden_pressure_plate, slime, stonebrick)
            try {
                base = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, com.koper.koper_lib.api.core.BedrockNazwy.przedmiotDoJavy(sb.toString()), false).blockState();
            } catch (Exception e2) {
                throw new Oops("unknown block type " + ty);
            }
        }
        if (states == null) return base;
        for (var e : states.entrySet()) {
            String name = STATE_NAMES.getOrDefault(e.getKey(), e.getKey());
            Property<?> prop = base.getBlock().getStateDefinition().getProperty(name);
            if (prop == null) continue;
            String val = e.getValue().getAsString();
            if (e.getKey().equals("upside_down_bit")) val = e.getValue().getAsBoolean() ? "top" : "bottom";
            base = with(base, prop, val);
        }
        return base;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState st, Property<T> prop, String val) {
        Optional<T> v = prop.getValue(val);
        return v.isPresent() ? st.setValue(prop, v.get()) : st;
    }

    private static JsonElement perm(JsonObject q) {
        BlockState st = parseState(s(q, "ty"), q.has("st") ? q.getAsJsonObject("st") : null);
        JsonObject o = new JsonObject();
        o.addProperty("ty", blockId(st));
        o.add("st", states(st));
        return o;
    }

    private static JsonElement blkSet(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        BlockPos p = pos(q);
        if (!l.isLoaded(p)) throw new Oops("that block is in an unloaded chunk");
        BlockState st;
        if (q.has("wl") && !q.has("ty")) {
            st = l.getBlockState(p);
            if (!st.hasProperty(BlockStateProperties.WATERLOGGED)) return null;
            st = st.setValue(BlockStateProperties.WATERLOGGED, q.get("wl").getAsBoolean());
        } else {
            st = parseState(s(q, "ty"), q.has("st") && q.get("st").isJsonObject() ? q.getAsJsonObject("st") : null);
        }
        l.setBlock(p, st, Block.UPDATE_ALL);
        return null;
    }

    private static JsonElement fill(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        BlockState st = parseState(s(q, "ty"), q.has("st") && q.get("st").isJsonObject() ? q.getAsJsonObject("st") : null);
        JsonObject a = q.getAsJsonObject("from"), b = q.getAsJsonObject("to");
        int x0 = (int) Math.min(d(a, "x"), d(b, "x")), x1 = (int) Math.max(d(a, "x"), d(b, "x"));
        int y0 = (int) Math.min(d(a, "y"), d(b, "y")), y1 = (int) Math.max(d(a, "y"), d(b, "y"));
        int z0 = (int) Math.min(d(a, "z"), d(b, "z")), z1 = (int) Math.max(d(a, "z"), d(b, "z"));
        long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (vol > 32768) throw new Oops("fill of " + vol + " blocks is over the 32768 limit");
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            m.set(x, y, z);
            if (l.setBlock(m, st, Block.UPDATE_ALL)) n++;
        }
        return new JsonPrimitive(n);
    }

    private static JsonElement top(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        int x = (int) d(q, "x"), z = (int) d(q, "z");
        if (!l.isLoaded(new BlockPos(x, 64, z))) return null;
        if (!q.has("from")) return new JsonPrimitive(l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1);
        // walk down from where the script said to the first block that isn't air
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, Math.min((int) d(q, "from"), l.getMaxY()), z);
        for (; p.getY() >= l.getMinY(); p.move(Direction.DOWN)) if (!l.getBlockState(p).isAir()) return new JsonPrimitive(p.getY());
        return null;
    }

    private static String face(Direction d) {
        return switch (d) {
            case UP -> "Up"; case DOWN -> "Down"; case NORTH -> "North";
            case SOUTH -> "South"; case EAST -> "East"; case WEST -> "West";
        };
    }

    private static JsonElement blkRay(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        Vec3 from = vec(q.getAsJsonObject("from"));
        Vec3 dir = vec(q.getAsJsonObject("dir")).normalize();
        Vec3 to = from.add(dir.scale(d(q, "max")));
        boolean liq = q.has("liq") && q.get("liq").getAsBoolean();
        BlockHitResult hit = l.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
            liq ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        JsonObject o = new JsonObject();
        o.addProperty("dim", dimName(l));
        o.addProperty("x", p.getX()); o.addProperty("y", p.getY()); o.addProperty("z", p.getZ());
        o.addProperty("face", face(hit.getDirection()));
        JsonObject fl = new JsonObject();
        fl.addProperty("x", hit.getLocation().x - p.getX());
        fl.addProperty("y", hit.getLocation().y - p.getY());
        fl.addProperty("z", hit.getLocation().z - p.getZ());
        o.add("fl", fl);
        return o;
    }

    private static JsonElement entsRay(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        Vec3 from = vec(q.getAsJsonObject("from"));
        Vec3 dir = vec(q.getAsJsonObject("dir")).normalize();
        double max = d(q, "max");
        Vec3 to = from.add(dir.scale(max));
        AABB sweep = new AABB(from, to).inflate(1);
        List<JsonObject> hits = new ArrayList<>();
        for (Entity e : l.getEntities((Entity) null, sweep, x -> !x.isSpectator())) {
            Optional<Vec3> at = e.getBoundingBox().clip(from, to);
            if (at.isEmpty()) continue;
            JsonObject r = ref(e);
            r.addProperty("d", at.get().distanceTo(from));
            hits.add(r);
        }
        hits.sort(Comparator.comparingDouble(h -> h.get("d").getAsDouble()));
        JsonArray out = new JsonArray();
        hits.forEach(out::add);
        return out;
    }

    // entity.playAnimation: every client near the mob (or only options.players) plays it on its actor
    private static JsonElement anim(JsonObject q) {
        java.util.Set<String> only = null;
        if (q.has("players") && q.get("players").isJsonArray()) {
            only = new java.util.HashSet<>();
            for (JsonElement x : q.getAsJsonArray("players")) only.add(x.getAsString());
        }
        zagraj(needEnt(q), s(q, "anim"), opt(q, "next"), q.has("blend") && !q.get("blend").isJsonNull() ? q.get("blend").getAsFloat() : 0f,
            opt(q, "stop"), opt(q, "ctrl"), only);
        return null;
    }

    private static String opt(JsonObject q, String k) {
        return q.has(k) && !q.get(k).isJsonNull() ? q.get(k).getAsString() : "";
    }

    // also /playanimation. only = player names or uuids, null = everyone near
    static void zagraj(Entity e, String anim, String next, float blend, String stop, String ctrl, java.util.Set<String> only) {
        if (!(e.level() instanceof ServerLevel sl) || anim == null || anim.isBlank()) return;
        var pkt = new com.koper.koper_lib.api.core.BedrockAnimPayload(e.getId(), anim, next == null ? "" : next, blend,
            stop == null ? "" : stop, ctrl == null ? "" : ctrl);
        for (ServerPlayer p : sl.players()) {
            if (only != null && !only.contains(p.getStringUUID()) && !only.contains(p.getGameProfile().name())) continue;
            if (p.distanceToSqr(e) < 160 * 160) com.koper.koper_lib.api.core.KoperNetwork.send(p, pkt);
        }
    }

    // ── spawning ─────────────────────────────────────────────────────────────

    private static JsonElement spawn(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        String ty = s(q, "ty");
        int hash = ty.indexOf('<');
        String ev = hash > 0 && ty.endsWith(">") ? ty.substring(hash + 1, ty.length() - 1) : null;
        if (hash > 0) ty = ty.substring(0, hash); // "ns:mob<minecraft:entity_born>" spawn event suffix
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.tryParse(com.koper.koper_lib.api.core.BedrockNazwy.doJavy(ty))).orElseThrow(() -> new Oops("unknown entity type " + s(q, "ty")));
        Entity e = type.create(l, EntitySpawnReason.COMMAND);
        if (e == null) throw new Oops("can't spawn " + ty);
        e.snapTo(d(q, "x"), d(q, "y"), d(q, "z"), l.getRandom().nextFloat() * 360f, 0f);
        boolean ours = BedrockZachowanie.ma(e);
        if (ours) BedrockZachowanie.overrideSpawnEvent(e, ev);
        if (e instanceof Mob m) m.finalizeSpawn(l, l.getCurrentDifficultyAt(e.blockPosition()), EntitySpawnReason.COMMAND, null);
        if (!l.addFreshEntity(e)) throw new Oops("the world refused to spawn " + ty);
        if (!ours && ev != null && !ev.equals("minecraft:entity_spawned")) BedrockZachowanie.event(e, ev, null);
        return ref(e);
    }

    private static JsonElement spawnItem(JsonObject q) {
        ServerLevel l = level(s(q, "dim"));
        ItemEntity ie = new ItemEntity(l, d(q, "x"), d(q, "y"), d(q, "z"), stackOf(q.get("item")));
        ie.setDefaultPickUpDelay();
        l.addFreshEntity(ie);
        return ref(ie);
    }
}
