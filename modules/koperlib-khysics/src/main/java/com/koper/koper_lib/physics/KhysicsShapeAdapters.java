package com.koper.koper_lib.physics;

import com.koper.koper_lib.physics.shape.KhysShapeCache;
import com.koper.koper_lib.api.core.KoperShapeBox;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Optional model-aware collider bridge. Khysics works with vanilla shapes when KGecko is absent. */
public final class KhysicsShapeAdapters {
    public interface Provider {
        List<KoperShapeBox> shapes(BlockState state);
        default Vec3 colliderOffset(BlockState state) { return Vec3.ZERO; }
    }

    private static volatile Provider provider;

    private KhysicsShapeAdapters() {}

    public static void install(Provider value) { provider = value; }

    public static List<KoperShapeBox> forState(BlockState state) {
        Provider current = provider;
        if (current != null) {
            List<KoperShapeBox> boxes = current.shapes(state);
            if (boxes != null && !boxes.isEmpty()) return boxes;
        }
        List<KoperShapeBox> fallback = new ArrayList<>();
        for (var aabb : KhysShapeCache.get(state)) {
            fallback.add(new KoperShapeBox(
                (float)((aabb.minX + aabb.maxX) * 0.5 - 0.5),
                (float)((aabb.minY + aabb.maxY) * 0.5 - 0.5),
                (float)((aabb.minZ + aabb.maxZ) * 0.5 - 0.5),
                (float)((aabb.maxX - aabb.minX) * 0.5),
                (float)((aabb.maxY - aabb.minY) * 0.5),
                (float)((aabb.maxZ - aabb.minZ) * 0.5), 0, 0, 0, 1));
        }
        return List.copyOf(fallback);
    }

    public static Vec3 colliderOffset(BlockState state) {
        Provider current = provider;
        return current == null ? Vec3.ZERO : current.colliderOffset(state);
    }
}
