package com.koper.koper_lib.kfx.render;

public enum KfxQuality {
    LOW(0.40),
    MEDIUM(0.70),
    HIGH(1.0);

    private final double particleScale;

    KfxQuality(double particleScale) {
        this.particleScale = particleScale;
    }

    public double particleScale() {
        return particleScale;
    }

    public int particleBudget(int declaredBudget) {
        if (declaredBudget < 1) throw new IllegalArgumentException("KFX particle budget must be positive");
        return Math.max(1, (int)Math.floor(declaredBudget * particleScale));
    }

    public static KfxQuality configured() {
        String value = System.getProperty("koperlib.kfx.quality", "high");
        try { return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return HIGH; }
    }
}
