package com.koper.koper_lib.scripting;

public enum ScriptEvent {
    ON_USE,
    ON_TICK,
    ON_HIT,
    ON_DAMAGE,
    ON_DEATH,
    ON_PLACE,
    ON_BREAK,
    ON_STEP,
    ON_SPAWN,
    ON_INTERACT,
    ON_TARGET,
    ON_EQUIP,
    ON_UNEQUIP,
    ON_CONSUME,
    ON_CRAFT,
    ON_AI // fired by script_goal on its own cadence, separate from the mob's on_tick
}
