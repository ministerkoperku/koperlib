package com.koper.koper_lib.physics;

import com.koper.koper_lib.api.core.KoperConfigFile;

/** Settings owned by Khysics, local grids, and its conditional Create adapter. */
public final class KhysicsConfig {
    private static final KoperConfigFile<KhysicsConfig> FILE = new KoperConfigFile<>(
        "khysics", KhysicsConfig.class, KhysicsConfig::new);
    private static KhysicsConfig instance;

    public boolean enablePhysics = true;
    public int maxKontraktionBlocks = -1;
    public String kontraCameraMode = "vanilla";
    public float kenderBlockCullDistance = 128f;
    public int kenderMaxBlocksPerKontraktion = -1;
    // geometry only: an aggregate per-player ACK window keeps slow connections bounded
    public int kenderGeometryStreamThreshold = 8_192;
    public int kenderGeometryChunkBytes = 8_192;
    public int kenderGeometryBytesPerTick = 65_536;
    public int kenderGeometryWindowBytes = 65_536;
    public String createRender = "auto";
    public boolean kontraFancyLight = true;
    public int kontraLightSpillRange = 7;
    // most liquid cells one kontra holds. flowing water spreads over the hull like in the world and
    // stops here, so one bucket cannot turn a big ship into a water bomb. 0 = sources only, no flow
    public int kontraFluidCellLimit = 256;
    // which physics engine khysics runs on: rapier (everything) or elpe (cheap, for big servers)
    public String physicsBackend = "rapier";
    // aero mode for every kontra that has not been given its own (low / correct / extreme)
    public String defaultAeroMode = "correct";
    // share of a propeller's drag torque that turns its airframe (0..1). game props spin several times
    // slower than real ones for the same power, so the full reaction rolled planes far harder than any
    // real aircraft; 0.25 is about the real ratio of engine torque to aileron authority. 1 = all of it
    public float propellerReaction = 0.25f;
    // weather wind from the dimension files (khysics/dimensions/*.json). false = still air everywhere
    public boolean wind = true;

    public static KhysicsConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() { instance = FILE.load(); }
    public static void save() { FILE.save(get()); }
}
