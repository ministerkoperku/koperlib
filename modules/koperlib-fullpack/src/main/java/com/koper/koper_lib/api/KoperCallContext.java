package com.koper.koper_lib.api;

import com.google.gson.JsonObject;
import com.koper.koper_lib.data.KoperStackData;
import com.koper.koper_lib.scripting.JavaHookRegistry;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public record KoperCallContext(
    KoperContext ctx,
    JsonObject json,
    String event,
    String ownerId
) {
    public ServerPlayer player() { return ctx != null ? ctx.player() : null; }
    public LivingEntity entity() { return ctx != null ? ctx.entity() : null; }
    public LivingEntity target() { return ctx != null ? ctx.target() : null; }
    public ItemStack stack() { return ctx != null ? ctx.stack() : ItemStack.EMPTY; }

    public String string(String key, String fallback) {
        return json != null && json.has(key) ? json.get(key).getAsString() : fallback;
    }

    public int integer(String key, int fallback) {
        return json != null && json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    public float number(String key, float fallback) {
        return json != null && json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    public boolean bool(String key, boolean fallback) {
        return json != null && json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }

    public InteractionResult callJava(String hookId) {
        if (hookId == null || hookId.isBlank() || ctx == null) return InteractionResult.PASS;
        return JavaHookRegistry.fireHook(hookId, ctx);
    }

    public void callLua(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) return;
        UniversalScriptEngine.call(scriptId, scriptEvent(), player(), ctx != null ? ctx.world() : null, ctx != null ? ctx.pos() : null);
    }

    public void kfxCast(String effectId, float range) {
        ServerPlayer p = player();
        if (p == null || effectId == null || effectId.isBlank()) return;
        var eye = p.getEyePosition();
        var look = p.getLookAngle();
        var start = eye.add(look.scale(0.8));
        var end = eye.add(look.scale(range));
        FullpackAddons.spawnEffect((ServerLevel)p.level(), effectId,
            start.x, start.y, start.z, end.x, end.y, end.z);
    }

    public void kfxAt(String effectId) {
        if (effectId == null || effectId.isBlank() || ctx == null || !(ctx.world() instanceof ServerLevel level)) return;
        double x = ctx.pos() != null ? ctx.pos().getX() + 0.5 : (entity() != null ? entity().getX() : 0.0);
        double y = ctx.pos() != null ? ctx.pos().getY() + 0.5 : (entity() != null ? entity().getY() + entity().getBbHeight() * 0.5 : 0.0);
        double z = ctx.pos() != null ? ctx.pos().getZ() + 0.5 : (entity() != null ? entity().getZ() : 0.0);
        FullpackAddons.spawnEffect(level, effectId, x, y, z, x, y + 0.01, z);
    }

    public void cooldown(int ticks) {
        if (player() != null && !stack().isEmpty() && ticks > 0) player().getCooldowns().addCooldown(stack(), ticks);
    }

    public void setStackString(String key, String value) {
        if (!stack().isEmpty() && key != null && !key.isBlank()) KoperStackData.setString(stack(), key, value);
    }

    public void setStackNumber(String key, double value) {
        if (!stack().isEmpty() && key != null && !key.isBlank()) KoperStackData.setDouble(stack(), key, value);
    }

    public Identifier ownerIdentifier() {
        Identifier id = ownerId != null ? Identifier.tryParse(ownerId) : null;
        return id != null ? id : Identifier.fromNamespaceAndPath("koper_lib", "unknown");
    }

    private ScriptEvent scriptEvent() {
        return switch (event == null ? "" : event) {
            case "on_hit", "hit" -> ScriptEvent.ON_HIT;
            case "on_consume", "consume" -> ScriptEvent.ON_CONSUME;
            case "on_place", "place" -> ScriptEvent.ON_PLACE;
            case "on_break", "break" -> ScriptEvent.ON_BREAK;
            case "on_step", "step" -> ScriptEvent.ON_STEP;
            case "on_tick", "tick" -> ScriptEvent.ON_TICK;
            default -> ScriptEvent.ON_USE;
        };
    }
}
