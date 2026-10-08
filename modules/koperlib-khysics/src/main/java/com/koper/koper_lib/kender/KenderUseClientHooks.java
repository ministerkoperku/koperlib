package com.koper.koper_lib.kender;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class KenderUseClientHooks {

    @FunctionalInterface
    public interface Hook {
        boolean use(Minecraft minecraft, KenderTargeting.PhysHit hit);
    }

    private static final List<Hook> HOOKS = new CopyOnWriteArrayList<>();

    private KenderUseClientHooks() {}

    public static void add(Hook hook) {
        if (hook != null) HOOKS.add(hook);
    }

    public static boolean fire(Minecraft minecraft, KenderTargeting.PhysHit hit) {
        for (Hook hook : HOOKS) {
            if (hook.use(minecraft, hit)) return true;
        }
        return false;
    }
}
