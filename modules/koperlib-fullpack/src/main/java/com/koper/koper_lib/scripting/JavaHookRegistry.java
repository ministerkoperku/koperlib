package com.koper.koper_lib.scripting;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.*;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// scans compiled pack classes for @KoperHook / @KoperItem / @KoperSubscribe, wires them up on reload
// hooks run BEFORE lua scripts for same event — returning SUCCESS/FAIL short-circuits lua
public class JavaHookRegistry {

    private static final Map<String, KoperPackClassLoader> LOADERS   = new ConcurrentHashMap<>();
    private static final Map<String, List<HookEntry>>      HOOKS     = new ConcurrentHashMap<>();

    // registered by other mods via KoperLibAPI — these SURVIVE reload (mods don't re-onInitialize)
    private static final Map<String, List<java.util.function.Function<KoperContext, net.minecraft.world.InteractionResult>>>
        MOD_HOOKS = new ConcurrentHashMap<>();

    record HookEntry(Object instance, Method method) {}

    // well-known method names for @KoperItem classes — method name → event suffix
    // so onUse in a @KoperItem class auto-becomes "namespace:id/on_use"
    private static final Map<String, String> ITEM_AUTO_EVENTS = Map.of(
        "onUse",     "on_use",
        "onHit",     "on_hit",
        "onAttack",  "on_attack",
        "onEquip",   "on_equip",
        "onUnequip", "on_unequip",
        "onTick",    "on_tick",
        "onBreak",   "on_break",
        "onThrow",   "on_throw",
        "onDrop",    "on_drop"
    );

    private static final Map<String, String> ENTITY_AUTO_EVENTS = Map.of(
        "onSpawn",    "on_spawn",
        "onTick",     "on_tick",
        "onInteract", "on_interact",
        "onDamage",   "on_damage",
        "onDeath",    "on_death"
    );

    private static final Map<String, String> BLOCK_AUTO_EVENTS = Map.of(
        "onUse",   "on_use",
        "onPlace", "on_place",
        "onBreak", "on_break"
    );

    // ── load ─────────────────────────────────────────────────────────────────

    public static void loadPackJava(Path packRoot, String namespace) {
        Path classesDir;
        try {
            classesDir = KoperPackJavaCompiler.compile(packRoot, namespace);
        } catch (IOException e) {
            KoperLib.LOGGER.error("[JavaPack:{}] compile failed: {}", namespace, e.getMessage());
            return;
        }
        if (classesDir == null) return;

        KoperPackClassLoader old = LOADERS.remove(namespace);
        if (old != null) try { old.close(); } catch (IOException ignored) {}
        HOOKS.entrySet().removeIf(e -> e.getKey().startsWith(namespace + ":"));

        KoperPackClassLoader loader;
        try {
            loader = new KoperPackClassLoader(classesDir, JavaHookRegistry.class.getClassLoader(), namespace);
        } catch (Exception e) {
            KoperLib.LOGGER.error("[JavaPack:{}] loader init failed: {}", namespace, e.getMessage());
            return;
        }
        LOADERS.put(namespace, loader);

        try (var walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> scanClass(p, classesDir, loader, namespace));
        } catch (IOException e) {
            KoperLib.LOGGER.error("[JavaPack:{}] class scan failed: {}", namespace, e.getMessage());
        }
    }

    private static void scanClass(Path classFile, Path classesDir, KoperPackClassLoader loader, String ns) {
        String className = classesDir.relativize(classFile).toString()
            .replace(java.io.File.separatorChar, '.').replace('/', '.').replaceAll("\\.class$", "");
        try {
            // Class.forName with init=true triggers static blocks (KoperEvents.on* registrations)
            Class<?> cls = Class.forName(className, true, loader);
            Object instance = null;

            // @KoperItem class-level — auto-bind well-known method names to item events
            KoperItem itemAnn = cls.getAnnotation(KoperItem.class);
            KoperEntity entityAnn = cls.getAnnotation(KoperEntity.class);
            KoperBlock blockAnn = cls.getAnnotation(KoperBlock.class);
            if (itemAnn != null) {
                // collect all item IDs this class handles (primary + also[])
                List<String> itemIds = new ArrayList<>();
                itemIds.add(itemAnn.value());
                for (String extra : itemAnn.also()) itemIds.add(extra);

                for (Method m : cls.getDeclaredMethods()) {
                    String eventSuffix = ITEM_AUTO_EVENTS.get(m.getName());
                    // also accept @KoperHook on methods inside a @KoperItem class (explicit override)
                    KoperHook explicitHook = m.getAnnotation(KoperHook.class);
                    if (eventSuffix == null && explicitHook == null) continue;

                    if (!Modifier.isStatic(m.getModifiers()) && instance == null) {
                        try { instance = cls.getDeclaredConstructor().newInstance(); }
                        catch (Exception e) {
                            KoperLib.LOGGER.warn("[JavaPack:{}] can't instantiate @KoperItem {} — needs no-arg ctor", ns, cls.getSimpleName());
                            break;
                        }
                    }
                    m.setAccessible(true);

                    if (explicitHook != null) {
                        // explicit @KoperHook inside @KoperItem — use that exact ID
                        registerHook(explicitHook.value(), instance, m, ns, cls.getSimpleName());
                    } else {
                        // auto-bind to every item ID this class declared
                        for (String itemId : itemIds) {
                            String hookId = itemId + "/" + eventSuffix;
                            registerHook(hookId, instance, m, ns, cls.getSimpleName());
                        }
                    }
                }
            }

            if (entityAnn != null) {
                List<String> entityIds = new ArrayList<>();
                entityIds.add(entityAnn.value());
                for (String extra : entityAnn.also()) entityIds.add(extra);

                for (Method m : cls.getDeclaredMethods()) {
                    String eventSuffix = ENTITY_AUTO_EVENTS.get(m.getName());
                    KoperHook explicitHook = m.getAnnotation(KoperHook.class);
                    if (eventSuffix == null && explicitHook == null) continue;

                    if (!Modifier.isStatic(m.getModifiers()) && instance == null) {
                        try { instance = cls.getDeclaredConstructor().newInstance(); }
                        catch (Exception e) {
                            KoperLib.LOGGER.warn("[JavaPack:{}] can't instantiate @KoperEntity {} — needs no-arg ctor", ns, cls.getSimpleName());
                            break;
                        }
                    }
                    m.setAccessible(true);

                    if (explicitHook != null) {
                        registerHook(explicitHook.value(), instance, m, ns, cls.getSimpleName());
                    } else {
                        for (String entityId : entityIds) {
                            registerHook(entityId + "/" + eventSuffix, instance, m, ns, cls.getSimpleName());
                        }
                    }
                }
            }

            if (blockAnn != null) {
                List<String> blockIds = new ArrayList<>();
                blockIds.add(blockAnn.value());
                for (String extra : blockAnn.also()) blockIds.add(extra);

                for (Method m : cls.getDeclaredMethods()) {
                    String eventSuffix = BLOCK_AUTO_EVENTS.get(m.getName());
                    KoperHook explicitHook = m.getAnnotation(KoperHook.class);
                    if (eventSuffix == null && explicitHook == null) continue;

                    if (!Modifier.isStatic(m.getModifiers()) && instance == null) {
                        try { instance = cls.getDeclaredConstructor().newInstance(); }
                        catch (Exception e) {
                            KoperLib.LOGGER.warn("[JavaPack:{}] can't instantiate @KoperBlock {} — needs no-arg ctor", ns, cls.getSimpleName());
                            break;
                        }
                    }
                    m.setAccessible(true);

                    if (explicitHook != null) {
                        registerHook(explicitHook.value(), instance, m, ns, cls.getSimpleName());
                    } else {
                        for (String blockId : blockIds) {
                            registerHook(blockId + "/" + eventSuffix, instance, m, ns, cls.getSimpleName());
                        }
                    }
                }
            }

            // @KoperHook / @KoperSubscribe on individual methods (works in any class)
            for (Method m : cls.getDeclaredMethods()) {
                // skip if already handled by @KoperItem/@KoperEntity/@KoperBlock auto-binding
                if (itemAnn != null && ITEM_AUTO_EVENTS.containsKey(m.getName())) continue;
                if (entityAnn != null && ENTITY_AUTO_EVENTS.containsKey(m.getName())) continue;
                if (blockAnn != null && BLOCK_AUTO_EVENTS.containsKey(m.getName())) continue;

                KoperHook    hookAnn = m.getAnnotation(KoperHook.class);
                KoperSubscribe subAnn = m.getAnnotation(KoperSubscribe.class);
                if (hookAnn == null && subAnn == null) continue;

                if (!Modifier.isStatic(m.getModifiers()) && instance == null) {
                    try { instance = cls.getDeclaredConstructor().newInstance(); }
                    catch (Exception e) {
                        KoperLib.LOGGER.warn("[JavaPack:{}] can't instantiate {} — skipping non-static hooks", ns, cls.getSimpleName());
                        continue;
                    }
                }
                m.setAccessible(true);

                if (hookAnn != null) registerHook(hookAnn.value(), instance, m, ns, cls.getSimpleName());
                if (subAnn != null) {
                    try {
                        MethodHandle mh = MethodHandles.lookup().unreflect(m);
                        if (instance != null) mh = mh.bindTo(instance);
                        KoperEventBus.subscribe(subAnn.value(), mh);
                        KoperLib.LOGGER.debug("[JavaPack:{}] @KoperSubscribe {} → {}.{}", ns, subAnn.value(), cls.getSimpleName(), m.getName());
                    } catch (Exception e) {
                        KoperLib.LOGGER.warn("[JavaPack:{}] @KoperSubscribe MH failed for {}.{}: {}", ns, cls.getSimpleName(), m.getName(), e.getMessage());
                    }
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            KoperLib.LOGGER.debug("[JavaPack:{}] skipped class {}: {}", ns, className, e.getMessage());
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[JavaPack:{}] error scanning {}: {}", ns, className, e.getMessage());
        }
    }

    private static void registerHook(String hookId, Object instance, Method m, String ns, String simpleName) {
        HOOKS.computeIfAbsent(hookId, k -> new ArrayList<>()).add(new HookEntry(instance, m));
        KoperLib.LOGGER.debug("[JavaPack:{}] hook {} → {}.{}", ns, hookId, simpleName, m.getName());
    }

    // called by other mods via KoperLibAPI.fullpack().addItemHook(...)
    // these persist across fullpack reloads — mods don't re-register on reload
    public static void registerModHook(
            String hookId,
            java.util.function.Function<KoperContext, net.minecraft.world.InteractionResult> fn) {
        MOD_HOOKS.computeIfAbsent(hookId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(fn);
        KoperLib.LOGGER.debug("[ModHook] registered mod hook for {}", hookId);
    }

    // ── fire ─────────────────────────────────────────────────────────────────

    // bedrock addon custom components listen here. one slot, swapped on reload, never piles up like MOD_HOOKS would
    private static volatile java.util.function.BiFunction<String, KoperContext, net.minecraft.world.InteractionResult> BEDROCK_EAR;

    public static void bedrockEar(java.util.function.BiFunction<String, KoperContext, net.minecraft.world.InteractionResult> ear) {
        BEDROCK_EAR = ear;
    }

    // returns first non-PASS result or PASS if none; call before lua scripts
    public static net.minecraft.world.InteractionResult fireHook(String hookId, KoperContext ctx) {
        var ear = BEDROCK_EAR;
        if (ear != null) {
            var said = ear.apply(hookId, ctx);
            if (said != null && said != net.minecraft.world.InteractionResult.PASS) return said;
        }
        // pack hooks first (cleared on reload)
        List<HookEntry> hooks = HOOKS.get(hookId);
        if (hooks != null) {
            for (HookEntry h : hooks) {
                try {
                    Object result = h.method().invoke(h.instance(), resolveArgs(h.method().getParameterTypes(), ctx));
                    if (result instanceof net.minecraft.world.InteractionResult ir
                            && ir != net.minecraft.world.InteractionResult.PASS) return ir;
                } catch (Exception e) {
                    KoperLib.LOGGER.error("[JavaPack] hook {} threw: {}", hookId, e.getMessage());
                }
            }
        }
        // mod hooks (survive reload)
        var modHooks = MOD_HOOKS.get(hookId);
        if (modHooks != null) {
            for (var fn : modHooks) {
                try {
                    var result = fn.apply(ctx);
                    if (result != null && result != net.minecraft.world.InteractionResult.PASS) return result;
                } catch (Exception e) {
                    KoperLib.LOGGER.error("[ModHook] hook {} threw: {}", hookId, e.getMessage());
                }
            }
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    public static boolean hasHooks(String hookId) {
        if (BEDROCK_EAR != null && com.koper.koper_lib.bedrock.BedrockSkrypciarz.slucha(hookId)) return true;
        List<HookEntry> h = HOOKS.get(hookId);
        if (h != null && !h.isEmpty()) return true;
        var mh = MOD_HOOKS.get(hookId);
        return mh != null && !mh.isEmpty();
    }

    private static Object[] resolveArgs(Class<?>[] types, KoperContext ctx) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if      (t == KoperContext.class)                                                             out[i] = ctx;
            else if (t.isAssignableFrom(net.minecraft.server.level.ServerPlayer.class) && ctx.player() != null) out[i] = ctx.player();
            else if (t.isAssignableFrom(net.minecraft.world.entity.LivingEntity.class) && ctx.entity() != null) out[i] = ctx.entity();
            else if (t.isAssignableFrom(net.minecraft.world.level.Level.class)         && ctx.world()  != null) out[i] = ctx.world();
            else if (t == net.minecraft.world.item.ItemStack.class)                                      out[i] = ctx.stack();
            else if (t == net.minecraft.core.BlockPos.class)                                             out[i] = ctx.pos();
            else out[i] = null;
        }
        return out;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    public static void clearAll() {
        LOADERS.values().forEach(l -> { try { l.close(); } catch (IOException ignored) {} });
        LOADERS.clear();
        HOOKS.clear();
        KoperEventBus.clearAll();
        KoperEventProxy.bumpGeneration();
    }
}
