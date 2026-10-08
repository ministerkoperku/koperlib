package com.koper.koper_lib.block;

import com.google.gson.JsonElement;
import com.koper.koper_lib.api.KoperActions;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.data.KoperBlockData;
import com.koper.koper_lib.scripting.JavaHookRegistry;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;

// same java-then-lua-then-json ladder the proximity scan uses, minus the player.
// split out so the BE ticker doesn't drag KoperBlockRuntimeEvents' player-shaped api along
public final class KoperBrainRuntime {

    private KoperBrainRuntime() {}

    public static void fireTick(KoperBlockData data, ServerLevel level, BlockPos pos) {
        KoperContext ctx = new KoperContext(null, null, null, level,
            net.minecraft.world.item.ItemStack.EMPTY, pos);

        if (JavaHookRegistry.fireHook(data.id + "/on_tick", ctx) != InteractionResult.PASS) return;
        if (data.logic != null && !data.logic.isEmpty())
            UniversalScriptEngine.call(data.logic, ScriptEvent.ON_TICK, null, level, pos);
        for (String script : data.scripts)
            UniversalScriptEngine.call(script, ScriptEvent.ON_TICK, null, level, pos);

        JsonElement actions = data.events != null ? data.events.get("on_tick") : null;
        if (actions != null && !actions.isJsonNull()) KoperActions.run(actions, ctx, "on_tick", data.id);
    }
}
