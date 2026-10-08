package com.koper.koper_lib.kodel.bedrock;

import com.koper.koper_lib.coremod.KoperCore;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// color mask materials (change_color, multicolor_tint, *_color_mask) keep the tint mask in alpha:
// 0 = nothing (only under alpha test), small = plain texture, high = multiply by the mob's color.
// java has no such shader, so the texture is split once into two with disjoint opaque pixels:
// "plain" drawn as is and "tinted" drawn with the color. same geometry twice, no z fighting
public final class BrMaska {

    public record Para(Identifier plain, Identifier tinted) {}

    private record Klucz(Identifier tex, boolean alphaTest) {}

    private static final Map<Klucz, Para> CACHE = new HashMap<>();
    private static final Para NONE = new Para(null, null);

    private BrMaska() {}

    // render thread only (texture registration)
    public static Para para(Identifier tex, boolean alphaTest) {
        Klucz key = new Klucz(tex, alphaTest);
        Para p = CACHE.get(key);
        if (p == null) {
            p = split(tex, alphaTest);
            CACHE.put(key, p);
        }
        return p == NONE ? null : p;
    }

    private static Para split(Identifier tex, boolean alphaTest) {
        var rm = Minecraft.getInstance().getResourceManager();
        var res = rm.getResource(tex);
        if (res.isEmpty()) return NONE;
        try (InputStream in = res.get().open(); NativeImage src = NativeImage.read(in)) {
            int w = src.getWidth(), h = src.getHeight();
            NativeImage plain = new NativeImage(w, h, false), tinted = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int c = src.getPixel(x, y), a = c >>> 24, rgb = c & 0x00FFFFFF;
                    boolean tint = a >= 128;
                    boolean seen = !alphaTest || a > 0;
                    plain.setPixel(x, y, seen && !tint ? 0xFF000000 | rgb : 0);
                    tinted.setPixel(x, y, tint ? 0xFF000000 | rgb : 0);
                }
            String base = tex.getPath().replace(".png", "");
            String suffix = alphaTest ? "" : "_solid";
            Identifier pid = Identifier.fromNamespaceAndPath(tex.getNamespace(), base + "_kmask_plain" + suffix);
            Identifier tid = Identifier.fromNamespaceAndPath(tex.getNamespace(), base + "_kmask_tinted" + suffix);
            var tm = Minecraft.getInstance().getTextureManager();
            tm.register(pid, new DynamicTexture(pid::toString, plain));
            tm.register(tid, new DynamicTexture(tid::toString, tinted));
            return new Para(pid, tid);
        } catch (Exception bad) {
            KoperCore.LOGGER.warn("[Kodel/Bedrock] color mask split failed for {}: {}", tex, bad.getMessage());
            return NONE;
        }
    }

    // after a resource reload the packs may have other pixels
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) { CACHE.clear(); return; }
        mc.execute(() -> {
            for (Para p : CACHE.values()) {
                if (p == NONE) continue;
                mc.getTextureManager().release(p.plain());
                mc.getTextureManager().release(p.tinted());
            }
            CACHE.clear();
        });
    }
}
