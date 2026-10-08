package com.koper.koper_lib.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.Arrays;

public final class KenderSnapshotParts {
    public static final int MAX_CELLS = 4_096;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private final KenderSnapshotPayload snapshot;
    private int nextCell;
    private boolean emptySent;

    public KenderSnapshotParts(KenderSnapshotPayload source) {
        var p=source.geometry();
        int n=p.blockStateIds().length;
        if (n > Integer.MAX_VALUE/3 || p.offsets().length != n*3 || p.locals().length != n*3
            || p.pos().length != 3 || p.rot().length != 4 || p.blockEntityTags() == null)
            throw new IllegalArgumentException("invalid geometry arrays for streaming");
        var tags=new CompoundTag[n];
        for (int i=0; i<Math.min(n,p.blockEntityTags().length); i++)
            if (p.blockEntityTags()[i] != null) tags[i]=p.blockEntityTags()[i].copy();
        snapshot=new KenderSnapshotPayload(source.serverTick(),new KenderSpawnPayload(p.kontraId(),p.pos().clone(),p.rot().clone(),
            p.blockStateIds().clone(),p.offsets().clone(),p.locals().clone(),tags));
    }

    public boolean hasNext() {
        int n=snapshot.geometry().blockStateIds().length;
        return n == 0 ? !emptySent : nextCell < n;
    }

    public byte[] next() {
        if (!hasNext()) return null;
        var p=snapshot.geometry();
        int end=Math.min(p.blockStateIds().length,nextCell+MAX_CELLS);
        for (int i=nextCell; i<end; i++) if (p.blockEntityTags()[i] != null) { end=i+1; break; }
        var part=new KenderSnapshotPayload(snapshot.serverTick(),new KenderSpawnPayload(p.kontraId(),p.pos(),p.rot(),
            Arrays.copyOfRange(p.blockStateIds(),nextCell,end),Arrays.copyOfRange(p.offsets(),nextCell*3,end*3),
            Arrays.copyOfRange(p.locals(),nextCell*3,end*3),Arrays.copyOfRange(p.blockEntityTags(),nextCell,end)));
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(256,MAX_BYTES), RegistryAccess.EMPTY);
        try {
            KenderSnapshotPayload.CODEC.encode(buf,part);
            byte[] bytes=new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            nextCell=end;
            emptySent=true;
            return bytes;
        } finally { buf.release(); }
    }
}
