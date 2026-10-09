package com.koper.koper_lib.block;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperBlockData;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// ONE BlockEntityType for every pack block that wants a brain.
// registering a type per pack block would mean touching a frozen registry on every reload.
// VALID is the live answer to "does this block want a brain"; bind() also tells the type itself.
public final class KoperBrainRegistry {

    private KoperBrainRegistry() {}

    private static final Set<Block> VALID = ConcurrentHashMap.newKeySet();
    private static final Map<Block, Integer> SLOTS = new ConcurrentHashMap<>();

    public static final BlockEntityType<KoperBlockBrain> TYPE =
        new BlockEntityType<>(KoperBlockBrain::new, VALID);

    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "block_brain"), TYPE);
    }

    // reload wipes the block set — the Block objects themselves are recreated by the factory
    public static void clear() {
        VALID.clear();
        SLOTS.clear();
    }

    public static void bind(Block block, int slotCount) {
        if (block == null) return;
        VALID.add(block);
        SLOTS.put(block, Math.max(0, slotCount));
        com.koper.koper_lib.loader.FullpackTombstones.noteBrain(block, Math.max(0, slotCount));
        // the live-set-by-reference trick above is not enough on its own: fabric's addValidBlock swaps
        // the type's set for its own copy, and from then on TYPE.isValid never saw a block bound
        // afterwards. every pack machine placed like that got no block entity, no inventory and no
        // bstate. telling fabric directly keeps both sets in step; wants() still gates it, so a block
        // unbound by a reload stays brainless even though fabric's set only grows
        if (!TYPE.isValid(block.defaultBlockState()))
            ((net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityType) TYPE).addValidBlock(block);
    }

    // self healing. the bind used to depend on the factory having run in the right order, and when
    // it hadn't, MC threw "Invalid block entity ... got Block{...}" and took the client down.
    // now a miss looks the block up and binds it on the spot, so ordering cannot break placement
    public static boolean wants(Block block) {
        if (block == null) return false;
        if (VALID.contains(block)) return true;

        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) return false;
        KoperBlockData data = com.koper.koper_lib.loader.ContentRegistry.getBlockData(id);
        if (data == null || !shouldHaveBrain(data)) return false;

        bind(block, slotsFor(data));
        KoperLib.LOGGER.debug("[Brain] late bound {} — factory order missed it", id);
        return true;
    }

    // slot count comes from the kui page the block points at; no page means state only
    public static int slotsFor(KoperBlockData data) {
        return data.gui != null && !data.gui.isBlank()
            ? com.koper.koper_lib.kui.KuiOpen.slotCountFor(data.gui) : 0;
    }

    public static int slotCount(Block block) { return SLOTS.getOrDefault(block, 0); }

    // a block gets a brain when it needs somewhere to put things: an inventory, per-position
    // scripting state, or its own ticker. explicit "block_entity" wins either way.
    public static boolean shouldHaveBrain(KoperBlockData data) {
        if (data == null) return false;
        if (data.blockEntity != null) return data.blockEntity;
        if (data.gui != null && !data.gui.isBlank()) return true;
        if (data.connectedCubeGuis != null && !data.connectedCubeGuis.isEmpty()) return true;
        return data.events != null && data.events.has("on_tick");
    }
}
