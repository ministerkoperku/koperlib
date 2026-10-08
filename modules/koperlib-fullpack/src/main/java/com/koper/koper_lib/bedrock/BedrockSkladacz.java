package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

// newer packs are not plain folders any more: subpacks layered over the root, and whole folders
// squashed into __brarchive files ("pack_optimization_version"). this flattens all of it into one
// ordinary pack folder the translator can walk like it always did
public final class BedrockSkladacz {

    private static final long BRARCHIVE_MAGIC = 0x267052A0B125277DL;

    private BedrockSkladacz() {}

    static boolean potrzebny(Path dir) {
        return Files.isDirectory(dir.resolve("__brarchive")) || Files.isDirectory(dir.resolve("subpacks"));
    }

    // dir = pack with manifest.json, into = empty scratch folder. returns into
    static Path scal(Path dir, Path into, String subpackWish) throws IOException {
        wipe(into);
        Files.createDirectories(into);
        copyTree(dir, into, true);
        unpackArchives(dir.resolve("__brarchive"), into);

        String sub = wybierzSubpack(dir, subpackWish);
        if (sub != null) {
            Path sp = dir.resolve("subpacks").resolve(sub);
            if (Files.isDirectory(sp)) {
                copyTree(sp, into, true);
                unpackArchives(sp.resolve("__brarchive"), into);
                KoperLib.LOGGER.info("[Bedrock] {} uses subpack {}", dir.getFileName(), sub);
            }
        }
        return into;
    }

    // wish can be the folder name or the display name. no wish: the subpack with the most in it,
    // which is what "full experience" style packs mean by their top setting
    static String wybierzSubpack(Path dir, String wish) {
        JsonObject m = BedrockTlumacz.czytajObj(dir.resolve("manifest.json"));
        if (m == null || !m.has("subpacks") || !m.get("subpacks").isJsonArray()) return null;
        String best = null;
        long bestCount = -1;
        for (JsonElement e : m.getAsJsonArray("subpacks")) {
            JsonObject s = e.getAsJsonObject();
            String folder = BedrockTlumacz.str(s, "folder_name", null);
            if (folder == null) continue;
            if (wish != null && (wish.equalsIgnoreCase(folder) || wish.equalsIgnoreCase(BedrockTlumacz.str(s, "name", "")))) return folder;
            long n = countFiles(dir.resolve("subpacks").resolve(folder));
            if (n > bestCount) { bestCount = n; best = folder; }
        }
        return best;
    }

    private static long countFiles(Path p) {
        if (!Files.isDirectory(p)) return 0;
        try (Stream<Path> s = Files.walk(p)) {
            long n = 0;
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                // an archive counts for what is inside it, not as one file
                n += f.toString().endsWith(".brarchive") ? Math.max(1, Files.size(f) / 4096) : 1;
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void copyTree(Path from, Path to, boolean skipPackPlumbing) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(from)) {
            files = s.filter(Files::isRegularFile).filter(p -> {
                String first = from.relativize(p).getName(0).toString();
                return !skipPackPlumbing || !(first.equals("subpacks") || first.equals("__brarchive"));
            }).toList();
        }
        BedrockTaczka.Katalogi dirs = new BedrockTaczka.Katalogi();
        BedrockTaczka.kazdy(files, p -> {
            Path dst = to.resolve(from.relativize(p).toString());
            dirs.dla(dst);
            Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
        });
    }

    // __brarchive/models/entity.brarchive holds what used to be models/entity/*
    private static void unpackArchives(Path archiveRoot, Path into) throws IOException {
        if (!Files.isDirectory(archiveRoot)) return;
        List<Path> archives;
        try (Stream<Path> s = Files.walk(archiveRoot)) {
            archives = s.filter(p -> p.toString().endsWith(".brarchive")).sorted().toList();
        }
        for (Path a : archives) {
            String rel = archiveRoot.relativize(a).toString().replace('\\', '/');
            String folder = rel.substring(0, rel.length() - ".brarchive".length());
            int n = rozpakuj(a, into.resolve(folder));
            if (n < 0) KoperLib.LOGGER.warn("[Bedrock] {} is not a brarchive, skipped", a);
        }
    }

    // layout: u64 magic, u32 count, u32 version, then count slots of 256 bytes
    // (u8 name length, name, zero padding, u32 offset at 248, u32 size at 252), then the data.
    // a size of 0 means the file lives loose in the pack instead
    static int rozpakuj(Path archive, Path into) throws IOException {
        byte[] all = Files.readAllBytes(archive);
        if (all.length < 16) return -1;
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getLong(0) != BRARCHIVE_MAGIC) return -1;
        int count = b.getInt(8);
        long base = 16L + 256L * count;
        if (count < 0 || base > all.length) return -1;
        Path real = into.toAbsolutePath().normalize();
        int written = 0;
        for (int i = 0; i < count; i++) {
            int slot = 16 + 256 * i;
            int len = all[slot] & 0xFF;
            String name = new String(all, slot + 1, Math.min(len, 247), StandardCharsets.UTF_8);
            long off = b.getInt(slot + 248) & 0xFFFFFFFFL;
            long size = b.getInt(slot + 252) & 0xFFFFFFFFL;
            if (size == 0) continue;
            if (base + off + size > all.length) { KoperLib.LOGGER.warn("[Bedrock] {} entry {} runs past the end", archive.getFileName(), name); continue; }
            Path dst = real.resolve(name).normalize();
            if (!dst.startsWith(real)) continue; // same zip slip rule as the zips
            Files.createDirectories(dst.getParent());
            Files.write(dst, java.util.Arrays.copyOfRange(all, (int) (base + off), (int) (base + off + size)));
            written++;
        }
        return written;
    }

    static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    // ── tga -> png, java.desktop is not there on every launcher so no ImageIO ───

    static byte[] tgaToPng(byte[] tga) {
        // bedrock looks at the bytes, not the name. a .tga that is really a png stays a png
        if (tga.length >= 8 && (tga[0] & 0xFF) == 0x89 && tga[1] == 'P' && tga[2] == 'N' && tga[3] == 'G') return tga;
        if (tga.length < 18) return null;
        int idLen = tga[0] & 0xFF, cmapType = tga[1] & 0xFF, type = tga[2] & 0xFF;
        int cmapLen = (tga[5] & 0xFF) | (tga[6] & 0xFF) << 8, cmapBits = tga[7] & 0xFF;
        int w = (tga[12] & 0xFF) | (tga[13] & 0xFF) << 8, h = (tga[14] & 0xFF) | (tga[15] & 0xFF) << 8;
        int bpp = tga[16] & 0xFF, desc = tga[17] & 0xFF;
        if (w <= 0 || h <= 0) return null;
        boolean rle = type == 9 || type == 10 || type == 11;
        boolean gray = type == 3 || type == 11;
        // 1 / 9: an index per pixel into a palette stored right after the id (bedrock's water normal maps)
        boolean mapped = type == 1 || type == 9;
        if (!(type == 2 || type == 10 || gray || mapped)) return null;
        if (mapped != (cmapType == 1)) return null;
        int px = bpp / 8;
        int[] paleta = null;
        if (mapped) {
            int first = (tga[3] & 0xFF) | (tga[4] & 0xFF) << 8;
            int eb = (cmapBits + 7) / 8;
            paleta = new int[first + cmapLen];
            int pa = 18 + idLen;
            if (pa + cmapLen * eb > tga.length || px < 1 || px > 2 || eb < 2) return null;
            for (int i = 0; i < cmapLen; i++) paleta[first + i] = pixel(tga, pa + i * eb, eb, false);
        }
        int at = 18 + idLen + cmapLen * ((cmapBits + 7) / 8);
        int[] rgba = new int[w * h];
        int n = 0;
        try {
            while (n < w * h) {
                int run = 1;
                boolean packet = false;
                if (rle) {
                    int hdr = tga[at++] & 0xFF;
                    run = (hdr & 0x7F) + 1;
                    packet = (hdr & 0x80) != 0;
                }
                int c = 0;
                for (int k = 0; k < run && n < w * h; k++) {
                    if (k == 0 || !packet) {
                        if (paleta != null) {
                            int ix = px == 2 ? (tga[at] & 0xFF) | (tga[at + 1] & 0xFF) << 8 : tga[at] & 0xFF;
                            c = ix < paleta.length ? paleta[ix] : 0;
                        } else c = pixel(tga, at, px, gray);
                        at += px;
                    }
                    rgba[n++] = c;
                }
            }
        } catch (ArrayIndexOutOfBoundsException truncated) {
            return null;
        }
        boolean topDown = (desc & 0x20) != 0;
        boolean rightLeft = (desc & 0x10) != 0;
        // straight into one array, the stream version paid a synchronized call per byte
        int stride = w * 4 + 1;
        byte[] raw = new byte[stride * h];
        for (int y = 0; y < h; y++) {
            int sy = topDown ? y : h - 1 - y;
            int o = y * stride + 1;
            for (int x = 0; x < w; x++) {
                int c = rgba[sy * w + (rightLeft ? w - 1 - x : x)];
                raw[o++] = (byte) (c >>> 24);
                raw[o++] = (byte) (c >>> 16);
                raw[o++] = (byte) (c >>> 8);
                raw[o++] = (byte) c;
            }
        }
        return png(w, h, raw);
    }

    // returns 0xRRGGBBAA
    private static int pixel(byte[] d, int at, int px, boolean gray) {
        if (gray) {
            int g = d[at] & 0xFF;
            int a = px > 1 ? d[at + 1] & 0xFF : 255;
            return g << 24 | g << 16 | g << 8 | a;
        }
        if (px == 2) {
            int v = (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8;
            int r = (v >> 10 & 31) * 255 / 31, g = (v >> 5 & 31) * 255 / 31, bl = (v & 31) * 255 / 31;
            return r << 24 | g << 16 | bl << 8 | 255;
        }
        int bl = d[at] & 0xFF, g = d[at + 1] & 0xFF, r = d[at + 2] & 0xFF;
        int a = px == 4 ? d[at + 3] & 0xFF : 255;
        return r << 24 | g << 16 | bl << 8 | a;
    }

    private static byte[] png(int w, int h, byte[] rawRows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        // speed over the last few percent of size, these get written once and read by the game once
        Deflater z = new Deflater(Deflater.BEST_SPEED);
        z.setInput(rawRows);
        z.finish();
        ByteArrayOutputStream zd = new ByteArrayOutputStream(rawRows.length / 4 + 64);
        byte[] buf = new byte[65536];
        while (!z.finished()) zd.write(buf, 0, z.deflate(buf));
        z.end();
        chunk(out, "IDAT", zd.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        ByteBuffer len = ByteBuffer.allocate(4).putInt(data.length);
        out.writeBytes(len.array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
