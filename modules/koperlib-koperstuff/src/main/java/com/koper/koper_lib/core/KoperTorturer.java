package com.koper.koper_lib.core;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.api.KoperEventBus;
import com.koper.koper_lib.config.KoperLibConfig;
import com.koper.koper_lib.loader.ContentRegistry;
import com.koper.koper_lib.loader.resource.VirtualResourcePack;
import com.koper.koper_lib.panama.RustBridge;
import com.koper.koper_lib.scripting.ScriptCommandDispatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// beats the non-physics half of koperlib until something admits it's broken.
// every torture is a real edge case that already bit us or is one bad tick away from biting.
// physics has its own /koperlib physics stuff — this is loader, lua, vpack, bus, molang, dispatcher.
public final class KoperTorturer {

    private KoperTorturer() {}

    // one torture run: did it survive, how long, what did it say
    public record Wynik(String id, Stan stan, long ms, String gadanie) {}

    public enum Stan { OK, ZLE, POMINIETE }

    @FunctionalInterface
    private interface Tortura {
        void bij(Kartka pad) throws Throwable;
    }

    // scratchpad each torture scribbles on
    public static final class Kartka {
        private final List<String> lines = new ArrayList<>();
        private Stan stan = Stan.OK;

        public void note(String s) { lines.add(s); }
        public void fail(String s) { stan = Stan.ZLE; lines.add(s); }
        public void skip(String s) { if (stan == Stan.OK) stan = Stan.POMINIETE; lines.add(s); }
        String joined() { return String.join(" | ", lines); }
    }

    private static final Map<String, Tortura> KATOWNIA = new LinkedHashMap<>();
    static {
        KATOWNIA.put("luabomba",     KoperTorturer::luabomba);
        KATOWNIA.put("vpackracer",   KoperTorturer::vpackracer);
        KATOWNIA.put("busbrawl",     KoperTorturer::busbrawl);
        KATOWNIA.put("molangevil",   KoperTorturer::molangevil);
        KATOWNIA.put("idfuzz",       KoperTorturer::idfuzz);
        KATOWNIA.put("cmdpotop",     KoperTorturer::cmdpotop);
        KATOWNIA.put("reloadsztorm", KoperTorturer::reloadsztorm);
    }

    public static List<String> ids() { return new ArrayList<>(KATOWNIA.keySet()); }

    // runs one torture or "all". sink gets the report line by line so the caller can chat/log it
    public static List<Wynik> run(String which, Consumer<String> sink) {
        List<Wynik> out = new ArrayList<>();
        for (var e : KATOWNIA.entrySet()) {
            if (!"all".equals(which) && !e.getKey().equals(which)) continue;

            Kartka pad = new Kartka();
            long t0 = System.nanoTime();
            try {
                e.getValue().bij(pad);
            } catch (Throwable t) {
                pad.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            Wynik w = new Wynik(e.getKey(), pad.stan, ms, pad.joined());
            out.add(w);
            sink.accept(format(w));
            KoperLib.LOGGER.info("[Torturer] {} {} {}ms — {}", w.id(), w.stan(), w.ms(), w.gadanie());
        }
        return out;
    }

    private static String format(Wynik w) {
        String col = switch (w.stan()) { case OK -> "§a"; case ZLE -> "§c"; case POMINIETE -> "§8"; };
        return col + "  " + w.stan() + " §f" + w.id() + " §7" + w.ms() + "ms §r" + w.gadanie();
    }

    // ── tortury ───────────────────────────────────────────────────────────────

    // config says scriptTimeoutMs but nothing on either side of the FFM call reads it.
    // a pack with while true do end freezes the server tick forever. this proves it in a throwaway VM.
    private static void luabomba(Kartka pad) throws Exception {
        if (!RustBridge.isLoaded()) { pad.skip("no native engine, nothing to bomb"); return; }

        long vm = RustBridge.scriptCreateVm();
        if (vm == 0) { pad.skip("scriptCreateVm gave 0"); return; }

        // os.time is wall clock. os.clock is process CPU time and with MC's thread pile that
        // burns through "seconds" way faster than real time — useless for timing a bomb
        if (!RustBridge.scriptExec(vm, "assert(os and os.time)")) {
            RustBridge.scriptDestroyVm(vm);
            pad.skip("no os.time in this mlua build — can't time-box the bomb safely");
            return;
        }

        int budget = com.koper.koper_lib.fullpack.config.FullpackConfig.get().scriptTimeoutMs;
        // bomb has to outlive the budget, else it just finishes on its own and we call that a pass
        final int spinSek = Math.min(12, Math.max(3, budget / 1000 + 3));
        long czekaj = budget + 500L;

        CountDownLatch wrocil = new CountDownLatch(1);
        // VM dies on this thread so we never free it out from under a running exec
        Thread t = new Thread(() -> {
            try { RustBridge.scriptExec(vm, "local s=os.time() while os.time()-s < " + spinSek + " do end"); }
            catch (Throwable ignored) {}
            finally { RustBridge.scriptDestroyVm(vm); wrocil.countDown(); }
        }, "koper-torture-luabomba");
        t.setDaemon(true);
        t.start();

        if (wrocil.await(czekaj, TimeUnit.MILLISECONDS)) {
            pad.note("exec died inside " + czekaj + "ms while the bomb wanted " + spinSek
                + "s — something really does cut scripts off");
        } else {
            pad.fail(spinSek + "s spin still going after " + czekaj + "ms — scriptTimeoutMs=" + budget
                + " is dead config, nothing reads it, a runaway pack hangs the server thread forever");
        }
    }

    // VirtualResourcePack keeps plain HashMap/HashSet. every factory writes into it during load,
    // and MC reads it off the resource-reload workers. this is the race, on a throwaway copy.
    private static void vpackracer(Kartka pad) throws Exception {
        VirtualResourcePack pack = new VirtualResourcePack();
        final int pisarze = 4, kazdy = 6000;

        ConcurrentLinkedQueue<Throwable> bum = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1); // everyone leaves the gate together or it isn't a race
        CountDownLatch koniec = new CountDownLatch(pisarze);
        List<Thread> threads = new ArrayList<>();

        for (int w = 0; w < pisarze; w++) {
            final int me = w;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < kazdy; i++)
                        pack.addAsset(Identifier.fromNamespaceAndPath(
                            "torture", "models/item/t" + me + "_" + i + ".json"), "{}");
                } catch (Throwable e) {
                    bum.add(e);
                } finally {
                    koniec.countDown();
                }
            }, "koper-torture-vpack-" + w);
            th.setDaemon(true);
            threads.add(th);
            th.start();
        }

        var probe = Identifier.fromNamespaceAndPath("torture", "models/item/t0_1.json");
        var typ = net.minecraft.server.packs.PackType.CLIENT_RESOURCES;
        int czytane = 0, petle = 0;
        boolean wytarte = false;

        start.countDown();
        // hammer reads for as long as the writers are alive — a reload landing mid-load looks exactly like this
        while (koniec.getCount() > 0 && petle < 2_000_000) {
            try {
                if (pack.getResource(typ, probe) != null) czytane++;
                pack.getNamespaces(typ).size();
            } catch (Throwable e) {
                bum.add(e);
            }
            if (!wytarte && czytane > 50) {
                wytarte = true;
                try { pack.clearAssets(); } catch (Throwable e) { bum.add(e); }
            }
            petle++;
        }

        boolean doszli = koniec.await(10, TimeUnit.SECONDS);
        for (Thread th : threads) th.interrupt();

        if (!doszli) {
            pad.fail("writers stuck >10s — that's the HashMap resize spin, vpack needs ConcurrentHashMap");
            return;
        }
        if (!bum.isEmpty()) {
            Throwable first = bum.peek();
            pad.fail(bum.size() + " blowups, first " + first.getClass().getSimpleName()
                + " — vpack maps are not thread safe");
            return;
        }
        if (petle < 100) {
            pad.skip(petle + " read loops overlapped the writers — too few to call it a race, run it again");
            return;
        }
        pad.note(petle + " read loops against " + (pisarze * kazdy) + " concurrent writes + wipe, "
            + czytane + " hits, no blowup — still luck, the maps are unsynchronized");
    }

    // bus fires by iterating an ArrayList. a handler that subscribes during dispatch (very normal
    // for a pack registering follow-ups) mutates that list mid-loop.
    private static void busbrawl(Kartka pad) throws Throwable {
        var lookup = MethodHandles.lookup();
        MethodType sig = MethodType.methodType(void.class, KoperContext.class);
        MethodHandle bomba  = lookup.findStatic(KoperTorturer.class, "handlerCoWybucha", sig);
        MethodHandle krolik = lookup.findStatic(KoperTorturer.class, "handlerCoSieMnozy", sig);

        KoperContext ctx = new KoperContext(null, null, null, null, ItemStack.EMPTY, null);

        KoperEventBus.subscribe(BUS_EV, bomba);
        KoperEventBus.subscribe(BUS_EV, bomba);
        KoperEventBus.fire(BUS_EV, ctx);
        pad.note("throwing handlers contained ok");

        KoperEventBus.subscribe(BUS_EV, krolik);
        try {
            KoperEventBus.fire(BUS_EV, ctx);
            pad.note("self-subscribe during fire did not blow up this time (ArrayList got lucky)");
        } catch (ConcurrentModificationException cme) {
            pad.fail("CME out of KoperEventBus.fire — a handler that subscribes mid-dispatch kills the event");
        }

        // no per-id unsubscribe exists, so torture handlers stay on koperlib:torture/bus until a reload
        pad.note("bus has no unsubscribe — leftovers cleared only by /koperlib reload");
    }

    private static final String BUS_EV = "koperlib:torture/bus";

    private static void handlerCoWybucha(KoperContext ctx) {
        throw new IllegalStateException("torture handler exploded on purpose");
    }

    private static void handlerCoSieMnozy(KoperContext ctx) {
        try {
            KoperEventBus.subscribe(BUS_EV, MethodHandles.lookup().findStatic(
                KoperTorturer.class, "handlerCoWybucha",
                MethodType.methodType(void.class, KoperContext.class)));
        } catch (Throwable ignored) {}
    }

    // MoLang comes straight out of pack json. recursive-descent + attacker-ish input = stack overflow.
    // runs on a thin-stack thread so an SOE lands there and not on the server tick.
    private static void molangevil(Kartka pad) throws Exception {
        String[] zlo = {
            "(".repeat(600) + "1" + ")".repeat(600),
            "1/0",
            "0/0",
            "math.sqrt(-1)",
            "nie_ma_takiej_funkcji(2)",
            "query.życie + 'ą'",
            "",
            "9".repeat(400),
            "1" + "+1".repeat(5000),
            "1" + "+1".repeat(1000), // just under the parser's cap: must compile, not overflow
        };

        List<String> zabiło = new ArrayList<>();
        List<String> wisiało = new ArrayList<>();

        for (String expr : zlo) {
            final String e = expr;
            final Throwable[] caught = new Throwable[1];
            // 256k stack — deep nesting hits the wall fast instead of eating the real stack
            Thread th = new Thread(null, () -> {
                try {
                    var request = new com.google.gson.JsonObject();
                    request.addProperty("expr", e);
                    com.koper.koper_lib.panama.RustBridge.molangEval(request);
                } catch (Throwable t) {
                    caught[0] = t;
                }
            }, "koper-torture-molang", 1 << 18);
            th.setDaemon(true);
            th.start();
            th.join(3000);

            String tag = e.length() > 20 ? e.substring(0, 20) + "…" : (e.isEmpty() ? "<empty>" : e);
            if (th.isAlive()) { wisiało.add(tag); th.interrupt(); }
            else if (caught[0] instanceof StackOverflowError) zabiło.add(tag);
        }

        if (!wisiało.isEmpty()) pad.fail("molang eval hangs on: " + String.join(", ", wisiało));
        if (!zabiło.isEmpty())
            pad.fail("StackOverflowError on: " + String.join(", ", zabiło)
                + " — a nested molang expression in any pack json takes the loader down");
        if (wisiało.isEmpty() && zabiło.isEmpty())
            pad.note(zlo.length + " evil expressions, all threw or compiled cleanly");
    }

    // ids come from folder names and json keys, so users hand us garbage constantly.
    // Identifier.tryParse must reject it before it reaches a registry, not after.
    private static void idfuzz(Kartka pad) {
        String[] smiecie = {
            "UPPER:case", "ns:pa th", "ns::double", ":", "", "ns:", ":path",
            "ns:" + "a".repeat(400), "ną:ść", "ns:ok/../../escape", "ns:ok\\back",
            "n s:ok", "ns:ok null", "ns:ok\n",
        };

        List<String> przeszlo = new ArrayList<>();
        List<String> rzucilo  = new ArrayList<>();

        for (String s : smiecie) {
            try {
                Identifier id = Identifier.tryParse(s);
                if (id != null) przeszlo.add(s.length() > 24 ? s.substring(0, 24) + "…" : s);
                ContentRegistry.createItemSettings(s); // must not throw even on junk — factories call it blind
            } catch (Throwable t) {
                rzucilo.add((s.isEmpty() ? "<empty>" : s) + " → " + t.getClass().getSimpleName());
            }
        }

        if (!rzucilo.isEmpty())
            pad.fail("createItemSettings threw instead of falling back: " + String.join(", ", rzucilo));
        if (!przeszlo.isEmpty())
            pad.note("tryParse accepted: " + String.join(", ", przeszlo) + " — check these reach no registry");
        if (rzucilo.isEmpty() && przeszlo.isEmpty())
            pad.note(smiecie.length + " junk ids all rejected cleanly");
    }

    // one lua tick can queue an unbounded pile of commands. dispatch parses every line on the server
    // thread with no cap — this measures what a pack doing that inside on_tick costs us.
    private static void cmdpotop(Kartka pad) {
        if (com.koper.koper_lib.scripting.UniversalScriptEngine.getCurrentServer() == null) {
            pad.skip("no server cached, dispatch returns early");
            return;
        }

        StringBuilder sb = new StringBuilder(1 << 20);
        for (int i = 0; i < 50_000; i++) {
            switch (i % 4) {
                case 0 -> sb.append("teleport:not-a-uuid:1,2,3\n");
                case 1 -> sb.append("teleport:not-a-uuid:a,b,c\n");   // NumberFormatException per line
                case 2 -> sb.append("play_sound:1,2,3:koperlib:nope:1,1\n");
                default -> sb.append("zupelnie-nieznana-komenda:").append(i).append('\n');
            }
        }
        sb.append("teleport:").append("x".repeat(200_000)).append(":1,2,3\n"); // one fat line

        long t0 = System.nanoTime();
        ScriptCommandDispatcher.dispatch(sb.toString(), new Object[0]);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        int spilled = ScriptCommandDispatcher.backlogSize();

        if (ms > 25) {
            pad.fail(ms + "ms on one dispatch — the time slice isn't holding, a script can still eat the tick");
            ScriptCommandDispatcher.clearBacklog();
            return;
        }
        pad.note("50k junk commands, " + ms + "ms on the tick, " + spilled + " deferred");

        // a queue that never empties is just a slower way to die
        int rundy = 0;
        while (ScriptCommandDispatcher.backlogSize() > 0 && rundy < 500) {
            ScriptCommandDispatcher.drainBacklog();
            rundy++;
        }
        if (ScriptCommandDispatcher.backlogSize() > 0) {
            pad.fail("backlog still " + ScriptCommandDispatcher.backlogSize() + " after " + rundy + " drains — it isn't draining");
            ScriptCommandDispatcher.clearBacklog();
        } else {
            pad.note("backlog cleared in " + rundy + " ticks");
        }
    }

    // reload is the most-run code path in the whole mod and it rebuilds every cache by hand.
    // anything it forgets to release shows up here as metaspace/heap that never comes back.
    private static void reloadsztorm(Kartka pad) {
        final int rund = 5;
        System.gc();
        long heap0 = uzyteHeap(), meta0 = uzyteMeta();

        long najgorsza = 0;
        for (int i = 0; i < rund; i++) {
            long t0 = System.nanoTime();
            com.koper.koper_lib.loader.CommandRegistry.koperReloadCaches();
            najgorsza = Math.max(najgorsza, (System.nanoTime() - t0) / 1_000_000L);
        }

        System.gc();
        long heapD = (uzyteHeap() - heap0) / (1024 * 1024);
        long metaD = (uzyteMeta() - meta0) / (1024 * 1024);

        pad.note(rund + " reloads, worst " + najgorsza + "ms, heap " + (heapD >= 0 ? "+" : "") + heapD
            + "MB, metaspace " + (metaD >= 0 ? "+" : "") + metaD + "MB");

        // pack java gets its own classloader per reload; if those stick, metaspace only goes up
        if (metaD > 8)
            pad.fail("metaspace +" + metaD + "MB over " + rund + " reloads — KoperPackClassLoader is leaking");
        if (heapD > 64)
            pad.fail("heap +" + heapD + "MB after gc — a cache is holding old pack data");
    }

    private static long uzyteHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long uzyteMeta() {
        for (var pool : ManagementFactory.getMemoryPoolMXBeans())
            if (pool.getName().contains("Metaspace")) return pool.getUsage().getUsed();
        return 0L;
    }
}
