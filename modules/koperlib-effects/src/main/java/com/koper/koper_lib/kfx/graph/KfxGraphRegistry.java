package com.koper.koper_lib.kfx.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Two-phase registry: declarations are mutable, linked snapshots replace atomically only on success. */
public final class KfxGraphRegistry {
    private Map<String, Declaration> declarations = new LinkedHashMap<>();
    private Map<String, Declaration> stagedDeclarations;
    private Map<String, KfxLinkedGraph> linked = Map.of();
    private boolean dirty;

    public synchronized void declare(KfxOrigin origin, KfxGraph graph) {
        if (origin == null || graph == null) throw new IllegalArgumentException("KFX declaration needs origin and graph");
        Map<String, Declaration> target = stagedDeclarations != null ? stagedDeclarations : declarations;
        Declaration previous = target.get(graph.id());
        if (previous != null && previous.origin() != origin) {
            throw new KfxGraphException(graph.source(), "$.id",
                "graph '" + graph.id() + "' is already owned by " + previous.origin().name().toLowerCase());
        }
        target.put(graph.id(), new Declaration(origin, graph));
        if (stagedDeclarations == null) dirty = true;
    }

    public synchronized void beginReload(Set<KfxOrigin> rebuiltOrigins) {
        // Starting a new scan always rebuilds from the active snapshot. This also recovers safely
        // if an external reload coordinator died before closing its previous candidate.
        stagedDeclarations = new LinkedHashMap<>(declarations);
        stagedDeclarations.entrySet().removeIf(entry -> rebuiltOrigins.contains(entry.getValue().origin()));
    }

    public synchronized Map<String, KfxLinkedGraph> commitReload() {
        if (stagedDeclarations == null) return linkAll();
        Map<String, KfxLinkedGraph> candidate = KfxGraphLinker.link(Map.copyOf(stagedDeclarations));
        declarations = stagedDeclarations;
        stagedDeclarations = null;
        linked = candidate;
        dirty = false;
        return linked;
    }

    public synchronized void abortReload() {
        stagedDeclarations = null;
    }

    public synchronized Map<String, KfxLinkedGraph> linkAll() {
        Map<String, KfxLinkedGraph> candidate = KfxGraphLinker.link(Map.copyOf(declarations));
        linked = candidate;
        dirty = false;
        return linked;
    }

    public synchronized KfxLinkedGraph linked(String id) {
        if (dirty) {
            try {
                linkAll();
            } catch (KfxGraphException error) {
                KfxLinkedGraph previous = linked.get(id);
                if (previous != null) return previous;
                throw error;
            }
        }
        return linked.get(id);
    }

    /** Runtime lookup keeps serving the last atomic snapshot while a replacement is invalid. */
    public synchronized KfxLinkedGraph runtimeLinked(String id) {
        if (dirty) {
            try {
                linkAll();
            } catch (KfxGraphException error) {
                return linked.get(id);
            }
        }
        return linked.get(id);
    }

    public synchronized KfxLinkedGraph lastLinked(String id) {
        return linked.get(id);
    }

    public synchronized void clearOrigin(KfxOrigin origin) {
        if (declarations.entrySet().removeIf(entry -> entry.getValue().origin() == origin)) dirty = true;
    }

    public synchronized void clear() {
        declarations.clear();
        stagedDeclarations = null;
        linked = Map.of();
        dirty = false;
    }

    record Declaration(KfxOrigin origin, KfxGraph graph) {}
}
