package com.koper.koper_lib.api;

import java.util.List;
import java.util.Optional;

public interface DimensionsAPI {
    Optional<KoperDimensionRef> get(String fullId);
    KoperDimensionRef require(String fullId);
    List<KoperDimensionRef> getAllFromNamespace(String namespace);
    List<KoperDimensionRef> getAll();
}
