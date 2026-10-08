package com.koper.koper_lib.scripting;

import com.koper.koper_lib.KoperLib;

import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

// compiles the java/ folder inside a fullpack dir into .cache/classes/ on every reload
// pack creators write normal java, annotate with @KoperHook / @KoperSubscribe, done
public class KoperPackJavaCompiler {

    private static boolean hasClasses(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    public static Path compile(Path packRoot, String namespace) throws IOException {
        Path javaDir = packRoot.resolve("java");
        // most players run a launcher JRE with no javac, so a pack that wants its java tier to
        // reach them ships java/out/ compiled once by the author. that wins over sources.
        Path shipped = javaDir.resolve("out");
        if (Files.isDirectory(shipped) && hasClasses(shipped)) {
            KoperLib.LOGGER.info("[JavaPack:{}] using prebuilt classes from java/out — no javac needed", namespace);
            return shipped;
        }

        if (!Files.isDirectory(javaDir)) return null;

        List<Path> sources;
        try (var walk = Files.walk(javaDir)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) return null;

        Path outputDir = packRoot.resolve(".cache").resolve("classes");
        Files.createDirectories(outputDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            KoperLib.LOGGER.warn("[JavaPack:{}] no javac here (launcher JRE?) and no prebuilt java/out —"
                + " the java tier of this pack is off. author: run a build once and ship java/out/", namespace);
            return null;
        }

        // full classpath from current JVM — in Loom dev env this has all MC/Fabric jars
        String cp = System.getProperty("java.class.path", "");
        KoperLib.LOGGER.info("[JavaPack:{}] compiling {} file(s), javac cp length={}", namespace, sources.size(), cp.length());

        // --enable-preview only works if --source matches the running JVM's own feature version,
        // not whatever we targeted the main build with. hardcoded "25" here blows up the second
        // someone runs on JDK 26 ("invalid source release 25 with --enable-preview"), so ask the JVM.
        String javaFeature = String.valueOf(Runtime.version().feature());
        List<String> args = List.of(
            "-classpath", cp,
            "-d", outputDir.toString(),
            "--source", javaFeature,
            "--enable-preview",
            "-proc:none"
        );

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = fm.getJavaFileObjectsFromPaths(sources);
            var diags = new DiagnosticCollector<JavaFileObject>();
            boolean ok = compiler.getTask(null, fm, diags, args, null, units).call();

            for (var d : diags.getDiagnostics()) {
                String msg = d.getMessage(null);
                if (d.getKind() == Diagnostic.Kind.ERROR)        KoperLib.LOGGER.error("[JavaPack:{}] {}", namespace, msg);
                else if (d.getKind() == Diagnostic.Kind.WARNING) KoperLib.LOGGER.debug("[JavaPack:{}] {}", namespace, msg);
            }

            if (!ok) {
                KoperLib.LOGGER.error("[JavaPack:{}] compilation FAILED — hooks disabled for this pack", namespace);
                return null;
            }
            KoperLib.LOGGER.info("[JavaPack:{}] compiled {} file(s)", namespace, sources.size());
            return outputDir;
        }
    }
}
