package com.koper.koper_lib.api.core;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// bedrock's villager_v2 keeps the job in minecraft:variant, the biome in minecraft:mark_variant and one
// of six faces in minecraft:skin_id, numbered the way its behavior file numbers them. java keeps them in
// VillagerData. same answer for kodel's queries, behavior filters and scripts
public final class BedrockWiesniak {

    private static final List<String> ZAWODY = List.of("none", "farmer", "fisherman", "shepherd", "fletcher", "librarian",
        "cartographer", "cleric", "armorer", "weaponsmith", "toolsmith", "butcher", "leatherworker", "mason", "nitwit");
    private static final List<String> BIOMY = List.of("plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga");
    private static final Map<Class<?>, Method> DANE = new ConcurrentHashMap<>();
    private static final Method BRAK;

    static {
        try { BRAK = Object.class.getMethod("hashCode"); } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
    }

    private BedrockWiesniak() {}

    public static boolean jest(Entity e) {
        return dane(e) != BRAK;
    }

    // villager and zombie villager both have getVillagerData(), found once per class, no class names to guess
    private static Method dane(Entity e) {
        return DANE.computeIfAbsent(e.getClass(), c -> {
            try { return c.getMethod("getVillagerData"); } catch (NoSuchMethodException no) { return BRAK; }
        });
    }

    // "variant" / "mark_variant" / "skin_id", null when e is no villager
    public static Integer liczba(Entity e, String what) {
        Method m = dane(e);
        if (m == BRAK) return null;
        String w = what.startsWith("minecraft:") ? what.substring(10) : what;
        if (w.equals("skin_id")) return Math.floorMod(e.getUUID().hashCode(), 6);
        if (!w.equals("variant") && !w.equals("mark_variant")) return null;
        try {
            Object data = m.invoke(e);
            Object holder = data.getClass().getMethod(w.equals("variant") ? "profession" : "type").invoke(data);
            if (!(holder instanceof Holder<?> h)) return null;
            String path = h.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            return Math.max(0, (w.equals("variant") ? ZAWODY : BIOMY).indexOf(path));
        } catch (ReflectiveOperationException | RuntimeException odd) {
            return null;
        }
    }
}
