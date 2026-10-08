package com.koper.koper_lib.data;

import com.koper.koper_lib.KoperLib;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class KoperStackData {
    private static final String ROOT = "koper";

    private KoperStackData() {}

    public static CompoundTag copy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new CompoundTag();
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompoundOrEmpty(ROOT).copy();
    }

    public static String snbt(ItemStack stack) {
        return copy(stack).toString();
    }

    public static void setSnbt(ItemStack stack, String snbt) {
        if (stack == null || stack.isEmpty()) return;
        try {
            CompoundTag parsed = snbt == null || snbt.isBlank() ? new CompoundTag() : parseSnbt(snbt);
            setAll(stack, parsed);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[ItemData] bad SNBT '{}': {}", snbt, e.getMessage());
        }
    }

    public static String getString(ItemStack stack, String key, String fallback) {
        CompoundTag data = copy(stack);
        return data.contains(key) ? data.getStringOr(key, fallback) : fallback;
    }

    public static double getDouble(ItemStack stack, String key, double fallback) {
        CompoundTag data = copy(stack);
        return data.contains(key) ? data.getDoubleOr(key, fallback) : fallback;
    }

    public static int getInt(ItemStack stack, String key, int fallback) {
        CompoundTag data = copy(stack);
        return data.contains(key) ? data.getIntOr(key, fallback) : fallback;
    }

    public static boolean getBool(ItemStack stack, String key, boolean fallback) {
        CompoundTag data = copy(stack);
        return data.contains(key) ? data.getBooleanOr(key, fallback) : fallback;
    }

    public static void setString(ItemStack stack, String key, String value) {
        mutate(stack, data -> data.putString(key, value == null ? "" : value));
    }

    public static void setDouble(ItemStack stack, String key, double value) {
        mutate(stack, data -> data.putDouble(key, value));
    }

    public static void setInt(ItemStack stack, String key, int value) {
        mutate(stack, data -> data.putInt(key, value));
    }

    public static void setBool(ItemStack stack, String key, boolean value) {
        mutate(stack, data -> data.putBoolean(key, value));
    }

    public static void remove(ItemStack stack, String key) {
        mutate(stack, data -> data.remove(key));
    }

    public static void setAll(ItemStack stack, CompoundTag koperData) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            if (koperData == null || koperData.isEmpty()) root.remove(ROOT);
            else root.put(ROOT, koperData.copy());
        });
    }

    private static void mutate(ItemStack stack, java.util.function.Consumer<CompoundTag> edit) {
        if (stack == null || stack.isEmpty() || edit == null) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag data = root.getCompoundOrEmpty(ROOT).copy();
            edit.accept(data);
            if (data.isEmpty()) root.remove(ROOT);
            else root.put(ROOT, data);
        });
    }

    private static CompoundTag parseSnbt(String snbt) throws Exception {
        Class<?> parser = Class.forName("net.minecraft.nbt.TagParser");
        for (String name : new String[]{"parseTag", "parseCompoundFully", "parseCompoundAsArgument"}) {
            try {
                Object out = parser.getMethod(name, String.class).invoke(null, snbt);
                if (out instanceof CompoundTag tag) return tag;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("No public SNBT parser found on TagParser");
    }
}
