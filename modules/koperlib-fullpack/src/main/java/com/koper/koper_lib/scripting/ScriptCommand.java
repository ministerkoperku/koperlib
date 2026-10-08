package com.koper.koper_lib.scripting;

// typed commands parsed from the Lua koper._cmds queue
public sealed interface ScriptCommand permits
        ScriptCommand.Teleport,
        ScriptCommand.PlaySound,
        ScriptCommand.Particle,
        ScriptCommand.ParticleBurst,
        ScriptCommand.ParticleLine,
        ScriptCommand.Effect,
        ScriptCommand.EffectRemove,
        ScriptCommand.SetBlock,
        ScriptCommand.FillBlocks,
        ScriptCommand.DropItem,
        ScriptCommand.Give,
        ScriptCommand.Msg,
        ScriptCommand.Summon,
        ScriptCommand.RunCmd,
        ScriptCommand.Explosion,
        ScriptCommand.Weather,
        ScriptCommand.SpawnXp,
        ScriptCommand.Title,
        ScriptCommand.TitleAll,
        ScriptCommand.SetHealth,
        ScriptCommand.KillEntity,
        ScriptCommand.Ignite,
        ScriptCommand.Freeze,
        ScriptCommand.Kick,
        ScriptCommand.SetGamemode,
        ScriptCommand.SetName,
        ScriptCommand.LaunchEntity,
        ScriptCommand.SpawnProjectile,
        ScriptCommand.NetBroadcast,
        ScriptCommand.TagAdd,
        ScriptCommand.TagRemove,
        ScriptCommand.SetTarget,
        ScriptCommand.MoveTo,
        ScriptCommand.Attack,
        ScriptCommand.PlayAnim,
        ScriptCommand.SetDurability,
        ScriptCommand.KontraForce,
        ScriptCommand.KontraImpulse,
        ScriptCommand.KontraSelfRight,
        ScriptCommand.KontraDestroy,
        ScriptCommand.KontraRestore,
        ScriptCommand.KfxSpawn,
        ScriptCommand.KfxUpdate,
        ScriptCommand.KfxStop,
        ScriptCommand.KfxAttach,
        ScriptCommand.KfxProgramJson,
        ScriptCommand.KfxGraphDeclare,
        ScriptCommand.KfxGraphPlay,
        ScriptCommand.KfxHandleSet,
        ScriptCommand.KfxHandleAnchor,
        ScriptCommand.KfxHandleDetach,
        ScriptCommand.KfxHandleSignal,
        ScriptCommand.GuiConsume,
        ScriptCommand.GuiClearSlot,
        ScriptCommand.GuiSetSlot,
        ScriptCommand.GuiClearAll,
        ScriptCommand.GuiWidgetSet,
        ScriptCommand.GuiOpen,
        ScriptCommand.GuiClose,
        ScriptCommand.GuiHud {

    record Teleport(String uuid, double x, double y, double z) implements ScriptCommand {}
    record PlaySound(double x, double y, double z, String id, float vol, float pitch) implements ScriptCommand {}
    record Particle(String id, double x, double y, double z, int count) implements ScriptCommand {}
    record ParticleBurst(String id, double x, double y, double z, int count, double spread) implements ScriptCommand {}
    record ParticleLine(String id, double x1, double y1, double z1, double x2, double y2, double z2, int steps) implements ScriptCommand {}
    record Effect(String uuid, String effectId, int ticks, int amplifier) implements ScriptCommand {}
    record EffectRemove(String uuid, String effectId) implements ScriptCommand {}
    record SetBlock(int x, int y, int z, String blockId) implements ScriptCommand {}
    record FillBlocks(int x1, int y1, int z1, int x2, int y2, int z2, String blockId) implements ScriptCommand {}
    record DropItem(double x, double y, double z, String itemId, int count) implements ScriptCommand {}
    record Give(String uuid, String itemId, int count) implements ScriptCommand {}
    record Msg(String uuid, String text) implements ScriptCommand {}
    record Summon(String entityId, double x, double y, double z) implements ScriptCommand {}
    record RunCmd(String command) implements ScriptCommand {}
    record Explosion(double x, double y, double z, float power, boolean fire) implements ScriptCommand {}
    record Weather(String type) implements ScriptCommand {}
    record SpawnXp(double x, double y, double z, int amount) implements ScriptCommand {}
    record Title(String uuid, String title, String subtitle) implements ScriptCommand {}
    record TitleAll(String title, String subtitle) implements ScriptCommand {}
    record SetHealth(String uuid, float health) implements ScriptCommand {}
    record KillEntity(String uuid) implements ScriptCommand {}
    record Ignite(String uuid, int ticks) implements ScriptCommand {}
    record Freeze(String uuid, int ticks) implements ScriptCommand {}
    record Kick(String uuid, String reason) implements ScriptCommand {}
    record SetGamemode(String uuid, String mode) implements ScriptCommand {}
    record SetName(String uuid, String name) implements ScriptCommand {}
    record LaunchEntity(String uuid, double vx, double vy, double vz) implements ScriptCommand {}
    record SpawnProjectile(String entityType, double x, double y, double z, double vx, double vy, double vz) implements ScriptCommand {}
    record NetBroadcast(String message) implements ScriptCommand {}
    record TagAdd(String uuid, String tag) implements ScriptCommand {}
    record TagRemove(String uuid, String tag) implements ScriptCommand {}
    record SetTarget(String uuid, String targetUuid) implements ScriptCommand {}
    record MoveTo(String uuid, double x, double y, double z, double speed) implements ScriptCommand {}
    record Attack(String uuid, String targetUuid) implements ScriptCommand {}
    record PlayAnim(String uuid, String clip) implements ScriptCommand {}
    record SetDurability(String uuid, String hand, int value) implements ScriptCommand {}
    record KontraForce(long id, float fx, float fy, float fz) implements ScriptCommand {}
    record KontraImpulse(long id, float ix, float iy, float iz) implements ScriptCommand {}
    record KontraSelfRight(long id) implements ScriptCommand {}
    record KontraDestroy(long id) implements ScriptCommand {}
    record KontraRestore(long id) implements ScriptCommand {}
    record KfxSpawn(String id, double sx, double sy, double sz, double ex, double ey, double ez) implements ScriptCommand {}
    record KfxUpdate(long id, double sx, double sy, double sz, double ex, double ey, double ez) implements ScriptCommand {}
    record KfxStop(long id) implements ScriptCommand {}
    record KfxAttach(long id, String uuid, double ox, double oy, double oz, double ex, double ey, double ez, boolean endRelative) implements ScriptCommand {}
    record KfxProgramJson(String json, double sx, double sy, double sz, double ex, double ey, double ez) implements ScriptCommand {}
    record KfxGraphDeclare(String graphJson) implements ScriptCommand {}
    record KfxGraphPlay(long id, String graphJson, String optionsJson) implements ScriptCommand {}
    record KfxHandleSet(long id, String name, String valueJson) implements ScriptCommand {}
    record KfxHandleAnchor(long id, String startJson, String endJson) implements ScriptCommand {}
    record KfxHandleDetach(long id) implements ScriptCommand {}
    record KfxHandleSignal(long id, String name, String dataJson) implements ScriptCommand {}
    // kui container mutation — slot is 1-indexed (matches e.slots), targets the player's open KuiMenu
    record GuiConsume(String uuid, int slot, int count) implements ScriptCommand {}
    record GuiClearSlot(String uuid, int slot) implements ScriptCommand {}
    record GuiSetSlot(String uuid, int slot, String itemId, int count) implements ScriptCommand {}
    record GuiClearAll(String uuid) implements ScriptCommand {}
    record GuiWidgetSet(String uuid, String widget, String value) implements ScriptCommand {}
    record GuiOpen(String uuid, String guiId) implements ScriptCommand {}
    record GuiClose(String uuid) implements ScriptCommand {}
    record GuiHud(String uuid, String guiId, boolean show) implements ScriptCommand {}
}
