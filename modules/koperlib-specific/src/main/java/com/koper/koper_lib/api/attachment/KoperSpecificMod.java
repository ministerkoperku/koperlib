package com.koper.koper_lib.api.attachment;

import com.koper.koper_lib.api.core.KoperModules;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Entrypoint for Koper-specific gameplay APIs used by Koper's own mods. */
public final class KoperSpecificMod implements ModInitializer {
    public static final String MOD_ID = "koperlib_specific";

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        KoperModules.register("specific", version, KoperModules.Environment.COMMON,
            "attachments", "multiblocks", "workstations", "companions", "combat");
        KoperPlacementNetworking.init();
        // connector-ish tools own the attack button; nothing may mine while one is held
        com.koper.koper_lib.api.core.KoperToolHogger.register(
            stack -> KoperAttachments.isTool(stack.getItem()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 100 != 0) return;
            for (var level : server.getAllLevels())
                com.koper.koper_lib.api.workstation.KoperPhysicalWorkstation.clearOrphans(level);
        });
    }
}
