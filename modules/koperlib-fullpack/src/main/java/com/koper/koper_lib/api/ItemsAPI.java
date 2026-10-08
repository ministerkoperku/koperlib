package com.koper.koper_lib.api;

import java.util.List;
import java.util.Optional;

// typed access to all json-registered items — no json parsing needed on your end
public interface ItemsAPI {

    // get by full id like "koper_proof:echo_stick" — empty if not found or pack disabled
    Optional<KoperItemRef> get(String fullId);

    // get by full id, throws if missing — for when you know it MUST be there
    KoperItemRef require(String fullId);

    // all items from a specific namespace / pack
    List<KoperItemRef> getAllFromNamespace(String namespace);

    // all registered items across all enabled packs
    List<KoperItemRef> getAll();
}
