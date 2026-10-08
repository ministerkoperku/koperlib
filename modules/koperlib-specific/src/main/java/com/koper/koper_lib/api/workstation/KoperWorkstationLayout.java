package com.koper.koper_lib.api.workstation;

import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

/** Positions are local block coordinates in the 0..1 range. */
public record KoperWorkstationLayout(List<SlotPosition> slots) {
    public KoperWorkstationLayout {
        slots = List.copyOf(slots);
        if (slots.isEmpty()) throw new IllegalArgumentException("A workstation layout needs at least one slot");
    }

    public static KoperWorkstationLayout of(SlotPosition... slots) {
        return new KoperWorkstationLayout(Arrays.asList(slots));
    }

    public SlotPosition slot(int index) { return slots.get(index); }
    public int size() { return slots.size(); }

    public record SlotPosition(double x, double y, double z, float yaw, float pitch, float scale) {
        public SlotPosition(double x, double y, double z, float yaw, float pitch) { this(x, y, z, yaw, pitch, .36f); }
        public SlotPosition(double x, double y, double z, float yaw) { this(x, y, z, yaw, 90, .36f); }
        public SlotPosition(double x, double y, double z) { this(x, y, z, 0, 90, .36f); }
        public Vec3 vector() { return new Vec3(x, y, z); }
    }
}
