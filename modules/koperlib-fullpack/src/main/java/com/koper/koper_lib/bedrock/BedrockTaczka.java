package com.koper.koper_lib.bedrock;

import com.koper.koper_lib.KoperLib;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// the wheelbarrow: every "thousands of tiny files" job of the bedrock converter goes through here.
// a big resource pack is 10k+ pngs and flash storage (phones!) only gets fast with a few requests
// in flight, one file at a time was 3x slower than plain `unzip`. hello to whoever reads this,
// if you know a faster way to write 10k files from java PLEASE HELP A SILLY LITTLE KOPERDEV
public final class BedrockTaczka {

    // io bound, so a bit more than the cores, but a phone with 8 little cores does not need 16
    private static final int KOLKA = Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final AtomicInteger NUMEREK = new AtomicInteger();
    private static final ForkJoinPool TACZKA = new ForkJoinPool(KOLKA, pool -> {
        ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        t.setName("koper-bedrock-taczka-" + NUMEREK.incrementAndGet());
        t.setDaemon(true);
        return t;
    }, null, false);

    private BedrockTaczka() {}

    public interface Robota<T> {
        void zrob(T t) throws IOException;
    }

    // runs the job for every item on the pool, waits, rethrows the first io failure
    public static <T> void kazdy(Collection<T> rzeczy, Robota<T> robota) throws IOException {
        if (rzeczy.isEmpty()) return;
        if (rzeczy.size() < 8) {
            for (T t : rzeczy) robota.zrob(t);
            return;
        }
        try {
            TACZKA.submit(() -> rzeczy.parallelStream().forEach(t -> {
                try {
                    robota.zrob(t);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof UncheckedIOException u) throw u.getCause();
            if (c instanceof RuntimeException r) throw r;
            if (c instanceof Error er) throw er;
            throw new IOException(c);
        }
    }

    // createDirectories is a handful of syscalls even when the folder is there. 10k textures in 40 folders
    // asked 10k times; this remembers what already exists for one job
    public static final class Katalogi {
        private final Set<Path> sa = ConcurrentHashMap.newKeySet();

        public void dla(Path file) throws IOException {
            Path dir = file.getParent();
            if (dir == null || sa.contains(dir)) return;
            Files.createDirectories(dir);
            for (Path d = dir; d != null && sa.add(d); d = d.getParent()) {}
        }
    }

    private static volatile boolean bezLinkow;

    // a file the converted pack keeps byte for byte (png, json, ogg): a hard link to the unpacked addon
    // is one syscall and zero bytes written. sd cards (fat/exfat) and odd setups say no, then it is a copy.
    // both ends are ours: the unpacked cache is only ever replaced by a fresh folder, never written in place
    public static void przenies(Path src, Path dst) throws IOException {
        if (!bezLinkow) {
            try {
                Files.createLink(dst, src);
                return;
            } catch (java.nio.file.FileAlreadyExistsException twice) {
                // same texture wanted twice (item icon + runtime copy), copy over it below
            } catch (IOException | UnsupportedOperationException | SecurityException noLinks) {
                if (!(noLinks instanceof java.nio.file.NoSuchFileException)) bezLinkow = true;
            }
        }
        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // old windows tools write entry names in cp437 without the utf-8 flag (Mowzie's: "ipoqdñasd.json").
    // java reads names as utf-8 and calls the whole zip broken, so a second try in cp437
    public static ZipFile otworz(Path zip) throws IOException {
        try {
            return new ZipFile(zip.toFile());
        } catch (java.util.zip.ZipException utf8) {
            KoperLib.LOGGER.info("[Bedrock] {} has non utf-8 file names, reading them as cp437", zip.getFileName());
            return new ZipFile(zip.toFile(), java.nio.charset.Charset.forName("IBM437"));
        }
    }

    // zip -> folder, entries in parallel straight off the central directory. same zip slip rule as before
    public static void rozpakuj(Path zip, Path target) throws IOException {
        Files.createDirectories(target);
        Path real = target.toAbsolutePath().normalize();
        Katalogi dirs = new Katalogi();
        try (ZipFile z = otworz(zip)) {
            // the same name twice (windows zips with both slashes): last one wins like the old loop
            java.util.Map<Path, ZipEntry> pliki = new java.util.LinkedHashMap<>();
            var it = z.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                Path p = real.resolve(e.getName().replace('\\', '/')).normalize();
                if (!p.startsWith(real)) { KoperLib.LOGGER.warn("[Bedrock] {} tried to escape with {}", zip.getFileName(), e.getName()); continue; }
                if (e.isDirectory() || e.getName().endsWith("/")) { Files.createDirectories(p); continue; }
                pliki.put(p, e);
            }
            kazdy(new ArrayList<>(pliki.entrySet()), pe -> {
                Path p = pe.getKey();
                ZipEntry e = pe.getValue();
                dirs.dla(p);
                try (InputStream in = z.getInputStream(e);
                     OutputStream out = Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                }
            });
        }
    }

    // a converted pack or an unpacked addon is thousands of files. deleting them one by one before every
    // reconversion was a good part of the wait: move the folder aside (one rename) and let a daemon
    // thread chew on it. whatever it did not finish is swept on the next start
    public static void wywal(Path dir, Path smietnik) throws IOException {
        if (!Files.exists(dir)) return;
        try {
            Files.createDirectories(smietnik);
            Path kosz = smietnik.resolve(dir.getFileName() + "." + System.nanoTime());
            Files.move(dir, kosz);
            posprzataj(smietnik);
        } catch (IOException | UnsupportedOperationException crossDevice) {
            // different disk, no rename: old slow way
            usun(dir);
        }
    }

    private static final Set<Path> SPRZATANE = ConcurrentHashMap.newKeySet();

    // background delete of everything in the trash folder
    public static void posprzataj(Path smietnik) {
        Path key = smietnik.toAbsolutePath().normalize();
        if (!Files.isDirectory(key) || !SPRZATANE.add(key)) return;
        Thread t = new Thread(() -> {
            try {
                // the folder itself stays, another reconversion may be moving something in right now.
                // round and round until nothing is left, so that one is not left behind either
                for (int round = 0; round < 16; round++) {
                    List<Path> kosze;
                    try (Stream<Path> s = Files.list(key)) {
                        kosze = s.toList();
                    }
                    if (kosze.isEmpty()) break;
                    for (Path k : kosze) usun(k);
                }
            } catch (IOException e) {
                KoperLib.LOGGER.debug("[Bedrock] trash {} not fully gone: {}", key, e.toString());
            } finally {
                SPRZATANE.remove(key);
            }
        }, "koper-bedrock-smieciarka");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // plain recursive delete, files in parallel, folders deepest first
    public static void usun(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> pliki, foldery;
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> all = s.toList();
            pliki = all.stream().filter(p -> !Files.isDirectory(p)).toList();
            foldery = all.stream().filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();
        }
        kazdy(pliki, Files::deleteIfExists);
        for (Path d : foldery) Files.deleteIfExists(d);
    }
}
