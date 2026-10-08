package com.koper.koper_lib.kender;

import com.koper.koper_lib.network.*;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class KenderSyncClient {
    private KenderSyncClient() {}
    private static final int MAX_UPDATES = 4_096;
    private static final long MAX_REPLAY_BYTES = 4 * 1024 * 1024;
    private record Pose(long tick,float[] pos,float[] rot,boolean aligned) {}
    private static final class BufferedUpdate {
        final CustomPacketPayload payload;
        net.minecraft.world.level.block.entity.BlockEntity liveTarget;
        BufferedUpdate(CustomPacketPayload payload) { this.payload=payload; }
    }
    private static final class Stage {
        final KenderStreamPayloads.Start start;
        final ArrayList<byte[]> parts = new ArrayList<>();
        final ArrayList<BufferedUpdate> updates = new ArrayList<>();
        final Map<net.minecraft.core.BlockPos,BufferedUpdate> latestBeChange = new HashMap<>();
        ByteArrayOutputStream part = new ByteArrayOutputStream();
        int cells;
        long encodedBytes, replayBytes;
        Pose previous, latest;
        Stage(KenderStreamPayloads.Start start) {
            this.start=new KenderStreamPayloads.Start(start.token(),start.body(),start.serverTick(),start.totalBlocks(),
                start.pos().clone(),start.rot().clone());
        }
    }
    public record Stats(int pendingBodies,long encodedBytes,int bufferedUpdates,long sequence) {}
    private static final Map<Long,Stage> BODIES = new HashMap<>();
    private static final Map<Long,Stage> TOKENS = new HashMap<>();
    private static long received;

    public static void start(KenderStreamPayloads.Start start) {
        cancel(start.body());
        Stage old=TOKENS.get(start.token());
        if (old != null) cancel(old.start.body());
        var stage=new Stage(start);
        BODIES.put(start.body(),stage);
        TOKENS.put(start.token(),stage);
    }

    public static void data(KenderStreamPayloads.Data data,Consumer<KenderStreamPayloads.Ack> ack) {
        if (data.sequence() <= received) { ack.accept(new KenderStreamPayloads.Ack(received)); return; }
        if (data.sequence() != received+1) {
            for (Stage stage : new ArrayList<>(BODIES.values())) abandon(stage,"fragment sequence gap");
            received=data.sequence();
            ack.accept(new KenderStreamPayloads.Ack(received));
            return;
        }
        received=data.sequence();
        Stage stage=TOKENS.get(data.token());
        try {
            if (stage == null) return;
            if (stage.part.size() > KenderSnapshotParts.MAX_BYTES-data.bytes().length)
                throw new IllegalArgumentException("geometry part exceeds byte limit");
            stage.part.writeBytes(data.bytes());
            if (!data.endPart()) return;
            byte[] part=stage.part.toByteArray();
            var snapshot=decodePart(part,stage.start);
            int count=snapshot.geometry().blockStateIds().length;
            if (count == 0 && !(stage.start.totalBlocks() == 0 && stage.parts.isEmpty() && data.endStream())
                || (long)stage.cells+count > stage.start.totalBlocks())
                throw new IllegalArgumentException("invalid geometry stream cell count");
            stage.cells+=count;
            stage.encodedBytes+=part.length;
            stage.parts.add(part);
            stage.part=new ByteArrayOutputStream();
            if (data.endStream()) {
                if (stage.cells != stage.start.totalBlocks()) throw new IllegalArgumentException("incomplete geometry stream");
                complete(stage);
            } else if (stage.cells == stage.start.totalBlocks())
                throw new IllegalArgumentException("geometry stream missing completion flag");
        } catch (RuntimeException broken) {
            if (stage != null) abandon(stage,broken.getMessage());
        } finally {
            // even cancelled/invalid fragments occupied the sender's window
            ack.accept(new KenderStreamPayloads.Ack(received));
        }
    }

    private static KenderSnapshotPayload decodePart(byte[] bytes,KenderStreamPayloads.Start start) {
        var buf=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);
        try {
            buf.skipBytes(44);
            int cells=buf.readVarInt();
            if (cells < 0 || cells > KenderSnapshotParts.MAX_CELLS) throw new IllegalArgumentException("geometry part cell limit");
            buf.readerIndex(0);
            var snapshot=KenderSnapshotPayload.CODEC.decode(buf);
            var p=snapshot.geometry();
            if (buf.isReadable() || snapshot.serverTick() != start.serverTick() || p.kontraId() != start.body()
                || !sameFloats(p.pos(),start.pos()) || !sameFloats(p.rot(),start.rot()))
                throw new IllegalArgumentException("geometry part header mismatch");
            int tags=0;
            for (int i=0; i<p.blockEntityTags().length; i++) if (p.blockEntityTags()[i] != null) {
                if (++tags > 1 || i != p.blockEntityTags().length-1)
                    throw new IllegalArgumentException("geometry part tag boundary");
            }
            return snapshot;
        } finally { buf.release(); }
    }

    private static boolean sameFloats(float[] a,float[] b) {
        if (a.length != b.length) return false;
        for (int i=0; i<a.length; i++) if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) return false;
        return true;
    }

    private static void complete(Stage stage) {
        int n=stage.cells;
        if (n > Integer.MAX_VALUE/3) throw new IllegalArgumentException("geometry arrays exceed addressable size");
        int[] states=new int[n], locals=new int[n*3];
        float[] offsets=new float[n*3];
        CompoundTag[] tags=new CompoundTag[n];
        int cell=0;
        for (byte[] bytes : stage.parts) {
            var p=decodePart(bytes,stage.start).geometry();
            int count=p.blockStateIds().length;
            System.arraycopy(p.blockStateIds(),0,states,cell,count);
            System.arraycopy(p.locals(),0,locals,cell*3,count*3);
            System.arraycopy(p.offsets(),0,offsets,cell*3,count*3);
            System.arraycopy(p.blockEntityTags(),0,tags,cell,count);
            cell+=count;
        }
        boolean newBody=KenderClientState.getById(stage.start.body()) == null;
        Pose latest=stage.latest != null ? stage.latest : new Pose(stage.start.serverTick(),stage.start.pos(),stage.start.rot(),false);
        Pose previous=stage.previous != null ? stage.previous : latest;
        cancel(stage.start.body());
        KenderClientState.spawnSnapshot(latest.tick,stage.start.body(),latest.pos,latest.rot,states,offsets,locals,tags);
        if (newBody) KenderClientState.seedSnapshotMotion(stage.start.body(),previous.tick,previous.pos,previous.rot,
            latest.tick,latest.pos,latest.rot,latest.aligned);
        if (KenderClientState.getById(stage.start.body()) == null) {
            requestResync(stage.start.body());
            return;
        }
        for (BufferedUpdate update : stage.updates) {
            replay(update.payload);
            if (update.payload instanceof KenderBlockDeltaPayload p && !p.removed())
                KenderClientState.reuseStreamBlockEntity(p.kontraId(),p.local(),update.liveTarget,p.blockEntityTag());
            else if (update.payload instanceof KenderBlockEntityUpdatePayload p)
                KenderClientState.reuseStreamBlockEntity(p.kontraId(),p.localPos(),update.liveTarget,p.tag());
        }
    }

    public static void cancel(long body) {
        Stage stage=BODIES.remove(body);
        if (stage != null) TOKENS.remove(stage.start.token(),stage);
    }

    public static void cancelToken(long token,long body) {
        Stage stage=BODIES.get(body);
        if (stage != null && stage.start.token() == token) cancel(body);
    }

    public static boolean pending(long body) { return BODIES.containsKey(body); }

    public static void notePose(long body,long tick,float[] pos,float[] rot,boolean aligned) {
        Stage stage=BODIES.get(body);
        if (stage == null || tick < stage.start.serverTick() || stage.latest != null && tick <= stage.latest.tick) return;
        if (pos.length != 3 || rot.length != 4) return;
        for (float v : pos) if (!Float.isFinite(v)) return;
        for (float v : rot) if (!Float.isFinite(v)) return;
        stage.previous=stage.latest;
        stage.latest=new Pose(tick,pos.clone(),rot.clone(),aligned);
    }

    public static void applyUpdate(long body,CustomPacketPayload payload,Runnable action) {
        Stage stage=BODIES.get(body);
        boolean deliveredEvent=payload instanceof KenderBlockEventPayload event && eventHasTarget(body,event);
        BufferedUpdate retained=null;
        if (stage != null && deliveredEvent && payload instanceof KenderBlockEventPayload event) {
            BufferedUpdate creator=stage.latestBeChange.get(event.localPos());
            if (creator != null) creator.liveTarget=KenderClientState.getById(body).blockEntities.get(event.localPos());
        }
        if (stage != null && !deliveredEvent) {
            CustomPacketPayload copy=copyUpdate(payload);
            long weight=updateBytes(copy);
            if (stage.updates.size() >= MAX_UPDATES || weight > MAX_REPLAY_BYTES-stage.replayBytes)
                abandon(stage,"geometry replay buffer limit");
            else { retained=new BufferedUpdate(copy); stage.updates.add(retained); stage.replayBytes+=weight; }
        }
        action.run();
        if (retained != null) {
            net.minecraft.core.BlockPos local=payload instanceof KenderBlockDeltaPayload p ? p.local()
                : payload instanceof KenderBlockEntityUpdatePayload p ? p.localPos() : null;
            if (local != null) {
                var grid=KenderClientState.getById(body);
                retained.liveTarget=grid == null ? null : grid.blockEntities.get(local);
                stage.latestBeChange.put(local,retained);
            }
        }
    }

    private static boolean eventHasTarget(long body,KenderBlockEventPayload event) {
        var grid=KenderClientState.getById(body);
        if (grid == null || Minecraft.getInstance().level == null) return false;
        var state=grid.localMap.get(event.localPos());
        if (state == null) return false;
        var be=grid.blockEntities.get(event.localPos());
        return !state.hasBlockEntity() || be != null && !be.isRemoved();
    }

    private static CustomPacketPayload copyUpdate(CustomPacketPayload payload) {
        if (payload instanceof KenderBlockEntityUpdatePayload p)
            return new KenderBlockEntityUpdatePayload(p.kontraId(),p.localPos(),p.tag() == null ? null : p.tag().copy());
        if (payload instanceof KenderBlockDeltaPayload p)
            return new KenderBlockDeltaPayload(p.kontraId(),p.local(),p.blockStateId(),p.ox(),p.oy(),p.oz(),p.removed(),
                p.blockEntityTag() == null ? null : p.blockEntityTag().copy());
        if (payload instanceof KenderGridBlockUpdatePayload || payload instanceof KenderBlockEventPayload) return payload;
        throw new IllegalArgumentException("unsupported geometry replay update");
    }

    private static long updateBytes(CustomPacketPayload payload) {
        CompoundTag tag=payload instanceof KenderBlockEntityUpdatePayload p ? p.tag()
            : payload instanceof KenderBlockDeltaPayload p ? p.blockEntityTag() : null;
        if (tag == null) return 128;
        int size=tag.sizeInBytes();
        return size < 0 ? Long.MAX_VALUE : 128L+size;
    }

    private static void replay(CustomPacketPayload payload) {
        if (payload instanceof KenderGridBlockUpdatePayload p) KenderClientState.updateBlockState(p.kontraId(),p.localPos(),p.blockStateId());
        else if (payload instanceof KenderBlockEntityUpdatePayload p) KenderClientState.updateBlockEntity(p.kontraId(),p.localPos(),p.tag());
        else if (payload instanceof KenderBlockEventPayload p) KenderClientState.blockEvent(p.kontraId(),p.localPos(),p.eventId(),p.eventData());
        else if (payload instanceof KenderBlockDeltaPayload p) KenderClientState.applyDelta(p.kontraId(),p.local(),p.blockStateId(),
            p.ox(),p.oy(),p.oz(),p.removed(),p.blockEntityTag());
    }

    private static void abandon(Stage stage,String reason) {
        cancel(stage.start.body());
        com.koper.koper_lib.coremod.KoperCore.LOGGER.debug("[Kender] abandoned geometry stream {} for {}: {}",
            stage.start.token(),stage.start.body(),reason);
        requestResync(stage.start.body());
    }

    private static void requestResync(long body) {
        if (Minecraft.getInstance().getConnection() != null
            && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(KenderResyncPayload.TYPE))
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new KenderResyncPayload(body));
    }

    public static Stats stats() {
        long bytes=0; int updates=0;
        for (Stage stage : BODIES.values()) { bytes+=stage.encodedBytes+stage.part.size(); updates+=stage.updates.size(); }
        return new Stats(BODIES.size(),bytes,updates,received);
    }

    public static void clear() { BODIES.clear(); TOKENS.clear(); received=0; }
}
