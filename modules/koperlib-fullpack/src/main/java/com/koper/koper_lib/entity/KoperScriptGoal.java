package com.koper.koper_lib.entity;

import com.koper.koper_lib.data.KoperEntityData;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

// the escape hatch out of the nine hardcoded goals. a real Goal with a real lifecycle, so the mob's
// pathfinder and the rest of its brain keep working — unlike ticking lua from a proximity scan.
// json: "entity_ai": ["wander", "script_goal:10"]   (10 = ticks between on_ai calls)
public class KoperScriptGoal extends Goal {

    private final Mob mob;
    private final KoperEntityData data;
    private final int everyTicks;
    private int cooldown;

    public KoperScriptGoal(Mob mob, KoperEntityData data, int everyTicks) {
        this.mob = mob;
        this.data = data;
        this.everyTicks = Math.max(1, everyTicks);
        // no MOVE/LOOK flags — this goal decides things, it doesn't hold the mob hostage
        setFlags(java.util.EnumSet.noneOf(Goal.Flag.class));
    }

    @Override public boolean canUse() { return hasHandler(); }
    @Override public boolean canContinueToUse() { return hasHandler(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (--cooldown > 0) return;
        cooldown = everyTicks;
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        if (data.logic != null && !data.logic.isEmpty())
            UniversalScriptEngine.call(data.logic, ScriptEvent.ON_AI, mob, level, mob.blockPosition());
        for (String script : data.scripts)
            UniversalScriptEngine.call(script, ScriptEvent.ON_AI, mob, level, mob.blockPosition());
    }

    private boolean hasHandler() {
        return (data.logic != null && !data.logic.isEmpty()) || !data.scripts.isEmpty();
    }
}
