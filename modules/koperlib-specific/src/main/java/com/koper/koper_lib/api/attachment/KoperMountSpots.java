package com.koper.koper_lib.api.attachment;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

// where a part is ALLOWED to sit on a face. free-hand placement meant every wheel landed a pixel
// off from the last one, so a face now offers a fixed handful of spots: four corners + the middle.
// a block that wants its own layout (a mount ring, a rail, whatever) registers a provider instead.
//
// spots are block-local and CENTRED: (0,0,0) is the middle of the block, so the component along the
// face normal is dropped by callers — only the two tangential ones matter
public final class KoperMountSpots {

    public static final String CORNERS_AND_MIDDLE = "corners+middle";

    private static final Map<Block, BiFunction<BlockState, Direction, List<Vec3>>> BY_BLOCK =
        new ConcurrentHashMap<>();
    private static final Map<Identifier, BiFunction<BlockState, Direction, List<Vec3>>> BY_ID =
        new ConcurrentHashMap<>();

    private KoperMountSpots() {}

    public static void register(Block block, BiFunction<BlockState, Direction, List<Vec3>> spots) {
        BY_BLOCK.put(block, spots);
    }

    public static void register(Identifier blockId, BiFunction<BlockState, Direction, List<Vec3>> spots) {
        BY_ID.put(blockId, spots);
    }

    // every spot this state offers on that face. custom if the block declared one, else the default 5
    public static List<Vec3> of(BlockState state, Direction face) {
        if (state == null || face == null) return List.of();
        var provider = BY_BLOCK.get(state.getBlock());
        if (provider == null) provider = BY_ID.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (provider != null) {
            List<Vec3> custom = provider.apply(state, face);
            if (custom != null && !custom.isEmpty()) return custom;
        }
        return defaults(state, face);
    }

    // four corners of the face + its middle, taken from the block's own shape so a half-size part
    // gets ITS corners and not the cell's
    public static List<Vec3> defaults(BlockState state, Direction face) {
        AABB bounds = bounds(state);
        double minX = bounds.minX - 0.5, maxX = bounds.maxX - 0.5;
        double minY = bounds.minY - 0.5, maxY = bounds.maxY - 0.5;
        double minZ = bounds.minZ - 0.5, maxZ = bounds.maxZ - 0.5;
        double midX = (minX + maxX) * 0.5, midY = (minY + maxY) * 0.5, midZ = (minZ + maxZ) * 0.5;
        List<Vec3> out = new ArrayList<>(5);
        // on a small part the four corners sit within a couple of pixels of each other, so aiming a
        // hair off centre snapped a suspension onto a corner of the microblock. something that size
        // has one sensible mount and it is the middle of it
        double spanU = switch (face.getAxis()) {
            case Y, Z -> maxX - minX;
            case X -> maxZ - minZ;
        };
        double spanV = switch (face.getAxis()) {
            case Y -> maxZ - minZ;
            case X, Z -> maxY - minY;
        };
        if (spanU <= 0.5 && spanV <= 0.5) {
            out.add(new Vec3(midX, midY, midZ));
            return out;
        }
        switch (face.getAxis()) {
            case Y -> {
                out.add(new Vec3(minX, midY, minZ));
                out.add(new Vec3(maxX, midY, minZ));
                out.add(new Vec3(minX, midY, maxZ));
                out.add(new Vec3(maxX, midY, maxZ));
                out.add(new Vec3(midX, midY, midZ));
            }
            case X -> {
                out.add(new Vec3(midX, minY, minZ));
                out.add(new Vec3(midX, minY, maxZ));
                out.add(new Vec3(midX, maxY, minZ));
                out.add(new Vec3(midX, maxY, maxZ));
                out.add(new Vec3(midX, midY, midZ));
            }
            case Z -> {
                out.add(new Vec3(minX, minY, midZ));
                out.add(new Vec3(maxX, minY, midZ));
                out.add(new Vec3(minX, maxY, midZ));
                out.add(new Vec3(maxX, maxY, midZ));
                out.add(new Vec3(midX, midY, midZ));
            }
        }
        return out;
    }

    // the spot you actually clicked. tangent is centred block-local; the normal component is ignored
    public static Vec3 snap(BlockState state, Direction face, Vec3 tangent) {
        List<Vec3> spots = of(state, face);
        if (spots.isEmpty()) return flatten(face, tangent);
        Vec3 aim = flatten(face, tangent);
        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Vec3 spot : spots) {
            Vec3 flat = flatten(face, spot);
            double distance = flat.distanceToSqr(aim);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = flat;
            }
        }
        return best;
    }

    public static Vec3 flatten(Direction face, Vec3 point) {
        if (point == null) return Vec3.ZERO;
        return switch (face.getAxis()) {
            case X -> new Vec3(0, point.y, point.z);
            case Y -> new Vec3(point.x, 0, point.z);
            case Z -> new Vec3(point.x, point.y, 0);
        };
    }

    private static AABB bounds(BlockState state) {
        VoxelShape shape = com.koper.koper_lib.api.core.KoperBlockShapes.shape(state);
        if (shape == null || shape.isEmpty()) shape = Shapes.block();
        return shape.bounds();
    }
}
