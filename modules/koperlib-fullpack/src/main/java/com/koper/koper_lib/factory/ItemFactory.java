package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperItemData;
import com.koper.koper_lib.loader.ContentRegistry;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import com.koper.koper_lib.scripting.ScriptEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Unit;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.koper.koper_lib.item.KoperSpearItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.enchantment.Enchantable;
import net.minecraft.world.item.enchantment.Repairable;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public class ItemFactory {
    public static void createAndRegister(JsonObject json) {
        KoperItemData merged = FactoryUtils.prepare(new KoperItemData(), json);

        if (merged.id == null) {
            KoperLib.LOGGER.warn("[ItemFactory] json has no id and none could be inferred from the filename");
            return;
        }
        Identifier id = Identifier.tryParse(merged.id);
        if (id == null) {
            KoperLib.LOGGER.warn("[ItemFactory] bad or missing id '{}' — item skipped", merged.id);
            return;
        }
        boolean alreadyRegistered = net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id);

        if (alreadyRegistered) {
            if (!com.koper.koper_lib.loader.FullpackTombstones.claimItem(id)) return;
            String tex = (merged.texture != null && !merged.texture.isEmpty()) ? merged.texture
                    : vanillaFallbackTexture(merged.type != null ? merged.type.toLowerCase() : "");
			emitItemModel(id, tex, modelTypeFor(merged.type, id.getPath()), merged.textureInHand, geoIcon(merged), merged.tint, merged.dyeable, merged);
            emitItemTranslation(id, "item." + id.getNamespace() + "." + id.getPath(), json);
            if (!merged.scripts.isEmpty()) {
                com.koper.koper_lib.scripting.ItemScriptRegistry.register(merged.id, merged.scripts);
            }
            String tab = FactoryUtils.tabOr(merged.creativeTab, id.getNamespace());
            com.koper.koper_lib.loader.CreativeTabRegistry.addToTab(tab, BuiltInRegistries.ITEM.getValue(id));
            KoperLib.LOGGER.debug("[ItemFactory] Item already registered, refreshed assets: {}", merged.id);
            return;
        }

        Item.Properties settings = ContentRegistry.createItemSettings(merged.id);

        if (merged.maxStack != null)
            settings = settings.stacksTo(merged.maxStack);

        // Equipment and ranged weapons should NOT stack
        if (merged.type != null) {
            String t = merged.type.toLowerCase();
            if (t.equals("sword") || t.equals("axe") || t.equals("pickaxe") || t.equals("shovel")
                    || t.equals("hoe") || t.equals("spear") || t.equals("trident") || t.equals("shield")
                    || t.equals("bow") || t.equals("crossbow")
                    || t.equals("armor") || t.equals("helmet") || t.equals("chestplate")
                    || t.equals("leggings") || t.equals("boots")) {
                settings = settings.stacksTo(1);
            }
        }

        if (merged.durability != null && merged.durability > 0) {
            settings = settings.durability(merged.durability);
        }
        if (merged.fireproof != null && merged.fireproof) {
            settings = settings.fireResistant();
        }
        if (merged.rarity != null) {
            settings = settings.rarity(merged.rarity);
        }

        if (merged.foodHunger != null && merged.foodSaturation != null) {
            FoodProperties.Builder foodBuilder = new FoodProperties.Builder()
                    .nutrition(merged.foodHunger)
                    .saturationModifier(merged.foodSaturation);
            if (Boolean.TRUE.equals(merged.alwaysEdible)) {
                foodBuilder.alwaysEdible();
            }
            settings = settings.component(DataComponents.FOOD, foodBuilder.build());
            Consumable.Builder consumable = Consumable.builder();
            addConsumeEffects(consumable, merged);
            settings = settings.component(DataComponents.CONSUMABLE, consumable.build());
        }

        if (Boolean.TRUE.equals(merged.glint)) {
            settings = settings.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }

        if (!merged.lore.isEmpty()) {
            // keys, not literals: the text itself lives in the lang files, one line per key.
            // a pack with no "lang" block still works — its written lore became the en_us entry
            String lorePrefix = loreKey(id);
            java.util.List<Component> loreTexts = new java.util.ArrayList<>();
            for (int i = 0; i < merged.lore.size(); i++)
                loreTexts.add(Component.translatable(lorePrefix + "." + i));
            settings = settings.component(DataComponents.LORE, new ItemLore(loreTexts));
        }

        if (Boolean.TRUE.equals(merged.unbreakable)) {
            settings = settings.component(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        }

        if (merged.customModelData != null) {
            settings = settings.component(DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(
                            java.util.List.of(merged.customModelData.floatValue()),
                            java.util.List.of(), java.util.List.of(), java.util.List.of()));
        }

        String itemType = merged.type != null ? merged.type.toLowerCase() : "";
        ToolMaterial toolMaterial = toolMaterialFor(merged, id);
        boolean isWeapon = itemType.equals("sword") || itemType.equals("axe")
                || itemType.equals("pickaxe") || itemType.equals("shovel") || itemType.equals("hoe")
                || itemType.equals("spear") || itemType.equals("trident");
        boolean isArmor = itemType.equals("armor") || itemType.equals("helmet")
                || itemType.equals("chestplate") || itemType.equals("leggings") || itemType.equals("boots");

        if (isWeapon) {
            float damage = merged.damage != null ? merged.damage.floatValue() : 1.0f;
            float speed = merged.attackSpeed != null ? merged.attackSpeed.floatValue() : -2.4f;
            settings = switch (itemType) {
                case "sword" -> settings.sword(toolMaterial, damage, speed);
                case "pickaxe" -> settings.pickaxe(toolMaterial, damage, speed);
                case "axe" -> settings.axe(toolMaterial, damage, speed);
                case "shovel" -> settings.shovel(toolMaterial, damage, speed);
                case "hoe" -> settings.hoe(toolMaterial, damage, speed);
                // KINETIC_WEAPON spear — jab/charge, not throwable
                // spear() params: f1=swing ticks (/20), f2=kineticDmgMult, f3=delayTicks, f4-f9=speed conditions
                // attack damage/speed are set by ToolMaterial inside spear() — we override after
                case "spear" -> {
                    float atkSpd = merged.attackSpeed != null ? merged.attackSpeed.floatValue() : -3.0f;
                    Item.Properties s = settings.spear(toolMaterial, 0.95f, 0.95f, 0.6f, 2.5f, 11.0f, 6.75f, 5.1f, 11.25f, 4.6f);
                    ItemAttributeModifiers.Builder sb = ItemAttributeModifiers.builder();
                    sb.add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID,
                            (double) damage, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                    sb.add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(net.minecraft.world.item.Item.BASE_ATTACK_SPEED_ID,
                            (double) atkSpd, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                    yield s.component(DataComponents.ATTRIBUTE_MODIFIERS, sb.build());
                }
                // throwable trident-like spear
                case "trident" -> {
                    ItemAttributeModifiers.Builder tb = ItemAttributeModifiers.builder();
                    tb.add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("koper_lib", "trident.damage." + id.getPath()),
                            damage, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                    float atkSpd2 = merged.attackSpeed != null ? merged.attackSpeed.floatValue() : -2.9f;
                    tb.add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("koper_lib", "trident.speed." + id.getPath()),
                            atkSpd2, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                    yield settings.component(DataComponents.ATTRIBUTE_MODIFIERS, tb.build());
                }
                default -> settings.sword(toolMaterial, damage, speed);
            };
            // Override durability if specified (tool methods set material default)
            if (merged.durability != null && merged.durability > 0) {
                settings = settings.durability(merged.durability);
            }
        } else if (isArmor && merged.defense != null) {
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
            EquipmentSlot armorSlot = detectArmorSlot(itemType, merged.slot);
            EquipmentSlotGroup modifierSlot = EquipmentSlotGroup.bySlot(armorSlot);

            builder.add(
                    Attributes.ARMOR,
                    new AttributeModifier(
                            Identifier.fromNamespaceAndPath("minecraft", "armor." + armorSlot.getName()),
                            merged.defense,
                            AttributeModifier.Operation.ADD_VALUE),
                    modifierSlot);

            if (merged.toughness != null && merged.toughness > 0) {
                builder.add(
                        Attributes.ARMOR_TOUGHNESS,
                        new AttributeModifier(
                                Identifier.fromNamespaceAndPath("minecraft", "armor_toughness." + armorSlot.getName()),
                                merged.toughness,
                                AttributeModifier.Operation.ADD_VALUE),
                        modifierSlot);
            }

            if (merged.knockbackResistance != null && merged.knockbackResistance > 0) {
                builder.add(
                        Attributes.KNOCKBACK_RESISTANCE,
                        new AttributeModifier(
                                Identifier.fromNamespaceAndPath("minecraft",
                                        "knockback_resistance." + armorSlot.getName()),
                                merged.knockbackResistance,
                                AttributeModifier.Operation.ADD_VALUE),
                        modifierSlot);
            }

            settings = settings.component(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
        }

        if (isArmor) {
            EquipmentSlot armorSlot2 = detectArmorSlot(itemType, merged.slot);
            String armorMaterial = deriveArmorMaterial(id);

            // geo armor MUST keep an asset or MC drops it from the render state (chestEquipment goes null);
            // KoperArmorVanillaSkipMixin hides the vanilla draw so only our geo model shows
            Identifier equipAssetId;
            if (merged.armorTextureLayer1 != null && !merged.armorTextureLayer1.isEmpty()) {
                equipAssetId = Identifier.fromNamespaceAndPath(id.getNamespace(), armorMaterial);
                KoperLib.VIRTUAL_PACK.addEquipmentModel(id.getNamespace(), armorMaterial,
                        merged.armorTextureLayer1, merged.armorTextureLayer2);
            } else {
                equipAssetId = Identifier.fromNamespaceAndPath("minecraft", "iron");
            }

            ResourceKey<EquipmentAsset> equipKey = ResourceKey.create(EquipmentAssets.ROOT_ID, equipAssetId);
            settings = settings.component(DataComponents.EQUIPPABLE,
                    Equippable.builder(armorSlot2).setAsset(equipKey).build());
        }

        // dyeable geo item: give it a default dye color so the geo render can tint by it (DyedItemColor)
        if (merged.dyeable) {
            int def = merged.tint != com.koper.koper_lib.api.FullpackColors.NONE ? (merged.tint & 0xFFFFFF) : 0xFFFFFF;
            settings = settings.component(DataComponents.DYED_COLOR,
					new net.minecraft.world.item.component.DyedItemColor(0xFF000000 | def));
        }

        if (merged.reach != null && !isWeapon && !isArmor) {
            ItemAttributeModifiers.Builder reachBuilder = ItemAttributeModifiers.builder();
            reachBuilder.add(
                    Attributes.ENTITY_INTERACTION_RANGE,
                    new AttributeModifier(
                            Identifier.fromNamespaceAndPath(id.getNamespace(), "reach." + id.getPath()),
                            merged.reach,
                            AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
            settings = settings.component(DataComponents.ATTRIBUTE_MODIFIERS, reachBuilder.build());
        }

        if (!merged.attributes.isEmpty()) {
            settings = settings.attributes(buildAttributeModifiers(merged, id, itemType, toolMaterial, isWeapon, isArmor));
        }

        settings = applyExtraComponents(settings, merged);

        if (merged.enchantability != null && merged.enchantability > 0) {
            settings = settings.component(DataComponents.ENCHANTABLE,
                    new Enchantable(merged.enchantability));
        }

        // the block with this id came first and already has its BlockItem: a second item object would stay
        // unregistered and the game dies at startup on it. the block's item stays, loudly
        Identifier zajete = Identifier.tryParse(merged.id);
        if (zajete != null && BuiltInRegistries.ITEM.getValue(zajete) instanceof net.minecraft.world.item.BlockItem
                && BuiltInRegistries.BLOCK.containsKey(zajete)) {
            KoperLib.LOGGER.warn("ItemFactory: item {} has the same id as a block that is already there, the block's item stays", merged.id);
            return;
        }
        Item customItem;
        String texturePath = (merged.texture != null && !merged.texture.isEmpty()) ? merged.texture : merged.preset;
        if (texturePath == null)
            texturePath = vanillaFallbackTexture(itemType);

        if ("portal_igniter".equalsIgnoreCase(merged.type)) {
            String dim = json.has("dimension") ? json.get("dimension").getAsString() : "minecraft:overworld";
            String frame = json.has("frame_block") ? json.get("frame_block").getAsString() : "minecraft:obsidian";
            customItem = new com.koper.koper_lib.item.PortalIgniterItem(settings, dim, frame);

            Identifier portalId = Identifier.fromNamespaceAndPath("koper_lib", dim.replace(":", "_") + "_portal");
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(portalId)) {
                BlockBehaviour.Properties blockSettings = ContentRegistry.createBlockSettings(portalId.toString())
                        .noCollision()
                        .lightLevel(state -> 11);
                com.koper.koper_lib.block.CustomPortalBlock portalBlock = new com.koper.koper_lib.block.CustomPortalBlock(
                        blockSettings, dim);
                ContentRegistry.registerBlock(portalId.toString(), portalBlock, null);
            }

            KoperLib.VIRTUAL_PACK.addPresetModel(id, texturePath, "item");
        } else {
            final String capturedType = merged.type != null ? merged.type.toLowerCase() : "";
            final java.util.List<String> capturedScripts = merged.scripts;
            final java.util.Map<String, Integer> capturedEnchants = merged.enchantments;
            final java.util.List<com.koper.koper_lib.data.KoperItemData.EffectEntry> capturedOnUseEffects = merged.onUseEffects;
            final java.util.List<com.koper.koper_lib.data.KoperItemData.EffectEntry> capturedOnHitEffects = merged.onHitEffects;
            final String capturedOnHitCommand = merged.onHitCommand;
            final Integer capturedCooldownTicks = merged.cooldownTicks;
            final Boolean capturedConsumeOnUse = merged.consumeOnUse;
            final Integer capturedDropXp = merged.rightClickDropXp;
            final Double capturedAoeRadius = merged.attackAoeRadius;
            final com.google.gson.JsonObject capturedEvents = merged.events;
            final String capturedOwnerId = merged.id;

            Item javaTypeItem = com.koper.koper_lib.api.KoperItemTypes.create(capturedType,
                    new com.koper.koper_lib.api.KoperItemBuildContext(id, merged, json, settings));

            if (javaTypeItem != null) {
                customItem = javaTypeItem;

            } else if ("spear".equals(capturedType)) {
                customItem = new KoperSpearItem(settings, capturedScripts);

            } else if ("trident".equals(capturedType)) {
                customItem = new com.koper.koper_lib.item.KoperThrowableSpearItem(settings, capturedScripts);

            } else if ("bow".equals(capturedType)) {
                customItem = new BowItem(settings) {
                    @Override
                    public boolean releaseUsing(ItemStack stack, Level Level, LivingEntity user,
                            int remainingUseTicks) {
                        boolean result = super.releaseUsing(stack, Level, user, remainingUseTicks);
                        if (!Level.isClientSide() && user instanceof Player player) {
                            for (String script : capturedScripts) {
                                UniversalScriptEngine.call(script, ScriptEvent.ON_USE, player, Level);
                            }
                            if (player instanceof net.minecraft.server.level.ServerPlayer sp && capturedEvents != null) {
                                com.koper.koper_lib.api.KoperActions.run(capturedEvents.get("on_use"),
                                        com.koper.koper_lib.api.KoperContext.ofUse(sp, stack, user.blockPosition()),
                                        "on_use", capturedOwnerId);
                            }
                        }
                        return result;
                    }
                };

            } else if ("crossbow".equals(capturedType)) {
                // crossbow needs CHARGED_PROJECTILES initialized or it won't load/fire
                settings = settings.component(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
                final int capturedDrawTicks = merged.drawTime != null ? (int) (merged.drawTime * 20) : 25;
                customItem = new CrossbowItem(settings) {
                    @Override
                    public int getUseDuration(ItemStack stack, LivingEntity user) {
                        return Math.max(capturedDrawTicks, getChargeDuration(stack, user));
                    }

                    @Override
                    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user,
                            int remainingUseTicks) {
                        boolean result = super.releaseUsing(stack, level, user, remainingUseTicks);
                        if (!level.isClientSide() && user instanceof Player player) {
                            for (String script : capturedScripts) {
                                UniversalScriptEngine.call(script, ScriptEvent.ON_USE, player, level);
                            }
                            if (player instanceof net.minecraft.server.level.ServerPlayer sp && capturedEvents != null) {
                                com.koper.koper_lib.api.KoperActions.run(capturedEvents.get("on_use"),
                                        com.koper.koper_lib.api.KoperContext.ofUse(sp, stack, user.blockPosition()),
                                        "on_use", capturedOwnerId);
                            }
                        }
                        return result;
                    }
                };

            } else if ("shield".equals(capturedType)) {
                // BLOCKS_ATTACKS handles all actual blocking in 26.2 — the old startUsingItem hack was fake
                if (merged.durability == null || merged.durability <= 0) {
                    settings = settings.durability(336);
                }
                settings = settings.equippableUnswappable(EquipmentSlot.OFFHAND);
                settings = settings.component(DataComponents.BREAK_SOUND, SoundEvents.SHIELD_BREAK);
                settings = settings.component(DataComponents.BLOCKS_ATTACKS,
                    new BlocksAttacks(
                        0.0f,
                        1.0f,
                        java.util.List.of(new BlocksAttacks.DamageReduction(
                            90.0f,
                            java.util.Optional.empty(),
                            0.0f,
                            1.0f
                        )),
                        BlocksAttacks.ItemDamageFunction.DEFAULT,
                        java.util.Optional.empty(),
                        java.util.Optional.of(SoundEvents.SHIELD_BLOCK),
                        java.util.Optional.of(SoundEvents.SHIELD_BREAK)
                    )
                );
                customItem = new ShieldItem(settings);

            } else {
                final String capturedHookId = id.toString();
                customItem = new Item(settings) {
                    @Override
                    public InteractionResult use(Level Level, Player user, InteractionHand InteractionHand) {
                        if (!Level.isClientSide()) {
                            if (user instanceof net.minecraft.server.level.ServerPlayer sp) {
                                var ctx = com.koper.koper_lib.api.KoperContext.ofUse(sp, user.getItemInHand(InteractionHand), user.blockPosition());
                                var hookResult = com.koper.koper_lib.scripting.JavaHookRegistry.fireHook(capturedHookId + "/on_use", ctx);
                                if (hookResult != InteractionResult.PASS) return hookResult;
                            }
                            for (String script : capturedScripts) {
                                UniversalScriptEngine.call(script, ScriptEvent.ON_USE, user, Level);
                            }
                            ItemFactory.applyEffects(capturedOnUseEffects, user);
                            if (capturedCooldownTicks != null && capturedCooldownTicks > 0) {
                                user.getCooldowns().addCooldown(user.getItemInHand(InteractionHand),
                                        capturedCooldownTicks);
                            }
                            if (capturedDropXp != null && capturedDropXp > 0) {
                                Level.addFreshEntity(new net.minecraft.world.entity.ExperienceOrb(
                                        Level, user.getX(), user.getY(), user.getZ(), capturedDropXp));
                            }
                            if (user instanceof net.minecraft.server.level.ServerPlayer sp && capturedEvents != null) {
                                var actionResult = com.koper.koper_lib.api.KoperActions.run(capturedEvents.get("on_use"),
                                        com.koper.koper_lib.api.KoperContext.ofUse(sp, user.getItemInHand(InteractionHand), user.blockPosition()),
                                        "on_use", capturedOwnerId);
                                if (actionResult != InteractionResult.PASS) return actionResult;
                            }
                        }
                        if (Boolean.TRUE.equals(capturedConsumeOnUse) && !user.isCreative()) {
                            ItemStack stack2 = user.getItemInHand(InteractionHand);
                            stack2.shrink(1);
                            return InteractionResult.SUCCESS;
                        }
                        return super.use(Level, user, InteractionHand);
                    }

                    @Override
                    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
                        super.hurtEnemy(stack, target, attacker);
                        if (!attacker.level().isClientSide() && attacker instanceof net.minecraft.server.level.ServerPlayer sp) {
                            var ctx = com.koper.koper_lib.api.KoperContext.ofHit(sp, target, stack);
                            com.koper.koper_lib.scripting.JavaHookRegistry.fireHook(capturedHookId + "/on_hit", ctx);
                            for (String script : capturedScripts) {
                                UniversalScriptEngine.call(script, ScriptEvent.ON_HIT, sp, target);
                            }
                        } else if (!attacker.level().isClientSide() && attacker instanceof Player player) {
                            for (String script : capturedScripts) {
                                UniversalScriptEngine.call(script, ScriptEvent.ON_HIT, player, target);
                            }
                        }
                        if (!attacker.level().isClientSide()) {
                            ItemFactory.applyEffects(capturedOnHitEffects, target);
                            if (capturedOnHitCommand != null && !capturedOnHitCommand.isEmpty()) {
                                String cmd = capturedOnHitCommand
                                        .replace("{target}", target.getDisplayName().getString())
                                        .replace("{player}", attacker.getDisplayName().getString())
                                        .replace("{target_uuid}", target.getStringUUID())
                                        .replace("{player_uuid}", attacker.getStringUUID());
                                net.minecraft.server.MinecraftServer srv = UniversalScriptEngine.getCurrentServer();
                                if (srv != null) {
                                    try {
                                        srv.getCommands().getDispatcher().execute(cmd, srv.createCommandSourceStack());
                                    } catch (Exception e) {
                                        KoperLib.LOGGER.warn("[ItemFactory] on_hit_command failed: {}", e.getMessage());
                                    }
                                }
                            }
                            if (capturedAoeRadius != null && capturedAoeRadius > 0
                                    && attacker.level() instanceof ServerLevel sw) {
                                float aoeDmg = (merged.damage != null ? merged.damage : 1) * 0.5f;
                                net.minecraft.world.phys.AABB aoeBox = attacker.getBoundingBox()
                                        .inflate(capturedAoeRadius);
                                sw.getEntitiesOfClass(LivingEntity.class, aoeBox,
                                        e -> e != attacker && e != target && e.isAlive())
                                        .forEach(e -> e.hurtServer(sw, sw.damageSources().mobAttack(attacker), aoeDmg));
                            }
                            if (attacker instanceof net.minecraft.server.level.ServerPlayer sp && capturedEvents != null) {
                                com.koper.koper_lib.api.KoperActions.run(capturedEvents.get("on_hit"),
                                        com.koper.koper_lib.api.KoperContext.ofHit(sp, target, stack),
                                        "on_hit", capturedOwnerId);
                            }
                        }
                    }

                    @Override
                    public ItemStack finishUsingItem(ItemStack stack, Level Level, LivingEntity user) {
                        ItemStack result = super.finishUsingItem(stack, Level, user);
                        if (!Level.isClientSide() && user instanceof net.minecraft.server.level.ServerPlayer eater) {
                            com.koper.koper_lib.scripting.JavaHookRegistry.fireHook(capturedHookId + "/on_consume",
                                com.koper.koper_lib.api.KoperContext.ofUse(eater, stack, user.blockPosition()));
                        }
                        if (!Level.isClientSide() && user instanceof Player player) {
                            for (String script : capturedScripts) {
                                UniversalScriptEngine.call(script, ScriptEvent.ON_CONSUME, player, Level);
                            }
                            if (player instanceof net.minecraft.server.level.ServerPlayer sp && capturedEvents != null) {
                                com.koper.koper_lib.api.KoperActions.run(capturedEvents.get("on_consume"),
                                        com.koper.koper_lib.api.KoperContext.ofUse(sp, stack, user.blockPosition()),
                                        "on_consume", capturedOwnerId);
                            }
                        }
                        return result;
                    }

                    @Override
                    public ItemStack getDefaultInstance() {
                        return applyEnchantments(super.getDefaultInstance(), capturedEnchants);
                    }
                };
            }

            // Asset Generation
			emitItemModel(id, texturePath, modelTypeFor(capturedType, id.getPath()), merged.textureInHand, geoIcon(merged), merged.tint, merged.dyeable, merged);
        }

        if (!alreadyRegistered) {
            ContentRegistry.registerItem(merged.id, customItem, merged.creativeTab);
            com.koper.koper_lib.loader.FullpackTombstones.noteFactoryItem(id, customItem);
            KoperLib.LOGGER.info("ItemFactory: Registered item: " + merged.id + " (" + merged.type + ")");
        }
        // store for KoperItemRef API — always update so reload picks up changed json
        ContentRegistry.storeItemData(merged.id, merged);

        if (!merged.scripts.isEmpty()) {
            com.koper.koper_lib.scripting.ItemScriptRegistry.register(merged.id, merged.scripts);
        }

        // alreadyRegistered is always false here (early-returned above) — use the item's own descriptionId
        emitItemTranslation(id, customItem.getDescriptionId(), json);
    }

    // model template from item type; tools fall back to id-path keywords, else flat "item"
    private static String modelTypeFor(String type, String idPath) {
        String t = type != null ? type.toLowerCase() : "";
        return switch (t) {
            case "trident"  -> "trident";
            case "spear"    -> "spear";
            case "bow"      -> "bow";
            case "crossbow" -> "crossbow";
            case "shield"   -> "shield";
            case "sword", "axe", "pickaxe", "shovel", "hoe" -> "handheld";
            case "khysics_wand", "khys_selection_wand", "khys_flight_stick" -> "handheld"; // held like a stick, not a sandwich
            default -> (idPath.contains("sword") || idPath.contains("axe") || idPath.contains("pickaxe")) ? "handheld" : "item";
        };
    }

    // geoIcon = show the 3D geo model as the item icon; else spear/trident two-part, else a flat preset sprite
	private static void emitItemModel(Identifier id, String texturePath, String modelType, String textureInHand,
			boolean geoIcon, int tint, boolean dyeable, KoperItemData merged) {
        if (geoIcon) {
            KoperLib.VIRTUAL_PACK.addGeoItemModel(id);
        } else if ("trident".equals(modelType) || "spear".equals(modelType)) {
            String inHand = (textureInHand != null && !textureInHand.isEmpty()) ? textureInHand : null;
            KoperLib.VIRTUAL_PACK.addSpearModels(id, texturePath, inHand);
        } else if (merged != null && !merged.itemStates.isEmpty()) {
            KoperLib.VIRTUAL_PACK.addStatefulItemModel(id, texturePath, modelType,
                    merged.itemStates, merged.defaultState, tint, dyeable);
        } else {
			KoperLib.VIRTUAL_PACK.addPresetModel(id, texturePath, modelType, tint, dyeable);
        }
    }

    // item_display == "model" and a geo model is set -> render the model as the icon
    private static boolean geoIcon(KoperItemData m) {
        return "model".equals(m.itemDisplay) && m.model != null && !m.model.isBlank();
    }

    private static void emitItemTranslation(Identifier id, String translationKey, JsonObject json) {
        String name = json.has("name") ? json.get("name").getAsString() : FactoryUtils.capitalizeWords(id.getPath());
        java.util.List<String> lore = new java.util.ArrayList<>();
        if (json.has("lore") && json.get("lore").isJsonArray())
            json.getAsJsonArray("lore").forEach(line -> lore.add(line.getAsString()));
        FactoryUtils.emitLang(json, translationKey, loreKey(id), name, lore);
    }

    static String loreKey(Identifier id) {
        return "item." + id.getNamespace() + "." + id.getPath() + ".lore";
    }

    static void applyEffects(java.util.List<KoperItemData.EffectEntry> effects,
            net.minecraft.world.entity.LivingEntity target) {
        if (effects == null || effects.isEmpty())
            return;
        for (var eff : effects) {
            if (Math.random() > eff.chance())
                continue;
            Identifier eid = Identifier.tryParse(eff.id());
            if (eid == null)
                continue;
            BuiltInRegistries.MOB_EFFECT.getOptional(eid).ifPresent(
                    eff2 -> target.addEffect(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(eff2),
                            eff.ticks(), eff.amplifier())));
        }
    }

    private static ItemStack applyEnchantments(ItemStack stack, java.util.Map<String, Integer> enchants) {
        if (enchants == null || enchants.isEmpty())
            return stack;
        net.minecraft.server.MinecraftServer server = com.koper.koper_lib.scripting.UniversalScriptEngine
                .getCurrentServer();
        if (server == null)
            return stack;
        var enchLookup = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (var entry : enchants.entrySet()) {
            Identifier enchId = Identifier.tryParse(entry.getKey());
            if (enchId == null)
                continue;
            enchLookup.get(ResourceKey.create(Registries.ENCHANTMENT, enchId))
                    .ifPresent(e -> stack.enchant(e, entry.getValue()));
        }
        return stack;
    }

    private static String vanillaFallbackTexture(String type) {
        return switch (type == null ? "" : type) {
            case "sword" -> "minecraft:item/iron_sword";
            case "pickaxe" -> "minecraft:item/iron_pickaxe";
            case "axe" -> "minecraft:item/iron_axe";
            case "shovel" -> "minecraft:item/iron_shovel";
            case "hoe" -> "minecraft:item/iron_hoe";
            case "bow" -> "minecraft:item/bow";
            case "crossbow" -> "minecraft:item/crossbow_standby";
            case "spear", "trident" -> "minecraft:item/trident";
            case "shield" -> "minecraft:item/iron_ingot"; // 26.3 has no flat shield sprite
            case "helmet" -> "minecraft:item/iron_helmet";
            case "chestplate", "armor" -> "minecraft:item/iron_chestplate";
            case "leggings" -> "minecraft:item/iron_leggings";
            case "boots" -> "minecraft:item/iron_boots";
            case "food" -> "minecraft:item/apple";
            case "gem" -> "minecraft:item/emerald";
            case "material" -> "minecraft:item/iron_ingot";
            case "portal_igniter" -> "minecraft:item/flint_and_steel";
            default -> "minecraft:item/iron_ingot";
        };
    }

    private static String deriveArmorMaterial(Identifier id) {
        String path = id.getPath();
        for (String suffix : new String[] { "_helmet", "_chestplate", "_leggings", "_boots", "_armor" }) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }

    private static EquipmentSlot detectArmorSlot(String itemType, String slotOverride) {
        if (slotOverride != null) {
            return switch (slotOverride.toLowerCase()) {
                case "head", "helmet" -> EquipmentSlot.HEAD;
                case "chest", "chestplate" -> EquipmentSlot.CHEST;
                case "legs", "leggings" -> EquipmentSlot.LEGS;
                case "feet", "boots" -> EquipmentSlot.FEET;
                default -> EquipmentSlot.CHEST;
            };
        }
        return switch (itemType) {
            case "helmet" -> EquipmentSlot.HEAD;
            case "chestplate" -> EquipmentSlot.CHEST;
            case "leggings" -> EquipmentSlot.LEGS;
            case "boots" -> EquipmentSlot.FEET;
            default -> EquipmentSlot.CHEST;
        };
    }

    private static ToolMaterial toolMaterialFor(KoperItemData data, Identifier itemId) {
        if (data != null && data.customToolTier != null) return customToolMaterial(data.customToolTier, itemId);
        String tier = data != null ? data.toolTier : null;
        if (tier == null || tier.isBlank()) return ToolMaterial.IRON;
        return switch (tier.toLowerCase()) {
            case "wood", "wooden" -> ToolMaterial.WOOD;
            case "stone" -> ToolMaterial.STONE;
            case "copper" -> ToolMaterial.COPPER;
            case "gold", "golden" -> ToolMaterial.GOLD;
            case "diamond" -> ToolMaterial.DIAMOND;
            case "netherite" -> ToolMaterial.NETHERITE;
            case "iron" -> ToolMaterial.IRON;
            default -> {
                KoperLib.LOGGER.warn("[ItemFactory] Unknown tool_tier '{}', using iron", tier);
                yield ToolMaterial.IRON;
            }
        };
    }

    private static ToolMaterial customToolMaterial(KoperItemData.ToolTierDef tier, Identifier itemId) {
        String baseName = safePath(tier.name != null ? tier.name : itemId.getPath() + "_tier");
        Identifier incorrectId = tier.incorrectForTag != null && !tier.incorrectForTag.isBlank()
                ? Identifier.tryParse(stripHash(tier.incorrectForTag))
                : Identifier.fromNamespaceAndPath(itemId.getNamespace(), "incorrect_for/" + baseName + "_tool");
        Identifier repairId = tier.repairTag != null && !tier.repairTag.isBlank()
                ? Identifier.tryParse(stripHash(tier.repairTag))
                : Identifier.fromNamespaceAndPath(itemId.getNamespace(), baseName + "_tool_materials");
        if (incorrectId == null) incorrectId = Identifier.fromNamespaceAndPath(itemId.getNamespace(), "incorrect_for/" + baseName + "_tool");
        if (repairId == null) repairId = Identifier.fromNamespaceAndPath(itemId.getNamespace(), baseName + "_tool_materials");

        java.util.List<String> incorrect = tier.incorrectFor.isEmpty()
                ? defaultIncorrectFor(tier.miningLevel)
                : tier.incorrectFor;
        for (String value : incorrect) {
            KoperLib.VIRTUAL_PACK.addServerTagValue(
                    Identifier.fromNamespaceAndPath(incorrectId.getNamespace(), "block/" + incorrectId.getPath()),
                    value);
        }
        for (String value : tier.repairItems) {
            KoperLib.VIRTUAL_PACK.addServerTagValue(
                    Identifier.fromNamespaceAndPath(repairId.getNamespace(), "item/" + repairId.getPath()),
                    value);
        }

        TagKey<Block> incorrectTag = TagKey.create(Registries.BLOCK, incorrectId);
        TagKey<Item> repairTag = TagKey.create(Registries.ITEM, repairId);
        return new ToolMaterial(
                incorrectTag,
                Math.max(1, tier.durability),
                Math.max(0.0f, tier.speed),
                tier.attackDamageBonus,
                Math.max(0, tier.enchantability),
                repairTag);
    }

    private static java.util.List<String> defaultIncorrectFor(int miningLevel) {
        return switch (miningLevel) {
            case 0 -> java.util.List.of("#minecraft:needs_stone_tool", "#minecraft:needs_iron_tool", "#minecraft:needs_diamond_tool");
            case 1 -> java.util.List.of("#minecraft:needs_iron_tool", "#minecraft:needs_diamond_tool");
            case 2 -> java.util.List.of("#minecraft:needs_diamond_tool");
            default -> java.util.List.of();
        };
    }

    private static String stripHash(String raw) {
        return raw != null && raw.startsWith("#") ? raw.substring(1) : raw;
    }

    private static String safePath(String raw) {
        return raw == null ? "custom" : raw.toLowerCase().replace(':', '_').replace('/', '_').replaceAll("[^a-z0-9_\\-.]", "_");
    }

    private static Item.Properties applyExtraComponents(Item.Properties settings, KoperItemData data) {
        if (data.useSeconds != null || data.useAnimation != null || data.useSound != null
                || data.useParticles != null || !data.consumeEffects.isEmpty()) {
            var consumable = Consumable.builder();
            if (data.useSeconds != null) consumable.consumeSeconds(Math.max(0.0f, data.useSeconds));
            if (data.useAnimation != null) consumable.animation(useAnimation(data.useAnimation));
            if (data.useSound != null) {
                SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.tryParse(data.useSound));
                if (sound != null) consumable.sound(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound));
                else KoperLib.LOGGER.warn("[ItemFactory] Unknown use_sound '{}'", data.useSound);
            }
            if (data.useParticles != null) consumable.hasConsumeParticles(data.useParticles);
            addConsumeEffects(consumable, data);
            settings = settings.component(DataComponents.CONSUMABLE, consumable.build());
        }
        if (data.useCooldownSeconds != null && data.useCooldownSeconds > 0) {
            Identifier group = data.cooldownGroup != null && !data.cooldownGroup.isBlank()
                    ? Identifier.tryParse(data.cooldownGroup) : null;
            settings = settings.component(DataComponents.USE_COOLDOWN,
                    new UseCooldown(data.useCooldownSeconds, java.util.Optional.ofNullable(group)));
        }
        if (data.useRemainder != null && !data.useRemainder.isBlank()) {
            Item item = item(data.useRemainder);
            if (item != null) settings = settings.component(DataComponents.USE_REMAINDER,
                    new UseRemainder(new ItemStackTemplate(item)));
            else KoperLib.LOGGER.warn("[ItemFactory] Unknown use_remainder '{}'", data.useRemainder);
        }
        if (!data.repairItems.isEmpty()) {
            java.util.List<net.minecraft.core.Holder<Item>> holders = new java.util.ArrayList<>();
            for (String raw : data.repairItems) {
                Item item = item(raw);
                if (item != null) holders.add(item.builtInRegistryHolder());
                else KoperLib.LOGGER.warn("[ItemFactory] Unknown repair_item '{}'", raw);
            }
            if (!holders.isEmpty()) settings = settings.component(DataComponents.REPAIRABLE,
                    new Repairable(net.minecraft.core.HolderSet.direct(holders)));
        }
        return settings;
    }

    private static void addConsumeEffects(Consumable.Builder consumable, KoperItemData data) {
        if (data.consumeEffects == null || data.consumeEffects.isEmpty()) return;
        for (var entry : data.consumeEffects) {
            Identifier eid = Identifier.tryParse(entry.id());
            if (eid == null) continue;
            var effect = BuiltInRegistries.MOB_EFFECT.getValue(eid);
            if (effect == null) {
                KoperLib.LOGGER.warn("[ItemFactory] Unknown consume effect '{}'", entry.id());
                continue;
            }
            consumable.onConsume(new ApplyStatusEffectsConsumeEffect(
                    new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect),
                            Math.max(1, entry.ticks()), Math.max(0, entry.amplifier())),
                    Math.max(0.0f, Math.min(1.0f, entry.chance()))));
        }
    }

    private static Item item(String raw) {
        Identifier id = Identifier.tryParse(raw);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    private static ItemUseAnimation useAnimation(String raw) {
        if (raw == null) return ItemUseAnimation.EAT;
        return switch (raw.toLowerCase()) {
            case "none" -> ItemUseAnimation.NONE;
            case "drink" -> ItemUseAnimation.DRINK;
            case "block" -> ItemUseAnimation.BLOCK;
            case "bow" -> ItemUseAnimation.BOW;
            case "trident" -> ItemUseAnimation.TRIDENT;
            case "crossbow" -> ItemUseAnimation.CROSSBOW;
            case "spyglass" -> ItemUseAnimation.SPYGLASS;
            case "toot_horn", "horn" -> ItemUseAnimation.TOOT_HORN;
            case "brush" -> ItemUseAnimation.BRUSH;
            case "bundle" -> ItemUseAnimation.BUNDLE;
            case "spear" -> ItemUseAnimation.SPEAR;
            default -> ItemUseAnimation.EAT;
        };
    }

    private static ItemAttributeModifiers buildAttributeModifiers(KoperItemData merged, Identifier id,
            String itemType, ToolMaterial material, boolean isWeapon, boolean isArmor) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        if (isWeapon) {
            addBaseWeaponAttributes(builder, merged, id, itemType, material);
        } else if (isArmor && merged.defense != null) {
            EquipmentSlot armorSlot = detectArmorSlot(itemType, merged.slot);
            EquipmentSlotGroup modifierSlot = EquipmentSlotGroup.bySlot(armorSlot);
            builder.add(Attributes.ARMOR,
                    new AttributeModifier(Identifier.fromNamespaceAndPath("minecraft", "armor." + armorSlot.getName()),
                            merged.defense, AttributeModifier.Operation.ADD_VALUE),
                    modifierSlot);
            if (merged.toughness != null && merged.toughness > 0) {
                builder.add(Attributes.ARMOR_TOUGHNESS,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("minecraft", "armor_toughness." + armorSlot.getName()),
                                merged.toughness, AttributeModifier.Operation.ADD_VALUE),
                        modifierSlot);
            }
            if (merged.knockbackResistance != null && merged.knockbackResistance > 0) {
                builder.add(Attributes.KNOCKBACK_RESISTANCE,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("minecraft", "knockback_resistance." + armorSlot.getName()),
                                merged.knockbackResistance, AttributeModifier.Operation.ADD_VALUE),
                        modifierSlot);
            }
        } else if (merged.reach != null) {
            builder.add(Attributes.ENTITY_INTERACTION_RANGE,
                    new AttributeModifier(Identifier.fromNamespaceAndPath(id.getNamespace(), "reach." + id.getPath()),
                            merged.reach, AttributeModifier.Operation.ADD_VALUE),
                    EquipmentSlotGroup.MAINHAND);
        }

        for (int i = 0; i < merged.attributes.size(); i++) {
            addCustomAttribute(builder, merged.attributes.get(i), id, defaultAttributeSlot(merged, itemType, isArmor), i);
        }
        return builder.build();
    }

    private static void addBaseWeaponAttributes(ItemAttributeModifiers.Builder builder, KoperItemData merged,
            Identifier id, String itemType, ToolMaterial material) {
        float damage = merged.damage != null ? merged.damage.floatValue() : 1.0f;
        float speed = merged.attackSpeed != null ? merged.attackSpeed.floatValue() : -2.4f;
        switch (itemType) {
            case "spear" -> {
                builder.add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID,
                                damage, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                builder.add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(net.minecraft.world.item.Item.BASE_ATTACK_SPEED_ID,
                                merged.attackSpeed != null ? merged.attackSpeed.floatValue() : -3.0f,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
            }
            case "trident" -> {
                builder.add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("koper_lib", "trident.damage." + id.getPath()),
                                damage, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                builder.add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("koper_lib", "trident.speed." + id.getPath()),
                                merged.attackSpeed != null ? merged.attackSpeed.floatValue() : -2.9f,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
            }
            default -> {
                builder.add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID,
                                damage + material.attackDamageBonus(), AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
                builder.add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(net.minecraft.world.item.Item.BASE_ATTACK_SPEED_ID,
                                speed, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND);
            }
        }
    }

    private static void addCustomAttribute(ItemAttributeModifiers.Builder builder,
            KoperItemData.AttributeEntry entry, Identifier itemId, EquipmentSlotGroup fallbackSlot, int index) {
        Identifier attrId = normalizeAttributeId(entry.attribute);
        if (attrId == null) return;
        var attribute = BuiltInRegistries.ATTRIBUTE.getOptional(attrId);
        if (attribute.isEmpty()) {
            KoperLib.LOGGER.warn("[ItemFactory] Unknown attribute '{}' on {}", entry.attribute, itemId);
            return;
        }
        Identifier modifierId = entry.modifierId != null && !entry.modifierId.isBlank()
                ? Identifier.tryParse(entry.modifierId)
                : Identifier.fromNamespaceAndPath(itemId.getNamespace(),
                        "attr." + itemId.getPath() + "." + attrId.getPath().replace('/', '.') + "." + index);
        if (modifierId == null) modifierId = Identifier.fromNamespaceAndPath(itemId.getNamespace(), "attr." + itemId.getPath() + "." + index);
        EquipmentSlotGroup slot = slotGroup(entry.slot, fallbackSlot);
        ItemAttributeModifiers.Display display = entry.hidden
                ? ItemAttributeModifiers.Display.hidden()
                : ItemAttributeModifiers.Display.attributeModifiers();
        builder.add(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute.get()),
                new AttributeModifier(modifierId, entry.amount, operation(entry.operation)),
                slot, display);
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

    private static AttributeModifier.Operation operation(String raw) {
        String op = raw == null ? "" : raw.toLowerCase();
        return switch (op) {
            case "multiply_base", "multiplied_base", "add_multiplied_base", "base_percent" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "multiply_total", "multiplied_total", "add_multiplied_total", "total_percent" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static EquipmentSlotGroup defaultAttributeSlot(KoperItemData merged, String itemType, boolean isArmor) {
        if (isArmor) return EquipmentSlotGroup.bySlot(detectArmorSlot(itemType, merged.slot));
        return EquipmentSlotGroup.MAINHAND;
    }

    private static EquipmentSlotGroup slotGroup(String raw, EquipmentSlotGroup fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return switch (raw.toLowerCase()) {
            case "any" -> EquipmentSlotGroup.ANY;
            case "mainhand", "main", "main_hand" -> EquipmentSlotGroup.MAINHAND;
            case "offhand", "off", "off_hand" -> EquipmentSlotGroup.OFFHAND;
            case "hand", "hands" -> EquipmentSlotGroup.HAND;
            case "head", "helmet" -> EquipmentSlotGroup.HEAD;
            case "chest", "chestplate" -> EquipmentSlotGroup.CHEST;
            case "legs", "leggings" -> EquipmentSlotGroup.LEGS;
            case "feet", "boots" -> EquipmentSlotGroup.FEET;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "body" -> EquipmentSlotGroup.BODY;
            default -> fallback;
        };
    }
}
