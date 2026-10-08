package com.koper.koper_lib;

import com.koper.koper_lib.loader.CreativeTabRegistry;
import com.koper.koper_lib.loader.UniversalLoader;
import com.koper.koper_lib.loader.resource.VirtualResourcePack;
import com.koper.koper_lib.core.KoperCrashGuard;
import com.koper.koper_lib.core.KoperBlockRuntimeEvents;
import com.koper.koper_lib.core.KoperItemRuntimeEvents;
import com.koper.koper_lib.core.KoperRuntime;
import com.koper.koper_lib.core.KoperTasks;
import com.koper.koper_lib.api.core.KoperConfigs;
import com.koper.koper_lib.api.core.KoperModules;
import com.koper.koper_lib.panama.RustBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import com.koper.koper_lib.loader.ContentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KoperLib implements ModInitializer {
    public static final String MOD_ID = "koper_lib";
    public static final String CONTAINER_ID = "koperlib_fullpack";
    // x.y.z — x=release, y=beta/bugfix, z=alpha. read from fabric.mod.json (expanded from mod_version)
    public static final String VERSION = FabricLoader.getInstance().getModContainer(CONTAINER_ID)
        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.1.0-alpha");
    public static final Logger LOGGER = LoggerFactory.getLogger("KoperLib");
    public static final VirtualResourcePack VIRTUAL_PACK = new VirtualResourcePack();

    public static final boolean HAS_TRINKETS    = FabricLoader.getInstance().isModLoaded("trinkets");
    public static final boolean HAS_ACCESSORIES = FabricLoader.getInstance().isModLoaded("accessories");

    @Override
    public void onInitialize() {
        KoperModules.register("fullpack", VERSION, KoperModules.Environment.COMMON,
            "content", "scripting", "pack-java", "kui", "state", "actions");
        KoperCrashGuard.install();
        KoperRuntime.boot();
        KoperTasks.init();
        KoperItemRuntimeEvents.init();
        KoperBlockRuntimeEvents.init();
        if (KoperRuntime.on(KoperRuntime.Feature.ENGINE)) {
            LOGGER.info("[KoperLib] Rust engine v{} ready.", RustBridge.version());
        }

        KoperCrashGuard.run("content-registry", () ->
            com.koper.koper_lib.loader.ContentRegistry.prepareRegistriesForNewContent());

        com.koper.koper_lib.loader.KoperLibDirectories.init();
        com.koper.koper_lib.api.core.KoperPackSources.register("fullpack",
            com.koper.koper_lib.loader.KoperLibDirectories.FULLPACKS,
            com.koper.koper_lib.loader.FullPackLoader::isEnabled);
        com.koper.koper_lib.api.core.KoperPackSources.setPriority(com.koper.koper_lib.loader.FullPackLoader::priority);
        KoperConfigs.register("fullpack",
            com.koper.koper_lib.fullpack.config.FullpackConfig::load,
            com.koper.koper_lib.fullpack.config.FullpackConfig::save);
        com.koper.koper_lib.fullpack.config.FullpackConfig.load();
        com.koper.koper_lib.api.core.KoperGameplayBridge.install(
            new com.koper.koper_lib.api.core.KoperGameplayBridge.Provider() {
                @Override public float damageMultiplier() {
                    return com.koper.koper_lib.fullpack.config.FullpackConfig.get().globalDamageMultiplier;
                }
                @Override public float healthMultiplier() {
                    return com.koper.koper_lib.fullpack.config.FullpackConfig.get().globalHealthMultiplier;
                }
            });
        KoperRuntime.applyConfig();
        FabricLoader.getInstance().getEntrypoints("koperlib-fullpack-addon", Runnable.class)
            .forEach(addon -> KoperCrashGuard.run("fullpack-addon", addon));
        com.koper.koper_lib.loader.FullPackLoader.loadFullPacks();

        LOGGER.info("[KoperLib] Universal Engine starting...");

        if (!FabricLoader.getInstance().isModLoaded("koperlib_koperstuff"))
            com.koper.koper_lib.loader.FullpackCommands.register();
        com.koper.koper_lib.loader.FullpackNetworking.init();
        com.koper.koper_lib.api.KoperActions.init();
        com.koper.koper_lib.api.KoperCallLua.register();
        com.koper.koper_lib.block.KoperBrainRegistry.register();
        com.koper.koper_lib.state.KoperSoulLua.register(); // koper.pstate.* — persistent per-player state
        com.koper.koper_lib.state.KoperBrainLua.register(); // koper.bstate.* — per-block-position state
        com.koper.koper_lib.state.KoperPeekLua.register();  // real world/entity reads, replaces the rust stubs
        com.koper.koper_lib.state.KoperWorldVault.register(); // koper.data.* — actually saved now
        com.koper.koper_lib.scripting.KoperSnitch.register(); // player:* world events, the thing quests hang off
        com.koper.koper_lib.bedrock.BedrockUszy.register();
        com.koper.koper_lib.bedrock.BedrockStrefy.zarejestruj();
        com.koper.koper_lib.quest.QuestChase.register();      // quests listen to the snitch
        com.koper.koper_lib.quest.QuestLua.register();        // koper.quest.*
        com.koper.koper_lib.quest.QuestPages.register();      // the book screen
        com.koper.koper_lib.quest.QuestMaker.register();      // build quests in game
        com.koper.koper_lib.quest.DialogRunner.register();    // talking to mobs
        com.koper.koper_lib.quest.DialogMaker.register();     // writing those conversations
        com.koper.koper_lib.scripting.KoperScriptCommands.init();
        com.koper.koper_lib.scripting.KoperScriptCommands.register(); // koper.commands.register
        com.koper.koper_lib.kui.KuiMenus.register(); // custom container menu type for kui guis with item slots

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            com.koper.koper_lib.scripting.ScriptCommandDispatcher.drainBacklog();
            com.koper.koper_lib.bedrock.BedrockSkrypciarz.tick(server);
            com.koper.koper_lib.bedrock.BedrockZachowanie.sprzatnijBossy();
            com.koper.koper_lib.bedrock.BedrockRozsiewacz.tick(server);
        });

        // kui container contents persist per world
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
            com.koper.koper_lib.kui.KuiContainers::loadIfNeeded);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
            com.koper.koper_lib.kui.KuiContainers::onStopping);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
            com.koper.koper_lib.state.KoperWorldVault::loadIfNeeded);
        // bedrock addon scripts want a world to talk to, so they start once there is one
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
            com.koper.koper_lib.bedrock.BedrockSkrypciarz::serverStarted);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
            com.koper.koper_lib.state.KoperWorldVault::onStopping);

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            com.koper.koper_lib.scripting.UniversalScriptEngine.setServer(server);
            com.koper.koper_lib.loader.DevPackWatcher.setServer(server);
            com.koper.koper_lib.loader.DevPackWatcher.start();
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            com.koper.koper_lib.loader.DevPackWatcher.stop();
            com.koper.koper_lib.bedrock.BedrockSkrypciarz.serverStopping();
        });

        net.minecraft.core.Registry.register(
            BuiltInRegistries.ENCHANTMENT_ENTITY_EFFECT_TYPE,
            Identifier.fromNamespaceAndPath(MOD_ID, "script_effect"),
            com.koper.koper_lib.enchantment.KoperScriptEnchantmentEffect.CODEC
        );

        if (HAS_TRINKETS)    LOGGER.info("[KoperLib] Trinkets detected.");
        if (HAS_ACCESSORIES) LOGGER.info("[KoperLib] Accessories detected.");

        // brewing mixes for pack potions are data recipes in 26.3, PotionFactory writes them into VIRTUAL_PACK

        com.koper.koper_lib.loader.FullPackLoader.getAllPacks().forEach((folder, meta) -> {
            if (!com.koper.koper_lib.loader.FullPackLoader.isEnabled(folder)) return;
            String ns = meta != null ? meta.getEffectiveNamespace(folder) : folder;
            java.nio.file.Path packRoot = com.koper.koper_lib.loader.KoperLibDirectories.FULLPACKS.resolve(folder);
            com.koper.koper_lib.scripting.JavaHookRegistry.loadPackJava(packRoot, ns);
        });

        // addons use this for blocks that need a real class before their FULLPACK json is scanned
        FabricLoader.getInstance().getEntrypoints("koperlib-early-content", Runnable.class)
            .forEach(Runnable::run);

        UniversalLoader loader = new UniversalLoader();
        loader.loadContent();

        // runs after normal and FullPack blocks exist, but before generated data is reloaded
        FabricLoader.getInstance().getEntrypoints("koperlib-after-content", Runnable.class)
            .forEach(Runnable::run);

        CreativeTabRegistry.processTabs(MOD_ID);

        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.SERVER_DATA)
            .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                @Override public Identifier getFabricId() { return Identifier.fromNamespaceAndPath(MOD_ID, "script_reloader"); }
                @Override public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                    LOGGER.info("[KoperLib] Reloading logic...");
                }
            });

        LOGGER.info("[KoperLib] Loaded.");
    }

}
