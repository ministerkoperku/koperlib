package com.koper.koper_lib.data;

import com.google.gson.JsonObject;

// books/<name>.json. one mod, one book, so a modpack with five content mods has five of them
// instead of one soup
public class KoperBookData {
    public String id;
    public String title = "";
    public String description = "";
    public String icon = "minecraft:written_book";
    public int order = 100;

    public void applyJson(JsonObject json) {
        if (json.has("id")) id = json.get("id").getAsString();
        if (json.has("title")) title = json.get("title").getAsString();
        if (json.has("name")) title = json.get("name").getAsString();
        if (json.has("description")) description = json.get("description").getAsString();
        if (json.has("icon")) icon = json.get("icon").getAsString();
        if (json.has("order")) order = json.get("order").getAsInt();
        if (title.isBlank() && id != null) title = id;
    }

    public String namespace() {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }
}
