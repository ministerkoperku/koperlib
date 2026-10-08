package com.koper.koper_lib.api.attachment;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public final class KoperAttachmentOverlayClient {

    public record Link(Vec3 from, Vec3 to, int color, int direction) {}

    private static final Map<String, List<Link>> LINKS = new ConcurrentHashMap<>();

    private KoperAttachmentOverlayClient() {}

    public static void setLinks(String owner, List<Link> links) {
        if (links == null || links.isEmpty()) LINKS.remove(owner);
        else LINKS.put(owner, List.copyOf(links));
    }

    public static void clear(String owner) {
        LINKS.remove(owner);
    }

    static Iterable<List<Link>> all() {
        return LINKS.values();
    }
}
