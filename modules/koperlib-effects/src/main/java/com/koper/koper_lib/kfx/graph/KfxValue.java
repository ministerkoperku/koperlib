package com.koper.koper_lib.kfx.graph;

sealed interface KfxValue permits KfxValue.Constant, KfxValue.Input, KfxValue.Random {
    record Constant(KfxResolvedValue value) implements KfxValue {}

    record Input(String name) implements KfxValue {}

    record Random(KfxDistribution distribution) implements KfxValue {}
}
