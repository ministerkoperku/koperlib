package com.koper.koper_lib.elpe;

// knobs for one elpe world. order matches koper_elpe_configure, dont shuffle it
public record ElpeTuning(
    float gravityX, float gravityY, float gravityZ,
    float damping,
    float friction,
    float sleepSpeed,
    int sleepTicks,
    float wakeSpeed,
    int iterations,
    float maxStep,
    boolean terrain,
    float floorY,
    float killY,
    int threads,
    int jointIterations
) {
    // -28 like khysics, blocks per second squared
    public static final ElpeTuning KOPER_DEFAULT = new ElpeTuning(
        0f, -28f, 0f, 0.999f, 0.4f, 0.08f, 20, 1.5f, 2, 0.45f, true, Float.NaN, -2048f, 0, 4);

    public ElpeTuning withKillY(float y) {
        return new ElpeTuning(gravityX, gravityY, gravityZ, damping, friction, sleepSpeed, sleepTicks, wakeSpeed,
            iterations, maxStep, terrain, floorY, y, threads, jointIterations);
    }

    public ElpeTuning withThreads(int t) {
        return new ElpeTuning(gravityX, gravityY, gravityZ, damping, friction, sleepSpeed, sleepTicks, wakeSpeed,
            iterations, maxStep, terrain, floorY, killY, t, jointIterations);
    }

    float[] packed() {
        return new float[] {
            gravityX, gravityY, gravityZ, damping, friction, sleepSpeed, sleepTicks, wakeSpeed,
            iterations, maxStep, terrain ? 1f : 0f, floorY, killY, threads, jointIterations
        };
    }
}
