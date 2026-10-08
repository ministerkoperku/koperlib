package com.koper.koper_lib.scripting;

import com.koper.koper_lib.KoperLib;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.item.ItemEntity;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

// parses the Lua koper._cmds queue (newline-separated "type:args" entries) and executes them
public final class ScriptCommandDispatcher {

    private ScriptCommandDispatcher() {}

    // ids bring their own colon (minecraft:gold_block), so in "x,y,z:minecraft:gold_block" the id can
    // only be the middle: peel the fixed fields off both ends and whatever is left is the id. splitting
    // from one side broke every command with a namespaced id — set_block, summon, drop_item, sounds,
    // particles and effects all failed with "For input string: ...:minecraft"
    static String[] koperCut(String rest, int left, int right) {
        java.util.List<String> out = new java.util.ArrayList<>(left + right + 1);
        String middle = rest;
        for (int i = 0; i < left; i++) {
            int at = middle.indexOf(':');
            if (at < 0) return null;
            out.add(middle.substring(0, at));
            middle = middle.substring(at + 1);
        }
        String[] tail = new String[right];
        for (int i = right - 1; i >= 0; i--) {
            int at = middle.lastIndexOf(':');
            if (at < 0) return null;
            tail[i] = middle.substring(at + 1);
            middle = middle.substring(0, at);
        }
        out.add(middle);
        out.addAll(java.util.List.of(tail));
        return out.toArray(new String[0]);
    }

    // called by Rust synchronously via Panama upcall
    // CRITICAL: must NEVER throw — uncaught exception in upcall = "Unrecoverable" JVM crash
    // Rust &str is NOT null-terminated — use explicit byte-length, never getString
    public static void dispatchUpcall(MemorySegment cmdPtr, int cmdLen) {
        try {
            if (cmdLen <= 0) return;
            byte[] bytes = cmdPtr.reinterpret(cmdLen).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            String cmd = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            ScriptCommand parsed = parse(cmd);
            if (parsed == null) return;
            if (!requiresServer(parsed)) {
                executeServerIndependent(parsed);
                return;
            }
            MinecraftServer server = UniversalScriptEngine.getCurrentServer();
            if (server != null) execute(parsed, server);
        } catch (Throwable t) {
            try { KoperLib.LOGGER.warn("[ScriptCmd] upcall failed: {}", t.getMessage()); }
            catch (Throwable ignored) {} // if logger also fails, silently eat it — DO NOT rethrow
        }
    }

    // a pack looping inside on_tick can hand us an unbounded pile in one go. counting commands is
    // the wrong knob — a teleport and a bad-uuid line that builds an exception differ 100x in cost.
    // so we spend a slice of the tick and whatever didn't fit waits for the next one.
    private static final long SLICE_NANOS  = 4_000_000L; // 4ms = 8% of a 50ms tick
    // the clock every few commands. it was every 64, on the idea that nanoTime per line costs more
    // than the work: it is ~25 ns against a command's microseconds, and 64 heavy commands (a set_block
    // with its neighbour updates is ~0.4 ms) ran 27 ms past a 4 ms slice before the first look
    private static final int  CHECK_EVERY  = 4;
    private static final int  BACKLOG_CAP  = 200_000;    // past this the pack is broken, stop feeding the leak
    private static final java.util.Queue<String> BACKLOG = new java.util.ArrayDeque<>();
    private static boolean warnedBacklog;

    public static void dispatch(String cmds, Object[] scriptArgs) {
        MinecraftServer server = UniversalScriptEngine.getCurrentServer();
        if (cmds.isBlank()) return;
        if (server == null) {
            for (String line : cmds.split("\n")) {
                ScriptCommand parsed = parse(line);
                if (parsed != null && !requiresServer(parsed)) executeServerIndependent(parsed);
            }
            return;
        }

        long until = System.nanoTime() + SLICE_NANOS;
        int done = 0, spilled = 0;
        boolean spent = false;

        for (String line : cmds.split("\n")) {
            if (line.isBlank()) continue;
            if (spent) {
                if (BACKLOG.size() < BACKLOG_CAP) { BACKLOG.add(line); spilled++; }
                continue;
            }
            runOne(line, server);
            if (++done % CHECK_EVERY == 0 && System.nanoTime() >= until) spent = true;
        }

        if (spilled > 0 && !warnedBacklog) {
            warnedBacklog = true;
            KoperLib.LOGGER.warn("[ScriptCmd] one dispatch outran its {}ms slice after {} commands — {} queued"
                + " for later ticks. some script is emitting way too much per call",
                SLICE_NANOS / 1_000_000L, done, spilled);
        }
    }

    static boolean requiresServer(ScriptCommand command) {
        return !(command instanceof ScriptCommand.KfxGraphDeclare);
    }

    private static void executeServerIndependent(ScriptCommand command) {
        if (command instanceof ScriptCommand.KfxGraphDeclare(var graphJson)) {
            com.koper.koper_lib.api.FullpackAddons.declareEffectGraph(graphJson);
        }
    }

    // drained from the server tick so a spill costs later ticks a slice, not one tick everything
    public static void drainBacklog() {
        MinecraftServer server = UniversalScriptEngine.getCurrentServer();
        if (server == null || BACKLOG.isEmpty()) return;
        long until = System.nanoTime() + SLICE_NANOS;
        int done = 0;
        String line;
        while ((line = BACKLOG.poll()) != null) {
            runOne(line, server);
            if (++done % CHECK_EVERY == 0 && System.nanoTime() >= until) break;
        }
        if (BACKLOG.isEmpty()) warnedBacklog = false;
    }

    public static int backlogSize() { return BACKLOG.size(); }

    public static void clearBacklog() { BACKLOG.clear(); warnedBacklog = false; }

    private static void runOne(String line, MinecraftServer server) {
        try {
            ScriptCommand cmd = parse(line.trim());
            if (cmd != null) execute(cmd, server);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[ScriptCmd] Failed to apply command '{}': {}", line, e.getMessage());
        }
    }

    // ── parser ────────────────────────────────────────────────────────────────

    static ScriptCommand parse(String cmd) {
        if (cmd.startsWith("teleport:")) {
            String rest = cmd.substring("teleport:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            String uuid = rest.substring(0, colon);
            String[] xyz = rest.substring(colon + 1).split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.Teleport(uuid,
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]));

        } else if (cmd.startsWith("play_sound:")) {
            String rest = cmd.substring("play_sound:".length());
            String[] parts = koperCut(rest, 1, 1);
            if (parts == null) return null;
            String[] xyz = parts[0].split(",", 3);
            if (xyz.length < 3) return null;
            String[] vp = parts[2].split(",", 2);
            return new ScriptCommand.PlaySound(
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                parts[1],
                vp.length > 0 ? Float.parseFloat(vp[0]) : 1.0f,
                vp.length > 1 ? Float.parseFloat(vp[1]) : 1.0f);

        } else if (cmd.startsWith("particle:")) {
            String rest = cmd.substring("particle:".length());
            String[] parts = koperCut(rest, 0, 2);
            if (parts == null) return null;
            String[] xyz = parts[1].split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.Particle(parts[0],
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                Integer.parseInt(parts[2].trim()));

        } else if (cmd.startsWith("effect_remove:")) {
            String rest = cmd.substring("effect_remove:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.EffectRemove(rest.substring(0, colon), rest.substring(colon + 1));

        } else if (cmd.startsWith("effect:")) {
            String rest = cmd.substring("effect:".length());
            // lua sends uuid:id:ticks:amp; older callers sent uuid:id:ticks,amp
            String[] parts = koperCut(rest, 1, 1);
            if (parts == null) return null;
            String id = parts[1];
            int ticks, amp;
            if (parts[2].contains(",")) {
                String[] ta = parts[2].split(",", 2);
                ticks = Integer.parseInt(ta[0].trim());
                amp = Integer.parseInt(ta[1].trim());
            } else {
                int at = id.lastIndexOf(':');
                if (at < 0) return null;
                ticks = Integer.parseInt(id.substring(at + 1).trim());
                id = id.substring(0, at);
                amp = Integer.parseInt(parts[2].trim());
            }
            return new ScriptCommand.Effect(parts[0], id, ticks, amp);

        } else if (cmd.startsWith("set_block:")) {
            String rest = cmd.substring("set_block:".length());
            String[] parts = koperCut(rest, 1, 0);
            if (parts == null) return null;
            String[] xyz = parts[0].split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.SetBlock(
                (int) Math.floor(Double.parseDouble(xyz[0])),
                (int) Math.floor(Double.parseDouble(xyz[1])),
                (int) Math.floor(Double.parseDouble(xyz[2])),
                parts[1].trim());

        } else if (cmd.startsWith("give:")) {
            // give:uuid:itemId:count — itemId has its own colon (minecraft:x), so split from the ends
            String rest = cmd.substring("give:".length());
            int firstColon = rest.indexOf(':');
            int lastColon  = rest.lastIndexOf(':');
            if (firstColon < 0 || lastColon <= firstColon) return null;
            String uuid   = rest.substring(0, firstColon);
            String itemId = rest.substring(firstColon + 1, lastColon);
            return new ScriptCommand.Give(uuid, itemId, Integer.parseInt(rest.substring(lastColon + 1).trim()));

        } else if (cmd.startsWith("msg:")) {
            String rest = cmd.substring("msg:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            String text = rest.substring(colon + 1).replace("\\n", "\n").replace("\\:", ":");
            return new ScriptCommand.Msg(rest.substring(0, colon), text);

        } else if (cmd.startsWith("summon:")) {
            String rest = cmd.substring("summon:".length());
            String[] parts = koperCut(rest, 0, 1);
            if (parts == null) return null;
            String[] xyz = parts[1].split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.Summon(parts[0],
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]));

        } else if (cmd.startsWith("run_cmd:")) {
            return new ScriptCommand.RunCmd(cmd.substring("run_cmd:".length()));

        } else if (cmd.startsWith("explosion:")) {
            // explosion:x,y,z:power:fire
            String rest = cmd.substring("explosion:".length());
            String[] parts = rest.split(":", 3);
            if (parts.length < 3) return null;
            String[] xyz = parts[0].split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.Explosion(
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                Float.parseFloat(parts[1]), Boolean.parseBoolean(parts[2]));

        } else if (cmd.startsWith("weather:")) {
            return new ScriptCommand.Weather(cmd.substring("weather:".length()).trim());

        } else if (cmd.startsWith("spawn_xp:")) {
            // spawn_xp:x,y,z:amount
            String rest = cmd.substring("spawn_xp:".length());
            int colon = rest.lastIndexOf(':');
            if (colon < 0) return null;
            String[] xyz = rest.substring(0, colon).split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.SpawnXp(
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                Integer.parseInt(rest.substring(colon + 1).trim()));

        } else if (cmd.startsWith("title:")) {
            // title:uuid:title:subtitle
            String rest = cmd.substring("title:".length());
            String[] parts = rest.split(":", 3);
            if (parts.length < 2) return null;
            String title    = parts.length > 1 ? parts[1].replace("\\:", ":") : "";
            String subtitle = parts.length > 2 ? parts[2].replace("\\:", ":") : "";
            return new ScriptCommand.Title(parts[0], title, subtitle);

        } else if (cmd.startsWith("set_health:")) {
            String rest = cmd.substring("set_health:".length());
            int colon = rest.lastIndexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.SetHealth(rest.substring(0, colon), Float.parseFloat(rest.substring(colon + 1)));

        } else if (cmd.startsWith("kill_entity:")) {
            return new ScriptCommand.KillEntity(cmd.substring("kill_entity:".length()).trim());

        } else if (cmd.startsWith("ignite:")) {
            String rest = cmd.substring("ignite:".length());
            int colon = rest.lastIndexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.Ignite(rest.substring(0, colon), Integer.parseInt(rest.substring(colon + 1).trim()));

        } else if (cmd.startsWith("freeze:")) {
            String rest = cmd.substring("freeze:".length());
            int colon = rest.lastIndexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.Freeze(rest.substring(0, colon), Integer.parseInt(rest.substring(colon + 1).trim()));

        } else if (cmd.startsWith("kick:")) {
            String rest = cmd.substring("kick:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.Kick(rest.substring(0, colon), rest.substring(colon + 1).replace("\\:", ":"));

        } else if (cmd.startsWith("set_gamemode:")) {
            String rest = cmd.substring("set_gamemode:".length());
            int colon = rest.lastIndexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.SetGamemode(rest.substring(0, colon), rest.substring(colon + 1).trim());

        } else if (cmd.startsWith("set_name:")) {
            String rest = cmd.substring("set_name:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.SetName(rest.substring(0, colon), rest.substring(colon + 1).replace("\\:", ":"));

        } else if (cmd.startsWith("launch:")) {
            // launch:{uuid}:{vx},{vy},{vz}
            String rest = cmd.substring("launch:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            String[] v = rest.substring(colon + 1).split(",", 3);
            if (v.length < 3) return null;
            return new ScriptCommand.LaunchEntity(rest.substring(0, colon),
                Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]));

        } else if (cmd.startsWith("spawn_proj:")) {
            // spawn_proj:{id}:{x},{y},{z}:{vx},{vy},{vz}
            String rest = cmd.substring("spawn_proj:".length());
            String[] parts = koperCut(rest, 0, 2);
            if (parts == null) return null;
            String[] xyz = parts[1].split(",", 3);
            String[] vel = parts[2].split(",", 3);
            if (xyz.length < 3 || vel.length < 3) return null;
            return new ScriptCommand.SpawnProjectile(parts[0],
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                Double.parseDouble(vel[0]), Double.parseDouble(vel[1]), Double.parseDouble(vel[2]));

        } else if (cmd.startsWith("particle_burst:")) {
            // particle_burst:{id}:{x},{y},{z}:{count}:{spread}
            String rest = cmd.substring("particle_burst:".length());
            String[] parts = koperCut(rest, 0, 3);
            if (parts == null) return null;
            String[] xyz = parts[1].split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.ParticleBurst(parts[0],
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                Integer.parseInt(parts[2].trim()), Double.parseDouble(parts[3].trim()));

        } else if (cmd.startsWith("particle_line:")) {
            // particle_line:{id}:{x1},{y1},{z1}:{x2},{y2},{z2}:{steps}
            String rest = cmd.substring("particle_line:".length());
            String[] parts = koperCut(rest, 0, 3);
            if (parts == null) return null;
            String[] p1 = parts[1].split(",", 3);
            String[] p2 = parts[2].split(",", 3);
            if (p1.length < 3 || p2.length < 3) return null;
            return new ScriptCommand.ParticleLine(parts[0],
                Double.parseDouble(p1[0]), Double.parseDouble(p1[1]), Double.parseDouble(p1[2]),
                Double.parseDouble(p2[0]), Double.parseDouble(p2[1]), Double.parseDouble(p2[2]),
                Integer.parseInt(parts[3].trim()));

        } else if (cmd.startsWith("fill:")) {
            // fill:{x1},{y1},{z1}:{x2},{y2},{z2}:{blockId}
            String rest = cmd.substring("fill:".length());
            String[] halves = koperCut(rest, 2, 0);
            if (halves == null) return null;
            String blockId = halves[2].trim();
            String[] p1 = halves[0].split(",", 3);
            String[] p2 = halves[1].split(",", 3);
            if (p1.length < 3 || p2.length < 3) return null;
            return new ScriptCommand.FillBlocks(
                (int) Double.parseDouble(p1[0]), (int) Double.parseDouble(p1[1]), (int) Double.parseDouble(p1[2]),
                (int) Double.parseDouble(p2[0]), (int) Double.parseDouble(p2[1]), (int) Double.parseDouble(p2[2]),
                blockId);

        } else if (cmd.startsWith("drop_item:")) {
            // drop_item:{x},{y},{z}:{itemId}:{count}
            String rest = cmd.substring("drop_item:".length());
            String[] parts = koperCut(rest, 1, 1);
            if (parts == null) return null;
            String[] xyz = parts[0].split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.DropItem(
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                parts[1], Integer.parseInt(parts[2].trim()));

        } else if (cmd.startsWith("net_broadcast:")) {
            String msg = cmd.substring("net_broadcast:".length()).replace("\\:", ":");
            return new ScriptCommand.NetBroadcast(msg);

        } else if (cmd.startsWith("title_all:")) {
            // title_all:{title}:{subtitle}
            String rest = cmd.substring("title_all:".length());
            int colon = rest.indexOf(':');
            String title    = colon >= 0 ? rest.substring(0, colon).replace("\\:", ":") : rest.replace("\\:", ":");
            String subtitle = colon >= 0 ? rest.substring(colon + 1).replace("\\:", ":") : "";
            return new ScriptCommand.TitleAll(title, subtitle);

        } else if (cmd.startsWith("set_target:")) {
            String rest = cmd.substring("set_target:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.SetTarget(rest.substring(0, colon), rest.substring(colon + 1));

        } else if (cmd.startsWith("attack:")) {
            String rest = cmd.substring("attack:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.Attack(rest.substring(0, colon), rest.substring(colon + 1));

        } else if (cmd.startsWith("play_anim:")) {
            String rest = cmd.substring("play_anim:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.PlayAnim(rest.substring(0, colon), rest.substring(colon + 1));

        } else if (cmd.startsWith("move_to:")) {
            // move_to:<uuid>:<x,y,z>:<speed>
            String[] parts = cmd.substring("move_to:".length()).split(":", 3);
            if (parts.length < 3) return null;
            String[] xyz = parts[1].split(",", 3);
            if (xyz.length < 3) return null;
            return new ScriptCommand.MoveTo(parts[0],
                Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                Double.parseDouble(parts[2]));

        } else if (cmd.startsWith("set_dura:")) {
            String[] parts = cmd.substring("set_dura:".length()).split(":", 3);
            if (parts.length < 3) return null;
            return new ScriptCommand.SetDurability(parts[0], parts[1], Integer.parseInt(parts[2]));

        } else if (cmd.startsWith("tag_add:")) {
            String rest = cmd.substring("tag_add:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.TagAdd(rest.substring(0, colon), rest.substring(colon + 1));

        } else if (cmd.startsWith("tag_remove:")) {
            String rest = cmd.substring("tag_remove:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            return new ScriptCommand.TagRemove(rest.substring(0, colon), rest.substring(colon + 1));

        } else if (cmd.startsWith("kontra_force:")) {
            // kontra_force:{id}:{fx},{fy},{fz}
            String rest = cmd.substring("kontra_force:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            String[] v = rest.substring(colon + 1).split(",", 3);
            if (v.length < 3) return null;
            return new ScriptCommand.KontraForce(Long.parseLong(rest.substring(0, colon)),
                Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]));

        } else if (cmd.startsWith("kontra_impulse:")) {
            String rest = cmd.substring("kontra_impulse:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) return null;
            String[] v = rest.substring(colon + 1).split(",", 3);
            if (v.length < 3) return null;
            return new ScriptCommand.KontraImpulse(Long.parseLong(rest.substring(0, colon)),
                Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]));

        } else if (cmd.startsWith("kontra_selfright:")) {
            return new ScriptCommand.KontraSelfRight(Long.parseLong(cmd.substring("kontra_selfright:".length()).trim()));

        } else if (cmd.startsWith("kontra_destroy:")) {
            return new ScriptCommand.KontraDestroy(Long.parseLong(cmd.substring("kontra_destroy:".length()).trim()));

        } else if (cmd.startsWith("kontra_restore:")) {
            return new ScriptCommand.KontraRestore(Long.parseLong(cmd.substring("kontra_restore:".length()).trim()));

        } else if (cmd.startsWith("kfx_graph_declare:")) {
            List<String> fields = decodeKfxFields(cmd, "kfx_graph_declare", 1);
            return fields == null ? null : new ScriptCommand.KfxGraphDeclare(fields.get(0));

        } else if (cmd.startsWith("kfx_graph_play:")) {
            List<String> fields = decodeKfxFields(cmd, "kfx_graph_play", 3);
            return fields == null ? null : new ScriptCommand.KfxGraphPlay(
                Long.parseLong(fields.get(0)), fields.get(1), fields.get(2));

        } else if (cmd.startsWith("kfx_handle_set:")) {
            List<String> fields = decodeKfxFields(cmd, "kfx_handle_set", 3);
            return fields == null ? null : new ScriptCommand.KfxHandleSet(
                Long.parseLong(fields.get(0)), fields.get(1), fields.get(2));

        } else if (cmd.startsWith("kfx_handle_anchor:")) {
            List<String> fields = decodeKfxFields(cmd, "kfx_handle_anchor", 3);
            return fields == null ? null : new ScriptCommand.KfxHandleAnchor(
                Long.parseLong(fields.get(0)), fields.get(1), fields.get(2));

        } else if (cmd.startsWith("kfx_handle_detach:")) {
            List<String> fields = decodeKfxFields(cmd, "kfx_handle_detach", 1);
            return fields == null ? null : new ScriptCommand.KfxHandleDetach(Long.parseLong(fields.get(0)));

        } else if (cmd.startsWith("kfx_handle_signal:")) {
            List<String> fields = decodeKfxFields(cmd, "kfx_handle_signal", 3);
            return fields == null ? null : new ScriptCommand.KfxHandleSignal(
                Long.parseLong(fields.get(0)), fields.get(1), fields.get(2));

        } else if (cmd.startsWith("kfx_spawn:")) {
            // kfx_spawn:{id}:{sx},{sy},{sz}:{ex},{ey},{ez}
            String rest = cmd.substring("kfx_spawn:".length());
            String[] parts = koperCut(rest, 0, 2);
            if (parts == null) return null;
            String id = parts[0];
            String[] s = parts[1].split(",", 3);
            String[] e = parts[2].split(",", 3);
            if (s.length < 3 || e.length < 3) return null;
            return new ScriptCommand.KfxSpawn(id,
                Double.parseDouble(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]),
                Double.parseDouble(e[0]), Double.parseDouble(e[1]), Double.parseDouble(e[2]));

        } else if (cmd.startsWith("kfx_update:")) {
            // kfx_update:{fxId}:{sx},{sy},{sz}:{ex},{ey},{ez}
            String rest = cmd.substring("kfx_update:".length());
            String[] parts = rest.split(":", 3);
            if (parts.length < 3) return null;
            String[] s = parts[1].split(",", 3);
            String[] e = parts[2].split(",", 3);
            if (s.length < 3 || e.length < 3) return null;
            return new ScriptCommand.KfxUpdate(Long.parseLong(parts[0]),
                Double.parseDouble(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]),
                Double.parseDouble(e[0]), Double.parseDouble(e[1]), Double.parseDouble(e[2]));

        } else if (cmd.startsWith("kfx_stop:")) {
            return new ScriptCommand.KfxStop(Long.parseLong(cmd.substring("kfx_stop:".length()).trim()));

        } else if (cmd.startsWith("kfx_attach:")) {
            // kfx_attach:{fxId}:{entityUuid}:{ox},{oy},{oz}:{ex},{ey},{ez}:{endRelative}
            String rest = cmd.substring("kfx_attach:".length());
            String[] parts = rest.split(":", 5);
            if (parts.length < 4) return null;
            String[] o = parts[2].split(",", 3);
            String[] e = parts[3].split(",", 3);
            if (o.length < 3 || e.length < 3) return null;
            return new ScriptCommand.KfxAttach(Long.parseLong(parts[0]), parts[1],
                Double.parseDouble(o[0]), Double.parseDouble(o[1]), Double.parseDouble(o[2]),
                Double.parseDouble(e[0]), Double.parseDouble(e[1]), Double.parseDouble(e[2]),
                parts.length > 4 && Boolean.parseBoolean(parts[4]));

        } else if (cmd.startsWith("kfx_spawn_json:")) {
            // kfx_spawn_json:{sx},{sy},{sz}:{ex},{ey},{ez}:{percent-escaped-json}
            String rest = cmd.substring("kfx_spawn_json:".length());
            String[] parts = rest.split(":", 3);
            if (parts.length < 3) return null;
            String[] s = parts[0].split(",", 3);
            String[] e = parts[1].split(",", 3);
            if (s.length < 3 || e.length < 3) return null;
            return new ScriptCommand.KfxProgramJson(cmdUnescape(parts[2]),
                Double.parseDouble(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]),
                Double.parseDouble(e[0]), Double.parseDouble(e[1]), Double.parseDouble(e[2]));

        } else if (cmd.startsWith("gui_consume:")) {
            String[] p = cmd.substring("gui_consume:".length()).split(":");
            if (p.length < 3) return null;
            return new ScriptCommand.GuiConsume(p[0], Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));

        } else if (cmd.startsWith("gui_clear_all:")) {
            return new ScriptCommand.GuiClearAll(cmd.substring("gui_clear_all:".length()).trim());

        } else if (cmd.startsWith("gui_clear:")) {
            String rest = cmd.substring("gui_clear:".length());
            int c = rest.lastIndexOf(':');
            if (c < 0) return null;
            return new ScriptCommand.GuiClearSlot(rest.substring(0, c), Integer.parseInt(rest.substring(c + 1).trim()));

        } else if (cmd.startsWith("gui_set_slot:")) {
            // gui_set_slot:uuid:slot:itemId:count — itemId carries its own colon
            String rest = cmd.substring("gui_set_slot:".length());
            int c1 = rest.indexOf(':');
            if (c1 < 0) return null;
            String r2 = rest.substring(c1 + 1); // slot:itemId:count
            int c2 = r2.indexOf(':');
            int last = r2.lastIndexOf(':');
            if (c2 < 0 || last <= c2) return null;
            return new ScriptCommand.GuiSetSlot(rest.substring(0, c1),
                Integer.parseInt(r2.substring(0, c2).trim()),
                r2.substring(c2 + 1, last),
                Integer.parseInt(r2.substring(last + 1).trim()));

        } else if (cmd.startsWith("gui_wset:")) {
            // gui_wset:uuid:widget:value — value is the rest (may hold colons for label text)
            String rest = cmd.substring("gui_wset:".length());
            int c1 = rest.indexOf(':');
            if (c1 < 0) return null;
            String r2 = rest.substring(c1 + 1);
            int c2 = r2.indexOf(':');
            if (c2 < 0) return null;
            return new ScriptCommand.GuiWidgetSet(rest.substring(0, c1), r2.substring(0, c2), r2.substring(c2 + 1));

        } else if (cmd.startsWith("gui_open:")) {
            // gui_open:uuid:guiId — guiId carries its own colon
            String rest = cmd.substring("gui_open:".length());
            int c = rest.indexOf(':');
            if (c < 0) return null;
            return new ScriptCommand.GuiOpen(rest.substring(0, c), rest.substring(c + 1));

        } else if (cmd.startsWith("gui_close:")) {
            return new ScriptCommand.GuiClose(cmd.substring("gui_close:".length()).trim());

        } else if (cmd.startsWith("gui_hud:")) {
            // gui_hud:uuid:guiId:show — guiId in the middle, show (0/1) last
            String rest = cmd.substring("gui_hud:".length());
            int c1 = rest.indexOf(':');
            int last = rest.lastIndexOf(':');
            if (c1 < 0 || last <= c1) return null;
            return new ScriptCommand.GuiHud(rest.substring(0, c1), rest.substring(c1 + 1, last),
                "1".equals(rest.substring(last + 1).trim()));
        }
        return null;
    }

    // ── executor ─────────────────────────────────────────────────────────────

    private static void execute(ScriptCommand cmd, MinecraftServer server) {
        switch (cmd) {
            case ScriptCommand.Teleport(var uuid, var x, var y, var z) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target instanceof ServerPlayer sp) {
                    sp.connection.teleport(x, y, z, sp.getYRot(), sp.getXRot());
                } else if (target != null) {
                    target.snapTo(x, y, z, target.getYRot(), target.getXRot());
                }
            }
            case ScriptCommand.PlaySound(var x, var y, var z, var id, var vol, var pitch) -> {
                Identifier sid = Identifier.tryParse(id);
                if (sid == null) return;
                SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(sid);
                if (sound == null) sound = SoundEvent.createVariableRangeEvent(sid);
                server.overworld().playSound(null, x, y, z, sound, SoundSource.PLAYERS, vol, pitch);
            }
            case ScriptCommand.Particle(var id, var x, var y, var z, var count) -> {
                Identifier pid = Identifier.tryParse(id);
                if (pid == null) return;
                var type = BuiltInRegistries.PARTICLE_TYPE.getValue(pid);
                if (type instanceof SimpleParticleType dp) {
                    ServerLevel level = findNearestPlayerWorld(server, new Vec3(x, y, z));
                    level.sendParticles(dp, x, y, z, count, 0.5, 0.5, 0.5, 0.0);
                }
            }
            case ScriptCommand.Effect(var uuid, var effectId, var ticks, var amplifier) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target == null) return;
                Identifier eid = Identifier.tryParse(effectId);
                if (eid == null) return;
                MobEffect mobEff = BuiltInRegistries.MOB_EFFECT.getValue(eid);
                if (mobEff == null) return;
                Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(mobEff);
                target.addEffect(new MobEffectInstance(effect, ticks, amplifier));
            }
            case ScriptCommand.EffectRemove(var uuid, var effectId) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target == null) return;
                Identifier eid = Identifier.tryParse(effectId);
                if (eid == null) return;
                MobEffect mobEff = BuiltInRegistries.MOB_EFFECT.getValue(eid);
                if (mobEff != null) target.removeEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(mobEff));
            }
            case ScriptCommand.SetBlock(var x, var y, var z, var blockId) -> {
                Identifier bid = Identifier.tryParse(blockId);
                if (bid == null) return;
                var block = BuiltInRegistries.BLOCK.getValue(bid);
                if (block == null) return;
                server.overworld().setBlock(BlockPos.containing(x, y, z), block.defaultBlockState(), 3);
            }
            case ScriptCommand.Give(var uuid, var itemId, var count) -> {
                ServerPlayer player = findPlayer(server, uuid);
                if (player == null) return;
                Identifier iid = Identifier.tryParse(itemId);
                if (iid == null) return;
                var item = BuiltInRegistries.ITEM.getValue(iid);
                if (item == null) return;
                player.getInventory().add(new ItemStack(item, count));
            }
            case ScriptCommand.Msg(var uuid, var msg) -> {
                ServerPlayer player = findPlayer(server, uuid);
                if (player != null) player.sendSystemMessage(Component.literal(msg));
            }
            case ScriptCommand.Summon(var entityId, var x, var y, var z) -> {
                Identifier eid = Identifier.tryParse(entityId);
                if (eid == null) return;
                var type = BuiltInRegistries.ENTITY_TYPE.getValue(eid);
                if (type == null) return;
                ServerLevel level = server.overworld();
                var entity = type.create(level, null, BlockPos.containing(x, y, z), EntitySpawnReason.COMMAND, false, false);
                if (entity != null) {
                    entity.setPos(x, y, z);
                    level.addFreshEntity(entity);
                }
            }
            case ScriptCommand.RunCmd(var command) -> {
                try {
                    server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
                } catch (Exception e) {
                    KoperLib.LOGGER.warn("[ScriptCmd] run_cmd failed '{}': {}", command, e.getMessage());
                }
            }
            case ScriptCommand.Explosion(var x, var y, var z, var power, var fire) -> {
                ServerLevel level = findNearestPlayerWorld(server, new Vec3(x, y, z));
                level.explode(null, x, y, z, power,
                    fire ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.BLOCK);
            }
            case ScriptCommand.Weather(var type) -> {
                // just run /weather — handles clear/rain/thunder cleanly
                try {
                    String wtype = switch (type.toLowerCase()) {
                        case "clear", "sunny"  -> "clear";
                        case "rain", "rainy"   -> "rain";
                        case "thunder", "storm" -> "thunder";
                        default -> type;
                    };
                    server.getCommands().getDispatcher().execute("weather " + wtype, server.createCommandSourceStack());
                } catch (Exception e) {
                    KoperLib.LOGGER.warn("[ScriptCmd] weather '{}' failed: {}", type, e.getMessage());
                }
            }
            case ScriptCommand.SpawnXp(var x, var y, var z, var amount) -> {
                ServerLevel level = findNearestPlayerWorld(server, new Vec3(x, y, z));
                net.minecraft.world.entity.ExperienceOrb.award(level, new Vec3(x, y, z), amount);
            }
            case ScriptCommand.Title(var uuid, var title, var subtitle) -> {
                ServerPlayer player = findPlayer(server, uuid);
                if (player == null) return;
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
                if (!subtitle.isEmpty()) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
                }
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 70, 20));
            }
            case ScriptCommand.SetHealth(var uuid, var health) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) target.setHealth(Math.max(0.0f, health));
            }
            case ScriptCommand.KillEntity(var uuid) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) target.kill(server.overworld());
            }
            case ScriptCommand.Ignite(var uuid, var ticks) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) target.setRemainingFireTicks(ticks);
            }
            case ScriptCommand.Freeze(var uuid, var ticks) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) target.setTicksFrozen(ticks);
            }
            case ScriptCommand.Kick(var uuid, var reason) -> {
                ServerPlayer player = findPlayer(server, uuid);
                if (player != null) player.connection.disconnect(Component.literal(reason));
            }
            case ScriptCommand.SetGamemode(var uuid, var mode) -> {
                ServerPlayer player = findPlayer(server, uuid);
                if (player == null) return;
                GameType gt = switch (mode.toLowerCase()) {
                    case "survival", "s", "0"  -> GameType.SURVIVAL;
                    case "creative", "c", "1"  -> GameType.CREATIVE;
                    case "adventure", "a", "2" -> GameType.ADVENTURE;
                    case "spectator", "sp", "3" -> GameType.SPECTATOR;
                    default -> null;
                };
                if (gt != null) player.setGameMode(gt);
            }
            case ScriptCommand.SetName(var uuid, var name) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) {
                    target.setCustomName(Component.literal(name));
                    target.setCustomNameVisible(!name.isEmpty());
                }
            }
            case ScriptCommand.LaunchEntity(var uuid, var vx, var vy, var vz) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) target.setDeltaMovement(vx, vy, vz);
            }
            case ScriptCommand.SpawnProjectile(var entityType, var x, var y, var z, var vx, var vy, var vz) -> {
                Identifier eid = Identifier.tryParse(entityType);
                if (eid == null) return;
                var type = BuiltInRegistries.ENTITY_TYPE.getValue(eid);
                if (type == null) return;
                ServerLevel level = findNearestPlayerWorld(server, new Vec3(x, y, z));
                var entity = type.create(level, null, BlockPos.containing(x, y, z), EntitySpawnReason.COMMAND, false, false);
                if (entity != null) {
                    entity.setPos(x, y, z);
                    entity.setDeltaMovement(vx, vy, vz);
                    level.addFreshEntity(entity);
                }
            }
            case ScriptCommand.ParticleBurst(var id, var x, var y, var z, var count, var spread) -> {
                Identifier pid = Identifier.tryParse(id);
                if (pid == null) return;
                var ptype = BuiltInRegistries.PARTICLE_TYPE.getValue(pid);
                if (ptype instanceof SimpleParticleType dp) {
                    ServerLevel level = findNearestPlayerWorld(server, new Vec3(x, y, z));
                    level.sendParticles(dp, x, y, z, count, spread, spread, spread, 0.0);
                }
            }
            case ScriptCommand.ParticleLine(var id, var x1, var y1, var z1, var x2, var y2, var z2, var steps) -> {
                Identifier pid = Identifier.tryParse(id);
                if (pid == null || steps <= 0) return;
                var ptype = BuiltInRegistries.PARTICLE_TYPE.getValue(pid);
                if (!(ptype instanceof SimpleParticleType dp)) return;
                ServerLevel level = findNearestPlayerWorld(server, new Vec3(x1, y1, z1));
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    double px = x1 + (x2 - x1) * t;
                    double py = y1 + (y2 - y1) * t;
                    double pz = z1 + (z2 - z1) * t;
                    level.sendParticles(dp, px, py, pz, 1, 0, 0, 0, 0.0);
                }
            }
            case ScriptCommand.FillBlocks(var x1, var y1, var z1, var x2, var y2, var z2, var blockId) -> {
                Identifier bid = Identifier.tryParse(blockId);
                if (bid == null) return;
                var block = BuiltInRegistries.BLOCK.getValue(bid);
                if (block == null) return;
                // cap region size so scripts can't freeze the server with a fill:0,0,0:9999,9999,9999
                int minX = Math.min(x1, x2), maxX = Math.min(Math.max(x1, x2), minX + 63);
                int minY = Math.min(y1, y2), maxY = Math.min(Math.max(y1, y2), minY + 63);
                int minZ = Math.min(z1, z2), maxZ = Math.min(Math.max(z1, z2), minZ + 63);
                ServerLevel level = server.overworld();
                var state = block.defaultBlockState();
                for (int bx = minX; bx <= maxX; bx++)
                    for (int by = minY; by <= maxY; by++)
                        for (int bz = minZ; bz <= maxZ; bz++)
                            level.setBlock(new BlockPos(bx, by, bz), state, 3);
            }
            case ScriptCommand.DropItem(var x, var y, var z, var itemId, var count) -> {
                Identifier iid = Identifier.tryParse(itemId);
                if (iid == null) return;
                var item = BuiltInRegistries.ITEM.getValue(iid);
                if (item == null) return;
                ServerLevel level = findNearestPlayerWorld(server, new Vec3(x, y, z));
                ItemEntity ie = new ItemEntity(level, x, y, z, new ItemStack(item, count));
                ie.setDefaultPickUpDelay();
                level.addFreshEntity(ie);
            }
            case ScriptCommand.NetBroadcast(var message) -> {
                Component txt = Component.literal(message);
                server.getPlayerList().broadcastSystemMessage(txt, false);
            }
            case ScriptCommand.TitleAll(var title, var subtitle) -> {
                var titlePkt    = new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title));
                var subtitlePkt = new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle));
                var timingPkt   = new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 70, 20);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    p.connection.send(titlePkt);
                    if (!subtitle.isEmpty()) p.connection.send(subtitlePkt);
                    p.connection.send(timingPkt);
                }
            }
            case ScriptCommand.SetTarget(var uuid, var targetUuid) -> {
                if (findEntity(server, uuid) instanceof net.minecraft.world.entity.Mob mob)
                    mob.setTarget(targetUuid.isEmpty() ? null : findEntity(server, targetUuid));
            }
            case ScriptCommand.Attack(var uuid, var targetUuid) -> {
                LivingEntity attacker = findEntity(server, uuid);
                LivingEntity victim = findEntity(server, targetUuid);
                if (attacker != null && victim != null) attacker.doHurtTarget((ServerLevel) attacker.level(), victim);
            }
            case ScriptCommand.MoveTo(var uuid, var x, var y, var z, var speed) -> {
                // real pathfinding, not a teleport — the mob walks there and gives up if it can't
                if (findEntity(server, uuid) instanceof net.minecraft.world.entity.Mob mob)
                    mob.getNavigation().moveTo(x, y, z, speed);
            }
            case ScriptCommand.PlayAnim(var uuid, var clip) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null)
                    com.koper.koper_lib.api.FullpackAddons.playEntityAnimation(target, clip, 40);
            }
            case ScriptCommand.SetDurability(var uuid, var hand, var value) -> {
                LivingEntity holder = findEntity(server, uuid);
                if (holder != null) {
                    var stack = "off".equalsIgnoreCase(hand) ? holder.getOffhandItem() : holder.getMainHandItem();
                    if (!stack.isEmpty() && stack.getMaxDamage() > 0)
                        stack.setDamageValue(Math.max(0, stack.getMaxDamage() - value));
                }
            }
            case ScriptCommand.TagAdd(var uuid, var tag) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) target.addTag(tag);
            }
            case ScriptCommand.TagRemove(var uuid, var tag) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null) target.removeTag(tag);
            }
            case ScriptCommand.KontraForce(var id, var fx, var fy, var fz) -> {
                com.koper.koper_lib.api.FullpackAddons.physicsForce(id, fx, fy, fz);
            }
            case ScriptCommand.KontraImpulse(var id, var ix, var iy, var iz) -> {
                com.koper.koper_lib.api.FullpackAddons.physicsImpulse(id, ix, iy, iz);
            }
            case ScriptCommand.KontraSelfRight(var id) -> {
                com.koper.koper_lib.api.FullpackAddons.physicsSelfRight(id);
            }
            case ScriptCommand.KontraDestroy(var id) -> {
                com.koper.koper_lib.api.FullpackAddons.physicsDestroy(server, id);
            }
            case ScriptCommand.KontraRestore(var id) -> {
                com.koper.koper_lib.api.FullpackAddons.physicsRestore(server, id);
            }
            case ScriptCommand.KfxSpawn(var id, var sx, var sy, var sz, var ex, var ey, var ez) -> {
                ServerLevel level = findNearestPlayerWorld(server, new Vec3(sx, sy, sz));
                long fxId = com.koper.koper_lib.api.FullpackAddons.spawnEffect(
                    level, id, sx, sy, sz, ex, ey, ez);
                if (fxId == 0L) {
                    KoperLib.LOGGER.warn("[ScriptCmd] unknown kfx '{}'", id);
                }
            }
            case ScriptCommand.KfxUpdate(var id, var sx, var sy, var sz, var ex, var ey, var ez) -> {
                ServerLevel level = findNearestPlayerWorld(server, new Vec3(sx, sy, sz));
                com.koper.koper_lib.api.FullpackAddons.updateEffect(level, id, sx, sy, sz, ex, ey, ez);
            }
            case ScriptCommand.KfxStop(var id) -> {
                com.koper.koper_lib.api.FullpackAddons.stopEffect(server, id);
            }
            case ScriptCommand.KfxAttach(var id, var uuid, var ox, var oy, var oz, var ex, var ey, var ez, var endRelative) -> {
                LivingEntity target = findEntity(server, uuid);
                if (target != null && target.level() instanceof ServerLevel level)
                    com.koper.koper_lib.api.FullpackAddons.attachEffect(
                        level, id, target, ox, oy, oz, ex, ey, ez, endRelative);
            }
            case ScriptCommand.KfxProgramJson(var json, var sx, var sy, var sz, var ex, var ey, var ez) -> {
                try {
                    ServerLevel level = findNearestPlayerWorld(server, new Vec3(sx, sy, sz));
                    com.koper.koper_lib.api.FullpackAddons.runEffectProgram(
                        level, json, sx, sy, sz, ex, ey, ez);
                } catch (Exception e) {
                    KoperLib.LOGGER.warn("[ScriptCmd] bad inline kfx json: {}", e.getMessage());
                }
            }
            case ScriptCommand.KfxGraphDeclare(var graphJson) ->
                com.koper.koper_lib.api.FullpackAddons.declareEffectGraph(graphJson);
            case ScriptCommand.KfxGraphPlay(var id, var graphJson, var optionsJson) ->
                com.koper.koper_lib.api.FullpackAddons.playEffectGraph(server, id, graphJson, optionsJson);
            case ScriptCommand.KfxHandleSet(var id, var name, var valueJson) ->
                com.koper.koper_lib.api.FullpackAddons.setEffectHandle(server, id, name, valueJson);
            case ScriptCommand.KfxHandleAnchor(var id, var startJson, var endJson) ->
                com.koper.koper_lib.api.FullpackAddons.reanchorEffectHandle(server, id, startJson, endJson);
            case ScriptCommand.KfxHandleDetach(var id) ->
                com.koper.koper_lib.api.FullpackAddons.detachEffectHandle(server, id);
            case ScriptCommand.KfxHandleSignal(var id, var name, var dataJson) ->
                com.koper.koper_lib.api.FullpackAddons.signalEffectHandle(server, id, name, dataJson);
            case ScriptCommand.GuiConsume(var uuid, var slot, var count) -> {
                net.minecraft.world.Container c = openKuiContainer(server, uuid);
                if (c != null && slot >= 1 && slot <= c.getContainerSize()) {
                    c.getItem(slot - 1).shrink(count);
                    c.setChanged();
                    syncOpenMenu(server, uuid);
                }
            }
            case ScriptCommand.GuiClearSlot(var uuid, var slot) -> {
                net.minecraft.world.Container c = openKuiContainer(server, uuid);
                if (c != null && slot >= 1 && slot <= c.getContainerSize()) {
                    c.setItem(slot - 1, ItemStack.EMPTY);
                    c.setChanged();
                    syncOpenMenu(server, uuid);
                }
            }
            case ScriptCommand.GuiSetSlot(var uuid, var slot, var itemId, var count) -> {
                net.minecraft.world.Container c = openKuiContainer(server, uuid);
                Identifier iid = Identifier.tryParse(itemId);
                if (c != null && iid != null && slot >= 1 && slot <= c.getContainerSize()) {
                    var item = BuiltInRegistries.ITEM.getValue(iid);
                    if (item != null) {
                        c.setItem(slot - 1, new ItemStack(item, count));
                        c.setChanged();
                        syncOpenMenu(server, uuid);
                    }
                }
            }
            case ScriptCommand.GuiClearAll(var uuid) -> {
                net.minecraft.world.Container c = openKuiContainer(server, uuid);
                if (c != null) {
                    c.clearContent();
                    c.setChanged();
                    syncOpenMenu(server, uuid);
                }
            }
            case ScriptCommand.GuiWidgetSet(var uuid, var widget, var value) -> {
                ServerPlayer p = findPlayer(server, uuid);
                if (p != null) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
                    new com.koper.koper_lib.network.KuiWidgetUpdatePayload(widget, value));
            }
            case ScriptCommand.GuiOpen(var uuid, var guiId) -> {
                ServerPlayer p = findPlayer(server, uuid);
                if (p != null) com.koper.koper_lib.kui.KuiOpen.open(p, guiId);
            }
            case ScriptCommand.GuiClose(var uuid) -> com.koper.koper_lib.kui.KuiOpen.close(findPlayer(server, uuid));
            case ScriptCommand.GuiHud(var uuid, var guiId, var show) -> {
                ServerPlayer p = findPlayer(server, uuid);
                var page = com.koper.koper_lib.kui.KuiBook.get(guiId);
                if (p != null && page != null) {
                    String layout = show && page.layoutFile != null ? com.koper.koper_lib.kui.KuiOpen.readOr(page.layoutFile, "") : "";
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
                        new com.koper.koper_lib.network.KuiHudPayload(guiId, show, layout, page.hudX, page.hudY));
                }
            }
        }
    }

    private static List<String> decodeKfxFields(String command, String opcode, int expectedFields) {
        String prefix = opcode + ':';
        if (!command.startsWith(prefix)) return null;
        String rest = command.substring(prefix.length());
        java.util.ArrayList<String> fields = new java.util.ArrayList<>(expectedFields);
        int cursor = 0;
        try {
            while (cursor < rest.length() && fields.size() < expectedFields) {
                int lengthEnd = rest.indexOf(':', cursor);
                if (lengthEnd < 0) return null;
                int expectedBytes = Integer.parseInt(rest.substring(cursor, lengthEnd));
                if (expectedBytes < 0) return null;
                int payloadEnd = rest.indexOf(':', lengthEnd + 1);
                if (payloadEnd < 0) payloadEnd = rest.length();
                byte[] decoded = Base64.getDecoder().decode(rest.substring(lengthEnd + 1, payloadEnd));
                if (decoded.length != expectedBytes) return null;
                fields.add(new String(decoded, StandardCharsets.UTF_8));
                cursor = payloadEnd + 1;
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return fields.size() == expectedFields && cursor >= rest.length() ? List.copyOf(fields) : null;
    }

    // the container behind the player's currently open kui menu, or null if it isn't one
    private static net.minecraft.world.Container openKuiContainer(MinecraftServer server, String uuid) {
        ServerPlayer p = findPlayer(server, uuid);
        if (p != null && p.containerMenu instanceof com.koper.koper_lib.kui.KuiMenu km) return km.getContainer();
        return null;
    }

    private static void syncOpenMenu(MinecraftServer server, String uuid) {
        ServerPlayer p = findPlayer(server, uuid);
        if (p != null) p.containerMenu.broadcastChanges();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static LivingEntity findEntity(MinecraftServer server, String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            for (ServerLevel level : server.getAllLevels()) {
                var e = level.getEntity(uuid);
                if (e instanceof LivingEntity le) return le;
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String uuidStr) {
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(uuidStr));
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    private static ServerLevel findNearestPlayerWorld(MinecraftServer server, Vec3 pos) {
        for (ServerLevel w : server.getAllLevels()) {
            if (!w.getPlayers(p -> true).isEmpty()) return w;
        }
        return server.overworld();
    }

    private static String cmdUnescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length()) {
                String hex = s.substring(i + 1, i + 3);
                try {
                    out.append((char)Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            out.append(ch);
        }
        return out.toString();
    }
}
