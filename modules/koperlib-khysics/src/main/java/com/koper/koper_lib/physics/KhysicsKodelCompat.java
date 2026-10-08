package com.koper.koper_lib.physics;

import com.koper.koper_lib.api.core.KoperShapeBox;
import com.koper.koper_lib.kodel.KodelPhysicsShapes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Optional Kodel shape provider owned and loaded by Khysics. */
final class KhysicsKodelCompat {
    private KhysicsKodelCompat() {}

    static void install() {
        KhysicsShapeAdapters.install(new KhysicsShapeAdapters.Provider() {
            @Override public List<KoperShapeBox> shapes(BlockState state) {
                return KodelPhysicsShapes.forState(state);
            }

            @Override public Vec3 colliderOffset(BlockState state) {
                return KodelPhysicsShapes.colliderOffset(state);
            }
        });
    }
}
