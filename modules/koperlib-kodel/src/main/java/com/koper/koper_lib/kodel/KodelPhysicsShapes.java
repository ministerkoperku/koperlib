package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperShapeBox;
import com.koper.koper_lib.api.core.KoperStateOffset;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// A model bone named *hitbox* is both the entity OBB and the Rapier block collider source.
public final class KodelPhysicsShapes {

    private static final Map<String, List<RawBox>> MODEL_BOXES = new ConcurrentHashMap<>();

    private record RawBox(Matrix4f pose, float hx, float hy, float hz) {}

    private KodelPhysicsShapes() {}

    public static void clear() {
        MODEL_BOXES.clear();
    }

    public static void invalidate(String model) {
        MODEL_BOXES.remove(model);
    }

    public static List<KoperShapeBox> forState(BlockState state) {
        List<KoperShapeBox> bones = boneBoxes(state);
        if (!bones.isEmpty()) return bones;

        var shape = KodelBlockBook.authoredShape(state);
        if (shape == null || shape.isEmpty()) return List.of();
        List<KoperShapeBox> out = new ArrayList<>();
        for (var aabb : shape.toAabbs()) {
            out.add(new KoperShapeBox(
                    (float)((aabb.minX + aabb.maxX) * 0.5 - 0.5),
                    (float)((aabb.minY + aabb.maxY) * 0.5 - 0.5),
                    (float)((aabb.minZ + aabb.maxZ) * 0.5 - 0.5),
                    (float)((aabb.maxX - aabb.minX) * 0.5),
                    (float)((aabb.maxY - aabb.minY) * 0.5),
                    (float)((aabb.maxZ - aabb.minZ) * 0.5),
                    0f, 0f, 0f, 1f));
        }
        return List.copyOf(out);
    }

    // only the *hitbox* bones of the bound model, no authored-json fallback. empty = model has none,
    // so blocks can tell "my model draws its own hitbox" from "somebody wrote a box in the json"
    public static List<KoperShapeBox> boneBoxes(BlockState state) {
        KodelBlockBook.Binding binding = state == null ? null : KodelBlockBook.binding(state.getBlock());
        if (binding == null) return List.of();
        boolean whole = KodelBlockBook.modelHitbox(state.getBlock());
        // "hitbox": ["top","bottom"] — hand picked bones. they may well be visible ones; naming decides
        // rendering, this decides shape, and the two are deliberately separate
        java.util.Set<String> picked = KodelBlockBook.hitboxBones(state.getBlock());
        String key = binding.model() + (whole ? "|whole" : "")
                + (picked.isEmpty() ? "" : "|" + new java.util.TreeSet<>(picked));
        List<RawBox> raw = MODEL_BOXES.computeIfAbsent(key,
                ignored -> readModelBoxes(binding.model(), whole, picked));
        return raw.isEmpty() ? List.of() : transform(raw, binding, state);
    }

    private static boolean isHitboxBone(String name, java.util.Set<String> picked) {
        String n = name.toLowerCase();
        if (picked.contains(n)) return true;
        return n.contains("hitbox") || n.startsWith("bhit");
    }

    // Used by procedural shapes such as wheels which stay cylinders instead of becoming box compounds.
    public static Vec3 colliderOffset(BlockState state) {
        if (state == null) return Vec3.ZERO;
        return state.getBlock() instanceof KoperStateOffset provider
                ? provider.koperStateOffset(state) : Vec3.ZERO;
    }

    private static List<KoperShapeBox> transform(List<RawBox> raw, KodelBlockBook.Binding binding, BlockState state) {
        Matrix4f base = new Matrix4f();
        Vec3 stateOffset = state.getBlock() instanceof KoperStateOffset provider
                ? provider.koperStateOffset(state) : Vec3.ZERO;
        if (stateOffset != Vec3.ZERO)
            base.translate((float)stateOffset.x, (float)stateOffset.y, (float)stateOffset.z);
        Quaternionf facing = KodelBlockBook.facingQuat(binding, state);
        if (facing != null)
            base.translate(0f, 0.5f, 0f).rotate(facing).translate(0f, -0.5f, 0f);
        float[] offset = binding.offset();
        base.translate(offset[0] / 16f, offset[1] / 16f, offset[2] / 16f);
        base.scale(binding.scale());
        float[] rotate = binding.rotate();
        if (rotate[2] != 0f) base.rotateZ((float)Math.toRadians(rotate[2]));
        if (rotate[1] != 0f) base.rotateY((float)Math.toRadians(rotate[1]));
        if (rotate[0] != 0f) base.rotateX((float)Math.toRadians(rotate[0]));

        List<KoperShapeBox> out = new ArrayList<>(raw.size());
        float scale = Math.abs(binding.scale());
        for (RawBox box : raw) {
            Matrix4f pose = new Matrix4f(base).mul(box.pose());
            Vector3f center = pose.transformPosition(new Vector3f());
            Quaternionf rotation = pose.getUnnormalizedRotation(new Quaternionf()).normalize();
            out.add(new KoperShapeBox(center.x, center.y - 0.5f, center.z,
                    box.hx() * scale, box.hy() * scale, box.hz() * scale,
                    rotation.x, rotation.y, rotation.z, rotation.w));
        }
        return List.copyOf(out);
    }

    // every cube of the hitbox bones, at the rest pose. bones inherit "hitbox" from a parent, like
    // the renderer's naming does, and a picked or whole-model binding widens the net
    private static List<RawBox> readModelBoxes(String modelName, boolean wholeModel,
                                               java.util.Set<String> picked) {
        KodelBook.Entry entry = KodelBook.get(modelName);
        if (entry == null) return List.of();
        KodelModel model = entry.model();
        float[] rest = entry.restPose();
        int n = model.bones.size();
        boolean[] hitbox = new boolean[n];
        List<RawBox> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            KodelModel.KodelBone bone = model.bones.get(i);
            boolean inherited = bone.parent >= 0 && bone.parent < i && hitbox[bone.parent];
            hitbox[i] = wholeModel || inherited || isHitboxBone(bone.name == null ? "" : bone.name, picked);
            if (!hitbox[i]) continue;
            Matrix4f world = new Matrix4f().set(rest, i * KodelSampler.MAT4_FLOATS);
            for (KodelModel.KodelCube cube : bone.cubes) addCube(cube, world, out);
        }
        return List.copyOf(out);
    }

    private static void addCube(KodelModel.KodelCube cube, Matrix4f boneWorld, List<RawBox> out) {
        float hx = Math.abs(cube.size[0]) / 32f + cube.inflate / 16f;
        float hy = Math.abs(cube.size[1]) / 32f + cube.inflate / 16f;
        float hz = Math.abs(cube.size[2]) / 32f + cube.inflate / 16f;
        if (hx <= 0f || hy <= 0f || hz <= 0f) return;
        // model pixels all the way, then the translation is scaled into blocks once at the end
        Matrix4f pose = new Matrix4f(boneWorld)
                .translate(cube.pivot[0], cube.pivot[1], cube.pivot[2])
                .rotate(new Quaternionf(cube.rotation[0], cube.rotation[1], cube.rotation[2], cube.rotation[3]).normalize())
                .translate(-cube.pivot[0], -cube.pivot[1], -cube.pivot[2])
                .translate(cube.origin[0] + cube.size[0] * 0.5f, cube.origin[1] + cube.size[1] * 0.5f,
                        cube.origin[2] + cube.size[2] * 0.5f);
        Vector3f centre = pose.transformPosition(new Vector3f()).div(16f);
        Quaternionf rotation = pose.getUnnormalizedRotation(new Quaternionf()).normalize();
        out.add(new RawBox(new Matrix4f().translation(centre).rotate(rotation), hx, hy, hz));
    }
}
