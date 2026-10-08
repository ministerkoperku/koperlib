package com.koper.koper_lib.block;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperBlockData;
import com.koper.koper_lib.loader.ContentRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

// the thing pack blocks were missing. holds per-position state + optional inventory.
// one BE class for every pack block that asks for one — no per-block java, no per-block type.
public class KoperBlockBrain extends BlockEntity implements Container {

    // koper.bstate.* lives in here. namespaced so two packs on the same block don't collide
    private CompoundTag brain = new CompoundTag();
    private NonNullList<ItemStack> slots = NonNullList.create();

    public KoperBlockBrain(BlockPos pos, BlockState state) {
        super(KoperBrainRegistry.TYPE, pos, state);
        resizeFor(state);
    }

    // slot count comes from the kui page the block declared; 0 = state only, no inventory
    private void resizeFor(BlockState state) {
        int want = KoperBrainRegistry.slotCount(state.getBlock());
        if (want == slots.size()) return;
        NonNullList<ItemStack> next = NonNullList.withSize(want, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(want, slots.size()); i++) next.set(i, slots.get(i));
        slots = next;
    }

    // ── state ─────────────────────────────────────────────────────────────────

    public CompoundTag brain() { return brain; }

    public CompoundTag namespace(String ns) {
        CompoundTag sub = brain.getCompound(ns).orElse(null);
        if (sub == null) { sub = new CompoundTag(); brain.put(ns, sub); }
        return sub;
    }

    public void dirty() {
        setChanged();
        if (level != null && !level.isClientSide())
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ── persistence ───────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        brain = in.read("koper_brain", CompoundTag.CODEC).orElseGet(CompoundTag::new);
        resizeFor(getBlockState());
        if (!slots.isEmpty()) ContainerHelper.loadAllItems(in, slots);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (!brain.isEmpty()) out.store("koper_brain", CompoundTag.CODEC, brain);
        if (!slots.isEmpty()) ContainerHelper.saveAllItems(out, slots);
    }

    // client needs the state for rendering conditions; items stay server-side
    @Override
    public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (!brain.isEmpty()) tag.put("koper_brain", brain.copy());
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Container — this is the whole point, hoppers and comparators see us now ──

    @Override public int getContainerSize() { return slots.size(); }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : slots) if (!s.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) {
        return slot >= 0 && slot < slots.size() ? slots.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack taken = ContainerHelper.removeItem(slots, slot, count);
        if (!taken.isEmpty()) dirty();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(slots, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= slots.size()) return;
        slots.set(slot, stack);
        stack.limitSize(getMaxStackSize(stack));
        dirty();
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null
            && level.getBlockEntity(worldPosition) == this
            && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        slots.clear();
        dirty();
    }

    public NonNullList<ItemStack> stacks() { return slots; }

    // ── ticker ────────────────────────────────────────────────────────────────

    // real per-block ticking. blocks with a brain no longer ride the player-proximity cube scan
    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, KoperBlockBrain be) {
        KoperBlockData data = ContentRegistry.getBlockData(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (data == null || data.events == null || !data.events.has("on_tick")) return;
        int interval = Math.max(1, data.tickInterval != null ? data.tickInterval : 20);
        if (level.getGameTime() % interval != 0) return;
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        try {
            KoperBrainRuntime.fireTick(data, sl, pos);
        } catch (Throwable t) {
            KoperLib.LOGGER.warn("[Brain] on_tick blew up at {}: {}", pos, t.getMessage());
        }
    }
}
