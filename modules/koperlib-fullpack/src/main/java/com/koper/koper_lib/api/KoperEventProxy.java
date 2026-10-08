package com.koper.koper_lib.api;

// bumped on every hot-reload — registered Fabric event handlers capture the generation at
// registration time and bail if it's stale, so we don't pile up handlers across reloads
public final class KoperEventProxy {
    private KoperEventProxy() {}

    private static volatile long generation = 0;

    public static void bumpGeneration() { generation++; }
    public static long currentGeneration() { return generation; }
}
