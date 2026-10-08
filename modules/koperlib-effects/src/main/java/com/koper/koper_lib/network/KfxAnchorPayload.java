package com.koper.koper_lib.network;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.graph.KfxSocket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public record KfxAnchorPayload(long id, KfxAnchor start, KfxAnchor end) implements CustomPacketPayload {
    public static final Type<KfxAnchorPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_anchor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxAnchorPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeLong(payload.id);
            write(buf, payload.start, 0);
            write(buf, payload.end, 0);
        },
        buf -> new KfxAnchorPayload(buf.readLong(), read(buf, 0), read(buf, 0))
    );

    public int estimatedSize() {
        return 8 + estimated(start) + estimated(end);
    }

    private static void write(RegistryFriendlyByteBuf buf, KfxAnchor anchor, int depth) {
        if (depth > 4) throw new IllegalArgumentException("KFX anchor nesting is too deep");
        if (anchor instanceof KfxAnchor.World world) {
            buf.writeByte(0); writePolicy(buf, world.missing()); writeVec(buf, world.position()); writeVec(buf, world.normal());
        } else if (anchor instanceof KfxAnchor.Entity entity) {
            buf.writeByte(1); writePolicy(buf, entity.missing()); buf.writeVarInt(entity.entityId());
            buf.writeByte(entity.socket().ordinal()); writeVec(buf, entity.offset());
        } else if (anchor instanceof KfxAnchor.Bone bone) {
            buf.writeByte(2); writePolicy(buf, bone.missing()); buf.writeVarInt(bone.entityId());
            buf.writeUtf(bone.bone(), 128); write(buf, bone.fallback(), depth + 1);
        } else if (anchor instanceof KfxAnchor.Between between) {
            buf.writeByte(3); writePolicy(buf, between.missing()); buf.writeFloat(between.mix());
            write(buf, between.start(), depth + 1); write(buf, between.end(), depth + 1);
        } else {
            throw new IllegalArgumentException("unknown KFX anchor " + anchor);
        }
    }

    private static KfxAnchor read(RegistryFriendlyByteBuf buf, int depth) {
        if (depth > 4) throw new IllegalArgumentException("KFX anchor nesting is too deep");
        int kind = buf.readUnsignedByte();
        KfxMissingPolicy missing = readPolicy(buf);
        return switch (kind) {
            case 0 -> new KfxAnchor.World(readVec(buf), readVec(buf), missing);
            case 1 -> new KfxAnchor.Entity(buf.readVarInt(), socket(buf.readUnsignedByte()), readVec(buf), missing);
            case 2 -> {
                int entityId = buf.readVarInt();
                String bone = buf.readUtf(128);
                KfxAnchor fallback = read(buf, depth + 1);
                if (!(fallback instanceof KfxAnchor.Entity entity)) {
                    throw new IllegalArgumentException("KFX bone fallback must be an entity socket");
                }
                yield new KfxAnchor.Bone(entityId, bone, entity, missing);
            }
            case 3 -> {
                float mix = buf.readFloat();
                yield new KfxAnchor.Between(read(buf, depth + 1), read(buf, depth + 1), mix, missing);
            }
            default -> throw new IllegalArgumentException("unknown KFX anchor wire kind " + kind);
        };
    }

    private static void writeVec(RegistryFriendlyByteBuf buf, Vec3 vec) {
        buf.writeFloat((float)vec.x); buf.writeFloat((float)vec.y); buf.writeFloat((float)vec.z);
    }

    private static Vec3 readVec(RegistryFriendlyByteBuf buf) {
        return new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    private static void writePolicy(RegistryFriendlyByteBuf buf, KfxMissingPolicy policy) {
        buf.writeByte(policy.ordinal());
    }

    private static KfxMissingPolicy readPolicy(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= KfxMissingPolicy.values().length) throw new IllegalArgumentException("unknown KFX missing policy " + ordinal);
        return KfxMissingPolicy.values()[ordinal];
    }

    private static KfxSocket socket(int ordinal) {
        if (ordinal >= KfxSocket.values().length) throw new IllegalArgumentException("unknown KFX socket " + ordinal);
        return KfxSocket.values()[ordinal];
    }

    private static int estimated(KfxAnchor anchor) {
        if (anchor instanceof KfxAnchor.World) return 26;
        if (anchor instanceof KfxAnchor.Entity) return 20;
        if (anchor instanceof KfxAnchor.Bone bone) return 9 + bone.bone().length() * 3 + estimated(bone.fallback());
        if (anchor instanceof KfxAnchor.Between between) return 6 + estimated(between.start()) + estimated(between.end());
        return Integer.MAX_VALUE / 4;
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
