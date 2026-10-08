package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraSolidBook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// EVERY vanilla block-collision query funnels through BlockCollisions.computeNext reading the
// chunk directly (which is why entities used to fall straight through kontras). swap in the
// aligned kontra's block state and the whole engine — collide, noCollision, spawn checks,
// findSupportingBlock, mods — treats it as real solid blocks with real shapes. stairs included.
@Mixin(BlockCollisions.class)
public abstract class KontraSolidBlocksMixin {

    @Shadow @Final private CollisionGetter collisionGetter;

    // NO require=0 here — if this ever stops matching, parked kontras lose ALL collision and
    // everything falls through. better a loud boot error than that silently coming back.
    @Redirect(method = "computeNext()Ljava/lang/Object;",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState koper$solidKontraBlocks(BlockGetter chunk, BlockPos pos) {
        BlockState real = chunk.getBlockState(pos);
        if (!real.isAir()) return real;
        BlockState kontra = KontraSolidBook.at(this.collisionGetter, pos);
        return kontra != null ? kontra : real;
    }

    // vanilla then asks that state for its shape AGAINST THE WORLD. a micro block keeps its geometry
    // in local data, so out here it has none -> empty shape -> you walk straight through a parked
    // kontra. moving ones use our own sweep and were fine, which is why a lift at rest was a ghost
    // and the same lift a quarter block up was solid
    @Redirect(method = "computeNext()Ljava/lang/Object;",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape koper$kontraShape(CollisionContext ctx, BlockState state,
                                         CollisionGetter getter, BlockPos pos) {
        // only if THIS state is the one we just projected, else we'd stomp a real world block
        if (KontraSolidBook.at(getter, pos) != state) return ctx.getCollisionShape(state, getter, pos);
        var boxes = KontraSolidBook.shapeAt(getter, pos);
        if (boxes == null || boxes.isEmpty()) return ctx.getCollisionShape(state, getter, pos);
        VoxelShape out = Shapes.empty();
        for (AABB b : boxes)
            out = Shapes.or(out, Shapes.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        return out;
    }
}
