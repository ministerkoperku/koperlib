package com.koper.koper_lib.kender;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// per-tick OBB raycast against all physics blocks
// if the player is looking at a physics block closer than any vanilla block, we store the hit
// PhysicsPlaceMixin reads this to intercept right-click-to-place
public final class KenderTargeting {

    public record PhysHit(
        long  kontraId,
        int   blockIndex,         // which block within the kontraktion was hit
        float newOffX, float newOffY, float newOffZ,  // body-local offset for the new block to attach
        float dist,               // ray distance to hit (for vanilla comparison)
        int   hitLocalX, int hitLocalY, int hitLocalZ,  // rounded local coords of the hit block (for break)
        float hitX, float hitY, float hitZ // exact hit relative to this block's center, in kontra axes
    ) {
        public BlockPos localPos() {
            return new BlockPos(hitLocalX, hitLocalY, hitLocalZ);
        }

        public Direction localFace() {
            KenderClientState.KontraRenderData grid = KenderClientState.getById(kontraId);
            if (grid == null || blockIndex * 3 + 2 >= grid.offsets.length) return Direction.UP;
            int i = blockIndex * 3;
            return Direction.getApproximateNearest(
                newOffX - grid.offsets[i], newOffY - grid.offsets[i + 1], newOffZ - grid.offsets[i + 2]);
        }

        public BlockPos logicalPos() {
            KenderClientState.KontraRenderData grid = KenderClientState.getById(kontraId);
            return grid != null ? grid.toGrid(localPos()) : localPos();
        }

        public Vec3 blockPoint() {
            return new Vec3(hitX + 0.5, hitY + 0.5, hitZ + 0.5);
        }

        public com.koper.koper_lib.network.KenderHitRef networkRef() {
            return new com.koper.koper_lib.network.KenderHitRef(
                kontraId, localPos(), localFace().ordinal(), hitX, hitY, hitZ);
        }
    }

    private static volatile PhysHit current = null;
    // the world cell we last forced onto mc.hitResult — lets us recognise OUR phantom in the distance
    // gate without the lossy getClientBlockStateAt round-trip (which misses on rotated kontras → flicker)
    private static volatile BlockPos lastSetPos = null;

    // scratch — client tick is single-threaded, zero allocation per block per frame
    private static final Quaternionf SCRATCH_Q    = new Quaternionf();
    private static final Quaternionf SCRATCH_QINV = new Quaternionf();
    private static final Vector3f    SCRATCH_OFF  = new Vector3f();
    private static final Vector3f    SCRATCH_LO   = new Vector3f(); // local ray origin
    private static final Vector3f    SCRATCH_LD   = new Vector3f(); // local ray dir

    // key state tracked per tick — used by KenderPlaceClientMixin for fresh-click detection
    // volatile: tick thread writes, render thread reads
    public static volatile boolean freshRightClick = false;
    private static boolean prevRightDown = false;

    public static PhysHit getHit()      { return current; }
    public static boolean isTargeting() { return current != null; }

    public static PhysHit refreshHit(Minecraft mc) {
        update(mc);
        return current;
    }

    // exact state of the block being aimed at — ClientLevelAccessMixin returns THIS at wbp so vanilla
    // mining/crack hits the right block. wbp rounding can re-derive a neighbour otherwise → dirt mines
    // at stone speed, or the cell maps to air and the block acts like you're hitting nothing.
    public static BlockState targetedState() {
        PhysHit h = current;
        if (h == null) return null;
        KenderClientState.KontraRenderData k = KenderClientState.getById(h.kontraId());
        if (k == null) return null;
        int idx = h.blockIndex();
        if (idx < 0 || idx >= k.states.length) return null;
        return k.states[idx];
    }

    public static KenderClientState.KontraRenderData targetedGrid() {
        PhysHit hit = current;
        return hit != null ? KenderClientState.getById(hit.kontraId()) : null;
    }

    public static boolean targetsLogical(BlockPos pos, BlockState state) {
        PhysHit hit = current;
        if (hit == null || !hit.logicalPos().equals(pos)) return false;
        BlockState targeted = targetedState();
        return targeted != null && (state == null || targeted.getBlock() == state.getBlock());
    }

    public static boolean targetsProjected(BlockPos pos, BlockState state) {
        PhysHit hit = current;
        if (hit == null || !pos.equals(lastSetPos)) return false;
        BlockState targeted = targetedState();
        return targeted != null && (state == null || targeted.getBlock() == state.getBlock());
    }

    // in vanilla you cannot select water: its outline shape is empty and only a bucket's
    // ClipContext.Fluid.SOURCE_ONLY reaches it. here every cell falls back to KGeoBook's full cube,
    // so a puddle on deck was eating the click meant for the planks underneath and you could not
    // build in it, mine through it, or aim past it. bucket in hand still gets to hit the water.
    private static boolean clickThrough(BlockState state, net.minecraft.world.entity.player.Player player) {
        if (state == null) return true;
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) return false;
        return !(player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BucketItem)
            && !(player.getOffhandItem().getItem() instanceof net.minecraft.world.item.BucketItem);
    }

    public static BlockHitResult cameraClip(Vec3 from, Vec3 to, float currentBestDistance) {
        if (KenderClientState.isEmpty()) return null;
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0e-6) return null;
        Vec3 dir = delta.scale(1.0 / len);
        float best = Math.min((float)len, currentBestDistance);
        BlockHitResult bestHit = null;
        long nowNs = System.nanoTime();

        for (KenderClientState.KontraRenderData k : KenderClientState.all()) {
            float[] rp = KenderClientState.renderPos(k, nowNs);
            float[] rr = KenderClientState.renderRot(k, nowNs);
            if (rp == null || rr == null) continue;
            float px = rp[0], py = rp[1], pz = rp[2];
            Quaternionf q = new Quaternionf(rr[0], rr[1], rr[2], rr[3]);
            Quaternionf qi = new Quaternionf(q).conjugate();

            int blockCount = k.offsets.length / 3;
            for (int i = 0; i < blockCount; i++) {
                Vector3f off = new Vector3f(k.offsets[i*3], k.offsets[i*3+1], k.offsets[i*3+2]);
                q.transform(off);
                double bx = px + off.x, by = py + off.y, bz = pz + off.z;

                Vector3f lo = new Vector3f((float)(from.x - bx), (float)(from.y - by), (float)(from.z - bz));
                Vector3f ld = new Vector3f((float)dir.x, (float)dir.y, (float)dir.z);
                qi.transform(lo);
                qi.transform(ld);

                BlockState state = i < k.states.length ? k.states[i] : null;
                // camera never gets shoved by a puddle
                if (state != null && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
                ShapeHit shapeHit = state == null ? null
                    : voxelShapeTest(lo, ld,
                        com.koper.koper_lib.api.core.KoperBlockShapes.shape(state));
                float t = shapeHit != null ? shapeHit.distance() : -1f;
                if (t < 0 || t >= best) continue;
                best = t;

                float hx = lo.x + ld.x * t;
                float hy = lo.y + ld.y * t;
                float hz = lo.z + ld.z * t;
                float[] localFace = shapeHit.face();
                Vector3f wf = new Vector3f(localFace[0], localFace[1], localFace[2]);
                q.transform(wf);
                Direction face = Direction.getNearest(new net.minecraft.core.Vec3i(
                    Math.round(wf.x), Math.round(wf.y), Math.round(wf.z)), Direction.UP);
                Vec3 hit = from.add(dir.scale(t));
                bestHit = new BlockHitResult(hit, face, BlockPos.containing(hit), false);
            }
        }
        return bestHit;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // track right-click key transitions for placement cooldown bypass
            boolean rightDown = mc.options != null && mc.options.keyUse.isDown();
            freshRightClick = rightDown && !prevRightDown;
            prevRightDown = rightDown;
            update(mc);
        });
    }

    private static void update(Minecraft mc) {
        current = null;
        if (KenderClientState.isEmpty()) return;

        LocalPlayer player = mc.player;
        if (player == null) return;
        
        // KOPER: if holding a wand, disable "attach block" targeting so wand's 'use' works
        var held = player.getMainHandItem();
        if (held.getItem().toString().contains("wand") || held.getItem().toString().contains("selection")) return;

        float vanillaDist = Float.MAX_VALUE;
        Vec3 rideOffset=KontraRideClient.renderRideOffset(player,1);
        Vec3 eye=player.getEyePosition().add(rideOffset);
        if(rideOffset.lengthSqr()>1.0e-12) {
            // Both static occlusion and hull selection must start in the rendered rider frame.
            com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(true);
            try {
                var real=player.level().clip(new net.minecraft.world.level.ClipContext(
                    eye,eye.add(player.getLookAngle().scale(5.0)),net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,player));
                if(real.getType()!=HitResult.Type.MISS) vanillaDist=(float)real.getLocation().distanceTo(eye);
            } finally {com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(false);}
        } else if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            // if vanilla is already seeing a physics block (via ClientLevelAccessMixin),
            // don't use its distance as the OBB upper limit — otherwise OBB hits the same
            // block at the same distance and gets rejected (t >= vanillaDist - 0.05)
            boolean vanillaHitsPhysBlock = mc.hitResult instanceof BlockHitResult bhr
                && (bhr.getBlockPos().equals(lastSetPos)
                    || KenderClientState.getClientBlockStateAt(bhr.getBlockPos()) != null);
            if (!vanillaHitsPhysBlock) {
                Vec3 loc = mc.hitResult.getLocation();
                double dx = loc.x - eye.x, dy = loc.y - eye.y, dz = loc.z - eye.z;
                vanillaDist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            } else {
                // vanilla is looking at OUR projection, so its distance is useless as a cap — but
                // dropping the cap entirely let the obb hit a kontra sitting BEHIND a real block.
                // the server has no such hole: there a real block always wins. that disagreement is
                // where ghost blocks come from — the client thinks it broke a kontra cell while the
                // server breaks the static block that was actually in the way, and never tells the
                // client about a block it believes it never touched.
                // so: ask the world again with the projection switched off and cap on that.
                com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(true);
                try {
                    Vec3 end = eye.add(player.getLookAngle().scale(5.0));
                    HitResult real = player.level().clip(new net.minecraft.world.level.ClipContext(
                        eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                    // only a real block in a DIFFERENT, nearer cell may veto the obb. when the
                    // real hit is the same cell we project into they are the same surface —
                    // a lowered lift plate shares its cell with the lift foot, and capping there
                    // stopped you aiming at the plate at all
                    if (real instanceof BlockHitResult realBlock
                            && real.getType() != HitResult.Type.MISS
                            && !(mc.hitResult instanceof BlockHitResult aimed
                                 && realBlock.getBlockPos().equals(aimed.getBlockPos())))
                        vanillaDist = (float) real.getLocation().distanceTo(eye);
                } finally {
                    com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(false);
                }
            }
        }

        Vec3  look = player.getLookAngle();
        float best = Math.min(5.0f, vanillaDist - 0.05f);
        PhysHit bestHit = null;
        long nowNs = System.nanoTime();

        for (KenderClientState.KontraRenderData k : KenderClientState.all()) {
            float[] cp = KenderClientState.renderPos(k, nowNs);
            float[] cr = KenderClientState.renderRot(k, nowNs);
            if (cp == null || cr == null) continue;
            SCRATCH_Q.set(cr[0], cr[1], cr[2], cr[3]);
            SCRATCH_QINV.set(cr[0], cr[1], cr[2], cr[3]).conjugate();

            int blockCount = k.offsets.length / 3;
            for (int i = 0; i < blockCount; i++) {
                SCRATCH_OFF.set(k.offsets[i*3], k.offsets[i*3+1], k.offsets[i*3+2]);
                SCRATCH_Q.transform(SCRATCH_OFF);
                double bx = cp[0] + SCRATCH_OFF.x, by = cp[1] + SCRATCH_OFF.y, bz = cp[2] + SCRATCH_OFF.z;

                // ray to local block space
                SCRATCH_LO.set((float)(eye.x - bx), (float)(eye.y - by), (float)(eye.z - bz));
                SCRATCH_LD.set((float)look.x, (float)look.y, (float)look.z);
                SCRATCH_QINV.transform(SCRATCH_LO);
                SCRATCH_QINV.transform(SCRATCH_LD);

                int lx = k.locals[i*3];
                int ly = k.locals[i*3 + 1];
                int lz = k.locals[i*3 + 2];
                if (clickThrough(k.states[i], player)) continue;
                var localPos = new net.minecraft.core.BlockPos(lx, ly, lz);
                var blockEntity = k.blockEntities.get(localPos);
                // a micro grid keeps its geometry in local data, not in the state — raycasting the
                // state shape hit an empty box, so the block could not be aimed at, broken or built on
                ShapeHit shapeHit = blockEntity instanceof com.koper.koper_lib.physics.KhysicsShapeProvider shape
                    ? microShapeTest(SCRATCH_LO, SCRATCH_LD, shape)
                    : voxelShapeTest(SCRATCH_LO, SCRATCH_LD,
                        com.koper.koper_lib.physics.shape.KhysShapeCache.voxel(
                            k.states[i], k.localData.get(localPos)));
                float t = shapeHit != null ? shapeHit.distance() : -1f;
                if (t < 0 || t >= best) continue;
                best = t;

                float hx = SCRATCH_LO.x + SCRATCH_LD.x * t;
                float hy = SCRATCH_LO.y + SCRATCH_LD.y * t;
                float hz = SCRATCH_LO.z + SCRATCH_LD.z * t;
                float[] localFace = shapeHit != null ? shapeHit.face() : dominantAxis(hx, hy, hz);

                // use raw float offsets so placed block renders at correct world position
                // (rounded integers shift by 0.5 on even-sized kontraktions where offsets are ±0.5)
                float newOx = k.offsets[i*3]     + localFace[0];
                float newOy = k.offsets[i*3 + 1] + localFace[1];
                float newOz = k.offsets[i*3 + 2] + localFace[2];
                bestHit = new PhysHit(k.id, i, newOx, newOy, newOz, t, lx, ly, lz, hx, hy, hz);
            }
        }

        current = bestHit;

        // override mc.hitResult so vanilla draws outline + crack stages + mining sound automatically
        // ClientLevelAccessMixin returns physics block state at worldBlockPos so everything just works
        lastSetPos = null;
        if (bestHit != null) {
            KenderClientState.KontraRenderData bestK = KenderClientState.getById(bestHit.kontraId());
            if (bestK != null) {
                int idx = bestHit.blockIndex();
                if (idx * 3 + 2 < bestK.offsets.length) {
                    float[] bp = KenderClientState.renderPos(bestK, nowNs);
                    float[] br = KenderClientState.renderRot(bestK, nowNs);
                    if (bp == null || br == null) return;
                    Quaternionf bq = new Quaternionf(br[0], br[1], br[2], br[3]);
                    // block world center
                    var wOff = new Vector3f(bestK.offsets[idx*3], bestK.offsets[idx*3+1], bestK.offsets[idx*3+2]);
                    bq.transform(wOff);
                    BlockPos wbp = new BlockPos(
                        (int) Math.floor(bp[0] + wOff.x),
                        (int) Math.floor(bp[1] + wOff.y),
                        (int) Math.floor(bp[2] + wOff.z)
                    );
                    // placement face in world space (rotate local dominant axis by kontra rot)
                    var fOff = new Vector3f(
                        bestHit.newOffX() - bestK.offsets[idx*3],
                        bestHit.newOffY() - bestK.offsets[idx*3+1],
                        bestHit.newOffZ() - bestK.offsets[idx*3+2]
                    );
                    bq.transform(fOff);
                    Direction face = Direction.getNearest(new net.minecraft.core.Vec3i(
                        Math.round(fOff.x), Math.round(fOff.y), Math.round(fOff.z)), Direction.UP);
                    mc.hitResult = new BlockHitResult(eye.add(look.scale(bestHit.dist())), face, wbp, false);
                    lastSetPos = wbp;
                }
            }
        }
    }

    // Kay-Kajiya slab test vs unit cube [-0.5, 0.5]^3 centered at origin
    private static float slabTest(Vector3f o, Vector3f d) {
        return slabTest(o, d, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);
    }

    private static float slabTest(Vector3f o, Vector3f d,
                                  float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        float invX = d.x == 0f ? 1e30f : 1f / d.x;
        float invY = d.y == 0f ? 1e30f : 1f / d.y;
        float invZ = d.z == 0f ? 1e30f : 1f / d.z;
        float t1 = (minX - o.x) * invX, t2 = (maxX - o.x) * invX;
        float t3 = (minY - o.y) * invY, t4 = (maxY - o.y) * invY;
        float t5 = (minZ - o.z) * invZ, t6 = (maxZ - o.z) * invZ;
        float tmin = Math.max(Math.max(Math.min(t1,t2), Math.min(t3,t4)), Math.min(t5,t6));
        float tmax = Math.min(Math.min(Math.max(t1,t2), Math.max(t3,t4)), Math.max(t5,t6));
        if (tmax < 0 || tmin > tmax) return -1f;
        return tmin < 0f ? tmax : tmin;
    }

    private record ShapeHit(float distance, float[] face) {}

    private static ShapeHit voxelShapeTest(Vector3f origin, Vector3f direction,
                                           net.minecraft.world.phys.shapes.VoxelShape shape) {
        if (shape == null || shape.isEmpty()) return null;
        float best = Float.POSITIVE_INFINITY;
        float[] face = null;
        for (net.minecraft.world.phys.AABB box : shape.toAabbs()) {
            float minX = (float)box.minX - 0.5f;
            float minY = (float)box.minY - 0.5f;
            float minZ = (float)box.minZ - 0.5f;
            float maxX = (float)box.maxX - 0.5f;
            float maxY = (float)box.maxY - 0.5f;
            float maxZ = (float)box.maxZ - 0.5f;
            float distance = slabTest(origin, direction, minX, minY, minZ, maxX, maxY, maxZ);
            if (distance < 0 || distance >= best) continue;
            float hx = origin.x + direction.x * distance;
            float hy = origin.y + direction.y * distance;
            float hz = origin.z + direction.z * distance;
            face = boxFace(hx, hy, hz, minX, minY, minZ, maxX, maxY, maxZ);
            best = distance;
        }
        return face == null ? null : new ShapeHit(best, face);
    }

    private static ShapeHit microShapeTest(Vector3f origin, Vector3f direction,
                                           com.koper.koper_lib.physics.KhysicsShapeProvider shape) {
        int resolution = shape.khysicsResolution();
        long cells = shape.khysicsCells();
        if (resolution < 1 || resolution > 4 || cells == 0) return null;
        float best = Float.POSITIVE_INFINITY;
        float[] face = null;
        for (int y = 0; y < resolution; y++) {
            for (int z = 0; z < resolution; z++) {
                for (int x = 0; x < resolution; x++) {
                    int index = x + resolution * (z + resolution * y);
                    if ((cells & 1L << index) == 0) continue;
                    float minX = x / (float)resolution - 0.5f;
                    float minY = y / (float)resolution - 0.5f;
                    float minZ = z / (float)resolution - 0.5f;
                    float maxX = (x + 1) / (float)resolution - 0.5f;
                    float maxY = (y + 1) / (float)resolution - 0.5f;
                    float maxZ = (z + 1) / (float)resolution - 0.5f;
                    float distance = slabTest(origin, direction, minX, minY, minZ, maxX, maxY, maxZ);
                    if (distance < 0 || distance >= best) continue;
                    float hx = origin.x + direction.x * distance;
                    float hy = origin.y + direction.y * distance;
                    float hz = origin.z + direction.z * distance;
                    face = boxFace(hx, hy, hz, minX, minY, minZ, maxX, maxY, maxZ);
                    best = distance;
                }
            }
        }
        return face == null ? null : new ShapeHit(best, face);
    }

    private static float[] boxFace(float x, float y, float z,
                                   float minX, float minY, float minZ,
                                   float maxX, float maxY, float maxZ) {
        float dx = Math.min(Math.abs(x - minX), Math.abs(x - maxX));
        float dy = Math.min(Math.abs(y - minY), Math.abs(y - maxY));
        float dz = Math.min(Math.abs(z - minZ), Math.abs(z - maxZ));
        if (dx <= dy && dx <= dz)
            return new float[]{x < (minX + maxX) * 0.5f ? -1f : 1f, 0f, 0f};
        if (dy <= dz)
            return new float[]{0f, y < (minY + maxY) * 0.5f ? -1f : 1f, 0f};
        return new float[]{0f, 0f, z < (minZ + maxZ) * 0.5f ? -1f : 1f};
    }

    // returns local-space face normal based on which surface component is largest
    private static float[] dominantAxis(float hx, float hy, float hz) {
        float ax = Math.abs(hx), ay = Math.abs(hy), az = Math.abs(hz);
        if (ax >= ay && ax >= az) return new float[]{ Math.signum(hx), 0f, 0f };
        if (ay >= az)             return new float[]{ 0f, Math.signum(hy), 0f };
        return new float[]{ 0f, 0f, Math.signum(hz) };
    }
}
