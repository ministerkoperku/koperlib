package com.koper.koper_lib.physics;

import com.google.gson.*;
import com.koper.koper_lib.network.KenderSpawnPayload;
import com.koper.koper_lib.network.KoperNetworking;
import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.weight.KhysWeightBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.ticks.TickPriority;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

// kontraktions persist to worlddir/kontrakcions/kontras.bin — compact binary, length-framed records.
// the old pretty-printed JSON got huge with many kontras, and ONE parse error nuked ALL of them
// (getAsJsonArray throws → whole load aborts). binary here is ~5x smaller, each kontra is its own
// length-prefixed record (a corrupt one is skipped, not fatal), and it's written temp-then-rename so a
// crash mid-save can't leave a half-written file. legacy kontras.json is migrated on first load.
public class KontraWorldData {

    private static final int    MAGIC    = 0x4B4B4F4E; // "KKON"
    private static final byte   VERSION  = 10; // 10 poses are exact, no ground fit; 9 body stash; 8 joint limits; 7 block origins; 6 saved ids + joints
    private static final int    MAX_RECORDS = 100_000;
    private static final int    MAX_RECORD_BYTES = 32 * 1024 * 1024;
    private static final String DIR      = "kontrakcions";
    private static final String BIN      = "kontras.bin";
    private static final String OLD_JSON = "kontras.json"; // legacy root file, migrated on load

    private KontraWorldData() {}

    // ── save ────────────────────────────────────────────────────────────────────

    // the server the file was read for. vanilla saves once while the world is still starting, before
    // the load: writing then put an empty file over every saved body
    private static volatile MinecraftServer loadedFor;

    public static void unload() { loadedFor = null; }

    public static void save(MinecraftServer server) {
        if (loadedFor != server) return;
        // serialize each kontra to its own byte[] first so we can length-frame it (and one bad block
        // can't desync the whole file — the reader skips exactly recordLength bytes)
        List<byte[]> records = new ArrayList<>();
        java.util.Set<Long> savedIds = new java.util.HashSet<>();
        for (var e : KoperPhys.all().entrySet()) {
            long id = e.getKey();
            KontraEntry d = e.getValue();
            if (d.blocks.isEmpty()) continue;
            // frozen-while-resting transform → reloads don't accumulate Rapier re-settle drift
            float[] t = KoperPhys.getSaveTransform(id);
            if (t == null) continue;
            float[] pos = { t[0], t[1], t[2] };
            float[] rot = { t[3], t[4], t[5], t[6] };
            try {
                ByteArrayOutputStream rec = new ByteArrayOutputStream(256);
                DataOutputStream o = new DataOutputStream(rec);
                o.writeLong(id); // v6: joints below re-key off this after the restore hands out new ids
                o.writeUTF(d.levelKey());
                o.writeFloat(pos[0]); o.writeFloat(pos[1]); o.writeFloat(pos[2]);
                o.writeFloat(rot[0]); o.writeFloat(rot[1]); o.writeFloat(rot[2]); o.writeFloat(rot[3]);
                o.writeByte(d.aeroOverride() == null ? 0 : d.aeroOverride().id()); // 0 = global default
                o.writeInt(d.blocks.size());
                for (var be : d.blocks.entrySet()) {
                    BlockPos lp = be.getKey();
                    float[] off = d.blockOffsets.get(lp);
                    o.writeInt(lp.getX()); o.writeInt(lp.getY()); o.writeInt(lp.getZ());
                    o.writeFloat(off != null ? off[0] : lp.getX());
                    o.writeFloat(off != null ? off[1] : lp.getY());
                    o.writeFloat(off != null ? off[2] : lp.getZ());
                    // registry-name based (Name + Properties) → a fullpack block stays itself even if its
                    // numeric id shifts between restarts/registry reorders (was the "balon → inny blok" bug)
                    NbtIo.write(NbtUtils.writeBlockState(be.getValue()), o);
                    BlockEntity blockEntity = d.blockEntities.get(lp);
                    CompoundTag carrier = blockEntity != null
                        ? blockEntity.saveWithFullMetadata(server.registryAccess()) : null;
                    carrier = com.koper.koper_lib.api.local.KoperLocalData.pack(carrier, d.localData.get(lp));
                    o.writeBoolean(carrier != null);
                    if (carrier != null) NbtIo.write(carrier, o);
                    BlockPos origin = d.originWorldPos(lp);
                    o.writeBoolean(origin != null);
                    if (origin != null) {
                        o.writeInt(origin.getX());
                        o.writeInt(origin.getY());
                        o.writeInt(origin.getZ());
                    }
                }
                var ticks = d.grid(id).savedTicks();
                o.writeInt(ticks.size());
                for (var tick : ticks) {
                    o.writeInt(tick.localPos().getX());
                    o.writeInt(tick.localPos().getY());
                    o.writeInt(tick.localPos().getZ());
                    o.writeBoolean(tick.fluid());
                    o.writeUTF(tick.type().toString());
                    o.writeLong(tick.delay());
                    o.writeByte(tick.priority().getValue());
                }
                NbtIo.write(d.stash, o); // v9
                records.add(rec.toByteArray());
                savedIds.add(id);
            } catch (IOException ex) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraWorldData] couldn't serialize kontra {}: {}", id, ex.getMessage());
            }
        }

        Path dir = dir(server);
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(BIN + ".tmp");
            try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                o.writeInt(MAGIC);
                o.writeByte(VERSION);
                o.writeInt(records.size());
                for (byte[] rec : records) {
                    o.writeInt(rec.length);
                    o.write(rec);
                }
                // v6 tail: live joints whose BOTH ends made it into this save — bearing vehicles
                // wake up welded instead of falling apart at every restart
                List<KoperPhys.JointSpec> joints = new ArrayList<>();
                for (var js : KoperPhys.jointSpecs().values())
                    if (savedIds.contains(js.a()) && (js.b() == 0L || savedIds.contains(js.b()))) joints.add(js);
                o.writeInt(joints.size());
                for (var js : joints) {
                    o.writeLong(js.a()); o.writeLong(js.b());
                    for (float f : js.anchorA()) o.writeFloat(f);
                    for (float f : js.anchorB()) o.writeFloat(f);
                    for (float f : js.axis())    o.writeFloat(f);
                    o.writeBoolean(js.prismatic());
                    o.writeFloat(js.motorVel()); o.writeFloat(js.motorForce());
                    o.writeFloat(js.limitMin()); o.writeFloat(js.limitMax());
                }
            }
            Path target = dir.resolve(BIN);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amnse) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KontraWorldData] saved {} kontraktions", records.size());
        } catch (IOException ex) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraWorldData] save failed: {}", ex.getMessage());
        }
    }

    // a world that had bodies keeps its file. with every body gone the save still has to run, or the
    // last file stays on disk and the removed bodies come back on the next start
    public static boolean hasSave(MinecraftServer server) {
        return Files.exists(dir(server).resolve(BIN));
    }

    // ── load ────────────────────────────────────────────────────────────────────

    public static void load(MinecraftServer server) {
        loadedFor = server;
        Path bin = dir(server).resolve(BIN);
        if (Files.exists(bin)) { loadBinary(server, bin); return; }
        // first run after the rewrite — migrate the legacy root JSON if it's there
        Path json = server.getWorldPath(LevelResource.ROOT).resolve(OLD_JSON);
        if (Files.exists(json)) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KontraWorldData] migrating legacy kontras.json → binary");
            loadLegacyJson(server, json);
            save(server); // write the binary so next load uses it
        }
    }

    private static void loadBinary(MinecraftServer server, Path bin) {
        int loaded = 0, skipped = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(bin)))) {
            if (in.readInt() != MAGIC) { com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraWorldData] bad magic in {} — not loading", bin); return; }
            byte version = in.readByte();
            if (version < 1 || version > VERSION) { com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraWorldData] unsupported version {} in {}", version, bin); return; }
            // registry lookup for reading blockstates by name (v2); v1 falls back to numeric ids
            HolderGetter<Block> blockLookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
            int count = in.readInt();
            if (count < 0 || count > MAX_RECORDS) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraWorldData] insane record count {} in {}", count, bin);
                return;
            }
            // old save id → freshly spawned id, feeds the joint re-wire + ON_RESTORE for addons
            java.util.Map<Long, Long> remap = new java.util.HashMap<>();
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                if (len <= 0 || len > MAX_RECORD_BYTES) {
                    skipped++;
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KontraWorldData] skipped record with bad length {}", len);
                    if (len > 0) in.skipNBytes(len);
                    continue;
                }
                byte[] rec = new byte[len];
                in.readFully(rec);
                // each record is isolated — a single corrupt kontra is skipped, not fatal for the rest
                try {
                    long[] ids = restoreRecord(server, new DataInputStream(new ByteArrayInputStream(rec)), version, blockLookup);
                    if (ids != null) {
                        loaded++;
                        if (ids[0] != 0) {
                            remap.put(ids[0], ids[1]);
                            KoperPhysicsEvents.fireRestore(ids[0], ids[1]);
                        }
                    } else skipped++;
                } catch (Exception ex) {
                    skipped++;
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KontraWorldData] skipped a corrupt kontra record: {}", ex.getMessage());
                }
            }
            if (version >= 6) restoreJoints(server, in, remap, version);
        } catch (IOException ex) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraWorldData] load failed: {}", ex.getMessage());
        }
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KontraWorldData] loaded {} kontraktions ({} skipped)", loaded, skipped);
    }

    // v6 tail — recreate every saved joint on the NEW kontra ids and re-arm its last motor command
    private static void restoreJoints(MinecraftServer server, DataInputStream in,
                                      java.util.Map<Long, Long> remap, byte version) throws IOException {
        int jointCount = in.readInt();
        if (jointCount < 0 || jointCount > 100_000) throw new IOException("insane joint count " + jointCount);
        record KoperSavedJoint(long a, long b, float[] aa, float[] ab, float[] ax, boolean prismatic,
                               float motorVel, float motorForce, float limitMin, float limitMax) {}
        List<KoperSavedJoint> saved = new ArrayList<>();
        for (int i = 0; i < jointCount; i++) {
            long oldA = in.readLong(), oldB = in.readLong();
            float[] aa = { in.readFloat(), in.readFloat(), in.readFloat() };
            float[] ab = { in.readFloat(), in.readFloat(), in.readFloat() };
            float[] ax = { in.readFloat(), in.readFloat(), in.readFloat() };
            boolean prismatic = in.readBoolean();
            float motorVel = in.readFloat(), motorForce = in.readFloat();
            float limitMin = version >= 8 ? in.readFloat() : Float.NaN;
            float limitMax = version >= 8 ? in.readFloat() : Float.NaN;
            Long a = remap.get(oldA), b = oldB == 0L ? 0L : remap.get(oldB);
            if (a == null || b == null) continue; // one end didn't come back — joint dies with it
            saved.add(new KoperSavedJoint(a, b, aa, ab, ax, prismatic, motorVel, motorForce, limitMin, limitMax));
        }
        // the ground fit in spawnRestored can't tell a part that sits inside a bearing's cell on purpose
        // from a body sunk into terrain, so it lifted it ~2 blocks and every joint came back stretched.
        // the joints were closed when saved: put each body back where its joint says. world ends
        // first, then down the chains (a mounted part on a part on a bearing), a few passes deep
        for (var j : saved)
            if (j.b() == 0L && !j.prismatic()) koperSnapToWorldAnchor(j.a(), j.aa(), j.ab());
        for (int pass = 0; pass < 4; pass++)
            for (var j : saved)
                if (j.b() != 0L && !j.prismatic()) koperSnapChild(j.a(), j.aa(), j.b(), j.ab());
        int revived = 0;
        for (var j : saved) {
            long a = j.a(), b = j.b();
            float[] aa = j.aa(), ab = j.ab(), ax = j.ax();
            boolean prismatic = j.prismatic();
            // world anchors only exist because some block says so. if nothing claims this one any
            // more, leaving it out is the difference between a body that falls and one that hovers
            if (b == 0L) {
                KontraEntry entry = KoperPhys.all().get(a);
                ServerLevel level = entry == null ? null : KoperPhys.levelFor(entry);
                if (!KoperPhysicsEvents.worldJointClaimed(level, ab, ax, prismatic)) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KontraWorldData] dropped an unclaimed world joint at {},{},{}",
                        ab[0], ab[1], ab[2]);
                    continue;
                }
            }
            long jid = prismatic
                ? (b == 0L
                    ? KoperPhys.createWorldPrismaticJoint(a, aa[0], aa[1], aa[2], ab[0], ab[1], ab[2], ax[0], ax[1], ax[2])
                    : KoperPhys.createPrismaticJoint(a, b, aa[0], aa[1], aa[2], ab[0], ab[1], ab[2], ax[0], ax[1], ax[2]))
                : (b == 0L
                    ? KoperPhys.createWorldRevoluteJoint(a, aa[0], aa[1], aa[2], ab[0], ab[1], ab[2], ax[0], ax[1], ax[2])
                    : KoperPhys.createRevoluteJoint(a, b, aa[0], aa[1], aa[2], ab[0], ab[1], ab[2], ax[0], ax[1], ax[2]));
            if (jid <= 0) continue;
            if (Float.isFinite(j.limitMin()) && Float.isFinite(j.limitMax()))
                KoperPhys.setJointLimits(jid, j.limitMin(), j.limitMax());
            if (j.motorForce() > 0f) KoperPhys.setJointMotor(jid, j.motorVel(), j.motorForce());
            revived++;
        }
        if (jointCount > 0)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KontraWorldData] revived {}/{} joints", revived, jointCount);
    }

    private static void koperSnapToWorldAnchor(long id, float[] localAnchor, float[] worldAnchor) {
        float[] pos = KoperPhys.getCachedPos(id);
        float[] rot = KoperPhys.getCachedRot(id);
        if (pos == null || rot == null) return;
        float[] at = KoperPhys.localToWorld(localAnchor[0], localAnchor[1], localAnchor[2], pos, rot);
        float dx = worldAnchor[0] - at[0], dy = worldAnchor[1] - at[1], dz = worldAnchor[2] - at[2];
        float off = dx * dx + dy * dy + dz * dz;
        // past a few blocks it's not a restore nudge any more, something else moved and we leave it
        if (off > 1.0e-4f && off < 16f) {
            if (off > 0.09f)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KontraWorldData] snapped kontra {} onto its joint by {} {} {}",
                    id, String.format("%.2f", dx), String.format("%.2f", dy), String.format("%.2f", dz));
            KoperPhys.translateKontraktion(id, dx, dy, dz);
        }
    }

    // b hangs off a: move b so its anchor lands on a's
    private static void koperSnapChild(long a, float[] anchorA, long b, float[] anchorB) {
        float[] posA = KoperPhys.getCachedPos(a), rotA = KoperPhys.getCachedRot(a);
        if (posA == null || rotA == null) return;
        koperSnapToWorldAnchor(b, anchorB, KoperPhys.localToWorld(anchorA[0], anchorA[1], anchorA[2], posA, rotA));
    }

    // returns [savedId, newId] on spawn (savedId 0 for pre-v6 files), null if dropped
    private static long[] restoreRecord(MinecraftServer server, DataInputStream in, byte version,
                                        HolderGetter<Block> blockLookup) throws IOException {
        long savedId = version >= 6 ? in.readLong() : 0L;
        String levelKey = in.readUTF();
        float cx = in.readFloat(), cy = in.readFloat(), cz = in.readFloat();
        float qx = in.readFloat(), qy = in.readFloat(), qz = in.readFloat(), qw = in.readFloat();
        // 0 = follows the global default. old saves wrote CORRECT for everything because that WAS the
        // default, so a 2 means the same thing
        int aeroId = version >= 3 ? in.readUnsignedByte() : 0;
        AeroMode aeroMode = aeroId == 0 || aeroId == AeroMode.CORRECT.id() ? null : AeroMode.byId(aeroId);
        int n = in.readInt();
        if (n <= 0) return null;

        List<BlockState> states = new ArrayList<>(n);
        float[] offsets  = new float[n * 3];
        int[]   stateIds = new int[n];
        int[]   coords   = new int[n * 3];
        float[] masses   = new float[n];
        CompoundTag[] blockEntityTags = new CompoundTag[n];
        BlockPos[] origins = new BlockPos[n];
        int lightCount = 0;
        for (int j = 0; j < n; j++) {
            int lx = in.readInt(), ly = in.readInt(), lz = in.readInt();
            float fox = in.readFloat(), foy = in.readFloat(), foz = in.readFloat();
            BlockState bs = version >= 2
                ? com.koper.koper_lib.api.core.KoperBlockStateNbt.read(blockLookup, NbtIo.read(in))   // v2: by registry name (stable), 26.2 saves too
                : Block.stateById(in.readInt());                        // v1: legacy numeric (best-effort migration)
            if (bs == null) bs = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(); // missing block (fullpack off) → air
            int sid = Block.getId(bs);   // current-session numeric id, only for the client render payload (not persisted)
            states.add(bs);
            offsets[j*3] = fox; offsets[j*3+1] = foy; offsets[j*3+2] = foz;
            stateIds[j] = sid;
            coords[j*3] = lx; coords[j*3+1] = ly; coords[j*3+2] = lz;
            masses[j] = KhysWeightBook.get(bs).mass();
            if (bs.is(KoperPhys.LIGHT_BLOCKS)) lightCount++;
            if (version >= 4 && in.readBoolean()) blockEntityTags[j] = NbtIo.read(in);
            if (version >= 7 && in.readBoolean())
                origins[j] = new BlockPos(in.readInt(), in.readInt(), in.readInt());
        }
        List<KontraGrid.SavedGridTick> gridTicks = new ArrayList<>();
        if (version >= 5) {
            int tickCount = in.readInt();
            if (tickCount < 0 || tickCount > 1_000_000)
                throw new IOException("insane grid tick count " + tickCount);
            for (int j = 0; j < tickCount; j++) {
                BlockPos local = new BlockPos(in.readInt(), in.readInt(), in.readInt());
                boolean fluid = in.readBoolean();
                Identifier type = Identifier.parse(in.readUTF());
                long delay = in.readLong();
                TickPriority priority = TickPriority.byValue(in.readByte());
                gridTicks.add(new KontraGrid.SavedGridTick(local, fluid, type, delay, priority));
            }
        }
        CompoundTag stash = version >= 9 ? NbtIo.read(in) : null;
        long newId = spawnRestored(server, levelKey, cx, cy, cz, qx, qy, qz, qw, states, offsets, stateIds,
            coords, masses, lightCount, aeroMode, blockEntityTags, origins, gridTicks, version < 10);
        // before ON_RESTORE fires, so a listener re-keying its data finds the stash already there
        if (newId > 0 && stash != null) {
            KontraEntry restored = KoperPhys.all().get(newId);
            if (restored != null) restored.stash.merge(stash);
        }
        return newId > 0 ? new long[]{savedId, newId} : null;
    }

    // shared spawn for both binary + legacy-json restore — returns the new kontra id, -1 if dropped
    private static long spawnRestored(MinecraftServer server, String levelKey,
                                      float cx, float cy, float cz, float qx, float qy, float qz, float qw,
                                      List<BlockState> states, float[] offsets, int[] stateIds, int[] coords,
                                      float[] masses, int lightCount, AeroMode aeroMode, CompoundTag[] blockEntityTags,
                                      BlockPos[] origins, List<KontraGrid.SavedGridTick> gridTicks, boolean fitGround) {
        ServerLevel level = KoperPhys.findLevel(server, levelKey);
        if (level == null) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KontraWorldData] level {} gone, dropping a kontra", levelKey);
            return -1L;
        }
        // air never comes back as a block: an old save that stored it, or a block whose mod is gone.
        // spawned as is it was a collider with nothing drawn in it
        int kept = 0;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).isAir()) continue;
            if (kept != i) {
                states.set(kept, states.get(i));
                System.arraycopy(offsets, i * 3, offsets, kept * 3, 3);
                System.arraycopy(coords, i * 3, coords, kept * 3, 3);
                stateIds[kept] = stateIds[i];
                masses[kept] = masses[i];
                blockEntityTags[kept] = blockEntityTags[i];
                origins[kept] = origins[i];
            }
            kept++;
        }
        if (kept == 0) return -1L;
        if (kept < states.size()) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KontraWorldData] left {} air/missing block(s) out of a restored kontra",
                states.size() - kept);
            states = new ArrayList<>(states.subList(0, kept));
            offsets = java.util.Arrays.copyOf(offsets, kept * 3);
            coords = java.util.Arrays.copyOf(coords, kept * 3);
            stateIds = java.util.Arrays.copyOf(stateIds, kept);
            masses = java.util.Arrays.copyOf(masses, kept);
            blockEntityTags = java.util.Arrays.copyOf(blockEntityTags, kept);
            origins = java.util.Arrays.copyOf(origins, kept);
        }
        long worldHandle = KoperPhys.getWorldHandle(level);

        // prime the section cache BEFORE the spawn cmd — same queue, so the body never steps
        // without its floor (the old restore fell through the ground for a couple ticks)
        int r = 8;
        for (int j = 0; j < offsets.length; j += 3) {
            r = Math.max(r, Math.abs(Math.round(offsets[j])));
            r = Math.max(r, Math.abs(Math.round(offsets[j+1])));
            r = Math.max(r, Math.abs(Math.round(offsets[j+2])));
        }
        // old saves carry sunk poses (pre-section era) and the save pin kept re-serving them —
        // a restored body embedded in terrain rides the anti-clip escalator up = "kontra leci
        // upward on every load. fit the restore so the lowest block sits on the ground, not in it.
        // v10+ saves hold the pose the body rested in, parts inside a bearing's cell on purpose included:
        // fitting those lifted every rotor a block on every load. only old files get fitted.
        // each block looks at the cell it sits in, not one column under the body's middle: Math.round
        // of a lone block's x.5 centre is the NEIGHBOUR's column, and a step there lifted the block a
        // whole block on every load
        float[] rq = { qx, qy, qz, qw };
        double lift = 0;
        String liftedBy = "";
        KoperPhys.TERRAIN_SCAN_ACTIVE.set(true);
        try {
            for (int j = 0; fitGround && j < offsets.length; j += 3) {
                float[] w = KoperPhys.localToWorld(offsets[j], offsets[j+1], offsets[j+2],
                    new float[]{cx, cy, cz}, rq);
                int bx = (int)Math.floor(w[0]), bz = (int)Math.floor(w[2]);
                double bottom = w[1] - 0.5;
                // a block is sunk while the cell holding its lower half is solid terrain; walk up out of it
                for (int y = (int)Math.floor(bottom + 0.02), steps = 0; steps < 4; y++, steps++) {
                    var bp = new net.minecraft.core.BlockPos(bx, y, bz);
                    if (y < level.getMinY()) continue;
                    var bs = level.getBlockState(bp);
                    if (bs.isAir() || bs.getCollisionShape(level, bp).isEmpty()) break;
                    double top = y + bs.getCollisionShape(level, bp).max(net.minecraft.core.Direction.Axis.Y);
                    if (top - bottom > lift + 0.02) {
                        lift = top - bottom;
                        liftedBy = states.get(j / 3).getBlock() + " at " + String.format("%.2f %.2f %.2f", w[0], w[1], w[2])
                            + " sits in " + bs.getBlock() + " at " + bp.toShortString();
                    }
                }
            }
        } finally {
            KoperPhys.TERRAIN_SCAN_ACTIVE.set(false);
        }
        if (lift > 0.02) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KontraWorldData] restore lifted kontra out of the ground by {}: {}", String.format("%.2f", lift), liftedBy);
            cy += (float)lift;
        }

        int ox = Math.round(cx), oy = Math.round(cy), oz = Math.round(cz);
        com.koper.koper_lib.physics.terrain.TerrainSlurper.primeArea(level, worldHandle,
            ox - r, oy - r, oz - r, ox + r, oy + r, oz + r);
        long kontraId = KoperPhysBridge.spawnKontraktionOffsets(worldHandle, offsets, masses, lightCount, cx, cy, cz);
        if (kontraId <= 0) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KontraWorldData] rust refused restored kontra in {} at {},{},{}", levelKey, cx, cy, cz);
            return -1L;
        }
        // restore may carry a non-identity rotation — setTransform applies it (terrain already present)
        KoperPhysBridge.setTransform(worldHandle, kontraId, cx, cy, cz, qx, qy, qz, qw);

        KontraEntry data = new KontraEntry(levelKey, worldHandle, states, offsets);
        data.setAeroMode(aeroMode);
        for (int i = 0; i < states.size(); i++) {
            BlockPos local = new BlockPos(coords[i*3], coords[i*3+1], coords[i*3+2]);
            if (i < origins.length && origins[i] != null)
                data.assembledFrom.put(local, origins[i].immutable());
            if (i >= blockEntityTags.length || blockEntityTags[i] == null) continue;
            CompoundTag localPayload = com.koper.koper_lib.api.local.KoperLocalData.unpack(blockEntityTags[i]);
            if (localPayload != null) data.localData.put(local, localPayload);
            KoperPhys.stripKineticNbt(blockEntityTags[i]); // old saves carry pre-strip stale networks
            if (!blockEntityTags[i].contains("id")) continue;
            BlockEntity be = BlockEntity.loadStatic(data.grid(kontraId).toGrid(local), states.get(i),
                blockEntityTags[i], level.registryAccess());
            if (be != null) {
                be.setLevel(level);
                data.blockEntities.put(local, be);
            }
        }
        KoperPhys.restoreEntry(kontraId, data, new float[]{cx,cy,cz}, new float[]{qx,qy,qz,qw});
        KoperPhys.bootstrapGrid(level, kontraId, data, false);
        data.grid(kontraId).restoreTicks(gridTicks);
        KoperPhys.pushMaterials(kontraId, data);
        KoperPhys.pushAero(kontraId, data);

        var render = data.renderArrays();
        KhysicsNetworking.broadcastSpawn(level, new KenderSpawnPayload(
            kontraId, new float[]{cx,cy,cz}, new float[]{qx,qy,qz,qw}, render.stateIds(), render.offsets(), render.locals(),
            KoperPhys.blockEntityTags(data, level)));
        return kontraId;
    }

    // ── legacy JSON migration ────────────────────────────────────────────────────

    private static void loadLegacyJson(MinecraftServer server, Path json) {
        JsonArray arr;
        try (Reader r = Files.newBufferedReader(json)) {
            arr = JsonParser.parseReader(r).getAsJsonArray();
        } catch (Exception ex) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraWorldData] legacy json load failed: {}", ex.getMessage());
            return;
        }
        for (JsonElement el : arr) {
            try {
                JsonObject kt = el.getAsJsonObject();
                String levelKey = kt.get("level").getAsString();
                JsonArray blist = kt.getAsJsonArray("blocks");
                if (blist == null || blist.isEmpty()) continue;
                int n = blist.size();
                List<BlockState> states = new ArrayList<>(n);
                float[] offsets = new float[n*3]; int[] stateIds = new int[n];
                int[] coords = new int[n*3]; float[] masses = new float[n];
                int lightCount = 0;
                for (int j = 0; j < n; j++) {
                    JsonObject bl = blist.get(j).getAsJsonObject();
                    int lx = bl.get("lx").getAsInt(), ly = bl.get("ly").getAsInt(), lz = bl.get("lz").getAsInt();
                    float fox = bl.has("fox") ? bl.get("fox").getAsFloat() : lx;
                    float foy = bl.has("foy") ? bl.get("foy").getAsFloat() : ly;
                    float foz = bl.has("foz") ? bl.get("foz").getAsFloat() : lz;
                    int sid = bl.get("sid").getAsInt();
                    BlockState bs = Block.stateById(sid);
                    states.add(bs);
                    offsets[j*3] = fox; offsets[j*3+1] = foy; offsets[j*3+2] = foz;
                    stateIds[j] = sid; coords[j*3] = lx; coords[j*3+1] = ly; coords[j*3+2] = lz;
                    masses[j] = KhysWeightBook.get(bs).mass();
                    if (bs.is(KoperPhys.LIGHT_BLOCKS)) lightCount++;
                }
                float cx = kt.get("px").getAsFloat(), cy = kt.get("py").getAsFloat(), cz = kt.get("pz").getAsFloat();
                float qx = kt.get("qx").getAsFloat(), qy = kt.get("qy").getAsFloat();
                float qz = kt.get("qz").getAsFloat(), qw = kt.get("qw").getAsFloat();
                spawnRestored(server, levelKey, cx, cy, cz, qx, qy, qz, qw, states, offsets, stateIds,
                    coords, masses, lightCount, AeroMode.CORRECT, new CompoundTag[n], new BlockPos[n], List.of(), true);
            } catch (Exception ex) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KontraWorldData] skipped a bad legacy kontra: {}", ex.getMessage());
            }
        }
    }

    private static Path dir(MinecraftServer server) {
        // own folder inside the world save — each world keeps its own kontraktions
        return server.getWorldPath(LevelResource.ROOT).resolve(DIR);
    }
}
