package com.koper.koper_lib.api;

// typed access to json-defined content — items + sugar aliases for everything else
public interface FullPackJarsAPI extends ItemsAPI {

    default java.util.Optional<KoperItemRef> getItem(String fullId) {
        return get(fullId);
    }

    default KoperItemRef requireItem(String fullId) {
        return require(fullId);
    }

    default java.util.Optional<KoperBlockRef> getBlock(String fullId) {
        return KoperLibAPI.blocks().get(fullId);
    }

    default KoperBlockRef requireBlock(String fullId) {
        return KoperLibAPI.blocks().require(fullId);
    }

    default java.util.Optional<KoperEntityRef> getEntity(String fullId) {
        return KoperLibAPI.entities().get(fullId);
    }

    default KoperEntityRef requireEntity(String fullId) {
        return KoperLibAPI.entities().require(fullId);
    }

    default java.util.Optional<KoperDimensionRef> getDimension(String fullId) {
        return KoperLibAPI.dimensions().get(fullId);
    }

    default KoperDimensionRef requireDimension(String fullId) {
        return KoperLibAPI.dimensions().require(fullId);
    }
}
