package com.koper.koper_lib.kfx.render;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Bounded revision-keyed cache; callers own how a section revision is obtained. */
public final class KfxCollisionCache {
    public static final int HARD_MAX_FIELDS = 128;
    private final Map<Key, CacheEntry> fields = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, CacheEntry> eldest) {
            return size() > HARD_MAX_FIELDS;
        }
    };

    public synchronized KfxCollisionField field(Key key, long revision, Supplier<KfxCollisionField> builder) {
        CacheEntry existing = fields.get(key);
        if (existing != null && existing.revision == revision) return existing.field;
        KfxCollisionField built = java.util.Objects.requireNonNull(builder.get(), "collision field builder");
        fields.put(key, new CacheEntry(revision, built));
        return built;
    }

    public synchronized void invalidate(Object levelIdentity) {
        fields.keySet().removeIf(key -> key.levelIdentity().equals(levelIdentity));
    }
    public synchronized void clear() { fields.clear(); }
    public synchronized int size() { return fields.size(); }

    public record Key(Object levelIdentity, int sectionX, int sectionY, int sectionZ, int radius) {
        public Key {
            java.util.Objects.requireNonNull(levelIdentity, "levelIdentity");
            if (radius < 1 || radius > 16) throw new IllegalArgumentException("KFX collision radius must be 1..16");
        }
    }
    private record CacheEntry(long revision, KfxCollisionField field) {}
}
