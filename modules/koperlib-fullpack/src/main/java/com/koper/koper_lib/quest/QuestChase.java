package com.koper.koper_lib.quest;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.KoperActions;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.data.KoperQuestData;
import com.koper.koper_lib.scripting.KoperSnitch;
import com.koper.koper_lib.state.KoperSoulVault;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

// goal bookkeeping. listens to the snitch, decides whether what just happened finished anything.
// progress lives in the soul vault, so there's no second save file to keep honest
public final class QuestChase {
    private QuestChase() {}

    public static final String NS = "quest";

    public static final String NONE = "none";
    public static final String ACTIVE = "active";
    public static final String DONE = "done";

    // ── progress ────────────────────────────────────────────────────────────

    public static String status(ServerPlayer player, String questId) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return NONE;
        return KoperSoulVault.getStr(server, player.getUUID(), NS, questId, NONE);
    }

    public static boolean done(ServerPlayer player, String questId) {
        return DONE.equals(status(player, questId));
    }

    public static int progress(ServerPlayer player, String questId, String goalId) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return 0;
        return (int) KoperSoulVault.getNum(server, player.getUUID(), NS, goalKey(questId, goalId), 0);
    }

    private static String goalKey(String questId, String goalId) {
        return questId + "#" + goalId;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    public static boolean canStart(ServerPlayer player, KoperQuestData quest) {
        if (quest == null) return false;
        String now = status(player, quest.id);
        if (ACTIVE.equals(now)) return false;
        if (DONE.equals(now) && !quest.repeatable) return false;
        for (String needed : quest.requires)
            if (!done(player, needed)) return false;
        return true;
    }

    public static boolean start(ServerPlayer player, String questId) {
        KoperQuestData quest = QuestBook.get(questId);
        if (quest == null || !canStart(player, quest)) return false;

        MinecraftServer server = player.level().getServer();
        if (server == null) return false;

        KoperSoulVault.setRaw(server, player.getUUID(), NS, questId, "s" + ACTIVE);
        for (KoperQuestData.Goal goal : quest.goals)
            KoperSoulVault.remove(server, player.getUUID(), NS, goalKey(questId, goal.id));

        if (quest.onStart != null)
            KoperActions.run(quest.onStart, KoperContext.ofEquip(player, player.getMainHandItem()), "on_start", questId);

        player.sendSystemMessage(Component.literal("§6Quest §f" + label(quest)));
        for (KoperQuestData.Goal goal : quest.goals)
            player.sendSystemMessage(Component.literal("  §7" + goalText(goal) + " §80/" + goal.count));

        KoperSnitch.snitch(player, "quest:started", "id", questId);
        return true;
    }

    public static void complete(ServerPlayer player, String questId) {
        if (!ACTIVE.equals(status(player, questId))) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        KoperQuestData quest = QuestBook.get(questId);
        KoperSoulVault.setRaw(server, player.getUUID(), NS, questId, "s" + DONE);

        if (quest != null && quest.onComplete != null)
            KoperActions.run(quest.onComplete, KoperContext.ofEquip(player, player.getMainHandItem()), "on_complete", questId);

        player.sendSystemMessage(Component.literal("§aQuest complete§7: §f" + label(quest)));
        KoperSnitch.snitch(player, "quest:done", "id", questId);

        // finishing one thing often unlocks the next
        offerAutoStarts(player);
    }

    public static void reset(ServerPlayer player, String questId) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        KoperSoulVault.remove(server, player.getUUID(), NS, questId);
        KoperQuestData quest = QuestBook.get(questId);
        if (quest == null) return;
        for (KoperQuestData.Goal goal : quest.goals)
            KoperSoulVault.remove(server, player.getUUID(), NS, goalKey(questId, goal.id));
    }

    public static void offerAutoStarts(ServerPlayer player) {
        for (KoperQuestData quest : QuestBook.all())
            if (quest.autoStart && canStart(player, quest)) start(player, quest.id);
    }

    // ── goals ───────────────────────────────────────────────────────────────

    public static void bump(ServerPlayer player, String questId, String goalId, int by) {
        if (!ACTIVE.equals(status(player, questId))) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        KoperQuestData quest = QuestBook.get(questId);
        KoperQuestData.Goal goal = goalOf(quest, goalId);
        if (goal == null) return;

        int before = progress(player, questId, goalId);
        if (before >= goal.count) return;

        int now = (int) KoperSoulVault.add(server, player.getUUID(), NS, goalKey(questId, goalId), by);
        int shown = Math.min(now, goal.count);
        player.sendSystemMessage(Component.literal("§e" + goalText(goal) + " §7" + shown + "/" + goal.count), true);

        // this goal just filled, so anything hung off it fires before the quest is looked at
        if (before < goal.count && now >= goal.count) {
            if (goal.onDone != null)
                KoperActions.run(goal.onDone, KoperContext.ofEquip(player, player.getMainHandItem()),
                    "on_done", questId + "#" + goalId);
            KoperSnitch.snitch(player, "quest:goal", "id", questId, "goal", goalId);
        }

        if (allMet(player, quest)) complete(player, questId);
    }

    public static boolean allMet(ServerPlayer player, KoperQuestData quest) {
        if (quest == null) return false;
        for (KoperQuestData.Goal goal : quest.goals) {
            if (goal.optional) continue;
            if (progress(player, quest.id, goal.id) < goal.count) return false;
        }
        return true;
    }

    // fan one world event out to whatever active goal cares about it
    private static void feed(ServerPlayer player, String type, String reported, int amount) {
        if (reported == null) return;
        for (KoperQuestData quest : new ArrayList<>(QuestBook.all())) {
            if (!ACTIVE.equals(status(player, quest.id))) continue;
            for (KoperQuestData.Goal goal : quest.goals) {
                if (!goal.type.equals(type)) continue;
                if (!matches(goal.target, reported, "kill".equals(type))) continue;
                bump(player, quest.id, goal.id, amount);
            }
        }
    }

    // a target starting with # is a tag, so "get 4 of #minecraft:logs" takes any log. blank matches
    // anything, which is how you write "kill 10 of whatever"
    public static boolean matches(String target, String reported, boolean entity) {
        if (target == null || target.isEmpty()) return true;
        if (!target.startsWith("#")) return target.equals(reported);

        Identifier tagId = Identifier.tryParse(target.substring(1));
        Identifier thingId = Identifier.tryParse(reported);
        if (tagId == null || thingId == null) return false;

        if (entity) {
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(thingId);
            if (type == null) return false;
            for (var holder : BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(
                    net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, tagId)))
                if (holder.value() == type) return true;
            return false;
        }

        var item = BuiltInRegistries.ITEM.getValue(thingId);
        if (item == null) return false;
        for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId)))
            if (holder.value() == item) return true;
        return false;
    }

    // "have" is the one goal that can go down again, so it gets looked up instead of counted
    private static void sweepHaveGoals(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        for (KoperQuestData quest : new ArrayList<>(QuestBook.all())) {
            if (!ACTIVE.equals(status(player, quest.id))) continue;
            boolean moved = false;

            for (KoperQuestData.Goal goal : quest.goals) {
                if (!goal.type.equals("have")) continue;
                int held = countHeld(player, goal.target);
                int stored = progress(player, quest.id, goal.id);
                if (held == stored) continue;
                KoperSoulVault.setRaw(server, player.getUUID(), NS, goalKey(quest.id, goal.id), "n" + held);
                moved = true;
            }

            if (moved && allMet(player, quest)) complete(player, quest.id);
        }
    }

    private static int countHeld(ServerPlayer player, String target) {
        int total = 0;
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            var stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key != null && matches(target, key.toString(), false)) total += stack.getCount();
        }
        return total;
    }

    // a talk goal can name a scoreboard tag instead of a mob type, so one specific villager counts
    private static void feedTalkTags(KoperSnitch.Tattle t) {
        String tags = t.bit("tags");
        if (tags.isEmpty()) return;
        for (String tag : tags.split(","))
            if (!tag.isBlank()) feed(t.who(), "talk", "tag:" + tag.trim(), 1);
    }

    // script goals are never fed by the world. this tells scripts which ones are waiting so a
    // handler can look at whatever it cares about and call koper.quest.bump itself
    private static void pokeScriptGoals(ServerPlayer player) {
        for (KoperQuestData quest : new ArrayList<>(QuestBook.all())) {
            if (!ACTIVE.equals(status(player, quest.id))) continue;
            for (KoperQuestData.Goal goal : quest.goals) {
                if (!goal.type.equals("script")) continue;
                if (progress(player, quest.id, goal.id) >= goal.count) continue;
                KoperSnitch.snitch(player, "quest:check",
                    "id", quest.id, "goal", goal.id, "target", goal.target);
            }
        }
    }

    // ── quest givers ────────────────────────────────────────────────────────

    public static boolean isGiver(String giver, String entityId, String scoreboardTags) {
        if (giver == null || giver.isBlank()) return false;
        if (giver.startsWith("tag:")) {
            String want = giver.substring(4).trim();
            for (String tag : scoreboardTags.split(","))
                if (tag.trim().equals(want)) return true;
            return false;
        }
        return matches(giver, entityId, true);
    }

    private static void offerFromGiver(KoperSnitch.Tattle t) {
        ServerPlayer player = t.who();
        String entityId = t.id();
        String tags = t.bit("tags");

        for (KoperQuestData quest : new ArrayList<>(QuestBook.all())) {
            if (!isGiver(quest.giver, entityId, tags)) continue;

            String state = status(player, quest.id);
            if (ACTIVE.equals(state)) {
                player.sendSystemMessage(Component.literal("§e" + label(quest) + "§7 is already yours."), true);
                continue;
            }
            if (DONE.equals(state) && !quest.repeatable) continue;

            if (canStart(player, quest)) start(player, quest.id);
            else player.sendSystemMessage(Component.literal("§7They have nothing for you yet."), true);
        }
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    public static void register() {
        KoperSnitch.listen(KoperSnitch.KILL, t -> feed(t.who(), "kill", t.id(), 1));
        KoperSnitch.listen(KoperSnitch.CRAFT, t -> feed(t.who(), "craft", t.id(), t.count()));
        KoperSnitch.listen(KoperSnitch.GOT, t -> feed(t.who(), "get", t.id(), t.count()));
        KoperSnitch.listen(KoperSnitch.ADVANCE, t -> feed(t.who(), "advancement", t.id(), 1));
        KoperSnitch.listen(KoperSnitch.DIM, t -> feed(t.who(), "dimension", t.bit("to"), 1));

        KoperSnitch.listen(KoperSnitch.BREAK, t -> feed(t.who(), "break", t.id(), 1));
        KoperSnitch.listen(KoperSnitch.PLACE, t -> feed(t.who(), "place", t.id(), 1));
        KoperSnitch.listen(KoperSnitch.DIED, t -> feed(t.who(), "die", t.bit("by"), 1));

        KoperSnitch.listen(KoperSnitch.TALK, t -> {
            feed(t.who(), "talk", t.id(), 1);
            feedTalkTags(t);
            offerFromGiver(t);
        });

        // finishing a conversation is what a dialog goal waits for
        KoperSnitch.listen("dialog:done", t -> feed(t.who(), "dialog", t.id(), 1));

        KoperSnitch.listen(KoperSnitch.JOIN, t -> offerAutoStarts(t.who()));
        KoperSnitch.listen(KoperSnitch.HEART, t -> {
            sweepHaveGoals(t.who());
            pokeScriptGoals(t.who());
        });

        KoperLib.LOGGER.info("[KoperLib] quests chasing goals");
    }

    // ── text ────────────────────────────────────────────────────────────────

    public static String label(KoperQuestData quest) {
        if (quest == null) return "?";
        return quest.name.isBlank() ? quest.id : quest.name;
    }

    public static String goalText(KoperQuestData.Goal goal) {
        if (goal.text != null && !goal.text.isBlank()) return goal.text;
        String what = shortName(goal.target);
        return switch (goal.type) {
            case "kill" -> "Kill " + what;
            case "craft" -> "Craft " + what;
            case "get" -> "Obtain " + what;
            case "have" -> "Hold " + what;
            case "advancement" -> "Earn " + what;
            case "dimension" -> "Reach " + what;
            case "break" -> "Break " + what;
            case "place" -> "Place " + what;
            case "talk" -> "Talk to " + what;
            case "die" -> "Die to " + what;
            case "dialog" -> "Speak with " + what;
            case "script" -> what;
            default -> goal.type + " " + what;
        };
    }

    private static String shortName(String id) {
        if (id == null || id.isBlank()) return "anything";
        String tail = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return tail.replace('_', ' ');
    }

    public static List<String> journal(ServerPlayer player) {
        List<String> rows = new ArrayList<>();
        for (KoperQuestData quest : QuestBook.all()) {
            String state = status(player, quest.id);
            if (NONE.equals(state) && quest.hidden) continue;

            StringBuilder row = new StringBuilder("§f" + label(quest) + " §8[" + state + "]");
            if (ACTIVE.equals(state)) {
                for (KoperQuestData.Goal goal : quest.goals) {
                    int at = Math.min(progress(player, quest.id, goal.id), goal.count);
                    row.append("\n  §7").append(goalText(goal)).append(" §8").append(at).append('/').append(goal.count);
                }
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private static KoperQuestData.Goal goalOf(KoperQuestData quest, String goalId) {
        if (quest == null) return null;
        for (KoperQuestData.Goal goal : quest.goals)
            if (goal.id.equals(goalId)) return goal;
        return null;
    }
}
