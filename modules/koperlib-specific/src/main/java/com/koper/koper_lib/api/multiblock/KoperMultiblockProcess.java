package com.koper.koper_lib.api.multiblock;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Persistent timed process for multiblock controllers and other machines. */
public final class KoperMultiblockProcess {
    private int progress;
    private int duration;
    private UUID owner;

    public boolean start(UUID owner, int durationTicks) {
        if (active() || owner == null || durationTicks <= 0) return false;
        this.owner = owner;
        this.duration = durationTicks;
        progress = 0;
        return true;
    }

    public TickResult tick(BooleanSupplier structureValid, IntConsumer progressListener) {
        if (!active()) return TickResult.IDLE;
        if (!structureValid.getAsBoolean()) {
            reset();
            return TickResult.ABORTED;
        }
        progress++;
        if (progressListener != null) progressListener.accept(progress);
        if (progress < duration) return TickResult.RUNNING;
        progress = 0;
        duration = 0;
        return TickResult.COMPLETED;
    }

    public void cancel() { reset(); }
    public boolean active() { return owner != null && duration > 0; }
    public int progress() { return progress; }
    public int duration() { return duration; }
    public int percent() { return duration <= 0 ? 0 : Math.min(100, progress * 100 / duration); }
    public UUID owner() { return owner; }

    public void save(ValueOutput output, String prefix) {
        String key = prefix(prefix);
        output.putInt(key + "progress", progress);
        output.putInt(key + "duration", duration);
        if (owner != null) output.putString(key + "owner", owner.toString());
    }

    public void load(ValueInput input, String prefix) {
        String key = prefix(prefix);
        progress = Math.max(0, input.getIntOr(key + "progress", 0));
        duration = Math.max(0, input.getIntOr(key + "duration", 0));
        String raw = input.getStringOr(key + "owner", "");
        try { owner = raw.isEmpty() ? null : UUID.fromString(raw); }
        catch (IllegalArgumentException ignored) { owner = null; }
        if (owner == null || duration <= 0) reset();
    }

    private void reset() {
        progress = 0;
        duration = 0;
        owner = null;
    }

    private static String prefix(String value) {
        return value == null || value.isBlank() ? "koper_process_" : value;
    }

    public enum TickResult { IDLE, RUNNING, COMPLETED, ABORTED }
}
