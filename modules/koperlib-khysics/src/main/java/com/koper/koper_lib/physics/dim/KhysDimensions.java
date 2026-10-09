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

// per-dimension physics overrides — data/<ns>/khysics/dimensions/*.json in fullpacks
// called after world handle is created to configure Rapier gravity + drag
public final class KhysDimensions {

    private KhysDimensions() {}

    public record DimPhysics(float[] gravity, float universalDrag, com.koper.koper_lib.physics.KontraFlightPolicy flight) {
        public DimPhysics(float[] gravity, float universalDrag) {
            this(gravity, universalDrag, com.koper.koper_lib.physics.KontraFlightPolicy.DEFAULT);
        }
        public static final DimPhysics DEFAULT = new DimPhysics(new float[]{0f, -28f, 0f}, 0.01f);
    }

    // dimension id string → physics settings
    private static final Map<String, DimPhysics> OVERRIDES = new ConcurrentHashMap<>();

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
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KhysDimensions] Error in {}: {}", packName, e.getMessage());
                }
                });
            } catch (IOException e) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KhysDimensions] Error listing {}: {}", source.owner(), e.getMessage());
            }
        }
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KhysDimensions] Loaded {} dimension overrides", OVERRIDES.size());
    }

    private static void loadFile(Path file) {
        try (var reader = new InputStreamReader(Files.newInputStream(file))) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (!json.has("dimension")) return;
            String dim = json.get("dimension").getAsString();

            float[] gravity = DimPhysics.DEFAULT.gravity().clone();
            if (json.has("gravity")) {
                var arr = json.getAsJsonArray("gravity");
                if (arr.size() == 3) {
                    gravity[0] = arr.get(0).getAsFloat();
                    gravity[1] = arr.get(1).getAsFloat();
                    gravity[2] = arr.get(2).getAsFloat();
                }
            }
            float drag = getFloat(json, "universal_drag", DimPhysics.DEFAULT.universalDrag());
            if (!Float.isFinite(drag) || drag < 0 || !Float.isFinite(gravity[0])
                    || !Float.isFinite(gravity[1]) || !Float.isFinite(gravity[2]))
                throw new IllegalArgumentException("non-finite dimension physics");
            var flight = com.koper.koper_lib.physics.KontraFlightPolicy.parse(
                json.has("flight") ? json.getAsJsonObject("flight") : null);
            OVERRIDES.put(dim, new DimPhysics(gravity, drag, flight));
        } catch (Exception e) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KhysDimensions] Failed {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private static float getFloat(JsonObject json, String key, float fallback) {
        JsonElement el = json.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsFloat() : fallback;
    }

    // apply dimension overrides to all world handles — call after loadAll() and on dimension change
    public static void applyAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            String dimId = level.dimension().identifier().toString();
            long wh = KoperPhys.getWorldHandle(level);
            if (wh <= 0) continue;
            DimPhysics cfg = OVERRIDES.getOrDefault(dimId, DimPhysics.DEFAULT);
            KoperPhysBridge.setGravity(wh, cfg.gravity()[0], cfg.gravity()[1], cfg.gravity()[2]);
            KoperPhysBridge.configureAtmosphere(wh,cfg.universalDrag());
            if (!KoperPhysBridge.configureFlight(wh, cfg.flight().fastFlight(), (float)cfg.flight().maxSpeed(), level.getMinY() >> 4, (level.getMaxY()-1) >> 4) && cfg.flight().fastFlight())
                throw new IllegalStateException("fast flight requires a compatible Rapier native backend");
        }
    }

    public static DimPhysics getFor(String dimensionId) {
        return OVERRIDES.getOrDefault(dimensionId, DimPhysics.DEFAULT);
    }
}
