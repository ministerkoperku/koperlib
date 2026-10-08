package com.koper.koper_lib.core;

import net.fabricmc.api.ClientModInitializer;

/** Noisy debug HUD belongs to the private dev mod, not to production modules. */
public final class KoperstuffClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KoperDebugHud.register();
        KfxPodglad.wlacz();
    }
}
