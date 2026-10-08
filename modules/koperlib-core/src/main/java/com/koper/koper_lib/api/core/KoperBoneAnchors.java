package com.koper.koper_lib.api.core;

import java.util.Optional;

public final class KoperBoneAnchors {
    private static final Provider NOTHING = new Provider() {
        @Override public Optional<TransformData> resolve(int entityId, String bone, float partialTick) {
            return Optional.empty();
        }
        @Override public void clear() {}
    };
    private static volatile Provider provider = NOTHING;

    private KoperBoneAnchors() {}

    public static void install(Provider next) {
        provider = next == null ? NOTHING : next;
    }

    public static Optional<TransformData> resolve(int entityId, String bone, float partialTick) {
        return provider.resolve(entityId, bone, partialTick);
    }

    public static void beginFrame() {
        provider.beginFrame();
    }

    public static void endFrame() {
        provider.endFrame();
    }

    public static void clear() {
        provider.clear();
    }

    public interface Provider {
        Optional<TransformData> resolve(int entityId, String bone, float partialTick);
        default void beginFrame() {}
        default void endFrame() {}
        void clear();
    }

    public record TransformData(
        float x, float y, float z,
        float forwardX, float forwardY, float forwardZ,
        float normalX, float normalY, float normalZ
    ) {}
}
