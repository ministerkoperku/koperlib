package com.koper.koper_lib.physics;

import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KontraFlightPolicyTest {
    @Test void ordinaryDimensionsKeepAltitudeBounds() {
        assertTrue(KontraFlightPolicy.DEFAULT.allows(new Vec3(0, 6000, 0), Vec3.ZERO));
        assertFalse(KontraFlightPolicy.DEFAULT.allows(new Vec3(0, 6001, 0), Vec3.ZERO));
        assertFalse(KontraFlightPolicy.DEFAULT.allows(new Vec3(0, -2049, 0), Vec3.ZERO));
    }
    @Test void fastFlightRequiresFiniteConfiguredLimits() {
        var policy = KontraFlightPolicy.parse(JsonParser.parseString(
            "{\"enabled\":true,\"min_body_y\":-10000,\"max_body_y\":10000,\"max_speed\":10000,\"vacuum_momentum\":true}").getAsJsonObject());
        assertTrue(policy.allows(new Vec3(0, 9000, 0), new Vec3(10000, 0, 0)));
        assertFalse(policy.allows(Vec3.ZERO, new Vec3(10001, 0, 0)));
        assertFalse(policy.allows(new Vec3(Double.NaN, 0, 0), Vec3.ZERO));
        assertFalse(policy.allows(Vec3.ZERO, new Vec3(Double.POSITIVE_INFINITY, 0, 0)));
    }
    @Test void malformedLimitsCannotBecomeAnActivePolicy() {
        assertThrows(IllegalArgumentException.class, () -> KontraFlightPolicy.parse(
            JsonParser.parseString("{\"enabled\":true}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> new KontraFlightPolicy(true, 10, -10, 100, true));
        assertThrows(IllegalArgumentException.class, () -> new KontraFlightPolicy(true, -10, 10, Double.NaN, true));
    }
}
