package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashSet;
import java.util.Set;

// world.scoreboard. players are held by name, everything else by uuid, same as vanilla does it
final class BedrockTabela {

    private BedrockTabela() {}

    private static Objective need(Scoreboard b, String id) {
        Objective o = b.getObjective(id);
        if (o == null) throw new BedrockPytajnik.Oops("no objective " + id);
        return o;
    }

    private static DisplaySlot slot(String s) {
        return switch (s == null ? "" : s) {
            case "List" -> DisplaySlot.LIST;
            case "BelowName" -> DisplaySlot.BELOW_NAME;
            default -> DisplaySlot.SIDEBAR;
        };
    }

    // a holder name that is a uuid of a live entity comes back with that entity attached
    private static JsonObject who(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        Entity e = BedrockPytajnik.find(name);
        if (e == null) {
            MinecraftServer s = BedrockSkrypciarz.server();
            ServerPlayer p = s == null ? null : s.getPlayerList().getPlayerByName(name);
            if (p != null) e = p;
        }
        if (e != null) o.add("e", BedrockPytajnik.ref(e));
        return o;
    }

    static JsonElement handle(MinecraftServer s, JsonObject q) {
        Scoreboard b = s.getScoreboard();
        String a = q.get("a").getAsString();
        String id = BedrockPytajnik.s(q, "id");
        switch (a) {
            case "has": return new JsonPrimitive(b.getObjective(id) != null);
            case "dn": { Objective o = b.getObjective(id); return o == null ? null : new JsonPrimitive(o.getDisplayName().getString()); }
            case "new":
                b.addObjective(id, ObjectiveCriteria.DUMMY, Component.literal(BedrockPytajnik.s(q, "dn")),
                    ObjectiveCriteria.RenderType.INTEGER, true, null);
                return null;
            case "del": { Objective o = b.getObjective(id); if (o == null) return new JsonPrimitive(false); b.removeObjective(o); return new JsonPrimitive(true); }
            case "list": {
                JsonArray out = new JsonArray();
                for (Objective o : b.getObjectives()) {
                    JsonObject x = new JsonObject();
                    x.addProperty("id", o.getName());
                    x.addProperty("dn", o.getDisplayName().getString());
                    out.add(x);
                }
                return out;
            }
            case "get": {
                var info = b.getPlayerScoreInfo(ScoreHolder.forNameOnly(BedrockPytajnik.s(q, "who")), need(b, id));
                return info == null ? null : new JsonPrimitive(info.value());
            }
            case "set":
                b.getOrCreatePlayerScore(ScoreHolder.forNameOnly(BedrockPytajnik.s(q, "who")), need(b, id)).set((int) BedrockPytajnik.d(q, "v"));
                return null;
            case "add": {
                var acc = b.getOrCreatePlayerScore(ScoreHolder.forNameOnly(BedrockPytajnik.s(q, "who")), need(b, id));
                acc.add((int) BedrockPytajnik.d(q, "v"));
                return new JsonPrimitive(acc.get());
            }
            case "reset": {
                Objective o = need(b, id);
                ScoreHolder h = ScoreHolder.forNameOnly(BedrockPytajnik.s(q, "who"));
                boolean had = b.getPlayerScoreInfo(h, o) != null;
                b.resetSinglePlayerScore(h, o);
                return new JsonPrimitive(had);
            }
            case "who": {
                JsonArray out = new JsonArray();
                for (PlayerScoreEntry e : b.listPlayerScores(need(b, id))) out.add(who(e.owner()));
                return out;
            }
            case "all": {
                JsonArray out = new JsonArray();
                for (PlayerScoreEntry e : b.listPlayerScores(need(b, id))) {
                    JsonObject x = who(e.owner());
                    x.addProperty("v", e.value());
                    out.add(x);
                }
                return out;
            }
            case "everyone": {
                JsonArray out = new JsonArray();
                Set<String> seen = new HashSet<>();
                for (ScoreHolder h : b.getTrackedPlayers()) if (seen.add(h.getScoreboardName())) out.add(who(h.getScoreboardName()));
                return out;
            }
            case "show": {
                String target = BedrockPytajnik.s(q, "id");
                b.setDisplayObjective(slot(BedrockPytajnik.s(q, "slot")), target == null ? null : need(b, target));
                return null;
            }
            case "shown": {
                Objective o = b.getDisplayObjective(slot(BedrockPytajnik.s(q, "slot")));
                return o == null ? null : new JsonPrimitive(o.getName());
            }
            default: throw new BedrockPytajnik.Oops("scoreboard can't " + a);
        }
    }
}
