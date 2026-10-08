package com.koper.koper_lib.api.companion;

import com.koper.koper_lib.state.KoperCompanionLedger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent companion ownership and commands backed by the world save. */
public final class KoperCompanions {
    private static final String COMPANION = "koperlib_companion";
    private static final String OWNER = "koperlib_owner_";
    private static final String GROUP = "koperlib_group_";
    private static final String MODE = "koperlib_mode_";
    private static final String COST = "koperlib_cost_";

    private KoperCompanions() {}

    public static void attach(Mob mob, UUID owner, String group, double reservedResource) {
        String cleanGroup = clean(group);
        double cleanCost = Math.max(0, reservedResource);
        mob.addTag(COMPANION);
        replace(mob, OWNER, OWNER + owner);
        replace(mob, GROUP, GROUP + cleanGroup);
        replace(mob, MODE, MODE + Mode.FOLLOW.id);
        replace(mob, COST, COST + Math.round(cleanCost * 1000));
        mob.setPersistenceRequired();
        MinecraftServer server = server(mob);
        if (server != null)
            KoperCompanionLedger.put(server, mob.getUUID(),
                new KoperCompanionLedger.Entry(owner, cleanGroup, Mode.FOLLOW.id, cleanCost));
    }

    public static boolean isCompanion(LivingEntity entity) {
        return owner(entity) != null;
    }

    public static boolean isOwnedBy(LivingEntity entity, UUID owner) {
        return owner != null && owner.equals(owner(entity));
    }

    public static boolean isFriendly(LivingEntity first, LivingEntity second) {
        UUID owner = owner(first);
        return owner != null && (owner.equals(second.getUUID()) || isOwnedBy(second, owner));
    }

    public static UUID owner(LivingEntity entity) {
        String raw = suffix(entity, OWNER, "");
        try {
            if (!raw.isEmpty()) {
                UUID owner = UUID.fromString(raw);
                migrateTagState(entity, owner);
                return owner;
            }
        } catch (IllegalArgumentException ignored) { }
        KoperCompanionLedger.Entry stored = stored(entity);
        if (stored == null) return null;
        restoreTags(entity, stored);
        return stored.owner();
    }

    public static String group(LivingEntity entity) {
        String tagged = suffix(entity, GROUP, "");
        if (!tagged.isEmpty()) return tagged;
        KoperCompanionLedger.Entry stored = stored(entity);
        return stored == null ? "default" : stored.group();
    }

    public static double reservedResource(LivingEntity entity) {
        String tagged = suffix(entity, COST, "");
        try { if (!tagged.isEmpty()) return Long.parseLong(tagged) / 1000d; }
        catch (NumberFormatException ignored) { }
        KoperCompanionLedger.Entry stored = stored(entity);
        return stored == null ? 0 : stored.reservedResource();
    }

    public static Mode mode(LivingEntity entity) {
        String tagged = suffix(entity, MODE, "");
        if (!tagged.isEmpty()) return Mode.parse(tagged);
        KoperCompanionLedger.Entry stored = stored(entity);
        return Mode.parse(stored == null ? Mode.FOLLOW.id : stored.mode());
    }

    public static void setMode(Mob mob, Mode mode) {
        replace(mob, MODE, MODE + mode.id);
        mob.setTarget(null);
        mob.getNavigation().stop();
        UUID owner = owner(mob);
        MinecraftServer server = server(mob);
        if (owner != null && server != null)
            KoperCompanionLedger.put(server, mob.getUUID(),
                new KoperCompanionLedger.Entry(owner, group(mob), mode.id, reservedResource(mob)));
    }

    /** Removes both the loaded-entity cache and its persistent ledger entry. */
    public static void detach(Mob mob) {
        mob.removeTag(COMPANION);
        remove(mob, OWNER);
        remove(mob, GROUP);
        remove(mob, MODE);
        remove(mob, COST);
        MinecraftServer server = server(mob);
        if (server != null) KoperCompanionLedger.remove(server, mob.getUUID());
    }

    public static List<Mob> owned(MinecraftServer server, UUID owner, String group) {
        List<Mob> result = new ArrayList<>();
        String wanted = clean(group);
        for (ServerLevel level : server.getAllLevels())
            for (Entity entity : level.getAllEntities())
                if (entity instanceof Mob mob && isOwnedBy(mob, owner) && group(mob).equals(wanted)) result.add(mob);
        return result;
    }

    public static ServerPlayer creditedPlayer(MinecraftServer server, Entity attacker) {
        if (attacker instanceof ServerPlayer player) return player;
        if (attacker instanceof LivingEntity living) {
            UUID owner = owner(living);
            if (owner != null) return server.getPlayerList().getPlayer(owner);
        }
        return null;
    }

    public static boolean equip(ServerPlayer owner, Mob mob, InteractionHand hand) {
        if (!isOwnedBy(mob, owner.getUUID())) return false;
        ItemStack held = owner.getItemInHand(hand);
        if (held.isEmpty()) return false;
        EquipmentSlot slot = mob.getEquipmentSlotForItem(held);
        if (!mob.canUseSlot(slot)) return false;
        ItemStack previous = mob.getItemBySlot(slot);
        mob.setItemSlot(slot, held.copyWithCount(1));
        mob.setGuaranteedDrop(slot);
        held.shrink(1);
        if (!previous.isEmpty() && !owner.getInventory().add(previous)) com.koper.koper_lib.core.KoperWyrzucacz.drop(owner, previous, false);
        return true;
    }

    private static void replace(Entity entity, String prefix, String value) {
        for (String tag : List.copyOf(entity.entityTags())) if (tag.startsWith(prefix)) entity.removeTag(tag);
        entity.addTag(value);
    }

    private static void remove(Entity entity, String prefix) {
        for (String tag : List.copyOf(entity.entityTags())) if (tag.startsWith(prefix)) entity.removeTag(tag);
    }

    private static KoperCompanionLedger.Entry stored(LivingEntity entity) {
        MinecraftServer server = server(entity);
        return server == null ? null : KoperCompanionLedger.get(server, entity.getUUID());
    }

    private static void migrateTagState(LivingEntity entity, UUID owner) {
        MinecraftServer server = server(entity);
        if (server == null || KoperCompanionLedger.get(server, entity.getUUID()) != null) return;
        KoperCompanionLedger.put(server, entity.getUUID(), new KoperCompanionLedger.Entry(
            owner, group(entity), mode(entity).id, reservedResource(entity)));
    }

    private static void restoreTags(LivingEntity entity, KoperCompanionLedger.Entry entry) {
        entity.addTag(COMPANION);
        replace(entity, OWNER, OWNER + entry.owner());
        replace(entity, GROUP, GROUP + clean(entry.group()));
        replace(entity, MODE, MODE + Mode.parse(entry.mode()).id);
        replace(entity, COST, COST + Math.max(0, Math.round(entry.reservedResource() * 1000)));
        if (entity instanceof Mob mob) mob.setPersistenceRequired();
    }

    private static MinecraftServer server(Entity entity) {
        return entity.level() instanceof ServerLevel level ? level.getServer() : null;
    }

    private static String suffix(Entity entity, String prefix, String fallback) {
        for (String tag : entity.entityTags()) if (tag.startsWith(prefix)) return tag.substring(prefix.length());
        return fallback;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "default";
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    public enum Mode {
        FOLLOW("follow"), STAY("stay"), ATTACK("attack");
        private final String id;
        Mode(String id) { this.id = id; }
        public String id() { return id; }
        public static Mode parse(String id) {
            for (Mode mode : values()) if (mode.id.equals(id)) return mode;
            return FOLLOW;
        }
    }
}
