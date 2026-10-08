package com.koper.koper_lib.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Inflater;

// tiny png peeker: size + "does any pixel have alpha under 255". no awt, zalith's jre barely has it.
// only 8 bit non interlaced, anything weirder answers "dunno" and the caller plays safe
final class BedrockPikselarz {

    private BedrockPikselarz() {}

    // {w, h} or null
    static int[] rozmiar(byte[] png) {
        if (!isPng(png) || png.length < 24) return null;
        ByteBuffer b = ByteBuffer.wrap(png);
        return new int[] {b.getInt(16), b.getInt(20)};
    }

    // 1 = some pixel is see-through, 0 = fully opaque, -1 = cant tell
    static int przezroczysty(byte[] png) {
        if (!isPng(png)) return -1;
        ByteBuffer b = ByteBuffer.wrap(png);
        int w = 0, h = 0, depth = 0, type = -1, interlace = 0;
        boolean trns = false;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int at = 8;
        while (at + 8 <= png.length) {
            int len = b.getInt(at);
            String kind = new String(png, at + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int data = at + 8;
            if (len < 0 || data + len > png.length) return -1;
            switch (kind) {
                case "IHDR" -> { w = b.getInt(data); h = b.getInt(data + 4); depth = png[data + 8]; type = png[data + 9]; interlace = png[data + 12]; }
                case "tRNS" -> trns = true;
                case "IDAT" -> idat.write(png, data, len);
                default -> {}
            }
            if (kind.equals("IEND")) break;
            at = data + len + 4;
        }
        if (type == 0 || type == 2) return trns ? -1 : 0;
        if (type == 3) return trns ? 1 : 0; // palette with trns almost always means holes
        if ((type != 4 && type != 6) || depth != 8 || interlace != 0 || w <= 0 || h <= 0) return -1;
        int bpp = type == 6 ? 4 : 2;
        int stride = w * bpp;
        byte[] raw = new byte[(stride + 1) * h];
        try {
            Inflater inf = new Inflater();
            inf.setInput(idat.toByteArray());
            int got = 0;
            while (got < raw.length && !inf.finished()) {
                int n = inf.inflate(raw, got, raw.length - got);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                got += n;
            }
            inf.end();
            if (got < raw.length) return -1;
        } catch (Exception bad) {
            return -1;
        }
        byte[] prev = new byte[stride], cur = new byte[stride];
        for (int y = 0; y < h; y++) {
            int row = y * (stride + 1);
            int filter = raw[row];
            for (int x = 0; x < stride; x++) {
                int v = raw[row + 1 + x] & 0xFF;
                int a = x >= bpp ? cur[x - bpp] & 0xFF : 0;
                int up = prev[x] & 0xFF;
                int c = x >= bpp ? prev[x - bpp] & 0xFF : 0;
                v += switch (filter) {
                    case 1 -> a;
                    case 2 -> up;
                    case 3 -> (a + up) >>> 1;
                    case 4 -> paeth(a, up, c);
                    default -> 0;
                };
                cur[x] = (byte) v;
            }
            for (int x = bpp - 1; x < stride; x += bpp) if ((cur[x] & 0xFF) < 255) return 1;
            byte[] t = prev; prev = cur; cur = t;
        }
        return 0;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static boolean isPng(byte[] d) {
        return d != null && d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }
}
