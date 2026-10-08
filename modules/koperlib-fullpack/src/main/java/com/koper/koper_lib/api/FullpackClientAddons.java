package com.koper.koper_lib.api;

import com.koper.koper_lib.data.KoperEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;

/** Client-only Fullpack extension surface. Optional render modules install providers here. */
public final class FullpackClientAddons {
    private static EntityRenderer entityRenderer;

    private FullpackClientAddons() {}

    public static synchronized void entityRenderer(String owner, EntityRenderer provider) {
        if (entityRenderer != null)
            throw new IllegalStateException("Fullpack entity renderer provider already registered");
        entityRenderer = java.util.Objects.requireNonNull(provider, owner);
    }

    public static boolean registerEntity(EntityType<PathfinderMob> type,
                                         KoperEntityData data, Identifier texture) {
        EntityRenderer current;
        synchronized (FullpackClientAddons.class) { current = entityRenderer; }
        return current != null && current.register(type, data, texture);
    }

    @FunctionalInterface
    public interface EntityRenderer {
        boolean register(EntityType<PathfinderMob> type, KoperEntityData data, Identifier texture);
    }
}
