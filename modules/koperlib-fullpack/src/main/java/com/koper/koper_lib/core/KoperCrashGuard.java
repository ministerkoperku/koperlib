package com.koper.koper_lib.core;

import com.koper.koper_lib.KoperLib;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// logs the ugly stack traces to disk + log — we swallow koper-only thread crashes if we can
public final class KoperCrashGuard {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static volatile boolean installed;

    private KoperCrashGuard() {}

    public static void install() {
        if (installed) return;
        installed = true;

        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread.getName() != null && thread.getName().toLowerCase().contains("koper")) {
                dump("uncaught-thread", error);
                KoperLib.LOGGER.error("[KoperLib] swallowed crash on thread {}", thread.getName(), error);
                return;
            }
            if (prev != null) prev.uncaughtException(thread, error);
        });
    }

    public static void run(String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            dump(where, t);
            KoperLib.LOGGER.error("[KoperLib] {} failed — continuing without crash", where, t);
        }
    }

    public static <T> T call(String where, java.util.function.Supplier<T> s, T fallback) {
        try {
            return s.get();
        } catch (Throwable t) {
            dump(where, t);
            KoperLib.LOGGER.error("[KoperLib] {} failed — fallback", where, t);
            return fallback;
        }
    }

    public static void dump(String tag, Throwable t) {
        String text = format(tag, t);
        KoperLib.LOGGER.error(text);
        try {
            Path dir = Path.of("logs", "koperlib");
            Files.createDirectories(dir);
            String name = "crash-" + tag + "-" + STAMP.format(LocalDateTime.now()) + ".txt";
            Files.writeString(dir.resolve(name), text);
            KoperRuntime.note("crash log → logs/koperlib/" + name);
        } catch (Exception ignored) {}
    }

    public static String format(String tag, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("=== KoperLib crash dump ===");
        pw.println("tag: " + tag);
        pw.println("time: " + LocalDateTime.now());
        pw.println("--- runtime ---");
        for (String line : KoperRuntime.snapshot()) pw.println(line);
        pw.println("--- throwable ---");
        if (t != null) t.printStackTrace(pw);
        else pw.println("(null)");
        pw.println("=== end ===");
        return sw.toString();
    }
}
