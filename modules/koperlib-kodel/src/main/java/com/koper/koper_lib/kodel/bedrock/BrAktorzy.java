package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.kodel.KodelEntityRender;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// the glue between minecraft's render pipeline and the native bedrock actors. extract (once per
// entity per frame, render thread) ticks the actor and hangs a BrKlatka on the render state;
// the dispatcher mixin then draws that instead of the vanilla model
public final class BrAktorzy {

    private static final class Inst {
        final BrTyp typ;
        final long[] handles;
        final BrKlatka[] frames;
        final BrPytania.Stan stan = new BrPytania.Stan();
        int geo;
        boolean firstPerson;
        // attachables: bedrock's c.item_slot name and the holder's actor
        String slot;
        Inst holder;
        net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
        double lastLife = -1;
        long seenFrame;

        Inst(BrTyp typ) {
            this.typ = typ;
            this.handles = new long[typ.geos.size()];
            this.frames = new BrKlatka[typ.geos.size()];
            if (typ.onVanillaModel) {
                geo = -1;
                // "default" is the body. any other missing one (a shield geometry nobody shipped) must not become it
                for (int i = 0; i < typ.geos.size(); i++) if (typ.geos.get(i).model == null && typ.geos.get(i).key.equals("default")) { geo = i; break; }
                if (geo < 0) for (int i = 0; i < typ.geos.size(); i++) if (typ.geos.get(i).model == null) { geo = i; break; }
                if (geo < 0) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kodel/Bedrock] {} stays on the java model but has no java-drawn geometry, drawing its first one as the body", typ.id);
                    geo = 0;
                }
            }
        }
    }

    // variables bedrock's engine keeps on actors itself. packs replacing the player read them and never set them
    static final String[] SILNIK = {"attack_time", "is_first_person", "is_paperdoll", "map_face_icon", "is_holding_right",
        "is_holding_left", "player_x_rotation", "is_enchanted", "has_trim"};

    private static float silnik(Inst inst, int co) {
        Entity e = inst.stan.e;
        LivingEntity le = inst.stan.le;
        return switch (co) {
            case 0 -> le != null ? le.getSwingAnimation(inst.stan.pt) : 0f;
            case 1 -> inst.firstPerson ? 1f : 0f;
            case 2 -> inst.stan.ui ? 1f : 0f;
            case 4 -> le != null && !le.getMainHandItem().isEmpty() ? 1f : 0f;
            case 5 -> le != null && !le.getOffhandItem().isEmpty() ? 1f : 0f;
            case 6 -> e != null ? e.getXRot(inst.stan.pt) : 0f;
            case 7 -> inst.stack.hasFoil() ? 1f : 0f;
            case 8 -> inst.stack.has(net.minecraft.core.component.DataComponents.TRIM) ? 1f : 0f;
            default -> 0f; // map face icon: never here
        };
    }

    // the inventory screen draws the player again in the same frame: its own actors, or the
    // world one gets ticked twice with two different poses and shakes. render thread only
    public static boolean wUi;

    private static final Object NONE = new Object();
    private static final Map<String, Object> TYPY = new java.util.concurrent.ConcurrentHashMap<>();
    // a type being built in the background: drawn as java's own model until it's ready
    private static final Object PENDING = new Object();
    // one low priority thread builds the types. on the render thread the first sight of a mob froze the game
    // for 0.2 to 1.5 s, the player with a&s for 6.6 s: rlcraft has hundreds of mob types, so it stuttered all the time
    private static final java.util.concurrent.ExecutorService BUILDER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "kodel-bedrock-builder");
        th.setDaemon(true);
        th.setPriority(Thread.MIN_PRIORITY + 1);
        return th;
    });

    // null = not ready yet (or nothing to build); builds it in the background the first time it's asked for
    private static BrTyp built(BrPaczki.Indeks idx, String key, String what, java.util.function.Supplier<BrTyp> make) {
        Object got = TYPY.get(key);
        if (got instanceof BrTyp t) return t;
        if (got != null) return null; // NONE or PENDING
        if (TYPY.putIfAbsent(key, PENDING) != null) return null;
        BUILDER.execute(() -> {
            BrTyp t = null;
            long t0 = System.nanoTime();
            try {
                t = make.get();
            } catch (Throwable broke) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kodel/Bedrock] {} could not be built: {}", what, broke.toString());
            }
            if (forIndex != idx) {
                // the index changed while we built, this one belongs to nobody
                if (t != null) t.zwolnij();
                return;
            }
            TYPY.put(key, t == null ? NONE : t);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms >= 500) com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kodel/Bedrock] {} took {} ms to build (in the background)", what, ms);
        });
        return null;
    }
    private static final Map<Integer, Inst> INSTS = new HashMap<>();
    private static volatile BrPaczki.Indeks forIndex;
    private static long frame;

    private BrAktorzy() {}

    public static void przeladuj() {
        for (Inst i : INSTS.values()) free(i);
        INSTS.clear();
        for (Inst i : ATT.values()) free(i);
        ATT.clear();
        for (Object t : TYPY.values()) if (t instanceof BrTyp bt) bt.zwolnij();
        TYPY.clear();
        forIndex = null;
        BrCzastki.przeladuj();
        BrStany.wyczysc();
        BrMaska.clear();
        BrPaczki.przeladuj();
    }

    private static void free(Inst i) {
        for (int g = 0; g < i.handles.length; g++) {
            if (i.handles[g] != 0) BrNatywka.free(i.handles[g]);
            i.handles[g] = 0; // an attachable may still point at us as its holder
        }
    }

    // entity.playAnimation / playanimation from the server, onto every live geometry of that entity. a mob
    // whose actor is not up yet (the packet tends to come with the spawn) gets it when it is, if that is soon
    private record Zagranie(String anim, float blendOut, String stop, String ctrl, long frame) {}
    private static final Map<Integer, List<Zagranie>> CZEKA = new HashMap<>();

    public static void zagraj(int entity, String anim, float blendOut, String stop, String ctrl) {
        if (anim == null || anim.isBlank() || !BrNatywka.ready()) return;
        String st = stop == null || stop.isBlank() ? null : stop, ct = ctrl == null || ctrl.isBlank() ? null : ctrl;
        Inst inst = INSTS.get(entity);
        boolean any = false;
        if (inst != null) for (long h : inst.handles) if (h != 0) any |= BrNatywka.play(h, anim, blendOut, st, ct);
        if (any || inst != null && inst.handles.length > 0 && inst.handles[0] != 0) return;
        if (CZEKA.size() > 256) CZEKA.clear();
        CZEKA.computeIfAbsent(entity, k -> new java.util.ArrayList<>()).add(new Zagranie(anim, blendOut, st, ct, frame));
    }

    private static BrTyp typ(BrPaczki.Indeks idx, String id) {
        if (forIndex != idx) {
            // the index got rebuilt under us, drop everything built from the old one
            for (Inst i : INSTS.values()) free(i);
            INSTS.clear();
            for (Inst i : ATT.values()) free(i);
            ATT.clear();
            for (Object t : TYPY.values()) if (t instanceof BrTyp bt) bt.zwolnij();
            TYPY.clear();
            forIndex = idx;
        }
        BrPaczki.Paczka p = idx.entityOwner.get(id);
        if (p == null) return null;
        return built(idx, id, id, () -> BrTyp.zbuduj(idx, p, id, BrTyp.merged(idx, p, id)));
    }

    // off = every mob draws its java self. omni flips it to shoot the same mob both ways side by side
    public static volatile boolean wylaczone;

    // how big java should think the mob is when deciding to draw it at all (hitbox size * 64 = distance).
    // packs give ui holograms and hidden bosses a 0 hitbox, java then draws them never, not even point blank.
    // -1 = not ours, java decides
    public static double drawSize(Entity e) {
        if (wylaczone) return -1;
        BrPaczki.Indeks idx = BrPaczki.indeks();
        if (idx == null || idx.entityOwner.isEmpty()) return -1;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        if (!idx.entityOwner.containsKey(id)) return -1;
        BrTyp typ = typ(idx, id);
        if (typ == null) return -1;
        return typ.visibleBounds > 0 ? typ.visibleBounds : 1;
    }

    public static BrKlatka extract(Entity e, float pt) {
        if (wylaczone) return null;
        BrPaczki.Indeks idx = BrPaczki.indeks();
        if (idx == null || idx.entityOwner.isEmpty() || !BrNatywka.ready()) return null;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        if (!idx.entityOwner.containsKey(id)) return null;
        BrTyp typ = typ(idx, id);
        if (typ == null) return null;

        int klucz = wUi ? ~e.getId() : e.getId();
        Inst inst = INSTS.get(klucz);
        if (inst == null || inst.typ != typ) {
            if (inst != null) free(inst);
            inst = new Inst(typ);
            INSTS.put(klucz, inst);
        }
        inst.seenFrame = frame;
        inst.firstPerson = false;
        inst.stan.ui = wUi;
        inst.stan.fp = false;

        BrPytania.Stan s = inst.stan;
        s.e = e;
        s.le = e instanceof LivingEntity le ? le : null;
        s.pt = pt;
        s.lifeTime = (e.tickCount + pt) / 20.0;
        float yaw = s.le != null ? Mth.rotLerp(pt, s.le.yBodyRotO, s.le.yBodyRot) : e.getYRot(pt);
        float dt = inst.lastLife < 0 ? 0f : (float) Math.max(0, Math.min(0.25, s.lifeTime - inst.lastLife));
        // degrees per second. it was per frame * 20, at 144 fps every turn looked like a whip
        if (dt > 0) s.yawSpeed = Mth.wrapDegrees(yaw - s.lastYaw) / dt;
        s.lastYaw = yaw;
        inst.lastLife = s.lifeTime;

        BrKlatka k = tick(inst, inst.geo, dt);
        if (k == null) return null;
        // the render controller picked another geometry: run that one from now on. not for a
        // humanoid on the java model tho, its extra geometries (faces, capes) have no limbs and
        // switching to one froze the whole player
        // a player-style actor keeps java's own body as its base, always: another geometry as the base drew a
        // second player (rlcraft + a&s picked geometry.shield once bedrock's vanilla base made it resolve) and in
        // first person put the camera inside it. its other geometries are layers (warstwy), nothing else
        int want = typ.onVanillaModel ? inst.geo : typ.geo(k.info[1]);
        if (want >= 0 && want != inst.geo) {
            inst.geo = want;
            BrKlatka other = tick(inst, want, dt);
            if (other != null) k = other;
        }

        BrTyp.Geo g = typ.geos.get(inst.geo);
        k.geo = g;
        k.onVanillaModel = typ.onVanillaModel || g.model == null;
        k.vanillaRuch = typ.vanillaRuch;
        k.vanillaGlowa = typ.vanillaGlowa;
        int tex = k.info[0];
        k.texture = tex >= 0 && tex < typ.textures.size() ? typ.textures.get(tex) : typ.textures.isEmpty() ? null : typ.textures.get(0);
        k.translucent = k.geo.jeden == BrFarby.Tryb.BLEND;
        k.bodyYaw = s.le != null ? Mth.rotLerp(pt, s.le.yBodyRotO, s.le.yBodyRot) : Mth.rotLerp(pt, e.yRotO, e.getYRot());
        k.sx = Float.intBitsToFloat(k.info[2]);
        k.sy = Float.intBitsToFloat(k.info[6]);
        k.sz = Float.intBitsToFloat(k.info[7]);
        k.hurt = s.le != null && (s.le.hurtTime > 0 || s.le.deathTime > 0);
        k.maskColor = kolorek(e);
        // part_visibility: a hidden bone gets a zero matrix, its cubes collapse to nothing
        if (!k.onVanillaModel) for (int b = 0; b < k.bones; b++) mirror(k.mats, b * 16);
        k.surowe = k.mats.clone();
        for (int b = 0; b < k.bones; b++) if (k.vis[b] == 0) java.util.Arrays.fill(k.mats, b * 16, b * 16 + 16, 0f);
        warstwy(inst, k, dt);
        effects(k, g, e);
        if (System.getenv("KOPER_BR_DEBUG") != null && e.tickCount > 20 && DEBUG_RAZ.add("mob|" + typ.id)) {
            int widac = 0;
            for (int b = 0; b < k.bones; b++) if (k.vis[b] != 0) widac++;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kodel/Bedrock] mob {} geo {} ({}/{}) model {} vanilla {} tex {} visible bones {}/{} layers {} paint {} scale {},{},{}",
                typ.id, g.key, inst.geo, typ.geos.size(), g.model != null, k.onVanillaModel, k.texture, widac, k.bones, k.warstwy.size(),
                k.malowanie == null ? "-" : k.malowanie.grupy().keySet(), k.sx, k.sy, k.sz);
        }
        withSkin(k, e);
        return k;
    }

    private static final int[] LAY = new int[48];

    // every render controller after the one that picked the main geometry is its own draw in bedrock:
    // golem cracks on top of the golem, a cow's face, the player's extra bits
    private static final byte[] WIDAC = new byte[512];

    private static void warstwy(Inst inst, BrKlatka k, float dt) {
        BrTyp typ = inst.typ;
        k.warstwy.clear();
        k.malowanie = null;
        int n = BrNatywka.layers(inst.handles[inst.geo], LAY);
        for (int i = 0; i < n; i++) {
            int rc = LAY[i * 3], tx = LAY[i * 3 + 1];
            int gi = LAY[i * 3 + 2] < 0 ? inst.geo : typ.geo(LAY[i * 3 + 2]);
            if (gi < 0) continue;
            BrTyp.Geo gg = typ.geos.get(gi);
            if (k.malowanie == null && gi == inst.geo) {
                k.malowanie = typ.malowanie(gg, rc, inst.handles[gi]);
                nakladki(inst, k, gg, k.mats, rc);
                continue;
            }
            if (gg.model == null) continue;
            BrKlatka kk = k;
            float[] zrodlo;
            if (gi != inst.geo) {
                kk = tick(inst, gi, dt);
                if (kk == null) continue;
                for (int b = 0; b < kk.bones; b++) mirror(kk.mats, b * 16);
                zrodlo = kk.mats;
            } else {
                zrodlo = k.surowe != null ? k.surowe : k.mats;
            }
            // this controller's own part_visibility and nothing else (the villager's level badge hides
            // everything for itself when it has no job, the villager stays). the base controller's must
            // not leak in: A&S puts an all hidden helper first and the creeper, zombie, skeleton vanished
            float[] mats = zrodlo.clone();
            int nb = BrNatywka.layerVis(inst.handles[gi], rc, WIDAC);
            for (int b = 0; b < nb && b < kk.bones; b++)
                if (WIDAC[b] == 0) java.util.Arrays.fill(mats, b * 16, b * 16 + 16, 0f);
            Identifier tex = tx >= 0 && tx < typ.textures.size() ? typ.textures.get(tx) : k.texture;
            if (tex != null) k.warstwy.add(new BrKlatka.Warstwa(gg, mats, tex, typ.malowanie(gg, rc, inst.handles[gi]), gi == inst.geo));
            nakladki(inst, k, gg, mats, rc);
        }
        if (k.malowanie == null) k.malowanie = new BrTyp.Malowanie(k.geo.jeden, k.geo.grupy);
    }

    private static final int[] TEKSTURY = new int[16];

    // "textures": [base, markings, armor]: bedrock's multitexture materials lay the later ones over the
    // first, each where it has pixels. drawn here as cutout passes on the same pose. the horse's armor
    // and markings, a villager's biome and job, were all simply missing
    private static void nakladki(Inst inst, BrKlatka k, BrTyp.Geo gg, float[] mats, int rc) {
        if (gg.model == null) return;
        int gi = inst.typ.geos.indexOf(gg);
        // only a material that samples more than one texture uses the later ones. A&S lists its enchant
        // glints there for its single texture armor and swords: drawn as layers they were opaque black
        // and lilac streaks. a llama's carpet, a horse's markings, a villager's job are real layers
        if (!inst.typ.wieloTekstura(rc, inst.handles[gi < 0 ? inst.geo : gi])) return;
        int n = Math.min(BrNatywka.rcTextures(inst.handles[inst.geo], rc, TEKSTURY), TEKSTURY.length);
        for (int t = 1; t < n; t++) {
            int tx = TEKSTURY[t];
            if (tx < 0 || tx >= inst.typ.textures.size()) continue;
            k.warstwy.add(new BrKlatka.Warstwa(gg, mats, inst.typ.textures.get(tx), gg.nakladka(), gg == inst.typ.geos.get(inst.geo)));
        }
    }

    // an attachable's render controller picks its geometry like a mob's does. without following it
    // they all drew whatever geometry the file listed first: A&S's mace drew its 20 block outline
    // helper, the whole item atlas in a box around the player
    private static BrKlatka przelacz(Inst inst, BrKlatka k, float dt) {
        if (k == null) return null;
        int want = inst.typ.geo(k.info[1]);
        if (want < 0 || want == inst.geo) return k;
        inst.geo = want;
        BrKlatka other = tick(inst, want, dt);
        return other != null ? other : k;
    }

    private static BrKlatka tick(Inst inst, int gi, float dt) {
        BrTyp.Geo g = inst.typ.geos.get(gi);
        if (inst.handles[gi] == 0) {
            inst.handles[gi] = BrNatywka.spawn(g.def, inst.stan.e.getId() * 31 + 7);
            if (inst.handles[gi] == 0) return null;
            List<Zagranie> czeka = CZEKA.remove(inst.stan.e.getId());
            if (czeka != null) for (Zagranie z : czeka)
                if (frame - z.frame() < 200) BrNatywka.play(inst.handles[gi], z.anim(), z.blendOut(), z.stop(), z.ctrl());
        }
        BrKlatka k = inst.frames[gi];
        if (k == null) k = inst.frames[gi] = new BrKlatka(g.bones.length);
        for (int i = 0; i < g.queries.length; i++) {
            BrPytania.P p = g.queries[i];
            String str;
            try {
                str = p.str(inst.stan);
                g.qScratch[i] = str == null ? p.num(inst.stan) : 0f;
            } catch (RuntimeException oddEntity) {
                // a query hit something this mob doesn't have, bedrock answers 0 there too
                str = null;
                g.qScratch[i] = 0f;
            }
            g.qstrScratch[i] = str == null ? -1 : g.strings.getOrDefault(str, -2);
        }
        for (int i = 0; i < g.contexts.size(); i++) {
            // layers never draw first person, the hand renderer is its own thing
            String c = g.contexts.get(i);
            g.cScratch[i] = switch (c) {
                case "is_first_person" -> inst.firstPerson ? 1f : 0f;
                default -> 0f;
            };
            g.cstrScratch[i] = c.equals("item_slot") && inst.slot != null ? g.strings.getOrDefault(inst.slot, -2) : -1;
        }
        for (int i = 0; i < g.silnikIds.length; i++) g.silnikVals[i] = silnik(inst, g.silnikCo[i]);
        BrNatywka.vars(inst.handles[gi], g.silnikIds, g.silnikVals, g.silnikIds.length);
        Inst h = inst.holder;
        if (h != null) BrNatywka.owner(inst.handles[gi], h.handles[h.geo]);
        boolean ok = BrNatywka.tick(inst.handles[gi], g.qScratch, g.qstrScratch, g.cScratch, g.cstrScratch, dt, k, inst.typ.onVanillaModel || g.model == null);
        // lands on the holder's next tick, a frame late
        if (ok && h != null) BrNatywka.parentSetup(inst.handles[gi], h.handles[h.geo]);
        return ok ? k : null;
    }

    // sound effects named in animations and controllers. the name maps to a sound through the
    // client entity's sound_effects table, an unknown name is taken as the sound id itself
    private static void effects(BrKlatka k, BrTyp.Geo g, Entity e) {
        int n = Math.min(k.info[3], k.events.length);
        for (int i = 0; i < n; i++) {
            int ev = k.events[i];
            if (ev < 0 || ev >= g.events.size()) continue;
            String name = g.events.get(ev);
            if (name.startsWith("particle:")) {
                String body = name.substring(9);
                int at = body.indexOf('@');
                String shortName = at < 0 ? body : body.substring(0, at);
                String locator = at < 0 ? "" : body.substring(at + 1);
                String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                BrPaczki.Paczka pk = BrPaczki.indeks() == null ? null : BrPaczki.indeks().entityOwner.get(typeId);
                JsonObject fxTable = pk == null ? null : BrPaczki.obj(pk.entities.get(typeId), "particle_effects");
                String effect = fxTable != null && fxTable.has(shortName) ? fxTable.get(shortName).getAsString() : shortName;
                Vec3 pos = locatorWorld(e, locator);
                BrCzastki.spawn(effect, pos.x, pos.y, pos.z, e.getId(), locator);
                continue;
            }
            if (!name.startsWith("sound:")) continue;
            String snd = name.substring(6);
            BrPaczki.Paczka p = BrPaczki.indeks() == null ? null : BrPaczki.indeks().entityOwner.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            JsonObject table = p == null ? null : BrPaczki.obj(p.entities.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()), "sound_effects");
            // both forms bedrock takes: "step": {"effect": "mob.x.step"} and "step": "mob.x.step" (mowzie's writes
            // the short one, every ferrous and grottol sound played as mow:whoosh_sound, unknown)
            JsonElement raw = table == null ? null : table.get(snd);
            String event = raw == null ? snd
                : raw.isJsonPrimitive() ? raw.getAsString()
                : raw.isJsonObject() && raw.getAsJsonObject().has("effect") ? raw.getAsJsonObject().get("effect").getAsString() : snd;
            // pack sounds live under the pack with odd characters made '_' (the converter's rule), ':' included
            Identifier sid = p == null ? Identifier.tryParse(event.contains(":") ? event : "minecraft:" + event)
                : Identifier.tryParse(p.ns + ":" + event.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_"));
            if (sid == null) continue;
            e.level().playLocalSound(e.getX(), e.getY(), e.getZ(), SoundEvent.createVariableRangeEvent(sid), SoundSource.NEUTRAL, 1f, 1f, false);
        }
    }

    // bedrock space -> render space for one bone matrix: M' = S M S with S = diag(-1, 1, 1).
    // the mesh is already mirrored (KodelBedrock.toRenderSpace), so winding stays right
    // and kender can keep culling back faces
    static void mirrorForTest(float[] m, int o) { mirror(m, o); }

    // a mirrored copy per draw: the draw runs later and the same frame can be drawn twice
    private static float[] lustro(float[] src, int bones) {
        float[] m = src.clone();
        for (int b = 0; b < bones; b++) mirror(m, b * 16);
        return m;
    }

    private static void mirror(float[] m, int o) {
        m[o + 1] = -m[o + 1];
        m[o + 2] = -m[o + 2];
        m[o + 3] = -m[o + 3];
        m[o + 4] = -m[o + 4];
        m[o + 8] = -m[o + 8];
        m[o + 12] = -m[o + 12];
    }


    // ── audit: what omni asks to find the mobs and items that draw nothing or draw without a texture ──

    public static List<String> audytMoby() {
        BrPaczki.Indeks idx = BrPaczki.indeks();
        if (idx == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        idx.entityOwner.forEach((id, p) -> { if (!p.baza) out.add(id); });
        return out;
    }

    public static List<String> audytItemy() {
        BrPaczki.Indeks idx = BrPaczki.indeks();
        return idx == null ? List.of() : new java.util.ArrayList<>(idx.byItem.keySet());
    }

    // "ok ..." or "BAD ..." for the entity as it was drawn last frame
    public static String audyt(Entity e) {
        Inst inst = INSTS.get(e.getId());
        if (inst == null) return "BAD never drawn through its bedrock definition";
        return opisz(inst);
    }

    // the attachable in the main hand of e, as drawn last frame
    public static String audytReki(Entity e) {
        Inst inst = ATT.get(((long) e.getId() << 3) | 0);
        if (inst == null && e instanceof LivingEntity le) {
            BrPaczki.Indeks idx = BrPaczki.indeks();
            var list = idx == null ? null : idx.byItem.get(BuiltInRegistries.ITEM.getKey(le.getMainHandItem().getItem()).toString());
            String holder = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            boolean fits = false;
            if (list != null) for (BrPaczki.Przyczepa c : list) fits |= holderFits(c.condition(), holder);
            if (!fits) return "ok only for other holders (armor stand and such)";
        }
        if (inst == null) return "BAD no attachable drawn for the main hand";
        return opisz(inst);
    }

    private static String opisz(Inst inst) {
        BrKlatka k = inst.frames[inst.geo];
        if (k == null) return "BAD no frame";
        BrTyp.Geo g = inst.typ.geos.get(inst.geo);
        int widac = widoczne(k.mats, k.bones);
        int warstwyWidac = 0;
        StringBuilder brak = new StringBuilder();
        var rm = Minecraft.getInstance().getResourceManager();
        if (k.texture == null) brak.append(" main texture none");
        else if (rm.getResource(k.texture).isEmpty()) brak.append(" missing ").append(k.texture);
        for (BrKlatka.Warstwa w : k.warstwy) {
            if (w.geo().model != null) warstwyWidac += widoczne(w.mats(), w.geo().bones.length);
            if (w.tex() != null && rm.getResource(w.tex()).isEmpty()) brak.append(" missing ").append(w.tex());
        }
        String o = "geo " + g.key + (g.model == null ? " (java model)" : "") + " bones shown " + widac + "/" + k.bones
            + " + layers " + k.warstwy.size() + " showing " + warstwyWidac + " tex " + k.texture + brak;
        boolean pusty = (g.model != null || !k.onVanillaModel) && widac == 0 && warstwyWidac == 0;
        return (pusty || brak.length() > 0 ? "BAD " : "ok ") + o;
    }

    private static int widoczne(float[] mats, int bones) {
        int n = 0;
        for (int b = 0; b < bones && (b + 1) * 16 <= mats.length; b++) {
            boolean any = false;
            for (int i = 0; i < 16 && !any; i++) any = mats[b * 16 + i] != 0f;
            if (any) n++;
        }
        return n;
    }

    // q.bone_origin: the bone's resting pivot in its own geometry, bedrock pixels
    static float[] boneOrigin(Entity e, String bone) {
        Inst inst = e == null ? null : INSTS.get(e.getId());
        if (inst != null && !inst.typ.geos.isEmpty()) {
            BrTyp.Geo g = inst.typ.geos.get(inst.geo);
            int b = kosc(g, bone);
            if (b >= 0 && g.model != null) return g.model.bones.get(b).pivot.clone();
        }
        return BrTyp.pivotOf(bone);
    }

    // q.bone_orientation_trs: what the animations did to the bone last frame, in its parent's space.
    // t = moved by (pixels), r = euler degrees, s = scale. 9 floats t r s, null = no such bone
    static float[] boneTrs(Entity e, String bone) {
        Inst inst = e == null ? null : INSTS.get(e.getId());
        if (inst == null || inst.typ.geos.isEmpty()) return null;
        BrTyp.Geo g = inst.typ.geos.get(inst.geo);
        BrKlatka k = inst.frames[inst.geo];
        int b = kosc(g, bone);
        if (b < 0 || k == null || k.mats == null || k.mats.length < (b + 1) * 16) return null;
        int par = -1;
        float[] piv;
        if (g.model != null) {
            par = g.model.bones.get(b).parent;
            piv = g.model.bones.get(b).pivot;
        } else {
            piv = BrTyp.pivotOf(g.bones[b]);
            for (String[] h : BrTyp.HUMANOID)
                if (h[0].equalsIgnoreCase(g.bones[b]) && !h[1].isEmpty()) par = kosc(g, h[1]);
        }
        if (piv == null) piv = new float[3];
        org.joml.Matrix4f w = new org.joml.Matrix4f().set(k.mats, b * 16);
        org.joml.Matrix4f local = par >= 0 ? new org.joml.Matrix4f().set(k.mats, par * 16).invert().mul(w) : w;
        org.joml.Vector3f at = local.transformPosition(new org.joml.Vector3f(piv[0], piv[1], piv[2]));
        org.joml.Vector3f rot = new org.joml.Vector3f(), scl = new org.joml.Vector3f();
        local.getScale(scl);
        local.normalize3x3(new org.joml.Matrix4f()).getEulerAnglesZYX(rot);
        return new float[] {at.x - piv[0], at.y - piv[1], at.z - piv[2],
            rot.x * Mth.RAD_TO_DEG, rot.y * Mth.RAD_TO_DEG, rot.z * Mth.RAD_TO_DEG, scl.x, scl.y, scl.z};
    }

    // pivot of the group's top bone: the one whose parent is not in the group (the bone that carries the binding)
    private static float[] groupRootPivot(BrTyp.Geo g, java.util.Set<String> group) {
        if (g.model == null) return null;
        var bones = g.model.bones;
        for (var b : bones) {
            if (!group.contains(b.name)) continue;
            if (b.parent < 0 || b.parent >= bones.size() || !group.contains(bones.get(b.parent).name)) return b.pivot;
        }
        return null;
    }

    private static float[] pivotKosci(BrTyp.Geo g, String bone) {
        if (g.model == null) return null;
        for (var b : g.model.bones) if (b.name.equalsIgnoreCase(bone)) return b.pivot;
        return null;
    }

    private static int kosc(BrTyp.Geo g, String bone) {
        if (bone == null) return -1;
        for (int i = 0; i < g.bones.length; i++) if (g.bones[i].equalsIgnoreCase(bone)) return i;
        return -1;
    }

    // q.get_root_locator_offset: where the locator sits from its own bone's pivot, not animated. the
    // binding already put the helmet on the head pivot, this only nudges it for an unusual head
    static float[] rootLocator(Entity e, String name) {
        if (e == null || name == null) return null;
        name = name.toLowerCase(java.util.Locale.ROOT);
        Inst inst = INSTS.get(e.getId());
        if (inst != null && !inst.typ.geos.isEmpty()) {
            BrTyp.Geo g = inst.typ.geos.get(inst.geo);
            float[] loc = g.locators.get(name);
            if (loc != null) {
                float[] piv = g.model != null && (int) loc[0] < g.model.bones.size() ? g.model.bones.get((int) loc[0]).pivot : new float[3];
                return new float[] {loc[1] - piv[0], loc[2] - piv[1], loc[3] - piv[2]};
            }
        }
        // no such locator (java's own model has none): 0, the helmet sits right on the head pivot
        return null;
    }

    // where a geometry locator is in the world right now. falls back to the entity's feet
    public static Vec3 locatorWorld(Entity e, String locator) {
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 base = e.getPosition(pt);
        Inst inst = INSTS.get(e.getId());
        if (inst == null || locator == null || locator.isEmpty()) return base;
        BrTyp.Geo g = inst.typ.geos.get(inst.geo);
        float[] loc = g.locators.get(locator.toLowerCase(java.util.Locale.ROOT));
        BrKlatka k = inst.frames[inst.geo];
        if (loc == null || k == null) return base;
        int b = (int) loc[0];
        int o = b * 16;
        // matrices are in render space already, the locator goes there too
        float lx = -loc[1], ly = loc[2], lz = loc[3];
        float[] m = k.mats;
        // column major bone matrix times the locator, still bedrock pixels in model space
        float mx = m[o] * lx + m[o + 4] * ly + m[o + 8] * lz + m[o + 12];
        float my = m[o + 1] * lx + m[o + 5] * ly + m[o + 9] * lz + m[o + 13];
        float mz = m[o + 2] * lx + m[o + 6] * ly + m[o + 10] * lz + m[o + 14];
        // same placement the draw uses: mirror x, scale, turn by 180 - body yaw, pixels to blocks
        mx = mx * k.sx / 16f;
        my = my * k.sy / 16f;
        mz = mz * k.sz / 16f;
        double yaw = Math.toRadians(180f - k.bodyYaw);
        double c = Math.cos(yaw), s = Math.sin(yaw);
        double wx = mx * c + mz * s;
        double wz = -mx * s + mz * c;
        return base.add(wx, my, wz);
    }

    // once per frame from the dispatcher: instances nobody drew for a few seconds get freed
    // KOPER_ZRZUT=<entity type>: every 200 frames the nearest such actor's queries, vars, bones and layers go to the log
    private static final String ZRZUT = System.getenv("KOPER_ZRZUT");
    // KOPER_ZRZUT_EVERY=30: dump more often than every 200 frames (the frees below ride the same beat, harmless)
    private static final int ZRZUT_EVERY = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("KOPER_ZRZUT_EVERY", "200")));

    public static void frame() {
        frame++;
        if (frame % ZRZUT_EVERY != 0) return;
        if (ZRZUT != null) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                Entity best = null;
                for (Entity e : mc.level.entitiesForRendering())
                    if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(ZRZUT)
                        && (best == null || e.distanceToSqr(mc.player) < best.distanceToSqr(mc.player))) best = e;
                if (best != null) {
                    Inst in = INSTS.get(best.getId());
                    StringBuilder w = new StringBuilder();
                    if (in != null && in.frames[in.geo] != null) {
                        BrKlatka kk = in.frames[in.geo];
                        w.append(" texture=").append(kk.texture).append(" layers=");
                        for (var l : kk.warstwy) w.append(l.geo().key).append('=').append(l.tex()).append(l.wlasna() ? "(own)" : "").append(' ');
                    }
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Zrzut] {}{}{}", zrzut(best), w, zrzutPrzyczep(best));
                }
            }
        }
        ATT.values().removeIf(i -> {
            if (frame - i.seenFrame <= 400) return false;
            free(i);
            return true;
        });
        Iterator<Inst> it = INSTS.values().iterator();
        while (it.hasNext()) {
            Inst i = it.next();
            if (frame - i.seenFrame > 400) {
                free(i);
                it.remove();
            }
        }
    }

    public static void submit(BrKlatka k, PoseStack pose, SubmitNodeCollector tasks, CameraRenderState camera, int light) {
        if (k.geo == null || k.geo.model == null || k.texture == null) return;
        pose.pushPose();
        pose.rotate(Axis.YP.rotationDegrees(180f - k.bodyYaw));
        pose.scale(k.sx, k.sy, k.sz);
        pose.translate(0, 0.01f, 0);
        int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(k.hurt));
        BrTyp.Geo g = k.geo;
        BrTyp.Malowanie m = k.malowanie != null ? k.malowanie : new BrTyp.Malowanie(g.jeden, g.grupy);
        if (m.jeden() != null) {
            if (m.jeden() != BrFarby.Tryb.HIDDEN)
                farbuj(m.jeden(), k.texture, k.tint, k.maskColor, (tex, rt, tint, glow) ->
                    KodelEntityRender.submitPosed(g.renderModel, g.meshKey, k.mats, tex, rt, pose, tasks, camera, glow ? JASNO : light, overlay, tint));
        } else {
            rysuj(g, k.mats, k.texture, m, pose, tasks, light, overlay, k.tint, k.maskColor);
        }
        for (BrKlatka.Warstwa w : k.warstwy) rysuj(w.geo(), w.mats(), w.tex(), w.m(), pose, tasks, light, overlay, k.tint, k.maskColor);
        pose.popPose();
    }

    // layers only, for mobs that stay on the java model (the player): the java model is already drawn
    public static void submitWarstwy(BrKlatka k, PoseStack pose, SubmitNodeCollector tasks, int light) {
        if (k.warstwy.isEmpty()) return;
        pose.pushPose();
        pose.rotate(Axis.YP.rotationDegrees(180f - k.bodyYaw));
        pose.scale(k.sx, k.sy, k.sz);
        int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(k.hurt));
        for (BrKlatka.Warstwa w : k.warstwy) rysuj(w.geo(), w.mats(), w.tex(), w.m(), pose, tasks, light, overlay, k.tint, k.maskColor);
        pose.popPose();
    }

    // bedrock hands change_color the mob's color component, java keeps it per mob
    static int kolorek(Entity e) {
        if (e instanceof net.minecraft.world.entity.animal.sheep.Sheep sh) return 0xFF000000 | sh.getColor().getTextureDiffuseColor();
        return 0xFFFFFFFF;
    }

    // leather and the like: the dye, undyed leather is leather brown on both editions
    static int farbka(net.minecraft.world.item.ItemStack stack) {
        var dyed = stack.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
        if (dyed != null) return 0xFF000000 | dyed.rgb();
        return stack.is(net.minecraft.tags.ItemTags.CAULDRON_CAN_REMOVE_DYE) ? 0xFFA06540 : 0xFFFFFFFF;
    }

    // BrPodglad: the held items of this mob, their bones
    static String zrzutPrzyczep(Entity e) {
        StringBuilder sb = new StringBuilder();
        for (var en : ATT.entrySet()) {
            Inst i = en.getValue();
            if (i.stan.e != e || frame - i.seenFrame > 2) continue;
            BrTyp.Geo g = i.typ.geos.get(i.geo);
            BrKlatka k = i.frames[i.geo];
            sb.append("\n att ").append(i.typ.id).append(" slot=").append(i.slot).append(" fp=").append(i.firstPerson);
            var qn = BrNatywka.describe(g.def).getAsJsonArray("queries");
            for (int q = 0; q < qn.size() && q < g.qScratch.length; q++) {
                String n = qn.get(q).getAsString();
                if (n.length() > 70) n = n.substring(0, 70) + "..";
                sb.append("\n   q.").append(n).append(" = ").append(g.qstrScratch[q] >= 0 ? "'" + g.qstrScratch[q] + "'" : g.qScratch[q]);
            }
            var vn = BrNatywka.describe(g.def).getAsJsonArray("vars");
            sb.append("\n   vars:");
            for (int q = 0; q < vn.size(); q++) {
                float v = BrNatywka.varGet(i.handles[i.geo], q);
                if (!Float.isNaN(v) && v != 0f) sb.append(' ').append(vn.get(q).getAsString()).append('=').append(v);
            }
            if (k != null) for (int b = 0; b < g.bones.length; b++)
                sb.append(String.format("\n   %s bind=%s vis=%d rot=[%.1f %.1f %.1f] pos=[%.1f %.1f %.1f]", g.bones[b],
                    g.binding == null ? null : g.binding[b], k.vis[b], k.local[b * 9], k.local[b * 9 + 1], k.local[b * 9 + 2],
                    k.local[b * 9 + 3], k.local[b * 9 + 4], k.local[b * 9 + 5]));
        }
        return sb.toString();
    }

    // BrPodglad: what the last tick of this mob read and what its limbs came out as
    static String zrzut(Entity e) {
        Inst inst = e == null ? null : INSTS.get(e.getId());
        if (inst == null) return "no actor";
        BrTyp.Geo g = inst.typ.geos.get(inst.geo);
        var names = BrNatywka.describe(g.def).getAsJsonArray("queries");
        StringBuilder sb = new StringBuilder(inst.typ.id).append(" geo ").append(g.key).append(" firstPerson=").append(inst.firstPerson);
        BrPaczki.Indeks ix = BrPaczki.indeks();
        BrPaczki.Paczka own = ix == null ? null : ix.entityOwner.get(inst.typ.id);
        sb.append(" owner=").append(own == null ? "?" : own.root).append(" geos=");
        for (BrTyp.Geo gg : inst.typ.geos) sb.append(gg.key).append(gg.model == null ? "(java)" : "").append(',');
        if (own != null) sb.append(" defGeometry=").append(own.entities.get(inst.typ.id) == null ? null : own.entities.get(inst.typ.id).get("geometry"));
        for (int i = 0; i < names.size() && i < g.qScratch.length; i++) {
            String n = names.get(i).getAsString();
            if (n.length() > 60) n = n.substring(0, 60) + "..";
            sb.append("\n  q.").append(n).append(" = ").append(g.qstrScratch[i] >= 0 ? "'" + g.qstrScratch[i] + "'" : g.qScratch[i]);
        }
        var vars = BrNatywka.describe(g.def).getAsJsonArray("vars");
        sb.append("\n  vars:");
        for (int i = 0; i < vars.size(); i++) {
            float v = BrNatywka.varGet(inst.handles[inst.geo], i);
            if (!Float.isNaN(v) && v != 0f) sb.append(' ').append(vars.get(i).getAsString()).append('=').append(v);
        }
        BrKlatka k = inst.frames[inst.geo];
        if (k != null) for (int b = 0; b < g.bones.length; b++)
            sb.append(String.format("\n  bone %s vis=%d rot=[%.1f %.1f %.1f] pos=[%.1f %.1f %.1f]", g.bones[b], k.vis[b],
                k.local[b * 9], k.local[b * 9 + 1], k.local[b * 9 + 2], k.local[b * 9 + 3], k.local[b * 9 + 4], k.local[b * 9 + 5]));
        return sb.toString();
    }

    interface Rysunek { void go(Identifier tex, net.minecraft.client.renderer.rendertype.RenderType rt, int tint, boolean glow); }

    // mask and glow materials are two draws: the plain pixels as they are, the high alpha ones
    // times the mob's color (mask) or full bright (glow)
    static void farbuj(BrFarby.Tryb t, Identifier tex, int tint, int maskColor, Rysunek r) {
        if (t.dzielony()) {
            BrMaska.Para p = BrMaska.para(tex, t.alfaTest());
            if (p != null) {
                boolean glow = t != BrFarby.Tryb.MASK && t != BrFarby.Tryb.MASK_SOLID;
                if (t != BrFarby.Tryb.GLOW_ONLY) r.go(p.plain(), BrFarby.typ(BrFarby.Tryb.CUTOUT, p.plain()), tint, false);
                r.go(p.tinted(), BrFarby.typ(BrFarby.Tryb.CUTOUT, p.tinted()), glow ? tint : razy(tint, maskColor), glow);
                return;
            }
        }
        r.go(tex, BrFarby.typ(t, tex), tint, false);
    }

    private static final int JASNO = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;

    static int razy(int a, int b) {
        int out = 0;
        for (int sh = 0; sh < 32; sh += 8) out |= ((((a >>> sh) & 255) * ((b >>> sh) & 255) / 255) << sh);
        return out;
    }

    // one draw per material, each bakes only the bones that wear it
    private static void rysuj(BrTyp.Geo g, float[] mats, Identifier tex, BrTyp.Malowanie m, PoseStack pose,
                              SubmitNodeCollector tasks, int light, int overlay, int tint, int maskColor) {
        for (var e : m.grupy().entrySet()) {
            if (e.getKey() == BrFarby.Tryb.HIDDEN) continue;
            java.util.Set<String> only = m.grupy().size() == 1 ? null : e.getValue();
            farbuj(e.getKey(), tex, tint, maskColor, (t, rt, c, glow) -> tasks.submitCustomGeometry(pose, rt, (snapshot, consumer) ->
                com.koper.koper_lib.kodel.KodelModelRender.render(g.renderModel, mats, snapshot.pose(), only, consumer, glow ? JASNO : light, overlay, c)));
        }
    }

    // ── attachables ──────────────────────────────────────────────────────────

    private static final Map<Long, Inst> ATT = new HashMap<>();
    private static final String[] SLOT_NAMES = {"main_hand", "off_hand", "head", "chest", "legs", "feet"};
    private static final net.minecraft.world.entity.EquipmentSlot[] SLOTS = {
        net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.OFFHAND,
        net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
        net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};

    // bedrock's player textures (steve, alex) mean "this player's skin". java draws the real skin there,
    // a mob that names them gets java's steve. before this they were missing textures: a black and purple box
    // over rlcraft players' heads and, in first person, the inside of that box over the whole screen
    static final Identifier STEVE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    static Identifier skinOf(Entity e) {
        return e instanceof net.minecraft.client.player.AbstractClientPlayer p ? p.getSkin().body().texturePath() : STEVE;
    }

    static void withSkin(BrKlatka k, Entity owner) {
        if (k == null) return;
        if (BrTyp.PLAYER_SKIN.equals(k.texture)) k.texture = skinOf(owner);
        for (int i = 0; i < k.warstwy.size(); i++) {
            BrKlatka.Warstwa w = k.warstwy.get(i);
            if (BrTyp.PLAYER_SKIN.equals(w.tex())) k.warstwy.set(i, new BrKlatka.Warstwa(w.geo(), w.mats(), skinOf(owner), w.m(), w.wlasna()));
        }
    }

    public static BrKlatka[] attachables(Entity e, float pt) {
        BrPaczki.Indeks idx = BrPaczki.indeks();
        if (idx == null || idx.byItem.isEmpty() || !(e instanceof LivingEntity le) || !BrNatywka.ready()) return null;
        BrKlatka[] out = null;
        String holder = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        for (int slot = 0; slot < SLOTS.length; slot++) {
            var stack = le.getItemBySlot(SLOTS[slot]);
            if (stack.isEmpty()) continue;
            var list = idx.byItem.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (list == null) continue;
            BrPaczki.Przyczepa pick = null;
            for (BrPaczki.Przyczepa c : list) if (holderFits(c.condition(), holder)) { pick = c; break; }
            if (pick == null) continue;
            BrTyp typ = typAtt(idx, pick);
            if (typ == null) continue;
            long key = ((long) e.getId() << 3) | slot | (wUi ? 1L << 62 : 0);
            Inst inst = ATT.get(key);
            if (inst == null || inst.typ != typ) {
                if (inst != null) free(inst);
                inst = new Inst(typ);
                ATT.put(key, inst);
            }
            inst.seenFrame = frame;
            inst.slot = SLOT_NAMES[slot];
            inst.stack = stack;
            inst.holder = INSTS.get(wUi ? ~e.getId() : e.getId());
            inst.stan.ui = wUi;
            inst.stan.attached = true;
            BrPytania.Stan st = inst.stan;
            st.e = e;
            st.le = le;
            st.pt = pt;
            st.lifeTime = (e.tickCount + pt) / 20.0;
            float dt = inst.lastLife < 0 ? 0f : (float) Math.max(0, Math.min(0.25, st.lifeTime - inst.lastLife));
            inst.lastLife = st.lifeTime;
            BrKlatka k = przelacz(inst, tick(inst, inst.geo, dt), dt);
            if (k == null || typ.geos.get(inst.geo).model == null) continue;
            k.geo = typ.geos.get(inst.geo);
            int tex = k.info[0];
            k.texture = tex >= 0 && tex < typ.textures.size() ? typ.textures.get(tex) : typ.textures.isEmpty() ? null : typ.textures.get(0);
            k.translucent = k.geo.jeden == BrFarby.Tryb.BLEND;
            k.maskColor = farbka(stack);
            k.surowe = k.mats.clone();
            for (int b = 0; b < k.bones; b++) if (k.vis[b] == 0) java.util.Arrays.fill(k.mats, b * 16, b * 16 + 16, 0f);
            warstwy(inst, k, dt);
            withSkin(k, e);
            if (out == null) out = new BrKlatka[SLOTS.length];
            out[slot] = k;
        }
        return out;
    }

    // "query.is_owner_identifier_any('minecraft:player')" and friends. no condition = anybody
    private static boolean holderFits(String cond, String holder) {
        if (cond == null || cond.isBlank()) return true;
        String c = cond.toLowerCase(java.util.Locale.ROOT);
        int at = c.indexOf("is_owner_identifier_any(");
        if (at < 0) return true;
        int end = c.indexOf(')', at);
        String inside = c.substring(at + "is_owner_identifier_any(".length(), end < 0 ? c.length() : end);
        boolean hit = false;
        for (Object o : BrPytania.args(inside)) if (holder.equals(String.valueOf(o))) hit = true;
        boolean negated = at > 0 && c.charAt(at - 1) == '!' || c.startsWith("!q") || c.startsWith("!query");
        return negated != hit;
    }

    private static BrTyp typAtt(BrPaczki.Indeks idx, BrPaczki.Przyczepa a) {
        return built(idx, "att:" + a.id(), "attachable " + a.id(), () -> BrTyp.zbuduj(idx, a.pack(), a.id(), a.desc()));
    }

    public static BrKlatka forSlot(BrKlatka[] att, net.minecraft.world.entity.EquipmentSlot slot) {
        if (att == null) return null;
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) return att[i];
        return null;
    }

    // java part a binding name means. item bones ride the arm they hang from
    private static ModelPart part(HumanoidModel<?> m, String binding, boolean rightHand) {
        if (binding == null) return null;
        if (binding.startsWith("=")) binding = binding.substring(1);
        return switch (binding) {
            case "@slot" -> rightHand ? m.rightArm : m.leftArm;
            case "head", "hat", "helmet" -> m.head;
            case "body", "waist", "jacket", "chest" -> m.body;
            case "rightarm", "rightitem", "rightsleeve" -> m.rightArm;
            case "leftarm", "leftitem", "leftsleeve" -> m.leftArm;
            case "rightleg", "rightpants", "rightboot" -> m.rightLeg;
            case "leftleg", "leftpants", "leftboot" -> m.leftLeg;
            default -> null;
        };
    }

    // bones are drawn in groups, each group under the java model part its binding names. bedrock
    // pixel space -> java part space: move the part's rest pivot (in bedrock terms) to the origin, flip y
    private static final java.util.Set<String> DEBUG_RAZ = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void renderBound(BrKlatka k, HumanoidModel<?> m, boolean rightHand, PoseStack pose,
                                   SubmitNodeCollector tasks, int light) {
        renderBound(k, m, rightHand, pose, tasks, light, null);
    }

    // onlyPart: first person draws just the bones that hang off the arm it is drawing
    public static void renderBound(BrKlatka k, HumanoidModel<?> m, boolean rightHand, PoseStack pose,
                                   SubmitNodeCollector tasks, int light, ModelPart onlyPart) {
        if (k.geo == null || k.geo.model == null || k.texture == null) return;
        BrTyp.Malowanie mal = k.malowanie != null ? k.malowanie : new BrTyp.Malowanie(k.geo.jeden, k.geo.grupy);
        if (System.getenv("KOPER_BR_DEBUG") != null && DEBUG_RAZ.add(System.identityHashCode(k.geo) + "|" + k.texture))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kodel/Bedrock] bound {} tex {} paint {} mask {} layers {} bones {} binding {}", k.geo.key, k.texture,
                mal.grupy().keySet(), Integer.toHexString(k.maskColor), k.warstwy.size(), java.util.Arrays.toString(k.geo.bones),
                java.util.Arrays.toString(k.geo.binding) + " t0 " + (k.mats == null ? "-" : k.mats[12] + "," + k.mats[13] + "," + k.mats[14] + " s " + k.mats[0] + "," + k.mats[5] + "," + k.mats[10])
                + " vis " + java.util.Arrays.toString(k.vis) + " warstwy " + k.warstwy.stream().map(w -> w.geo().key + ":" + w.tex() + ":" + w.m().grupy().keySet()).toList());
        zwiazane(k.geo, lustro(k.mats, k.bones), k.texture, mal, k.maskColor, m, rightHand, pose, tasks, light, onlyPart);
        // every other render controller that drew: a torch's flame, an enchanted glint, a second geometry.
        // warstwy() already mirrored the matrices of other geometries, the main one's are shared
        for (BrKlatka.Warstwa w : k.warstwy)
            zwiazane(w.geo(), w.wlasna() ? lustro(w.mats(), k.bones) : w.mats(), w.tex(), w.m(), k.maskColor,
                m, rightHand, pose, tasks, light, onlyPart);
    }

    private static void zwiazane(BrTyp.Geo geo, float[] mats, Identifier tex, BrTyp.Malowanie mal, int maskColor,
                                 HumanoidModel<?> m, boolean rightHand, PoseStack pose, SubmitNodeCollector tasks,
                                 int light, ModelPart onlyPart) {
        if (geo.model == null || tex == null) return;
        Map<String, java.util.Set<String>> groups = new java.util.LinkedHashMap<>();
        String[] names = geo.bones;
        for (int b = 0; b < names.length; b++) {
            String bind = geo.binding == null ? null : geo.binding[b];
            groups.computeIfAbsent(bind == null ? "" : bind, x -> new java.util.HashSet<>()).add(names[b]);
        }
        for (var g : groups.entrySet()) {
            ModelPart p = part(m, g.getKey().isEmpty() ? null : g.getKey(), rightHand);
            if (onlyPart != null && p != onlyPart) continue;
            pose.pushPose();
            float px = 0, py = 24, pz = 0;
            if (p != null) {
                // a hidden bone keeps its place for whatever is bound to it: the pack hides the arm
                // in first person and the item still rides it. scale 0 took the item along
                float sx = p.xScale, sy = p.yScale, sz = p.zScale;
                if (sx == 0f && sy == 0f && sz == 0f) { p.xScale = 1f; p.yScale = 1f; p.zScale = 1f; }
                p.translateAndRotate(pose);
                p.xScale = sx; p.yScale = sy; p.zScale = sz;
                var rest = p.getInitialPose();
                px = rest.x();
                py = 24f - rest.y();
                pz = rest.z();
            }
            // bound bones sit relative to the pivot of the bone they bind to (item bones: rightItem).
            // a name matched one ("=body") lands with ITS pivot on the holder bone's pivot: A&S writes its
            // chestplate around 0,0,0 and it went to the feet. a pack that wrote it at 0,24,0 has the same
            // pivot as the holder, so for those nothing moves
            String bind = g.getKey();
            if (p != null && bind.startsWith("=")) {
                float[] own = pivotKosci(geo, bind.substring(1));
                if (own != null) { px = own[0]; py = own[1]; pz = own[2]; }
            } else if (p != null) {
                float[] t = BrTyp.pivotOf(bind.equals("@slot") ? (rightHand ? "rightItem" : "leftItem") : bind);
                if (t != null) { px -= t[0]; py -= t[1]; pz -= t[2]; }
                else { px = 0; py = 0; pz = 0; }
                // the bound bone's own pivot lands on the holder bone's pivot, not its 0,0,0. bedrock's trident
                // (and a&s's copy of it) pivots the pole at 0,24,0: it stuck out 24 px above the hand, over the head
                float[] c = groupRootPivot(geo, g.getValue());
                if (c != null) { px += c[0]; py += c[1]; pz += c[2]; }
            }
            // same space mobs draw in: the x mirrored render model and x mirrored bone matrices, with the
            // pivot mirrored too so everything lands where it did. the raw model under a lone y flip was
            // inside out (det -1): culling ate the outside faces, dirt missing sides, normals grey
            org.joml.Matrix4f base = new org.joml.Matrix4f().scale(-1f, -1f, 1f).translate(px / 16f, -py / 16f, -pz / 16f);
            var model = geo.renderModel;
            for (var farba : mal.grupy().entrySet()) {
                if (farba.getKey() == BrFarby.Tryb.HIDDEN) continue;
                java.util.Set<String> only = new java.util.HashSet<>(g.getValue());
                only.retainAll(farba.getValue());
                if (only.isEmpty()) continue;
                farbuj(farba.getKey(), tex, 0xFFFFFFFF, maskColor, (t, rt, c, glow) -> tasks.submitCustomGeometry(pose, rt, (snapshot, consumer) -> {
                    org.joml.Matrix4f full = new org.joml.Matrix4f(snapshot.pose()).mul(base);
                    com.koper.koper_lib.kodel.KodelModelRender.render(model, mats, full, only, consumer, glow ? JASNO : light, OverlayTexture.NO_OVERLAY, c);
                }));
            }
            pose.popPose();
        }
    }

    // ── first person ─────────────────────────────────────────────────────────

    // the local player is not extracted in first person, so its actor ticks from here once a frame
    private static long fpFrame = -1;
    private static BrKlatka fpPlayer;

    public static BrKlatka firstPersonPlayer(Entity player, float pt) {
        if (fpFrame == frame && fpPlayer != null) return fpPlayer;
        fpFrame = frame;
        fpPlayer = null;
        BrPaczki.Indeks idx = BrPaczki.indeks();
        if (idx == null || !BrNatywka.ready()) return null;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(player.getType()).toString();
        if (!idx.entityOwner.containsKey(id)) return null;
        BrTyp typ = typ(idx, id);
        if (typ == null) return null;
        Inst inst = INSTS.computeIfAbsent(player.getId(), x -> new Inst(typ));
        inst.seenFrame = frame;
        inst.firstPerson = true;
        BrPytania.Stan s = inst.stan;
        s.fp = true;
        s.e = player;
        s.le = player instanceof LivingEntity le ? le : null;
        s.pt = pt;
        s.lifeTime = (player.tickCount + pt) / 20.0;
        float dt = inst.lastLife < 0 ? 0f : (float) Math.max(0, Math.min(0.25, s.lifeTime - inst.lastLife));
        inst.lastLife = s.lifeTime;
        BrKlatka k = tick(inst, inst.geo, dt);
        if (k == null) return null;
        k.geo = typ.geos.get(inst.geo);
        k.onVanillaModel = typ.onVanillaModel || k.geo.model == null;
        fpPlayer = k;
        return k;
    }

    // an item's attachable for the first person hand, ticked with context.is_first_person = 1
    public static BrKlatka firstPersonAttachable(LivingEntity holder, boolean mainHand, float pt) {
        BrPaczki.Indeks idx = BrPaczki.indeks();
        if (idx == null || idx.byItem.isEmpty() || !BrNatywka.ready()) return null;
        var stack = holder.getItemBySlot(mainHand ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        if (stack.isEmpty()) return null;
        var list = idx.byItem.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (list == null) return null;
        String holderId = BuiltInRegistries.ENTITY_TYPE.getKey(holder.getType()).toString();
        BrPaczki.Przyczepa pick = null;
        for (BrPaczki.Przyczepa c : list) if (holderFits(c.condition(), holderId)) { pick = c; break; }
        if (pick == null) return null;
        BrTyp typ = typAtt(idx, pick);
        if (typ == null) return null;
        long key = ((long) holder.getId() << 3) | (mainHand ? 6 : 7);
        Inst inst = ATT.get(key);
        if (inst == null || inst.typ != typ) {
            if (inst != null) free(inst);
            inst = new Inst(typ);
            ATT.put(key, inst);
        }
        inst.seenFrame = frame;
        inst.firstPerson = true;
        inst.slot = mainHand ? "main_hand" : "off_hand";
        inst.stack = stack;
        inst.holder = INSTS.get(holder.getId());
        BrPytania.Stan st = inst.stan;
        st.attached = true;
        st.e = holder;
        st.le = holder;
        st.pt = pt;
        st.lifeTime = (holder.tickCount + pt) / 20.0;
        float dt = inst.lastLife < 0 ? 0f : (float) Math.max(0, Math.min(0.25, st.lifeTime - inst.lastLife));
        inst.lastLife = st.lifeTime;
        BrKlatka k = przelacz(inst, tick(inst, inst.geo, dt), dt);
        if (k == null || typ.geos.get(inst.geo).model == null) return null;
        k.geo = typ.geos.get(inst.geo);
        int tex = k.info[0];
        k.texture = tex >= 0 && tex < typ.textures.size() ? typ.textures.get(tex) : typ.textures.isEmpty() ? null : typ.textures.get(0);
        k.translucent = k.geo.jeden == BrFarby.Tryb.BLEND;
        k.maskColor = farbka(stack);
        k.surowe = k.mats.clone();
        for (int b = 0; b < k.bones; b++) if (k.vis[b] == 0) java.util.Arrays.fill(k.mats, b * 16, b * 16 + 16, 0f);
        warstwy(inst, k, dt);
        withSkin(k, holder);
        return k;
    }

    // bedrock arm offsets on one java arm part, first person
    public static void onArm(BrKlatka k, ModelPart arm, boolean right) {
        if (k == null || k.geo == null) return;
        String want = right ? "rightArm" : "leftArm";
        String[] names = k.geo.bones;
        for (int b = 0; b < names.length; b++) {
            if (!names[b].equals(want)) continue;
            int at = b * 9;
            // bedrock x/z turn the other way, the y flip into java space turns them back
            arm.xRot += k.local[at] * DEG;
            arm.yRot += k.local[at + 1] * DEG;
            arm.zRot += k.local[at + 2] * DEG;
            arm.x += k.local[at + 3];
            arm.y -= k.local[at + 4];
            arm.z += k.local[at + 5];
            arm.xScale *= k.local[at + 6];
            arm.yScale *= k.local[at + 7];
            arm.zScale *= k.local[at + 8];
            if (k.vis[b] == 0) { arm.xScale = 0f; arm.yScale = 0f; arm.zScale = 0f; }
        }
    }

    // first person the bedrock way. bedrock has no hand renderer: the player model stands in the
    // world with the camera in its eyes, first person animations (arm at [95,-45,115], [13.5,-10,12])
    // put the arms in view and the pack's first person render controller hides the rest. so the
    // model goes to camera space with the eyes at 0,0,0 and draws what the pack left visible
    public static void pierwszaOsoba(BrKlatka k, BrKlatka att, net.minecraft.client.model.player.PlayerModel m, Identifier skin,
                                     boolean right, float eye, float pitch, float yawRel, boolean warstwy,
                                     PoseStack pose, SubmitNodeCollector tasks, int light) {
        pose.pushPose();
        boolean debug = System.getenv("KOPER_FP_DEBUG") != null;
        if (debug) pose.translate(0f, 0f, -2.5f); // whole body pushed out in front of the camera, see where it stands
        // the hand pass is camera space, but bedrock draws the first person body in the world with the
        // camera in its head: the pack's arms follow the view by turning with q.target_x_rotation. without
        // the pitch here they turned twice, looking down spun the arms up past the face
        pose.rotate(Axis.XP.rotationDegrees(pitch));
        pose.translate(0f, -eye, 0f);
        // every other render controller: A&S draws its first person arms (sleeve of whatever chestplate
        // you wear) as its own geometry and hides java's. drawn like a mob, feet at 0, facing away
        if (warstwy && !k.warstwy.isEmpty()) {
            pose.pushPose();
            pose.rotate(Axis.YP.rotationDegrees(180f - yawRel));
            pose.scale(k.sx, k.sy, k.sz);
            for (BrKlatka.Warstwa w : k.warstwy) rysuj(w.geo(), w.mats(), w.tex(), w.m(), pose, tasks, light, OverlayTexture.NO_OVERLAY, k.tint, k.maskColor);
            pose.popPose();
        }
        // bedrock's first person model faces the camera: its first person arm poses point the arms
        // backwards (+x rotation) and across (+13.5 on the right arm), which only lands in view like this
        pose.rotate(Axis.YP.rotationDegrees(180f));
        // the same flip entity rendering does, the model's own space from here on
        pose.scale(-1f, -1f, 1f);
        pose.translate(0f, -1.501f, 0f);
        m.root().getAllParts().forEach(ModelPart::resetPose);
        onHumanoid(k, m);
        ModelPart arm = right ? m.rightArm : m.leftArm;
        if (debug) {
            for (ModelPart part : new ModelPart[] {m.body, m.rightLeg, m.leftLeg, right ? m.leftArm : m.rightArm}) {
                part.xScale = part.yScale = part.zScale = 1f;
                tasks.submitModelPart(part, pose, net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(skin), light,
                    OverlayTexture.NO_OVERLAY, null);
            }
        }
        if (arm.xScale != 0f || arm.yScale != 0f || arm.zScale != 0f)
            tasks.submitModelPart(arm, pose, net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(skin), light,
                OverlayTexture.NO_OVERLAY, null);
        if (att != null) renderBound(att, m, right, pose, tasks, light, arm);
        pose.popPose();
    }

    // what the first person hand pass wants drawn on the arm it is about to draw
    public record Reka(BrKlatka attachable, boolean right) {}

    public static final ThreadLocal<Reka> REKA = new ThreadLocal<>();

    private static final float DEG = Mth.DEG_TO_RAD;

    // player style packs: the geometry is bedrock's own humanoid, so the java model stays and gets
    // the bedrock offsets on top. java model space is bedrock's with y pointing down
    public static void onHumanoid(BrKlatka k, HumanoidModel<?> m) {
        if (k.geo == null) return;
        String[] names = k.geo.bones;
        org.joml.Matrix4f w = new org.joml.Matrix4f(), fin = new org.joml.Matrix4f();
        org.joml.Vector3f rot = new org.joml.Vector3f(), scl = new org.joml.Vector3f();
        for (int b = 0; b < names.length; b++) {
            ModelPart part = switch (names[b]) {
                case "head" -> m.head;
                case "hat" -> m.hat;
                case "body" -> m.body;
                case "rightArm" -> m.rightArm;
                case "leftArm" -> m.leftArm;
                case "rightLeg" -> m.rightLeg;
                case "leftLeg" -> m.leftLeg;
                default -> null;
            };
            if (part == null) continue;
            // hiding by scale, visible is a flag vanilla doesn't reset every frame
            if (k.vis[b] == 0) { part.xScale = 0f; part.yScale = 0f; part.zScale = 0f; continue; }
            if (part == m.hat) continue; // child of head in java, rides along
            // java parts are flat, bedrock's are a tree (root > waist > body > head/arms). the bone's
            // world matrix already has every parent in it: F*W*F moves the java part the same way,
            // F = bedrock<->java model space (y flipped around 24px), its own inverse
            net.minecraft.client.model.geom.PartPose p0 = k.vanillaRuch || (k.vanillaGlowa && part == m.head) ? part.storePose() : part.getInitialPose();
            w.set(k.mats, b * 16);
            fin.translation(0f, 24f, 0f).scale(1f, -1f, 1f).mul(w).translate(0f, 24f, 0f).scale(1f, -1f, 1f)
                .translate(p0.x(), p0.y(), p0.z()).rotateZYX(p0.zRot(), p0.yRot(), p0.xRot())
                .scale(p0.xScale(), p0.yScale(), p0.zScale());
            fin.getScale(scl);
            fin.normalize3x3(new org.joml.Matrix4f()).getEulerAnglesZYX(rot);
            part.x = fin.m30();
            part.y = fin.m31();
            part.z = fin.m32();
            part.xRot = rot.x;
            part.yRot = rot.y;
            part.zRot = rot.z;
            part.xScale = scl.x;
            part.yScale = scl.y;
            part.zScale = scl.z;
        }
    }

}
