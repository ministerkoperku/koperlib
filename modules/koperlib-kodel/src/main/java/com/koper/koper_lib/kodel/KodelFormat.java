package com.koper.koper_lib.kodel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Little-endian binary IO for the .kodel format. Same layout as the reference
 * Python library in kodel/SPEC.md, so files produced by either side roundtrip.
 */
public final class KodelFormat {
    public static final int MAGIC = 0x4C444F4B; // "KODL"
    public static final int ENDK = 0x454E444B;   // "ENDK"
    public static final int MAJOR = 1;
    public static final int MINOR = 0;
    public static final int NO_BONE = 0xFFFFFFFF;

    public static final int EASE_LINEAR = 0;
    public static final int EASE_STEP = 1;
    public static final int EASE_BEZIER = 2;

    private KodelFormat() {}

    /** Thrown by {@link Reader} when the buffer is shorter than the format needs. */
    public static final class Corruption extends RuntimeException {
        public Corruption(String message) {
            super(message);
        }
    }

    /** Sequential little-endian reader over a byte[] or direct MemorySegment backed byte[]. */
    public static final class Reader {
        private final ByteBuffer in;

        public Reader(byte[] data) {
            in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        public Reader(ByteBuffer data) {
            in = data.order(ByteOrder.LITTLE_ENDIAN);
        }

        public int tell() {
            return in.position();
        }

        private void need(int n) {
            if (in.remaining() < n) throw new Corruption("truncated kodel stream at " + in.position());
        }

        public int u8() {
            need(1);
            return in.get() & 0xFF;
        }

        public int u16() {
            need(2);
            return in.getShort() & 0xFFFF;
        }

        public int i32() {
            need(4);
            return in.getInt();
        }

        public long u32() {
            need(4);
            return in.getInt() & 0xFFFFFFFFL;
        }

        public float f32() {
            need(4);
            return in.getFloat();
        }

        public void vec3(float[] out, int at) {
            need(12);
            out[at] = in.getFloat();
            out[at + 1] = in.getFloat();
            out[at + 2] = in.getFloat();
        }

        public void quat(float[] out, int at) {
            need(16);
            out[at] = in.getFloat();
            out[at + 1] = in.getFloat();
            out[at + 2] = in.getFloat();
            out[at + 3] = in.getFloat();
        }

        /** u16 length prefixed UTF-8. */
        public String string() {
            int n = u16();
            need(n);
            byte[] b = new byte[n];
            in.get(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        public int remaining() {
            return in.remaining();
        }

        // u32 count, refused when the stream can't possibly hold that many. kodels
        // come from content packs, a garbage count must not become a 4GB array
        public int count(int minBytesEach, String what) {
            long n = u32();
            long room = minBytesEach > 0 ? in.remaining() / (long) minBytesEach : Integer.MAX_VALUE;
            if (n > room) {
                throw new Corruption("kodel declares " + n + " " + what + " but only "
                    + in.remaining() + " bytes remain");
            }
            return (int) n;
        }

        public byte[] raw(int n) {
            need(n);
            byte[] b = new byte[n];
            in.get(b);
            return b;
        }
    }

    /** Growing little-endian writer. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public void u8(int v) {
            out.write(v & 0xFF);
        }

        public void u16(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
        }

        public void i32(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
        }

        public void u32(long v) {
            i32((int) v);
        }

        public void f32(float v) {
            i32(Float.floatToIntBits(v));
        }

        public void vec3(float x, float y, float z) {
            f32(x);
            f32(y);
            f32(z);
        }

        public void vec3(float[] v, int at) {
            f32(v[at]);
            f32(v[at + 1]);
            f32(v[at + 2]);
        }

        public void quat(float x, float y, float z, float w) {
            f32(x);
            f32(y);
            f32(z);
            f32(w);
        }

        public void string(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            u16(b.length);
            out.write(b, 0, b.length);
        }

        public byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** Header shared by model.bin and animation.anim.bin. */
    public static void header(Writer w) {
        w.u32(MAGIC);
        w.u8(MAJOR);
        w.u8(MINOR);
        w.u16(0);
    }

    /** Validates and skips a header; returns the trailing reader position. */
    public static int header(Reader r) {
        if (r.u32() != MAGIC) throw new Corruption("bad kodel magic");
        int major = r.u8();
        if (major > MAJOR) throw new Corruption("unsupported kodel v" + major);
        r.u8();
        r.u16();
        return r.tell();
    }

    public static byte[] unsafeBytes(ByteArrayOutputStream out) {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}