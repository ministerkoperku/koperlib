package com.koper.koper_lib.api;

import java.util.List;
import java.util.Optional;

// typed access to all json-registered entities
public interface EntitiesAPI {
    Optional<KoperEntityRef> get(String fullId);
    KoperEntityRef require(String fullId);
    List<KoperEntityRef> getAllFromNamespace(String namespace);
    List<KoperEntityRef> getAll();
}
