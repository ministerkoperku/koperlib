package com.koper.koper_lib.bedrock;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

// bedrock writes nbt little endian (mcstructure, leveldb, items in block entities). java's NbtIo only
// speaks big endian, so this reads the same tag types the other way round into java's own tag classes
final class BedrockNbtLe {

    private BedrockNbtLe() {}

    static CompoundTag czytaj(byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            byte typ = b.get();
            if (typ != Tag.TAG_COMPOUND) throw new IOException("root tag is " + typ + ", not a compound");
            napis(b); // root name, always empty in practice
            return (CompoundTag) tag(b, typ, 0);
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            throw new IOException("broken little endian nbt at byte " + b.position() + ": " + e, e);
        }
    }

    private static String napis(ByteBuffer b) {
        int n = Short.toUnsignedInt(b.getShort());
        byte[] raw = new byte[n];
        b.get(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static Tag tag(ByteBuffer b, byte typ, int glebia) throws IOException {
        if (glebia > 512) throw new IOException("nbt nested deeper than 512");
        return switch (typ) {
            case Tag.TAG_BYTE -> ByteTag.valueOf(b.get());
            case Tag.TAG_SHORT -> ShortTag.valueOf(b.getShort());
            case Tag.TAG_INT -> IntTag.valueOf(b.getInt());
            case Tag.TAG_LONG -> LongTag.valueOf(b.getLong());
            case Tag.TAG_FLOAT -> FloatTag.valueOf(b.getFloat());
            case Tag.TAG_DOUBLE -> DoubleTag.valueOf(b.getDouble());
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] a = new byte[b.getInt()];
                b.get(a);
                yield new ByteArrayTag(a);
            }
            case Tag.TAG_STRING -> StringTag.valueOf(napis(b));
            case Tag.TAG_LIST -> {
                byte et = b.get();
                int n = b.getInt();
                ListTag l = new ListTag();
                for (int i = 0; i < n; i++) l.add(tag(b, et, glebia + 1));
                yield l;
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag c = new CompoundTag();
                while (true) {
                    byte t = b.get();
                    if (t == Tag.TAG_END) break;
                    String k = napis(b);
                    c.put(k, tag(b, t, glebia + 1));
                }
                yield c;
            }
            case Tag.TAG_INT_ARRAY -> {
                int[] a = new int[b.getInt()];
                for (int i = 0; i < a.length; i++) a[i] = b.getInt();
                yield new IntArrayTag(a);
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] a = new long[b.getInt()];
                for (int i = 0; i < a.length; i++) a[i] = b.getLong();
                yield new LongArrayTag(a);
            }
            default -> throw new IOException("unknown nbt tag type " + typ);
        };
    }
}
