package com.koper.koper_lib.loader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.block.FullpackTombstoneBlock;
import com.koper.koper_lib.block.KoperBrainRegistry;
import com.koper.koper_lib.item.FullpackTombstoneItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// "Unregistering" Fullpack content without losing anything. A registry entry is never removed:
// pulling one out shifts numeric ids under connected clients and turns placed blocks into air.
// Instead the id stays and its behaviour becomes a tombstone: no scripts, no menus, no use, no
// placement, a "Missing: pack:id" name and the missing model, one creative tab of its own, and a
// broken block drops itself plus whatever it held. Bringing the pack back brings the content back.
//
// Only content a JSON factory built is covered. Java mods register through ContentRegistry too, and
// their blocks are not re-registered on reload, so "not seen this reload" means nothing for them.
//
// Across restarts the manifest (koperlib/registry_manifest.json) remembers every factory id and its
// block state properties; ids no pack provides any more are registered as tombstones late in startup,
// after every mod has initialised and before a world loads.
public final class FullpackTombstones {

    public static final String TAB = "koper_lib:missing_content";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path MANIFEST = KoperLibDirectories.ROOT.resolve("registry_manifest.json");

    static final class BlockRecord {
        Map<String, List<String>> properties = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();
        float destroyTime;
        float resistance;
        boolean brain;
        int slots;
        boolean item;
    }

    private static final Map<Identifier, BlockRecord> BLOCKS = new TreeMap<>();
    private static final Set<Identifier> ITEMS = new java.util.TreeSet<>();

    private static final Set<Block> FACTORY_BLOCKS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Item> FACTORY_ITEMS = Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Set<Identifier> CLAIMED_BLOCKS = new HashSet<>();
    private static final Set<Identifier> CLAIMED_ITEMS = new HashSet<>();

    private static volatile Set<Block> missingBlocks = Set.of();
    private static volatile Set<Item> missingItems = Set.of();
    private static volatile Set<String> missingBlockIds = Set.of();

    private static boolean loaded;
    private static boolean broken;
    private static boolean dirty;
    private static boolean startupDone;

    private FullpackTombstones() {}

    // ── what the factories report ────────────────────────────────────────────

    public static synchronized void beginLoad() {
        ensureLoaded();
        CLAIMED_BLOCKS.clear();
        CLAIMED_ITEMS.clear();
    }

    // BlockFactory built and registered this block
    public static synchronized void noteFactoryBlock(Identifier id, Block block) {
        ensureLoaded();
        if (BuiltInRegistries.BLOCK.getValue(id) != block) return;
        FACTORY_BLOCKS.add(block);
        CLAIMED_BLOCKS.add(id);
        BlockRecord record = new BlockRecord();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            List<String> names = new ArrayList<>();
            for (Object value : property.getPossibleValues()) names.add(valueName(property, value));
            record.properties.put(property.getName(), names);
            record.defaults.put(property.getName(),
                valueName(property, block.defaultBlockState().getValue(property)));
        }
        record.destroyTime = block.defaultDestroyTime();
        record.resistance = block.getExplosionResistance();
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item instanceof BlockItem blockItem && blockItem.getBlock() == block) {
            FACTORY_ITEMS.add(item);
            CLAIMED_ITEMS.add(id);
            record.item = true;
        }
        put(id, record);
    }

    // BlockFactory found the id already registered. false: the live entry is a startup tombstone,
    // which cannot be turned back into the real block in this session
    public static synchronized boolean claimBlock(Identifier id) {
        ensureLoaded();
        Block live = BuiltInRegistries.BLOCK.getValue(id);
        if (live instanceof FullpackTombstoneBlock) {
            KoperLib.LOGGER.error("[Tombstone] block {} is back, but its placeholder holds the id since startup. "
                + "It stays a missing block until the game restarts", id);
            return false;
        }
        if (!FACTORY_BLOCKS.contains(live)) return true;
        CLAIMED_BLOCKS.add(id);
        BlockRecord record = BLOCKS.get(id);
        if (record != null) {
            record.brain = false;   // bind() sets it again if the block still wants one
            record.slots = 0;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (FACTORY_ITEMS.contains(item)) CLAIMED_ITEMS.add(id);
        return true;
    }

    public static synchronized void noteFactoryItem(Identifier id, Item item) {
        ensureLoaded();
        if (BuiltInRegistries.ITEM.getValue(id) != item) return;
        FACTORY_ITEMS.add(item);
        CLAIMED_ITEMS.add(id);
        if (ITEMS.add(id)) dirty = true;
    }

    public static synchronized boolean claimItem(Identifier id) {
        ensureLoaded();
        Item live = BuiltInRegistries.ITEM.getValue(id);
        if (live instanceof FullpackTombstoneItem || live instanceof FullpackTombstoneItem.OfBlock) {
            KoperLib.LOGGER.error("[Tombstone] item {} is back, but its placeholder holds the id since startup. "
                + "It stays a missing item until the game restarts", id);
            return false;
        }
        if (FACTORY_ITEMS.contains(live)) CLAIMED_ITEMS.add(id);
        return true;
    }

    // KoperBrainRegistry.bind: what a tombstone needs to keep the block entity it had
    public static synchronized void noteBrain(Block block, int slots) {
        if (!FACTORY_BLOCKS.contains(block)) return;
        BlockRecord record = BLOCKS.get(BuiltInRegistries.BLOCK.getKey(block));
        if (record == null || (record.brain && record.slots == slots)) return;
        record.brain = true;
        record.slots = slots;
        dirty = true;
    }

    // ── queries ──────────────────────────────────────────────────────────────

    public static boolean isMissing(Block block) { return missingBlocks.contains(block); }
    public static boolean isMissing(Item item) { return missingItems.contains(item); }
    public static boolean isMissingBlockId(String id) { return id != null && missingBlockIds.contains(id); }
    public static int missingCount() { return missingBlocks.size() + missingItems.size(); }

    // ── startup ──────────────────────────────────────────────────────────────

    // after every mod initialised, before any world loads. both the client and the integrated server
    // call this, only the first does anything
    public static synchronized void registerStartupTombstones() {
        if (startupDone) return;
        startupDone = true;
        ensureLoaded();
        if (broken) return;
        ContentRegistry.prepareRegistriesForNewContent();
        int blocks = 0;
        int items = 0;
        for (var entry : BLOCKS.entrySet()) {
            Identifier id = entry.getKey();
            if (BuiltInRegistries.BLOCK.containsKey(id)) continue;
            BlockRecord record = entry.getValue();
            try {
                BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, id))
                    .strength(record.destroyTime, record.resistance)
                    .noOcclusion();
                FullpackTombstoneBlock block = FullpackTombstoneBlock.create(props, id.toString(),
                    record.properties, record.defaults);
                Registry.register(BuiltInRegistries.BLOCK, id, block);
                if (record.brain) KoperBrainRegistry.bind(block, record.slots);
                blocks++;
                if (record.item && !BuiltInRegistries.ITEM.containsKey(id)) {
                    Registry.register(BuiltInRegistries.ITEM, id,
                        new FullpackTombstoneItem.OfBlock(block, itemProps(id), id.toString()));
                    items++;
                }
            } catch (Throwable error) {
                KoperLib.LOGGER.error("[Tombstone] could not hold block {} in place, every placed copy of it "
                    + "loads as air", id, error);
            }
        }
        for (Identifier id : ITEMS) {
            if (BuiltInRegistries.ITEM.containsKey(id)) continue;
            try {
                Registry.register(BuiltInRegistries.ITEM, id,
                    new FullpackTombstoneItem(itemProps(id), id.toString()));
                items++;
            } catch (Throwable error) {
                KoperLib.LOGGER.error("[Tombstone] could not hold item {} in place, stacks of it are lost "
                    + "when they load", id, error);
            }
        }
        if (blocks + items > 0)
            KoperLib.LOGGER.warn("[Tombstone] {} block(s) and {} item(s) belong to Fullpacks that are not "
                + "loaded; they are kept as missing content", blocks, items);
        finishLoad();
        CreativeTabRegistry.processTabs(KoperLib.MOD_ID);
    }

    // the largest stack any item may have, so every stack saved before the pack left still loads
    private static Item.Properties itemProps(Identifier id) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).stacksTo(99);
    }

    // ── after a load: work out what is missing ───────────────────────────────

    // runs after the factories went through every enabled pack, before creative tabs are rebuilt
    public static synchronized void finishLoad() {
        ensureLoaded();
        Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Item> items = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> blockIds = new HashSet<>();
        for (var entry : BLOCKS.entrySet()) {
            Identifier id = entry.getKey();
            Block live = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (live == null) continue;
            boolean tombstone = live instanceof FullpackTombstoneBlock;
            if (!tombstone && (!FACTORY_BLOCKS.contains(live) || CLAIMED_BLOCKS.contains(id))) continue;
            blocks.add(live);
            blockIds.add(id.toString());
            BlockRecord record = entry.getValue();
            // reload wiped the brain bindings; without one the block entity, and its data, is dropped
            if (record.brain) KoperBrainRegistry.bind(live, record.slots);
            Item item = live.asItem();
            if (item != Items.AIR) items.add(item);
        }
        for (Identifier id : ITEMS) {
            Item live = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (live == null) continue;
            boolean tombstone = live instanceof FullpackTombstoneItem;
            if (!tombstone && (!FACTORY_ITEMS.contains(live) || CLAIMED_ITEMS.contains(id))) continue;
            items.add(live);
        }
        missingBlocks = blocks;
        missingItems = items;
        missingBlockIds = blockIds;

        if (!items.isEmpty()) {
            CreativeTabRegistry.defineTab(TAB, "Missing content", null, 10_000);
            for (Item item : items) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                KoperLib.VIRTUAL_PACK.addTranslation(item.getDescriptionId(), "Missing: " + id);
                CreativeTabRegistry.addToTab(TAB, item);
            }
        }
        for (Block block : blocks)
            KoperLib.VIRTUAL_PACK.addTranslation(block.getDescriptionId(),
                "Missing: " + BuiltInRegistries.BLOCK.getKey(block));
        if (!blocks.isEmpty() || !items.isEmpty())
            KoperLib.LOGGER.info("[Tombstone] {} block(s) and {} item(s) are missing content now",
                blocks.size(), items.size());
        save();
    }

    // ── the guards ───────────────────────────────────────────────────────────

    public static void registerGuards() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) ->
            refuse(player, player.getItemInHand(hand)));
        // covers placing a missing block and using a missing item on a block
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) ->
            refuse(player, player.getItemInHand(hand)));
    }

    private static InteractionResult refuse(Player player, ItemStack stack) {
        if (stack.isEmpty() || !isMissing(stack.getItem())) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer)
            serverPlayer.sendOverlayMessage(Component.literal("§c" + BuiltInRegistries.ITEM.getKey(stack.getItem())
                + " belongs to a Fullpack that is not loaded"));
        return InteractionResult.FAIL;
    }

    // ── manifest ─────────────────────────────────────────────────────────────

    private static void put(Identifier id, BlockRecord record) {
        BlockRecord old = BLOCKS.get(id);
        if (old != null) {
            // bind() runs after this and fills the brain back in
            record.brain = old.brain;
            record.slots = old.slots;
        }
        BLOCKS.put(id, record);
        if (old == null || !GSON.toJson(old).equals(GSON.toJson(record))) dirty = true;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(MANIFEST)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(MANIFEST), JsonObject.class);
            if (root.has("blocks")) {
                for (var entry : root.getAsJsonObject("blocks").entrySet()) {
                    Identifier id = Identifier.parse(entry.getKey());
                    JsonObject saved = entry.getValue().getAsJsonObject();
                    BlockRecord record = new BlockRecord();
                    if (saved.has("properties"))
                        for (var property : saved.getAsJsonObject("properties").entrySet()) {
                            List<String> values = new ArrayList<>();
                            for (JsonElement value : property.getValue().getAsJsonArray()) values.add(value.getAsString());
                            record.properties.put(property.getKey(), values);
                        }
                    if (saved.has("defaults"))
                        for (var value : saved.getAsJsonObject("defaults").entrySet())
                            record.defaults.put(value.getKey(), value.getValue().getAsString());
                    record.destroyTime = saved.get("destroy_time").getAsFloat();
                    record.resistance = saved.get("explosion_resistance").getAsFloat();
                    record.brain = saved.has("brain") && saved.get("brain").getAsBoolean();
                    record.slots = saved.has("slots") ? saved.get("slots").getAsInt() : 0;
                    record.item = saved.has("item") && saved.get("item").getAsBoolean();
                    BLOCKS.put(id, record);
                }
            }
            if (root.has("items")) {
                for (JsonElement id : root.getAsJsonArray("items"))
                    ITEMS.add(Identifier.parse(id.getAsString()));
            }
        } catch (Exception error) {
            // never overwrite a manifest that could not be read: it is the only record of what
            // the worlds on this install may still contain
            broken = true;
            BLOCKS.clear();
            ITEMS.clear();
            KoperLib.LOGGER.error("[Tombstone] could not read {}. Missing content is not held in place this "
                + "session: blocks of packs that are gone load as air. The file is left as it is", MANIFEST, error);
        }
    }

    private static void save() {
        if (!dirty || broken) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject blocks = new JsonObject();
        BLOCKS.forEach((id, record) -> {
            JsonObject saved = new JsonObject();
            JsonObject properties = new JsonObject();
            record.properties.forEach((name, values) -> {
                JsonArray array = new JsonArray();
                values.forEach(array::add);
                properties.add(name, array);
            });
            saved.add("properties", properties);
            JsonObject defaults = new JsonObject();
            record.defaults.forEach(defaults::addProperty);
            saved.add("defaults", defaults);
            saved.addProperty("destroy_time", record.destroyTime);
            saved.addProperty("explosion_resistance", record.resistance);
            saved.addProperty("brain", record.brain);
            saved.addProperty("slots", record.slots);
            saved.addProperty("item", record.item);
            blocks.add(id.toString(), saved);
        });
        root.add("blocks", blocks);
        JsonArray items = new JsonArray();
        ITEMS.forEach(id -> items.add(id.toString()));
        root.add("items", items);
        Path temp = MANIFEST.resolveSibling(MANIFEST.getFileName() + ".tmp");
        try {
            Files.createDirectories(MANIFEST.getParent());
            Files.writeString(temp, GSON.toJson(root));
            try {
                Files.move(temp, MANIFEST, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, MANIFEST, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Exception error) {
            KoperLib.LOGGER.error("[Tombstone] could not write {}. Content added since the last write turns "
                + "into air if its pack is removed before the next successful write", MANIFEST, error);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property property, Object value) {
        return property.getName((Comparable) value);
    }
}
