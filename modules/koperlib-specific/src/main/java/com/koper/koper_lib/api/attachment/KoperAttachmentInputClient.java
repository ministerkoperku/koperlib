package com.koper.koper_lib.api.attachment;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Environment(EnvType.CLIENT)
public final class KoperAttachmentInputClient {

    @FunctionalInterface
    public interface PressHook {
        boolean onPress(Minecraft minecraft);
    }

    private static final List<PressHook> PRESS = new CopyOnWriteArrayList<>();

    private KoperAttachmentInputClient() {}

    public static void registerPress(PressHook hook) {
        PRESS.add(hook);
    }

    public static boolean firePress(Minecraft minecraft) {
        for (PressHook hook : PRESS) {
            try {
                if (hook.onPress(minecraft)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
