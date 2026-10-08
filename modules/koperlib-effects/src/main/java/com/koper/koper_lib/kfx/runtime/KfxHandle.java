package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxResolvedValue;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class KfxHandle {
    private final long id;
    private final Commands commands;

    KfxHandle(long id, Commands commands) {
        if (id == 0) throw new IllegalArgumentException("KFX handle id cannot be zero");
        this.id = id;
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public static KfxHandle server(ServerLevel level, long id) {
        Objects.requireNonNull(level, "level");
        return new KfxHandle(id, new Commands() {
            @Override public void stop(long handle) {
                com.koper.koper_lib.kfx.KfxApi.stop(level, handle);
            }

            @Override public void reanchor(long handle, KfxAnchor start, KfxAnchor end) {
                com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
                    new com.koper.koper_lib.network.KfxAnchorPayload(handle, start, end));
            }

            @Override public void detach(long handle) {
                com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
                    new com.koper.koper_lib.network.KfxDetachPayload(handle));
            }

            @Override public void set(long handle, String name, KfxResolvedValue value) {
                throw new IllegalStateException("this KFX handle has no graph parameter snapshot");
            }

            @Override public void setAll(long handle, Map<String, KfxResolvedValue> values) {
                throw new IllegalStateException("this KFX handle has no graph parameter snapshot");
            }
        });
    }

    public static KfxHandle server(ServerLevel level, long id, KfxPlayRequest initial) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(initial, "initial");
        return new KfxHandle(id, new Commands() {
            private KfxPlayRequest request = initial;

            @Override public void stop(long handle) {
                com.koper.koper_lib.kfx.KfxApi.stop(level, handle);
            }

            @Override public void reanchor(long handle, KfxAnchor start, KfxAnchor end) {
                com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
                    new com.koper.koper_lib.network.KfxAnchorPayload(handle, start, end));
                request = new KfxPlayRequest(request.graph(), request.parameters(), start, end, request.seed());
            }

            @Override public void detach(long handle) {
                com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
                    new com.koper.koper_lib.network.KfxDetachPayload(handle));
            }

            @Override public void set(long handle, String name, KfxResolvedValue value) {
                setAll(handle, Map.of(name, value));
            }

            @Override public void setAll(long handle, Map<String, KfxResolvedValue> values) {
                var parameters = new LinkedHashMap<>(request.parameters());
                parameters.putAll(values);
                KfxPlayRequest replacement = new KfxPlayRequest(
                    request.graph(), parameters, request.start(), request.end(), request.seed());
                com.koper.koper_lib.kfx.KfxApi.play(level, handle, replacement);
                request = replacement;
            }
        });
    }

    public long id() { return id; }

    public KfxHandle reanchor(KfxAnchor start, KfxAnchor end) {
        commands.reanchor(id, Objects.requireNonNull(start, "start"), Objects.requireNonNull(end, "end"));
        return this;
    }

    public KfxHandle detach() {
        commands.detach(id);
        return this;
    }

    public KfxHandle set(String name, KfxResolvedValue value) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("KFX input name cannot be blank");
        commands.set(id, name, Objects.requireNonNull(value, "value"));
        return this;
    }

    public KfxHandle setAll(Map<String, KfxResolvedValue> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) return this;
        var checked = new LinkedHashMap<String, KfxResolvedValue>();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("KFX input name cannot be blank");
            }
            checked.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "value"));
        }
        commands.setAll(id, Map.copyOf(checked));
        return this;
    }

    public void stop() {
        commands.stop(id);
    }

    interface Commands {
        void stop(long id);
        void reanchor(long id, KfxAnchor start, KfxAnchor end);
        void detach(long id);
        void set(long id, String name, KfxResolvedValue value);
        void setAll(long id, Map<String, KfxResolvedValue> values);
    }
}
