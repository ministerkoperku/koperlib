package com.koper.koper_lib.kodel;

import com.google.gson.JsonObject;
import com.koper.koper_lib.api.core.KodelBedrockBridge;

import java.util.List;

// bedrock addons dropped into fullpacks get their geometry turned into kodel on the way in
public final class KodelBedrockKonwerter implements KodelBedrockBridge.Converter {
    @Override
    public byte[] convert(JsonObject geoFile, JsonObject animFile, byte[] png, String name) {
        var geos = geoFile.getAsJsonArray("minecraft:geometry");
        if (geos == null || geos.isEmpty()) return null;
        KodelModel model = KodelConverters.geometry(geos.get(0).getAsJsonObject());
        if (model.bones.isEmpty()) return null;
        List<KodelAnimation> clips = animFile != null && animFile.has("animations")
            ? KodelConverters.animations(animFile) : List.of();
        return KodelPackager.pack(model, clips, png, name);
    }
}
