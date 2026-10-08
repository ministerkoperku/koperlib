package com.koper.koper_lib.compat.create;

import net.minecraft.world.phys.Vec3;

public interface KoperCreateContraptionAccess {
    long koperlib$parentBody();
    void koperlib$parentBody(long body);
    Vec3 koperlib$logicalAnchor();
    void koperlib$logicalAnchor(Vec3 anchor);
}
