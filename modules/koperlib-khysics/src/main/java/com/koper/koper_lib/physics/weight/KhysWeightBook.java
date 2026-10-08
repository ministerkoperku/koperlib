package com.koper.koper_lib.physics.weight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// loads block physics properties from datapacks — data/<ns>/khysics/block_props/*.json
// selector priority: exact block id > block tag > default
public final class KhysWeightBook {

    private KhysWeightBook() {}

    // exact block id → props
    private static final Map<Identifier, KhysBlockProps> EXACT  = new ConcurrentHashMap<>();
    // block tag → props  (tag as string because TagKey equals is finicky at load time)
    private static final Map<String, KhysBlockProps>     TAGS   = new ConcurrentHashMap<>();
    // per-BlockState cache to avoid repeated lookups
    private static final Map<BlockState, KhysBlockProps> CACHE  = new ConcurrentHashMap<>();

    // built-in tags for quick datapack use — cols after buoyancy are: aero?, dragCoeff, liftCoeff.
    // aero=false → block is inert in the air (mass/friction only). only #wing + #aerodynamic generate
    // forces: #wing is a lifting surface (Cl>0), #aerodynamic is a low-drag streamlined fin (no lift).
    // cols: mass, friction, restitution, fragility, buoyancyVolume(water), aero?, dragCoeff, liftCoeff(WING), balloonLift(BALLOON)
    // #wing = wool = airfoil wing (lift from MOTION). #balloon = air-buoyancy block (rises to altitude, no
    // fire). #aerodynamic = low-drag fin. only aero=true blocks generate forces; light/heavy are inert.
    private static final Map<String, KhysBlockProps> BUILTINS = Map.of(
        "koperlib:super_light", new KhysBlockProps(0.2f, 0.3f, 0.5f, 50f,  0.2f, false, 0.9f, 0.0f, 0.0f, false),
        "koperlib:light",       new KhysBlockProps(0.5f, 0.5f, 0.3f, 200f, 0.5f, false, 0.8f, 0.0f, 0.0f, false),
        "koperlib:heavy",       new KhysBlockProps(3.0f, 0.8f, 0.1f, 800f, 1.0f, false, 1.0f, 0.0f, 0.0f, false),
        "koperlib:ultra_heavy", new KhysBlockProps(8.0f, 0.9f, 0.05f,9999f,1.0f, false, 1.0f, 0.0f, 0.0f, false),
        "koperlib:wing",        new KhysBlockProps(0.3f, 0.5f, 0.2f, 200f, 0.6f, true,  0.8f, 2.0f, 0.0f, false),
        "koperlib:aerodynamic", new KhysBlockProps(1.0f, 0.5f, 0.15f,400f, 0.8f, true,  0.5f, 0.0f, 0.0f, false),
        // balloon: light envelope, big air-buoyancy → a few lift a small craft up to its density-altitude
        "koperlib:balloon",     new KhysBlockProps(0.3f, 0.6f, 0.1f, 100f, 0.5f, true,  0.6f, 0.0f, 4.0f, false)
    );

    public static KhysBlockProps get(BlockState state) {
        return CACHE.computeIfAbsent(state, s -> {
            KhysBlockProps props = resolve(s);
            var weigher = WEIGHERS.get(s.getBlock());
            if (weigher == null) return props;
            float mass = (float) weigher.weigh(s, props.mass());
            return new KhysBlockProps(mass, props.friction(), props.restitution(), props.fragilityImpulse(),
                props.buoyancyVolume(), props.aero(), props.dragCoeff(), props.liftCoeff(),
                props.balloonLift(), props.wheel());
        });
    }

    // mass that depends on the block STATE, not just the block: ballast that fills with redstone
    // power, a tank that weighs what's in it. gets the book's mass for that state and returns the real
    // one. a state flip on a live hull reweighs that one block in place, no respawn
    @FunctionalInterface public interface Weigher { double weigh(BlockState state, double bookMass); }

    private static final Map<net.minecraft.world.level.block.Block, Weigher> WEIGHERS = new ConcurrentHashMap<>();

    public static void weigher(net.minecraft.world.level.block.Block block, Weigher weigher) {
        if (weigher == null) WEIGHERS.remove(block); else WEIGHERS.put(block, weigher);
        CACHE.keySet().removeIf(s -> s.getBlock() == block);
    }

    // total mass of a block list — used when spawning kontraktion
    public static float totalMass(Iterable<BlockState> states) {
        float total = 0f;
        for (BlockState s : states) total += get(s).mass();
        return Math.max(total, 0.1f);
    }

    // scan all enabled fullpacks for khysics/block_props/*.json
    public static void loadAll() {
        EXACT.clear(); TAGS.clear(); CACHE.clear();

        for (var source : com.koper.koper_lib.api.core.KoperPackSources.all()) {
            Path fullpacksDir = source.root();
            if (!Files.isDirectory(fullpacksDir)) continue;
            try (var packDirs = Files.list(fullpacksDir)) {
                packDirs.filter(Files::isDirectory).forEach(packDir -> {
                String packName = packDir.getFileName().toString();
                if (!source.isEnabled(packName)) return;
                Path propsDir = packDir.resolve("khysics").resolve("block_props");
                if (!Files.isDirectory(propsDir)) return;
                try (var jsonFiles = Files.walk(propsDir)) {
                    jsonFiles.filter(p -> p.toString().endsWith(".json"))
                             .forEach(KhysWeightBook::loadFile);
                } catch (IOException e) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KhysWeightBook] Error in {}: {}", packName, e.getMessage());
                }
                });
            } catch (IOException e) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KhysWeightBook] Error listing {}: {}", source.owner(), e.getMessage());
            }
        }

        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KhysWeightBook] Loaded {} exact, {} tag entries", EXACT.size(), TAGS.size());
    }

    private static void loadFile(Path file) {
        try (var reader = new InputStreamReader(Files.newInputStream(file))) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            KhysBlockProps props = parseProps(json);
            if (!json.has("selector")) return;

            String selector = json.get("selector").getAsString();
            if (selector.startsWith("#")) {
                TAGS.put(selector.substring(1), props);
            } else {
                Identifier id = Identifier.tryParse(selector);
                if (id != null) EXACT.put(id, props);
            }
        } catch (Exception e) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KhysWeightBook] Failed to parse {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private static KhysBlockProps parseProps(JsonObject json) {
        float mass         = getFloat(json, "mass",              KhysBlockProps.DEFAULT.mass());
        float friction     = getFloat(json, "friction",          KhysBlockProps.DEFAULT.friction());
        float restitution  = getFloat(json, "restitution",       KhysBlockProps.DEFAULT.restitution());
        float fragility    = getFloat(json, "fragility_impulse", KhysBlockProps.DEFAULT.fragilityImpulse());
        float buoyancy     = getFloat(json, "buoyancy_volume",   KhysBlockProps.DEFAULT.buoyancyVolume());
        float drag         = getFloat(json, "drag",              KhysBlockProps.DEFAULT.dragCoeff());
        float lift         = getFloat(json, "lift",              KhysBlockProps.DEFAULT.liftCoeff());
        float balloon      = getFloat(json, "balloon",           KhysBlockProps.DEFAULT.balloonLift());
        // aero defaults on when the pack gives this block a wing (lift) or balloon — else opt in with "aero": true
        boolean aero       = json.has("aero") ? json.get("aero").getAsBoolean() : (lift > 0f || balloon > 0f);
        boolean wheel      = json.has("wheel") && json.get("wheel").getAsBoolean();
        return new KhysBlockProps(mass, friction, restitution, fragility, buoyancy, aero, drag, lift, balloon, wheel);
    }

    private static float getFloat(JsonObject json, String key, float fallback) {
        JsonElement el = json.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsFloat() : fallback;
    }

    /** Which rule gave this block its properties. Mirrors resolve() step for step. */
    public record Source(String rule, String detail) {}

    // Without this you cannot tell a deliberate pack entry from the blast-resistance guess, and
    // every "why is this thing so heavy" question turns into reading the loader.
    public static Source source(BlockState state) {
        Block block = state.getBlock();
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);

        if (id != null && EXACT.containsKey(id))
            return new Source("pack entry, exact block id", id.toString());

        for (var tagEntry : TAGS.entrySet()) {
            Identifier tagId = Identifier.tryParse(tagEntry.getKey());
            if (tagId == null) continue;
            if (block.defaultBlockState().is(TagKey.create(Registries.BLOCK, tagId)))
                return new Source("pack entry, tag", "#" + tagEntry.getKey());
        }

        for (String aeroTag : new String[]{ "koperlib:wing", "koperlib:aerodynamic", "koperlib:balloon" }) {
            Identifier tid = Identifier.tryParse(aeroTag);
            if (tid != null && block.defaultBlockState().is(TagKey.create(Registries.BLOCK, tid)))
                return new Source("built-in tag", "#" + aeroTag);
        }
        for (var builtin : BUILTINS.entrySet()) {
            Identifier tagId = Identifier.tryParse(builtin.getKey());
            if (tagId == null) continue;
            if (block.defaultBlockState().is(TagKey.create(Registries.BLOCK, tagId)))
                return new Source("built-in tag", "#" + builtin.getKey());
        }

        float resistance = block.getExplosionResistance();
        if (resistance > 0.0f)
            return new Source("derived from blast resistance",
                String.format("resistance=%.2f - no pack entry for this block", resistance));

        return new Source("default", "nothing matched, and the block has no blast resistance");
    }

    private static KhysBlockProps resolve(BlockState state) {
        Block block = state.getBlock();
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);

        // 1. exact block id match
        if (id != null) {
            KhysBlockProps exact = EXACT.get(id);
            if (exact != null) return exact;
        }

        // 2. block tag matches (iterate registered tags)
        for (var tagEntry : TAGS.entrySet()) {
            Identifier tagId = Identifier.tryParse(tagEntry.getKey());
            if (tagId == null) continue;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
            if (block.defaultBlockState().is(tag)) return tagEntry.getValue();
        }

        // 3. built-in koperlib tags — aero tags resolved FIRST so a block in both #light and #wing
        // (wool is) deterministically becomes a wing. Map.of order isn't stable so the loop below can't.
        for (String aeroTag : new String[]{ "koperlib:wing", "koperlib:aerodynamic", "koperlib:balloon" }) {
            Identifier tid = Identifier.tryParse(aeroTag);
            if (tid != null && block.defaultBlockState().is(TagKey.create(Registries.BLOCK, tid)))
                return BUILTINS.get(aeroTag);
        }
        for (var builtin : BUILTINS.entrySet()) {
            Identifier tagId = Identifier.tryParse(builtin.getKey());
            if (tagId == null) continue;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
            if (block.defaultBlockState().is(tag)) return builtin.getValue();
        }

        // 4. derive mass from blast resistance — obsidian (1200) >> stone (6) >> wood (3)
        // formula: 0.5 + resistance*0.01, clamped to [0.1, 15.0]
        // wood: ~0.53  stone: ~0.56  iron: ~0.75  obsidian: ~12.5  bedrock: 15.0 (capped)
        float res = block.getExplosionResistance();
        if (res > 0.0f) {
            float derivedMass = Math.max(0.1f, Math.min(15.0f, 0.5f + res * 0.01f));
            // derived masses all land ~0.5-0.75 so density alone can't split wood from stone in
            // water. shrink the displaced volume with resistance instead: wood(3)→0.76 floats,
            // stone(6)→0.37 sinks, obsidian→0.3 brick. packs override via buoyancy_volume anyway
            float derivedVol = Math.max(0.3f, Math.min(1.0f, 1.15f - res * 0.13f));
            return new KhysBlockProps(derivedMass, KhysBlockProps.DEFAULT.friction(),
                KhysBlockProps.DEFAULT.restitution(), KhysBlockProps.DEFAULT.fragilityImpulse(),
                derivedVol, false, KhysBlockProps.DEFAULT.dragCoeff(),
                KhysBlockProps.DEFAULT.liftCoeff(), KhysBlockProps.DEFAULT.balloonLift(), false);
        }

        return KhysBlockProps.DEFAULT;
    }
}
