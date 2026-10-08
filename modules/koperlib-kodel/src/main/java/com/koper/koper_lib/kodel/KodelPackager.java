package com.koper.koper_lib.kodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// builds the .kodel zip. manifest.json first, then model/animation/texture,
// same order the python packer writes
public final class KodelPackager {
    public record Parts(byte[] model, byte[] animations, byte[] texture, int texWidth, int texHeight,
                        String name) {
        public boolean hasAnimations() {
            return animations != null && animations.length > 0;
        }
    }

    private KodelPackager() {}

    // model.bin does NOT carry the atlas size, only manifest.json does. pack from
    // the live model so the frame can't get lost between converting and zipping
    public static byte[] pack(KodelModel model, List<KodelAnimation> anims, byte[] texture, String name) {
        byte[] animBytes = anims == null || anims.isEmpty() ? null : KodelAnimation.write(anims);
        return pack(new Parts(model.write(), animBytes, texture,
            model.texWidth, model.texHeight, name));
    }

    public static byte[] pack(Parts parts) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", "kodel");
        JsonArray version = new JsonArray();
        version.add(KodelFormat.MAJOR);
        version.add(KodelFormat.MINOR);
        manifest.add("version", version);
        manifest.addProperty("name", parts.name());
        manifest.addProperty("units", "pixels");
        JsonObject frame = new JsonObject();
        frame.addProperty("width", parts.texWidth());
        frame.addProperty("height", parts.texHeight());
        manifest.add("frame", frame);
        JsonArray textures = new JsonArray();
        if (parts.texture() != null) textures.add("texture.png");
        manifest.add("textures", textures);
        manifest.addProperty("model", "model.bin");
        manifest.addProperty("generator", "koperlib-kodel " + KodelFormat.MAJOR + "." + KodelFormat.MINOR);
        if (parts.hasAnimations()) manifest.addProperty("animation", "animation.anim.bin");

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                put(zip, "manifest.json", manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                put(zip, "model.bin", parts.model());
                if (parts.hasAnimations()) put(zip, "animation.anim.bin", parts.animations());
                if (parts.texture() != null) put(zip, "texture.png", parts.texture());
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("kodel pack failed", e);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}