import { world, system, ItemStack, GameMode, CustomCommandPermissionLevel, CustomCommandStatus } from "@minecraft/server";
import { ActionFormData, ModalFormData } from "@minecraft/server-ui";
import { stars } from "./lib/pretty";

// written for the koperlib bedrock layer test, uses the stable 2.x api surface
system.beforeEvents.startup.subscribe((ev) => {
  ev.itemComponentRegistry.registerCustomComponent("kbt:zap", {
    onUse({ source }, { params }) {
      source.sendMessage(stars(`zap for ${params.power}`));
      const hit = source.getEntitiesFromViewDirection({ maxDistance: 16 })[0];
      if (hit) hit.entity.applyDamage(params.power, { cause: "magic", damagingEntity: source });
      source.dimension.spawnParticle("minecraft:villager_happy", source.location);
    },
  });
  ev.itemComponentRegistry.registerCustomComponent("kbt:yum", {
    onConsume({ source }) { source.addEffect("regeneration", 100, { amplifier: 1 }); },
  });
  ev.blockComponentRegistry.registerCustomComponent("kbt:crate", {
    onPlayerInteract({ block, player }) {
      const n = (world.getDynamicProperty("crates_opened") ?? 0) + 1;
      world.setDynamicProperty("crates_opened", n);
      player?.onScreenDisplay.setActionBar(`crate opened ${n} times`);
    },
    onTick({ block, dimension }) { dimension.spawnParticle("minecraft:basic_flame_particle", block.center()); },
  });
  ev.customCommandRegistry.registerCommand(
    { name: "kbt:menu", description: "open the koper menu", permissionLevel: CustomCommandPermissionLevel.Any },
    (origin) => {
      const p = origin.sourceEntity;
      if (!p) return { status: CustomCommandStatus.Failure, message: "players only" };
      system.run(() => menu(p));
      return { status: CustomCommandStatus.Success };
    });
});

function menu(player) {
  new ActionFormData().title("Koper menu").body("pick one").button("heal").button("rename me").show(player).then((r) => {
    if (r.canceled) return;
    if (r.selection === 0) player.getComponent("minecraft:health").resetToMaxValue();
    if (r.selection === 1) new ModalFormData().title("name").textField("new name", "koper").toggle("shout", { defaultValue: false }).show(player)
      .then((m) => { if (!m.canceled) player.nameTag = m.formValues[1] ? m.formValues[0].toUpperCase() : m.formValues[0]; });
  });
}

world.afterEvents.playerSpawn.subscribe(({ player, initialSpawn }) => {
  if (!initialSpawn) return;
  player.sendMessage({ rawtext: [{ text: "§ahello " }, { text: player.name }] });
  const inv = player.getComponent("minecraft:inventory").container;
  if (!player.getDynamicProperty("got_wand")) {
    inv.addItem(new ItemStack("kbt:zap_wand"));
    player.setDynamicProperty("got_wand", true);
  }
  let obj = world.scoreboard.getObjective("kbt_kills") ?? world.scoreboard.addObjective("kbt_kills", "Bug kills");
  obj.setScore(player, obj.getScore(player) ?? 0);
});

world.beforeEvents.chatSend.subscribe((ev) => {
  if (ev.message.startsWith("!bug")) {
    ev.cancel = true;
    system.run(() => ev.sender.dimension.spawnEntity("kbt:koper_bug", ev.sender.location));
  }
});

world.afterEvents.entityDie.subscribe(({ deadEntity, damageSource }) => {
  if (deadEntity.typeId !== "kbt:koper_bug") return;
  const killer = damageSource.damagingEntity;
  if (killer?.typeId === "minecraft:player") world.scoreboard.getObjective("kbt_kills")?.addScore(killer, 1);
});

system.afterEvents.scriptEventReceive.subscribe(({ id, message, sourceEntity }) => {
  if (id === "kbt:ping") world.sendMessage(`pong ${message} from ${sourceEntity?.typeId ?? "server"}`);
});

system.runInterval(() => {
  for (const p of world.getAllPlayers()) {
    if (p.getGameMode() === GameMode.Creative) continue;
    const held = p.getComponent("minecraft:equippable")?.getEquipment("Mainhand");
    if (held?.typeId === "kbt:zap_wand") p.onScreenDisplay.setActionBar("§eright click to zap");
  }
}, 40);
