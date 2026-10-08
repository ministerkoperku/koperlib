package com.koper.koper_lib.network;

import com.koper.koper_lib.kfx.runtime.KfxImpact;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

public record KfxImpactPayload(
    long handle,
    int nodeId,
    long sequence,
    UUID owner,
    UUID entity,
    BlockPos block,
    Vec3 position,
    Vec3 normal,
    Vec3 incomingVelocity,
    Vec3 outgoingVelocity,
    int bounce,
    long seed
) implements CustomPacketPayload {
    public static final Type<KfxImpactPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_impact"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KfxImpactPayload> CODEC = StreamCodec.of(
        KfxImpactPayload::write,
        KfxImpactPayload::read
    );

    public static KfxImpactPayload from(KfxImpact impact) {
        return new KfxImpactPayload(impact.handle(), impact.nodeId(), impact.sequence(), impact.owner(),
            impact.entity(), impact.block(), impact.position(), impact.normal(), impact.incomingVelocity(),
            impact.outgoingVelocity(), impact.bounce(), impact.seed());
    }

    public KfxImpact toImpact() {
        return new KfxImpact(handle, nodeId, sequence, owner, entity, block, position, normal,
            incomingVelocity, outgoingVelocity, bounce, seed, Map.of());
    }

    private static void write(RegistryFriendlyByteBuf buf, KfxImpactPayload it) {
        buf.writeLong(it.handle);
        buf.writeVarInt(it.nodeId);
        buf.writeVarLong(it.sequence);
        buf.writeUUID(it.owner);
        int flags = (it.entity == null ? 0 : 1) | (it.block == null ? 0 : 2);
        buf.writeByte(flags);
        if (it.entity != null) buf.writeUUID(it.entity);
        if (it.block != null) buf.writeLong(it.block.asLong());
        writePosition(buf, it.position);
        writeVector(buf, it.normal);
        writeVector(buf, it.incomingVelocity);
        writeVector(buf, it.outgoingVelocity);
        buf.writeVarInt(it.bounce);
        buf.writeLong(it.seed);
    }

    private static KfxImpactPayload read(RegistryFriendlyByteBuf buf) {
        long handle = buf.readLong();
        int nodeId = buf.readVarInt();
        long sequence = buf.readVarLong();
        UUID owner = buf.readUUID();
        int flags = buf.readUnsignedByte();
        if ((flags & ~3) != 0) throw new IllegalArgumentException("unknown KFX impact flags " + flags);
        UUID entity = (flags & 1) == 0 ? null : buf.readUUID();
        BlockPos block = (flags & 2) == 0 ? null : BlockPos.of(buf.readLong());
        return new KfxImpactPayload(handle, nodeId, sequence, owner, entity, block,
            readPosition(buf), readVector(buf), readVector(buf), readVector(buf),
            buf.readVarInt(), buf.readLong());
    }

    private static void writePosition(RegistryFriendlyByteBuf buf, Vec3 value) {
        buf.writeDouble(value.x); buf.writeDouble(value.y); buf.writeDouble(value.z);
    }

    private static Vec3 readPosition(RegistryFriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeVector(RegistryFriendlyByteBuf buf, Vec3 value) {
        buf.writeFloat((float)value.x); buf.writeFloat((float)value.y); buf.writeFloat((float)value.z);
    }

    private static Vec3 readVector(RegistryFriendlyByteBuf buf) {
        return new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
