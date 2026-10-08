package com.koper.koper_lib.core;

import com.google.gson.JsonElement;
import com.koper.koper_lib.api.KoperActions;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.data.KoperBlockData;
import com.koper.koper_lib.loader.ContentRegistry;
import com.koper.koper_lib.scripting.JavaHookRegistry;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;

public final class KoperBlockRuntimeEvents {
    private static boolean registered;

    private KoperBlockRuntimeEvents() {}

    // the scan is O(players * r^3) getBlockState, so everything that can be answered without
    // touching the world gets answered once per reload instead of once per tick
    private static volatile Plan plan;

    // stepEvent/tickEvent: does ANY block want it. radius: biggest cube we must sweep.
    // beat: gcd of every tick_interval — on ticks that aren't a multiple, nothing is due, skip the whole thing
    private record Plan(boolean stepEvent, boolean tickEvent, int radius, int beat) {}

    // called from the reload path — block data changed, everything cached here is stale
    public static void invalidate() { plan = null; }

    public static void init() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Plan p = plan();
            if (!p.stepEvent() && !p.tickEvent()) return;

            int tick = server.getTickCount();
            if (p.stepEvent()) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) tickStep(player);
            }
            // nothing is due this tick — don't pay for the sweep at all
            if (p.tickEvent() && tick % p.beat() == 0) {
                HashSet<String> seen = new HashSet<>();
                for (ServerPlayer player : server.getPlayerList().getPlayers())
                    tickNearPlayer(player, tick, p.radius(), seen);
            }
        });
    }

    private static void tickStep(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        BlockPos pos = BlockPos.containing(player.getX(), player.getY() - 0.2, player.getZ());
        KoperBlockData data = data(level.getBlockState(pos));
        if (data == null || data.events == null || !data.events.has("on_step")) return;
        fire(data, player, level, pos, "on_step", ScriptEvent.ON_STEP);
    }

    private static void tickNearPlayer(ServerPlayer player, int tick, int radius, HashSet<String> seen) {
        if (!(player.level() instanceof ServerLevel level)) return;
        BlockPos center = player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    pos.set(x, y, z);
                    String key = level.dimension().identifier() + ":" + pos.asLong();
                    if (!seen.add(key)) continue;
                    BlockState state = level.getBlockState(pos);
                    // blocks with a brain tick themselves — scanning them too would double-fire
                    if (com.koper.koper_lib.block.KoperBrainRegistry.wants(state.getBlock())) continue;
                    KoperBlockData data = data(state);
                    if (data == null || data.events == null || !data.events.has("on_tick")) continue;
                    int interval = Math.max(1, data.tickInterval != null ? data.tickInterval : 20);
                    if (tick % interval != 0) continue;
                    // per-block radius: one block asking for 16 must not drag every other block's range up
                    int localRadius = Math.max(1, data.tickRadius != null ? data.tickRadius : 8);
                    if (center.distManhattan(pos) > localRadius * 3) continue;
                    fire(data, player, level, pos.immutable(), "on_tick", ScriptEvent.ON_TICK);
                }
            }
        }
    }

    private static Plan plan() {
        Plan p = plan;
        if (p != null) return p;
        boolean step = false, tickly = false;
        int radius = 0, beat = 0;
        for (KoperBlockData data : ContentRegistry.getAllBlockData().values()) {
            if (data.events == null) continue;
            if (data.events.has("on_step")) step = true;
            if (!data.events.has("on_tick")) continue;
            // if every ticking block owns a brain, the sweep has nothing left to find
            if (hasBrain(data.id)) continue;
            tickly = true;
            radius = Math.max(radius, data.tickRadius != null ? data.tickRadius : 8);
            beat = gcd(beat, Math.max(1, data.tickInterval != null ? data.tickInterval : 20));
        }
        p = new Plan(step, tickly, Math.min(Math.max(radius, 1), 16), Math.max(1, beat));
        plan = p;
        return p;
    }

    private static boolean hasBrain(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) return false;
        var block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return block != null && com.koper.koper_lib.block.KoperBrainRegistry.wants(block);
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int t = b; b = a % b; a = t; }
        return a;
    }

    private static KoperBlockData data(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return ContentRegistry.getBlockData(id);
    }

    private static void fire(KoperBlockData data, ServerPlayer player, ServerLevel level, BlockPos pos,
            String event, ScriptEvent scriptEvent) {
        KoperContext ctx = KoperContext.ofBlockUse(player, pos);
        if (JavaHookRegistry.fireHook(data.id + "/" + event, ctx) != InteractionResult.PASS) return;
        if (data.logic != null && !data.logic.isEmpty()) UniversalScriptEngine.call(data.logic, scriptEvent, player, level, pos);
        for (String script : data.scripts) UniversalScriptEngine.call(script, scriptEvent, player, level, pos);
        JsonElement actions = data.events != null ? data.events.get(event) : null;
        if (actions != null && !actions.isJsonNull()) KoperActions.run(actions, ctx, event, data.id);
    }
}
