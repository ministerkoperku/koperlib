package com.koper.koper_lib.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class KenderStreamQueueTest {
    @Test void encodingFailureIdentifiesItsBodyAndDoesNotBlockOtherTransfers() {
        var queue=new KenderStreamQueue();
        var bad=fixture(2,1,false);
        var tag=new CompoundTag();
        tag.putByteArray("too_large",new byte[8*1024*1024]);
        bad.geometry().blockEntityTags()[0]=tag;
        queue.offer(1,fixture(1,1,false));
        queue.offer(2,bad);
        queue.offer(3,fixture(3,1,false));
        var frames=new ArrayList<KenderStreamPayloads.Data>();
        var failure=assertThrows(KenderStreamQueue.EncodingFailure.class,() -> queue.drain(4096,4096,1024,frames::add));
        assertEquals(2,failure.body());
        assertEquals(1,frames.size());
        long inFlight=queue.inFlightBytes();
        queue.cancel(failure.body());
        assertEquals(inFlight,queue.inFlightBytes());
        queue.drain(4096,4096,1024,frames::add);
        assertEquals(2,frames.size());
        assertEquals(3,frames.getLast().token());
        assertEquals(0,queue.queuedBodies());
    }

    @Test void cancelledCompletedFramesRemainAccountedUntilCumulativeAck() {
        var queue = new KenderStreamQueue();
        queue.offer(1, fixture(1, 17, false));
        var frames = new ArrayList<KenderStreamPayloads.Data>();
        queue.drain(4096,4096,1024,frames::add);
        assertEquals(1,frames.size());
        assertEquals(0,queue.queuedBodies());
        long bytes = queue.inFlightBytes();
        assertTrue(bytes > 128);
        assertEquals(0,queue.cancel(1));
        assertEquals(bytes,queue.inFlightBytes());
        assertFalse(queue.ack(0));
        assertFalse(queue.ack(2));
        assertTrue(queue.ack(1));
        assertEquals(0,queue.inFlightBytes());
    }

    @Test void enqueueCapturesGeometryBeforeItsSourceChanges() {
        var original = fixture(1,2,true);
        var parts = new KenderSnapshotParts(original);
        original.geometry().blockStateIds()[0] = 100;
        original.geometry().locals()[0] = 100;
        original.geometry().offsets()[0] = 100;
        original.geometry().blockEntityTags()[1].putInt("changed",1);
        var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(parts.next()), RegistryAccess.EMPTY);
        try {
            var decoded = KenderSnapshotPayload.CODEC.decode(buf).geometry();
            assertEquals(1,decoded.blockStateIds()[0]);
            assertEquals(0,decoded.locals()[0]);
            assertEquals(Float.floatToRawIntBits(-0.25f),Float.floatToRawIntBits(decoded.offsets()[0]));
            assertFalse(decoded.blockEntityTags()[1].contains("changed"));
        } finally { buf.release(); }
    }

    @Test void eachPartStopsAtItsFirstTagAndRejectsOversizedNbt() {
        var snapshot = fixture(1,5,false);
        snapshot.geometry().blockEntityTags()[1] = new CompoundTag();
        snapshot.geometry().blockEntityTags()[3] = new CompoundTag();
        var parts = new KenderSnapshotParts(snapshot);
        for (int count : new int[]{2,2,1}) {
            var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(parts.next()), RegistryAccess.EMPTY);
            try { assertEquals(count,KenderSnapshotPayload.CODEC.decode(buf).geometry().blockStateIds().length); }
            finally { buf.release(); }
        }
        assertNull(parts.next());
        var large = fixture(1,1,false);
        var tag = new CompoundTag();
        tag.putByteArray("too_large",new byte[8*1024*1024]);
        large.geometry().blockEntityTags()[0] = tag;
        assertThrows(RuntimeException.class,() -> new KenderSnapshotParts(large).next());
    }

    @Test void absentAckStopsGeometryAtAggregateWindow() {
        var queue = new KenderStreamQueue();
        queue.offer(1, fixture(1, 1000, false));
        var frames = new ArrayList<KenderStreamPayloads.Data>();
        queue.drain(1024, 1024, 384, frames::add);
        assertEquals(2, frames.size());
        assertEquals(1024, queue.inFlightBytes());
        queue.drain(1024, 1024, 384, frames::add);
        assertEquals(2, frames.size(), "no ACK must mean no further geometry");
        assertFalse(queue.ack(3), "future ACK must not free credit");
        assertEquals(1024, queue.inFlightBytes());
        assertTrue(queue.ack(1));
        assertEquals(512, queue.inFlightBytes());
        assertFalse(queue.ack(1), "duplicate ACK must not free credit twice");
        queue.drain(1024, 1024, 384, frames::add);
        assertEquals(3, frames.size());
        assertEquals(1024, queue.inFlightBytes());
    }

    @Test void serviceIsFairAcrossBodiesAndRespectsTickBudget() {
        var queue = new KenderStreamQueue();
        queue.offer(11, fixture(1, 1000, false));
        queue.offer(22, fixture(2, 1000, false));
        var frames = new ArrayList<KenderStreamPayloads.Data>();
        queue.drain(2048, 2048, 384, frames::add);
        assertEquals(java.util.List.of(11L,22L,11L,22L), frames.stream().map(KenderStreamPayloads.Data::token).toList());
        assertEquals(2048, queue.inFlightBytes());
        assertTrue(frames.stream().allMatch(frame -> frame.bytes().length <= 384));
    }

    @Test void replacementDoesNotReleaseUnacknowledgedData() {
        var queue = new KenderStreamQueue();
        queue.offer(1, fixture(1, 1000, false));
        var frames = new ArrayList<KenderStreamPayloads.Data>();
        queue.drain(512, 512, 384, frames::add);
        assertEquals(1, queue.cancel(1));
        queue.offer(2, fixture(1, 17, false));
        queue.drain(512, 512, 384, frames::add);
        assertEquals(1, frames.size(), "cancelled bytes are still in flight");
        assertEquals(512, queue.inFlightBytes());
        assertTrue(queue.ack(1));
        queue.drain(512, 512, 384, frames::add);
        assertEquals(2, frames.size());
        assertEquals(2, frames.get(1).token());
        assertTrue(frames.get(1).endStream());
        assertEquals(0, queue.queuedBodies());
        assertTrue(queue.ack(2));
        assertEquals(0, queue.inFlightBytes());
    }

    @Test void delayedAckTransferReconstructsEveryCellAndLargeTag() {
        var snapshot = fixture(1, 10_000, true);
        var queue = new KenderStreamQueue();
        queue.offer(5, snapshot);
        var part = new ByteArrayOutputStream();
        int copied = 0, framesSent = 0;
        boolean finished = false;
        long lastSequence = 0;
        for (int tick = 0; tick < 1000 && !finished; tick++) {
            var frames = new ArrayList<KenderStreamPayloads.Data>();
            queue.drain(2048, 4096, 511, frames::add);
            int accounted = 0;
            for (var frame : frames) {
                framesSent++;
                accounted += frame.bytes().length + 128;
                assertTrue(frame.bytes().length <= 511);
                assertEquals(++lastSequence, frame.sequence());
                part.writeBytes(frame.bytes());
                if (frame.endPart()) {
                    var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(part.toByteArray()), RegistryAccess.EMPTY);
                    try {
                        var decoded = KenderSnapshotPayload.CODEC.decode(buf);
                        assertEquals(0, buf.readableBytes());
                        var p = decoded.geometry();
                        assertTrue(p.blockStateIds().length <= 4096);
                        for (int i = 0; i < p.blockStateIds().length; i++) {
                            int target = copied + i;
                            assertEquals(snapshot.geometry().blockStateIds()[target], p.blockStateIds()[i]);
                            assertEquals(snapshot.geometry().blockEntityTags()[target], p.blockEntityTags()[i]);
                            for (int axis = 0; axis < 3; axis++) {
                                assertEquals(snapshot.geometry().locals()[target * 3 + axis], p.locals()[i * 3 + axis]);
                                assertEquals(Float.floatToRawIntBits(snapshot.geometry().offsets()[target * 3 + axis]),
                                    Float.floatToRawIntBits(p.offsets()[i * 3 + axis]));
                            }
                        }
                        copied += p.blockStateIds().length;
                    } finally { buf.release(); }
                    part.reset();
                }
                if (frame.endStream()) { assertTrue(frame.endPart()); finished = true; }
            }
            assertTrue(accounted <= 2048);
            assertTrue(queue.inFlightBytes() <= 4096);
            if (tick % 3 == 2 && lastSequence > 0) queue.ack(lastSequence);
        }
        assertTrue(finished);
        assertEquals(10_000, copied);
        assertTrue(framesSent > 20, "large NBT must span fragments");
        queue.ack(lastSequence);
        assertEquals(0, queue.inFlightBytes());
    }

    @Test void startAndDataCodecsRejectMalformedMetadata() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buf.writeLong(1); buf.writeLong(1); buf.writeLong(1); buf.writeVarInt(-1); buf.writeZero(28);
            assertThrows(RuntimeException.class, () -> KenderStreamPayloads.Start.CODEC.decode(buf));
            buf.clear();
            buf.writeLong(1); buf.writeLong(1); buf.writeBoolean(false); buf.writeBoolean(true); buf.writeByteArray(new byte[]{1});
            assertThrows(RuntimeException.class, () -> KenderStreamPayloads.Data.CODEC.decode(buf));
        } finally { buf.release(); }
    }

    private static KenderSnapshotPayload fixture(long id, int n, boolean withTag) {
        int[] states = new int[n], locals = new int[n * 3];
        float[] offsets = new float[n * 3];
        var tags = new CompoundTag[n];
        for (int i = 0; i < n; i++) {
            states[i] = 1 + i % 3;
            locals[i*3] = i % 100;
            locals[i*3+1] = i / 100;
            for (int axis = 0; axis < 3; axis++) offsets[i*3+axis] = locals[i*3+axis] - .25f;
        }
        if (withTag) {
            var tag = new CompoundTag(); tag.putByteArray("large", new byte[17_000]); tags[n / 2] = tag;
        }
        return new KenderSnapshotPayload(100, new KenderSpawnPayload(id, new float[3], new float[]{0,0,0,1}, states, offsets, locals, tags));
    }
}
