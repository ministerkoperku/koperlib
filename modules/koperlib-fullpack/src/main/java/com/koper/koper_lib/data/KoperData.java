package com.koper.koper_lib.data;

import com.google.gson.JsonObject;

// shared shape for every JSON-defined content blob — so factories run one prepare() instead of
// copy-pasting the same defaults→preset→json boot dance. one place to evolve when the fork lands.
public interface KoperData {
    void applyDefaults();
    void applyPreset(String presetName);
    void applyJson(JsonObject json);
}
