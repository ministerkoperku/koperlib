package com.koper.koper_lib.kender;


import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Same camera + same world, only the entity backend flips. That makes FPS comparisons actually useful.
public final class KenderEntityBench {
    private static final long WARMUP_NS = 2_000_000_000L;
    private static final long SAMPLE_NS = 5_000_000_000L;
    private static Phase phase = Phase.IDLE;
    private static long phaseStart;
    private static long lastFrame;
    private static boolean restoreKender;
    private static Sample vanilla;
    private static Sample kender;

    private KenderEntityBench() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommands.literal("kender")
                .executes(ctx -> feedback("entity=" + (KenderConfig.get().kenderEntityRender ? "kender" : "vanilla")
                    + " " + KenderEntityBatch.stats()))
                .then(ClientCommands.literal("on").executes(ctx -> set(true)))
                .then(ClientCommands.literal("off").executes(ctx -> set(false)))
                .then(ClientCommands.literal("stats").executes(ctx -> feedback(String.valueOf(KenderEntityBatch.stats()))))
                .then(ClientCommands.literal("bench").executes(ctx -> start()))
                .then(ClientCommands.literal("cancel").executes(ctx -> cancel()))
                // first call arms it, second call spits the numbers and disarms
                .then(ClientCommands.literal("prof").executes(ctx -> feedback(
                    KontraLagSniffer.on ? KontraLagSniffer.drain() + " " + KontraLagSniffer.toggle()
                                        : KontraLagSniffer.toggle())))
        ));
    }

    public static void frame() {
        if (phase == Phase.IDLE) return;
        long now = System.nanoTime();
        if (lastFrame != 0 && (phase == Phase.VANILLA || phase == Phase.KENDER)) {
            long dt = now - lastFrame;
            if (dt > 0 && dt < 1_000_000_000L)
                (phase == Phase.VANILLA ? vanilla : kender).add(dt);
        }
        lastFrame = now;
        long elapsed = now - phaseStart;
        if ((phase == Phase.WARMUP_VANILLA || phase == Phase.WARMUP_KENDER) && elapsed >= WARMUP_NS) {
            phase = phase == Phase.WARMUP_VANILLA ? Phase.VANILLA : Phase.KENDER;
            phaseStart = lastFrame = now;
            feedback(phase == Phase.VANILLA ? "measuring vanilla for 5s..." : "measuring Kender for 5s...");
        } else if (phase == Phase.VANILLA && elapsed >= SAMPLE_NS) {
            KenderConfig.get().kenderEntityRender = true;
            phase = Phase.WARMUP_KENDER;
            phaseStart = lastFrame = now;
            feedback("vanilla gotowe; rozgrzewam Kender 2s...");
        } else if (phase == Phase.KENDER && elapsed >= SAMPLE_NS) {
            finish();
        }
    }

    private static int start() {
        if (phase != Phase.IDLE) return feedback("benchmark already running; use /kender cancel to stop it");
        if (!com.koper.koper_lib.kender.KenderFrame.vulkanActive() || !KenderBridge.geoEntityPassAvailable())
            return feedback("Kender Vulkan entity pass is unavailable");
        restoreKender = KenderConfig.get().kenderEntityRender;
        KenderConfig.get().kenderEntityRender = false;
        vanilla = new Sample();
        kender = new Sample();
        phase = Phase.WARMUP_VANILLA;
        phaseStart = System.nanoTime();
        lastFrame = 0;
        return feedback("benchmark started: keep the camera still; warming up vanilla for 2s...");
    }

    private static int cancel() {
        if (phase == Phase.IDLE) return feedback("benchmark is not running");
        KenderConfig.get().kenderEntityRender = restoreKender;
        phase = Phase.IDLE;
        return feedback("benchmark cancelled; previous setting restored");
    }

    private static void finish() {
        KenderConfig.get().kenderEntityRender = restoreKender;
        phase = Phase.IDLE;
        double vf = vanilla.fps(), kf = kender.fps();
        double gain = vf <= 0 ? 0 : (kf / vf - 1.0) * 100.0;
        String result = String.format(java.util.Locale.ROOT,
            "vanilla %.1f fps / p95 %.2fms | Kender %.1f fps / p95 %.2fms | %+.1f%% | frames %d/%d",
            vf, vanilla.p95Ms(), kf, kender.p95Ms(), gain, vanilla.frames.size(), kender.frames.size());
        feedback(result);
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-entity-bench] {}", result);
    }

    private static int set(boolean enabled) {
        if (phase != Phase.IDLE) cancel();
        KenderConfig.get().kenderEntityRender = enabled;
        KenderConfig.save();
        return feedback("entity backend = " + (enabled ? "kender" : "vanilla"));
    }

    private static int feedback(String text) {
        Minecraft mc = Minecraft.getInstance();
        Component msg = Component.literal("[Kender] ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        if (mc.player != null) mc.player.sendSystemMessage(msg);
        else com.koper.koper_lib.coremod.KoperCore.LOGGER.info("{}", msg.getString());
        return 1;
    }

    private enum Phase { IDLE, WARMUP_VANILLA, VANILLA, WARMUP_KENDER, KENDER }

    private static final class Sample {
        final List<Long> frames = new ArrayList<>(4096);
        long total;
        void add(long nanos) { frames.add(nanos); total += nanos; }
        double fps() { return total == 0 ? 0 : frames.size() * 1_000_000_000.0 / total; }
        double p95Ms() {
            if (frames.isEmpty()) return 0;
            List<Long> sorted = new ArrayList<>(frames);
            Collections.sort(sorted);
            return sorted.get(Math.min(sorted.size() - 1, (int)Math.ceil(sorted.size() * 0.95) - 1)) / 1_000_000.0;
        }
    }
}
