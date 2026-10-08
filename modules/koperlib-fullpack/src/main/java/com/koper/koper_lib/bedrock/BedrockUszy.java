package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.scripting.KoperSnitch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

// minecraft happenings -> bedrock events. the mixins in mixin/Bedrock*Mixin call in here and
// every method bails out first thing when no addon listens, so koper packs without bedrock
// stuff pay one set lookup per event and that's it
public final class BedrockUszy {

    private BedrockUszy() {}

    public static void register() {
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!world.isClientSide() && !udawany(player) && BedrockSkrypciarz.wants("entityHitBlock", false)) {
                JsonObject j = new JsonObject();
                j.add("damagingEntity", BedrockPytajnik.entRef(player));
                j.add("hitBlock", BedrockPytajnik.blockRef(world, pos));
                j.addProperty("blockFace", face(dir));
                j.add("hitBlockPermutation", BedrockPytajnik.blockRef(world, pos));
                BedrockSkrypciarz.fireAfter("entityHitBlock", j);
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, level) -> loaded(e));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((e, level) -> removing(e));
        KoperSnitch.listen(KoperSnitch.JOIN, t -> {
            if (!udawany(t.who()) && !CZEKAJACY.contains(t.who())) CZEKAJACY.add(t.who());
        });
        KoperSnitch.listen(KoperSnitch.LEFT, t -> {
            JsonObject b = new JsonObject();
            b.add("player", BedrockPytajnik.entRef(t.who()));
            BedrockSkrypciarz.fireBefore("playerLeave", b);
            BedrockFormy.playerLeft(t.who());
            JsonObject j = new JsonObject();
            j.addProperty("playerId", t.who().getStringUUID());
            j.addProperty("playerName", t.who().getGameProfile().name());
            BedrockSkrypciarz.fireAfter("playerLeave", j);
        });
        KoperSnitch.listen(KoperSnitch.PLACE, t -> {
            if (!BedrockSkrypciarz.wants("playerPlaceBlock", false)) return;
            BlockPos p = new BlockPos(Integer.parseInt(t.bit("x", "0")), Integer.parseInt(t.bit("y", "0")), Integer.parseInt(t.bit("z", "0")));
            JsonObject j = new JsonObject();
            j.add("player", BedrockPytajnik.entRef(t.who()));
            j.add("block", BedrockPytajnik.blockRef(t.who().level(), p));
            j.add("dimension", BedrockPytajnik.dimRef(t.who().level()));
            BedrockSkrypciarz.fireAfter("playerPlaceBlock", j);
        });
        KoperSnitch.listen(KoperSnitch.DIM, t -> {
            if (!BedrockSkrypciarz.wants("playerDimensionChange", false)) return;
            JsonObject j = new JsonObject();
            j.add("player", BedrockPytajnik.entRef(t.who()));
            JsonObject from = new JsonObject();
            from.addProperty("$d", t.bit("from").replace("the_nether", "nether"));
            j.add("fromDimension", from);
            j.add("toDimension", BedrockPytajnik.dimRef(t.who().level()));
            j.add("toLocation", loc(t.who()));
            BedrockSkrypciarz.fireAfter("playerDimensionChange", j);
        });
    }

    private static JsonObject loc(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        return o;
    }

    private static JsonObject perm(BlockState st) {
        JsonObject p = new JsonObject();
        p.addProperty("ty", BedrockPytajnik.blockId(st));
        p.add("st", BedrockPytajnik.states(st));
        JsonObject w = new JsonObject();
        w.add("$p", p);
        return w;
    }

    private static String face(Direction d) {
        return switch (d) {
            case UP -> "Up"; case DOWN -> "Down"; case NORTH -> "North";
            case SOUTH -> "South"; case EAST -> "East"; case WEST -> "West";
        };
    }

    private static JsonObject damageSource(DamageSource src) {
        JsonObject o = new JsonObject();
        o.addProperty("cause", cause(src));
        Entity by = src.getEntity(), direct = src.getDirectEntity();
        if (by != null) o.add("damagingEntity", BedrockPytajnik.entRef(by));
        if (direct instanceof Projectile) o.add("damagingProjectile", BedrockPytajnik.entRef(direct));
        return o;
    }

    static String cause(DamageSource src) {
        String id = src.getMsgId();
        return switch (id) {
            case "fall" -> "fall";
            case "inFire" -> "fire";
            case "onFire" -> "fireTick";
            case "lava" -> "lava";
            case "drown" -> "drowning";
            case "magic", "indirectMagic" -> "magic";
            case "starve" -> "starve";
            case "wither", "witherSkull" -> "wither";
            case "outOfWorld", "genericKill" -> "void";
            case "lightningBolt" -> "lightning";
            case "freeze" -> "freezing";
            case "cactus", "sweetBerryBush" -> "contact";
            case "inWall", "cramming" -> "suffocation";
            case "explosion", "explosion.player" -> src.getEntity() != null ? "entityExplosion" : "blockExplosion";
            case "arrow", "trident", "thrown", "fireball", "witherSkull.item", "mob_projectile" -> "projectile";
            case "thorns" -> "thorns";
            case "sonic_boom" -> "sonicBoom";
            case "anvil" -> "anvil";
            case "fallingBlock", "fallingStalactite" -> "fallingBlock";
            case "flyIntoWall" -> "flyIntoWall";
            case "hotFloor" -> "magma";
            case "stalagmite" -> "stalagmite";
            case "fireworks" -> "fireworks";
            case "mob", "player", "sting", "mace_smash" -> "entityAttack";
            default -> src.getEntity() != null ? "entityAttack" : "none";
        };
    }

    // ── breaking ─────────────────────────────────────────────────────────────

    // true = cancelled, caller must not break the block
    public static boolean beforeBreak(ServerPlayer p, BlockPos pos) {
        if (udawany(p)) return false;
        if (!BedrockSkrypciarz.anyAddons()) return false;
        JsonObject j = new JsonObject();
        j.add("player", BedrockPytajnik.entRef(p));
        j.add("block", BedrockPytajnik.blockRef(p.level(), pos));
        j.add("dimension", BedrockPytajnik.dimRef(p.level()));
        j.add("itemStack", BedrockPytajnik.itemRef(p.getMainHandItem()));
        if (!BedrockSkrypciarz.fireBefore("playerBreakBlock", j)) return false;
        // client already thinks it's gone, put it back on their screen
        p.connection.send(new ClientboundBlockUpdatePacket(p.level(), pos));
        return true;
    }

    public static void afterBreak(ServerPlayer p, BlockPos pos, BlockState was, ItemStack heldBefore) {
        if (udawany(p)) return;
        if (!BedrockSkrypciarz.anyAddons()) return;
        JsonObject j = new JsonObject();
        j.add("player", BedrockPytajnik.entRef(p));
        j.add("block", BedrockPytajnik.blockRef(p.level(), pos));
        j.add("dimension", BedrockPytajnik.dimRef(p.level()));
        j.add("brokenBlockPermutation", perm(was));
        j.add("itemStackBeforeBreak", BedrockPytajnik.itemRef(heldBefore));
        j.add("itemStackAfterBreak", BedrockPytajnik.itemRef(p.getMainHandItem()));
        BedrockSkrypciarz.fireAfter("playerBreakBlock", j);

        // tool custom component: onMineBlock
        if (heldBefore != null && !heldBefore.isEmpty()) {
            String id = BuiltInRegistries.ITEM.getKey(heldBefore.getItem()).toString();
            JsonElement comps = BedrockSkrypciarz.itemComps(id);
            if (comps != null) {
                JsonObject d = new JsonObject();
                d.add("source", BedrockPytajnik.entRef(p));
                d.add("block", BedrockPytajnik.blockRef(p.level(), pos));
                d.add("minedBlockPermutation", perm(was));
                d.add("itemStack", BedrockPytajnik.itemRef(heldBefore));
                BedrockSkrypciarz.fireComponent("item", id, "onMineBlock", comps, d, false);
            }
        }
    }

    // ── using items ──────────────────────────────────────────────────────────

    public static boolean beforeUse(ServerPlayer p, ItemStack st) {
        if (udawany(p)) return false;
        if (!BedrockSkrypciarz.anyAddons() || st.isEmpty()) return false;
        JsonObject j = new JsonObject();
        j.add("source", BedrockPytajnik.entRef(p));
        j.add("itemStack", BedrockPytajnik.itemRef(st));
        return BedrockSkrypciarz.fireBefore("itemUse", j);
    }

    public static void afterUse(ServerPlayer p, ItemStack st) {
        if (udawany(p)) return;
        if (st.isEmpty()) return;
        JsonObject thr = BedrockSkrypciarz.throwable(BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        if (thr != null && BedrockPocisk.rzuc(p, BedrockTlumacz.str(thr, "entity", ""), thr.has("power") ? thr.get("power").getAsFloat() : 1.5f) != null) {
            if (!thr.has("swing") || thr.get("swing").getAsBoolean()) p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, p.getMainHandItem().getInteractAnimation(), true);
            if (!p.getAbilities().instabuild) st.shrink(1);
        }
        if (!BedrockSkrypciarz.anyAddons()) return;
        JsonObject j = new JsonObject();
        j.add("source", BedrockPytajnik.entRef(p));
        j.add("itemStack", BedrockPytajnik.itemRef(st));
        BedrockSkrypciarz.fireAfter("itemUse", j);
    }

    public static boolean beforeUseOn(ServerPlayer p, ItemStack st, BlockHitResult hit) {
        if (udawany(p)) return false;
        if (!BedrockSkrypciarz.anyAddons()) return false;
        JsonObject j = useOnData(p, st, hit);
        j.add("player", BedrockPytajnik.entRef(p));
        boolean cancel = BedrockSkrypciarz.fireBefore("playerInteractWithBlock", j);
        if (!st.isEmpty()) {
            JsonObject u = useOnData(p, st, hit);
            u.add("source", BedrockPytajnik.entRef(p));
            cancel |= BedrockSkrypciarz.fireBefore("itemUseOn", u);
        }
        return cancel;
    }

    public static void afterUseOn(ServerPlayer p, ItemStack st, BlockHitResult hit) {
        if (udawany(p)) return;
        if (!BedrockSkrypciarz.anyAddons()) return;
        JsonObject j = useOnData(p, st, hit);
        j.add("player", BedrockPytajnik.entRef(p));
        BedrockSkrypciarz.fireAfter("playerInteractWithBlock", j);
        if (st.isEmpty()) return;
        JsonObject u = useOnData(p, st, hit);
        u.add("source", BedrockPytajnik.entRef(p));
        BedrockSkrypciarz.fireAfter("itemUseOn", u);
        if (BedrockSkrypciarz.wants("itemStartUseOn", false)) {
            JsonObject so = new JsonObject();
            so.add("source", BedrockPytajnik.entRef(p));
            so.add("itemStack", BedrockPytajnik.itemRef(st));
            so.add("block", BedrockPytajnik.blockRef(p.level(), hit.getBlockPos()));
            so.addProperty("blockFace", face(hit.getDirection()));
            BedrockSkrypciarz.fireAfter("itemStartUseOn", so);
        }

        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        JsonElement comps = BedrockSkrypciarz.itemComps(id);
        if (comps != null) {
            JsonObject d = useOnData(p, st, hit);
            d.add("source", BedrockPytajnik.entRef(p));
            d.add("usedOnBlockPermutation", perm(p.level().getBlockState(hit.getBlockPos())));
            BedrockSkrypciarz.fireComponent("item", id, "onUseOn", comps, d, false);
        }
    }

    private static JsonObject useOnData(ServerPlayer p, ItemStack st, BlockHitResult hit) {
        JsonObject j = new JsonObject();
        BlockPos pos = hit.getBlockPos();
        j.add("block", BedrockPytajnik.blockRef(p.level(), pos));
        j.addProperty("blockFace", face(hit.getDirection()));
        JsonObject fl = new JsonObject();
        fl.addProperty("x", hit.getLocation().x - pos.getX());
        fl.addProperty("y", hit.getLocation().y - pos.getY());
        fl.addProperty("z", hit.getLocation().z - pos.getZ());
        j.add("faceLocation", fl);
        j.add("itemStack", BedrockPytajnik.itemRef(st));
        j.addProperty("isFirstEvent", true);
        return j;
    }

    public static boolean beforeInteractEntity(Player player, Entity target) {
        if (udawany(player)) return false;
        if (!(player instanceof ServerPlayer p) || !BedrockSkrypciarz.anyAddons()) return false;
        JsonObject j = new JsonObject();
        j.add("player", BedrockPytajnik.entRef(p));
        j.add("target", BedrockPytajnik.entRef(target));
        j.add("itemStack", BedrockPytajnik.itemRef(p.getMainHandItem()));
        return BedrockSkrypciarz.fireBefore("playerInteractWithEntity", j);
    }

    public static void afterInteractEntity(Player player, Entity target) {
        if (udawany(player)) return;
        if (!(player instanceof ServerPlayer p) || !BedrockSkrypciarz.anyAddons()) return;
        JsonObject j = new JsonObject();
        j.add("player", BedrockPytajnik.entRef(p));
        j.add("target", BedrockPytajnik.entRef(target));
        j.add("itemStack", BedrockPytajnik.itemRef(p.getMainHandItem()));
        BedrockSkrypciarz.fireAfter("playerInteractWithEntity", j);
    }

    // ── chat ─────────────────────────────────────────────────────────────────

    public static boolean beforeChat(ServerPlayer p, String message) {
        if (udawany(p)) return false;
        if (!BedrockSkrypciarz.wants("chatSend", true)) return false;
        JsonObject j = new JsonObject();
        j.add("sender", BedrockPytajnik.entRef(p));
        j.addProperty("message", message);
        return BedrockSkrypciarz.fireBefore("chatSend", j);
    }

    public static void afterChat(ServerPlayer p, String message) {
        if (udawany(p)) return;
        if (!BedrockSkrypciarz.wants("chatSend", false)) return;
        JsonObject j = new JsonObject();
        j.add("sender", BedrockPytajnik.entRef(p));
        j.addProperty("message", message);
        BedrockSkrypciarz.fireAfter("chatSend", j);
    }

    // ── living things ────────────────────────────────────────────────────────

    // beforeEvents.entityHurt: NaN = cancelled, anything else is the damage to go on with
    public static float beforeHurt(LivingEntity victim, DamageSource src, float amount) {
        if (udawany(victim)) return amount;
        if (victim.level().isClientSide() || !BedrockSkrypciarz.wants("entityHurt", true)) return amount;
        JsonObject j = new JsonObject();
        j.add("hurtEntity", BedrockPytajnik.entRef(victim));
        j.addProperty("damage", amount);
        j.add("damageSource", damageSource(src));
        JsonObject out = BedrockSkrypciarz.fireBeforeEdit("entityHurt", j, "damage");
        return out == null ? Float.NaN : out.get("damage").getAsFloat();
    }

    // beforeEvents.effectAdd: -1 = cancelled, otherwise the duration to apply
    public static int beforeEffect(LivingEntity e, net.minecraft.world.effect.MobEffectInstance fx) {
        if (udawany(e)) return fx.getDuration();
        if (e.level().isClientSide() || !BedrockSkrypciarz.wants("effectAdd", true)) return fx.getDuration();
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(e));
        j.addProperty("effectType", BuiltInRegistries.MOB_EFFECT.getKey(fx.getEffect().value()).toString());
        j.addProperty("duration", fx.getDuration());
        JsonObject out = BedrockSkrypciarz.fireBeforeEdit("effectAdd", j, "duration");
        return out == null ? -1 : out.get("duration").getAsInt();
    }

    public static void hurt(LivingEntity victim, DamageSource src, float amount) {
        if (udawany(victim)) return;
        boolean hurtWanted = BedrockSkrypciarz.wants("entityHurt", false);
        boolean hitWanted = BedrockSkrypciarz.wants("entityHitEntity", false) && src.getEntity() != null && src.getDirectEntity() == src.getEntity();
        if (!hurtWanted && !hitWanted) return;
        if (hurtWanted) {
            JsonObject j = new JsonObject();
            j.add("hurtEntity", BedrockPytajnik.entRef(victim));
            j.addProperty("damage", amount);
            j.add("damageSource", damageSource(src));
            BedrockSkrypciarz.fireAfter("entityHurt", j);
        }
        if (hitWanted) {
            JsonObject j = new JsonObject();
            j.add("damagingEntity", BedrockPytajnik.entRef(src.getEntity()));
            j.add("hitEntity", BedrockPytajnik.entRef(victim));
            BedrockSkrypciarz.fireAfter("entityHitEntity", j);
        }
    }

    // ── more world events: health, heal, item use, projectiles, effects, swings, game mode ─

    private static boolean wZdrowiu;

    public static void healthChanged(LivingEntity e, float before, float now) {
        if (udawany(e)) return;
        // tickCount 0: the constructor's own setHealth, nobody to tell yet
        if (wZdrowiu || before == now || e.tickCount == 0 || e.level().isClientSide() || !BedrockSkrypciarz.wants("entityHealthChanged", false)) return;
        wZdrowiu = true; // a handler setting health again must not loop
        try {
            JsonObject j = new JsonObject();
            j.add("entity", BedrockPytajnik.entRef(e));
            j.addProperty("oldValue", before);
            j.addProperty("newValue", now);
            BedrockSkrypciarz.fireAfter("entityHealthChanged", j);
        } finally {
            wZdrowiu = false;
        }
    }

    public static void healed(LivingEntity e, float amount) {
        if (udawany(e)) return;
        if (amount <= 0 || e.level().isClientSide() || !BedrockSkrypciarz.wants("entityHeal", false)) return;
        JsonObject j = new JsonObject();
        j.add("healedEntity", BedrockPytajnik.entRef(e));
        j.addProperty("healAmount", amount);
        JsonObject src = new JsonObject();
        src.addProperty("cause", "None");
        j.add("healSource", src);
        BedrockSkrypciarz.fireAfter("entityHeal", j);
    }

    // itemStartUse / itemStopUse / itemReleaseUse / itemCompleteUse, players only like bedrock
    public static void itemUseStage(String event, LivingEntity who, ItemStack st, int useDuration) {
        if (!(who instanceof ServerPlayer p) || st == null || st.isEmpty() || !BedrockSkrypciarz.wants(event, false)) return;
        JsonObject j = new JsonObject();
        j.add("source", BedrockPytajnik.entRef(p));
        j.add("itemStack", BedrockPytajnik.itemRef(st));
        j.addProperty("useDuration", useDuration);
        BedrockSkrypciarz.fireAfter(event, j);
    }

    public static void projectileHit(Projectile pr, net.minecraft.world.phys.HitResult hit) {
        if (pr.level().isClientSide() || hit == null) return;
        boolean entity = hit instanceof net.minecraft.world.phys.EntityHitResult;
        String name = entity ? "projectileHitEntity" : "projectileHitBlock";
        if (!BedrockSkrypciarz.wants(name, false)) return;
        if (!entity && hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        JsonObject j = new JsonObject();
        j.add("projectile", BedrockPytajnik.entRef(pr));
        if (pr.getOwner() != null) j.add("source", BedrockPytajnik.entRef(pr.getOwner()));
        j.add("dimension", BedrockPytajnik.dimRef(pr.level()));
        j.add("location", vec(hit.getLocation()));
        j.add("hitVector", vec(pr.getDeltaMovement()));
        if (hit instanceof net.minecraft.world.phys.EntityHitResult eh) {
            JsonObject o = new JsonObject();
            o.add("entity", BedrockPytajnik.entRef(eh.getEntity()));
            j.add("entityHit", o);
        } else if (hit instanceof BlockHitResult bh) {
            JsonObject o = new JsonObject();
            o.add("block", BedrockPytajnik.blockRef(pr.level(), bh.getBlockPos()));
            o.addProperty("face", face(bh.getDirection()));
            o.add("faceLocation", vec(bh.getLocation()));
            j.add("blockHit", o);
        }
        BedrockSkrypciarz.fireAfter(name, j);
    }

    public static void effectAdded(LivingEntity e, net.minecraft.world.effect.MobEffectInstance fx) {
        if (udawany(e)) return;
        if (e.level().isClientSide() || !BedrockSkrypciarz.wants("effectAdd", false)) return;
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(e));
        JsonObject f = new JsonObject();
        f.addProperty("typeId", fx.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse(""));
        f.addProperty("duration", fx.getDuration());
        f.addProperty("amplifier", fx.getAmplifier());
        j.add("effect", f);
        j.addProperty("effectState", 1);
        BedrockSkrypciarz.fireAfter("effectAdd", j);
    }

    public static void swung(LivingEntity e) {
        if (udawany(e)) return;
        if (!(e instanceof ServerPlayer p) || !BedrockSkrypciarz.wants("playerSwingStart", false)) return;
        JsonObject j = new JsonObject();
        j.add("player", BedrockPytajnik.entRef(p));
        if (!p.getMainHandItem().isEmpty()) j.add("heldItemStack", BedrockPytajnik.itemRef(p.getMainHandItem()));
        j.addProperty("swingSource", "Attack");
        BedrockSkrypciarz.fireAfter("playerSwingStart", j);
    }

    public static void gameModeChanged(ServerPlayer p, String from, String to) {
        if (udawany(p)) return;
        if (from.equals(to) || !BedrockSkrypciarz.wants("playerGameModeChange", false)) return;
        JsonObject j = new JsonObject();
        j.add("player", BedrockPytajnik.entRef(p));
        j.addProperty("fromGameMode", from);
        j.addProperty("toGameMode", to);
        BedrockSkrypciarz.fireAfter("playerGameModeChange", j);
    }

    // a behavior pack event ran on a mob (entity.triggerEvent, sensors, timers, other events)
    public static void dataDriven(Entity e, String event, java.util.List<String> added, java.util.List<String> removed) {
        if (!BedrockSkrypciarz.wants("dataDrivenEntityTrigger", false)) return;
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(e));
        j.addProperty("eventId", event);
        JsonObject mod = new JsonObject();
        com.google.gson.JsonArray a = new com.google.gson.JsonArray(), r = new com.google.gson.JsonArray();
        added.forEach(a::add);
        removed.forEach(r::add);
        mod.add("addedComponentGroups", a);
        mod.add("removedComponentGroups", r);
        com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
        mods.add(mod);
        j.add("modifiers", mods);
        BedrockSkrypciarz.fireAfter("dataDrivenEntityTrigger", j);
    }

    private static JsonObject vec(net.minecraft.world.phys.Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x);
        o.addProperty("y", v.y);
        o.addProperty("z", v.z);
        return o;
    }

    public static void died(LivingEntity dead, DamageSource src) {
        if (BedrockSkrypciarz.wants("entityDie", false)) {
            JsonObject j = new JsonObject();
            j.add("deadEntity", BedrockPytajnik.entRef(dead));
            j.add("damageSource", damageSource(src));
            BedrockSkrypciarz.fireAfter("entityDie", j);
        }
        if (!(dead instanceof Player) && BedrockSkrypciarz.server() != null && BedrockSkrypciarz.anyAddons())
            BedrockSkrzynka.forget(BedrockSkrypciarz.server(), dead.getStringUUID());
    }

    public static void spawned(Entity e) {
        if (e instanceof Player || !BedrockSkrypciarz.wants("entitySpawn", false)) return;
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(e));
        j.addProperty("cause", "Spawned");
        BedrockSkrypciarz.fireAfter("entitySpawn", j);
    }

    // chunk load brings a saved mob back. a fresh spawn goes through addFreshEntity and is entitySpawn
    // instead, the mixin flags it so the two never both fire for one mob
    public static final ThreadLocal<Boolean> SWIEZAK = ThreadLocal.withInitial(() -> false);

    public static void loaded(Entity e) {
        if (SWIEZAK.get() || e instanceof Player || e.level().isClientSide() || !BedrockSkrypciarz.wants("entityLoad", false)) return;
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(e));
        BedrockSkrypciarz.fireAfter("entityLoad", j);
    }

    // unload or removal, bedrock does not tell those apart either
    public static void removing(Entity e) {
        if (e instanceof Player || e.level().isClientSide() || !onServerThread()) return;
        BedrockSkrypciarz.gone(e.getUUID());
        BedrockPytajnik.REMOVED_THIS_TICK.put(e.getUUID(), e);
        if (BedrockSkrypciarz.wants("entityRemove", true)) {
            JsonObject b = new JsonObject();
            b.add("removedEntity", BedrockPytajnik.entRef(e));
            BedrockPytajnik.ZEGNANY.put(e.getUUID(), e);
            try { BedrockSkrypciarz.fireBefore("entityRemove", b); }
            finally { BedrockPytajnik.ZEGNANY.remove(e.getUUID()); }
        }
        if (BedrockSkrypciarz.wants("entityRemove", false)) {
            JsonObject j = new JsonObject();
            j.addProperty("removedEntityId", e.getStringUUID());
            j.addProperty("typeId", com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()));
            BedrockSkrypciarz.fireAfter("entityRemove", j);
        }
    }

    // true = cancelled
    public static boolean beforePickup(Entity who, net.minecraft.world.entity.item.ItemEntity item) {
        if (udawany(who)) return false;
        if (who.level().isClientSide() || !BedrockSkrypciarz.wants("entityItemPickup", true)) return false;
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(who));
        j.add("item", BedrockPytajnik.entRef(item));
        return BedrockSkrypciarz.fireBefore("entityItemPickup", j);
    }

    public static void afterPickup(Entity who, ItemStack got) {
        if (udawany(who)) return;
        if (got.isEmpty() || who.level().isClientSide() || !BedrockSkrypciarz.wants("entityItemPickup", false)) return;
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(who));
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        items.add(BedrockPytajnik.itemRef(got));
        j.add("items", items);
        BedrockSkrypciarz.fireAfter("entityItemPickup", j);
    }

    public static void dropped(Entity who, net.minecraft.world.entity.item.ItemEntity item) {
        if (udawany(who)) return;
        if (item == null || who.level().isClientSide() || !BedrockSkrypciarz.wants("entityItemDrop", false)) return;
        JsonObject j = new JsonObject();
        j.add("entity", BedrockPytajnik.entRef(who));
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        items.add(BedrockPytajnik.entRef(item));
        j.add("items", items);
        BedrockSkrypciarz.fireAfter("entityItemDrop", j);
    }

    public static void buttonPushed(net.minecraft.world.level.Level l, BlockPos pos, Entity source) {
        if (l.isClientSide() || !BedrockSkrypciarz.wants("buttonPush", false)) return;
        JsonObject j = new JsonObject();
        j.add("block", BedrockPytajnik.blockRef(l, pos));
        j.add("dimension", BedrockPytajnik.dimRef(l));
        if (source != null) j.add("source", BedrockPytajnik.entRef(source));
        BedrockSkrypciarz.fireAfter("buttonPush", j);
    }

    // playerInventoryItemChange: a snapshot per player, diffed once a tick while somebody listens.
    // 36 ItemStack.matches per player per tick, only paid when an addon asked for it
    private static final java.util.Map<java.util.UUID, ItemStack[]> PLECAKI = new java.util.HashMap<>();

    // weatherChange: java has no event for it, so every level's weather is compared once a tick
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, String> POGODA = new java.util.HashMap<>();

    static void weatherTick(net.minecraft.server.MinecraftServer s) {
        if (!BedrockSkrypciarz.wants("weatherChange", false)) { POGODA.clear(); return; }
        for (ServerLevel l : s.getAllLevels()) {
            String now = l.isThundering() ? "Thunder" : l.isRaining() ? "Rain" : "Clear";
            String was = POGODA.put(l.dimension(), now);
            if (was == null || was.equals(now)) continue;
            JsonObject j = new JsonObject();
            j.addProperty("dimension", BedrockPytajnik.dimName(l));
            j.addProperty("newWeather", now);
            j.addProperty("previousWeather", was);
            j.addProperty("raining", l.isRaining());
            j.addProperty("lightning", l.isThundering());
            BedrockSkrypciarz.fireAfter("weatherChange", j);
        }
    }

    static void inventoryTick(net.minecraft.server.MinecraftServer s) {
        if (!BedrockSkrypciarz.wants("playerInventoryItemChange", false)) { PLECAKI.clear(); return; }
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            seen.add(p.getUUID());
            var inv = p.getInventory();
            int n = inv.getNonEquipmentItems().size();
            ItemStack[] was = PLECAKI.get(p.getUUID());
            boolean first = was == null || was.length != n;
            if (first) was = new ItemStack[n];
            for (int i = 0; i < n; i++) {
                ItemStack now = inv.getNonEquipmentItems().get(i);
                ItemStack before = was[i];
                if (!first && !ItemStack.matches(before == null ? ItemStack.EMPTY : before, now)) {
                    JsonObject j = new JsonObject();
                    j.add("player", BedrockPytajnik.entRef(p));
                    j.addProperty("slot", i);
                    j.addProperty("inventoryType", i < 9 ? "Hotbar" : "Inventory");
                    if (!now.isEmpty()) j.add("itemStack", BedrockPytajnik.itemRef(now));
                    if (before != null && !before.isEmpty()) j.add("beforeItemStack", BedrockPytajnik.itemRef(before));
                    BedrockSkrypciarz.fireAfter("playerInventoryItemChange", j);
                }
                was[i] = now.copy();
            }
            PLECAKI.put(p.getUUID(), was);
        }
        PLECAKI.keySet().retainAll(seen);
    }

    // fake players (mods, gametests, kontra interactions) are no players to a bedrock addon: they are in no
    // level and no player list, so every script touching one got "entity is gone"
    static boolean udawany(Entity e) {
        return e instanceof net.fabricmc.fabric.api.entity.FakePlayer;
    }

    // on 26.3 fabric's JOIN comes before the player is in the player list or its level. bedrock's
    // playerSpawn(initialSpawn) is a player standing in the world, so it waits for the next tick
    private static final java.util.List<ServerPlayer> CZEKAJACY = new java.util.ArrayList<>();

    static void joinTick(net.minecraft.server.MinecraftServer s) {
        if (CZEKAJACY.isEmpty()) return;
        for (ServerPlayer p : CZEKAJACY.toArray(new ServerPlayer[0])) {
            if (p.hasDisconnected()) { CZEKAJACY.remove(p); continue; }
            if (s.getPlayerList().getPlayer(p.getUUID()) != p || p.level().getEntity(p.getUUID()) != p) continue;
            CZEKAJACY.remove(p);
            JsonObject j = new JsonObject();
            j.addProperty("playerId", p.getStringUUID());
            j.addProperty("playerName", p.getGameProfile().name());
            BedrockSkrypciarz.fireAfter("playerJoin", j);
            JsonObject sp = new JsonObject();
            sp.add("player", BedrockPytajnik.entRef(p));
            sp.addProperty("initialSpawn", true);
            BedrockSkrypciarz.fireAfter("playerSpawn", sp);
        }
    }

    public static void respawned(ServerPlayer p) {
        if (udawany(p)) return;
        if (!BedrockSkrypciarz.wants("playerSpawn", false)) return;
        JsonObject j = new JsonObject();
        j.add("player", BedrockPytajnik.entRef(p));
        j.addProperty("initialSpawn", false);
        BedrockSkrypciarz.fireAfter("playerSpawn", j);
    }

    static boolean onServerThread() {
        var s = BedrockSkrypciarz.server();
        return s != null && s.isSameThread();
    }

    public static boolean onServerThread(ServerLevel l) {
        return l.getServer().isSameThread();
    }
}
