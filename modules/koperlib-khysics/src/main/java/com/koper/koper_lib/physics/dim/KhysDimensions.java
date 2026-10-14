package com.koper.koper_lib.physics.dim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// per-dimension physics, from <fullpack>/khysics/dimensions/*.json. one file per dimension id: gravity,
// air (drag scale, density, how it thins with height), water, the default aero mode, fast flight and
// wind. a dimension without a file gets DimPhysics.DEFAULT, the overworld the physics was tuned in.
// planets and space are just files: "universal_drag": 0 is vacuum, no aero and no wind at all.
public final class KhysDimensions {
    private KhysDimensions() {}

    /** density multiplier, sea level, scale height (blocks per e-fold of thinning), lowest density factor */
    public record Air(float density, float seaLevel, float scaleHeight, float minDensity) {
        public static final Air DEFAULT = new Air(1f, 64f, 180f, 0.001f);
    }

    /**
     * Wind in blocks per second. {@code direction} is where it blows toward, degrees clockwise from north.
     * Gusts and veer are slow sine mixes; weather multiplies speed, thunder also the gusts. Biome entries
     * are ids or {@code #tags}, the first match wins, everything else is 1.
     */
    public record Wind(float speed, float direction, float vertical, float gust, float gustPeriod,
                       float veer, float veerPeriod, float rain, float thunder, float stormGust,
                       float heightGain, float maxHeightFactor, Map<String, Float> biomes) {
        public float biome(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder) {
            for (var entry : biomes.entrySet()) {
                String key = entry.getKey();
                boolean match = key.startsWith("#")
                    ? holder.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BIOME,
                        Identifier.parse(key.substring(1))))
                    : holder.unwrapKey().map(k -> k.identifier().toString().equals(key)).orElse(false);
                if (match) return entry.getValue();
            }
            return 1f;
        }
    }

    public record DimPhysics(float[] gravity, float universalDrag, com.koper.koper_lib.physics.KontraFlightPolicy flight,
                             Air air, Float waterDensity, com.koper.koper_lib.physics.AeroMode aeroMode, Wind wind) {
        public DimPhysics(float[] gravity, float universalDrag, com.koper.koper_lib.physics.KontraFlightPolicy flight) {
            this(gravity, universalDrag, flight, Air.DEFAULT, null, null, null);
        }
        public DimPhysics(float[] gravity, float universalDrag) {
            this(gravity, universalDrag, com.koper.koper_lib.physics.KontraFlightPolicy.DEFAULT);
        }
        public static final DimPhysics DEFAULT = new DimPhysics(new float[]{0f, -28f, 0f}, 0.01f);
    }

    // dimension id string → physics settings
    private static final Map<String, DimPhysics> OVERRIDES = new ConcurrentHashMap<>();
    // set by loadAll, so a pack reload also reaches worlds that already exist (see KhysicsWind.tick)
    private static volatile boolean pendingApply;

    public static void loadAll() {
        OVERRIDES.clear();
        for (var source : com.koper.koper_lib.api.core.KoperPackSources.all()) {
            Path fullpacksDir = source.root();
            if (!Files.isDirectory(fullpacksDir)) continue;
            try (var packDirs = Files.list(fullpacksDir)) {
                packDirs.filter(Files::isDirectory).forEach(packDir -> {
                String packName = packDir.getFileName().toString();
                if (!source.isEnabled(packName)) return;
                Path dimDir = packDir.resolve("khysics").resolve("dimensions");
                if (!Files.isDirectory(dimDir)) return;
                try (var jsonFiles = Files.walk(dimDir)) {
                    jsonFiles.filter(p -> p.toString().endsWith(".json"))
                             .forEach(KhysDimensions::loadFile);
                } catch (IOException e) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KhysDimensions] dimension physics does not work for pack {}: {}", packName, e.getMessage());
                }
                });
            } catch (IOException e) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KhysDimensions] dimension physics does not work for {}: {}", source.owner(), e.getMessage());
            }
        }
        pendingApply = true;
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KhysDimensions] Loaded {} dimension overrides", OVERRIDES.size());
    }

    private static void loadFile(Path file) {
        try (var reader = new InputStreamReader(Files.newInputStream(file))) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (!json.has("dimension")) throw new IllegalArgumentException("missing \"dimension\"");
            String dim = json.get("dimension").getAsString();
            float[] gravity = DimPhysics.DEFAULT.gravity().clone();
            if (json.has("gravity")) {
                var arr = json.getAsJsonArray("gravity");
                if (arr.size() != 3) throw new IllegalArgumentException("\"gravity\" needs [x, y, z]");
                for (int i = 0; i < 3; i++) gravity[i] = arr.get(i).getAsFloat();
            }
            float drag = getFloat(json, "universal_drag", DimPhysics.DEFAULT.universalDrag());
            if (!Float.isFinite(drag) || drag < 0 || !Float.isFinite(gravity[0])
                    || !Float.isFinite(gravity[1]) || !Float.isFinite(gravity[2]))
                throw new IllegalArgumentException("non-finite dimension physics");
            var flight = com.koper.koper_lib.physics.KontraFlightPolicy.parse(
                json.has("flight") ? json.getAsJsonObject("flight") : null);
            Air air = Air.DEFAULT;
            if (json.has("air")) {
                JsonObject a = json.getAsJsonObject("air");
                air = new Air(getFloat(a, "density", air.density()), getFloat(a, "sea_level", air.seaLevel()),
                    getFloat(a, "scale_height", air.scaleHeight()), getFloat(a, "min_density", air.minDensity()));
                if (!(air.density() >= 0) || !Float.isFinite(air.seaLevel()) || !(air.scaleHeight() > 0)
                        || !(air.minDensity() >= 0) || !Float.isFinite(air.density()) || !Float.isFinite(air.scaleHeight()))
                    throw new IllegalArgumentException("bad \"air\": density >= 0, scale_height > 0, min_density >= 0");
            }
            Float water = null;
            if (json.has("water_density")) {
                water = json.get("water_density").getAsFloat();
                if (!Float.isFinite(water) || water < 0) throw new IllegalArgumentException("bad \"water_density\"");
            }
            com.koper.koper_lib.physics.AeroMode aero = null;
            if (json.has("aero_mode")) {
                aero = com.koper.koper_lib.physics.AeroMode.parse(json.get("aero_mode").getAsString());
                if (aero == null) throw new IllegalArgumentException("\"aero_mode\" must be low, correct or extreme");
            }
            Wind wind = json.has("wind") ? parseWind(json.getAsJsonObject("wind")) : null;
            OVERRIDES.put(dim, new DimPhysics(gravity, drag, flight, air, water, aero, wind));
        } catch (Exception e) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KhysDimensions] dimension physics does not work: {} was rejected: {}", file, e.getMessage());
        }
    }

    private static Wind parseWind(JsonObject w) {
        var biomes = new java.util.LinkedHashMap<String, Float>();
        if (w.has("biomes")) for (var entry : w.getAsJsonObject("biomes").entrySet()) {
            float mult = entry.getValue().getAsFloat();
            if (!Float.isFinite(mult) || mult < 0) throw new IllegalArgumentException("bad wind multiplier for " + entry.getKey());
            biomes.put(entry.getKey(), mult);
        }
        Wind wind = new Wind(getFloat(w, "speed", 0f), getFloat(w, "direction", 0f), getFloat(w, "vertical", 0f),
            getFloat(w, "gust", 0f), getFloat(w, "gust_period", 8f), getFloat(w, "veer", 0f), getFloat(w, "veer_period", 600f),
            getFloat(w, "rain", 1f), getFloat(w, "thunder", 1f), getFloat(w, "storm_gust", 1f),
            getFloat(w, "height_gain", 0f), getFloat(w, "max_height_factor", 3f), java.util.Collections.unmodifiableMap(biomes));
        float[] check = {wind.speed(), wind.direction(), wind.vertical(), wind.gust(), wind.gustPeriod(), wind.veer(),
            wind.veerPeriod(), wind.rain(), wind.thunder(), wind.stormGust(), wind.heightGain(), wind.maxHeightFactor()};
        for (float v : check) if (!Float.isFinite(v)) throw new IllegalArgumentException("non-finite wind setting");
        if (wind.speed() < 0 || wind.gust() < 0 || wind.gustPeriod() <= 0 || wind.veerPeriod() <= 0
                || wind.rain() < 0 || wind.thunder() < 0 || wind.stormGust() < 0 || wind.maxHeightFactor() < 0)
            throw new IllegalArgumentException("wind speeds, multipliers and periods must not be negative");
        return wind;
    }

    private static float getFloat(JsonObject json, String key, float fallback) {
        JsonElement el = json.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsFloat() : fallback;
    }

    /** Sets up one physics world for its dimension: at creation and again after every pack reload. */
    public static void configure(long wh, ServerLevel level) {
        DimPhysics cfg = getFor(level.dimension().identifier().toString());
        KoperPhysBridge.setGravity(wh, cfg.gravity()[0], cfg.gravity()[1], cfg.gravity()[2]);
        KoperPhysBridge.configureAtmosphere(wh, cfg.universalDrag());
        if (!KoperPhysBridge.configureFlight(wh, cfg.flight().fastFlight(), (float)cfg.flight().maxSpeed(), level.getMinY() >> 4, (level.getMaxY()-1) >> 4) && cfg.flight().fastFlight())
            throw new IllegalStateException("fast flight requires a compatible Rapier native backend");
        if (!KoperPhysBridge.setAir(wh, cfg.air().density(), cfg.air().seaLevel(), cfg.air().scaleHeight(), cfg.air().minDensity())
                && !cfg.air().equals(Air.DEFAULT))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KhysDimensions] dimension air does not work in {}: the physics backend has no air model", level.dimension().identifier());
        if (cfg.waterDensity() != null) KoperPhysBridge.setWaterDensity(wh, cfg.waterDensity());
        float reaction = com.koper.koper_lib.physics.KhysicsConfig.get().propellerReaction;
        if (!Float.isFinite(reaction) || reaction < 0f || reaction > 1f) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KhysDimensions] propellerReaction does not work: {} is outside 0..1, using 1", reaction);
            reaction = 1f;
        }
        if (!KoperPhysBridge.setPropReaction(wh, reaction) && reaction < 1f)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KhysDimensions] propellerReaction does not work: the physics backend cannot scale it");
    }

    // apply dimension overrides to all world handles — call after loadAll() and on dimension change
    public static void applyAll(MinecraftServer server) {
        pendingApply = false;
        for (ServerLevel level : server.getAllLevels()) {
            long wh = KoperPhys.getWorldHandle(level);
            if (wh <= 0) continue;
            configure(wh, level);
        }
        KoperPhys.refreshDefaultAeroModes();
    }

    /** a pack reload happened since the last apply */
    public static boolean pendingApply() { return pendingApply; }

    public static DimPhysics getFor(String dimensionId) {
        return OVERRIDES.getOrDefault(dimensionId, DimPhysics.DEFAULT);
    }
}
