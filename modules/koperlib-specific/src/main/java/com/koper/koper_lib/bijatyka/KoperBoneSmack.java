package com.koper.koper_lib.bijatyka;

import com.koper.koper_lib.api.core.KoperBonePositions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

// grabs whatever sits near a bone right now. bone pos comes from the geo tracker, NOT physics (that crap is banned)
public final class KoperBoneSmack {

    private KoperBoneSmack() {}

    public static List<LivingEntity> smack(Level lvl, int sourceId, String bone, double reach, Predicate<LivingEntity> filter) {
        Vec3 p = KoperBonePositions.get(sourceId, bone);
        if (p == null) return List.of();   // bone not synced yet this tick, no hit
        AABB box = new AABB(p, p).inflate(reach);
        return lvl.getEntitiesOfClass(LivingEntity.class, box, filter);
    }
}
