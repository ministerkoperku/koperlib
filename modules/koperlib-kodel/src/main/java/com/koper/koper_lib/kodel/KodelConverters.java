package com.koper.koper_lib.kodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

// bedrock .geo.json + .animation.json -> kodel. layout follows kodel/SPEC.md so
// python and java spit out the same bytes. molang keyframes get dropped, they need
// game state and the container holds numbers
public final class KodelConverters {
    public static final String[] FACE_NAMES = {"px", "nx", "py", "ny", "pz", "nz"};

    // +Z is south in both editions and SPEC says pz = south. the old python mapped
    // this backwards which put the front of every per-face-uv model on its arse
    private static final String[] BEDROCK_FACE = {"east", "west", "up", "down", "south", "north"};

    // two keys at the same time break the binary search, pre/post splits use this gap
    private static final float DISCONTINUITY = 1e-4f;

    private KodelConverters() {}

    public static KodelModel geometry(JsonObject geo) {
        JsonObject desc = geo.has("description") ? geo.getAsJsonObject("description") : new JsonObject();
        int texW = desc.has("texture_width") ? desc.get("texture_width").getAsInt() : 64;
        int texH = desc.has("texture_height") ? desc.get("texture_height").getAsInt() : 64;
        KodelModel m = new KodelModel();
        m.texWidth = texW > 0 ? texW : 64;
        m.texHeight = texH > 0 ? texH : 64;

        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null) return m;

        // bedrock does NOT promise a bone shows up after its parent. old code only
        // looked backwards and silently orphaned everything declared out of order
        int n = bones.size();
        String[] names = new String[n];
        String[] parentNames = new String[n];
        for (int i = 0; i < n; i++) {
            JsonObject b = bones.get(i).getAsJsonObject();
            names[i] = b.has("name") ? b.get("name").getAsString().strip() : "bone" + i;
            parentNames[i] = b.has("parent") && !b.get("parent").isJsonNull()
                ? b.get("parent").getAsString().strip() : null;
        }
        int[] order = parentFirstOrder(names, parentNames);

        int[] indexOf = new int[n];
        for (int slot = 0; slot < n; slot++) indexOf[order[slot]] = slot;

        for (int slot = 0; slot < n; slot++) {
            int src = order[slot];
            JsonObject b = bones.get(src).getAsJsonObject();
            KodelModel.KodelBone kb = new KodelModel.KodelBone();
            kb.name = names[src];
            kb.parent = -1;
            if (parentNames[src] != null) {
                for (int j = 0; j < n; j++) {
                    if (names[j].equals(parentNames[src])) {
                        kb.parent = indexOf[j];
                        break;
                    }
                }
            }
            v3(b, "pivot", kb.pivot);
            v3(b, "position", kb.position);
            JsonObject rodzic = null;
            if (parentNames[src] != null) for (int j = 0; j < n; j++)
                if (names[j].equals(parentNames[src])) { rodzic = bones.get(j).getAsJsonObject(); break; }
            float[] e = spoczynek(b, rodzic, kb.position);
            eulerDegToQuat(e[0], e[1], e[2], kb.rotation);
            kb.scale[0] = kb.scale[1] = kb.scale[2] = 1f;

            // bone can set mirror/inflate once for all its cubes
            boolean boneMirror = b.has("mirror") && b.get("mirror").getAsBoolean();
            float boneInflate = b.has("inflate") ? b.get("inflate").getAsFloat() : 0f;

            JsonArray cubes = b.has("cubes") ? b.getAsJsonArray("cubes") : null;
            if (cubes != null) {
                for (JsonElement ce : cubes) {
                    kb.cubes.add(cube(ce.getAsJsonObject(), boneMirror, boneInflate));
                }
            }
            JsonArray tms = b.has("texture_meshes") ? b.getAsJsonArray("texture_meshes") : null;
            if (tms != null) {
                for (JsonElement tm : tms) {
                    if (tm.isJsonObject()) kb.cubes.add(cube(textureMesh(tm.getAsJsonObject(), m.texWidth, m.texHeight, kb.pivot[1]), false, 0f));
                }
            }
            m.bones.add(kb);
        }

        for (int slot = 0; slot < n; slot++) {
            JsonObject b = bones.get(order[slot]).getAsJsonObject();
            if (!b.has("poly_mesh")) continue;
            KodelModel.KodelMesh mesh = polyMesh(b.getAsJsonObject("poly_mesh"), slot, m.texWidth, m.texHeight);
            if (mesh != null) m.meshes.add(mesh);
        }
        return m;
    }

    // parents before children, model.bin and the sampler both need that. cycles keep
    // their slot instead of losing the bone
    private static int[] parentFirstOrder(String[] names, String[] parentNames) {
        int n = names.length;
        int[] parentIndex = new int[n];
        for (int i = 0; i < n; i++) {
            parentIndex[i] = -1;
            if (parentNames[i] == null) continue;
            for (int j = 0; j < n; j++) {
                if (j != i && names[j].equals(parentNames[i])) {
                    parentIndex[i] = j;
                    break;
                }
            }
        }
        int[] order = new int[n];
        boolean[] placed = new boolean[n];
        int at = 0;
        for (int pass = 0; pass < n && at < n; pass++) {
            boolean progress = false;
            for (int i = 0; i < n; i++) {
                if (placed[i]) continue;
                int p = parentIndex[i];
                if (p >= 0 && !placed[p]) continue;
                order[at++] = i;
                placed[i] = true;
                progress = true;
            }
            if (!progress) break;
        }
        for (int i = 0; i < n && at < n; i++) {
            if (!placed[i]) order[at++] = i;
        }
        return order;
    }

    // bedrock texture_meshes = the item png pushed out 1px, like java's generated item models. the picture
    // lies flat in x/z (u along +x, v along +z), "position" is where local_pivot ends up after the turn.
    // one slab with the picture top and bottom, cutout eats the empty pixels. edges are just the border
    // strips, java does proper per pixel sides but nobody has looked that close at a held sword yet
    // same reading as BrTyp.runs (blockbench's): "position" y counts down from the bone's pivot
    private static JsonObject textureMesh(JsonObject tm, int w, int h, float boneY) {
        float[] pos = v3or(tm, "position", 0, 0, 0);
        pos[1] = boneY - pos[1];
        float[] lp = v3or(tm, "local_pivot", 0, 0, 0);
        float[] sc = v3or(tm, "scale", 1, 1, 1);
        JsonObject c = new JsonObject();
        c.add("origin", vec(pos[0] - lp[0], pos[1] - sc[1] + lp[1], pos[2] - lp[2]));
        c.add("size", vec(w * sc[0], sc[1], h * sc[2]));
        c.add("pivot", vec(pos[0], pos[1], pos[2]));
        if (tm.has("rotation")) c.add("rotation", tm.get("rotation"));
        JsonObject uv = new JsonObject();
        // flips measured against KodelModelRender, not guessed: these put pixel (u,v) at (x=u, z=v)
        uv.add("up", face(w, h, -w, -h));
        uv.add("down", face(w, 0, -w, h));
        uv.add("north", face(0, 0, w, 1));
        uv.add("south", face(0, h - 1, w, 1));
        // side faces run along z, the column runs along v: turn it a quarter
        JsonObject west = face(0, 0, 1, h), east = face(w - 1, 0, 1, h);
        west.addProperty("uv_rotation", 90);
        east.addProperty("uv_rotation", 90);
        uv.add("west", west);
        uv.add("east", east);
        c.add("uv", uv);
        return c;
    }

    private static JsonObject face(float u, float v, float uw, float vh) {
        JsonObject f = new JsonObject();
        f.add("uv", vec(u, v));
        f.add("uv_size", vec(uw, vh));
        return f;
    }

    private static JsonArray vec(float... xs) {
        JsonArray a = new JsonArray();
        for (float x : xs) a.add(x);
        return a;
    }

    private static KodelModel.KodelCube cube(JsonObject c, boolean boneMirror, float boneInflate) {
        KodelModel.KodelCube cube = new KodelModel.KodelCube();
        float[] origin = v3or(c, "origin", 0, 0, 0);
        float[] size = v3or(c, "size", 1, 1, 1);
        // negative size = the box grows the other way from origin (bedrock's inside out shells,
        // slime's inner layer). the face builder assumes positive, it threw those faces out of the box
        for (int ax = 0; ax < 3; ax++) {
            if (size[ax] < 0) { origin[ax] += size[ax]; size[ax] = -size[ax]; }
        }
        System.arraycopy(origin, 0, cube.origin, 0, 3);
        System.arraycopy(size, 0, cube.size, 0, 3);
        cube.inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : boneInflate;
        v3(c, "pivot", cube.pivot);
        float[] re = v3or(c, "rotation", 0, 0, 0);
        eulerDegToQuat(re[0], re[1], re[2], cube.rotation);
        cube.mirror = c.has("mirror") ? c.get("mirror").getAsBoolean() : boneMirror;
        KodelModel.KodelFace[] faces = facesOf(c, cube);
        System.arraycopy(faces, 0, cube.faces, 0, 6);
        return cube;
    }

    // a bone's rest rotation. old 1.8 geometry (bedrock's cat, ocelot, sheep and every pack copying them) keeps
    // it in bind_pose_rotation instead, skipping that left the cat's body standing on its end
    public static float[] spoczynek(JsonObject bone) {
        float[] r = v3or(bone, "rotation", 0, 0, 0);
        float[] bind = v3or(bone, "bind_pose_rotation", 0, 0, 0);
        return new float[] {r[0] + bind[0], r[1] + bind[1], r[2] + bind[2]};
    }

    // same, for a bone under a parent. a 1.8 bind pose is where the parent sits in the model, not a turn its
    // children inherit: the cat's tail pivot is already written behind the turned body, and adding the body's
    // 90 to the tail's own 90 stood the tail straight up. so the parent's bind is taken back out of the child,
    // turn and pivot both (pos gets the shift of undoing it around the parent's pivot). no bind on the parent:
    // plain spoczynek, pos untouched
    public static float[] spoczynek(JsonObject bone, JsonObject parent, float[] pos) {
        float[] e = spoczynek(bone);
        if (parent == null || !parent.has("bind_pose_rotation")) return e;
        float[] pb = v3or(parent, "bind_pose_rotation", 0, 0, 0);
        if (pb[0] == 0 && pb[1] == 0 && pb[2] == 0) return e;
        float[] pp = v3or(parent, "pivot", 0, 0, 0), pc = v3or(bone, "pivot", 0, 0, 0);
        // the actor turns bedrock's x and z the other way (see kodel aktor compose), undo it with the same signs
        float[] q = new float[4];
        eulerDegToQuat(-pb[0], pb[1], -pb[2], q);
        float[] d = obrocWstecz(q, new float[] {pc[0] - pp[0], pc[1] - pp[1], pc[2] - pp[2]});
        for (int k = 0; k < 3; k++) pos[k] += d[k] + pp[k] - pc[k];
        return new float[] {e[0] - pb[0], e[1] - pb[1], e[2] - pb[2]};
    }

    // v turned by the inverse of unit quaternion q (x, y, z, w)
    private static float[] obrocWstecz(float[] q, float[] v) {
        float x = -q[0], y = -q[1], z = -q[2], w = q[3];
        float tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]);
        return new float[] {v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx)};
    }

    private static KodelModel.KodelFace[] facesOf(JsonObject c, KodelModel.KodelCube cube) {
        KodelModel.KodelFace[] out = new KodelModel.KodelFace[6];
        for (int i = 0; i < 6; i++) out[i] = new KodelModel.KodelFace();

        if (c.has("uv") && c.get("uv").isJsonArray()) {
            float[] uv = arr(c.get("uv").getAsJsonArray());
            classicBox(cube, uv, out);
            return out;
        }
        JsonObject uvMap = c.has("uv") && c.get("uv").isJsonObject() ? c.getAsJsonObject("uv") : null;
        if (uvMap == null) return out;
        for (int i = 0; i < 6; i++) {
            JsonElement e = uvMap.get(BEDROCK_FACE[i]);
            KodelModel.KodelFace f = out[i];
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            float[] off = o.has("uv") ? arr(o.get("uv").getAsJsonArray()) : new float[] {0, 0};
            float[] sz = o.has("uv_size") ? arr(o.get("uv_size").getAsJsonArray()) : new float[] {0, 0};
            f.u = off[0];
            f.v = off[1];
            f.uw = sz[0];
            f.vh = sz[1];
            if (o.has("uv_rotation")) f.rot = quarterTurns(o.get("uv_rotation").getAsInt());
            else if (o.has("rotation")) f.rot = quarterTurns(o.get("rotation").getAsInt());
            f.mirror = o.has("mirror") && o.get("mirror").getAsBoolean();
        }
        return out;
    }

    // classic box unwrap, the one blockbench and mc always used:
    //   row 1 (tall d) = py ny        row 2 (tall h) = px nz nx pz
    // written out per face on purpose, a wrong row is invisible here and very
    // visible in game. the old java had all six scrambled, that was THE bug.
    // ny keeps a negative height, the down face is flipped and that sign is how
    // the container says so
    private static void classicBox(KodelModel.KodelCube cube, float[] uv, KodelModel.KodelFace[] out) {
        float w = cube.size[0], h = cube.size[1], d = cube.size[2];
        float u = uv[0], v = uv[1];

        set(out[0], u, v + d, d, h);                 // px  east
        set(out[1], u + d + w, v + d, d, h);         // nx  west
        set(out[2], u + d, v, w, d);                 // py  up
        set(out[3], u + d + w, v + d, w, -d);        // ny  down
        set(out[4], u + d + w + d, v + d, w, h);     // pz  south
        set(out[5], u + d, v + d, w, h);             // nz  north

        // a mirrored box keeps these rects and flips each face across u, which is
        // what the mirror flag means to KodelModelRender.faceUv and how geckolib
        // does it too. do NOT swap the px/nx rects on top of that, it double flips
        if (cube.mirror) {
            for (int i = 0; i < 6; i++) out[i].mirror = true;
        }
    }

    private static void set(KodelModel.KodelFace f, float u, float v, float uw, float vh) {
        f.u = u;
        f.v = v;
        f.uw = uw;
        f.vh = vh;
        f.rot = 0;
        f.mirror = false;
    }

    // poly_mesh: positions/normals/uvs are POOLS, polys says which pool entries make
    // a face. old code read the pools as a vertex list so every poly mesh was noise
    private static KodelModel.KodelMesh polyMesh(JsonObject poly, int bone, int texW, int texH) {
        float[][] positions = pool(poly, "positions", 3);
        float[][] normals = pool(poly, "normals", 3);
        float[][] uvs = pool(poly, "uvs", 2);
        if (positions.length == 0) return null;
        boolean normalizedUvs = poly.has("normalized_uvs") && poly.get("normalized_uvs").getAsBoolean();

        List<int[]> tris = new ArrayList<>();
        JsonElement polys = poly.get("polys");
        if (polys != null && polys.isJsonPrimitive()) {
            // "tri_list" / "quad_list", pools already in draw order
            int stride = "quad_list".equals(polys.getAsString()) ? 4 : 3;
            for (int i = 0; i + stride <= positions.length; i += stride) {
                tris.add(fan(i, i + 1, i + 2));
                if (stride == 4) tris.add(fan(i, i + 2, i + 3));
            }
        } else if (polys != null && polys.isJsonArray()) {
            for (JsonElement pe : polys.getAsJsonArray()) {
                if (!pe.isJsonArray()) continue;
                JsonArray corners = pe.getAsJsonArray();
                int corner = corners.size();
                if (corner < 3) continue;
                int[][] idx = new int[corner][];
                for (int i = 0; i < corner; i++) idx[i] = triple(corners.get(i));
                for (int i = 1; i + 1 < corner; i++) {
                    tris.add(new int[] {
                        idx[0][0], idx[i][0], idx[i + 1][0],
                        idx[0][1], idx[i][1], idx[i + 1][1],
                        idx[0][2], idx[i][2], idx[i + 1][2],
                    });
                }
            }
        } else {
            for (int i = 0; i + 3 <= positions.length; i += 3) tris.add(fan(i, i + 1, i + 2));
        }
        if (tris.isEmpty()) return null;

        KodelModel.KodelMesh mesh = new KodelModel.KodelMesh();
        mesh.bone = bone;
        int vc = tris.size() * 3;
        mesh.positions = new float[vc * 3];
        mesh.uvs = new float[vc * 2];
        mesh.normals = new float[vc * 3];
        mesh.indices = new int[vc];
        int v = 0;
        for (int[] tri : tris) {
            for (int k = 0; k < 3; k++) {
                float[] p = at(positions, tri[k], 3);
                float[] nrm = at(normals, tri[3 + k], 3);
                float[] uv = at(uvs, tri[6 + k], 2);
                mesh.positions[v * 3] = p[0];
                mesh.positions[v * 3 + 1] = p[1];
                mesh.positions[v * 3 + 2] = p[2];
                mesh.normals[v * 3] = nrm[0];
                mesh.normals[v * 3 + 1] = nrm[1];
                mesh.normals[v * 3 + 2] = nrm[2];
                // container stores pixels top-left, bedrock's normalized form is
                // 0..1 from the bottom left
                mesh.uvs[v * 2] = normalizedUvs ? uv[0] * texW : uv[0];
                mesh.uvs[v * 2 + 1] = normalizedUvs ? (1f - uv[1]) * texH : uv[1];
                mesh.indices[v] = v;
                v++;
            }
        }
        return mesh;
    }

    // same index for pos/normal/uv
    private static int[] fan(int a, int b, int c) {
        return new int[] {a, b, c, a, b, c, a, b, c};
    }

    private static int[] triple(JsonElement e) {
        if (!e.isJsonArray()) return new int[] {0, 0, 0};
        JsonArray a = e.getAsJsonArray();
        int p = a.size() > 0 ? a.get(0).getAsInt() : 0;
        int nrm = a.size() > 1 ? a.get(1).getAsInt() : p;
        int uv = a.size() > 2 ? a.get(2).getAsInt() : p;
        return new int[] {p, nrm, uv};
    }

    private static float[][] pool(JsonObject o, String key, int width) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return new float[0][];
        JsonArray a = o.getAsJsonArray(key);
        float[][] out = new float[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            out[i] = a.get(i).isJsonArray() ? arr(a.get(i).getAsJsonArray()) : new float[width];
        }
        return out;
    }

    private static float[] at(float[][] pool, int index, int width) {
        if (index < 0 || index >= pool.length || pool[index] == null) return new float[width];
        float[] v = pool[index];
        if (v.length >= width) return v;
        float[] padded = new float[width];
        System.arraycopy(v, 0, padded, 0, v.length);
        return padded;
    }

    public static List<KodelAnimation> animations(JsonObject file) {
        List<KodelAnimation> out = new ArrayList<>();
        if (!file.has("animations")) return out;
        JsonObject all = file.getAsJsonObject("animations");
        for (String name : all.keySet()) {
            JsonObject body = all.getAsJsonObject(name);
            KodelAnimation anim = new KodelAnimation();
            // a clip named " attack_animation_bite" is a typo nothing can ever look
            // up, and kopertrex really does ship one
            anim.name = name.strip();
            anim.loop = loopOf(body);
            if (body.has("bones")) {
                JsonObject boneMap = body.getAsJsonObject("bones");
                for (String bone : boneMap.keySet()) {
                    JsonObject channels = boneMap.getAsJsonObject(bone);
                    KodelAnimation.KodelTrack track = new KodelAnimation.KodelTrack();
                    track.bone = bone.strip();
                    if (channels.has("rotation")) track.rotation = channel(channels.get("rotation"), true);
                    if (channels.has("position")) track.position = channel(channels.get("position"), false);
                    if (channels.has("scale")) track.scale = channel(channels.get("scale"), false);
                    if (track.hasChannels()) anim.tracks.add(track);
                }
            }
            // bedrock leaves animation_length out when the clip just runs to its last
            // key. defaulting to 1s chopped those clips in half
            anim.length = body.has("animation_length")
                ? body.get("animation_length").getAsFloat()
                : Math.max(lastKeyTime(anim), 1e-3f);
            out.add(anim);
        }
        return out;
    }

    // loop is a bool or the string hold_on_last_frame
    private static boolean loopOf(JsonObject body) {
        if (!body.has("loop") || !body.get("loop").isJsonPrimitive()) return false;
        var prim = body.getAsJsonPrimitive("loop");
        return prim.isBoolean() ? prim.getAsBoolean() : "true".equalsIgnoreCase(prim.getAsString());
    }

    private static float lastKeyTime(KodelAnimation anim) {
        float last = 0f;
        for (KodelAnimation.KodelTrack t : anim.tracks) {
            last = Math.max(last, channelEnd(t.rotation));
            last = Math.max(last, channelEnd(t.position));
            last = Math.max(last, channelEnd(t.scale));
        }
        return last;
    }

    private static float channelEnd(KodelAnimation.KodelChannel ch) {
        return ch == null || ch.count == 0 ? 0f : ch.times[ch.count - 1];
    }

    // eats every shape bedrock and blockbench emit: bare vector, one vector for the
    // whole clip, keyframe map, pre/post discontinuities, bezier handles
    private static KodelAnimation.KodelChannel channel(JsonElement node, boolean radians) {
        List<Key> keys = new ArrayList<>();
        if (node.isJsonArray()) {
            float[] value = vectorOf(node);
            if (value != null) keys.add(new Key(0f, value, KodelFormat.EASE_LINEAR, null, null));
        } else if (node.isJsonObject()) {
            JsonObject frames = node.getAsJsonObject();
            if (frames.has("vector")) {
                float[] value = vectorOf(frames.get("vector"));
                if (value != null) keys.add(new Key(0f, value, easingOf(frames), null, null));
            } else {
                for (String tStr : frames.keySet()) {
                    float t;
                    try {
                        t = Float.parseFloat(tStr);
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    readKey(frames.get(tStr), t, keys);
                }
            }
        }
        if (keys.isEmpty()) return null;

        // gson keeps file order and nothing says a bedrock file writes keys in
        // ascending time. the sampler binary searches so sort it, don't trust them
        keys.sort((a, b) -> Float.compare(a.time, b.time));

        int n = keys.size();
        float[] times = new float[n];
        float[] values = new float[n * 3];
        int[] easing = new int[n];
        float[] ctrlA = new float[n * 3];
        float[] ctrlB = new float[n * 3];
        for (int i = 0; i < n; i++) {
            Key k = keys.get(i);
            times[i] = k.time;
            easing[i] = k.easing;
            for (int c = 0; c < 3; c++) {
                float value = k.value[c];
                values[i * 3 + c] = radians ? (float) Math.toRadians(value) : value;
                float a = k.ctrlA == null ? 0f : k.ctrlA[c];
                float b = k.ctrlB == null ? 0f : k.ctrlB[c];
                ctrlA[i * 3 + c] = radians ? (float) Math.toRadians(a) : a;
                ctrlB[i * 3 + c] = radians ? (float) Math.toRadians(b) : b;
            }
        }
        return new KodelAnimation.KodelChannel().set(times, values, easing, ctrlA, ctrlB);
    }

    private static void readKey(JsonElement node, float t, List<Key> keys) {
        if (node.isJsonArray()) {
            // bare vector = plain linear. old code called this smooth and put
            // overshoot on literally every imported animation
            float[] value = vectorOf(node);
            if (value != null) keys.add(new Key(t, value, KodelFormat.EASE_LINEAR, null, null));
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject o = node.getAsJsonObject();

        float[] pre = o.has("pre") ? vectorIn(o.get("pre")) : null;
        float[] post = o.has("post") ? vectorIn(o.get("post")) : null;
        float[] plain = o.has("vector") ? vectorOf(o.get("vector")) : null;
        int easing = easingOf(o);
        float[] ctrlA = o.has("bezier_left_value") ? vectorOf(o.get("bezier_left_value")) : null;
        float[] ctrlB = o.has("bezier_right_value") ? vectorOf(o.get("bezier_right_value")) : null;
        if (ctrlA != null || ctrlB != null) easing = KodelFormat.EASE_BEZIER;

        if (pre != null && post != null) {
            // real discontinuity, one value coming in another going out. container has
            // no two-sided key so it becomes two keys a hair apart, samples as a snap
            keys.add(new Key(Math.max(0f, t - DISCONTINUITY), pre, KodelFormat.EASE_STEP, null, null));
            keys.add(new Key(t, post, easing, ctrlA, ctrlB));
            return;
        }
        float[] value = post != null ? post : pre != null ? pre : plain;
        if (value != null) keys.add(new Key(t, value, easing, ctrlA, ctrlB));
    }

    // bedrock default is LINEAR. catmullrom is the smooth one and maps to a bezier
    // key with no handles, which the sampler reads as auto catmull-rom
    private static int easingOf(JsonObject o) {
        if (!o.has("lerp_mode") || !o.get("lerp_mode").isJsonPrimitive()) return KodelFormat.EASE_LINEAR;
        return switch (o.get("lerp_mode").getAsString()) {
            case "step" -> KodelFormat.EASE_STEP;
            case "catmullrom", "smooth", "bezier" -> KodelFormat.EASE_BEZIER;
            default -> KodelFormat.EASE_LINEAR;
        };
    }

    private static float[] vectorIn(JsonElement e) {
        if (e.isJsonArray()) return vectorOf(e);
        if (e.isJsonObject() && e.getAsJsonObject().has("vector")) {
            return vectorOf(e.getAsJsonObject().get("vector"));
        }
        return null;
    }

    // null for molang strings or anything that isn't 3 numbers
    private static float[] vectorOf(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) return null;
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            JsonElement c = a.get(i);
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isNumber()) return null;
            out[i] = c.getAsFloat();
        }
        return out;
    }

    private record Key(float time, float[] value, int easing, float[] ctrlA, float[] ctrlB) {}

    // helpers ----------------------------------------------------------------

    private static int quarterTurns(int degrees) {
        return ((degrees / 90) % 4 + 4) % 4;
    }

    private static void v3(JsonObject o, String key, float[] out) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return;
        float[] a = arr(o.getAsJsonArray(key));
        System.arraycopy(a, 0, out, 0, Math.min(3, a.length));
    }

    private static float[] v3or(JsonObject o, String key, float x, float y, float z) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return new float[] {x, y, z};
        float[] a = arr(o.getAsJsonArray(key));
        return a.length >= 3 ? a : new float[] {x, y, z};
    }

    private static float[] arr(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < a.size(); i++) {
            JsonElement e = a.get(i);
            out[i] = e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : 0f;
        }
        return out;
    }

    // ZYX: X turns first then Y then Z. blockbench, bedrock and KodelSampler all do
    // this, so rest pose and animated pose compose the same
    static void eulerDegToQuat(float x, float y, float z, float[] out) {
        float rx = (float) Math.toRadians(x);
        float ry = (float) Math.toRadians(y);
        float rz = (float) Math.toRadians(z);
        float cx = (float) Math.cos(rx / 2), sx = (float) Math.sin(rx / 2);
        float cy = (float) Math.cos(ry / 2), sy = (float) Math.sin(ry / 2);
        float cz = (float) Math.cos(rz / 2), sz = (float) Math.sin(rz / 2);
        out[0] = sx * cy * cz - cx * sy * sz;
        out[1] = cx * sy * cz + sx * cy * sz;
        out[2] = cx * cy * sz - sx * sy * cz;
        out[3] = cx * cy * cz + sx * sy * sz;
    }
}
