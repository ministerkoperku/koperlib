package com.koper.koper_lib.physics;

import com.koper.koper_lib.api.core.KoperModules;
import com.koper.koper_lib.network.KenderRemovePayload;
import com.koper.koper_lib.network.KoperNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Standalone Khysics lifecycle. Create support remains conditional inside this mod. */
public final class KoperKhysicsMod implements ModInitializer {
    public static final String MOD_ID = "koperlib_khysics";

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        KoperModules.register("khysics", version, KoperModules.Environment.COMMON,
            "physics", "kontraptions", "local-grid", "kender-adapter");
        com.koper.koper_lib.api.core.KoperConfigs.register("khysics", KhysicsConfig::load, KhysicsConfig::save);
        KhysicsConfig.load();

        com.koper.koper_lib.physics.koperer.KopererPickerCmd.install();

        KhysicsNetworking.init();
        KhysicsCompat.init();
        if (FabricLoader.getInstance().isModLoaded("koperlib_kodel"))
            KhysicsKodelCompat.install();

        ServerTickEvents.START_SERVER_TICK.register(server -> KoperPhys.tickPlayerKontraInteractions(server));
        ServerTickEvents.END_SERVER_TICK.register(KoperPhys::tickAll);
        ServerTickEvents.END_SERVER_TICK.register(com.koper.koper_lib.network.KontraMotionServer::tick);
        ServerTickEvents.END_SERVER_TICK.register(KontraTransfer::recover);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> com.koper.koper_lib.network.KontraMotionServer.clear());

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!(player.getItemInHand(hand).getItem() instanceof KhysSelectionWand))
                return InteractionResult.PASS;
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                KoperPhys.getTwoPointSelection(sp.getUUID())[0] = pos;
                sp.sendSystemMessage(Component.literal("§a[Khysics] Pos 1 set to " + pos.toShortString()));
            }
            return InteractionResult.SUCCESS;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            KoperPhys.setWorldSave(server.getWorldData().getLevelName());
            KoperPhys.clearAll();
            com.koper.koper_lib.physics.weight.KhysWeightBook.loadAll();
            com.koper.koper_lib.physics.dim.KhysDimensions.loadAll();
            com.koper.koper_lib.physics.dim.KhysDimensions.applyAll(server);
            KontraWorldData.load(server);
        });

        Map<UUID, String> playerDimensions = new ConcurrentHashMap<>();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 6000 == 0 && !KoperPhys.all().isEmpty())
                KontraWorldData.save(server);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String dimension = KoperPhys.levelKey((ServerLevel) player.level());
                String previous = playerDimensions.put(player.getUUID(), dimension);
                if (previous != null && !previous.equals(dimension)) {
                    for (long id : KoperPhys.all().keySet()) {
                        KenderSyncServer.cancel(player,id);
                        KoperNetworking.sendToPlayer(player, new KenderRemovePayload(id));
                    }
                    KoperPhys.sendAllToPlayer(player);
                }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(KenderSyncServer::tick);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler,server) ->
            playerDimensions.remove(handler.player.getUUID()));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            KontraTransfer.clear(server);
            KontraWorldData.save(server);
            KontraWorldData.unload();
            KoperPhys.clearAll();
        });

        Registry.register(BuiltInRegistries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(MOD_ID, "kontra_seat"), KontraSeat.TYPE);
    }
}
