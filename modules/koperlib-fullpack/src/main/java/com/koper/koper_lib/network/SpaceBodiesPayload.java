package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Everything a client needs to draw the space bodies; the whole list every time it changes. */
public record SpaceBodiesPayload(List<Body> bodies) implements CustomPacketPayload {
    public record Body(String id, String space, double x, double y, double z, double radius, double atmosphere,
                       String texture, int color, int glow, float spin) {}

    public static final Type<SpaceBodiesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "space_bodies"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpaceBodiesPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarInt(payload.bodies().size());
            for (Body b : payload.bodies()) {
                buf.writeUtf(b.id()); buf.writeUtf(b.space());
                buf.writeDouble(b.x()); buf.writeDouble(b.y()); buf.writeDouble(b.z());
                buf.writeDouble(b.radius()); buf.writeDouble(b.atmosphere());
                buf.writeUtf(b.texture()); buf.writeInt(b.color()); buf.writeInt(b.glow()); buf.writeFloat(b.spin());
            }
        },
        buf -> {
            int n = buf.readVarInt();
            List<Body> bodies = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                bodies.add(new Body(buf.readUtf(), buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readUtf(), buf.readInt(), buf.readInt(), buf.readFloat()));
            return new SpaceBodiesPayload(List.copyOf(bodies));
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
