package com.koper.koper_lib.physics;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

public record KontraFlightPolicy(boolean fastFlight, double minBodyY, double maxBodyY,
                                double maxSpeed, boolean vacuumMomentum) {
    public static final KontraFlightPolicy DEFAULT = new KontraFlightPolicy(false, -2048, 6000, 10000, false);
    public KontraFlightPolicy {
        if (!Double.isFinite(minBodyY) || !Double.isFinite(maxBodyY) || minBodyY >= maxBodyY
                || !Double.isFinite(maxSpeed) || maxSpeed <= 0 || maxSpeed > 10000)
            throw new IllegalArgumentException("flight needs ordered finite bounds and max_speed in (0, 10000]");
    }
    public boolean allows(Vec3 position, Vec3 velocity) {
        return KontraFrame.finite(position) && KontraFrame.finite(velocity)
            && position.y >= minBodyY && position.y <= maxBodyY
            && velocity.lengthSqr() <= maxSpeed * maxSpeed;
    }
    public static KontraFlightPolicy parse(JsonObject json) {
        if (json == null || !json.has("enabled") || !json.get("enabled").getAsBoolean()) return DEFAULT;
        if (!json.has("max_speed")) throw new IllegalArgumentException("fast flight requires max_speed");
        return new KontraFlightPolicy(true,
            json.has("min_body_y") ? json.get("min_body_y").getAsDouble() : DEFAULT.minBodyY,
            json.has("max_body_y") ? json.get("max_body_y").getAsDouble() : DEFAULT.maxBodyY,
            json.get("max_speed").getAsDouble(),
            json.has("vacuum_momentum") && json.get("vacuum_momentum").getAsBoolean());
    }
}
