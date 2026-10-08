package com.koper.koper_lib.scripting;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperMeta;
import com.koper.koper_lib.panama.RustBridge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// one Lua VM per namespace; scripts in the same pack share globals and can require() each other
public class UniversalScriptEngine {

    // namespace → opaque Rust VM handle
    private static final Map<String, Long> PACK_VMS = new HashMap<>();
    // "vmHandle:absolutePath" → already loaded
    private static final Set<String> LOADED_FILES = new HashSet<>();
    // scripts that errored — suppresses spam until next reload
    private static final Set<String> FAILED_SCRIPTS = new HashSet<>();

    // guards VM create/destroy against concurrent game-tick calls
    // fine-grained locking would be better but reload is rare and ticks don't overlap here
    private static final java.util.concurrent.locks.ReentrantLock RELOAD_LOCK = new java.util.concurrent.locks.ReentrantLock();

    // a vm handle is a raw pointer on the rust side. a reload that lands while lua is still running
    // (a script ran "/koperlib reload" through an upcall, or a fan-out loop dispatched it between two
    // vms) used to free the vm that was still executing: "double free or corruption" and the server
    // died. now every entry point counts itself in, and a reload inside one only retires the old vms,
    // which are freed when the last entry point leaves
    private static int active;
    private static final java.util.List<Long> RETIRED = new java.util.ArrayList<>();

    private static final class Guard implements AutoCloseable {
        private static final Guard INSTANCE = new Guard();

        static Guard enter() {
            RELOAD_LOCK.lock();
            active++;
            return INSTANCE;
        }

        @Override
        public void close() {
            try {
                if (--active == 0 && !RETIRED.isEmpty()) {
                    for (long handle : RETIRED) RustBridge.scriptDestroyVm(handle);
                    RETIRED.clear();
                }
            } finally {
                RELOAD_LOCK.unlock();
            }
        }
    }

    // the fan-out loops walk a copy, and stop as soon as a reload retired the vms they were walking
    private static java.util.List<Long> liveVms() {
        return java.util.List.copyOf(PACK_VMS.values());
    }

    private static boolean retired(long handle) {
        return RETIRED.contains(handle);
    }

    // reuse one SB per thread — the Lua invocation strings are big enough that allocating a new
    // one every call becomes a measurable source of churn on busy tick rates
    private static final ThreadLocal<StringBuilder> INVOKE_BUF =
        ThreadLocal.withInitial(() -> new StringBuilder(512));

    private static net.minecraft.server.MinecraftServer cachedServer;

    public static void setServer(net.minecraft.server.MinecraftServer server) { cachedServer = server; }
    public static net.minecraft.server.MinecraftServer getCurrentServer() { return cachedServer; }

    public static void clearCache() {
        RELOAD_LOCK.lock();
        try {
            if (RustBridge.isLoaded()) {
                for (long handle : PACK_VMS.values()) {
                    if (handle == 0L) continue;
                    if (active > 0) RETIRED.add(handle);
                    else RustBridge.scriptDestroyVm(handle);
                }
            }
            PACK_VMS.clear();
            LOADED_FILES.clear();
            FAILED_SCRIPTS.clear();
        } finally {
            RELOAD_LOCK.unlock();
        }
    }

    // resolves "namespace:scripts/sword.lua" or bare path to absolute file
    public static Path resolveScriptPath(String scriptId) {
        if (scriptId == null) return null;

        Path packsRoot = FabricLoader.getInstance().getGameDir().resolve("koperlib").resolve("fullpacks");
        String[] EXTS = {"", ".lua", ".py", ".js"};

        if (scriptId.contains(":")) {
            String[] parts   = scriptId.split(":", 2);
            String namespace = parts[0];
            String name      = parts[1];
			if (name.startsWith("scripts/")) name = name.substring("scripts/".length());

            Path packDir = packsRoot.resolve(namespace);
            for (String ext : EXTS) {
                Path p = packDir.resolve("scripts").resolve(name + ext);
                if (Files.exists(p)) return p;
                p = packDir.resolve(name + ext);
                if (Files.exists(p)) return p;
            }

            try {
                if (Files.isDirectory(packsRoot)) {
                    try (var dirs = Files.list(packsRoot)) {
                        for (Path dir : dirs.toList()) {
                            if (!Files.isDirectory(dir) || dir.getFileName().toString().startsWith(".")) continue;
                            KoperMeta meta = FullPackLoader.getMeta(dir.getFileName().toString());
                            if (meta == null || !namespace.equals(meta.namespace)) continue;
                            for (String ext : EXTS) {
                                Path p = dir.resolve("scripts").resolve(name + ext);
                                if (Files.exists(p)) return p;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                KoperLib.LOGGER.warn("[Script] Error scanning packs for {}", scriptId, e);
            }
        } else {
            try {
                if (Files.isDirectory(packsRoot)) {
                    try (var dirs = Files.list(packsRoot)) {
                        for (Path dir : dirs.toList()) {
                            if (!Files.isDirectory(dir)) continue;
                            Path p = dir.resolve("scripts").resolve(scriptId);
                            if (Files.exists(p)) return p;
                        }
                    }
                }
            } catch (IOException e) {
                KoperLib.LOGGER.warn("[Script] Error scanning packs for {}", scriptId, e);
            }
        }
        return null;
    }

    private static long getOrCreateVm(String namespace) {
        if (!RustBridge.isLoaded()) return 0L;
        Long known = PACK_VMS.get(namespace);
        if (known != null) return known;
        // not computeIfAbsent: the install below runs lua, and lua that reaches back into this map
        // from inside the mapping function breaks a HashMap
        long h = RustBridge.scriptCreateVm();
        if (h != 0) KoperLib.LOGGER.debug("[Script] Created VM for: {}", namespace);
        else        KoperLib.LOGGER.error("[Script] Failed to create VM for: {}", namespace);
        PACK_VMS.put(namespace, h);
        if (h != 0) {
            String addonInstall = LuaAddonRegistry.installAllScript();
            if (!addonInstall.isBlank()) RustBridge.scriptExec(h, addonInstall);
        }
        return h;
    }

    static void installLuaModule(String name) {
        String script = LuaAddonRegistry.installScript(name);
        if (script.isBlank()) return;
        try (Guard guard = Guard.enter()) {
            for (long handle : liveVms()) {
                if (handle != 0L && !retired(handle)) RustBridge.scriptExec(handle, script);
            }
        }
    }

    private static String extractNamespace(String scriptId) {
        if (scriptId == null) return "koperlib";
        int colon = scriptId.indexOf(':');
        return colon > 0 ? scriptId.substring(0, colon) : "koperlib";
    }

    public static void loadScript(String scriptId) {
        if (scriptId == null) return;
        if (FAILED_SCRIPTS.contains(scriptId)) return;
        Path path = resolveScriptPath(scriptId);
        if (path == null) {
            KoperLib.LOGGER.warn("[Script] Not found: {} (suppressing until reload)", scriptId);
            FAILED_SCRIPTS.add(scriptId);
            return;
        }

        loadResolvedScript(scriptId, path);
    }

    /** Eagerly loads static declaration scripts such as scripts/kfx/*.lua in deterministic order. */
    public static void preloadDirectory(String directory) {
        if (directory == null || !directory.matches("[a-zA-Z0-9_-]+")) return;
        for (java.io.File pack : FullPackLoader.getEnabledPackDirs()) {
            Path scripts = pack.toPath().resolve("scripts");
            Path root = scripts.resolve(directory);
            if (!Files.isDirectory(root)) continue;
            KoperMeta meta = FullPackLoader.getMeta(pack.getName());
            String namespace = meta != null
                ? meta.getEffectiveNamespace(pack.getName())
                : pack.getName().toLowerCase();
            try (var files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".lua"))
                    .sorted()
                    .forEach(path -> {
                        String relative = scripts.relativize(path).toString().replace('\\', '/');
                        String scriptId = namespace + ":" + relative.substring(0, relative.length() - 4);
                        if (!loadResolvedScript(scriptId, path)) {
                            com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
                        }
                    });
            } catch (IOException error) {
                com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
                KoperLib.LOGGER.warn("[Script] Failed preloading {}/{}: {}", pack.getName(), directory, error.getMessage());
            }
        }
    }

    private static boolean loadResolvedScript(String scriptId, Path path) {
        try (Guard guard = Guard.enter()) {
            return loadResolvedScriptGuarded(scriptId, path);
        }
    }

    private static boolean loadResolvedScriptGuarded(String scriptId, Path path) {
        String namespace = extractNamespace(scriptId);
        long vmHandle = getOrCreateVm(namespace);
        if (vmHandle == 0) {
            KoperLib.LOGGER.warn("[Script] No VM for '{}', skipping: {}", namespace, scriptId);
            return false;
        }

        String absPath = path.toAbsolutePath().toString();
        String loadKey = vmHandle + ":" + absPath;
        if (LOADED_FILES.contains(loadKey)) return true;

        boolean ok = RustBridge.scriptLoad(vmHandle, absPath);
        if (ok) {
            LOADED_FILES.add(loadKey);
            if (!RustBridge.isUpcallActive()) {
                String commands = RustBridge.scriptDrainCommands(vmHandle);
                if (!commands.isEmpty()) ScriptCommandDispatcher.dispatch(commands, new Object[0]);
            }
            KoperLib.LOGGER.debug("[Script] Loaded: {} → {}", scriptId, absPath);
        } else {
            KoperLib.LOGGER.error("[Script] Failed to load: {}", scriptId);
        }
        return ok;
    }

    public static void call(String scriptId, ScriptEvent event, Object... args) {
        if (scriptId == null || FAILED_SCRIPTS.contains(scriptId)) return;

        try (Guard guard = Guard.enter()) {
            if (com.koper.koper_lib.fullpack.config.FullpackConfig.get().autoReloadScripts) {
                LOADED_FILES.removeIf(k -> k.endsWith(scriptId));
            }

            loadScript(scriptId);

            Path path = resolveScriptPath(scriptId);
            if (path == null) return;

            String namespace = extractNamespace(scriptId);
            long vmHandle = PACK_VMS.getOrDefault(namespace, 0L);
            if (vmHandle == 0) return;

            String fnName = getLuaFunctionName(event);
            String absPath = path.toAbsolutePath().toString().replace("\\", "\\\\");

            // build everything into one reused buffer — no intermediate String allocations
            StringBuilder sb = INVOKE_BUF.get();
            sb.setLength(0);

            appendWorld(sb);

            buildLuaInvocation(sb, fnName, event, args, absPath);

            boolean ok = RustBridge.scriptExec(vmHandle, sb.toString());
            if (!ok) {
                KoperLib.LOGGER.debug("[Script] Error in '{}' in: {}", fnName, scriptId);
            }

            // drain only if the synchronous upcall path isn't available
            // normally this branch is dead — upcall fires per-command inside scriptExec
            if (!RustBridge.isUpcallActive()) {
                String cmds = RustBridge.scriptDrainCommands(vmHandle);
                if (!cmds.isEmpty()) ScriptCommandDispatcher.dispatch(cmds, args);
            }
        }
    }

    // fires a kui interaction into the pack's lua via koper.events.on("gui:click"/"gui:toggle"/...).
    // handlers live in the koper.events table so they survive the load-time global wipe — no rust change needed.
    public static void fireGuiEvent(String scriptId, net.minecraft.server.level.ServerPlayer player,
                                    String guiId, String widget, String action,
                                    float value, String text, boolean checked,
                                    net.minecraft.world.Container slots) {
        if (scriptId == null || player == null || FAILED_SCRIPTS.contains(scriptId)) return;

        try (Guard guard = Guard.enter()) {
            loadScript(scriptId);
            Path path = resolveScriptPath(scriptId);
            if (path == null) return;
            String namespace = extractNamespace(scriptId);
            long vmHandle = PACK_VMS.getOrDefault(namespace, 0L);
            if (vmHandle == 0) return;

            StringBuilder sb = INVOKE_BUF.get();
            sb.setLength(0);
                appendWorld(sb);
            sb.append("koper._ctx = {}\n");
            appendEntitySetup(sb, "_kp", player);
            sb.append("koper._ctx.player = _kp\n");

            // container slots, so a button handler can read what the player put in (1-indexed, air = empty)
            sb.append("local _slots = {");
            if (slots != null) {
                for (int i = 0; i < slots.getContainerSize(); i++) {
                    var stack = slots.getItem(i);
                    String id = stack.isEmpty() ? "minecraft:air"
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    sb.append('[').append(i + 1).append("]={id=\"").append(id).append("\",count=").append(stack.getCount()).append("},");
                }
            }
            sb.append("}\n");

            sb.append("koper.events.fire(\"gui:").append(action).append("\", {")
              .append("gui=\"").append(luaStr(guiId)).append("\",")
              .append("widget=\"").append(luaStr(widget)).append("\",")
              .append("value=").append(value).append(",")
              .append("checked=").append(checked).append(",")
              .append("text=\"").append(luaStr(text)).append("\",")
              .append("slots=_slots,")
              .append("player=_kp})\n");

            boolean ok = RustBridge.scriptExec(vmHandle, sb.toString());
            if (!ok) KoperLib.LOGGER.debug("[Kui] gui handler error in: {}", scriptId);

            if (!RustBridge.isUpcallActive()) {
                String cmds = RustBridge.scriptDrainCommands(vmHandle);
                if (!cmds.isEmpty()) ScriptCommandDispatcher.dispatch(cmds, new Object[]{ player });
            }
        }
    }

    // a /command declared from lua. the pack that declared it may not be the only one listening,
    // so this goes to every vm and each decides via koper.events.on("command:<id>", ...)
    // koper.world.get_time / is_day read this table. it was only filled for plain handler calls, so in a
    // command, channel, gui or kfx handler get_time was always 0 and is_day always false
    private static void appendWorld(StringBuilder sb) {
        net.minecraft.server.level.ServerLevel overworld = cachedServer != null ? cachedServer.overworld() : null;
        if (overworld != null) {
            sb.append("koper._world = {time=").append(overworld.getGameTime())
              .append(",day=").append(overworld.getDefaultClockTime() % 24000 < 12000).append("}\n");
        } else {
            sb.append("koper._world = {time=0,day=true}\n");
        }
    }

    public static void fireCommandEvent(String commandId, net.minecraft.server.level.ServerPlayer player, String args) {
        if (commandId == null || player == null) return;

        try (Guard guard = Guard.enter()) {
            for (long vmHandle : liveVms()) {
                if (vmHandle == 0 || retired(vmHandle)) continue;
                StringBuilder sb = INVOKE_BUF.get();
                sb.setLength(0);
                appendWorld(sb);
                sb.append("koper._ctx = {}\n");
                appendEntitySetup(sb, "_kp", player);
                sb.append("koper._ctx.player = _kp\n");
                sb.append("koper.events.fire(\"command:").append(luaStr(commandId)).append("\", {")
                  .append("args=\"").append(luaStr(args)).append("\",")
                  .append("player=_kp})\n");

                if (!RustBridge.scriptExec(vmHandle, sb.toString()))
                    KoperLib.LOGGER.debug("[ScriptCmd] handler error for {}", commandId);

                if (!RustBridge.isUpcallActive()) {
                    String cmds = RustBridge.scriptDrainCommands(vmHandle);
                    if (!cmds.isEmpty()) ScriptCommandDispatcher.dispatch(cmds, new Object[]{ player });
                }
            }
        }
    }

    // koper.network.on_receive(channel, fn) — a client sent something on a pack channel
    public static void fireChannelEvent(String channel, net.minecraft.server.level.ServerPlayer player, String data) {
        if (channel == null || player == null) return;

        try (Guard guard = Guard.enter()) {
            for (long vmHandle : liveVms()) {
                if (vmHandle == 0 || retired(vmHandle)) continue;
                StringBuilder sb = INVOKE_BUF.get();
                sb.setLength(0);
                appendWorld(sb);
                sb.append("koper._ctx = {}\n");
                appendEntitySetup(sb, "_kp", player);
                sb.append("koper._ctx.player = _kp\n");
                sb.append("koper.events.fire(\"net:").append(luaStr(channel)).append("\", {")
                  .append("data=\"").append(luaStr(data)).append("\",")
                  .append("player=_kp})\n");

                if (!RustBridge.scriptExec(vmHandle, sb.toString()))
                    KoperLib.LOGGER.debug("[Net] channel handler error on {}", channel);

                if (!RustBridge.isUpcallActive()) {
                    String cmds = RustBridge.scriptDrainCommands(vmHandle);
                    if (!cmds.isEmpty()) ScriptCommandDispatcher.dispatch(cmds, new Object[]{ player });
                }
            }
        }
    }

    /** Fan-out for KFX signals and impacts. JSON is decoded inside Rust, never interpolated as Lua code. */
    public static void fireKfxEvent(String event, String dataJson) {
        if (event == null || event.isBlank() || PACK_VMS.isEmpty()) return;
        String safeJson = dataJson == null || dataJson.isBlank() ? "{}" : dataJson;
        try (Guard guard = Guard.enter()) {
            for (long vmHandle : liveVms()) {
                if (vmHandle == 0L || retired(vmHandle)) continue;
                String invocation = "koper.events.fire_json(\"" + luaStr(event) + "\",\""
                    + luaStr(safeJson) + "\")";
                if (!RustBridge.scriptExec(vmHandle, invocation)) {
                    KoperLib.LOGGER.debug("[KFX] Lua event handler error on {}", event);
                }
                if (!RustBridge.isUpcallActive()) {
                    String commands = RustBridge.scriptDrainCommands(vmHandle);
                    if (!commands.isEmpty()) ScriptCommandDispatcher.dispatch(commands, new Object[0]);
                }
            }
        }
    }

    // koper.snitch fan-out. every pack vm hears every world event, each one filters with
    // koper.events.on("player:kill", fn). same shape as the command/channel path above.
    public static void fireSnitchEvent(String what, net.minecraft.server.level.ServerPlayer player,
                                       java.util.Map<String, String> bits) {
        if (what == null || player == null || PACK_VMS.isEmpty()) return;

        try (Guard guard = Guard.enter()) {
            for (long vmHandle : liveVms()) {
                if (vmHandle == 0 || retired(vmHandle)) continue;
                StringBuilder sb = INVOKE_BUF.get();
                sb.setLength(0);
                appendWorld(sb);
                sb.append("koper._ctx = {}\n");
                appendEntitySetup(sb, "_kp", player);
                sb.append("koper._ctx.player = _kp\n");
                sb.append("koper.events.fire(\"").append(luaStr(what)).append("\", {player=_kp,");
                if (bits != null) for (var bit : bits.entrySet()) {
                    sb.append(bit.getKey()).append("=\"").append(luaStr(bit.getValue())).append("\",");
                }
                sb.append("})\n");

                if (!RustBridge.scriptExec(vmHandle, sb.toString()))
                    KoperLib.LOGGER.debug("[Snitch] handler error on {}", what);

                if (!RustBridge.isUpcallActive()) {
                    String cmds = RustBridge.scriptDrainCommands(vmHandle);
                    if (!cmds.isEmpty()) ScriptCommandDispatcher.dispatch(cmds, new Object[]{ player });
                }
            }
        }
    }

    // snitch skips building anything when nothing can listen
    public static boolean anyVmAlive() {
        return !PACK_VMS.isEmpty();
    }

    private static String luaStr(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /*
     * arg convention:
     *   args[0] = primary subject (player for item events, mob for mob events)
     *   args[1] = secondary subject (target/attacker — LivingEntity or null)
     *   BlockPos → koper._ctx.pos
     */
    private static void buildLuaInvocation(StringBuilder sb, String fnName, ScriptEvent event,
                                            Object[] args, String absPath) {
        net.minecraft.world.entity.player.Player player = null;
        net.minecraft.world.entity.LivingEntity entity  = null;
        net.minecraft.world.entity.LivingEntity target  = null;
        net.minecraft.core.BlockPos blockPos             = null;

        for (Object arg : args) {
            if (arg == null) continue;
            if (arg instanceof net.minecraft.world.entity.player.Player pe) {
                if (player == null) player = pe;
            } else if (arg instanceof net.minecraft.world.entity.LivingEntity le) {
                if (entity == null) entity = le;
                else if (target == null) target = le;
            } else if (arg instanceof net.minecraft.core.BlockPos bp) {
                blockPos = bp;
            }
        }

        if (entity == null && player != null) entity = player;

        sb.append("koper._ctx = {}\n");

        if (player != null) { appendEntitySetup(sb, "_kp", player); sb.append("koper._ctx.player = _kp\n"); }
        if (entity != null) { appendEntitySetup(sb, "_ke", entity); sb.append("koper._ctx.entity = _ke\n"); }
        if (target != null) { appendEntitySetup(sb, "_kt", target); sb.append("koper._ctx.target = _kt\n"); }

        if (blockPos != null) {
            sb.append("koper._ctx.pos = {x=").append(blockPos.getX())
              .append(",y=").append(blockPos.getY())
              .append(",z=").append(blockPos.getZ()).append("}\n");
        } else if (player != null) {
            net.minecraft.core.BlockPos pp = player.blockPosition();
            sb.append("koper._ctx.pos = {x=").append(pp.getX())
              .append(",y=").append(pp.getY())
              .append(",z=").append(pp.getZ()).append("}\n");
        } else if (entity != null) {
            net.minecraft.core.BlockPos ep = entity.blockPosition();
            sb.append("koper._ctx.pos = {x=").append(ep.getX())
              .append(",y=").append(ep.getY())
              .append(",z=").append(ep.getZ()).append("}\n");
        }

        String callArgs = buildCallArgs(event);
        sb.append("local _ks = _KOPER_SCRIPTS and _KOPER_SCRIPTS[\"").append(absPath).append("\"]\n");
        sb.append("local _fn = (_ks and _ks.").append(fnName).append(") or ").append(fnName).append("\n");
        sb.append("if _fn then _fn(").append(callArgs).append(") end\n");
    }

    // direct appends instead of String.format — kills Formatter allocations per entity per call
    private static void appendEntitySetup(StringBuilder sb, String v, net.minecraft.world.entity.LivingEntity e) {
        String typeId    = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        String name      = e.getName().getString().replace("\\", "\\\\").replace("\"", "\\\"");
        boolean isPlayer = e instanceof net.minecraft.world.entity.player.Player;
        net.minecraft.world.phys.Vec3 look = e.getViewVector(1.0f);

        sb.append("local ").append(v).append(" = koper.Entity.new(\"").append(typeId).append("\")\n");
        sb.append(v).append("._health = ").append(e.getHealth()).append('\n');
        sb.append(v).append("._max_health = ").append(e.getMaxHealth()).append('\n');
        sb.append(v).append("._pos = {x=").append(e.getX()).append(",y=").append(e.getY()).append(",z=").append(e.getZ()).append("}\n");
        sb.append(v).append("._name = \"").append(name).append("\"\n");
        sb.append(v).append("._uuid_str = \"").append(e.getStringUUID()).append("\"\n");
        sb.append(v).append("._entity_id = ").append(e.getId()).append('\n');
        sb.append(v).append("._is_player = ").append(isPlayer).append('\n');
        sb.append(v).append("._look_dir = {x=").append(look.x).append(",y=").append(look.y).append(",z=").append(look.z).append("}\n");
        sb.append(v).append("._sneaking  = ").append(e.isShiftKeyDown()).append('\n');
        sb.append(v).append("._sprinting = ").append(e.isSprinting()).append('\n');
        sb.append(v).append("._on_ground = ").append(e.onGround()).append('\n');
        sb.append(v).append("._in_water  = ").append(e.isInWater()).append('\n');
    }

    private static String buildCallArgs(ScriptEvent event) {
        return switch (event) {
            case ON_USE                               -> "koper._ctx.player, koper._ctx.pos";
            case ON_PLACE, ON_BREAK, ON_STEP          -> "koper._ctx.player, koper._ctx.pos";
            // target is in entity slot — first non-player LivingEntity arg goes to entity, not target
            case ON_HIT                               -> "koper._ctx.player, koper._ctx.entity";
            case ON_DAMAGE                            -> "koper._ctx.entity, koper._ctx.target";
            case ON_SPAWN, ON_TICK, ON_DEATH, ON_AI   -> "koper._ctx.entity";
            case ON_INTERACT                          -> "koper._ctx.entity, koper._ctx.player";
            case ON_TARGET                            -> "koper._ctx.entity";
            case ON_EQUIP, ON_UNEQUIP, ON_CONSUME     -> "koper._ctx.player";
            case ON_CRAFT                             -> "koper._ctx.player";
        };
    }

    private static String getLuaFunctionName(ScriptEvent event) {
        return switch (event) {
            case ON_USE      -> "on_use";
            case ON_TICK     -> "on_tick";
            case ON_HIT      -> "on_hit";
            case ON_DAMAGE   -> "on_damage";
            case ON_DEATH    -> "on_death";
            case ON_PLACE    -> "on_place";
            case ON_BREAK    -> "on_break";
            case ON_STEP     -> "on_step";
            case ON_SPAWN    -> "on_spawn";
            case ON_INTERACT -> "on_interact";
            case ON_TARGET   -> "on_target";
            case ON_EQUIP    -> "on_equip";
            case ON_UNEQUIP  -> "on_unequip";
            case ON_CONSUME  -> "on_consume";
            case ON_CRAFT    -> "on_craft";
            case ON_AI       -> "on_ai";
        };
    }
}
