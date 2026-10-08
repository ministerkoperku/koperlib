package com.koper.koper_lib.api.attachment;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public record KoperAttachmentPoint(
        String id,
        String kind,
        Vec3 position,
        Direction normal,
        int color
) {}
