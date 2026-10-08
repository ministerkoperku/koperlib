package com.koper.koper_lib.kui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

// everything the client needs to build the container menu + render its kui panel.
// synced once on open by the ExtendedScreenHandlerType.
public record KuiMenuData(String id, String title, int w, int h, String mode, String layout, String state, byte[] png) {
    public static final StreamCodec<RegistryFriendlyByteBuf, KuiMenuData> CODEC = StreamCodec.of(
        (buf, d) -> {
            buf.writeUtf(d.id);
            buf.writeUtf(d.title);
            buf.writeVarInt(d.w);
            buf.writeVarInt(d.h);
            buf.writeUtf(d.mode);
            buf.writeUtf(d.layout, 262144);
            buf.writeUtf(d.state, 32768);
            buf.writeByteArray(d.png);
        },
        buf -> new KuiMenuData(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(),
                               buf.readUtf(262144), buf.readUtf(32768), buf.readByteArray(2_000_000))
    );

    public boolean isTexture() { return "texture".equalsIgnoreCase(mode); }
}
