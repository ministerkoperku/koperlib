package com.koper.koper_lib.kfx.runtime;

import net.minecraft.server.level.ServerLevel;

@FunctionalInterface
public interface KfxEventListener {
    void onImpact(ServerLevel level, KfxImpact impact);
}
