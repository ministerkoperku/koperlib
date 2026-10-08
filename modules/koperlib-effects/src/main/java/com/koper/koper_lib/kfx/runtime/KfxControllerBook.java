package com.koper.koper_lib.kfx.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class KfxControllerBook {
    public static final int MAX_CONTROLLERS = 128;
    private final Map<Long, KfxController> controllers = new LinkedHashMap<>();

    public void add(KfxController controller) {
        if (controller == null) throw new IllegalArgumentException("KFX controller cannot be null");
        if (controllers.size() >= MAX_CONTROLLERS) {
            throw new IllegalStateException("KFX controller hard cap reached: " + MAX_CONTROLLERS);
        }
        if (controllers.putIfAbsent(controller.handle(), controller) != null) {
            throw new IllegalStateException("KFX controller handle is already active: " + controller.handle());
        }
    }

    public boolean stop(long handle) {
        return controllers.remove(handle) != null;
    }

    public KfxController get(long handle) { return controllers.get(handle); }

    public Frame tick(Function<KfxController, KfxSensor> sensors) {
        var motions = new ArrayList<KfxController.Step>(controllers.size());
        var impacts = new ArrayList<KfxImpact>();
        var dead = new ArrayList<Long>();
        for (KfxController controller : controllers.values()) {
            KfxController.Step step = controller.step(sensors.apply(controller));
            motions.add(step);
            if (step.impact() != null) impacts.add(step.impact());
            if (step.state() == KfxController.State.STOPPED) dead.add(controller.handle());
        }
        dead.forEach(controllers::remove);
        return new Frame(List.copyOf(motions), List.copyOf(impacts));
    }

    public boolean isEmpty() { return controllers.isEmpty(); }
    public int size() { return controllers.size(); }
    public void clear() { controllers.clear(); }

    public record Frame(List<KfxController.Step> motions, List<KfxImpact> impacts) {}
}
