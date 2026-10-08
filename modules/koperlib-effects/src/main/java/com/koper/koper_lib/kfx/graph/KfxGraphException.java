package com.koper.koper_lib.kfx.graph;

public final class KfxGraphException extends IllegalArgumentException {
    private final String source;
    private final String path;
    private final String detail;

    public KfxGraphException(String path, String message) {
        this(null, path, message);
    }

    public KfxGraphException(String source, String path, String message) {
        super((source == null || source.isBlank() ? "" : source + " ") + path + ": " + message);
        this.source = source;
        this.path = path;
        this.detail = message;
    }

    public String path() {
        return path;
    }

    public String source() {
        return source;
    }

    KfxGraphException atSource(String newSource) {
        return source == null ? new KfxGraphException(newSource, path, detail) : this;
    }
}
