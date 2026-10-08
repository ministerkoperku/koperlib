package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.kui.KuiBook;
import com.koper.koper_lib.kui.KuiPage;
import net.minecraft.resources.Identifier;

// reads the small registration json (mode/paths/size) into a KuiPage and parks it in KuiBook.
// the layout/texture/script files it points at are resolved later, when the screen actually opens.
public final class GuiFactory {
    private GuiFactory() {}

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) {
            KoperLib.LOGGER.warn("[Kui] gui json without id — skipping");
            return;
        }
        String id = json.get("id").getAsString();
        Identifier rid = Identifier.tryParse(id);
        if (rid == null) {
            KoperLib.LOGGER.warn("[Kui] bad gui id: {}", id);
            return;
        }

        KuiPage page = new KuiPage();
        page.id = id;
        page.namespace = rid.getNamespace();
        page.mode = json.has("mode") ? json.get("mode").getAsString().toLowerCase() : "json";

        page.title = json.has("title") ? json.get("title").getAsString()
                   : json.has("name")  ? json.get("name").getAsString()
                   : FactoryUtils.capitalizeWords(rid.getPath());

        // size: [w,h] array, or width/height fields, else vanilla container default
        if (json.has("size") && json.get("size").isJsonArray()) {
            JsonArray s = json.getAsJsonArray("size");
            if (s.size() >= 2) { page.w = s.get(0).getAsInt(); page.h = s.get(1).getAsInt(); }
        } else {
            if (json.has("width"))  page.w = json.get("width").getAsInt();
            if (json.has("height")) page.h = json.get("height").getAsInt();
        }

        if (json.has("layout"))       page.layout      = json.get("layout").getAsString();
        if (json.has("layout_file"))  page.layoutFile  = json.get("layout_file").getAsString();
        if (json.has("texture"))      page.texture     = json.get("texture").getAsString();
        if (json.has("texture_file")) page.textureFile = json.get("texture_file").getAsString();
        if (json.has("regions_file")) page.regionsFile = json.get("regions_file").getAsString();
        if (json.has("script"))       page.script      = json.get("script").getAsString();
        if (json.has("hud_x"))        page.hudX        = json.get("hud_x").getAsInt();
        if (json.has("hud_y"))        page.hudY        = json.get("hud_y").getAsInt();

        // shapeless recipes (optional) — { id, ingredients:[...], result, count }
        if (json.has("recipes") && json.get("recipes").isJsonArray()) {
            for (var el : json.getAsJsonArray("recipes")) {
                if (!el.isJsonObject()) continue;
                JsonObject r = el.getAsJsonObject();
                if (!r.has("ingredients") || !r.has("result")) continue;
                java.util.List<String> ings = new java.util.ArrayList<>();
                for (var ing : r.getAsJsonArray("ingredients")) ings.add(ing.getAsString());
                page.recipes.add(new com.koper.koper_lib.kui.KuiRecipe(
                    r.has("id") ? r.get("id").getAsString() : "recipe_" + page.recipes.size(),
                    ings, r.get("result").getAsString(),
                    r.has("count") ? r.get("count").getAsInt() : 1));
            }
        }

        // which buttons try a craft on click — scan the layout for craft=true
        if (!page.recipes.isEmpty() && json.has("layout_file")) {
            try {
                String layoutText = java.nio.file.Files.readString(java.nio.file.Path.of(json.get("layout_file").getAsString()));
                for (var e : com.koper.koper_lib.kui.KuiLayout.parse(layoutText))
                    if (e.craft && !e.id.isEmpty()) page.craftButtons.add(e.id);
            } catch (Exception ex) {
                KoperLib.LOGGER.warn("[Kui] couldn't scan layout for craft buttons: {}", ex.getMessage());
            }
        }

        KuiBook.put(page);
        KoperLib.LOGGER.info("[Kui] registered gui {} ({}, {}x{}{})", id, page.mode, page.w, page.h,
            page.recipes.isEmpty() ? "" : ", " + page.recipes.size() + " recipes");
    }
}
