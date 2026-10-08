package com.koper.koper_lib.network;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class KenderStreamQueue {
    public static final class EncodingFailure extends RuntimeException {
        private final long body;
        private EncodingFailure(long body,RuntimeException cause) { super("cannot encode geometry for "+body,cause); this.body=body; }
        public long body() { return body; }
    }
    private static final class Transfer {
        final long token, body;
        final KenderSnapshotParts parts;
        byte[] part;
        int position;
        Transfer(long token,KenderSnapshotPayload snapshot) {
            if (token <= 0 || snapshot.geometry().kontraId() <= 0) throw new IllegalArgumentException("invalid geometry stream identity");
            this.token=token;
            this.body=snapshot.geometry().kontraId();
            this.parts=new KenderSnapshotParts(snapshot);
        }
    }
    private record Flight(long sequence,int bytes) {}
    private final Map<Long,Transfer> bodies=new HashMap<>();
    private final ArrayDeque<Transfer> ready=new ArrayDeque<>();
    private final ArrayDeque<Flight> flight=new ArrayDeque<>();
    private long sent, acknowledged, inFlight;

    public long offer(long token,KenderSnapshotPayload snapshot) {
        var transfer=new Transfer(token,snapshot);
        long previous=cancel(transfer.body);
        bodies.put(transfer.body,transfer);
        ready.addLast(transfer);
        return previous;
    }

    public long cancel(long body) {
        Transfer transfer=bodies.remove(body);
        if (transfer == null) return 0;
        ready.remove(transfer);
        // fragments already sent still occupy the peer's ACK window
        return transfer.token;
    }

    public void drain(int byteBudget,int windowBytes,int chunkBytes,Consumer<KenderStreamPayloads.Data> output) {
        if (byteBudget <= KenderStreamPayloads.FRAME_OVERHEAD || windowBytes <= KenderStreamPayloads.FRAME_OVERHEAD || chunkBytes <= 0) return;
        long remaining=byteBudget;
        int limit=Math.min(chunkBytes,KenderStreamPayloads.MAX_FRAGMENT_BYTES);
        while (!ready.isEmpty()) {
            long available=Math.min(remaining,windowBytes-inFlight)-KenderStreamPayloads.FRAME_OVERHEAD;
            if (available <= 0) return;
            Transfer transfer=ready.peekFirst();
            if (transfer.part == null) {
                try { transfer.part=transfer.parts.next(); transfer.position=0; }
                catch (RuntimeException failure) { throw new EncodingFailure(transfer.body,failure); }
            }
            int count=(int)Math.min(Math.min(available,limit),transfer.part.length-transfer.position);
            byte[] bytes=Arrays.copyOfRange(transfer.part,transfer.position,transfer.position+count);
            boolean endPart=transfer.position+count == transfer.part.length;
            boolean endStream=endPart && !transfer.parts.hasNext();
            if (sent == Long.MAX_VALUE) throw new IllegalStateException("geometry stream sequence exhausted");
            var frame=new KenderStreamPayloads.Data(transfer.token,++sent,endPart,endStream,bytes);
            int accounted=count+KenderStreamPayloads.FRAME_OVERHEAD;
            inFlight+=accounted;
            remaining-=accounted;
            flight.addLast(new Flight(sent,accounted));
            ready.removeFirst();
            transfer.position+=count;
            if (endPart) transfer.part=null;
            if (endStream) bodies.remove(transfer.body);
            else ready.addLast(transfer);
            output.accept(frame);
        }
    }

    public boolean ack(long sequence) {
        if (sequence <= acknowledged || sequence > sent) return false;
        while (!flight.isEmpty() && flight.peekFirst().sequence <= sequence) inFlight-=flight.removeFirst().bytes;
        acknowledged=sequence;
        return true;
    }

    public int queuedBodies() { return bodies.size(); }
    public long inFlightBytes() { return inFlight; }
}
