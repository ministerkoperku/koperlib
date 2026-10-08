package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.data.KoperBlockData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class KoperBlockShapes {
    private KoperBlockShapes() {}

    public static VoxelShape visual(KoperBlockData data, Block block) {
        return visual(data, block, null);
    }

    // for blocks an addon registered itself (own java class, not the factory): pulls the pack json
    // and the model bone-hitbox for you. override getShape/getCollisionShape and call these two
    public static VoxelShape forState(net.minecraft.world.level.block.state.BlockState state) {
        return visual(dataOf(state), state.getBlock(), state);
    }

    public static VoxelShape collisionForState(net.minecraft.world.level.block.state.BlockState state) {
        return collision(dataOf(state), state.getBlock(), state);
    }

    private static KoperBlockData dataOf(net.minecraft.world.level.block.state.BlockState state) {
        return com.koper.koper_lib.loader.ContentRegistry.getBlockData(
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    // state-aware: a kender hitbox follows rotate_by (wall bearing plate sits ON the wall)
    public static VoxelShape visual(KoperBlockData data, Block block, net.minecraft.world.level.block.state.BlockState state) {
        if (data != null && data.hitbox != null) return parse(data.hitbox, data.shape);
        if (data != null && data.shape != null && !"cube".equalsIgnoreCase(data.shape)) return preset(data.shape);
        VoxelShape geo = state != null
            ? com.koper.koper_lib.api.core.KoperBlockShapes.shape(state)
            : null;
        return geo != null ? geo : Shapes.block();
    }

    public static VoxelShape collision(KoperBlockData data, Block block) {
        return collision(data, block, null);
    }

    public static VoxelShape collision(KoperBlockData data, Block block, net.minecraft.world.level.block.state.BlockState state) {
        if (data != null && Boolean.FALSE.equals(data.collidable)) return Shapes.empty();
        if (data != null && data.collisionBox != null) return parse(data.collisionBox, data.shape);
        return visual(data, block, state);
    }

    private static VoxelShape parse(JsonElement el, String fallbackShape) {
        if (el == null || el.isJsonNull()) return preset(fallbackShape);
        if (el.isJsonPrimitive()) return preset(el.getAsString());
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (isBox(arr)) return box(arr);
            VoxelShape shape = Shapes.empty();
            for (JsonElement child : arr) {
                VoxelShape part = parse(child, fallbackShape);
                shape = Shapes.joinUnoptimized(shape, part, BooleanOp.OR);
            }
            return shape.optimize();
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("type")) return preset(o.get("type").getAsString());
            if (o.has("box")) return parse(o.get("box"), fallbackShape);
            if (o.has("boxes")) return parse(o.get("boxes"), fallbackShape);
            if (o.has("from") && o.has("to")) return box(o.getAsJsonArray("from"), o.getAsJsonArray("to"));
        }
        return preset(fallbackShape);
    }

    private static VoxelShape preset(String raw) {
        String s = raw == null ? "cube" : raw.toLowerCase();
        return switch (s) {
            case "none", "empty", "air" -> Shapes.empty();
            case "slab", "lower_slab", "half" -> px(0, 0, 0, 16, 8, 16);
            case "upper_slab", "top_slab" -> px(0, 8, 0, 16, 16, 16);
            case "carpet", "plate", "pressure_plate" -> px(0, 0, 0, 16, 1, 16);
            case "thin_plate" -> px(1, 0, 1, 15, 1, 15);
            case "column" -> px(4, 0, 4, 12, 16, 12);
            case "post" -> px(6, 0, 6, 10, 16, 10);
            case "cross", "plant" -> px(2, 0, 2, 14, 16, 14);
            default -> Shapes.block();
        };
    }

    private static boolean isBox(JsonArray a) {
        return a.size() >= 6 && a.get(0).isJsonPrimitive() && a.get(1).isJsonPrimitive()
                && a.get(2).isJsonPrimitive() && a.get(3).isJsonPrimitive()
                && a.get(4).isJsonPrimitive() && a.get(5).isJsonPrimitive();
    }

    private static VoxelShape box(JsonArray a) {
        return px(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
                a.get(3).getAsDouble(), a.get(4).getAsDouble(), a.get(5).getAsDouble());
    }

    private static VoxelShape box(JsonArray from, JsonArray to) {
        if (from == null || to == null || from.size() < 3 || to.size() < 3) return Shapes.block();
        return px(from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble(),
                to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble());
    }

    private static VoxelShape px(double x0, double y0, double z0, double x1, double y1, double z1) {
        return Shapes.box(clamp(x0) / 16.0, clamp(y0) / 16.0, clamp(z0) / 16.0,
                clamp(x1) / 16.0, clamp(y1) / 16.0, clamp(z1) / 16.0);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(16.0, v));
    }
}
