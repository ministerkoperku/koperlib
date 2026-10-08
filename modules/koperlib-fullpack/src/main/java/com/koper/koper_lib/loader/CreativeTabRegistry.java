package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.*;

public class CreativeTabRegistry {
    private static final Map<String, ResourceKey<CreativeModeTab>> CUSTOM_TABS      = new HashMap<>();
    private static final Map<String, CreativeModeTab>              CUSTOM_INSTANCES = new HashMap<>();
    private static final Map<String, List<Item>>                   DEFERRED_ITEMS   = new HashMap<>();
    private static final Map<String, String>                       TAB_ICONS        = new HashMap<>();
    private static final Map<String, Integer>                      TAB_ORDERS       = new HashMap<>();
    private static final Map<String, String>                       TAB_NAMES        = new HashMap<>();
    // tabs that have a stable event listener registered — never cleared across reloads
    private static final Set<String>                               LISTENERS_REGISTERED = new HashSet<>();

    public static void defineTabIcon(String tabName, String iconItemId) {
        TAB_ICONS.put(tabName, iconItemId);
    }

    public static void defineTab(String tabName, String displayName, String iconItemId, int order) {
        TAB_ICONS.put(tabName, iconItemId);
        TAB_ORDERS.put(tabName, order);
        if (displayName != null && !displayName.isEmpty()) TAB_NAMES.put(tabName, displayName);
    }

    public static void addToTab(String tabName, Item item) {
        // a block with no item (technical ones, registered from java) resolves to air here, and one air
        // stack in a tab kills the creative screen with "Stack size must be exactly 1"
        if (item == null || item == Items.AIR) return;
        if (tabName == null || tabName.isEmpty()) tabName = "koper_lib:main";
        List<Item> items = DEFERRED_ITEMS.computeIfAbsent(tabName, k -> new ArrayList<>());
        if (!items.contains(item)) items.add(item);
    }

    // clears item lists for reload — registry entries and listeners are managed separately
    public static void clearDeferredItems() {
        DEFERRED_ITEMS.clear();
        TAB_ICONS.clear();
        TAB_ORDERS.clear();
        TAB_NAMES.clear();
    }

    // called AFTER loadExternalContent() so DEFERRED_ITEMS reflects enabled packs only.
    // removes from MC registry only the tabs whose packs are disabled (no items after reload).
    // enabled-pack tabs stay in registry — their stale item cache is rebuilt on next creative open.
    public static void removeDisabledPackTabs() {
        if (CUSTOM_TABS.isEmpty()) return;
        if (!(BuiltInRegistries.CREATIVE_MODE_TAB instanceof IKoperRegistry reg)) return;

        int removed = 0;
        java.util.Iterator<Map.Entry<String, ResourceKey<CreativeModeTab>>> it =
            CUSTOM_TABS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ResourceKey<CreativeModeTab>> entry = it.next();
            if (!DEFERRED_ITEMS.containsKey(entry.getKey())) {
                reg.koperlib$removeEntry(entry.getValue());
                LISTENERS_REGISTERED.remove(entry.getKey());
                CUSTOM_INSTANCES.remove(entry.getKey());
                it.remove();
                removed++;
                KoperLib.LOGGER.info("[CreativeTabRegistry] Removed disabled tab: {}", entry.getKey());
            }
        }
        if (removed > 0)
            KoperLib.LOGGER.info("[CreativeTabRegistry] Removed {} disabled pack tab(s) from registry.", removed);
    }

    public static void processTabs(String modId) {
        List<Map.Entry<String, List<Item>>> sorted = new ArrayList<>(DEFERRED_ITEMS.entrySet());
        sorted.sort(Comparator.comparingInt(e -> TAB_ORDERS.getOrDefault(e.getKey(), 999)));

        for (Map.Entry<String, List<Item>> entry : sorted) {
            String tabName = entry.getKey();

            if (tabName.startsWith("minecraft:")) {
                // register stable listener once per vanilla tab
                if (!LISTENERS_REGISTERED.contains(tabName)) {
                    Identifier vanillaId = Identifier.tryParse(tabName);
                    if (vanillaId != null) {
                        ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, vanillaId);
                        LISTENERS_REGISTERED.add(tabName);
                        CreativeModeTabEvents.modifyOutputEvent(key).register(content -> {
                            for (Item item : DEFERRED_ITEMS.getOrDefault(tabName, List.of())) {
                                if (item != Items.AIR) content.accept(item);
                            }
                        });
                    }
                }
                continue;
            }

            Identifier tabId = Identifier.tryParse(tabName);
            if (tabId == null) tabId = Identifier.fromNamespaceAndPath(modId, tabName.replace(":", "_"));
            final String finalTabName = tabName;

            ResourceKey<CreativeModeTab> customKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, tabId);

            if (!BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(tabId)) {
                String displayName = resolveDisplayName(tabName, tabId);
                // displayItems reads DEFERRED_ITEMS live on every rebuildTabContents()
                // this survives reloadResourcePacks() because it's a closure, not a Fabric event listener
                // Fabric wipes modifyOutputEvent listeners on client resource reload — so we DON'T use that here
                CreativeModeTab tab = FabricCreativeModeTab.builder()
                    .title(Component.literal(displayName))
                    .icon(() -> {
                        String iconId = TAB_ICONS.get(finalTabName);
                        if (iconId != null) {
                            Item iconItem = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(iconId));
                            if (iconItem != null && iconItem != Items.AIR) return new ItemStack(iconItem);
                        }
                        List<Item> cur = DEFERRED_ITEMS.getOrDefault(finalTabName, List.of());
                        return cur.isEmpty() ? new ItemStack(Items.STONE) : new ItemStack(cur.get(0));
                    })
                    .displayItems((params, output) -> {
                        DEFERRED_ITEMS.getOrDefault(finalTabName, List.of())
                                      .stream().filter(item -> item != Items.AIR)
                                      .forEach(item -> output.accept(new ItemStack(item)));
                    })
                    .build();
                Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, customKey, tab);
                CUSTOM_TABS.put(tabName, customKey);
                CUSTOM_INSTANCES.put(tabName, tab);
            }
        }
    }

    // Fabric assigns page numbers inside buildAllTabContents() (called by tryRebuildTabContents).
    // Direct buildContents() bypasses that → fabric_getPage throws → crash.
    // So: null CACHED_PARAMETERS to force needsUpdate() true, then call tryRebuildTabContents immediately.
    // The full Fabric pipeline runs, pages get assigned, screen renders clean.
    public static void forceFullTabRebuild(
            net.minecraft.world.flag.FeatureFlagSet features,
            boolean perms,
            net.minecraft.core.HolderLookup.Provider registry) {
        try {
            var f = net.minecraft.world.item.CreativeModeTabs.class.getDeclaredField("CACHED_PARAMETERS");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[CreativeTabRegistry] CACHED_PARAMETERS reset blew up: {}", e.getMessage());
        }
        // Fabric page assignment happens inside here — must call this, not buildContents directly
        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(features, perms, registry);
    }

    private static String resolveDisplayName(String tabName, Identifier tabId) {
        String name = TAB_NAMES.getOrDefault(tabName, null);
        if (name != null) return name;
        String raw = tabId.getPath().replace("_", " ");
        if ("main".equalsIgnoreCase(tabId.getPath())) raw = tabId.getNamespace().replace("_", " ");
        return Arrays.stream(raw.split(" "))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
            .collect(java.util.stream.Collectors.joining(" "));
    }
}
