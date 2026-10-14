package com.koper.koper_lib.physics.dim;

import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// the weather the bodies fly in. every few ticks each body gets the wind of its dimension's JSON, scaled
// by the biome under it, the rain and thunder, a slow gust mix and its height above sea level. the
// engine eases each body toward that, so a 4 tick update never lands as a jolt. no "wind" in the
// dimension file = no wind at all, and a body that had some is handed back to calm air
public final class KhysicsWind {
    private KhysicsWind() {}

    private static final int EVERY = 4;
    // bodies that currently fly in their own wind, so dropping the config calms them again
    private static final Set<Long> WINDY = ConcurrentHashMap.newKeySet();
    private static boolean reported;
    // -Dkoperlib.calmAir=true: no wind anywhere, for test runs that must repeat exactly. a test that
    // wants weather switches it back on with setCalm(false)
    private static volatile boolean calm = Boolean.getBoolean("koperlib.calmAir");

    public static void setCalm(boolean value) { calm = value; }
    public static boolean calm() { return calm; }

    public static void tick(MinecraftServer server) {
        if (KhysDimensions.pendingApply()) KhysDimensions.applyAll(server);
        if (server.getTickCount() % EVERY != 0) return;
        WINDY.removeIf(id -> !KoperPhys.all().containsKey(id));
        for (ServerLevel level : server.getAllLevels()) {
            var cfg = KhysDimensions.getFor(level.dimension().identifier().toString());
            String key = KoperPhys.levelKey(level);
            double seconds = level.getGameTime() / 20.0;
            for (var body : KoperPhys.all().entrySet()) {
                var entry = body.getValue();
                if (!key.equals(entry.levelKey())) continue;
                long id = body.getKey();
                if (calm || !com.koper.koper_lib.physics.KhysicsConfig.get().wind
                        || cfg.wind() == null || cfg.universalDrag() <= 0f) {
                    if (WINDY.remove(id)) KoperPhysBridge.setBodyWind(entry.worldHandle(), id, Float.NaN, Float.NaN, Float.NaN);
                    continue;
                }
                float[] pos = KoperPhys.getCachedPos(id);
                if (pos == null) continue;
                float[] wind = windAt(level, cfg.wind(), cfg.air(), pos, seconds);
                if (KoperPhysBridge.setBodyWind(entry.worldHandle(), id, wind[0], wind[1], wind[2])) {
                    WINDY.add(id);
                } else if (!reported) {
                    reported = true;
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KhysicsWind] wind does not work: the physics backend has no per body wind");
                }
            }
        }
    }

    /** blocks per second, world axes */
    public static float[] windAt(ServerLevel level, KhysDimensions.Wind wind, KhysDimensions.Air air,
                                 float[] pos, double seconds) {
        BlockPos at = BlockPos.containing(pos[0], pos[1], pos[2]);
        // one factor for everything local, so an updraft scales with the weather like the wind does
        float speed = wind.biome(level.getBiome(at));
        float rain = level.getRainLevel(1f), thunder = level.getThunderLevel(1f);
        speed *= 1f + (wind.rain() - 1f) * rain;
        speed *= 1f + (wind.thunder() - 1f) * thunder;
        float height = 1f + wind.heightGain() * (pos[1] - air.seaLevel());
        speed *= Math.clamp(height, 0f, wind.maxHeightFactor());
        // gusts drift across the land: the phase moves with position, so a fleet does not all lurch at once
        double phase = pos[0] * 0.004 + pos[2] * 0.006;
        double t = seconds / wind.gustPeriod() + phase;
        double mix = 0.55 * Math.sin(Math.PI * 2 * t) + 0.30 * Math.sin(Math.PI * 2 * t * 2.71 + 1.3)
            + 0.15 * Math.sin(Math.PI * 2 * t * 6.13 + 4.1);
        float gust = wind.gust() * (1f + (wind.stormGust() - 1f) * thunder);
        speed *= (float)Math.max(0.0, 1.0 + gust * mix);
        double heading = Math.toRadians(wind.direction()
            + wind.veer() * Math.sin(Math.PI * 2 * seconds / wind.veerPeriod()));
        // clockwise from north: 0 blows toward -z, 90 toward +x
        float horizontal = wind.speed() * speed;
        return new float[]{(float)(Math.sin(heading) * horizontal), wind.vertical() * speed,
            (float)(-Math.cos(heading) * horizontal)};
    }
}
