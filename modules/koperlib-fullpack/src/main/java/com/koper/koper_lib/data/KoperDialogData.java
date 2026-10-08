package com.koper.koper_lib.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// dialogs/<name>.json. a graph, not a tree, but everyone calls them trees so whatever.
// "speaker" decides who says it when you right click a mob: entity id, #tag, or tag:<scoreboard tag>
public class KoperDialogData {
    public String id;
    public String title = "";
    public String speaker = "";     // who this belongs to in the world
    public String start = "intro";
    public Map<String, Node> nodes = new LinkedHashMap<>();

    public Node node(String key) {
        return key == null ? null : nodes.get(key);
    }

    public static class Node {
        public String id = "";
        public String text = "";
        public String name = "";        // overrides the mob's name in the header
        public List<Choice> choices = new ArrayList<>();
        public JsonElement onShow;
        public boolean ends = false;    // no choices and no next: this is where the talk stops
        public String next = "";
    }

    public static class Choice {
        public String text = "";
        public String go = "";          // node to jump to, blank closes
        public JsonElement actions;
        public boolean once = false;
        public JsonObject when;         // gate: quest state, held items, dialogue already had
        public boolean hide = true;     // failed gate hides it; false greys it out instead
    }

    public void applyJson(JsonObject json) {
        if (json.has("id")) id = json.get("id").getAsString();
        if (json.has("title")) title = json.get("title").getAsString();
        if (json.has("speaker")) speaker = json.get("speaker").getAsString().trim();
        if (json.has("start")) start = json.get("start").getAsString();

        if (!json.has("nodes")) return;
        JsonObject raw = json.getAsJsonObject("nodes");
        for (var entry : raw.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject n = entry.getValue().getAsJsonObject();

            Node node = new Node();
            node.id = entry.getKey();
            node.text = n.has("text") ? n.get("text").getAsString() : "";
            node.name = n.has("name") ? n.get("name").getAsString() : "";
            node.next = n.has("next") ? n.get("next").getAsString() : "";
            node.ends = n.has("ends") && n.get("ends").getAsBoolean();
            if (n.has("on_show")) node.onShow = n.get("on_show");

            if (n.has("choices")) {
                for (JsonElement el : n.getAsJsonArray("choices")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject c = el.getAsJsonObject();
                    Choice choice = new Choice();
                    choice.text = c.has("text") ? c.get("text").getAsString() : "...";
                    choice.go = c.has("go") ? c.get("go").getAsString()
                        : c.has("goto") ? c.get("goto").getAsString() : "";
                    choice.once = c.has("once") && c.get("once").getAsBoolean();
                    if (c.has("when") && c.get("when").isJsonObject()) choice.when = c.getAsJsonObject("when");
                    if (c.has("if") && c.get("if").isJsonObject()) choice.when = c.getAsJsonObject("if");
                    choice.hide = !c.has("hide") || c.get("hide").getAsBoolean();
                    if (c.has("actions")) choice.actions = c.get("actions");
                    node.choices.add(choice);
                }
            }
            nodes.put(node.id, node);
        }
    }
}
