package com.koper.koper_lib.kodel.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Inflater;

// alpha of every pixel of a png without NativeImage: texture meshes get built on a pool thread and
// in unit tests, where stb isn't around and the failure was silent (whole-texture slabs, black
// holes). 8 bit, not interlaced, every color type. anything else -> null and the caller falls back
final class BrPng {

    private BrPng() {}

    // [x][y] alpha 0..255, or null
    static int[][] alfa(byte[] png) {
        if (png == null || png.length < 33 || (png[0] & 0xFF) != 0x89 || png[1] != 'P') return null;
        ByteBuffer b = ByteBuffer.wrap(png);
        int w = 0, h = 0, depth = 0, type = -1, interlace = 0;
        byte[] trns = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int at = 8;
        while (at + 8 <= png.length) {
            int len = b.getInt(at);
            int data = at + 8;
            if (len < 0 || data + len > png.length) return null;
            String kind = new String(png, at + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            switch (kind) {
                case "IHDR" -> { w = b.getInt(data); h = b.getInt(data + 4); depth = png[data + 8]; type = png[data + 9]; interlace = png[data + 12]; }
                case "tRNS" -> trns = java.util.Arrays.copyOfRange(png, data, data + len);
                case "IDAT" -> idat.write(png, data, len);
                default -> {}
            }
            if (kind.equals("IEND")) break;
            at = data + len + 4;
        }
        if (depth != 8 || interlace != 0 || w <= 0 || h <= 0 || w > 8192 || h > 8192) return null;
        int bpp = switch (type) { case 0, 3 -> 1; case 4 -> 2; case 2 -> 3; case 6 -> 4; default -> -1; };
        if (bpp < 0) return null;
        int stride = w * bpp;
        byte[] raw = new byte[(stride + 1) * h];
        Inflater inf = new Inflater();
        try {
            inf.setInput(idat.toByteArray());
            int got = 0;
            while (got < raw.length && !inf.finished()) {
                int n = inf.inflate(raw, got, raw.length - got);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                got += n;
            }
            if (got < raw.length) return null;
        } catch (java.util.zip.DataFormatException bad) {
            return null;
        } finally {
            inf.end();
        }
        int[][] out = new int[w][h];
        byte[] prev = new byte[stride], cur = new byte[stride];
        for (int y = 0; y < h; y++) {
            int row = y * (stride + 1), filter = raw[row];
            for (int x = 0; x < stride; x++) {
                int v = raw[row + 1 + x] & 0xFF;
                int a = x >= bpp ? cur[x - bpp] & 0xFF : 0, up = prev[x] & 0xFF, c = x >= bpp ? prev[x - bpp] & 0xFF : 0;
                v += switch (filter) {
                    case 1 -> a;
                    case 2 -> up;
                    case 3 -> (a + up) >>> 1;
                    case 4 -> { int p = a + up - c, pa = Math.abs(p - a), pb = Math.abs(p - up), pc = Math.abs(p - c); yield pa <= pb && pa <= pc ? a : pb <= pc ? up : c; }
                    default -> 0;
                };
                cur[x] = (byte) v;
            }
            for (int x = 0; x < w; x++) {
                int o = x * bpp;
                out[x][y] = switch (type) {
                    case 6 -> cur[o + 3] & 0xFF;
                    case 4 -> cur[o + 1] & 0xFF;
                    // palette: tRNS holds alpha per index, missing = opaque
                    case 3 -> { int i = cur[o] & 0xFF; yield trns != null && i < trns.length ? trns[i] & 0xFF : 255; }
                    // gray/rgb with tRNS: one exact color is the hole
                    case 0 -> trns != null && trns.length >= 2 && (cur[o] & 0xFF) == (trns[1] & 0xFF) ? 0 : 255;
                    default -> trns != null && trns.length >= 6 && (cur[o] & 0xFF) == (trns[1] & 0xFF)
                        && (cur[o + 1] & 0xFF) == (trns[3] & 0xFF) && (cur[o + 2] & 0xFF) == (trns[5] & 0xFF) ? 0 : 255;
                };
            }
            byte[] t = prev; prev = cur; cur = t;
        }
        return out;
    }
}
