package com.koper.koper_lib.api.core;

/** Module-neutral oriented box exchanged by render and physics compatibility adapters. */
public record KoperShapeBox(float cx, float cy, float cz, float hx, float hy, float hz,
                            float qx, float qy, float qz, float qw) {}
