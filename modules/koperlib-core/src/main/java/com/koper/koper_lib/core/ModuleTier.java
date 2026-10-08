package com.koper.koper_lib.core;

// module maturity tier — drives default-on, boot warnings, and config gating
// working = stable, on. experimental = on but flagged. alpha = off by default, dev-only, config-gated, lazy
public enum ModuleTier {
    WORKING("working", true, null),
    EXPERIMENTAL("experimental", true, "experimental — may change or have rough edges"),
    ALPHA("alpha", false, "ALPHA — off by default, dev-only, unstable. enable in config");

    public final String id;
    public final boolean defaultOn;
    public final String warning; // null = no warning

    ModuleTier(String id, boolean defaultOn, String warning) {
        this.id = id;
        this.defaultOn = defaultOn;
        this.warning = warning;
    }
}
