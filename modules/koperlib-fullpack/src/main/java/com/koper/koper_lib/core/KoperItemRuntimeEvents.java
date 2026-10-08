package com.koper.koper_lib.core;

import com.google.gson.JsonElement;
import com.koper.koper_lib.api.KoperActions;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.data.KoperItemData;
import com.koper.koper_lib.loader.ContentRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KoperItemRuntimeEvents {
    private static final EquipmentSlot[] EQUIP_SLOTS = {
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final Map<UUID, EnumMap<EquipmentSlot, ItemStack>> LAST_EQUIP = new ConcurrentHashMap<>();
    private static boolean registered;

    private KoperItemRuntimeEvents() {}

    public static void init() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int tick = server.getTickCount();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                tickPlayer(player, tick);
            }
        });
    }

    private static void tickPlayer(ServerPlayer player, int tick) {
        tickHeld(player, player.getMainHandItem(), "mainhand");
        tickHeld(player, player.getOffhandItem(), "offhand");
        tickInventory(player, tick);
        tickEquip(player);
    }

    private static void tickHeld(ServerPlayer player, ItemStack stack, String hand) {
        KoperItemData data = data(stack);
        if (data == null || data.events == null) return;
        fire(data, stack, player, "while_held_tick");
        fire(data, stack, player, "while_held_" + hand);
    }

    private static void tickInventory(ServerPlayer player, int tick) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            KoperItemData data = data(stack);
            if (data == null || data.events == null || !data.events.has("on_tick")) continue;
            fire(data, stack, player, "on_tick");
        }
    }

    private static void tickEquip(ServerPlayer player) {
        EnumMap<EquipmentSlot, ItemStack> prev = LAST_EQUIP.computeIfAbsent(player.getUUID(), id -> new EnumMap<>(EquipmentSlot.class));
        for (EquipmentSlot slot : EQUIP_SLOTS) {
            ItemStack now = player.getItemBySlot(slot);
            ItemStack old = prev.getOrDefault(slot, ItemStack.EMPTY);
            boolean sameItem = (!old.isEmpty() && !now.isEmpty() && ItemStack.isSameItem(old, now))
                || (old.isEmpty() && now.isEmpty());
            if (!sameItem) {
                KoperItemData oldData = data(old);
                if (oldData != null && oldData.events != null) fire(oldData, old, player, "on_unequip");
                KoperItemData newData = data(now);
                if (newData != null && newData.events != null) fire(newData, now, player, "on_equip");
                prev.put(slot, now.copy());
            } else if (!now.isEmpty()) {
                prev.put(slot, now.copy());
            }
        }
    }

    private static void fire(KoperItemData data, ItemStack stack, ServerPlayer player, String event) {
        JsonElement actions = data.events.get(event);
        if (actions == null || actions.isJsonNull()) return;
        KoperActions.run(actions,
            new KoperContext(player, player, null, player.level(), stack, player.blockPosition()),
            event,
            data.id);
    }

    private static KoperItemData data(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return ContentRegistry.getItemData(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
