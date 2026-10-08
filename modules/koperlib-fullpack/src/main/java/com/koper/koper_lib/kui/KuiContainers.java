package com.koper.koper_lib.kui;

import com.koper.koper_lib.KoperLib;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// backing inventories for kui container guis, keyed by GUI ID (one named container per id, shared).
// open the same id -> see the same stuff. saved to the world folder so it survives relog + restart.
public final class KuiContainers {
    private static final Map<String, SimpleContainer> STORE = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private KuiContainers() {}

    public static SimpleContainer get(String guiId, int size) {
        return STORE.computeIfAbsent(guiId, g -> new SimpleContainer(size));
    }

    // one-way move from the old key-string store into a block's own brain, done the first time
    // that block's gui is opened. leftovers that don't fit stay put rather than vanish.
    public static void moveInto(String key, net.minecraft.world.Container target) {
        SimpleContainer old = STORE.get(key);
        if (old == null || target.getContainerSize() == 0) return;

        boolean movedAny = false, leftBehind = false;
        for (int i = 0; i < old.getContainerSize(); i++) {
            ItemStack stack = old.getItem(i);
            if (stack.isEmpty()) continue;
            if (i < target.getContainerSize() && target.getItem(i).isEmpty()) {
                target.setItem(i, stack.copy());
                old.setItem(i, ItemStack.EMPTY);
                movedAny = true;
            } else {
                leftBehind = true;
            }
        }
        if (!movedAny) return;
        target.setChanged();
        if (!leftBehind) STORE.remove(key);
        KoperLib.LOGGER.info("[Kui] moved container {} into its block entity{}",
            key, leftBehind ? " (some slots didn't fit and stayed in the old store)" : "");
    }

    public static void dropAndClear(String key, net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        SimpleContainer cont = STORE.remove(key);
        if (cont == null || level == null || pos == null) return;
        for (int i = 0; i < cont.getContainerSize(); i++) {
            ItemStack stack = cont.getItem(i);
            if (!stack.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(level,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack.copy());
                cont.setItem(i, ItemStack.EMPTY);
            }
        }
        cont.setChanged();
    }

    public static void shrinkIntoOrDrop(String oldKey, String newKey, int newSize,
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (oldKey == null || oldKey.equals(newKey)) return;
        SimpleContainer old = STORE.remove(oldKey);
        if (old == null) return;
        if (newKey == null || newSize <= 0) {
            dropAll(old, level, pos);
            return;
        }

        SimpleContainer next = STORE.get(newKey);
        if (next == null || next.getContainerSize() != newSize) {
            SimpleContainer resized = new SimpleContainer(newSize);
            if (next != null) {
                int copy = Math.min(next.getContainerSize(), resized.getContainerSize());
                for (int i = 0; i < copy; i++) resized.setItem(i, next.getItem(i).copy());
                for (int i = copy; i < next.getContainerSize(); i++) {
                    ItemStack stack = next.getItem(i);
                    if (!stack.isEmpty()) drop(level, pos, stack.copy());
                }
            }
            next = resized;
            STORE.put(newKey, next);
        }
        for (int i = 0; i < old.getContainerSize(); i++) {
            ItemStack stack = old.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack copy = stack.copy();
            if (i < next.getContainerSize() && next.getItem(i).isEmpty()) {
                next.setItem(i, copy);
                continue;
            }
            int slot = firstEmpty(next);
            if (slot >= 0) next.setItem(slot, copy);
            else drop(level, pos, copy);
        }
        next.setChanged();
    }

    private static int firstEmpty(SimpleContainer cont) {
        for (int i = 0; i < cont.getContainerSize(); i++) {
            if (cont.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private static void dropAll(SimpleContainer cont, net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        for (int i = 0; i < cont.getContainerSize(); i++) {
            ItemStack stack = cont.getItem(i);
            if (!stack.isEmpty()) drop(level, pos, stack.copy());
        }
        cont.setChanged();
    }

    private static void drop(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, ItemStack stack) {
        if (level == null || pos == null || stack.isEmpty()) return;
        net.minecraft.world.Containers.dropItemStack(level,
            pos.getX() + 0.5, pos.getY() + 1.15, pos.getZ() + 0.5, stack);
    }

    public static void loadIfNeeded(MinecraftServer server) {
        if (!loaded) load(server);
    }

    public static void onStopping(MinecraftServer server) {
        save(server);
        loaded = false;
    }

    // ── persistence ──────────────────────────────────────────────────────────────

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("koperlib").resolve("kui_containers.nbt");
    }

    public static void load(MinecraftServer server) {
        STORE.clear();
        Path f = file(server);
        if (!Files.exists(f)) { loaded = true; KoperLib.LOGGER.info("[Kui] no saved containers at {}", f); return; }
        try {
            CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
            var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
            int totalItems = 0;
            for (String gid : root.keySet()) {
                CompoundTag cTag = root.getCompoundOrEmpty(gid);
                int size = Math.max(1, cTag.getIntOr("size", 1));
                SimpleContainer cont = new SimpleContainer(size);
                for (Tag t : cTag.getListOrEmpty("items")) {
                    if (!(t instanceof CompoundTag slot)) continue;
                    int idx = slot.getIntOr("s", -1);
                    if (idx < 0 || idx >= size) continue;
                    var parsed = ItemStack.OPTIONAL_CODEC.parse(ops, slot.getCompoundOrEmpty("i"))
                        .resultOrPartial(err -> KoperLib.LOGGER.warn("[Kui] item parse failed: {}", err));
                    if (parsed.isPresent()) { cont.setItem(idx, parsed.get()); totalItems++; }
                }
                STORE.put(gid, cont);
            }
            loaded = true; // only after a clean read — a throw above leaves it false so JOIN retries
            KoperLib.LOGGER.info("[Kui] loaded {} container(s), {} item stack(s) from {}", STORE.size(), totalItems, f.getFileName());
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[Kui] couldn't load containers (will retry on join): {}", e.toString());
        }
    }

    public static void save(MinecraftServer server) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        CompoundTag root = new CompoundTag();
        STORE.forEach((gid, cont) -> {
            CompoundTag cTag = new CompoundTag();
            cTag.putInt("size", cont.getContainerSize());
            ListTag items = new ListTag();
            for (int i = 0; i < cont.getContainerSize(); i++) {
                ItemStack s = cont.getItem(i);
                if (s.isEmpty()) continue;
                CompoundTag slot = new CompoundTag();
                slot.putInt("s", i);
                ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).result().ifPresent(tag -> slot.put("i", tag));
                items.add(slot);
            }
            if (!items.isEmpty()) { cTag.put("items", items); root.put(gid, cTag); }
        });
        try {
            Path f = file(server);
            Files.createDirectories(f.getParent());
            NbtIo.writeCompressed(root, f);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[Kui] couldn't save containers: {}", e.getMessage());
        }
    }
}
