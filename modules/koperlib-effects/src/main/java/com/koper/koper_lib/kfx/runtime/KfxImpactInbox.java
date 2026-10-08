package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.network.KfxImpactPayload;

import java.util.HashMap;
import java.util.Map;

public final class KfxImpactInbox {
    private final Map<Long, KfxImpactPayload> latest = new HashMap<>();

    public boolean accept(KfxImpactPayload impact) {
        KfxImpactPayload old = latest.get(impact.handle());
        if (old != null && impact.sequence() <= old.sequence()) return false;
        latest.put(impact.handle(), impact);
        return true;
    }

    public KfxImpactPayload latest(long handle) {
        return latest.get(handle);
    }

    public void forget(long handle) {
        latest.remove(handle);
    }

    public void clear() {
        latest.clear();
    }
}
