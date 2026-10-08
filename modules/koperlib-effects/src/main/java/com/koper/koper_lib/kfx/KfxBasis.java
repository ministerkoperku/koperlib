package com.koper.koper_lib.kfx;

import org.joml.Vector3f;

// orientation frame for an effect (start->end). pulled out so ops/addons can reach it
public record KfxBasis(Vector3f side, Vector3f up, Vector3f forward) {
    public float lx(float x, float y, float z) { return side.x * x + forward.x * y + up.x * z; }
    public float ly(float x, float y, float z) { return side.y * x + forward.y * y + up.y * z; }
    public float lz(float x, float y, float z) { return side.z * x + forward.z * y + up.z * z; }
}
