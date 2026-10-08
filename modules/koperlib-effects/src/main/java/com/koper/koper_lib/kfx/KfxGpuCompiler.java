package com.koper.koper_lib.kfx;

public final class KfxGpuCompiler {
    public static final int OP_RING_PARTICLES = 1;
    public static final int OP_PENTAGRAM_PARTICLES = 2;
    public static final int OP_RING_BAND = 3;
    public static final int OP_ORB = 4;
    public static final int OP_BEAM = 5;
    public static final int OP_BURST_RING = 6;
    public static final int OP_STREAM = 7;
    public static final int OP_SPIRAL = 8;
    public static final int FLOATS_PER_OP = 24;

    private KfxGpuCompiler() {}

    public static float[] compile(KfxInstance fx) {
        if (fx.programJson == null || fx.programJson.isBlank()) return new float[0];
        KfxProgram program = KfxProgram.parse(fx.programJson);
        if (program.ops.isEmpty()) return new float[0];
        try {
            float[] out = new float[program.ops.size() * FLOATS_PER_OP];
            for (int op = 0; op < program.ops.size(); op++) {
                KfxProgram.Op kfxOp = program.ops.get(op);
                int base = op * FLOATS_PER_OP;
                out[base] = KfxOps.opcodeForName(kfxOp.op);
                out[base + 1] = kfxOp.from;
                out[base + 2] = kfxOp.to;
                out[base + 3] = kfxOp.count;
                out[base + 4] = kfxOp.radius;
                out[base + 5] = kfxOp.size;
                out[base + 6] = kfxOp.thickness;
                out[base + 7] = kfxOp.alpha;
                out[base + 8] = KfxStyles.codeFor(kfxOp.style);
                out[base + 9] = easeCode(kfxOp.ease);
                out[base + 10] = buildCode(kfxOp.build);
                out[base + 11] = kfxOp.spin;
                out[base + 12] = kfxOp.wobble;
                out[base + 13] = kfxOp.depth;
                int color = kfxOp.color != 0 ? kfxOp.color : fx.color;
                out[base + 14] = ((color >> 16) & 255) / 255.0f;
                out[base + 15] = ((color >> 8) & 255) / 255.0f;
                out[base + 16] = (color & 255) / 255.0f;
                out[base + 17] = ((color >>> 24) & 255) / 255.0f;
                out[base + 18] = kfxOp.seed;
                out[base + 19] = kfxOp.radiusTo;
                out[base + 20] = kfxOp.speed;
                out[base + 21] = kfxOp.x;
                out[base + 22] = kfxOp.y;
                out[base + 23] = kfxOp.z;
            }
            return out;
        } catch (Exception ignored) {
            return new float[0];
        }
    }

    private static int easeCode(String ease) {
        if (ease == null) return 1;
        return switch (ease.toLowerCase()) {
            case "linear" -> 0;
            case "in", "quad_in" -> 2;
            case "out", "quad_out" -> 3;
            default -> 1;
        };
    }

    private static int buildCode(String build) {
        return "center_out".equalsIgnoreCase(build) ? 1 : 0;
    }
}
