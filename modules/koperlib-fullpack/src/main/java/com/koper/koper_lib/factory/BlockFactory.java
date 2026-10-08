package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperBlockData;
import com.koper.koper_lib.loader.ContentRegistry;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.scripting.JavaHookRegistry;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.context.BlockPlaceContext;

import java.util.Map;

// creates and registers custom blocks from JSON definitions
public class BlockFactory {
    private static final java.util.Map<String, String> BREAKING_CONTAINER_KEYS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Map<String, SoundType> SOUND_GROUPS = Map.ofEntries(
            Map.entry("wood", SoundType.WOOD),
            Map.entry("glass", SoundType.GLASS),
            Map.entry("metal", SoundType.METAL),
            Map.entry("sand", SoundType.SAND),
            Map.entry("grass", SoundType.GRASS),
            Map.entry("wool", SoundType.WOOL),
            Map.entry("stone", SoundType.STONE),
            Map.entry("gravel", SoundType.GRAVEL)
    );

    public static void createAndRegister(JsonObject json) {
        KoperBlockData merged = FactoryUtils.prepare(new KoperBlockData(), json);

        if (merged.id == null) {
            KoperLib.LOGGER.warn("[BlockFactory] json has no id and none could be inferred from the filename");
            return;
        }
        Identifier id = Identifier.tryParse(merged.id);
        if (id == null) {
            KoperLib.LOGGER.warn("[BlockFactory] bad or missing id '{}' — block skipped", merged.id);
            return;
        }
        boolean alreadyRegistered = BuiltInRegistries.BLOCK.containsKey(id);

        
        if (alreadyRegistered) {
            refreshAssets(id, merged, json);
            // reload wiped the brain bindings but the Block object survives — hook it back up,
            // otherwise every placed machine loses its block entity until the game restarts
            rebindBrain(id, merged);
            KoperLib.LOGGER.debug("[BlockFactory] Block already registered, refreshed assets: {}", merged.id);
            return;
        }

        BlockBehaviour.Properties settings = ContentRegistry.createBlockSettings(merged.id);

        if (merged.hardness != null && merged.resistance != null) {
            settings.strength(merged.hardness, merged.resistance);
        }
        if (merged.slipperiness != null) {
            settings.friction(merged.slipperiness);
        }
        if (merged.speedFactor != null) {
            settings.speedFactor(merged.speedFactor);
        }
        if (merged.jumpFactor != null) {
            settings.jumpFactor(merged.jumpFactor);
        }
        if (merged.bounce != null) {
            settings.bounceRestitution(merged.bounce);
        }
        if (!merged.lightByState.isEmpty()) {
            // light that depends on the state, key "prop=value,prop=value" in state order
            var byState = new java.util.HashMap<>(merged.lightByState);
            int fallback = merged.lightLevel != null ? merged.lightLevel : 0;
            settings.lightLevel(state -> {
                StringBuilder k = new StringBuilder();
                for (var p : state.getProperties()) {
                    if (!k.isEmpty()) k.append(',');
                    k.append(p.getName()).append('=').append(stateValue(state, p));
                }
                return byState.getOrDefault(k.toString(), fallback);
            });
        } else if (merged.lightLevel != null && merged.lightLevel > 0) {
            int light = merged.lightLevel;
            settings.lightLevel(state -> light);
        }

        SoundType soundGroup = SOUND_GROUPS.getOrDefault(
                merged.sound != null ? merged.sound.toLowerCase() : "stone",
                SoundType.STONE);
        settings.sound(soundGroup);

      
        boolean isCross = "cross".equalsIgnoreCase(merged.shape);
        boolean isCutout = "cutout".equalsIgnoreCase(merged.renderType)
                        || "translucent".equalsIgnoreCase(merged.renderType);
        if (Boolean.TRUE.equals(merged.transparent) || isCutout || isCross) {
            settings.noOcclusion();
        }
        if (isCross && merged.collisionBox == null) {
            settings.noCollision(); 
        }
        if (Boolean.FALSE.equals(merged.collidable)) {
            settings.noCollision();
        }
        if (Boolean.TRUE.equals(merged.randomTicks)) {
            settings.randomTicks();
        }
        if (Boolean.TRUE.equals(merged.ignitedByLava)) {
            settings.ignitedByLava();
        }
        if (Boolean.TRUE.equals(merged.noTerrainParticles)) {
            settings.noTerrainParticles();
        }
        if (Boolean.TRUE.equals(merged.replaceable)) {
            settings.replaceable();
        }
        if (Boolean.TRUE.equals(merged.requiresTool) || (merged.miningLevel != null && merged.miningLevel > 0)) {
            settings.requiresCorrectToolForDrops();
        }

        // Redstone power emission
        boolean hasRedstone = merged.redstonePower != null && merged.redstonePower > 0;
        int rsValue = hasRedstone ? merged.redstonePower : 0;

        Block customBlock = new com.koper.koper_lib.block.KoperBrainyBlock(settings) {
            {
                registerDefaultState(KoperBlockStates.applyDefaults(merged, defaultBlockState()));
            }

            @Override
            protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
                KoperBlockStates.addToDefinition(merged, builder);
            }

            @Override
            public BlockState getStateForPlacement(BlockPlaceContext context) {
                BlockState placed = defaultBlockState();
                String from = merged.facingFrom;
                if (from != null && placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                    placed = placed.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                            context.getHorizontalDirection());
                } else if (from != null && placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                    placed = placed.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                            from.equals("face") ? context.getClickedFace() : context.getNearestLookingDirection());
                } else if (placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                    placed = placed.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                            context.getHorizontalDirection().getOpposite());
                } else if (placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                    placed = placed.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                            context.getClickedFace());
                }
                if (placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
                    // clicked the top half of a side, or the underside of a block: sits at the top
                    boolean top = context.getClickedFace() == net.minecraft.core.Direction.DOWN
                        || (context.getClickedFace() != net.minecraft.core.Direction.UP
                            && context.getClickLocation().y - context.getClickedPos().getY() > 0.5);
                    placed = placed.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                        top ? net.minecraft.world.level.block.state.properties.Half.TOP : net.minecraft.world.level.block.state.properties.Half.BOTTOM);
                }
                placed = KoperBlockConnections.stateFor(context.getLevel(), context.getClickedPos(), placed, merged);
                return placed;
            }

            // connected textures used to be refreshed only from setPlacedBy + destroy, so anything
            // that wasn't a hand place or a hand break (piston, tnt, /fill, worldgen, a kontra eating
            // the neighbour) left the booleans stale — THE "czasem się łączą czasem nie". this is the
            // hook vanilla fences/panes use and it catches every one of those.
            @Override
            protected BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level,
                    net.minecraft.world.level.ScheduledTickAccess tickAccess, BlockPos pos, Direction direction,
                    BlockPos neighborPos, BlockState neighborState, net.minecraft.util.RandomSource random) {
                BlockState out = super.updateShape(state, level, tickAccess, pos, direction,
                        neighborPos, neighborState, random);
                return KoperBlockConnections.connectTo(out, direction, neighborState, merged);
            }

            @Override
            protected InteractionResult useWithoutItem(BlockState state, Level Level, BlockPos pos,
                                                 Player player, BlockHitResult hit) {
                if (!Level.isClientSide()) {
                    if (openKui(merged, Level, pos, player)) return InteractionResult.SUCCESS;
                    if (fireBlockHook(merged.id, "on_use", player, pos)) {
                        if (merged.logic != null && !merged.logic.isEmpty()) {
                            UniversalScriptEngine.call(merged.logic, ScriptEvent.ON_USE, player, Level, pos);
                        }
                        for (String script : merged.scripts) {
                            UniversalScriptEngine.call(script, ScriptEvent.ON_USE, player, Level, pos);
                        }
                        if (merged.events != null && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                            var result = com.koper.koper_lib.api.KoperActions.run(merged.events.get("on_use"),
                                    KoperContext.ofBlockUse(sp, pos), "on_use", merged.id);
                            if (result != InteractionResult.PASS) return result;
                        }
                    }
                }
                return super.useWithoutItem(state, Level, pos, player, hit);
            }

            @Override
            protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                    Player player, InteractionHand hand, BlockHitResult hit) {
                if (openKui(merged, level, pos, player)) {
                    return InteractionResult.SUCCESS;
                }
                return super.useItemOn(stack, state, level, pos, player, hand, hit);
            }

            @Override
            public void setPlacedBy(Level Level, BlockPos pos, BlockState state,
                                 LivingEntity placer, ItemStack stack) {
                super.setPlacedBy(Level, pos, state, placer, stack);
                if (!Level.isClientSide() && placer instanceof Player player) {
                    if (fireBlockHook(merged.id, "on_place", player, pos)) {
                        if (merged.logic != null && !merged.logic.isEmpty()) {
                            UniversalScriptEngine.call(merged.logic, ScriptEvent.ON_PLACE, player, Level, pos);
                        }
                        for (String script : merged.scripts) {
                            UniversalScriptEngine.call(script, ScriptEvent.ON_PLACE, player, Level, pos);
                        }
                        if (merged.events != null && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                            com.koper.koper_lib.api.KoperActions.run(merged.events.get("on_place"),
                                    KoperContext.ofBlockUse(sp, pos), "on_place", merged.id);
                        }
                    }
                }
            }

            @Override
            public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                if (!level.isClientSide()) {
                    String key = destroyContainerKey(merged, level, pos);
                    if (key != null) BREAKING_CONTAINER_KEYS.put(removeKey(merged, level, pos), key);
                    // last moment the block entity is still alive — after this its items are gone
                    if (shouldDropContainer(merged)) spillBrain(level, pos);
                }
                return super.playerWillDestroy(level, pos, state, player);
            }

            @Override
            public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
                super.destroy(level, pos, state);
                if (level instanceof Level realLevel && !realLevel.isClientSide())
                    handleContainerRemoved(merged, realLevel, pos);
            }

            @Override
            public void playerDestroy(net.minecraft.server.level.ServerLevel Level, net.minecraft.server.level.ServerPlayer player, BlockPos pos, BlockState state,
                                   BlockEntity be, ItemStack tool) {
                super.playerDestroy(Level, player, pos, state, be, tool);
                if (!Level.isClientSide()) {
                    if (fireBlockHook(merged.id, "on_break", player, pos)) {
                        if (merged.logic != null && !merged.logic.isEmpty()) {
                            UniversalScriptEngine.call(merged.logic, ScriptEvent.ON_BREAK, player, Level, pos);
                        }
                        for (String script : merged.scripts) {
                            UniversalScriptEngine.call(script, ScriptEvent.ON_BREAK, player, Level, pos);
                        }
                        if (merged.events != null && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                            com.koper.koper_lib.api.KoperActions.run(merged.events.get("on_break"),
                                    KoperContext.ofBlockUse(sp, pos), "on_break", merged.id);
                        }
                    }
                }
            }

            @Override
            protected boolean isSignalSource(BlockState state) {
                return hasRedstone;
            }

            @Override
            protected int getSignal(BlockState state, BlockGetter Level,
                                               BlockPos pos, Direction direction) {
                return rsValue;
            }

            // data-driven hitbox; geo blocks still fall back to their kender hitbox (rotates with facing)
            @Override
            protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, BlockGetter level,
                    BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
                return KoperBlockShapes.visual(merged, this, state);
            }

            @Override
            protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                    BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
                return KoperBlockShapes.collision(merged, this, state);
            }
        };

        if (!alreadyRegistered) {
            ContentRegistry.registerBlock(merged.id, customBlock, merged.creativeTab);
        }
        ContentRegistry.storeBlockData(merged.id, merged);

        // must happen after the block object exists — the BE type's valid set holds Block refs
        bindBrain(customBlock, merged);

        emitBlockAssets(id, merged, json);
        KoperLib.LOGGER.info("BlockFactory: Registered block: " + merged.id);
    }

    private static void spillBrain(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof com.koper.koper_lib.block.KoperBlockBrain brain)) return;
        if (brain.getContainerSize() == 0) return;
        net.minecraft.world.Containers.dropContents(level, pos, brain);
        brain.clearContent();
    }

    // reload wiped the bindings; the Block objects survive, so hook them back up. flipping
    // block_entity between reloads works in both directions because nothing is cached on the block
    private static void rebindBrain(Identifier id, KoperBlockData merged) {
        Block live = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (live != null) bindBrain(live, merged);
    }

    private static void bindBrain(Block block, KoperBlockData data) {
        if (!com.koper.koper_lib.block.KoperBrainRegistry.shouldHaveBrain(data)) return;
        com.koper.koper_lib.block.KoperBrainRegistry.bind(block, com.koper.koper_lib.block.KoperBrainRegistry.slotsFor(data));
    }

    private static void refreshAssets(Identifier id, KoperBlockData merged, JsonObject json) {
        emitBlockAssets(id, merged, json);
        // block already exists — just re-add its item to the creative tab
        String tab = FactoryUtils.tabOr(merged.creativeTab, id.getNamespace());
        com.koper.koper_lib.loader.CreativeTabRegistry.addToTab(tab, BuiltInRegistries.ITEM.getValue(id));
    }

    // translations + model + loot table + mining tag — same whether registering fresh or just refreshing assets
    private static void emitBlockAssets(Identifier id, KoperBlockData merged, JsonObject json) {
        String displayName = json.has("name") ? json.get("name").getAsString() : FactoryUtils.capitalizeWords(id.getPath());
        // a block wears the same name as its item, in every language the pack wrote
        FactoryUtils.emitLang(json, "block." + id.getNamespace() + "." + id.getPath(), null, displayName, null);
        FactoryUtils.emitLang(json, "item." + id.getNamespace() + "." + id.getPath(), null, displayName, null);

        // model block: bound from its json, the vanilla model stays empty and kodel draws it.
        // loot/mining tag still emit normally (a model block is still a real block)
        boolean geo = json.has("kender");
        if (geo) {
            Identifier texture = com.koper.koper_lib.api.FullpackAddons.blockVisual(
                id, json.getAsJsonObject("kender"));
            if (texture != null) KoperLib.VIRTUAL_PACK.addInvisibleBlock(id, texture);
            else KoperLib.LOGGER.warn("[Fullpack] block {} requests 'kender', but no installed addon handles it", id);
        } else {
            String texturePath = (merged.texture != null && !merged.texture.isEmpty())
                    ? merged.texture : "minecraft:block/stone";
            if (Boolean.TRUE.equals(merged.ownAssets)) KoperLib.VIRTUAL_PACK.addPresetModel(id, id.getNamespace() + ":block/" + id.getPath(), "block");
            else if ("cross".equalsIgnoreCase(merged.shape)) KoperLib.VIRTUAL_PACK.addCrossModel(id, texturePath);
            else if (merged.connectedTextures != null && !merged.connectedTextures.isEmpty())
                KoperLib.VIRTUAL_PACK.addConnectedBlockStateAndModel(id, merged, texturePath, assetStateProperties(merged));
            else KoperLib.VIRTUAL_PACK.addBlockStateAndModel(id, texturePath, merged.textureFaces, assetStateProperties(merged));
        }

        if (!merged.drops.isEmpty()) {
            String lootJson = customDropsLoot(merged);
            KoperLib.VIRTUAL_PACK.addServerAsset(
                    Identifier.fromNamespaceAndPath(id.getNamespace(), "loot_table/blocks/" + id.getPath() + ".json"), lootJson);
        } else if (Boolean.TRUE.equals(merged.dropsSelf)) {
            String lootJson = """
                {"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"%s"}],"condition":{"type":"minecraft:survives_explosion"}}]}
                """.formatted(merged.id);
            KoperLib.VIRTUAL_PACK.addServerAsset(
                    Identifier.fromNamespaceAndPath(id.getNamespace(), "loot_table/blocks/" + id.getPath() + ".json"), lootJson);
        }

        if (merged.miningTool != null) {
            String tagName = switch (merged.miningTool.toLowerCase()) {
                case "pickaxe" -> "mineable/pickaxe";
                case "axe"     -> "mineable/axe";
                case "shovel"  -> "mineable/shovel";
                case "hoe"     -> "mineable/hoe";
                default -> null;
            };
            if (tagName != null) KoperLib.VIRTUAL_PACK.addServerTagValue(
                    Identifier.fromNamespaceAndPath("minecraft", "block/" + tagName), merged.id);
        }
        String tierTag = miningTierTag(merged.miningLevel);
        if (tierTag != null) {
            KoperLib.VIRTUAL_PACK.addServerTagValue(
                    Identifier.fromNamespaceAndPath("minecraft", "block/" + tierTag), merged.id);
        }
    }

    private static String miningTierTag(Integer level) {
        if (level == null) return null;
        return switch (level) {
            case 1 -> "needs_stone_tool";
            case 2 -> "needs_iron_tool";
            case 3 -> "needs_diamond_tool";
            case 4 -> "needs_diamond_tool";
            default -> null;
        };
    }

    // true = lua/scripts may run (hook returned PASS)
    private static boolean fireBlockHook(String blockId, String event, Player player, BlockPos pos) {
        if (blockId == null || blockId.isEmpty()) return true;
        return JavaHookRegistry.fireHook(
            blockId + "/" + event,
            KoperContext.ofBlockUse(player, pos)
        ) == InteractionResult.PASS;
    }

    private static boolean openKui(KoperBlockData data, Level level, BlockPos pos, Player player) {
        if (!hasKui(data) || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) return false;
        String key = blockContainerKey(data, level, pos);
        if (key != null) {
            upgradeVaultContainer(data, level, pos, key);
            if (com.koper.koper_lib.kui.KuiOpen.open(sp, key)) return true;
        }
        return false;
    }

    // packs that never said which mode they wanted used to get ONE inventory shared by every block
    // with that gui id, worldwide. place two chests, same chest. that shape of surprise eats items,
    // so absent now means per-position and sharing has to be asked for
    private static final java.util.Set<String> SHARED_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean perBlockContainer(KoperBlockData data) {
        String mode = data.containerMode;
        if (mode == null || mode.isBlank()) {
            if (data.gui != null && !data.gui.isBlank() && SHARED_WARNED.add(data.id))
                KoperLib.LOGGER.info("[BlockFactory] {} has a gui but no container mode — using per-block."
                    + " add \"container\": \"shared\" if you wanted one world-wide inventory", data.id);
            return true;
        }
        return !"shared".equalsIgnoreCase(mode);
    }

    private static String positionKey(KoperBlockData data, Level level, BlockPos pos) {
        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        return data.gui + "#" + dim + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    private static String blockContainerKey(KoperBlockData data, Level level, BlockPos pos) {
        String connected = connectedCubeContainerKey(data, level, pos);
        if (connected != null) return connected;
        String gui = data.gui;
        if (gui == null || gui.isBlank()) return null;
        if (!perBlockContainer(data)) return gui;
        return positionKey(data, level, pos);
    }

    private static boolean hasKui(KoperBlockData data) {
        return (data.gui != null && !data.gui.isBlank())
            || (data.connectedCubeGuis != null && !data.connectedCubeGuis.isEmpty());
    }

    private static String connectedCubeContainerKey(KoperBlockData data, Level level, BlockPos pos) {
        return connectedCubeContainerKey(data, level, pos, true);
    }

    private static String connectedCubeContainerKey(KoperBlockData data, Level level, BlockPos pos, boolean requireValid) {
        if (data.connectedCubeGuis == null || data.connectedCubeGuis.isEmpty()) return null;
        int max = data.connectedCubeGuis.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        var cube = KoperBlockConnections.cubeInfo(level, pos, Math.max(1, max * max * max + 1));
        int size = cube.valid() ? cube.size() : cube.spanSize();
        if (requireValid && !cube.valid()) return null;
        String gui = data.connectedCubeGuis.get(size);
        if (gui == null || gui.isBlank()) return null;
        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        return gui + "#" + dim + "_" + cube.keyPart(size);
    }

    private static String destroyContainerKey(KoperBlockData data, Level level, BlockPos pos) {
        if (isConnectedVault(data)) return connectedCubeContainerKey(data, level, pos, false);
        return blockContainerKey(data, level, pos);
    }

    private static boolean isConnectedVault(KoperBlockData data) {
        return data.connectedCubeGuis != null && !data.connectedCubeGuis.isEmpty();
    }

    private static boolean shouldDropContainer(KoperBlockData data) {
        if (data.dropContainer != null) return data.dropContainer;
        return data.gui != null && !data.gui.isBlank() && perBlockContainer(data);
    }

    private static void handleContainerRemoved(KoperBlockData data, Level level, BlockPos pos) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        String keyId = removeKey(data, level, pos);
        String oldKey = BREAKING_CONTAINER_KEYS.remove(keyId);
        if (oldKey == null) oldKey = destroyContainerKeyAfterRemoval(data, level, pos);

        if (isConnectedVault(data)) {
            String capturedOldKey = oldKey != null ? oldKey : "";
            com.koper.koper_lib.core.KoperTasks.later(sl.getServer(), 1,
                () -> migrateVaultContainer(data, sl, pos, capturedOldKey));
        } else if (shouldDropContainer(data)) {
            if (oldKey == null) return;
            com.koper.koper_lib.kui.KuiContainers.dropAndClear(oldKey, sl, pos);
        }
    }

    private static String removeKey(KoperBlockData data, Level level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        return data.id + "|" + dim + "|" + pos.asLong();
    }

    private static String destroyContainerKeyAfterRemoval(KoperBlockData data, Level level, BlockPos pos) {
        if (!isConnectedVault(data)) {
            if (data.gui == null || data.gui.isBlank()) return null;
            if (!perBlockContainer(data)) return data.gui;
            return positionKey(data, level, pos);
        }
        int max = data.connectedCubeGuis.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        for (Direction dir : Direction.values()) {
            BlockPos p = pos.relative(dir);
            var cube = KoperBlockConnections.cubeInfo(level, p, Math.max(1, max * max * max + 1));
            int size = cube.valid() ? cube.size() : cube.spanSize();
            String gui = data.connectedCubeGuis.get(size);
            if (gui == null || cube.min() == null) continue;
            String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
            return gui + "#" + dim + "_" + cube.keyPart(size);
        }
        return null;
    }

    private static void migrateVaultContainer(KoperBlockData data, net.minecraft.server.level.ServerLevel level,
            BlockPos brokenPos, String oldKey) {
        String nextKey = null;
        KoperBlockConnections.CubeInfo nextCube = null;
        for (Direction dir : Direction.values()) {
            BlockPos p = brokenPos.relative(dir);
            String candidate = connectedCubeContainerKey(data, level, p, true);
            if (candidate != null) {
                nextKey = candidate;
                int max = data.connectedCubeGuis.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
                nextCube = KoperBlockConnections.cubeInfo(level, p, Math.max(1, max * max * max + 1));
                break;
            }
        }
        if (nextKey == null && hasConnectedNeighbor(data, level, brokenPos)) return;
        int slots = nextKey != null ? com.koper.koper_lib.kui.KuiOpen.slotCountFor(nextKey) : 0;
        BlockPos dropPos = vaultDropPos(data, level, brokenPos);
        com.koper.koper_lib.kui.KuiContainers.shrinkIntoOrDrop(oldKey, nextKey, slots, level, dropPos);
        if (nextKey != null && nextCube != null && nextCube.valid()) {
            migratePossibleLargerVaultKeys(data, level, nextCube, nextKey, slots, dropPos, oldKey);
        }
    }

    private static void migratePossibleLargerVaultKeys(KoperBlockData data, net.minecraft.server.level.ServerLevel level,
            KoperBlockConnections.CubeInfo cube, String nextKey, int slots, BlockPos dropPos, String alreadyTried) {
        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        for (var ent : data.connectedCubeGuis.entrySet()) {
            int size = ent.getKey();
            if (size <= cube.size()) continue;
            String gui = ent.getValue();
            int slack = size - cube.size();
            for (int dx = 0; dx <= slack; dx++) {
                for (int dy = 0; dy <= slack; dy++) {
                    for (int dz = 0; dz <= slack; dz++) {
                        int x = cube.min().getX() - dx;
                        int y = cube.min().getY() - dy;
                        int z = cube.min().getZ() - dz;
                        String oldKey = gui + "#" + dim + "_" + x + "_" + y + "_" + z + "_" + size;
                        if (oldKey.equals(nextKey) || oldKey.equals(alreadyTried)) continue;
                        com.koper.koper_lib.kui.KuiContainers.shrinkIntoOrDrop(oldKey, nextKey, slots, level, dropPos);
                    }
                }
            }
        }
    }

    private static void upgradeVaultContainer(KoperBlockData data, Level level, BlockPos pos, String newKey) {
        if (!isConnectedVault(data) || !(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        int max = data.connectedCubeGuis.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        var cube = KoperBlockConnections.cubeInfo(level, pos, Math.max(1, max * max * max + 1));
        if (!cube.valid() || cube.min() == null || cube.max() == null) return;
        int newSlots = com.koper.koper_lib.kui.KuiOpen.slotCountFor(newKey);
        if (newSlots <= 0) return;

        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        for (var ent : data.connectedCubeGuis.entrySet()) {
            int size = ent.getKey();
            if (size >= cube.size()) continue;
            String gui = ent.getValue();
            for (int x = cube.min().getX(); x <= cube.max().getX() - size + 1; x++) {
                for (int y = cube.min().getY(); y <= cube.max().getY() - size + 1; y++) {
                    for (int z = cube.min().getZ(); z <= cube.max().getZ() - size + 1; z++) {
                        String oldKey = gui + "#" + dim + "_" + x + "_" + y + "_" + z + "_" + size;
                        com.koper.koper_lib.kui.KuiContainers.shrinkIntoOrDrop(oldKey, newKey, newSlots, sl, cube.max().above());
                    }
                }
            }
        }
    }

    private static boolean hasConnectedNeighbor(KoperBlockData data, Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            var other = ContentRegistry.getBlockData(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.relative(dir)).getBlock()));
            if (KoperBlockConnections.enabled(other) && data.connectGroup != null && data.connectGroup.equals(other.connectGroup)) return true;
        }
        return false;
    }

    private static BlockPos vaultDropPos(KoperBlockData data, Level level, BlockPos brokenPos) {
        for (Direction dir : Direction.values()) {
            BlockPos p = brokenPos.relative(dir);
            int max = data.connectedCubeGuis.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
            var cube = KoperBlockConnections.cubeInfo(level, p, Math.max(1, max * max * max + 1));
            if (cube.min() != null && cube.max() != null) return cube.max().above();
        }
        return brokenPos.above();
    }

    private static java.util.List<String> assetStateProperties(KoperBlockData data) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(data.stateProperties);
        if (KoperBlockConnections.enabled(data)) {
            addAssetState(out, "north");
            addAssetState(out, "east");
            addAssetState(out, "south");
            addAssetState(out, "west");
            if (!Boolean.FALSE.equals(data.connectVertical)) {
                addAssetState(out, "up");
                addAssetState(out, "down");
            }
        }
        return out;
    }

    private static void addAssetState(java.util.List<String> out, String state) {
        if (!out.contains(state)) out.add(state);
    }

    private static String customDropsLoot(KoperBlockData data) {
        StringBuilder pools = new StringBuilder();
        for (var d : data.drops) {
            if (d.item == null || d.item.isBlank()) continue;
            if (pools.length() > 0) pools.append(',');
            // 26.3 loot format: "modifier" / "condition" objects, several conditions go into all_of
            String countFn = d.count > 1
                ? ",\"modifier\":{\"type\":\"minecraft:set_count\",\"count\":" + d.count + "}"
                : "";
            String explosion = "{\"type\":\"minecraft:survives_explosion\"}";
            String condition = d.chance < 1.0f
                ? "{\"type\":\"minecraft:all_of\",\"terms\":[" + explosion
                    + ",{\"type\":\"minecraft:random_chance\",\"chance\":" + d.chance + "}]}"
                : explosion;
            pools.append("{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"")
                .append(d.item).append("\"").append(countFn)
                .append("}],\"condition\":").append(condition).append("}");
        }
        if (pools.length() == 0) {
            return "{\"type\":\"minecraft:block\",\"pools\":[]}";
        }
        return "{\"type\":\"minecraft:block\",\"pools\":[" + pools + "]}";
    }


    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String stateValue(BlockState state, net.minecraft.world.level.block.state.properties.Property p) {
        return p.getName(state.getValue(p));
    }
}
