package com.koper.koper_lib.api;

// frozen pack info — safe to pass around, won't change under you mid-reload
public record KoperPackSnapshot(
    String folderName,
    String namespace,
    String displayName,
    String version,
    String author,
    String description,
    boolean enabled
) {}
