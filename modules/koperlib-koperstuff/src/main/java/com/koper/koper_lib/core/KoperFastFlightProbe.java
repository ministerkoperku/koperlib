package com.koper.koper_lib.core;

import com.koper.koper_lib.api.core.KoperCommands;
import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.network.KontraMotionServer;
import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.*;
import com.koper.koper_lib.physics.dim.KhysDimensions;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Explicit developer scene. Run only in a disposable world. */
public final class KoperFastFlightProbe {
    private KoperFastFlightProbe() {}
    private static Scene scene;
    private static final class Scene {
        final MinecraftServer server;
        long root,part;
        UUID standing,seated;
        int ticks;
        int missingTicks,absentStreak;
        float speed;
        boolean started;
        final Map<ChunkTicket,Integer> chunks=new HashMap<>();
        double maxError;
        boolean failed;
        Scene(MinecraftServer server) {this.server=server;}
    }
    private record ChunkTicket(ServerLevel level,int x,int z) {}
    public static void register() {
        KoperCommands.register("fast-flight",root -> root.then(commands("flight")));
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment) ->
            dispatcher.register(commands("koper-flight").requires(source -> !source.isPlayer())));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            var s=scene;if(s==null || s.server!=server)return;
            var p=KoperPhys.getCachedPos(s.root);var e=KoperPhys.all().get(s.root);
            if(p==null || e==null)return;
            var level=KoperPhys.levelFor(e);
            int x=((int)Math.floor(p[0]))>>4,z=((int)Math.floor(p[2]))>>4;
            force(s,level,x,z);
            for(var uuid:List.of(s.standing,s.seated)) {
                var rider=entity(s,uuid);if(rider!=null) force(s,(ServerLevel)rider.level(),rider.chunkPosition().x(),rider.chunkPosition().z());
            }
            for(var old:List.copyOf(s.chunks.keySet())) if(server.getTickCount()-s.chunks.get(old)>10) {
                old.level.setChunkForced(old.x,old.z,false);s.chunks.remove(old);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(KoperFastFlightProbe::tick);
    }
    private static void force(Scene s,ServerLevel level,int x,int z) {
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            var key=new ChunkTicket(level,x+dx,z+dz);
            if(!s.chunks.containsKey(key)) {level.setChunkForced(key.x,key.z,true);level.getChunk(key.x,key.z);}
            s.chunks.put(key,s.server.getTickCount());
        }
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> commands(String name) {
        return Commands.literal(name)
            .then(Commands.literal("probe").then(Commands.argument("speed",FloatArgumentType.floatArg(0,10000))
                .executes(c -> start(c.getSource(),FloatArgumentType.getFloat(c,"speed")))))
            .then(Commands.literal("transfer").executes(c -> transfer(c.getSource())))
            .then(Commands.literal("refuse").executes(c -> refuse(c.getSource())))
            .then(Commands.literal("resume").executes(c -> resume(c.getSource())))
            .then(Commands.literal("check").executes(c -> check(c.getSource())))
            .then(Commands.literal("board").executes(c -> board(c.getSource())))
            .then(Commands.literal("stop").executes(c -> stop(c.getSource())));
    }
    private static void message(CommandSourceStack source,String text) {
        source.sendSystemMessage(Component.literal("[FlightProbe] "+text));
    }
    private static int start(CommandSourceStack source,float speed) {
        try {return startScene(source,speed);}
        catch(RuntimeException failure) {KoperCore.LOGGER.error("[FlightProbe] scene startup failed",failure);return 0;}
    }
    private static int startScene(CommandSourceStack source,float speed) {
        if(scene!=null) {message(source,"Stop the previous scene first.");return 0;}
        var level=source.getLevel();
        var policy=KhysDimensions.getFor(level.dimension().identifier().toString()).flight();
        if(!policy.fastFlight() || !policy.allows(new Vec3(8,1000,8),new Vec3(speed,0,0))) {
            message(source,"Requires an opted-in flight dimension allowing y=1000 and the requested speed.");return 0;
        }
        level.setChunkForced(0,0,true); level.getChunk(0,0);
        var s=new Scene(source.getServer());s.speed=speed;
        var states=new ArrayList<BlockState>();var offsets=new ArrayList<Float>();
        for(int x=-2;x<=2;x++) for(int y=0;y<=4;y++) for(int z=-2;z<=2;z++) {
            if(y!=0 && y!=4 && Math.abs(x)!=2 && Math.abs(z)!=2) continue;
            states.add(Blocks.IRON_BLOCK.defaultBlockState());offsets.add((float)x);offsets.add((float)y);offsets.add((float)z);
        }
        states.add(Blocks.CHEST.defaultBlockState());offsets.add(1f);offsets.add(1f);offsets.add(1f);
        float[] flat=new float[offsets.size()];for(int i=0;i<flat.length;i++) flat[i]=offsets.get(i);
        var tags=new net.minecraft.nbt.CompoundTag[states.size()];
        var initialCargo=new ChestBlockEntity(BlockPos.ZERO,Blocks.CHEST.defaultBlockState());
        initialCargo.setItem(0,new ItemStack(Items.DIAMOND,3));
        tags[tags.length-1]=initialCargo.saveWithFullMetadata(level.registryAccess());
        s.root=KoperPhys.spawnBody(level,states,flat,tags,8,1000,8,new float[]{0,0,0,1});
        s.part=KoperPhys.spawnBody(level,List.of(Blocks.IRON_BLOCK.defaultBlockState()),new float[]{0,0,0},13,1000,8,new float[]{0,0,0,1});
        if(s.root<=0 || s.part<=0 || KoperPhys.createRevoluteJoint(s.root,s.part,5,0,0,0,0,0,0,1,0)<=0) {
            for(long id:new long[]{s.root,s.part}) if(id>0) KoperPhys.destroyKontraktion(s.server,id);
            message(source,"Assembly creation failed.");return 0;
        }
        KoperPhys.setBodyParked(s.root,true); KoperPhys.setBodyParked(s.part,true);
        var e=KoperPhys.all().get(s.root);
        e.stash.putString("flight_probe","cargo-preserved");
        e.setAeroMode(AeroMode.CORRECT);
        var chest=e.blockEntities.get(new BlockPos(1,1,1));
        if(chest instanceof ChestBlockEntity cargo) cargo.setItem(0,new ItemStack(Items.DIAMOND,3));
        e.grid(s.root).schedule(e.grid(s.root).toGrid(BlockPos.ZERO),Blocks.IRON_BLOCK,100000);
        Pig standing=EntityTypes.PIG.create(level,EntitySpawnReason.COMMAND);
        Pig seated=EntityTypes.PIG.create(level,EntitySpawnReason.COMMAND);
        if(standing==null || seated==null) {message(source,"Could not create occupants.");return 0;}
        standing.removeFreeWill();standing.setPos(8,1000.5,8);standing.setOnGround(true);
        seated.removeFreeWill();seated.setPos(7,1000.5,8);
        boolean addedStanding=level.addFreshEntity(standing),addedSeated=level.addFreshEntity(seated);
        KoperCore.LOGGER.info("[FlightProbe] crew creation standing={} seated={} ids={} {}",addedStanding,addedSeated,standing.getUUID(),seated.getUUID());
        s.standing=standing.getUUID();s.seated=seated.getUUID();
        var mind=KontraGlue.mind(standing);mind.deckId=s.root;mind.basePos=new float[]{8,1000,8};mind.baseRot=new float[]{0,0,0,1};
        var seat=KoperPhys.spawnSeat(level,s.root,-1,.5f,0);
        if(seat!=null) seated.startRiding(seat,true,false);
        s.chunks.put(new ChunkTicket(level,0,0),s.server.getTickCount());
        scene=s;message(source,"Started speed="+speed+" root="+s.root+" part="+s.part+" at=(8,1000,8). Use board for player trials.");return 1;
    }
    private static Entity entity(Scene s,UUID id) {
        for(var level:s.server.getAllLevels()) {var e=level.getEntity(id);if(e!=null)return e;}
        return KontraMotionServer.tracked(id);
    }
    private static void tick(MinecraftServer server) {
        var s=scene;if(s==null || s.server!=server) return;
        var frame=KontraMotionServer.frame(s.root);var rider=entity(s,s.standing);
        s.ticks++;
        if(frame==null) {s.failed=true;return;}
        if(rider==null) {
            s.missingTicks++;s.absentStreak++;
            if(s.absentStreak>20) {
                if(!s.failed) KoperCore.LOGGER.error("[FlightProbe] occupant unavailable for {} consecutive ticks",s.absentStreak);
                s.failed=true;
            }
            return;
        }
        s.absentStreak=0;
        if(!s.started && s.ticks>=20) {
            s.started=true;
            for(long id:List.of(s.root,s.part)) {KoperPhys.setBodyParked(id,false);KoperPhys.setBodyVelocity(id,s.speed,0,0);}
        }
        var local=frame.toLocal(rider.position());
        double error=Math.abs(local.x)+Math.abs(local.z)+Math.abs(local.y-.5);
        s.maxError=Math.max(s.maxError,error);
        if(KontraGlue.mind(rider).deckId!=s.root || error>1) s.failed=true;
        if(s.ticks%20==0) KoperCore.LOGGER.info("[FlightProbe] tick={} root={} world={} local={} maxError={} failed={}",s.ticks,s.root,rider.position(),local,s.maxError,s.failed);
    }
    private static final class RefusingPig extends Pig {
        RefusingPig(ServerLevel level) {super(EntityTypes.PIG,level);}
        @Override public Entity teleport(net.minecraft.world.level.portal.TeleportTransition destination) {return null;}
    }
    private static int refuse(CommandSourceStack source) {
        var s=scene;if(s==null)return 0;
        var old=entity(s,s.seated); if(old==null || !(old.getVehicle() instanceof KontraSeat seat)) return 0;
        var level=(ServerLevel)old.level(); var replacement=new RefusingPig(level);
        replacement.removeFreeWill();replacement.setPos(old.position());level.addFreshEntity(replacement);
        old.discard();replacement.startRiding(seat,true,false);s.seated=replacement.getUUID();
        var target=s.server.getLevel(Level.END);force(s,target,0,0);
        var before=Set.copyOf(KoperPhys.all().keySet());
        var result=KoperPhys.transferAssembly(s.root,target,new float[]{8,1000,8},1);
        boolean ok=result==null && before.equals(KoperPhys.all().keySet());
        message(source,"refusal pass="+ok+" inventory owners="+KoperPhys.all().size());
        check(source);
        // Replace the refusal entity with an ordinary occupant for the next successful transfer.
        replacement.stopRiding();var normal=EntityTypes.PIG.create(level,EntitySpawnReason.COMMAND);
        if(normal!=null) {normal.removeFreeWill();normal.setPos(replacement.position());level.addFreshEntity(normal);normal.startRiding(seat,true,false);s.seated=normal.getUUID();}
        replacement.discard();return ok?1:0;
    }
    private static int resume(CommandSourceStack source) {
        if(scene!=null)return 0;var s=new Scene(source.getServer());
        for(var entry:KoperPhys.all().entrySet()) {
            if(entry.getValue().stash.getString("flight_probe").orElse("").equals("cargo-preserved")) {
                if(s.root!=0) {message(source,"Duplicate saved cargo owners.");return 0;}s.root=entry.getKey();
            }
        }
        if(s.root==0)return 0;
        var group=KoperPhys.assemblyOf(s.root);if(group.size()!=2) {message(source,"Restored joint group differs: "+group);return 0;}
        s.part=group.get(1);var e=KoperPhys.all().get(s.root);var level=KoperPhys.levelFor(e);
        var cargo=e.blockEntities.get(new BlockPos(1,1,1));
        boolean inventory=cargo instanceof ChestBlockEntity chest && chest.getItem(0).getCount()==3 && chest.getItem(0).is(Items.DIAMOND);
        boolean ticks=!e.grid(s.root).savedTicks().isEmpty();
        message(source,"reload pass="+(inventory && ticks && level.dimension().equals(Level.END))+" root="+s.root+" bodies="+group+" inventory="+inventory+" ticks="+ticks+" dimension="+level.dimension().identifier());
        return inventory && ticks?1:0;
    }
    private static int transfer(CommandSourceStack source) {
        var s=scene;if(s==null)return 0;
        var target=s.server.getLevel(Level.END);if(target==null)return 0;
        force(s,target,0,0);
        long old=s.root;var result=KoperPhys.transferAssembly(old,target,new float[]{8,1000,8},1);
        if(result==null) {message(source,"Transfer refused; source="+old+" still exists="+KoperPhys.all().containsKey(old));return 0;}
        s.root=result.root();s.part=result.bodies().get(s.part);s.ticks=0;s.failed=false;s.maxError=0;s.missingTicks=0;s.absentStreak=0;
        message(source,"Transferred "+result.bodies()+" old source exists="+KoperPhys.all().containsKey(old)+"; check after occupant registration.");return 1;
    }
    private static int check(CommandSourceStack source) {
        var s=scene;if(s==null)return 0;
        var e=KoperPhys.all().get(s.root);if(e==null)return 0;
        var standing=entity(s,s.standing);var seated=entity(s,s.seated);
        var cargo=e.blockEntities.get(new BlockPos(1,1,1));
        boolean inventory=cargo instanceof ChestBlockEntity chest && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount()==3;
        boolean stash=e.stash.getString("flight_probe").orElse("").equals("cargo-preserved");
        boolean ticks=!e.grid(s.root).savedTicks().isEmpty();
        boolean crew=standing!=null && seated!=null && standing.level()==KoperPhys.levelFor(e) && seated.level()==standing.level()
            && KontraGlue.mind(standing).deckId==s.root && seated.getVehicle() instanceof KontraSeat seat && seat.kontraId()==s.root;
        boolean joints=KoperPhys.assemblyOf(s.root).contains(s.part);
        boolean ok=!s.failed && inventory && stash && ticks && crew && joints;
        message(source,"pass="+ok+" maxLocalError="+s.maxError+" temporarilyUnavailableTicks="+s.missingTicks+" inventory="+inventory+" stash="+stash+" ticks="+ticks+" crew="+crew+" joints="+joints+" dimension="+KoperPhys.levelFor(e).dimension().identifier());
        return ok?1:0;
    }
    private static int board(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var s=scene;if(s==null)return 0;var p=source.getPlayerOrException();var f=KontraMotionServer.frame(s.root);
        var e=KoperPhys.all().get(s.root);if(f==null || e==null)return 0;
        Vec3 at=f.toWorld(new Vec3(0,.5,0));
        p.teleportTo(KoperPhys.levelFor(e),at.x,at.y,at.z,Set.of(),0,0,true);
        var m=KontraGlue.mind(p);m.deckId=s.root;m.basePos=null;m.baseRot=null;
        return 1;
    }
    private static int stop(CommandSourceStack source) {
        var s=scene;
        if(s==null) {
            int count=0;
            for(var entry:List.copyOf(KoperPhys.all().entrySet())) if(entry.getValue().stash.getString("flight_probe").orElse("").equals("cargo-preserved")) {
                for(long id:KoperPhys.assemblyOf(entry.getKey())) {KoperPhys.destroyKontraktion(source.getServer(),id);count++;}
            }
            message(source,"Removed "+count+" saved probe bodies.");return count>0?1:0;
        }
        for(UUID uuid:List.of(s.standing,s.seated)) {var e=entity(s,uuid);if(e!=null)e.discard();}
        for(long id:KoperPhys.assemblyOf(s.root)) KoperPhys.destroyKontraktion(s.server,id);
        for(var chunk:s.chunks.keySet()) chunk.level.setChunkForced(chunk.x,chunk.z,false);
        scene=null;message(source,"Scene removed.");return 1;
    }
}
