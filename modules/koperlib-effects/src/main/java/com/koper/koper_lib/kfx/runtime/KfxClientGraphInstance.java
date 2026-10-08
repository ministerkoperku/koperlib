package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxAnchor;

import java.util.Objects;

public final class KfxClientGraphInstance {
    private final long id;
    private final KfxAnchorResolver resolver = new KfxAnchorResolver();
    private KfxAnchor startAnchor;
    private KfxAnchor endAnchor;
    private KfxTransform start;
    private KfxTransform end;

    public KfxClientGraphInstance(long id, KfxAnchor startAnchor, KfxAnchor endAnchor,
                                  KfxTransform start, KfxTransform end) {
        this.id = id;
        this.startAnchor = Objects.requireNonNull(startAnchor, "startAnchor");
        this.endAnchor = Objects.requireNonNull(endAnchor, "endAnchor");
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
    }

    public KfxAnchorState resolveFrame(KfxAnchorResolver.PoseSource source, float partialTick) {
        KfxAnchorResolver.Result startResult = resolve(startAnchor, source, partialTick, start);
        KfxAnchorResolver.Result endResult = resolve(endAnchor, source, partialTick, end);
        if (startResult.state() == KfxAnchorState.ACTIVE) start = startResult.transform();
        if (endResult.state() == KfxAnchorState.ACTIVE) end = endResult.transform();
        if (startResult.state() == KfxAnchorState.DETACH) startAnchor = null;
        if (endResult.state() == KfxAnchorState.DETACH) endAnchor = null;
        return strongest(startResult.state(), endResult.state());
    }

    // swap anchors on the live instance. keeps the last frame so the effect doesn't jump
    public void reanchor(KfxAnchor startAnchor, KfxAnchor endAnchor) {
        this.startAnchor = Objects.requireNonNull(startAnchor, "startAnchor");
        this.endAnchor = Objects.requireNonNull(endAnchor, "endAnchor");
    }

    public void detach() {
        startAnchor = null;
        endAnchor = null;
    }

    public long id() { return id; }
    public KfxTransform start() { return start; }
    public KfxTransform end() { return end; }

    private KfxAnchorResolver.Result resolve(KfxAnchor anchor, KfxAnchorResolver.PoseSource source,
                                             float partialTick, KfxTransform last) {
        if (anchor == null) return new KfxAnchorResolver.Result(KfxAnchorState.ACTIVE, last);
        return resolver.resolve(anchor, source, partialTick, last);
    }

    private static KfxAnchorState strongest(KfxAnchorState a, KfxAnchorState b) {
        if (a == KfxAnchorState.KILL || b == KfxAnchorState.KILL) return KfxAnchorState.KILL;
        if (a == KfxAnchorState.FADE || b == KfxAnchorState.FADE) return KfxAnchorState.FADE;
        if (a == KfxAnchorState.FREEZE || b == KfxAnchorState.FREEZE) return KfxAnchorState.FREEZE;
        if (a == KfxAnchorState.DETACH || b == KfxAnchorState.DETACH) return KfxAnchorState.DETACH;
        return KfxAnchorState.ACTIVE;
    }
}
