package com.koper.koper_lib.kodel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The texture a .kodel draws with. A pack texture the binding names wins when the pack ships it;
 * otherwise the png packed inside the .kodel itself is uploaded once and used, so a model that only
 * exists as a .kodel still draws.
 */
public final class KodelTextures {
    private KodelTextures() {}

    private static final Map<String, Identifier> CHOSEN = new ConcurrentHashMap<>();

    public static void clear() {
        CHOSEN.clear();
    }

    public static Identifier of(String model, Identifier named) {
        return CHOSEN.computeIfAbsent(model + '|' + named, key -> choose(model, named));
    }

    private static Identifier choose(String model, Identifier named) {
        Minecraft mc = Minecraft.getInstance();
        if (named != null && mc.getResourceManager().getResource(named).isPresent())
            return named;
        KodelBook.Entry entry = KodelBook.get(model);
        byte[] png = entry == null ? null : entry.texture();
        if (png == null || png.length == 0) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                "[Kodel] {} has no texture: {} is not in any pack and the .kodel carries none", model, named);
            return named;
        }
        try {
            Identifier id = Identifier.fromNamespaceAndPath("koperlib", "kodel_texture/" + model.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9/._-]", "_"));
            mc.getTextureManager().register(id, new DynamicTexture(id::toString, NativeImage.read(png)));
            return id;
        } catch (java.io.IOException broken) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kodel] {} carries an unreadable texture", model, broken);
            return named;
        }
    }
}
