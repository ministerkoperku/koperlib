package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.RuntimeAPI;
import com.koper.koper_lib.core.KoperRuntime;
import com.koper.koper_lib.panama.RustBridge;

import java.util.List;

public class KoperRuntimeFace implements RuntimeAPI {

    @Override
    public boolean engineLoaded() {
        return RustBridge.LOADED;
    }

    @Override
    public String engineVersion() {
        return RustBridge.version();
    }

    @Override
    public boolean featureOn(String featureId) {
        for (KoperRuntime.Feature f : KoperRuntime.Feature.values()) {
            if (f.id.equals(featureId)) return KoperRuntime.on(f);
        }
        return false;
    }

    @Override
    public List<String> snapshot() {
        return KoperRuntime.snapshot();
    }
}
