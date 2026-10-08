package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.KoperActions;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.data.KoperEntityData;
import com.koper.koper_lib.loader.ContentRegistry;
import com.koper.koper_lib.scripting.JavaHookRegistry;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class EntityFactory {

    private static final Map<Identifier, EntityType<?>> REGISTERED_ENTITIES = new HashMap<>();
    private static final Map<Identifier, KoperEntityData> ENTITY_DATA = new HashMap<>();

    public static Map<Identifier, EntityType<?>> getRegisteredEntities() {
        return REGISTERED_ENTITIES;
    }

    // accepts "#ff8800", "0xff8800", "16746496" or a plain decimal string
    private static Integer colorOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            if (s.startsWith("#"))  return Integer.parseInt(s.substring(1), 16) & 0xFFFFFF;
            if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16) & 0xFFFFFF;
            return Integer.decode(s) & 0xFFFFFF;
        } catch (NumberFormatException bad) {
            KoperLib.LOGGER.warn("[EntityFactory] spawn egg colour '{}' isn't a colour, ignoring", raw);
            return null;
        }
    }

    public static KoperEntityData dataFor(net.minecraft.world.entity.EntityType<?> type) {
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id == null ? null : ENTITY_DATA.get(id);
    }

    public static Map<Identifier, KoperEntityData> getEntityData() {
        return ENTITY_DATA;
    }

    public static void clearReloadData() {
        ENTITY_DATA.clear();
    }

    public static void createAndRegister(JsonObject json) {
        KoperEntityData merged = FactoryUtils.prepare(new KoperEntityData(), json);

        if (merged.id == null) {
            KoperLib.LOGGER.warn("[EntityFactory] json has no id and none could be inferred from the filename");
            return;
        }
        Identifier entityId = Identifier.tryParse(merged.id);
        if (entityId == null) {
            KoperLib.LOGGER.warn("[EntityFactory] bad or missing id '{}' — entity skipped", merged.id);
            return;
        }
        boolean alreadyRegistered = BuiltInRegistries.ENTITY_TYPE.containsKey(entityId);

        float width = json.has("width") ? json.get("width").getAsFloat() : 0.6f;
        float entityHeight = json.has("height") ? json.get("height").getAsFloat() : 1.8f;

        MobCategory group = "HOSTILE".equalsIgnoreCase(merged.aiType) ? MobCategory.MONSTER : MobCategory.CREATURE;
        ResourceKey<EntityType<?>> entityKey = ResourceKey.create(Registries.ENTITY_TYPE, entityId);

        List<String> aiGoals = new ArrayList<>();
        if (json.has("entity_ai") && json.get("entity_ai").isJsonArray()) {
            json.getAsJsonArray("entity_ai").forEach(e -> aiGoals.add(e.getAsString()));
        }

        if (aiGoals.isEmpty()) {
            aiGoals.add("swim");
            aiGoals.add("look_around");
            aiGoals.add("look_at_player");
            if ("HOSTILE".equalsIgnoreCase(merged.aiType)) {
                aiGoals.add("attack_melee");
                aiGoals.add("target_nearest_player");
                aiGoals.add("wander");
            } else if ("NEUTRAL".equalsIgnoreCase(merged.aiType)) {
                aiGoals.add("wander");
            } else {
                aiGoals.add("wander");
            }
        }

        boolean burnsDaylight = Boolean.TRUE.equals(merged.burnsInDaylight);

        boolean entityPersistent = Boolean.TRUE.equals(merged.persistent);
        boolean entityNoAi = Boolean.TRUE.equals(merged.noAi);
        boolean entityBaby = Boolean.TRUE.equals(merged.baby);
        String entityTamingItem = merged.tamingItem;
        boolean entityRideable = Boolean.TRUE.equals(merged.rideable);
        float entityRideSpeed = merged.rideSpeed != null ? merged.rideSpeed : 0.2f;

        if (!alreadyRegistered) {
            EntityType<PathfinderMob> entityType = EntityType.Builder.<PathfinderMob>of(
                (type, level) -> new KoperMobEntity(type, level, merged.id, merged.logic, merged.scripts, aiGoals, burnsDaylight,
                        entityPersistent, entityNoAi, entityBaby, entityTamingItem, entityRideable, entityRideSpeed, merged.events),
                group
            ).sized(width, entityHeight).build(entityKey);

            ContentRegistry.registerEntity(merged.id, entityType);
            REGISTERED_ENTITIES.put(entityId, entityType);

            double effectiveHealth = merged.maxHealth * com.koper.koper_lib.fullpack.config.FullpackConfig.get().globalHealthMultiplier;
            AttributeSupplier.Builder attributes = Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, effectiveHealth)
                    .add(Attributes.MOVEMENT_SPEED, merged.movementSpeed)
                    // TemptGoal reads this every tick and a mob without it takes the whole server down
                    .add(Attributes.TEMPT_RANGE);

            // always there: a bedrock mob gets its melee goal later from a component group (and its damage from
            // minecraft:attack), and a mob swinging without this attribute takes the whole server down
            double effectiveDamage = merged.attackDamage * com.koper.koper_lib.fullpack.config.FullpackConfig.get().globalDamageMultiplier;
            attributes.add(Attributes.ATTACK_DAMAGE, Math.max(0, effectiveDamage));
            if (merged.followRange != null && merged.followRange > 0) {
                attributes.add(Attributes.FOLLOW_RANGE, merged.followRange);
            }
            if (merged.armor != null && merged.armor > 0) {
                attributes.add(Attributes.ARMOR, merged.armor);
            }
            addCustomAttributes(attributes, merged, entityId);

            FabricDefaultAttributeRegistry.register(entityType, attributes);

            // "has_spawn_egg": false for helper entities nobody should place by hand (bedrock's is_spawnable)
            boolean egg = !json.has("has_spawn_egg") || json.get("has_spawn_egg").getAsBoolean();
            String eggId = entityId.getNamespace() + ":" + entityId.getPath() + "_spawn_egg";
            if (egg) {

            Item.Properties eggSettings = ContentRegistry.createItemSettings(eggId);
            Item spawnEgg = new KoperSpawnEggItem(eggSettings, entityType);
            String tab = FactoryUtils.tabOr(merged.creativeTab, entityId.getNamespace());
            ContentRegistry.registerItem(eggId, spawnEgg, tab);

            
            Identifier eggItemId = Identifier.tryParse(eggId);
            boolean ownTexture = merged.spawnEggTexture != null && !merged.spawnEggTexture.isEmpty();
            Integer primary = colorOf(merged.spawnEggPrimary);
            Integer secondary = colorOf(merged.spawnEggSecondary);

            // an explicit texture always wins; otherwise two colours build the egg for you
            if (!ownTexture && (primary != null || secondary != null)) {
                int a = primary != null ? primary : 0xFFFFFF;
                int b = secondary != null ? secondary : a;
                KoperLib.VIRTUAL_PACK.addSpawnEggModel(eggItemId, a, b);
            } else {
                String eggTexture = ownTexture ? merged.spawnEggTexture
                        : entityId.getNamespace() + ":item/" + entityId.getPath() + "_spawn_egg";
                KoperLib.VIRTUAL_PACK.addPresetModel(eggItemId, eggTexture, "item");
            }
            }

            KoperLib.LOGGER.info("EntityFactory: Registered entity: " + merged.id + " (AI: " + merged.aiType + ")");
        }

        ENTITY_DATA.put(entityId, merged);

        
        String eggTranslation = "item." + entityId.getNamespace() + "." + entityId.getPath() + "_spawn_egg";
        String entityName = json.has("name") ? json.get("name").getAsString() : entityId.getPath().replace("_", " ");
        entityName = FactoryUtils.capitalizeWords(entityName);
        KoperLib.VIRTUAL_PACK.addTranslation(eggTranslation, entityName + " Spawn Egg");

        String entityTransKey = "entity." + entityId.getNamespace() + "." + entityId.getPath();
        String displayName = json.has("name") ? json.get("name").getAsString() : FactoryUtils.capitalizeWords(entityId.getPath());
        FactoryUtils.emitLang(json, entityTransKey, null, displayName, null);
    }


    public static class KoperMobEntity extends PathfinderMob implements com.koper.koper_lib.api.core.KoperDelayedAttacker {
        // KoperDelayedAttackGoal swinging right now, synced: the client plays the pack's attack on q.is_delayed_attacking
        private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DELAYED =
            net.minecraft.network.syncher.SynchedEntityData.defineId(KoperMobEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

        public void setDelayedAttacking(boolean on) {
            if (this.entityData.get(DELAYED) != on) this.entityData.set(DELAYED, on);
        }

        @Override
        public boolean koperDelayedAttacking() {
            return this.entityData.get(DELAYED);
        }

        // the hitbox a bedrock pack's current component groups ask for (collision_box), -1 = the type's own.
        // synced: the client picks what the crosshair hits from it. a hidden foliaath kept its full size
        // underground, ate the hits meant for the sheep next to it and took no damage itself
        private static final net.minecraft.network.syncher.EntityDataAccessor<Float> BOX_W =
            net.minecraft.network.syncher.SynchedEntityData.defineId(KoperMobEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
        private static final net.minecraft.network.syncher.EntityDataAccessor<Float> BOX_H =
            net.minecraft.network.syncher.SynchedEntityData.defineId(KoperMobEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);

        @Override
        protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder data) {
            super.defineSynchedData(data);
            data.define(BOX_W, -1f);
            data.define(BOX_H, -1f);
            data.define(DELAYED, false);
        }

        // bedrock mobs leave the world the bedrock way (minecraft:despawn and its filters), not java's 32/128 block
        // rule: villager news' villagers and rlcraft's bosses vanished under java while scripts still held them
        @Override
        public void checkDespawn() {
            if (com.koper.koper_lib.bedrock.BedrockZachowanie.ma(this)) {
                if (this.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL && !this.getType().isAllowedInPeaceful()) this.discard();
                else com.koper.koper_lib.bedrock.BedrockZachowanie.checkDespawn(this);
                return;
            }
            super.checkDespawn();
        }

        public void setBox(float w, float h) {
            if (this.entityData.get(BOX_W) == w && this.entityData.get(BOX_H) == h) return;
            this.entityData.set(BOX_W, w);
            this.entityData.set(BOX_H, h);
            this.refreshDimensions();
        }

        @Override
        public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
            super.onSyncedDataUpdated(key);
            if (BOX_W.equals(key) || BOX_H.equals(key)) this.refreshDimensions();
        }

        @Override
        protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
            float w = this.entityData.get(BOX_W), h = this.entityData.get(BOX_H);
            if (w < 0 || h < 0) return super.getDefaultDimensions(pose);
            return net.minecraft.world.entity.EntityDimensions.scalable(w, h).scale(this.getAgeScale());
        }

        private final String hookId;
        private final String logicScript;
        private final List<String> scripts;
        private final List<String> aiGoals;
        private final boolean burnsDaylight;
        private final boolean persistent;
        private final boolean noAi;
        private final boolean baby;
        private final String tamingItem;
        private final boolean rideable;
        private final float rideSpeed;
        private final JsonObject events;

        public KoperMobEntity(EntityType<? extends PathfinderMob> type, net.minecraft.world.level.Level Level,
                              String hookId, String logicScript, List<String> scripts, List<String> aiGoals,
                              boolean burnsDaylight, boolean persistent, boolean noAi,
                              boolean baby, String tamingItem, boolean rideable, float rideSpeed, JsonObject events) {
            super(type, Level);
            this.hookId = hookId;
            this.logicScript = logicScript;
            this.scripts = scripts != null ? scripts : List.of();
            this.aiGoals = aiGoals != null ? aiGoals : List.of();
            this.burnsDaylight = burnsDaylight;
            this.persistent = persistent;
            this.noAi = noAi;
            this.baby = baby;
            this.tamingItem = tamingItem;
            this.rideable = rideable;
            this.rideSpeed = rideSpeed;
            this.events = events;

            this.activeGoals = this.aiGoals;
            setupGoals();
        }

        // bedrock behavior packs change a mob's goals when component groups come and go
        private List<String> activeGoals;

        public void rebuildGoals(List<String> goals) {
            this.goalSelector.removeAllGoals(g -> true);
            this.targetSelector.removeAllGoals(g -> true);
            this.activeGoals = goals;
            setupGoals();
        }

        @Override
        public void startSeenByPlayer(net.minecraft.server.level.ServerPlayer player) {
            super.startSeenByPlayer(player);
            com.koper.koper_lib.bedrock.BedrockZachowanie.seenBy(this, player);
        }

        @Override
        protected void registerGoals() {

        }

        // goals take args now: "wander:1.2", "look_at_player:24", "script_goal:10".
        // bare names keep the old defaults, so every existing pack reads the same
        private void setupGoals() {
            if (this.activeGoals == null || this.activeGoals.isEmpty()) return;
            int priority = 0;
            for (String entry : activeGoals) {
                String[] bits = entry.trim().split(":");
                String goal = bits[0].toLowerCase();
                switch (goal) {
                    case "swim" -> this.goalSelector.addGoal(priority++, new FloatGoal(this));
                    case "attack_melee" -> this.goalSelector.addGoal(priority++,
                            new MeleeAttackGoal(this, num(bits, 1, 1.0), bool(bits, 2, false)));
                    // attack_delayed:speed:seconds:hitPct:reach:once:track (bedrock's behavior.delayed_attack)
                    case "attack_delayed" -> this.goalSelector.addGoal(priority++, new KoperDelayedAttackGoal(this,
                            num(bits, 1, 1.0), (float) num(bits, 2, 0.5), (float) num(bits, 3, 0.5), num(bits, 4, 1.0), bool(bits, 5, false), bool(bits, 6, true)));
                    // a melee group with reach_multiplier 0: walk to / face the target, never hit (bedrock bosses idling)
                    case "chase_only" -> this.goalSelector.addGoal(priority++, new KoperDelayedAttackGoal(this,
                            num(bits, 1, 1.0), 1f, 0f, 0, false, true));
                    case "wander" -> this.goalSelector.addGoal(priority++,
                            new WaterAvoidingRandomStrollGoal(this, num(bits, 1, 0.8)));
                    case "look_at_player" -> this.goalSelector.addGoal(priority++,
                            new LookAtPlayerGoal(this, Player.class, (float) num(bits, 1, 8.0)));
                    case "look_around" -> this.goalSelector.addGoal(priority++, new RandomLookAroundGoal(this));
                    case "flee_player" -> this.goalSelector.addGoal(priority++,
                            new AvoidEntityGoal<>(this, Player.class, (float) num(bits, 1, 6.0),
                                    num(bits, 2, 1.0), num(bits, 3, 1.2)));
                    case "follow_player" -> this.goalSelector.addGoal(priority++,
                            new LookAtPlayerGoal(this, Player.class, (float) num(bits, 1, 16.0)));
                    case "target_nearest_player" -> this.targetSelector.addGoal(priority++,
                            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                                    this, Player.class, bool(bits, 1, true)));
                    case "revenge" -> this.targetSelector.addGoal(priority++,
                            new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
                    // bedrock behavior packs (BedrockZachowanie.goals)
                    case "panic" -> this.goalSelector.addGoal(priority++, new PanicGoal(this, num(bits, 1, 1.25)));
                    case "leap" -> this.goalSelector.addGoal(priority++, new LeapAtTargetGoal(this, (float) num(bits, 1, 0.4)));
                    case "swim_random" -> this.goalSelector.addGoal(priority++, new RandomSwimmingGoal(this, num(bits, 1, 1.0), 10));
                    case "restrict_sun" -> this.goalSelector.addGoal(priority++, new RestrictSunGoal(this));
                    case "flee_sun" -> this.goalSelector.addGoal(priority++, new FleeSunGoal(this, num(bits, 1, 1.0)));
                    case "tempt" -> {
                        // "tempt:1.2:minecraft~wheat,minecraft~carrot"
                        java.util.Set<String> want = new java.util.HashSet<>();
                        if (bits.length > 2) for (String it : bits[2].split(",")) want.add(it.replace('~', ':'));
                        this.goalSelector.addGoal(priority++, new TemptGoal(this, num(bits, 1, 1.0),
                            stack -> want.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()), false));
                    }
                    case "ranged" -> this.goalSelector.addGoal(priority++, new com.koper.koper_lib.bedrock.BedrockStrzelec(this,
                        num(bits, 1, 1.0), (int) num(bits, 2, 40), (float) num(bits, 3, 15)));
                    case "target_bedrock" -> this.targetSelector.addGoal(priority++,
                        new com.koper.koper_lib.bedrock.BedrockCelownik(this, bool(bits, 1, true)));
                    case "script_goal", "script" -> {
                        var data = com.koper.koper_lib.factory.EntityFactory.dataFor(this.getType());
                        if (data == null) KoperLib.LOGGER.warn("script_goal on {} but no entity data", this.getType());
                        else this.goalSelector.addGoal(priority++,
                                new com.koper.koper_lib.entity.KoperScriptGoal(this, data, (int) num(bits, 1, 10)));
                    }
                    default -> KoperLib.LOGGER.warn("Unknown AI goal: " + entry);
                }
            }
        }

        private static double num(String[] bits, int i, double fallback) {
            if (bits.length <= i) return fallback;
            try { return Double.parseDouble(bits[i]); } catch (NumberFormatException bad) { return fallback; }
        }

        private static boolean bool(String[] bits, int i, boolean fallback) {
            if (bits.length <= i) return fallback;
            return Boolean.parseBoolean(bits[i]);
        }

        @Override
        public SpawnGroupData finalizeSpawn(ServerLevelAccessor levelAccessor,
                DifficultyInstance difficulty, EntitySpawnReason spawnReason,
                SpawnGroupData entityData) {
            SpawnGroupData result = super.finalizeSpawn(levelAccessor, difficulty, spawnReason, entityData);
            if (persistent) this.setPersistenceRequired();
            if (noAi) this.setNoAi(true);
            if (baby) this.setBaby(true);
            if (runEntityHook("on_spawn", null)) {
                callScripts(ScriptEvent.ON_SPAWN);
                runActions("on_spawn", null);
            }
            com.koper.koper_lib.bedrock.BedrockZachowanie.spawned(this);
            return result;
        }

        @Override
        public net.minecraft.world.InteractionResult mobInteract(Player player, InteractionHand InteractionHand) {
            if (!this.level().isClientSide()) {
                var bedrock = com.koper.koper_lib.bedrock.BedrockZachowanie.interact(this, player, InteractionHand);
                if (bedrock != null) return bedrock;
            }
            // Taming
            if (tamingItem != null && !this.level().isClientSide()) {
                ItemStack held = player.getItemInHand(InteractionHand);
                Identifier heldId = BuiltInRegistries.ITEM.getKey(held.getItem());
                if (heldId != null && heldId.toString().equals(tamingItem)) {
                    if (!player.getAbilities().instabuild) held.shrink(1);
                    this.setNoAi(false);
                    this.setPersistenceRequired();
                    if (!this.level().isClientSide()) {
                        this.level().broadcastEntityEvent(this, (byte) EntityEvent.LOVE_HEARTS);
                    }
                    return net.minecraft.world.InteractionResult.SUCCESS;
                }
            }
            // Riding
            if (rideable && !this.level().isClientSide()) {
                player.startRiding(this);
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            if (!this.level().isClientSide()) {
                if (runEntityHook("on_interact", player)) {
                    if (logicScript != null && !logicScript.isEmpty())
                        UniversalScriptEngine.call(logicScript, ScriptEvent.ON_INTERACT, this, player);
                    for (String script : scripts)
                        UniversalScriptEngine.call(script, ScriptEvent.ON_INTERACT, this, player);
                    runActions("on_interact", player instanceof LivingEntity le ? le : null);
                }
            }
            return super.mobInteract(player, InteractionHand);
        }

        @Override
        public boolean isPushable() {
            return !rideable;
        }

        @Override
        protected void tickRidden(Player controllingPlayer, Vec3 movementInput) {
            super.tickRidden(controllingPlayer, movementInput);
        }

        @Override
        public Vec3 getPassengerRidingPosition(net.minecraft.world.entity.Entity passenger) {
            return new Vec3(this.getX(), this.getY() + this.getBbHeight() * 0.75, this.getZ());
        }

        // ── voices from a bedrock pack's sounds.json (BedrockGlos), java's own otherwise ──
        @Override
        protected net.minecraft.sounds.SoundEvent getAmbientSound() {
            var s = com.koper.koper_lib.bedrock.BedrockGlos.dzwiek(this, "ambient");
            return s != null ? s : super.getAmbientSound();
        }

        @Override
        protected net.minecraft.sounds.SoundEvent getHurtSound(DamageSource src) {
            var s = com.koper.koper_lib.bedrock.BedrockGlos.dzwiek(this, "hurt");
            return s != null ? s : super.getHurtSound(src);
        }

        @Override
        protected net.minecraft.sounds.SoundEvent getDeathSound() {
            var s = com.koper.koper_lib.bedrock.BedrockGlos.dzwiek(this, "death");
            return s != null ? s : super.getDeathSound();
        }

        @Override
        protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
            var s = com.koper.koper_lib.bedrock.BedrockGlos.dzwiek(this, "step");
            if (s == null) { super.playStepSound(pos, state); return; }
            this.playSound(s, com.koper.koper_lib.bedrock.BedrockGlos.glosnosc(this, "step", 0.15f),
                com.koper.koper_lib.bedrock.BedrockGlos.ton(this, "step", 1f));
        }

        @Override
        protected float getSoundVolume() {
            return com.koper.koper_lib.bedrock.BedrockGlos.glosnosc(this, "ambient", super.getSoundVolume());
        }

        @Override
        public float getVoicePitch() {
            return com.koper.koper_lib.bedrock.BedrockGlos.ton(this, "ambient", super.getVoicePitch());
        }

        @Override
        public void tick() {
            super.tick();
            if (!this.level().isClientSide()) {
                // Disable custom mobs if config says so
                if (com.koper.koper_lib.fullpack.config.FullpackConfig.get().disableCustomMobs) {
                    this.discard();
                    return;
                }
                boolean isDay = this.level().dimensionType().hasSkyLight() && (this.level().getDefaultClockTime() % 24000 < 12000);
                if (burnsDaylight && isDay && !this.isInWater()) {
                    BlockPos pos = this.blockPosition();
                    if (this.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) <= pos.getY()) {
                        this.igniteForSeconds(8);
                    }
                }
                if (runEntityHook("on_tick", null)) {
                    callScripts(ScriptEvent.ON_TICK);
                    runActions("on_tick", null);
                }
                com.koper.koper_lib.bedrock.BedrockZachowanie.tick(this);
            }
        }

        @Override
        public boolean hurtServer(ServerLevel Level, DamageSource source, float amount) {
            amount *= com.koper.koper_lib.fullpack.config.FullpackConfig.get().globalDamageMultiplier;
            float bedrock = com.koper.koper_lib.bedrock.BedrockZachowanie.hurt(this, source, amount);
            if (bedrock < 0) return false;
            amount *= bedrock;
            Entity attacker = source.getEntity();
            if (runEntityHook("on_damage", attacker instanceof Player p ? p : null)) {
                if (logicScript != null && !logicScript.isEmpty()) {
                    UniversalScriptEngine.call(logicScript, ScriptEvent.ON_DAMAGE,
                        this, attacker instanceof LivingEntity la ? la : null, Level);
                }
                for (String script : scripts) {
                    UniversalScriptEngine.call(script, ScriptEvent.ON_DAMAGE,
                        this, attacker instanceof LivingEntity la ? la : null, Level);
                }
                runActions("on_damage", attacker instanceof LivingEntity la ? la : null);
            }
            return super.hurtServer(Level, source, amount);
        }

        @Override
        public void setTarget(LivingEntity target) {
            LivingEntity before = this.getTargetUnchecked();
            super.setTarget(target);
            LivingEntity after = this.getTargetUnchecked();
            if (!this.level().isClientSide() && before != after) com.koper.koper_lib.bedrock.BedrockZachowanie.targetChanged(this, before, after);
            if (this.level().isClientSide() || after == null || before == after) return;
            if (runEntityHook("on_target", after instanceof Player p ? p : null)) {
                if (logicScript != null && !logicScript.isEmpty()) {
                    UniversalScriptEngine.call(logicScript, ScriptEvent.ON_TARGET, this, after);
                }
                for (String script : scripts) {
                    UniversalScriptEngine.call(script, ScriptEvent.ON_TARGET, this, after);
                }
                runActions("on_target", after);
            }
        }

        @Override
        public void die(DamageSource damageSource) {
            super.die(damageSource);
            if (!this.level().isClientSide()) com.koper.koper_lib.bedrock.BedrockZachowanie.died(this, damageSource);
            if (!this.level().isClientSide() && runEntityHook("on_death", null)) {
                callScripts(ScriptEvent.ON_DEATH);
                runActions("on_death", damageSource.getEntity() instanceof LivingEntity le ? le : null);
            }
        }

        private boolean runEntityHook(String event, Player player) {
            if (hookId == null || hookId.isEmpty()) return true;
            KoperContext ctx = player != null
                ? KoperContext.ofEntityInteract(player, this)
                : KoperContext.ofEntity(this);
            return JavaHookRegistry.fireHook(hookId + "/" + event, ctx) == net.minecraft.world.InteractionResult.PASS;
        }

        private void callScripts(ScriptEvent event) {
            if (logicScript != null && !logicScript.isEmpty()) {
                UniversalScriptEngine.call(logicScript, event, this);
            }
            for (String script : scripts) {
                UniversalScriptEngine.call(script, event, this);
            }
        }

        private void runActions(String event, LivingEntity target) {
            if (events == null || !events.has(event) || this.level().isClientSide()) return;
            KoperActions.run(events.get(event), new KoperContext(
                    null, this, target, this.level(), ItemStack.EMPTY, this.blockPosition()),
                event, hookId);
        }
    }

    private static void addCustomAttributes(AttributeSupplier.Builder builder, KoperEntityData data, Identifier entityId) {
        if (data.attributes == null || data.attributes.isEmpty()) return;
        for (KoperEntityData.AttributeEntry entry : data.attributes) {
            Identifier attrId = normalizeAttributeId(entry.attribute);
            if (attrId == null) continue;
            var attribute = BuiltInRegistries.ATTRIBUTE.getOptional(attrId);
            if (attribute.isEmpty()) {
                KoperLib.LOGGER.warn("[EntityFactory] Unknown attribute '{}' on {}", entry.attribute, entityId);
                continue;
            }
            builder.add(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute.get()), entry.amount);
        }
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

}
