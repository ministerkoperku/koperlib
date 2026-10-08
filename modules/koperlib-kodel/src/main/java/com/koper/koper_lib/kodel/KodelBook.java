package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperPackSources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// where loaded .kodel files live. packs drop them in <pack>/kodel/<name>.kodel and
// everything else asks here by bare name. caches the parse, the rest pose and the
// hitboxes, because all three are the same answer every frame.
//
// clear() on reload or the game keeps rendering last session's models
public final class KodelBook {

    // model and clips are already in mc render space. the raw container is NOT kept:
    // a model costs about 240 KB in memory against 8 KB on disk, so holding both the
    // authored copy and the render copy doubled that for nothing. texture is the only
    // part of the container anyone wanted back
    public record Entry(String name, byte[] texture, KodelModel model,
                        List<KodelAnimation> clips, float[] restPose,
                        List<KodelHitboxer.Obb> hitboxes, float[] hitboxBounds,
                        float[] modelBounds) {

        public KodelAnimation clip(String clipName) {
            if (clipName == null) return null;
            for (KodelAnimation a : clips) {
                if (a.name.equals(clipName)) return a;
            }
            return null;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    // names we already failed on, so a typo doesn't hit the disk sixty times a second
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();
    private static final Map<String, KodelSampler.ResolvedTracks> TRACKS = new ConcurrentHashMap<>();

    private KodelBook() {}

    public static void clear() {
        CACHE.clear();
        MISSING.clear();
        TRACKS.clear();
    }

    /// drop one model so the next get() reads it again, after its source file changed
    public static void forget(String name) {
        CACHE.remove(name);
        MISSING.remove(name);
        TRACKS.keySet().removeIf(key -> key.startsWith(name + '\u0000'));
    }

    /// hand a model straight in, for mods that ship theirs in the jar rather than a pack
    public static Entry put(String name, KodelLoader.Loaded loaded) {
        if (name == null || loaded == null) return null;
        Entry entry = build(name, loaded);
        CACHE.put(name, entry);
        MISSING.remove(name);
        return entry;
    }

    /// null when nothing in any enabled pack answers to that name
    public static Entry get(String name) {
        if (name == null || name.isBlank()) return null;
        Entry cached = CACHE.get(name);
        if (cached != null) return cached;
        if (MISSING.containsKey(name)) return null;

        Path file = find(name);
        try {
            // a packed .kodel wins; a fullpack's bedrock geo of the same name is converted on first use
            KodelLoader.Loaded loaded = file != null ? KodelLoader.load(file) : KodelGeoSources.load(name);
            if (loaded == null) {
                MISSING.put(name, Boolean.TRUE);
                return null;
            }
            Entry entry = build(name, loaded);
            CACHE.put(name, entry);
            return entry;
        } catch (IOException | RuntimeException e) {
            // a broken pack model must not take the renderer with it, but it has to say so
            KodelGeoSources.fail(file != null ? file.toString() : name, e);
            MISSING.put(name, Boolean.TRUE);
            return null;
        }
    }

    public static KodelModel model(String name) {
        Entry entry = get(name);
        return entry == null ? null : entry.model();
    }

    /// bone matrices for a clip at time t, or the rest pose when the clip is unknown.
    /// resolving tracks means a name lookup per bone so it is cached per model+clip
    public static float[] pose(String name, String clip, float t, float[] into) {
        Entry entry = get(name);
        if (entry == null) return null;
        KodelModel model = entry.model();
        int need = model.bones.size() * KodelSampler.MAT4_FLOATS;
        if (clip == null || clip.isBlank()) return entry.restPose();

        KodelAnimation anim = entry.clip(clip);
        if (anim == null) return entry.restPose();
        float[] out = into != null && into.length >= need ? into : new float[need];
        KodelSampler.samplePose(model, tracksFor(name, clip, model, anim), fold(anim, t),
            new float[3], new float[3], new float[3], out);
        return out;
    }

    /// Wraps a looping clip back to its start and clamps one that does not loop.
    ///
    /// Callers hand over the seconds since the clip began, which grows forever. The
    /// sampler clamps anything past the last keyframe, so without this a walk cycle
    /// plays once and then freezes on its final frame while the mob keeps walking.
    public static float fold(KodelAnimation anim, float t) {
        if (anim == null || anim.length <= 0f) return 0f;
        if (!anim.loop) return t < 0f ? 0f : Math.min(t, anim.length);
        float wrapped = t % anim.length;
        return wrapped < 0f ? wrapped + anim.length : wrapped;
    }

    /// cross-fade between two clips. mix 0 is all `from`, 1 is all `to`. either name
    /// may be null or unknown, which means the bind pose for that side
    public static float[] poseBlended(String name, String from, float tFrom,
                                      String to, float tTo, float mix, float[] into) {
        Entry entry = get(name);
        if (entry == null) return null;
        KodelModel model = entry.model();
        KodelAnimation a = entry.clip(from);
        KodelAnimation b = entry.clip(to);
        if (a == null && b == null) return entry.restPose();

        int need = model.bones.size() * KodelSampler.MAT4_FLOATS;
        float[] out = into != null && into.length >= need ? into : new float[need];
        KodelSampler.samplePoseBlended(model,
            a == null ? null : tracksFor(name, from, model, a), fold(a, tFrom),
            b == null ? null : tracksFor(name, to, model, b), fold(b, tTo),
            mix, out);
        return out;
    }

    /// Lays one clip over another: the overlay wins on every bone it owns, the base
    /// keeps the rest. An attack that only animates a jaw should not stop the legs.
    ///
    /// Merging happens on the tracks, before anything is sampled. Splicing finished
    /// world matrices instead leaves the children of an overlaid bone hanging off the
    /// pose their parent used to be in, which detaches teeth from a jaw.
    public static float[] poseLayered(String name, String base, float tBase,
                                      String overlay, float tOverlay, float[] into) {
        return poseLayered(name, base, tBase, null, 0f, 0f, overlay, tOverlay, into);
    }

    /// The whole thing a mob renderer needs: a base clip, optionally cross-fading out
    /// of the one before it, with an attack laid over the bones it owns.
    public static float[] poseLayered(String name, String base, float tBase,
                                      String fadeTo, float tFade, float mix,
                                      String overlay, float tOverlay, float[] into) {
        return poseLayered(name, base, tBase, fadeTo, tFade, mix, overlay, tOverlay, 1f, into);
    }

    /// {@code overWeight} lets the attack arrive and leave instead of snapping on.
    public static float[] poseLayered(String name, String base, float tBase,
                                      String fadeTo, float tFade, float mix,
                                      String overlay, float tOverlay, float overWeight,
                                      float[] into) {
        Entry entry = get(name);
        if (entry == null) return null;
        KodelModel model = entry.model();
        KodelAnimation under = entry.clip(base);
        KodelAnimation into2 = entry.clip(fadeTo);
        KodelAnimation over = entry.clip(overlay);
        if (under == null && into2 == null && over == null) return entry.restPose();

        int need = model.bones.size() * KodelSampler.MAT4_FLOATS;
        float[] out = into != null && into.length >= need ? into : new float[need];
        // a null clip on either side of the fade means the bind pose. a mob that
        // stops walking is fading TO nothing, and that has to be a real fade
        KodelSampler.samplePoseLayered(model,
            under == null ? null : tracksFor(name, base, model, under), fold(under, tBase),
            into2 == null ? null : tracksFor(name, fadeTo, model, into2), fold(into2, tFade), mix,
            over == null ? null : tracksFor(name, overlay, model, over), fold(over, tOverlay),
            overWeight, out);
        return out;
    }

    /// hitboxes at a pose. the cached list is the rest pose one; pass posed matrices
    /// for a mob that is actually moving
    public static List<KodelHitboxer.Obb> hitboxes(String name, float[] boneWorld) {
        Entry entry = get(name);
        if (entry == null) return List.of();
        if (boneWorld == null || boneWorld == entry.restPose()) return entry.hitboxes();
        return KodelHitboxer.of(entry.model(), boneWorld);
    }

    private static KodelSampler.ResolvedTracks tracksFor(String name, String clip,
                                                         KodelModel model, KodelAnimation anim) {
        return TRACKS.computeIfAbsent(name + '\u0000' + clip,
            key -> KodelSampler.ResolvedTracks.of(model, anim));
    }

    private static Entry build(String name, KodelLoader.Loaded loaded) {
        // the container holds bedrock authoring space. everything downstream of here
        // wants mc render space, so the flip happens once, here, and gets cached
        KodelModel model = KodelBedrock.toRenderSpace(loaded.model());
        List<KodelAnimation> clips = loaded.animations() == null ? List.of()
            : List.copyOf(KodelBedrock.toRenderSpace(loaded.animations()));
        float[] rest = new float[model.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(model, null, 0f, new float[3], new float[3], new float[3], rest);
        List<KodelHitboxer.Obb> boxes = KodelHitboxer.of(model, rest);
        return new Entry(name, loaded.texture(), model, clips, rest, List.copyOf(boxes),
            KodelHitboxer.bounds(boxes), restBounds(model, rest));
    }

    /// Box around the whole model at rest, in block units: minX minY minZ maxX maxY maxZ.
    ///
    /// A kodel mob is routinely far bigger than the aabb its entity type declares, and
    /// vanilla culls on that aabb, so the model blinks out the moment the little box
    /// leaves the frustum while the thing is still filling the screen. This is what a
    /// renderer should widen its culling box by. Rest pose only, because a per frame
    /// box would have to be recomputed every frame to be worth anything.
    private static float[] restBounds(KodelModel model, float[] rest) {
        float[] mesh = KodelModelRender.bake(model, rest, null);
        if (mesh.length < KodelModelRender.STRIDE) return new float[] {0, 0, 0, 0, 0, 0};
        float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE, mnz = Float.MAX_VALUE;
        float mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE, mxz = -Float.MAX_VALUE;
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            mnx = Math.min(mnx, mesh[i]);     mxx = Math.max(mxx, mesh[i]);
            mny = Math.min(mny, mesh[i + 1]); mxy = Math.max(mxy, mesh[i + 1]);
            mnz = Math.min(mnz, mesh[i + 2]); mxz = Math.max(mxz, mesh[i + 2]);
        }
        return new float[] {mnx, mny, mnz, mxx, mxy, mxz};
    }

    /// How far a renderer has to widen its culling box, in blocks: horizontal reach,
    /// then how far the model rises and drops. Zero when the model is unknown.
    public static float[] cullingReach(String name) {
        Entry entry = get(name);
        if (entry == null) return new float[] {0f, 0f, 0f};
        float[] b = entry.modelBounds();
        float side = Math.max(Math.max(Math.abs(b[0]), Math.abs(b[3])),
                              Math.max(Math.abs(b[2]), Math.abs(b[5])));
        return new float[] {side, Math.max(0f, b[4]), Math.max(0f, -b[1])};
    }

    /// <source root>/<pack>/kodel/<name>.kodel, enabled packs only
    private static Path find(String name) {
        String file = name.endsWith(".kodel") ? name : name + ".kodel";
        for (KoperPackSources.Source source : KoperPackSources.all()) {
            Path root = source.root();
            if (root == null || !Files.isDirectory(root)) continue;
            try (var packs = Files.list(root)) {
                for (Path pack : packs.toList()) {
                    if (!Files.isDirectory(pack)) continue;
                    String packName = pack.getFileName().toString();
                    if (packName.startsWith(".") || !source.isEnabled(packName)) continue;
                    Path candidate = pack.resolve("kodel").resolve(file);
                    if (Files.isRegularFile(candidate)) return candidate;
                }
            } catch (IOException ignored) {
                // unreadable source, try the next one
            }
        }
        return null;
    }

    /// every model name the enabled packs offer, for commands and tab completion
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (KoperPackSources.Source source : KoperPackSources.all()) {
            Path root = source.root();
            if (root == null || !Files.isDirectory(root)) continue;
            try (var packs = Files.list(root)) {
                for (Path pack : packs.toList()) {
                    Path dir = pack.resolve("kodel");
                    if (!Files.isDirectory(dir)) continue;
                    if (!source.isEnabled(pack.getFileName().toString())) continue;
                    try (var files = Files.list(dir)) {
                        for (Path f : files.toList()) {
                            String n = f.getFileName().toString();
                            if (n.endsWith(".kodel")) out.add(n.substring(0, n.length() - 6));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        for (String geo : KodelGeoSources.names()) if (!out.contains(geo)) out.add(geo);
        out.sort(String::compareTo);
        return out;
    }
}
