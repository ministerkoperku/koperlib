package com.koper.koper_lib.api;

import java.util.List;
import java.util.Optional;

public interface BlocksAPI {
    Optional<KoperBlockRef> get(String fullId);
    KoperBlockRef require(String fullId);
    List<KoperBlockRef> getAllFromNamespace(String namespace);
    List<KoperBlockRef> getAll();
}
