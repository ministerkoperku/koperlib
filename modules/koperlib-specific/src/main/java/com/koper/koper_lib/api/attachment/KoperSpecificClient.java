package com.koper.koper_lib.api.attachment;

import net.fabricmc.api.ClientModInitializer;

/** Client hooks owned by koperlib-specific. */
public final class KoperSpecificClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KoperAttachmentRenderer.register();
        KoperPlacementClient.register();
    }
}
