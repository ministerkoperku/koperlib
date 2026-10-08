package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class KenderStreamPayloads {
    public static final int MAX_FRAGMENT_BYTES = 65_536;
    public static final int FRAME_OVERHEAD = 128;

    private KenderStreamPayloads() {}

    public record Start(long token, long body, long serverTick, int totalBlocks, float[] pos, float[] rot)
            implements CustomPacketPayload {
        public Start {
            if (token <= 0 || body <= 0 || serverTick < 0 || totalBlocks < 0 || pos == null || pos.length != 3
                || rot == null || rot.length != 4) throw new IllegalArgumentException("invalid geometry stream header");
        }
        public static final Type<Start> TYPE = typeOf("kender_stream_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeLong(p.token); buf.writeLong(p.body); buf.writeLong(p.serverTick); buf.writeVarInt(p.totalBlocks);
            for (float f : p.pos) buf.writeInt(Float.floatToRawIntBits(f));
            for (float f : p.rot) buf.writeInt(Float.floatToRawIntBits(f));
        }, buf -> new Start(buf.readLong(), buf.readLong(), buf.readLong(), buf.readVarInt(), readFloats(buf,3), readFloats(buf,4)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Data(long token, long sequence, boolean endPart, boolean endStream, byte[] bytes)
            implements CustomPacketPayload {
        public Data {
            if (token <= 0 || sequence <= 0 || endStream && !endPart || bytes == null
                || bytes.length == 0 || bytes.length > MAX_FRAGMENT_BYTES)
                throw new IllegalArgumentException("invalid geometry stream fragment");
        }
        public static final Type<Data> TYPE = typeOf("kender_stream_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of((buf,p) -> {
            buf.writeLong(p.token); buf.writeLong(p.sequence); buf.writeBoolean(p.endPart); buf.writeBoolean(p.endStream);
            buf.writeByteArray(p.bytes);
        },buf -> new Data(buf.readLong(),buf.readLong(),buf.readBoolean(),buf.readBoolean(),buf.readByteArray(MAX_FRAGMENT_BYTES)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Ack(long sequence) implements CustomPacketPayload {
        public Ack { if (sequence <= 0) throw new IllegalArgumentException("invalid geometry ACK"); }
        public static final Type<Ack> TYPE = typeOf("kender_stream_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf, Ack> CODEC =
            StreamCodec.of((buf,p) -> buf.writeLong(p.sequence),buf -> new Ack(buf.readLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Cancel(long token, long body) implements CustomPacketPayload {
        public Cancel { if (token <= 0 || body <= 0) throw new IllegalArgumentException("invalid geometry stream cancellation"); }
        public static final Type<Cancel> TYPE = typeOf("kender_stream_cancel");
        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> CODEC =
            StreamCodec.of((buf,p) -> { buf.writeLong(p.token); buf.writeLong(p.body); },buf -> new Cancel(buf.readLong(),buf.readLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static <P extends CustomPacketPayload> CustomPacketPayload.Type<P> typeOf(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("koper_lib",name));
    }

    private static float[] readFloats(RegistryFriendlyByteBuf buf,int count) {
        float[] out = new float[count];
        for (int i=0; i<count; i++) out[i]=Float.intBitsToFloat(buf.readInt());
        return out;
    }
}
