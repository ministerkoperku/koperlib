package com.koper.koper_lib.api;

import net.minecraft.world.InteractionResult;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

// read/write access to the fullpack system — if you're another mod using koperlib, start here
public interface FullPackAPI {

    // --- read ---

    Optional<KoperPackSnapshot> getPack(String namespace);

    List<KoperPackSnapshot> listAll();
    List<KoperPackSnapshot> listEnabled();

    boolean isEnabled(String namespace);

    // --- write (runtime only, no restart needed) ---

    // persists to options.json, does NOT trigger reload automatically
    void setEnabled(String namespace, boolean enabled);

    // forces a full reload — blocks until complete
    // must be called from server thread
    void reload();

    // --- hooks (mod-side, survive fullpack reloads) ---

    // hook that can return SUCCESS/FAIL to short-circuit lua scripts for that event
    // hookId format: "namespace:item_id/event_name" e.g. "koper_lib:flame_sword/on_use"
    // event names: on_use, on_hit, on_attack, on_equip, on_unequip, on_tick, on_break, on_throw, on_drop
    void addItemHook(String itemFullId, String event, Function<KoperContext, InteractionResult> hook);

    default void addItemListener(String itemFullId, String event, Consumer<KoperContext> listener) {
        addItemHook(itemFullId, event, ctx -> { listener.accept(ctx); return InteractionResult.PASS; });
    }

    // entity hooks — on_spawn, on_tick, on_interact, on_damage, on_death
    void addEntityHook(String entityFullId, String event, Function<KoperContext, InteractionResult> hook);

    default void addEntityListener(String entityFullId, String event, Consumer<KoperContext> listener) {
        addEntityHook(entityFullId, event, ctx -> { listener.accept(ctx); return InteractionResult.PASS; });
    }

    // block hooks — on_use, on_place, on_break
    void addBlockHook(String blockFullId, String event, Function<KoperContext, InteractionResult> hook);

    default void addBlockListener(String blockFullId, String event, Consumer<KoperContext> listener) {
        addBlockHook(blockFullId, event, ctx -> { listener.accept(ctx); return InteractionResult.PASS; });
    }
}
