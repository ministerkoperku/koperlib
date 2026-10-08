package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// S2C heartbeat, 1/sec — one content hash per live kontra in the player's dimension.
// the whole kontra block list only ever reaches the client through KenderSpawnPayload, so ONE
// broadcast that never got sent (or got skipped because the pose cache blinked) left the client
// rendering a block the server deleted. forever. that's the ghost block. now the client can tell.
// ids is also the full "these exist" list — a kontra missing from it got its remove packet eaten.
public record KenderStampPayload(long[] ids, long[] stamps) implements CustomPacketPayload {

    public static final Type<KenderStampPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_stamp"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderStampPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeInt(p.ids().length);
            for (int i = 0; i < p.ids().length; i++) {
                buf.writeLong(p.ids()[i]);
                buf.writeLong(p.stamps()[i]);
            }
        },
        buf -> {
            int n = buf.readInt();
            long[] ids = new long[n];
            long[] stamps = new long[n];
            for (int i = 0; i < n; i++) { ids[i] = buf.readLong(); stamps[i] = buf.readLong(); }
            return new KenderStampPayload(ids, stamps);
        }
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
