package com.koper.koper_lib.kodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// every block whose pack json has a "kender" object: which .kodel it draws, how it animates and turns,
// what triggers it, and what shape it has. the model itself lives in KodelBook; this is the binding
public final class KodelBlockBook {

    private KodelBlockBook() {}

    // generic animation primitive configured by JSON (pure math, no gameplay)
    // kind: 0 spin (continuous), 1 hold (fixed angle), 2 sway (sine rot), 3 bob (sine translate)
    // amp: hold->angle deg, sway->amplitude deg, bob->distance px. speed: spin->deg/s, sway/bob->Hz. phase deg.
    // when: trigger name gating the op — "" always, "powered" redstone, anything else = a true boolean
    // blockstate property of that name ("lit", "open"...) or a KodelTriggerBox flip from code/Lua
    // 4 shift / 5 stretch are driven by a NUMBER on the blockstate instead of a clock: "by" names an
    // int/bool property and the bone moves (px per unit) or grows (scale per unit) with it. that is how
    // a lift mast stretches to its platform — texture stretches with it, it is a real bone scale
    public static final int SPIN = 0, HOLD = 1, SWAY = 2, BOB = 3, SHIFT = 4, STRETCH = 5;
    public record AnimOp(String bone, int kind, int axis, float amp, float speed, float phase,
                         String when, String by) {}

    // render: kind 0 cutout, 1 translucent, 2 solid. offset/rotate = base model correction (px / degrees)
    // animation = default .animation.json clip to loop-play. clips = trigger -> clip override (e.g. {"powered":"open", "lit":"running"})
    // rotateBy = blockstate property that spins the model to its facing ("facing" covers horizontal_facing too),
    //   "" = off. DEFAULTS ON — a block with a facing state just rotates, "rotate_by": false kills it
    // rotateBase = which facing the model was AUTHORED for ("north" default; a floor-mounted bearing is "up")
    // triggers = every trigger name this binding can react to, in JSON order — the renderer only evaluates these
    public record Binding(Identifier blockId, String model, Identifier texture, String tier, float scale,
                          List<AnimOp> anim, float[] offset, float[] rotate, int renderKind, boolean onKontra,
                          String animation, Map<String, String> clips, int tint,
                          String rotateBy, String rotateBase, List<String> triggers,
                          java.util.Set<String> bones, Map<String, String> boneWhen) {}

    private static final Map<Identifier, Binding> BY_ID = new ConcurrentHashMap<>();
    private static final Map<Block, Binding> BY_BLOCK = new ConcurrentHashMap<>();

    // data-driven per-block hitbox (model-like). none = full cube
    private static final Map<Block, VoxelShape> SHAPES = new ConcurrentHashMap<>();
    // raw authored box (unit space) kept next to the shape so rotate_by can spin it per state
    private static final Map<Block, double[]> SHAPE_BOXES = new ConcurrentHashMap<>();
    private static final Map<net.minecraft.world.level.block.state.BlockState, VoxelShape> SHAPE_BY_STATE = new ConcurrentHashMap<>();
    // bone-driven hitbox baked per state + the blocks whose model turned out to have no hitbox bone
    private static final Map<net.minecraft.world.level.block.state.BlockState, VoxelShape> BONE_SHAPE = new ConcurrentHashMap<>();
    private static final Set<Block> NO_BONES = ConcurrentHashMap.newKeySet();
    // "hitbox": "model" — take the shape off EVERY bone. for models that are one solid thing and have no
    // spare hitbox bone: renaming their only bone would work but *hitbox* bones never draw, so the block
    // would go invisible. this keeps the model visible and still gives it its real shape
    private static final Set<Block> MODEL_HITBOX = ConcurrentHashMap.newKeySet();

    public static boolean modelHitbox(Block block) { return MODEL_HITBOX.contains(block); }

    // "hitbox": ["top", "bhit_bottom"] — pick the bones by hand. a bone listed here feeds the shape
    // even if it is a perfectly visible one; only the *hitbox*/bhit_ NAMING hides a bone from the
    // renderer, so you can have "this visible bone is also my hitbox" and "this invisible bone is
    // collision only" in the same model
    private static final Map<Block, Set<String>> HITBOX_BONES = new ConcurrentHashMap<>();

    public static Set<String> hitboxBones(Block block) {
        return HITBOX_BONES.getOrDefault(block, Set.of());
    }

    public static boolean isEmpty() { return BY_BLOCK.isEmpty(); }
    public static Binding binding(Block block) { return BY_BLOCK.get(block); }
    public static Binding binding(Identifier id) { return BY_ID.get(id); }
    public static Collection<Binding> all() { return BY_ID.values(); }



    public static VoxelShape authoredShape(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) return null;
        if (state.getBlock() instanceof com.koper.koper_lib.api.core.KoperStateShape provider)
            return provider.koperStateShape(state);
        return SHAPES.containsKey(state.getBlock()) ? shape(state) : null;
    }

    public static void bindFromJson(Identifier blockId, JsonObject k) {
        String model = str(k, "model", blockId.getPath());
        if (model.isBlank()) model = blockId.getPath();
        String texRaw = str(k, "texture", model);
        if (texRaw.isBlank()) texRaw = model;
        String tier = str(k, "tier", "koperblock");
        float scale = k.has("scale") ? k.get("scale").getAsFloat() : 1f;

        List<AnimOp> anim = parseAnim(k.has("anim") && k.get("anim").isJsonObject() ? k.getAsJsonObject("anim") : null);
        float[] offset = vec3(k.get("offset"));
        float[] rotate = vec3(k.get("rotate"));
        int renderKind = switch (str(k, "render_type", "cutout").toLowerCase()) {
            case "translucent" -> 1; case "solid" -> 2; default -> 0;
        };
        boolean onKontra = !k.has("on_kontraktion") || k.get("on_kontraktion").getAsBoolean(); // default true
        String animation = k.has("animation") && !k.get("animation").isJsonNull() ? k.get("animation").getAsString() : null;
        if (animation != null && animation.isBlank()) animation = null;

        // "clips": { "powered": "open", "<trigger>": "<clip>" } — switch the played clip by trigger.
        // LinkedHashMap: JSON order decides which trigger wins when several are active at once
        Map<String, String> clips = new java.util.LinkedHashMap<>();
        if (k.has("clips") && k.get("clips").isJsonObject()) {
            for (var e : k.getAsJsonObject("clips").entrySet()) {
                if (e.getValue().isJsonPrimitive()) clips.put(e.getKey().toLowerCase(), e.getValue().getAsString());
            }
        }

        // consolidated schema: "animations": { "path":..., "default":"idle", "powered":"running", "<trigger>":"<clip>" }
        // default/idle -> the looping clip; every other key -> a trigger->clip override. mirrors the entity schema
        if (k.has("animations") && k.get("animations").isJsonObject()) {
            for (var e : k.getAsJsonObject("animations").entrySet()) {
                String key = e.getKey().toLowerCase();
                if (key.equals("path") || !e.getValue().isJsonPrimitive()) continue;
                String clip = e.getValue().getAsString();
                if (key.equals("default") || key.equals("idle")) { if (animation == null) animation = clip; }
                else clips.putIfAbsent(key, clip);
            }
        }

        Identifier tex = resolveTex(blockId.getNamespace(), texRaw);
        int tint = KoperTint.fromJson(k); // procedural color tint from the kender block JSON

        // "rotate_by" — model (and boxed hitbox) spins to the blockstate facing at render time.
        // ON BY DEFAULT: a block with a facing state just rotates; "rotate_by": false turns it off.
        // MC registers horizontal_facing under the name "facing" too, so "facing" covers both.
        // "rotate_by_base" = the facing the model was authored for (floor bearing → "up")
        String rotateBy = "facing";
        if (k.has("rotate_by") && !k.get("rotate_by").isJsonNull()) {
            var rb = k.get("rotate_by");
            rotateBy = rb.isJsonPrimitive() && rb.getAsJsonPrimitive().isBoolean()
                ? (rb.getAsBoolean() ? "facing" : "")
                : rb.getAsString().toLowerCase();
            if (rotateBy.equals("horizontal_facing")) rotateBy = "facing";
        }
        String rotateBase = str(k, "rotate_by_base", "north").toLowerCase();

        // every trigger this binding can react to, JSON order — the renderer evaluates ONLY these
        List<String> triggers = new ArrayList<>(clips.keySet());
        for (AnimOp op : anim)
            if (!op.when().isEmpty() && !triggers.contains(op.when())) triggers.add(op.when());

        // "bones": ["top"] — draw only these. lets two blocks share one model file and each show its
        // own piece of it, which is how a lift foot and its platform come out of the same lift.geo.json
        java.util.Set<String> visibleBones = new java.util.HashSet<>();
        if (k.has("bones") && k.get("bones").isJsonArray())
            for (JsonElement e : k.getAsJsonArray("bones"))
                if (e.isJsonPrimitive()) visibleBones.add(e.getAsString());
        Map<String, String> boneWhen = new HashMap<>();
        if (k.has("bone_when") && k.get("bone_when").isJsonObject())
            for (var e : k.getAsJsonObject("bone_when").entrySet())
                if (e.getValue().isJsonPrimitive())
                    boneWhen.put(e.getKey(), e.getValue().getAsString().toLowerCase());

        Binding b = new Binding(blockId, model, tex, tier, scale, anim, offset, rotate, renderKind, onKontra,
            animation, clips, tint, rotateBy, rotateBase, List.copyOf(triggers),
            java.util.Set.copyOf(visibleBones), Map.copyOf(boneWhen));
        BY_ID.put(blockId, b);

        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        if (block != null) {
            BY_BLOCK.put(block, b);
            if (wholeModelHitbox(k.get("hitbox"))) MODEL_HITBOX.add(block);
            Set<String> boneNames = hitboxBoneNames(k.get("hitbox"));
            if (!boneNames.isEmpty()) HITBOX_BONES.put(block, boneNames);
            VoxelShape shape = parseHitbox(k.get("hitbox"));
            if (shape != null) SHAPES.put(block, shape);
            double[] hb = hitboxBox(k.get("hitbox"));
            if (hb != null) SHAPE_BOXES.put(block, hb);
        }

        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kodel] bound block {} -> model '{}' tex {} tier {} anim {}", blockId, model, tex, tier, anim.size());
    }

    // an array of STRINGS is a bone pick list; an array of numbers is still the old pixel box
    private static Set<String> hitboxBoneNames(JsonElement h) {
        if (h == null || !h.isJsonArray()) return Set.of();
        JsonArray a = h.getAsJsonArray();
        Set<String> out = new java.util.HashSet<>();
        for (JsonElement e : a) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return Set.of();
            out.add(e.getAsString().toLowerCase());
        }
        return out;
    }

    private static boolean wholeModelHitbox(JsonElement h) {
        if (h == null || h.isJsonNull() || !h.isJsonPrimitive() || !h.getAsJsonPrimitive().isString())
            return false;
        String s = h.getAsString().toLowerCase();
        return s.equals("model") || s.equals("bones") || s.equals("geo");
    }

    // hitbox: [x0,y0,z0,x1,y1,z1] in pixels (0-16) | "none" | "model" | absent/"cube" = full
    private static VoxelShape parseHitbox(JsonElement h) {
        if (h == null || h.isJsonNull()) return null;
        if (h.isJsonArray()) {
            JsonArray a = h.getAsJsonArray();
            if (!hitboxBoneNames(h).isEmpty()) return null; // bone pick list, shape comes from the model
            if (a.size() >= 6) {
                return Shapes.box(a.get(0).getAsDouble() / 16, a.get(1).getAsDouble() / 16, a.get(2).getAsDouble() / 16,
                                  a.get(3).getAsDouble() / 16, a.get(4).getAsDouble() / 16, a.get(5).getAsDouble() / 16);
            }
            return null;
        }
        return "none".equalsIgnoreCase(h.getAsString()) ? Shapes.empty() : null;
    }

    // used by the geo block in BlockFactory. no entry = full cube
    public static VoxelShape shape(Block b) {
        return SHAPES.getOrDefault(b, Shapes.block());
    }

    // BONE HITBOX for blocks — same deal entities get: a bone called *hitbox* in the geo model IS the
    // shape (outline + collision), rotated by facing and shifted by the block's state offset.
    // null = this model has no hitbox bone, caller falls back to the json box / full cube
    public static VoxelShape boneShape(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) return null;
        if (SHAPES.containsKey(state.getBlock())) return null; // an authored json box wins, packs stay put
        if (NO_BONES.contains(state.getBlock())) return null;
        VoxelShape cached = BONE_SHAPE.get(state);
        if (cached != null) return cached;

        var boxes = KodelPhysicsShapes.boneBoxes(state);
        if (boxes.isEmpty()) {
            NO_BONES.add(state.getBlock());
            return null;
        }
        // obb -> enclosing aabb, mc voxel shapes can't tilt. still way tighter than a full cube
        VoxelShape shape = Shapes.empty();
        for (var b : boxes) {
            org.joml.Quaternionf q = new org.joml.Quaternionf(b.qx(), b.qy(), b.qz(), b.qw());
            org.joml.Vector3f ax = q.transform(new org.joml.Vector3f(b.hx(), 0, 0));
            org.joml.Vector3f ay = q.transform(new org.joml.Vector3f(0, b.hy(), 0));
            org.joml.Vector3f az = q.transform(new org.joml.Vector3f(0, 0, b.hz()));
            float ex = Math.abs(ax.x) + Math.abs(ay.x) + Math.abs(az.x);
            float ey = Math.abs(ax.y) + Math.abs(ay.y) + Math.abs(az.y);
            float ez = Math.abs(ax.z) + Math.abs(ay.z) + Math.abs(az.z);
            shape = Shapes.joinUnoptimized(shape, Shapes.box(
                b.cx() + 0.5 - ex, b.cy() + 0.5 - ey, b.cz() + 0.5 - ez,
                b.cx() + 0.5 + ex, b.cy() + 0.5 + ey, b.cz() + 0.5 + ez), BooleanOp.OR);
        }
        shape = shape.optimize();
        BONE_SHAPE.put(state, shape);
        return shape;
    }

    // state-aware: a boxed hitbox follows rotate_by, so a wall bearing's plate sits ON the wall
    // and you can actually click its face to hang the wheel. non-box shapes stay unrotated.
    public static VoxelShape shape(net.minecraft.world.level.block.state.BlockState state) {
        if (state != null && state.getBlock() instanceof
                com.koper.koper_lib.api.core.KoperStateShape provider)
            return provider.koperPickShape(state);
        Block block = state.getBlock();
        VoxelShape bones = boneShape(state);
        if (bones != null) return bones;
        VoxelShape base = SHAPES.getOrDefault(block, Shapes.block());
        Binding bind = BY_BLOCK.get(block);
        double[] box = SHAPE_BOXES.get(block);
        if (bind == null || bind.rotateBy().isEmpty() || box == null) return base;
        return SHAPE_BY_STATE.computeIfAbsent(state, s -> {
            org.joml.Quaternionf q = facingQuat(bind, s);
            // spin both corners around the cell centre — 90° steps keep boxes boxes
            org.joml.Vector3f a = new org.joml.Vector3f((float)(box[0]-0.5), (float)(box[1]-0.5), (float)(box[2]-0.5));
            org.joml.Vector3f c = new org.joml.Vector3f((float)(box[3]-0.5), (float)(box[4]-0.5), (float)(box[5]-0.5));
            if (q != null) {
                q.transform(a);
                q.transform(c);
            }
            net.minecraft.world.phys.Vec3 offset = block instanceof
                    com.koper.koper_lib.api.core.KoperStateOffset provider
                    ? provider.koperStateOffset(s) : net.minecraft.world.phys.Vec3.ZERO;
            return Shapes.box(
                Math.min(a.x, c.x) + 0.5 + offset.x, Math.min(a.y, c.y) + 0.5 + offset.y, Math.min(a.z, c.z) + 0.5 + offset.z,
                Math.max(a.x, c.x) + 0.5 + offset.x, Math.max(a.y, c.y) + 0.5 + offset.y, Math.max(a.z, c.z) + 0.5 + offset.z);
        });
    }

    // model spin for rotate_by: quat taking the AUTHORED facing (rotate_by_base) onto the state's
    // facing. null = nothing to do. shared by every render path + the hitbox above — keep in sync
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static org.joml.Quaternionf facingQuat(Binding b, net.minecraft.world.level.block.state.BlockState state) {
        if (b == null || b.rotateBy().isEmpty() || state == null) return null;
        var prop = state.getBlock().getStateDefinition().getProperty(b.rotateBy());
        if (prop == null) return null;
        Object v = state.getValue((net.minecraft.world.level.block.state.properties.Property) prop);
        if (!(v instanceof net.minecraft.core.Direction d)) return null;
        String base = b.rotateBase();
        if (d.getSerializedName().equals(base)) return null; // already authored this way
        // spelled out instead of Direction.byName: that quietly answered null for "up"/"down" here, so
        // every model authored for a floor/ceiling base silently span like it was authored facing north
        org.joml.Quaternionf q = dirQuat(d);
        return q.mul(dirQuat(baseDir(base)).conjugate());
    }

    private static net.minecraft.core.Direction baseDir(String name) {
        return switch (name) {
            case "up"    -> net.minecraft.core.Direction.UP;
            case "down"  -> net.minecraft.core.Direction.DOWN;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "east"  -> net.minecraft.core.Direction.EAST;
            case "west"  -> net.minecraft.core.Direction.WEST;
            default      -> net.minecraft.core.Direction.NORTH;
        };
    }

    // rotationY(+90°) sends the north-facing front toward WEST (RH, Y up) — the mapping falls out of that
    private static org.joml.Quaternionf dirQuat(net.minecraft.core.Direction d) {
        return switch (d) {
            case NORTH -> new org.joml.Quaternionf();
            case WEST  -> new org.joml.Quaternionf().rotationY((float) Math.toRadians(90));
            case SOUTH -> new org.joml.Quaternionf().rotationY((float) Math.toRadians(180));
            case EAST  -> new org.joml.Quaternionf().rotationY((float) Math.toRadians(270));
            case UP    -> new org.joml.Quaternionf().rotationX((float) Math.toRadians(90));
            case DOWN  -> new org.joml.Quaternionf().rotationX((float) Math.toRadians(-90));
        };
    }

    // hitbox as raw unit-space box for the rotation path — array form only ("none"/"cube" don't rotate)
    private static double[] hitboxBox(JsonElement h) {
        if (h == null || !h.isJsonArray()) return null;
        JsonArray a = h.getAsJsonArray();
        if (a.size() < 6) return null;
        return new double[]{
            a.get(0).getAsDouble() / 16, a.get(1).getAsDouble() / 16, a.get(2).getAsDouble() / 16,
            a.get(3).getAsDouble() / 16, a.get(4).getAsDouble() / 16, a.get(5).getAsDouble() / 16 };
    }

    // "anim": { "spin":[{bone,axis,speed,when}], "hold":[{bone,axis,angle}],
    //           "sway":[{bone,axis,angle,speed,phase}], "bob":[{bone,axis,dist,speed,phase}] }
    private static List<AnimOp> parseAnim(JsonObject a) {
        List<AnimOp> out = new ArrayList<>();
        if (a == null) return out;
        readOps(a, "spin", SPIN, out);
        readOps(a, "hold", HOLD, out);
        readOps(a, "sway", SWAY, out);
        readOps(a, "bob",  BOB,  out);
        readOps(a, "shift",   SHIFT,   out);
        readOps(a, "stretch", STRETCH, out);
        return out;
    }

    private static void readOps(JsonObject a, String key, int kind, List<AnimOp> out) {
        if (!a.has(key) || !a.get(key).isJsonArray()) return;
        for (JsonElement e : a.getAsJsonArray(key)) {
            JsonObject o = e.getAsJsonObject();
            String bone = str(o, "bone", "");
            if (bone.isBlank()) continue;
            int axis = axis(str(o, "axis", "y"));
            float speed = num(o, "speed", 0f);
            // amplitude: bob reads "dist", shift "px", stretch "per", the rest read "angle"
            float amp = switch (kind) {
                case BOB -> num(o, "dist", 0f);
                case SHIFT -> num(o, "px", 0f);
                case STRETCH -> num(o, "per", 0f);
                default -> num(o, "angle", 0f);
            };
            float phase = num(o, "phase", 0f);
            String when = str(o, "when", "").toLowerCase();
            if (when.equals("always")) when = "";
            String by = str(o, "by", "").toLowerCase();
            out.add(new AnimOp(bone, kind, axis, amp, speed, phase, when, by));
        }
    }


    public static void clear() {
        BY_ID.clear();
        BY_BLOCK.clear();
        SHAPES.clear();
        SHAPE_BOXES.clear();
        SHAPE_BY_STATE.clear();
        BONE_SHAPE.clear();
        NO_BONES.clear();
        MODEL_HITBOX.clear();
        HITBOX_BONES.clear();
        KodelPhysicsShapes.clear();
    }







    private static int axis(String s) {
        return switch (s.toLowerCase()) { case "x" -> 0; case "z" -> 2; default -> 1; };
    }

    private static Identifier resolveTex(String ns, String texRaw) {
        String t = texRaw.endsWith(".png") ? texRaw.substring(0, texRaw.length() - 4) : texRaw;
        if (t.contains(":")) {
            int c = t.indexOf(':');
            ns = t.substring(0, c);
            t = t.substring(c + 1);
        }
        String path = t.startsWith("textures/") ? t : "textures/" + t;
        return Identifier.fromNamespaceAndPath(ns, path + ".png");
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static float num(JsonObject o, String key, float def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : def;
    }

    private static float[] vec3(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new float[]{0, 0, 0};
        JsonArray a = e.getAsJsonArray();
        return new float[]{
            a.size() > 0 ? a.get(0).getAsFloat() : 0,
            a.size() > 1 ? a.get(1).getAsFloat() : 0,
            a.size() > 2 ? a.get(2).getAsFloat() : 0
        };
    }

    static String hjsonLite(String s) {
        s = s.replaceAll("(?m)//.*$", "");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll(",(\\s*[}\\]])", "$1");
        return s;
    }
}
