package com.koper.koper_lib.api.core;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// items that steal the attack button for their own gesture (connector: press-drag-release).
// while one is held NOTHING may mine — otherwise the drag eats the block you were wiring up.
// lives in core because specific knows the tools and khysics does the kontra mining, and those two
// never see each other
public final class KoperToolHogger {

    @FunctionalInterface
    public interface Check {
        boolean hogs(ItemStack held);
    }

    private static final List<Check> CHECKS = new CopyOnWriteArrayList<>();

    private KoperToolHogger() {}

    public static void register(Check check) {
        if (check != null) CHECKS.add(check);
    }

    public static boolean blocksMining(ItemStack held) {
        if (held == null || held.isEmpty()) return false;
        for (Check check : CHECKS) {
            try {
                if (check.hogs(held)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
