// @minecraft/server, koper edition. written from the public docs shape of the api, not from
// anyone's code. every class here is a thin face over __koper.ask(...) which lands in
// BedrockPytajnik on the java side. if a script calls something we never built you get a
// loud error naming it, not a silent undefined
const K = globalThis.__koper;

// ── small stuff ──────────────────────────────────────────────────────────────

export const TicksPerSecond = 20;
export const TicksPerDay = 24000;
export const HudElementsCount = 13;
export const MoonPhaseCount = 8;

const nope = (what) => { throw new Error("koperlib bedrock: " + what + " is not implemented yet"); };
const vec = (o) => ({ x: +o.x, y: +o.y, z: +o.z });
const blockVec = (o) => ({ x: Math.floor(o.x), y: Math.floor(o.y), z: Math.floor(o.z) });

// entity snapshots are cached for one tick. any write throws the whole cache away, so a
// script that does tp then reads location gets the new spot
let snapTick = -1;
const snaps = new Map();
const dirty = () => { snaps.clear(); };
const write = (op, args) => { dirty(); return K.ask(op, args); };
// a snapshot holds for one call from java only: a mob that died between the tick and an event in the same
// tick still read as alive, isValid said true and the next call threw "entity is not valid any more"
K.enter = dirty;

function dimId(d) {
  if (d === undefined || d === null) return "minecraft:overworld";
  if (typeof d === "string") return d.includes(":") ? d : "minecraft:" + d;
  return d.id;
}

// RawMessage -> vanilla text component json. strings pass through as plain text
function rawToComponent(msg) {
  if (msg === undefined || msg === null) return { text: "" };
  if (typeof msg === "string") return { text: msg };
  if (Array.isArray(msg)) return { text: "", extra: msg.map(rawToComponent) };
  if (msg.rawtext) return { text: "", extra: msg.rawtext.map(rawToComponent) };
  if (msg.text !== undefined) return { text: String(msg.text) };
  if (msg.translate !== undefined) {
    const out = { translate: msg.translate };
    if (msg.with) out.with = Array.isArray(msg.with) ? msg.with.map(String) : rawToComponent(msg.with).extra || [];
    return out;
  }
  if (msg.score) return { score: { name: msg.score.name, objective: msg.score.objective } };
  if (msg.selector) return { selector: msg.selector };
  return { text: String(msg) };
}

// ── enums ────────────────────────────────────────────────────────────────────

// enums stay open until the generated tail (server_api.js) filled in the members the api has and
// we did not write, then all of them get frozen
const ENUMS = [];
const enumOf = (pairs) => { const e = Object.assign(Object.create(null), pairs); ENUMS.push(e); return e; };
const __koperFill = (e, more) => { for (const k in more) if (!(k in e)) e[k] = more[k]; };
const __koperSeal = () => { for (const e of ENUMS) Object.freeze(e); };
// an api method we have not built: calling it names itself instead of "not a function"
const __koperStaticStub = (cls, m) => { Object.defineProperty(cls, m, { value() { nope(cls.name + "." + m); }, writable: true, configurable: true }); };
const __koperStub = (cls, m) => { Object.defineProperty(cls.prototype, m, { value() { nope(cls.name + "." + m); }, writable: true, configurable: true }); };

export const GameMode = enumOf({ Adventure: "Adventure", Creative: "Creative", Spectator: "Spectator", Survival: "Survival",
  adventure: "adventure", creative: "creative", spectator: "spectator", survival: "survival" });
export const EquipmentSlot = enumOf({ Chest: "Chest", Feet: "Feet", Head: "Head", Legs: "Legs", Mainhand: "Mainhand", Offhand: "Offhand", Body: "Body" });
export const Direction = enumOf({ Down: "Down", East: "East", North: "North", South: "South", Up: "Up", West: "West" });
export const ItemLockMode = enumOf({ inventory: "inventory", none: "none", slot: "slot" });
export const DisplaySlotId = enumOf({ BelowName: "BelowName", List: "List", Sidebar: "Sidebar" });
export const ObjectiveSortOrder = enumOf({ Ascending: 0, Descending: 1 });
export const ScoreboardIdentityType = enumOf({ Entity: "Entity", FakePlayer: "FakePlayer", Player: "Player" });
export const WeatherType = enumOf({ Clear: "Clear", Rain: "Rain", Thunder: "Thunder" });
export const TimeOfDay = enumOf({ Day: 1000, Noon: 6000, Sunset: 12000, Night: 13000, Midnight: 18000, Sunrise: 23000 });
export const Difficulty = enumOf({ Peaceful: "Peaceful", Easy: "Easy", Normal: "Normal", Hard: "Hard" });
export const ScriptEventSource = enumOf({ Block: "Block", Entity: "Entity", NPCDialogue: "NPCDialogue", Server: "Server" });
export const EntityInitializationCause = enumOf({ Born: "Born", Event: "Event", Loaded: "Loaded", Spawned: "Spawned", Transformed: "Transformed" });
export const EntityDamageCause = enumOf(Object.fromEntries(["anvil", "blockExplosion", "campfire", "charging", "contact", "drowning",
  "entityAttack", "entityExplosion", "fall", "fallingBlock", "fire", "fireTick", "fireworks", "flyIntoWall", "freezing", "lava",
  "lightning", "maceSmash", "magic", "magma", "none", "override", "piston", "projectile", "ramAttack", "selfDestruct", "sonicBoom",
  "soulCampfire", "stalactite", "stalagmite", "starve", "suffocation", "suicide", "temperature", "thorns", "void", "wither"].map((n) => [n, n])));
export const CustomCommandPermissionLevel = enumOf({ Any: 0, GameDirectors: 1, Admin: 2, Host: 3, Owner: 4 });
export const CommandPermissionLevel = CustomCommandPermissionLevel;
export const CustomCommandParamType = enumOf({ Boolean: "Boolean", Integer: "Integer", Float: "Float", String: "String",
  EntitySelector: "EntitySelector", PlayerSelector: "PlayerSelector", Location: "Location", BlockType: "BlockType",
  ItemType: "ItemType", Enum: "Enum", EntityType: "EntityType" });
export const CustomCommandStatus = enumOf({ Success: 0, Failure: 1 });
export const CustomCommandSource = enumOf({ Block: "Block", Entity: "Entity", NPCDialogue: "NPCDialogue", Server: "Server" });
export const InputPermissionCategory = enumOf({ Camera: 1, Movement: 2 });
export const MoonPhase = enumOf({ FullMoon: 0, WaningGibbous: 1, FirstQuarter: 2, WaningCrescent: 3, NewMoon: 4, WaxingCrescent: 5, LastQuarter: 6, WaxingGibbous: 7 });
export const EntityComponentTypes = enumOf({ Health: "minecraft:health", Inventory: "minecraft:inventory", Equippable: "minecraft:equippable",
  Movement: "minecraft:movement", TypeFamily: "minecraft:type_family", Item: "minecraft:item", OnFire: "minecraft:onfire",
  IsBaby: "minecraft:is_baby", Scale: "minecraft:scale", Variant: "minecraft:variant", Tameable: "minecraft:tameable",
  Rideable: "minecraft:rideable", Riding: "minecraft:riding", Projectile: "minecraft:projectile" });
export const ItemComponentTypes = enumOf({ Durability: "minecraft:durability", Enchantable: "minecraft:enchantable",
  Cooldown: "minecraft:cooldown", Food: "minecraft:food", Dyeable: "minecraft:dyeable" });
export const BlockComponentTypes = enumOf({ Inventory: "minecraft:inventory", Sign: "minecraft:sign" });
export const SignSide = enumOf({ Back: "Back", Front: "Front" });

// ── errors people catch by name ─────────────────────────────────────────────

export class InvalidEntityError extends Error { constructor(m) { super(m); this.name = "InvalidEntityError"; } }
export class LocationOutOfWorldBoundariesError extends Error { constructor(m) { super(m); this.name = "LocationOutOfWorldBoundariesError"; } }
export class LocationInUnloadedChunkError extends Error { constructor(m) { super(m); this.name = "LocationInUnloadedChunkError"; } }
export class CommandError extends Error { constructor(m) { super(m); this.name = "CommandError"; } }
export class InvalidContainerSlotError extends Error { constructor(m) { super(m); this.name = "InvalidContainerSlotError"; } }

// ── event plumbing ──────────────────────────────────────────────────────────

// java only builds payloads for events somebody listens to, so the first subscribe tells it
class KoperSignal {
  constructor(name, before) { this._n = name; this._b = before; this._ears = []; }
  subscribe(cb, options) {
    if (typeof cb !== "function") throw new TypeError(this._n + ".subscribe wants a function");
    if (this._ears.length === 0) K.ask("sub", { n: this._n, b: this._b, on: true });
    this._ears.push({ cb, options });
    return cb;
  }
  unsubscribe(cb) {
    this._ears = this._ears.filter((e) => e.cb !== cb);
    if (this._ears.length === 0) K.ask("sub", { n: this._n, b: this._b, on: false });
  }
  _fire(data) {
    for (const ear of this._ears.slice()) {
      if (ear.options && !passesFilter(ear.options, data)) continue;
      try { ear.cb(data); } catch (e) { console.error("[" + this._n + "] handler threw", e); }
    }
  }
}

function passesFilter(o, data) {
  const ent = data.entity || data.deadEntity || data.hurtEntity || data.damagingEntity || data.source || data.player || data.sourceEntity;
  if (o.namespaces && data.id !== undefined) {
    const ns = String(data.id).split(":")[0];
    if (!o.namespaces.includes(ns)) return false;
  }
  if (o.entityTypes && ent && !o.entityTypes.includes(ent.typeId)) return false;
  if (o.entities && ent && !o.entities.some((e) => e.id === ent.id)) return false;
  if (o.itemTypes && data.itemStack && !o.itemTypes.includes(data.itemStack.typeId)) return false;
  if (o.blockTypes && data.block && !o.blockTypes.includes(data.block.typeId)) return false;
  // EntityDataDrivenTriggerEventOptions: without this a grow_up handler ran on every mob event
  if (o.eventTypes && data.eventId !== undefined && !o.eventTypes.includes(data.eventId)) return false;
  if (o.entityFilter && ent && !ent.matches(o.entityFilter)) return false;
  if (o.playerFilter && data.player && !data.player.matches(o.playerFilter)) return false;
  if (o.allowedDamageCauses && data.damageSource && !o.allowedDamageCauses.includes(data.damageSource.cause)) return false;
  if (o.allowedHealCauses && data.healSource && !o.allowedHealCauses.includes(data.healSource.cause)) return false;
  if (o.blockFilter && data.block && !blockPasses(data.block, o.blockFilter)) return false;
  if (o.permutations && data.block && !o.permutations.some((p) => data.block.permutation.matches(p.type ? p.type.id : p.typeId, p.getAllStates ? p.getAllStates() : undefined))) return false;
  if (o.itemFilter) {
    // pickup before hands an item ENTITY, pickup after / drop hand stacks or item entities
    const stacks = [];
    for (const x of [data.item, ...(data.items || [])]) {
      if (!x) continue;
      if (x.typeId === "minecraft:item" && x.getComponent) { const c = x.getComponent("minecraft:item"); if (c && c.itemStack) stacks.push(c.itemStack); }
      else stacks.push(x);
    }
    if (!stacks.some((st) => itemPasses(st, o.itemFilter))) return false;
  }
  if (o.heldItemOption && data.heldItemStack !== undefined && (o.heldItemOption === "NoItem") !== !data.heldItemStack) return false;
  if (o.swingSource && data.swingSource !== undefined && o.swingSource !== data.swingSource) return false;
  // InventoryItemEventOptions (playerInventoryItemChange)
  if (data.slot !== undefined && data.inventoryType !== undefined) {
    const it = data.itemStack || data.beforeItemStack;
    const id = (t) => (String(t).includes(":") ? String(t) : "minecraft:" + t);
    if (o.allowedSlots && !o.allowedSlots.includes(data.slot)) return false;
    if (o.inventoryType && o.inventoryType !== data.inventoryType) return false;
    if (o.includeItems && (!it || !o.includeItems.map(id).includes(it.typeId))) return false;
    if (o.excludeItems && it && o.excludeItems.map(id).includes(it.typeId)) return false;
    if (o.includeTags && (!it || !o.includeTags.some((t) => it.hasTag(t)))) return false;
    if (o.excludeTags && it && o.excludeTags.some((t) => it.hasTag(t))) return false;
    if (o.ignoreQuantityChange && data.itemStack && data.beforeItemStack
      && data.itemStack.typeId === data.beforeItemStack.typeId) return false;
  }
  return true;
}

// a name we never heard of still subscribes fine (it just never fires until java sends it):
// world.afterEvents.somethingNew.subscribe(...) throwing would take the whole addon down at load
function signals(names, before) {
  const out = {};
  for (const n of names) out[n] = new KoperSignal(n, before);
  return new Proxy(out, {
    get(t, k) {
      if (typeof k !== "string" || k in t || k === "then" || k === "toJSON" || k.startsWith("_")) return t[k];
      return (t[k] = new KoperSignal(k, before));
    },
  });
}

const AFTER_NAMES = ["blockExplode", "buttonPush", "chatSend", "dataDrivenEntityTrigger", "effectAdd", "entityDie",
  "entityHealthChanged", "entityHitBlock", "entityHitEntity", "entityHurt", "entityLoad", "entityRemove", "entitySpawn",
  "explosion", "gameRuleChange", "itemCompleteUse", "itemReleaseUse", "itemStartUse", "itemStartUseOn", "itemStopUse",
  "itemStopUseOn", "itemUse", "itemUseOn", "leverAction", "pistonActivate", "playerBreakBlock", "playerButtonInput",
  "playerDimensionChange", "playerEmote", "playerGameModeChange", "playerHotbarSelectedSlotChange", "playerInputModeChange",
  "playerInputPermissionCategoryChange", "playerInteractWithBlock", "playerInteractWithEntity", "playerInventoryItemChange",
  "playerJoin", "playerLeave", "playerPlaceBlock", "playerSpawn", "pressurePlatePop", "pressurePlatePush", "projectileHitBlock",
  "projectileHitEntity", "targetBlockHit", "tripWireTrip", "weatherChange", "worldInitialize", "worldLoad",
  "blockContainerClosed", "blockContainerOpened", "entityContainerClosed", "entityContainerOpened", "entityHeal",
  "entityItemDrop", "entityItemPickup", "entityStartSneaking", "entityStopSneaking", "entityTamed", "entityUpgrade",
  "playerCancelBreakingBlock", "playerStartBreakingBlock", "playerSwingStart", "soundCompleted"];
const BEFORE_NAMES = ["chatSend", "effectAdd", "entityHeal", "entityItemPickup", "entityTamed", "entityRemove", "explosion", "itemUse", "itemUseOn", "playerBreakBlock",
  "playerGameModeChange", "playerInteractWithBlock", "playerInteractWithEntity", "playerLeave", "playerPlaceBlock",
  "weatherChange", "worldInitialize", "entityHurt"];

// ── wrappers from java refs ─────────────────────────────────────────────────

const entityCache = new Map();

function wrapEntity(ref) {
  if (!ref) return undefined;
  const id = ref.e;
  let got = entityCache.get(id);
  if (!got) {
    got = ref.p ? new Player(id, ref.ty, ref.n) : new Entity(id, ref.ty);
    entityCache.set(id, got);
  }
  return got;
}

function wrapBlock(ref) {
  if (!ref) return undefined;
  return new Block(dimensionOf(ref.dim), ref.x, ref.y, ref.z);
}

// ItemFilter: include/exclude by type id or tag
function itemPasses(st, f) {
  const id = (t) => (String(t).includes(":") ? String(t) : "minecraft:" + t);
  if (f.includeTypes && f.includeTypes.length && !f.includeTypes.map(id).includes(st.typeId)) return false;
  if (f.excludeTypes && f.excludeTypes.map(id).includes(st.typeId)) return false;
  if (f.includeTags && f.includeTags.length && !f.includeTags.some((t) => st.hasTag(t))) return false;
  if (f.excludeTags && f.excludeTags.some((t) => st.hasTag(t))) return false;
  return true;
}

// BlockFilter: include/exclude by type id, tag or permutation
function blockPasses(b, f) {
  if (!f) return true;
  const id = (t) => (String(t).includes(":") ? String(t) : "minecraft:" + t);
  const ty = b.typeId;
  if (f.includeTypes && f.includeTypes.length && !f.includeTypes.map(id).includes(ty)) return false;
  if (f.excludeTypes && f.excludeTypes.map(id).includes(ty)) return false;
  if ((f.includeTags && f.includeTags.length) || (f.excludeTags && f.excludeTags.length)) {
    const tags = K.ask("blk.tags", { ty }) || [];
    const has = (t) => tags.includes(t) || tags.includes(id(t));
    if (f.includeTags && f.includeTags.length && !f.includeTags.some(has)) return false;
    if (f.excludeTags && f.excludeTags.some(has)) return false;
  }
  if (f.includePermutations && f.includePermutations.length && !f.includePermutations.some((p) => b.permutation.matches(p.type ? p.type.id : p.typeId, p.getAllStates ? p.getAllStates() : undefined))) return false;
  if (f.excludePermutations && f.excludePermutations.some((p) => b.permutation.matches(p.type ? p.type.id : p.typeId, p.getAllStates ? p.getAllStates() : undefined))) return false;
  return true;
}

const dims = new Map();
function dimensionOf(id) {
  id = dimId(id);
  if (id === "minecraft:the_nether") id = "minecraft:nether";
  let d = dims.get(id);
  if (!d) { d = new Dimension(id); dims.set(id, d); }
  return d;
}

// ── items ────────────────────────────────────────────────────────────────────

export class ItemType { constructor(id) { this.id = id; } }
export class BlockType { constructor(id) { this.id = id; } }
export class EntityType { constructor(id) { this.id = id; } }
export class EffectType { constructor(id) { this._id = id; } getName() { return this._id; } }
export class EnchantmentType { constructor(id, max) { this.id = id; this.maxLevel = max || 5; } }

function typeBook(kind, Ctor) {
  return {
    get(id) {
      id = String(id); if (!id.includes(":")) id = "minecraft:" + id;
      const ok = K.ask("hasType", { kind, id });
      return ok ? new Ctor(id) : undefined;
    },
    getAll() { return (K.ask("types", { kind }) || []).map((id) => new Ctor(id)); },
  };
}
export const ItemTypes = typeBook("item", ItemType);
export const BlockTypes = typeBook("block", BlockType);
export const EntityTypes = typeBook("entity", EntityType);
export const EffectTypes = typeBook("effect", EffectType);
export const EnchantmentTypes = typeBook("ench", EnchantmentType);

// the ones the api hangs off static methods. before these existed getAll() was "not a function" and
// anything built from it (a list of dimensions to loop over every tick) stayed undefined forever
export class DimensionType { constructor(id) { this.typeId = id; } }
export class DimensionTypes {
  static get(id) { return typeBook("dim", DimensionType).get(id); }
  static getAll() { return typeBook("dim", DimensionType).getAll(); }
}
export const MinecraftDimensionTypes = Object.freeze({ Overworld: "minecraft:overworld", Nether: "minecraft:nether", TheEnd: "minecraft:the_end" });
export class BiomeType {
  constructor(id) { this.id = id; }
  getTags() { nope("BiomeType.getTags"); }
  hasTags() { nope("BiomeType.hasTags"); }
}
export class BiomeTypes {
  static get(id) { return typeBook("biome", BiomeType).get(id); }
  static getAll() { return typeBook("biome", BiomeType).getAll(); }
}
export class BlockStateType { constructor(id, values) { this.id = id; this.validValues = values; } }
let stateBook;
const states = () => stateBook || (stateBook = K.ask("blockStates") || {});
export class BlockStates {
  static get(name) { const v = states()[name]; return v ? new BlockStateType(name, v) : undefined; }
  static getAll() { return Object.entries(states()).map(([k, v]) => new BlockStateType(k, v)); }
}
export class PotionEffectType { constructor(id) { this.id = id; } }
// java potions have no delivery on the potion itself, it is the item: potion, splash, lingering
export class PotionDeliveryType { constructor(id) { this.id = id; } }
const DELIVERY = ["Consume", "ThrownSplash", "ThrownLingering"];
export class Potions {
  static getEffectType(id) { return typeBook("potion", PotionEffectType).get(id); }
  static getAllEffectTypes() { return typeBook("potion", PotionEffectType).getAll(); }
  static getDeliveryType(id) { return DELIVERY.includes(id) ? new PotionDeliveryType(id) : undefined; }
  static getAllDeliveryTypes() { return DELIVERY.map((d) => new PotionDeliveryType(d)); }
  static resolve() { nope("Potions.resolve"); }
}

const stackInfo = new Map();
function infoOf(typeId) {
  let i = stackInfo.get(typeId);
  if (!i) { i = K.ask("item.info", { ty: typeId }) || { max: 64, tags: [] }; stackInfo.set(typeId, i); }
  return i;
}

export class ItemStack {
  constructor(itemType, amount = 1) {
    let id = typeof itemType === "string" ? itemType : itemType && itemType.id;
    if (!id) throw new TypeError("ItemStack needs an item type");
    if (!id.includes(":")) id = "minecraft:" + id;
    this._s = { typeId: id, amount: amount | 0, nameTag: undefined, lore: [], damage: 0, ench: [], dyn: {}, lock: "none", keep: false, can: {} };
    if (this._s.amount < 1 || this._s.amount > 255) throw new RangeError("ItemStack amount out of range: " + amount);
  }
  static _from(s) {
    if (!s) return undefined;
    const st = Object.create(ItemStack.prototype);
    st._s = Object.assign({ lore: [], damage: 0, ench: [], dyn: {}, lock: "none", keep: false, can: {} }, s);
    return st;
  }
  _json() { return this._s; }
  get typeId() { return this._s.typeId; }
  get type() { return new ItemType(this._s.typeId); }
  get amount() { return this._s.amount; }
  set amount(v) { this._s.amount = v | 0; }
  get maxAmount() { return infoOf(this._s.typeId).max; }
  get isStackable() { return this.maxAmount > 1; }
  get nameTag() { return this._s.nameTag; }
  set nameTag(v) { this._s.nameTag = v === undefined || v === null ? undefined : String(v); }
  get lockMode() { return this._s.lock; }
  set lockMode(v) { this._s.lock = v; }
  get keepOnDeath() { return this._s.keep; }
  set keepOnDeath(v) { this._s.keep = !!v; }
  get localizationKey() { return "item." + this._s.typeId.replace(":", "."); }
  getLore() { return this._s.lore.slice(); }
  setLore(lines) { this._s.lore = (lines || []).map((l) => (typeof l === "string" ? l : JSON.stringify(rawToComponent(l)))); }
  getRawLore() { return this._s.lore.map((l) => ({ text: l })); }
  getTags() { return infoOf(this._s.typeId).tags.slice(); }
  hasTag(t) { return infoOf(this._s.typeId).tags.includes(t); }
  clone() { return ItemStack._from(JSON.parse(JSON.stringify(this._s))); }
  isStackableWith(other) {
    if (!other) return false;
    const a = Object.assign({}, this._s, { amount: 0 }), b = Object.assign({}, other._s, { amount: 0 });
    return JSON.stringify(a) === JSON.stringify(b);
  }
  matches(typeId) { return this._s.typeId === (typeId.includes(":") ? typeId : "minecraft:" + typeId); }
  setCanDestroy(ids) { this._s.can.destroy = ids || []; }
  setCanPlaceOn(ids) { this._s.can.place = ids || []; }
  getCanDestroy() { return (this._s.can.destroy || []).slice(); }
  getCanPlaceOn() { return (this._s.can.place || []).slice(); }
  getDynamicProperty(k) { return this._s.dyn[k]; }
  setDynamicProperty(k, v) { if (v === undefined) delete this._s.dyn[k]; else this._s.dyn[k] = v; }
  setDynamicProperties(vals) { for (const k in vals) this.setDynamicProperty(k, vals[k]); }
  getDynamicPropertyIds() { return Object.keys(this._s.dyn); }
  clearDynamicProperties() { this._s.dyn = {}; }
  getDynamicPropertyTotalByteCount() { return JSON.stringify(this._s.dyn).length; }
  hasComponent(id) { return this.getComponent(id) !== undefined; }
  getComponents() { return ["durability", "enchantable", "cooldown"].map((c) => this.getComponent(c)).filter(Boolean); }
  getComponent(id) {
    const s = this._s;
    switch (String(id).replace("minecraft:", "")) {
      case "durability": {
        const max = infoOf(s.typeId).dur || 0;
        if (!max) return undefined;
        return { typeId: "minecraft:durability", get damage() { return s.damage; }, set damage(v) { s.damage = v | 0; },
          maxDurability: max, getDamageChance() { return 100; }, getDamageChanceRange() { return { min: 0, max: 100 }; } };
      }
      case "enchantable": return {
        typeId: "minecraft:enchantable",
        getEnchantments: () => s.ench.map((e) => ({ type: new EnchantmentType(e.id), level: e.level })),
        getEnchantment: (t) => { const id = typeof t === "string" ? t : t.id; const e = s.ench.find((x) => x.id === id || x.id === "minecraft:" + id); return e && { type: new EnchantmentType(e.id), level: e.level }; },
        hasEnchantment: (t) => { const id = typeof t === "string" ? t : t.id; return s.ench.some((x) => x.id === id || x.id === "minecraft:" + id); },
        addEnchantment: (e) => { const id = typeof e.type === "string" ? e.type : e.type.id; s.ench = s.ench.filter((x) => x.id !== id); s.ench.push({ id: id.includes(":") ? id : "minecraft:" + id, level: e.level }); },
        addEnchantments: (list) => list.forEach((e) => this.getComponent("enchantable").addEnchantment(e)),
        removeEnchantment: (t) => { const id = typeof t === "string" ? t : t.id; s.ench = s.ench.filter((x) => x.id !== id && x.id !== "minecraft:" + id); },
        removeAllEnchantments: () => { s.ench = []; },
        canAddEnchantment: () => true,
      };
      case "cooldown": return {
        typeId: "minecraft:cooldown", cooldownCategory: s.typeId, cooldownTicks: infoOf(s.typeId).cool || 0,
        startCooldown: (player) => player && write("ent.set", { e: player.id, k: "cool", cat: s.typeId, v: infoOf(s.typeId).cool || 20 }),
        getCooldownTicksRemaining: (player) => (player ? K.ask("ent.cool", { e: player.id, cat: s.typeId }) : 0),
        isCooldownCategory: (c) => c === s.typeId,
      };
      case "food": {
        const f = infoOf(s.typeId).food;
        return f ? { typeId: "minecraft:food", nutrition: f.n, saturationModifier: f.s, canAlwaysEat: !!f.a, usingConvertsTo: undefined } : undefined;
      }
      default: return undefined;
    }
  }
}

// ── containers ───────────────────────────────────────────────────────────────

// owner = {e: uuid} or {blk: {dim,x,y,z}}, java does the same ops on both
export class Container {
  constructor(owner) { this._o = owner; }
  _q(a, extra) { return K.ask("inv", Object.assign({ o: this._o, a }, extra)); }
  _w(a, extra) { return write("inv", Object.assign({ o: this._o, a }, extra)); }
  get size() { return this._q("size"); }
  get emptySlotsCount() { return this._q("empty"); }
  get isValid() { return this._q("valid") === true; }
  get weight() { return 0; }
  getItem(slot) { this._check(slot); return ItemStack._from(this._q("get", { slot })); }
  setItem(slot, item) { this._check(slot); this._w("set", { slot, item: item ? item._json() : null }); }
  addItem(item) { return ItemStack._from(this._w("add", { item: item._json() })); }
  clearAll() { this._w("clear"); }
  swapItems(slot, otherSlot, other) {
    const a = this.getItem(slot), b = other.getItem(otherSlot);
    this.setItem(slot, b); other.setItem(otherSlot, a);
  }
  moveItem(from, to, other) {
    const a = this.getItem(from); if (!a) return;
    const b = other.getItem(to);
    if (!b) { other.setItem(to, a); this.setItem(from, undefined); return; }
    if (b.isStackableWith(a)) {
      const room = b.maxAmount - b.amount, moved = Math.min(room, a.amount);
      b.amount += moved; other.setItem(to, b);
      a.amount -= moved; this.setItem(from, a.amount > 0 ? a : undefined);
    }
  }
  transferItem(from, other) {
    const a = this.getItem(from); if (!a) return undefined;
    const left = other.addItem(a); this.setItem(from, left);
    return left;
  }
  getSlot(slot) { this._check(slot); return new ContainerSlot(this, slot); }
  find(item) { for (let i = 0; i < this.size; i++) { const it = this.getItem(i); if (it && it.isStackableWith(item)) return i; } return undefined; }
  findLast(item) { for (let i = this.size - 1; i >= 0; i--) { const it = this.getItem(i); if (it && it.isStackableWith(item)) return i; } return undefined; }
  firstEmptySlot() { for (let i = 0; i < this.size; i++) if (!this.getItem(i)) return i; return undefined; }
  firstItem() { for (let i = 0; i < this.size; i++) if (this.getItem(i)) return i; return undefined; }
  contains(item) { return this.find(item) !== undefined; }
  _check(slot) {
    if (typeof slot !== "number" || slot < 0 || slot >= this.size) throw new InvalidContainerSlotError("slot " + slot + " is outside the container");
  }
}

export class ContainerSlot {
  constructor(c, slot) { this._c = c; this._slot = slot; }
  getItem() { return this._c.getItem(this._slot); }
  setItem(i) { this._c.setItem(this._slot, i); }
  hasItem() { return this.getItem() !== undefined; }
  isValid() { return this._c.isValid; }
  _edit(fn) { const it = this.getItem(); if (!it) throw new Error("empty slot"); fn(it); this.setItem(it); }
  get typeId() { const i = this.getItem(); return i && i.typeId; }
  get type() { const i = this.getItem(); return i && i.type; }
  get amount() { const i = this.getItem(); return i ? i.amount : 0; }
  set amount(v) { this._edit((i) => { i.amount = v; }); }
  get nameTag() { const i = this.getItem(); return i && i.nameTag; }
  set nameTag(v) { this._edit((i) => { i.nameTag = v; }); }
  get maxAmount() { const i = this.getItem(); return i ? i.maxAmount : 64; }
  get isStackable() { const i = this.getItem(); return !!i && i.isStackable; }
  get keepOnDeath() { const i = this.getItem(); return !!i && i.keepOnDeath; }
  set keepOnDeath(v) { this._edit((i) => { i.keepOnDeath = v; }); }
  get lockMode() { const i = this.getItem(); return i ? i.lockMode : "none"; }
  set lockMode(v) { this._edit((i) => { i.lockMode = v; }); }
  getLore() { const i = this.getItem(); return i ? i.getLore() : []; }
  setLore(l) { this._edit((i) => i.setLore(l)); }
  getTags() { const i = this.getItem(); return i ? i.getTags() : []; }
  hasTag(t) { const i = this.getItem(); return !!i && i.hasTag(t); }
  getDynamicProperty(k) { const i = this.getItem(); return i && i.getDynamicProperty(k); }
  setDynamicProperty(k, v) { this._edit((i) => i.setDynamicProperty(k, v)); }
  isStackableWith(o) { const i = this.getItem(); return !!i && i.isStackableWith(o); }
  getDynamicPropertyIds() { const i = this.getItem(); return i ? i.getDynamicPropertyIds() : []; }
  getDynamicPropertyTotalByteCount() { const i = this.getItem(); return i ? i.getDynamicPropertyTotalByteCount() : 0; }
  clearDynamicProperties() { this._edit((i) => i.clearDynamicProperties()); }
  setDynamicProperties(v) { this._edit((i) => i.setDynamicProperties(v)); }
  getCanDestroy() { const i = this.getItem(); return i ? i.getCanDestroy() : []; }
  getCanPlaceOn() { const i = this.getItem(); return i ? i.getCanPlaceOn() : []; }
  setCanDestroy(v) { this._edit((i) => i.setCanDestroy(v)); }
  setCanPlaceOn(v) { this._edit((i) => i.setCanPlaceOn(v)); }
  getRawLore() { const i = this.getItem(); return i ? i.getRawLore() : []; }
}

// ── blocks ───────────────────────────────────────────────────────────────────

export class BlockPermutation {
  constructor(typeId, states) { this._t = typeId; this._st = states || {}; }
  static resolve(typeId, states) {
    if (!typeId.includes(":")) typeId = "minecraft:" + typeId;
    const got = K.ask("perm", { ty: typeId, st: states || {} });
    if (!got) throw new Error("unknown block type " + typeId);
    return new BlockPermutation(got.ty, got.st);
  }
  get type() { return new BlockType(this._t); }
  getState(name) { return this._st[name]; }
  getAllStates() { return Object.assign({}, this._st); }
  withState(name, value) { return BlockPermutation.resolve(this._t, Object.assign({}, this._st, { [name]: value })); }
  matches(typeId, states) {
    if (!typeId.includes(":")) typeId = "minecraft:" + typeId;
    if (typeId !== this._t) return false;
    for (const k in states || {}) if (this._st[k] !== states[k]) return false;
    return true;
  }
  getTags() { return K.ask("blk.tags", { ty: this._t }) || []; }
  hasTag(t) { return this.getTags().includes(t); }
  getItemStack(amount = 1) { return new ItemStack(this._t, amount); }
  canContainLiquid() { return false; }
  isLiquidBlocking() { return true; }
}

export class Block {
  constructor(dim, x, y, z) { this.dimension = dim; this.x = x; this.y = y; this.z = z; }
  get location() { return { x: this.x, y: this.y, z: this.z }; }
  _ref() { return { dim: this.dimension.id, x: this.x, y: this.y, z: this.z }; }
  _info() { return K.ask("blk", this._ref()) || { ty: "minecraft:air", st: {} }; }
  get typeId() { return this._info().ty; }
  get type() { return new BlockType(this.typeId); }
  get permutation() { const i = this._info(); return new BlockPermutation(i.ty, i.st); }
  get isAir() { return this._info().air === true; }
  get isLiquid() { return this._info().liq === true; }
  get isSolid() { return this._info().solid === true; }
  get isValid() { const ok = this._info().loaded !== false; return K.apiMajor >= 2 ? ok : (() => ok); }
  get isWaterlogged() { return this._info().wl === true; }
  set isWaterlogged(v) { this.setWaterlogged(v); }
  get localizationKey() { return "tile." + this.typeId.replace("minecraft:", "") + ".name"; }
  get redstonePower() { return this._info().rs; }
  getRedstonePower() { return this._info().rs; }
  setType(t) { write("blk.set", Object.assign(this._ref(), { ty: typeof t === "string" ? t : t.id })); }
  setPermutation(p) { write("blk.set", Object.assign(this._ref(), { ty: p._t, st: p._st })); }
  setWaterlogged(v) { write("blk.set", Object.assign(this._ref(), { wl: !!v })); }
  hasTag(t) { return (this._info().tags || []).includes(t); }
  getTags() { return (this._info().tags || []).slice(); }
  matches(typeId, states) { return this.permutation.matches(typeId, states); }
  getItemStack(amount = 1) { return new ItemStack(this.typeId, amount); }
  offset(v) { return this.dimension.getBlock({ x: this.x + v.x, y: this.y + v.y, z: this.z + v.z }); }
  above(n = 1) { return this.offset({ x: 0, y: n, z: 0 }); }
  below(n = 1) { return this.offset({ x: 0, y: -n, z: 0 }); }
  north(n = 1) { return this.offset({ x: 0, y: 0, z: -n }); }
  south(n = 1) { return this.offset({ x: 0, y: 0, z: n }); }
  east(n = 1) { return this.offset({ x: n, y: 0, z: 0 }); }
  west(n = 1) { return this.offset({ x: -n, y: 0, z: 0 }); }
  center() { return { x: this.x + 0.5, y: this.y + 0.5, z: this.z + 0.5 }; }
  bottomCenter() { return { x: this.x + 0.5, y: this.y, z: this.z + 0.5 }; }
  canPlace(p) { return this.isAir || this.isLiquid; }
  hasComponent(id) { return this.getComponent(id) !== undefined; }
  getComponent(id) {
    const short = String(id).replace("minecraft:", "");
    if (short === "inventory") {
      const owner = { blk: this._ref() };
      const c = new Container(owner);
      if (!c.isValid) return undefined;
      return { typeId: "minecraft:inventory", container: c, block: this };
    }
    if (short === "sign") {
      const ref = this._ref();
      return { typeId: "minecraft:sign",
        getText: (side) => K.ask("blk.sign", Object.assign({ a: "get", side: side || "Front" }, ref)),
        setText: (msg, side) => write("blk.sign", Object.assign({ a: "set", side: side || "Front", m: rawToComponent(msg) }, ref)),
        isWaxed: false, setWaxed() {} };
    }
    return undefined;
  }
}

// ── dimension ───────────────────────────────────────────────────────────────

export class Dimension {
  constructor(id) { this.id = id; }
  get localizationKey() { return "dimension." + this.id.replace("minecraft:", ""); }
  get heightRange() { const h = K.ask("dim.height", { dim: this.id }) || { min: -64, max: 320 }; return { min: h.min, max: h.max }; }
  getBlock(loc) {
    const b = blockVec(loc);
    const h = this.heightRange;
    if (b.y < h.min || b.y >= h.max) return undefined;
    return new Block(this, b.x, b.y, b.z);
  }
  getBlockAbove(loc, opts) { for (let y = Math.floor(loc.y) + 1; y < this.heightRange.max; y++) { const b = this.getBlock({ x: loc.x, y, z: loc.z }); if (b && !b.isAir && (!opts || opts.includeLiquidBlocks || !b.isLiquid)) return b; } return undefined; }
  getBlockBelow(loc, opts) { for (let y = Math.floor(loc.y) - 1; y >= this.heightRange.min; y--) { const b = this.getBlock({ x: loc.x, y, z: loc.z }); if (b && !b.isAir && (!opts || opts.includeLiquidBlocks || !b.isLiquid)) return b; } return undefined; }
  getBlocks(volume, filter, allowUnloadedChunks) {
    const q = { dim: this.id, f: filter || {}, unl: !!allowUnloadedChunks };
    if (volume instanceof BlockVolume) {
      const a = volume.getMin(), b = volume.getMax();
      Object.assign(q, { x0: a.x, y0: a.y, z0: a.z, x1: b.x, y1: b.y, z1: b.z });
    } else {
      q.list = [];
      for (const p of volume.getBlockLocationIterator()) q.list.push(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
    }
    const flat = K.ask("blk.find", q) || [];
    const out = [];
    for (let i = 0; i + 2 < flat.length; i += 3) out.push({ x: flat[i], y: flat[i + 1], z: flat[i + 2] });
    return new ListBlockVolume(out);
  }
  // bedrock's second argument is where the search down starts (default: the top of the world), not a floor
  getTopmostBlock(xz, minHeight) {
    const args = { dim: this.id, x: Math.floor(xz.x), z: Math.floor(xz.z) };
    if (minHeight !== undefined && minHeight !== null) args.from = Math.floor(minHeight);
    const y = K.ask("blk.top", args);
    if (y === undefined || y === null) return undefined;
    return this.getBlock({ x: xz.x, y, z: xz.z });
  }
  getBlockFromRay(from, dir, opts) {
    const hit = K.ask("blk.ray", { dim: this.id, from: vec(from), dir: vec(dir), max: (opts && opts.maxDistance) || 64, liq: !!(opts && opts.includeLiquidBlocks) });
    return hit ? { block: wrapBlock(hit), face: hit.face, faceLocation: hit.fl } : undefined;
  }
  getEntitiesFromRay(from, dir, opts) {
    const hits = K.ask("ents.ray", { dim: this.id, from: vec(from), dir: vec(dir), max: (opts && opts.maxDistance) || 64 }) || [];
    return hits.map((h) => ({ entity: wrapEntity(h), distance: h.d }));
  }
  getEntities(opts) { return (K.ask("ents", { dim: this.id, q: queryJson(opts) }) || []).map(wrapEntity); }
  getEntitiesAtBlockLocation(loc) { const b = blockVec(loc); return this.getEntities({ location: { x: b.x + 0.5, y: b.y, z: b.z + 0.5 }, volume: { x: 1, y: 1, z: 1 } }); }
  getPlayers(opts) { return this.getEntities(Object.assign({}, opts, { type: "minecraft:player" })); }
  spawnEntity(typeId, loc, opts) {
    const id = typeof typeId === "string" ? typeId : typeId.id;
    const ref = write("spawn", { dim: this.id, ty: id.includes(":") ? id : "minecraft:" + id, x: loc.x, y: loc.y, z: loc.z, ev: opts && opts.spawnEvent });
    if (!ref) throw new Error("could not spawn " + id);
    return wrapEntity(ref);
  }
  spawnItem(item, loc) { return wrapEntity(write("spawnItem", { dim: this.id, item: item._json(), x: loc.x, y: loc.y, z: loc.z })); }
  spawnParticle(id, loc, vars) { K.ask("particle", { dim: this.id, id, x: loc.x, y: loc.y, z: loc.z }); }
  playSound(id, loc, opts) { K.ask("sound", { dim: this.id, id, x: loc.x, y: loc.y, z: loc.z, vol: (opts && opts.volume) || 1, pitch: (opts && opts.pitch) || 1 }); }
  createExplosion(loc, radius, opts) {
    opts = opts || {};
    write("boom", { dim: this.id, x: loc.x, y: loc.y, z: loc.z, r: radius, fire: !!opts.causesFire, breaks: opts.breaksBlocks !== false, water: !!opts.allowUnderwater, src: opts.source ? opts.source.id : undefined });
    return true;
  }
  runCommand(cmd) {
    const r = write("cmd", { dim: this.id, cmd: String(cmd).replace(/^\//, "") });
    if (r && r.error) throw new CommandError(r.error);
    return { successCount: r ? r.n : 0 };
  }
  runCommandAsync(cmd) { try { return Promise.resolve(this.runCommand(cmd)); } catch (e) { return Promise.reject(e); } }
  setBlockType(loc, t) { this.getBlock(loc).setType(t); }
  setBlockPermutation(loc, p) { this.getBlock(loc).setPermutation(p); }
  fillBlocks(volOrFrom, blockOrTo, maybeBlock) {
    let from, to, block;
    if (volOrFrom && volOrFrom.from && volOrFrom.to) { from = volOrFrom.from; to = volOrFrom.to; block = blockOrTo; }
    else if (volOrFrom && typeof volOrFrom.getMin === "function") { from = volOrFrom.getMin(); to = volOrFrom.getMax(); block = blockOrTo; }
    else { from = volOrFrom; to = blockOrTo; block = maybeBlock; }
    const perm = typeof block === "string" ? { ty: block } : block instanceof BlockType ? { ty: block.id } : { ty: block._t, st: block._st };
    const n = write("blk.fill", Object.assign({ dim: this.id, from: blockVec(from), to: blockVec(to) }, perm));
    return { getCapacity: () => n, getBlockLocationIterator: () => [][Symbol.iterator]() };
  }
  getWeather() { return K.ask("world", { a: "weather" }); }
  setWeather(t, dur) { write("world", { a: "setweather", v: t, d: dur }); }
  isChunkLoaded(loc) { return K.ask("dim.loaded", { dim: this.id, x: loc.x, z: loc.z }) === true; }
  getLightLevel(loc) { return K.ask("dim.light", Object.assign({ dim: this.id }, blockVec(loc))); }
  getSkyLightLevel(loc) { return K.ask("dim.light", Object.assign({ dim: this.id, sky: true }, blockVec(loc))); }
  getBiome(loc) { const id = K.ask("dim.biome", Object.assign({ dim: this.id }, blockVec(loc))); return id ? { id } : undefined; }
  spawnParticleEffect() {}
  containsBlock() { return false; }
  placeFeature() { nope("Dimension.placeFeature"); }
  placeFeatureRule() { nope("Dimension.placeFeatureRule"); }
}

function queryJson(o) {
  if (!o) return {};
  const q = {};
  if (o.type) q.type = o.type.includes(":") ? o.type : "minecraft:" + o.type;
  if (o.excludeTypes) q.notType = o.excludeTypes.map((t) => (t.includes(":") ? t : "minecraft:" + t));
  if (o.tags) q.tags = o.tags;
  if (o.excludeTags) q.notTags = o.excludeTags;
  if (o.families) q.fam = o.families;
  if (o.excludeFamilies) q.notFam = o.excludeFamilies;
  if (o.name) q.name = o.name;
  if (o.excludeNames) q.notName = o.excludeNames;
  if (o.location) q.loc = vec(o.location);
  if (o.maxDistance !== undefined) q.max = o.maxDistance;
  if (o.minDistance !== undefined) q.min = o.minDistance;
  if (o.volume) q.vol = vec(o.volume);
  if (o.closest !== undefined) q.closest = o.closest;
  if (o.farthest !== undefined) q.farthest = o.farthest;
  if (o.gameMode) q.gm = o.gameMode;
  if (o.excludeGameModes) q.notGm = o.excludeGameModes;
  if (o.minLevel !== undefined) q.minLvl = o.minLevel;
  if (o.maxLevel !== undefined) q.maxLvl = o.maxLevel;
  if (o.scoreOptions) q.scores = o.scoreOptions;
  return q;
}

// ── entity ───────────────────────────────────────────────────────────────────

class HealthFace {
  constructor(e) { this._e = e; this.typeId = "minecraft:health"; this.entity = e; }
  get currentValue() { return this._e._snap().hp; }
  get effectiveMax() { return this._e._snap().mhp; }
  get effectiveMin() { return 0; }
  get defaultValue() { return this._e._snap().mhp; }
  setCurrentValue(v) { write("ent.set", { e: this._e.id, k: "hp", v }); return true; }
  resetToMaxValue() { this.setCurrentValue(this.effectiveMax); }
  resetToDefaultValue() { this.resetToMaxValue(); }
  resetToMinValue() { this.setCurrentValue(0); }
  isValid() { return this._e.isValid; }
}

class AttrFace {
  constructor(e, typeId, attr) { this._e = e; this.typeId = typeId; this._a = attr; this.entity = e; }
  get currentValue() { return K.ask("ent.attr", { e: this._e.id, a: this._a }); }
  get defaultValue() { return K.ask("ent.attr", { e: this._e.id, a: this._a, base: true }); }
  get effectiveMax() { return this.currentValue; }
  get effectiveMin() { return 0; }
  get value() { return this.currentValue; }
  set value(v) { this.setCurrentValue(v); }
  setCurrentValue(v) { write("ent.attr", { e: this._e.id, a: this._a, v }); return true; }
  resetToDefaultValue() { this.setCurrentValue(this.defaultValue); }
  resetToMaxValue() { this.resetToDefaultValue(); }
  resetToMinValue() { this.setCurrentValue(0); }
}

const SLOT_NAMES = { Head: "head", Chest: "chest", Legs: "legs", Feet: "feet", Mainhand: "mainhand", Offhand: "offhand", Body: "body" };

export class Entity {
  constructor(id, typeId) { this.id = id; this.typeId = typeId; }
  _snap() {
    if (snapTick !== system.currentTick) { snaps.clear(); snapTick = system.currentTick; }
    let s = snaps.get(this.id);
    if (!s) { s = K.ask("ent", { e: this.id }) || { ok: false }; snaps.set(this.id, s); }
    if (!s.ok) throw new InvalidEntityError("entity " + this.id + " (" + this.typeId + ") is gone");
    return s;
  }
  _alive() { if (snapTick !== system.currentTick) { snaps.clear(); snapTick = system.currentTick; } let s = snaps.get(this.id); if (!s) { s = K.ask("ent", { e: this.id }) || { ok: false }; snaps.set(this.id, s); } return !!s.ok; }
  get isValid() { return K.apiMajor >= 2 ? this._alive() : (() => this._alive()); }
  get dimension() { return dimensionOf(this._snap().dim); }
  get location() { const s = this._snap(); return { x: s.x, y: s.y, z: s.z }; }
  get nameTag() { return this._snap().tag || ""; }
  set nameTag(v) { write("ent.set", { e: this.id, k: "nameTag", v: String(v) }); }
  get isSneaking() { return this._snap().sneak; }
  set isSneaking(v) { write("ent.set", { e: this.id, k: "sneak", v: !!v }); }
  get isSprinting() { return this._snap().sprint; }
  get isOnGround() { return this._snap().ground; }
  get isInWater() { return this._snap().water; }
  get isSwimming() { return this._snap().swim; }
  get isFalling() { return this._snap().fall; }
  get isClimbing() { return this._snap().climb; }
  get isSleeping() { return this._snap().sleep; }
  get isGliding() { return this._snap().glide; }
  get isJumping() { return false; }
  get fallDistance() { return this._snap().fd || 0; }
  get localizationKey() { return "entity." + this.typeId.replace("minecraft:", "") + ".name"; }
  get target() { return wrapEntity(this._snap().target); }
  get scoreboardIdentity() { return new ScoreboardIdentity(this.id, this.nameTag || this.typeId, "Entity", this); }
  getHeadLocation() { const h = this._snap().head; return { x: h.x, y: h.y, z: h.z }; }
  getViewDirection() { const l = this._snap().look; return { x: l.x, y: l.y, z: l.z }; }
  getRotation() { const s = this._snap(); return { x: s.rx, y: s.ry }; }
  setRotation(r) { write("ent.set", { e: this.id, k: "rot", x: r.x, y: r.y }); }
  getVelocity() { const s = this._snap(); return { x: s.vx, y: s.vy, z: s.vz }; }
  clearVelocity() { write("ent.set", { e: this.id, k: "vel", x: 0, y: 0, z: 0 }); }
  applyImpulse(v) { write("ent.set", { e: this.id, k: "impulse", x: v.x, y: v.y, z: v.z }); }
  applyKnockback(a, b, c, d) {
    // 1.x: (dirX, dirZ, horizontal, vertical), 2.x: ({x,z} scaled, vertical)
    if (typeof a === "object") write("ent.set", { e: this.id, k: "impulse", x: a.x, y: b, z: a.z });
    else { const len = Math.hypot(a, b) || 1; write("ent.set", { e: this.id, k: "impulse", x: a / len * c, y: d, z: b / len * c }); }
  }
  teleport(loc, opts) {
    opts = opts || {};
    const r = opts.rotation;
    write("ent.tp", { e: this.id, x: loc.x, y: loc.y, z: loc.z, dim: opts.dimension ? opts.dimension.id : undefined,
      rx: r ? r.x : undefined, ry: r ? r.y : undefined, face: opts.facingLocation ? vec(opts.facingLocation) : undefined, keep: !!opts.keepVelocity });
  }
  tryTeleport(loc, opts) {
    opts = opts || {};
    return write("ent.tp", { e: this.id, x: loc.x, y: loc.y, z: loc.z, dim: opts.dimension ? opts.dimension.id : undefined, check: opts.checkForBlocks !== false, keep: !!opts.keepVelocity }) === true;
  }
  kill() { return write("ent.set", { e: this.id, k: "kill" }) === true; }
  remove() { write("ent.set", { e: this.id, k: "remove" }); entityCache.delete(this.id); }
  applyDamage(amount, opts) {
    opts = opts || {};
    const by = opts.damagingEntity || opts.damagingProjectile;
    return write("ent.dmg", { e: this.id, amt: amount, cause: opts.cause || "none", by: by ? by.id : undefined }) === true;
  }
  addTag(t) { return write("ent.set", { e: this.id, k: "tag+", v: t }) === true; }
  removeTag(t) { return write("ent.set", { e: this.id, k: "tag-", v: t }) === true; }
  hasTag(t) { return this._snap().tags.includes(t); }
  getTags() { return this._snap().tags.slice(); }
  addEffect(type, duration, opts) {
    const id = typeof type === "string" ? type : type.getName();
    opts = opts || {};
    write("ent.fx+", { e: this.id, id: id.includes(":") ? id : "minecraft:" + id, dur: duration, amp: opts.amplifier || 0, part: opts.showParticles !== false });
    return this.getEffect(id);
  }
  removeEffect(type) { const id = typeof type === "string" ? type : type.getName(); return write("ent.fx-", { e: this.id, id: id.includes(":") ? id : "minecraft:" + id }) === true; }
  getEffect(type) {
    const id = typeof type === "string" ? type : type.getName();
    const all = this.getEffects();
    const want = id.includes(":") ? id : "minecraft:" + id;
    return all.find((f) => f.typeId === want);
  }
  getEffects() {
    return (K.ask("ent.fx", { e: this.id }) || []).map((f) => ({ typeId: f.id, amplifier: f.amp, duration: f.dur, displayName: f.name || f.id, isValid: true }));
  }
  getComponent(id) {
    const short = String(id).replace("minecraft:", "");
    switch (short) {
      case "health": return this._snap().mhp !== undefined ? new HealthFace(this) : undefined;
      case "inventory": {
        const c = new Container({ e: this.id });
        if (!c.isValid) return undefined;
        return { typeId: "minecraft:inventory", container: c, inventorySize: c.size, entity: this, canBeSiphonedFrom: true, private: false, restrictToOwner: false, additionalSlotsPerStrength: 0, containerType: "inventory" };
      }
      case "equippable": return {
        typeId: "minecraft:equippable", entity: this,
        getEquipment: (slot) => ItemStack._from(K.ask("eq", { e: this.id, slot: SLOT_NAMES[slot] || slot })),
        setEquipment: (slot, item) => write("eq", { e: this.id, slot: SLOT_NAMES[slot] || slot, item: item ? item._json() : null }) !== false,
        getEquipmentSlot: (slot) => {
          const self = this, s = SLOT_NAMES[slot] || slot;
          const box = { size: 1, get isValid() { return true; }, getItem: () => ItemStack._from(K.ask("eq", { e: self.id, slot: s })), setItem: (_i, it) => write("eq", { e: self.id, slot: s, item: it ? it._json() : null }) };
          return { getItem: () => box.getItem(), setItem: (it) => box.setItem(0, it), hasItem: () => !!box.getItem(), get typeId() { const i = box.getItem(); return i && i.typeId; }, get amount() { const i = box.getItem(); return i ? i.amount : 0; } };
        },
      };
      case "movement": return new AttrFace(this, "minecraft:movement", "movement_speed");
      case "underwater_movement": return new AttrFace(this, "minecraft:underwater_movement", "water_movement_efficiency");
      case "type_family": {
        const fam = this._snap().fam || [];
        return { typeId: "minecraft:type_family", hasTypeFamily: (f) => fam.includes(f), getTypeFamilies: () => fam.slice() };
      }
      case "is_baby": return this._snap().baby ? { typeId: "minecraft:is_baby" } : undefined;
      case "onfire": return this._snap().fire > 0 ? { typeId: "minecraft:onfire", onFireTicksRemaining: this._snap().fire } : undefined;
      case "item": { const it = this._snap().item; return it ? { typeId: "minecraft:item", itemStack: ItemStack._from(it) } : undefined; }
      case "scale": return { typeId: "minecraft:scale", get value() { return K.ask("ent.attr", { e: this.id, a: "scale" }); } };
      case "variant": return { typeId: "minecraft:variant", value: this._snap().variant || 0 };
      case "mark_variant": return { typeId: "minecraft:mark_variant", value: this._snap().mark || 0 };
      case "skin_id": return { typeId: "minecraft:skin_id", value: this._snap().skin || 0 };
      case "tameable": return this._snap().tame === undefined ? undefined : { typeId: "minecraft:tameable", isTamed: !!this._snap().tame, get tamedToPlayer() { return undefined; }, tame: (p) => write("ent.set", { e: this.id, k: "tame", v: p ? p.id : null }) };
      case "projectile": return {
        typeId: "minecraft:projectile", entity: this,
        shoot: (v, opts) => write("ent.shoot", { e: this.id, x: +v.x, y: +v.y, z: +v.z, owner: opts && opts.owner ? opts.owner.id : undefined }),
        set owner(o) { write("ent.shoot", { e: this.id, ownerOnly: true, owner: o ? o.id : undefined }); },
      };
      case "riding": return this._snap().vehicle ? { typeId: "minecraft:riding", entityRidingOn: wrapEntity(this._snap().vehicle) } : undefined;
      case "rideable": return { typeId: "minecraft:rideable", getRiders: () => (this._snap().riders || []).map(wrapEntity), addRider: (e) => write("ent.set", { e: e.id, k: "ride", v: this.id }) === true, ejectRider: (e) => write("ent.set", { e: e.id, k: "unride" }), ejectRiders: () => (this._snap().riders || []).forEach((r) => write("ent.set", { e: r.e, k: "unride" })) };
      default: return undefined;
    }
  }
  hasComponent(id) { return this.getComponent(id) !== undefined; }
  getComponents() { return ["health", "inventory", "equippable", "movement", "type_family"].map((c) => this.getComponent(c)).filter(Boolean); }
  runCommand(cmd) {
    const r = write("cmd", { e: this.id, cmd: String(cmd).replace(/^\//, "") });
    if (r && r.error) throw new CommandError(r.error);
    return { successCount: r ? r.n : 0 };
  }
  runCommandAsync(cmd) { try { return Promise.resolve(this.runCommand(cmd)); } catch (e) { return Promise.reject(e); } }
  getDynamicProperty(k) { return K.ask("dyn", { o: this.id, a: "get", k }); }
  setDynamicProperty(k, v) { write("dyn", { o: this.id, a: "set", k, v: v === undefined ? null : v }); }
  getDynamicPropertyIds() { return K.ask("dyn", { o: this.id, a: "ids" }) || []; }
  clearDynamicProperties() { write("dyn", { o: this.id, a: "clear" }); }
  getDynamicPropertyTotalByteCount() { return K.ask("dyn", { o: this.id, a: "bytes" }) || 0; }
  // bedrock entity properties live in the entity json. we keep them next to dynamic props with a prefix
  // a mob with a behavior definition keeps properties in its behavior state (the resource pack reads them),
  // anything else in dynamic properties
  getProperty(k) {
    const live = K.ask("prop.get", { e: this.id, k });
    if (live && live.has) return live.v;
    const v = this.getDynamicProperty("§prop:" + k);
    return v === undefined ? K.ask("prop.default", { ty: this.typeId, k }) : v;
  }
  setProperty(k, v) { if (write("prop.set", { e: this.id, k, v }) !== true) this.setDynamicProperty("§prop:" + k, v); }
  resetProperty(k) {
    if (write("prop.reset", { e: this.id, k }) !== true) this.setDynamicProperty("§prop:" + k, undefined);
    return this.getProperty(k);
  }
  setOnFire(seconds, useEffects) { return write("ent.set", { e: this.id, k: "fire", v: seconds }) === true; }
  extinguishFire() { return write("ent.set", { e: this.id, k: "fire", v: 0 }) === true; }
  matches(opts) { return K.ask("ent.match", { e: this.id, q: queryJson(opts) }) === true; }
  lookAt(loc) { write("ent.set", { e: this.id, k: "look", x: loc.x, y: loc.y, z: loc.z }); }
  getAllBlocksStandingOn(opts) {
    return (K.ask("ent.onAll", { e: this.id }) || []).map(wrapBlock)
      .filter((b) => b && !b.isAir && (!b.isLiquid || (opts && opts.includeLiquidBlocks)) && blockPasses(b, opts && opts.blockFilter));
  }
  setDynamicProperties(vals) { for (const k in vals) this.setDynamicProperty(k, vals[k]); }
  getBlockStandingOn(opts) {
    const b = wrapBlock(K.ask("ent.on", { e: this.id }));
    if (!b || b.isAir) return undefined;
    if (b.isLiquid && !(opts && opts.includeLiquidBlocks)) return undefined;
    return blockPasses(b, opts && opts.blockFilter) ? b : undefined;
  }
  getBlockFromViewDirection(opts) {
    const s = this._snap();
    return this.dimension.getBlockFromRay(s.head, s.look, opts);
  }
  getEntitiesFromViewDirection(opts) {
    const s = this._snap();
    return this.dimension.getEntitiesFromRay(s.head, s.look, opts).filter((h) => h.entity.id !== this.id);
  }
  triggerEvent(ev) { K.ask("ent.event", { e: this.id, ev }); }
  // PlayAnimationOptions: nextState, blendOutTime, stopExpression, controller, players (names or Player objects)
  playAnimation(name, opts) {
    opts = opts || {};
    const players = opts.players ? opts.players.map((p) => (typeof p === "string" ? p : p.id)) : undefined;
    K.ask("ent.anim", { e: this.id, anim: String(name), next: opts.nextState, blend: opts.blendOutTime, stop: opts.stopExpression, ctrl: opts.controller, players });
  }
  addItem(item) { const c = this.getComponent("inventory"); return c ? c.container.addItem(item) : item; }
  getAABB() { const s = this._snap(); return { center: { x: s.x, y: s.y + (s.h || 1) / 2, z: s.z }, extent: { x: (s.w || 0.6) / 2, y: (s.h || 1) / 2, z: (s.w || 0.6) / 2 } }; }
}

class ScreenFace {
  constructor(p) { this._p = p; }
  setTitle(title, opts) {
    opts = opts || {};
    const e = this._p.id;
    if (opts.fadeInDuration !== undefined || opts.stayDuration !== undefined || opts.fadeOutDuration !== undefined)
      K.ask("ent.title", { e, a: "times", fi: opts.fadeInDuration ?? 10, st: opts.stayDuration ?? 70, fo: opts.fadeOutDuration ?? 20 });
    if (opts.subtitle !== undefined) K.ask("ent.title", { e, a: "sub", m: rawToComponent(opts.subtitle) });
    K.ask("ent.title", { e, a: "title", m: rawToComponent(title) });
  }
  updateSubtitle(sub) { K.ask("ent.title", { e: this._p.id, a: "sub", m: rawToComponent(sub) }); }
  setActionBar(msg) { K.ask("ent.title", { e: this._p.id, a: "bar", m: rawToComponent(msg) }); }
  clearTitle() { K.ask("ent.title", { e: this._p.id, a: "clear" }); }
  resetHudElements() {}
  setHudVisibility() {}
  hideAllExcept() {}
  isForcedHidden() { return false; }
  get isValid() { return this._p.isValid; }
}

export class Player extends Entity {
  constructor(id, typeId, name) { super(id, typeId || "minecraft:player"); this.name = name; this.onScreenDisplay = new ScreenFace(this); }
  get level() { return this._snap().lvl; }
  get xpEarnedAtCurrentLevel() { return this._snap().xp; }
  get totalXpNeededForNextLevel() { return this._snap().xpNext; }
  getTotalXp() { return this._snap().xpTotal; }
  addExperience(n) { write("ent.set", { e: this.id, k: "xp+", v: n }); return this.getTotalXp(); }
  addLevels(n) { write("ent.set", { e: this.id, k: "lvl+", v: n }); return this.level; }
  resetLevel() { write("ent.set", { e: this.id, k: "lvl0" }); }
  get selectedSlotIndex() { return this._snap().slot; }
  set selectedSlotIndex(v) { write("ent.set", { e: this.id, k: "slot", v }); }
  get isFlying() { return this._snap().fly; }
  get isEmoting() { return false; }
  get commandPermissionLevel() { return this._snap().op ? 2 : 0; }
  get playerPermissionLevel() { return this._snap().op ? 2 : 1; }
  // bedrock's camera: java draws it client side (BedrockKamera), this only says what the pack wants
  get camera() {
    const e = this.id;
    const send = (a, o) => write("camera", Object.assign({ e, a }, o));
    const v3 = (v) => [v.x, v.y, v.z];
    const c255 = (x) => Math.max(0, Math.min(255, Math.round((x ?? 0) * 255)));
    return {
      isValid: true,
      clear() { send("clear", {}); },
      fade(opts) {
        const t = (opts && opts.fadeTime) || {};
        const c = opts && opts.fadeColor;
        send("fade", { in: t.fadeInTime ?? 0.5, hold: t.holdTime ?? 0.5, out: t.fadeOutTime ?? 0.5,
          rgb: c ? (c255(c.red) << 16) | (c255(c.green) << 8) | c255(c.blue) : 0 });
      },
      setCamera(preset, opts) {
        const o = { preset: String(preset).includes(":") ? String(preset) : "minecraft:" + preset };
        if (opts) {
          if (opts.location) o.pos = v3(opts.location);
          if (opts.rotation) o.rot = [opts.rotation.x, opts.rotation.y];
          if (opts.facingLocation) o.facing = v3(opts.facingLocation);
          if (opts.facingEntity) o.facingUuid = opts.facingEntity.id;
          if (opts.easeOptions) { o.ease = opts.easeOptions.easeTime ?? 1; o.easeType = String(opts.easeOptions.easeType ?? "Linear"); }
        }
        send("set", o);
      },
      setDefaultCamera(preset, ease) { this.setCamera(preset, ease ? { easeOptions: ease } : undefined); },
      playAnimation() {},
      setFov() {},
      clearFov() {},
    };
  }
  get inputPermissions() {
    const self = this;
    const cat = (c) => (typeof c === "number" ? (c === 1 ? "camera" : "movement") : String(c).toLowerCase().includes("camera") ? "camera" : "movement");
    const get = (c) => K.ask("input", { e: self.id, cat: cat(c) }) !== false;
    const set = (c, on) => { write("input", { e: self.id, cat: cat(c), on: !!on }); };
    return {
      isPermissionCategoryEnabled: (c) => get(c),
      setPermissionCategory: (c, on) => set(c, on),
      get cameraEnabled() { return get("camera"); }, set cameraEnabled(v) { set("camera", v); },
      get movementEnabled() { return get("movement"); }, set movementEnabled(v) { set("movement", v); },
    };
  }
  get clientSystemInfo() { return { maxRenderDistance: 32, memoryTier: 4, platformType: "Desktop" }; }
  get inputInfo() { return { lastInputModeUsed: "KeyboardAndMouse", touchOnlyAffectsHotbar: false, getButtonState: () => "Released", getMovementVector: () => ({ x: 0, y: 0 }) }; }
  get graphicsMode() { return "Fancy"; }
  isOp() { return this._snap().op === true; }
  setOp(v) { write("ent.set", { e: this.id, k: "op", v: !!v }); }
  sendMessage(msg) { K.ask("ent.msg", { e: this.id, m: rawToComponent(msg) }); }
  getGameMode() { const g = this._snap().gm; return K.apiMajor >= 2 ? g.charAt(0).toUpperCase() + g.slice(1) : g; }
  setGameMode(g) { write("ent.set", { e: this.id, k: "gm", v: g === undefined ? "default" : String(g).toLowerCase() }); }
  playSound(id, opts) {
    opts = opts || {};
    const loc = opts.location;
    K.ask("ent.sound", { e: this.id, id, vol: opts.volume ?? 1, pitch: opts.pitch ?? 1, x: loc && loc.x, y: loc && loc.y, z: loc && loc.z });
  }
  playMusic(id) { this.playSound(id); }
  queueMusic() {}
  stopMusic() {}
  getSpawnPoint() { const s = K.ask("ent.spawn", { e: this.id }); return s ? { x: s.x, y: s.y, z: s.z, dimension: dimensionOf(s.dim) } : undefined; }
  setSpawnPoint(p) { write("ent.set", { e: this.id, k: "spawn", v: p ? { x: p.x, y: p.y, z: p.z, dim: p.dimension ? p.dimension.id : undefined } : null }); }
  getItemCooldown(cat) { return K.ask("ent.cool", { e: this.id, cat }) || 0; }
  startItemCooldown(cat, ticks) { write("ent.set", { e: this.id, k: "cool", cat, v: ticks }); }
  eatItem(item) { nope("Player.eatItem"); }
  spawnParticle(id, loc) { K.ask("particle", { dim: this.dimension.id, id, x: loc.x, y: loc.y, z: loc.z, only: this.id }); }
  postClientMessage() {}
  clearPropertyOverridesForEntity() {}
  setPropertyOverrideForEntity() {}
  removePropertyOverrideForEntity() {}
}

// ── scoreboard ──────────────────────────────────────────────────────────────

export class ScoreboardIdentity {
  constructor(id, displayName, type, entity) { this.id = id; this.displayName = displayName; this.type = type; this._ent = entity; }
  getEntity() { return this._ent; }
  isValid() { return true; }
}

function participantName(p) {
  if (typeof p === "string") return p;
  if (p instanceof Player) return p.name;
  if (p instanceof Entity) return p.id;
  if (p instanceof ScoreboardIdentity) return p._ent ? participantName(p._ent) : p.displayName;
  return String(p);
}

function identityOf(raw) {
  const ent = raw.e ? wrapEntity(raw.e) : undefined;
  return new ScoreboardIdentity(raw.name, raw.name, ent ? (ent instanceof Player ? "Player" : "Entity") : "FakePlayer", ent);
}

export class ScoreboardObjective {
  constructor(id, displayName) { this.id = id; this._dn = displayName; }
  get displayName() { return K.ask("sb", { a: "dn", id: this.id }) ?? this._dn; }
  get isValid() { return K.ask("sb", { a: "has", id: this.id }) === true; }
  getScore(p) { const v = K.ask("sb", { a: "get", id: this.id, who: participantName(p) }); return v === null ? undefined : v; }
  setScore(p, v) { write("sb", { a: "set", id: this.id, who: participantName(p), v: v | 0 }); }
  addScore(p, v) { return write("sb", { a: "add", id: this.id, who: participantName(p), v: v | 0 }); }
  hasParticipant(p) { return this.getScore(p) !== undefined; }
  removeParticipant(p) { return write("sb", { a: "reset", id: this.id, who: participantName(p) }) === true; }
  getParticipants() { return (K.ask("sb", { a: "who", id: this.id }) || []).map(identityOf); }
  getScores() { return (K.ask("sb", { a: "all", id: this.id }) || []).map((r) => ({ participant: identityOf(r), score: r.v })); }
}

class KoperScoreboard {
  getObjective(id) { return K.ask("sb", { a: "has", id }) ? new ScoreboardObjective(id) : undefined; }
  addObjective(id, displayName) {
    if (this.getObjective(id)) throw new Error("objective " + id + " already exists");
    write("sb", { a: "new", id, dn: displayName || id });
    return new ScoreboardObjective(id, displayName || id);
  }
  removeObjective(o) { return write("sb", { a: "del", id: typeof o === "string" ? o : o.id }) === true; }
  getObjectives() { return (K.ask("sb", { a: "list" }) || []).map((o) => new ScoreboardObjective(o.id, o.dn)); }
  getParticipants() { return (K.ask("sb", { a: "everyone" }) || []).map(identityOf); }
  setObjectiveAtDisplaySlot(slot, opts) { write("sb", { a: "show", slot, id: opts.objective.id, order: opts.sortOrder }); return undefined; }
  getObjectiveAtDisplaySlot(slot) { const id = K.ask("sb", { a: "shown", slot }); return id ? { objective: new ScoreboardObjective(id) } : undefined; }
  clearObjectiveAtDisplaySlot(slot) { write("sb", { a: "show", slot, id: null }); }
}

// ── system ───────────────────────────────────────────────────────────────────

let nextRunId = 1;
const runs = new Map();
const jobs = new Map();

class KoperSystem {
  constructor() {
    this.currentTick = 0;
    this.afterEvents = { scriptEventReceive: new KoperSignal("scriptEventReceive", false) };
    this.beforeEvents = { startup: new KoperSignal("startup", true), watchdogTerminate: new KoperSignal("watchdogTerminate", true), shutdown: new KoperSignal("shutdown", true) };
    this.isEditorWorld = false;
    this.serverSystemInfo = { memoryTier: 4 };
  }
  run(cb) { return this.runTimeout(cb, 0); }
  runTimeout(cb, ticks = 1) {
    const id = nextRunId++;
    runs.set(id, { cb, at: this.currentTick + Math.max(ticks | 0, 1), every: 0 });
    return id;
  }
  runInterval(cb, ticks = 1) {
    const id = nextRunId++;
    const every = Math.max(ticks | 0, 1);
    runs.set(id, { cb, at: this.currentTick + every, every });
    return id;
  }
  clearRun(id) { runs.delete(id); }
  runJob(gen) { const id = nextRunId++; jobs.set(id, gen); return id; }
  clearJob(id) { jobs.delete(id); }
  waitTicks(n) { return new Promise((ok) => this.runTimeout(() => ok(), n)); }
  sendScriptEvent(id, message) { K.ask("scriptevent", { id, m: message }); }
  get isValid() { return true; }
}

export const system = new KoperSystem();

// ── world ────────────────────────────────────────────────────────────────────

class KoperGameRules {
  constructor() {
    return new Proxy(this, {
      get: (_t, k) => (typeof k === "string" ? K.ask("rule", { k }) : undefined),
      set: (_t, k, v) => { write("rule", { k, v }); return true; },
    });
  }
}

// ticking areas: java chunk tickets (BedrockStrefy). create resolves once every chunk in it ticks
const areaOf = (a) => (a ? { identifier: a.identifier, chunkCount: a.chunkCount, isFullyLoaded: !!a.isFullyLoaded, boundingBox: a.boundingBox, dimension: dimensionOf(a.dim) } : undefined);
const areaId = (x) => (x !== null && typeof x === "object" ? x.identifier : x);
export class TickingAreaManager {
  get chunkCount() { return K.ask("ticking", { k: "count" }) || 0; }
  get maxChunkCount() { return 400; }
  createTickingArea(identifier, options) {
    const dim = options && options.dimension ? (options.dimension.id || String(options.dimension)) : "minecraft:overworld";
    try {
      K.ask("ticking", { k: "add", id: String(identifier), dim, from: options.from, to: options.to });
    } catch (e) {
      const err = new TickingAreaError(e.message);
      return Promise.reject(err);
    }
    return new Promise((ok) => {
      let left = 400;
      const h = system.runInterval(() => {
        const a = K.ask("ticking", { k: "get", id: String(identifier) });
        if (!a || a.isFullyLoaded || --left <= 0) { system.clearRun(h); ok(); }
      }, 1);
    });
  }
  getAllTickingAreas() { return (K.ask("ticking", { k: "all" }) || []).map(areaOf); }
  getTickingArea(identifier) { return areaOf(K.ask("ticking", { k: "get", id: String(areaId(identifier)) })); }
  hasCapacity(options) {
    const a = options.from, b = options.to;
    const n = (Math.abs(Math.floor(b.x / 16) - Math.floor(a.x / 16)) + 1) * (Math.abs(Math.floor(b.z / 16) - Math.floor(a.z / 16)) + 1);
    return this.chunkCount + n <= this.maxChunkCount;
  }
  hasTickingArea(identifier) { return !!K.ask("ticking", { k: "get", id: String(identifier) }); }
  removeAllTickingAreas() { K.ask("ticking", { k: "clear" }); }
  removeTickingArea(identifier) { K.ask("ticking", { k: "remove", id: String(areaId(identifier)) }); }
}
export class TickingAreaError extends Error { constructor(m) { super(m); this.name = "TickingAreaError"; } }

class KoperWorld {
  constructor() {
    this.tickingAreaManager = new TickingAreaManager();
    this.afterEvents = signals(AFTER_NAMES, false);
    this.beforeEvents = signals(BEFORE_NAMES, true);
    this.scoreboard = new KoperScoreboard();
    this.gameRules = new KoperGameRules();
    this.isHardcore = false;
    this.seed = "0";
  }
  getDimension(id) { return dimensionOf(id); }
  getPlayers(opts) { return (K.ask("players", { q: queryJson(opts) }) || []).map(wrapEntity); }
  getAllPlayers() { return this.getPlayers(); }
  getEntity(id) { const ref = K.ask("ent.ref", { e: id }); return wrapEntity(ref); }
  sendMessage(msg) { K.ask("world.msg", { m: rawToComponent(msg) }); }
  getTimeOfDay() { return K.ask("world", { a: "time" }); }
  setTimeOfDay(t) { write("world", { a: "settime", v: t | 0 }); }
  getAbsoluteTime() { return K.ask("world", { a: "abs" }); }
  setAbsoluteTime(t) { write("world", { a: "setabs", v: t | 0 }); }
  getDay() { return K.ask("world", { a: "day" }); }
  getMoonPhase() { return K.ask("world", { a: "moon" }); }
  getDifficulty() { return K.ask("world", { a: "diff" }); }
  setDifficulty(d) { write("world", { a: "setdiff", v: d }); }
  getDefaultSpawnLocation() { return K.ask("world", { a: "spawn" }); }
  setDefaultSpawnLocation(l) { write("world", { a: "setspawn", v: blockVec(l) }); }
  playSound(id, loc, opts) { this.getDimension("overworld").playSound(id, loc, opts); }
  playMusic() {}
  queueMusic() {}
  stopMusic() {}
  getDynamicProperty(k) { return K.ask("dyn", { o: "world", a: "get", k }); }
  setDynamicProperty(k, v) { write("dyn", { o: "world", a: "set", k, v: v === undefined ? null : v }); }
  getDynamicPropertyIds() { return K.ask("dyn", { o: "world", a: "ids" }) || []; }
  clearDynamicProperties() { write("dyn", { o: "world", a: "clear" }); }
  getDynamicPropertyTotalByteCount() { return K.ask("dyn", { o: "world", a: "bytes" }) || 0; }
  broadcastClientMessage() {}
  getLootTableManager() { nope("world.getLootTableManager"); }
  getPackSettings() { return K.ask("packSettings") || {}; }
}

export const world = new KoperWorld();

// ── custom components + commands (startup / worldInitialize) ────────────────

const itemComps = new Map();
const blockComps = new Map();
const commands = new Map();
const enums = new Map();

const itemComponentRegistry = {
  registerCustomComponent(name, handlers) {
    if (!name.includes(":")) throw new Error("custom component '" + name + "' needs a namespace");
    itemComps.set(name, handlers);
  },
};
const blockComponentRegistry = {
  registerCustomComponent(name, handlers) {
    if (!name.includes(":")) throw new Error("custom component '" + name + "' needs a namespace");
    blockComps.set(name, handlers);
    // java only ticks blocks that asked for it, onTick/onRandomTick are expensive otherwise
    K.ask("cc.block", { n: name, tick: typeof handlers.onTick === "function", random: typeof handlers.onRandomTick === "function", step: typeof handlers.onStepOn === "function" || typeof handlers.onStepOff === "function" });
  },
};
const customCommandRegistry = {
  registerCommand(def, cb) {
    commands.set(def.name, { def, cb });
    K.ask("cmd.reg", { name: def.name, desc: def.description || "", perm: def.permissionLevel || 0,
      req: (def.mandatoryParameters || []).map((p) => ({ n: p.name, t: p.type })),
      opt: (def.optionalParameters || []).map((p) => ({ n: p.name, t: p.type })) });
  },
  registerEnum(name, values) { enums.set(name, values); },
};

// ── java -> js entry points ─────────────────────────────────────────────────

const SPAWN_FIELD = { entity: 1, deadEntity: 1, hurtEntity: 1, damagingEntity: 1, hitEntity: 1, source: 1, player: 1, sender: 1,
  sourceEntity: 1, target: 1, removedEntity: 1, attackingEntity: 1, projectile: 1, initiator: 1 };

// java sends {"$e":ref} / {"$b":ref} / {"$i":stack} / {"$d":dim} / {"$p":perm} markers, this turns them into objects
function revive(v) {
  // bedrock leaves an absent field undefined, never null: packs test "void 0 !== e.itemStack" and a null from
  // java walked straight past that into null.typeId (villager news on every empty handed click)
  if (v === null) return undefined;
  if (typeof v !== "object") return v;
  if (Array.isArray(v)) return v.map(revive);
  if (v.$e !== undefined) return wrapEntity(v.$e);
  if (v.$b !== undefined) return wrapBlock(v.$b);
  if (v.$i !== undefined) return ItemStack._from(v.$i);
  if (v.$d !== undefined) return dimensionOf(v.$d);
  if (v.$p !== undefined) return new BlockPermutation(v.$p.ty, v.$p.st);
  const out = {};
  for (const k in v) out[k] = revive(v[k]);
  return out;
}

K.on("init", (m) => { K.apiMajor = m.major || 1; system.currentTick = m.tick || 0; });

K.on("tick", (m) => {
  system.currentTick = m.tick;
  if (m.gone) for (const id of m.gone) entityCache.delete(id);
  // run anything due. sorted by id so two things scheduled for the same tick keep their order
  const due = [];
  for (const [id, r] of runs) if (r.at <= m.tick) due.push([id, r]);
  for (const [id, r] of due) {
    if (r.every) r.at = m.tick + r.every; else runs.delete(id);
    try { r.cb(); } catch (e) { console.error("[system.run] callback threw", e); }
  }
  if (jobs.size) {
    const stop = Date.now() + 2;
    for (const [id, gen] of jobs) {
      try {
        do { if (gen.next().done) { jobs.delete(id); break; } } while (Date.now() < stop);
      } catch (e) { jobs.delete(id); console.error("[system.runJob] generator threw", e); }
      if (Date.now() >= stop) break;
    }
  }
});

K.on("startup", () => {
  const ev = { itemComponentRegistry, blockComponentRegistry, customCommandRegistry };
  system.beforeEvents.startup._fire(ev);
  world.beforeEvents.worldInitialize._fire(ev);
});

// java sent this since forever and nothing listened, so shutdown handlers never ran
K.on("shutdown", () => {
  system.beforeEvents.shutdown._fire({});
});

K.on("load", () => {
  world.afterEvents.worldInitialize._fire({});
  world.afterEvents.worldLoad._fire({});
});

K.on("ev", (m) => {
  const book = m.b ? world.beforeEvents : world.afterEvents;
  let sig = book[m.n];
  if (m.n === "scriptEventReceive") sig = system.afterEvents.scriptEventReceive;
  if (!sig) return undefined;
  const data = revive(m.d || {});
  if (m.b) data.cancel = false;
  // the few events whose data has methods on bedrock
  if (m.n === "projectileHitEntity" || m.n === "projectileHitBlock") {
    data.getEntityHit = () => data.entityHit;
    data.getBlockHit = () => data.blockHit;
  }
  if (m.n === "dataDrivenEntityTrigger") data.getModifiers = () => data.modifiers || [];
  sig._fire(data);
  if (!m.b) return undefined;
  // the writable fields go back too: entityHurt.damage, effectAdd.duration
  return { cancel: !!data.cancel, damage: data.damage, duration: data.duration };
});

// custom component event. comps = [{n: name, p: params}] from the item/block json
K.on("cc", (m) => {
  const book = m.kind === "block" ? blockComps : itemComps;
  const data = revive(m.d || {});
  if (m.cancelable) data.cancel = false;
  for (const c of m.comps) {
    const h = book.get(c.n);
    if (!h) continue;
    let fn = h[m.ev];
    // 1.x called block breaking onPlayerDestroy, 2.x onPlayerBreak
    if (!fn && m.ev === "onPlayerBreak") fn = h.onPlayerDestroy;
    if (typeof fn !== "function") continue;
    try { fn.call(h, data, { params: c.p }); } catch (e) { console.error("[" + c.n + "." + m.ev + "] threw", e); }
  }
  return m.cancelable ? { cancel: !!data.cancel } : undefined;
});

K.on("cmd", (m) => {
  const reg = commands.get(m.name);
  if (!reg) return { status: 1, message: "unknown command " + m.name };
  const origin = { sourceType: m.src ? "Entity" : "Server", sourceEntity: m.src ? wrapEntity(m.src) : undefined, initiator: m.src ? wrapEntity(m.src) : undefined, sourceBlock: undefined };
  const params = (reg.def.mandatoryParameters || []).concat(reg.def.optionalParameters || []);
  const words = splitArgs(m.args || "");
  const args = [];
  for (let i = 0; i < params.length; i++) {
    const p = params[i];
    if (i >= words.length) break;
    let w = words[i];
    switch (p.type) {
      case "Integer": w = parseInt(w, 10); break;
      case "Float": w = parseFloat(w); break;
      case "Boolean": w = w === "true" || w === "1"; break;
      case "Location": {
        const nums = [w, words[i + 1], words[i + 2]].map(Number); words.splice(i + 1, 2);
        w = { x: nums[0], y: nums[1], z: nums[2] }; break;
      }
      case "EntitySelector": case "PlayerSelector": w = (K.ask("select", { sel: w, src: m.src }) || []).map(wrapEntity); break;
      case "ItemType": w = new ItemType(w.includes(":") ? w : "minecraft:" + w); break;
      case "BlockType": w = new BlockType(w.includes(":") ? w : "minecraft:" + w); break;
      case "EntityType": w = new EntityType(w.includes(":") ? w : "minecraft:" + w); break;
      default: break;
    }
    args.push(w);
  }
  try {
    const res = reg.cb(origin, ...args);
    return res ? { status: res.status || 0, message: res.message } : { status: 0 };
  } catch (e) {
    console.error("[command " + m.name + "] threw", e);
    return { status: 1, message: String(e && e.message || e) };
  }
});

function splitArgs(s) {
  const out = [];
  const re = /"([^"]*)"|(\S+)/g;
  let hit;
  while ((hit = re.exec(s))) out.push(hit[1] !== undefined ? hit[1] : hit[2]);
  return out;
}

// server-ui leans on these, it can't import our internals any other way
Object.defineProperty(K, "wrapEntity", { value: wrapEntity });
Object.defineProperty(K, "rawToComponent", { value: rawToComponent });

// ── structures: pack .mcstructure files and what scripts save from the world ──

const sid = (s) => (typeof s === "string" ? (s.includes(":") ? s : "mystructure:" + s) : s.id);

export class Structure {
  constructor(id, size) { this.id = id; this.size = size; }
  isValid() { return K.ask("struct", { a: "get", id: this.id }) !== null; }
  getBlockPermutation() { nope("Structure.getBlockPermutation"); }
  getIsWaterlogged() { nope("Structure.getIsWaterlogged"); }
  setBlockPermutation() { nope("Structure.setBlockPermutation"); }
  saveAs() { nope("Structure.saveAs"); }
  saveToWorld() { nope("Structure.saveToWorld"); }
}

const structureManager = {
  get(id) {
    const r = K.ask("struct", { a: "get", id: sid(id) });
    return r ? new Structure(sid(id), { x: r.x, y: r.y, z: r.z }) : undefined;
  },
  place(structure, dimension, location, options) {
    const o = Object.assign({}, options || {});
    if (o.integritySeed !== undefined) o.integritySeed = String(o.integritySeed);
    write("struct", { a: "place", id: sid(structure), dim: dimId(dimension), x: location.x, y: location.y, z: location.z, o });
  },
  createFromWorld(id, dimension, from, to, options) {
    const o = options || {};
    write("struct", { a: "save", id: sid(id), dim: dimId(dimension), x0: from.x, y0: from.y, z0: from.z, x1: to.x, y1: to.y, z1: to.z,
      blocks: o.includeBlocks !== false, ents: o.includeEntities !== false, mode: o.saveMode || "World" });
    return this.get(id);
  },
  createEmpty() { nope("structureManager.createEmpty"); },
  delete(s) { return write("struct", { a: "del", id: sid(s) }) === true; },
  getWorldStructureIds() { return K.ask("struct", { a: "ids" }) || []; },
  getPackStructureIds() { return K.ask("struct", { a: "ids", pack: true }) || []; },
};
world.structureManager = structureManager;

// ── plain helper classes some packs construct ───────────────────────────────

// the shared half of every volume, written against the iterator so a list volume gets it for free
export class BlockVolumeBase {
  *getBlockLocationIterator() {}
  getCapacity() { let n = 0; for (const _ of this.getBlockLocationIterator()) n++; return n; }
  getMin() { let m; for (const p of this.getBlockLocationIterator()) m = m ? { x: Math.min(m.x, p.x), y: Math.min(m.y, p.y), z: Math.min(m.z, p.z) } : { ...p }; return m; }
  getMax() { let m; for (const p of this.getBlockLocationIterator()) m = m ? { x: Math.max(m.x, p.x), y: Math.max(m.y, p.y), z: Math.max(m.z, p.z) } : { ...p }; return m; }
  getSpan() { const a = this.getMin(), b = this.getMax(); return a ? { x: b.x - a.x + 1, y: b.y - a.y + 1, z: b.z - a.z + 1 } : { x: 0, y: 0, z: 0 }; }
  isInside(p) { const q = blockVec(p); for (const c of this.getBlockLocationIterator()) if (c.x === q.x && c.y === q.y && c.z === q.z) return true; return false; }
  getClosest(p) { return this._pick(p, (a, b) => a < b); }
  getFarthest(p) { return this._pick(p, (a, b) => a > b); }
  _pick(p, better) {
    let best, bd;
    for (const c of this.getBlockLocationIterator()) {
      const d = (c.x - p.x) ** 2 + (c.y - p.y) ** 2 + (c.z - p.z) ** 2;
      if (best === undefined || better(d, bd)) { best = c; bd = d; }
    }
    return best;
  }
}

export class BlockVolume extends BlockVolumeBase {
  constructor(from, to) { super(); this.from = vec(from); this.to = vec(to); }
  getMin() { return { x: Math.min(this.from.x, this.to.x), y: Math.min(this.from.y, this.to.y), z: Math.min(this.from.z, this.to.z) }; }
  getMax() { return { x: Math.max(this.from.x, this.to.x), y: Math.max(this.from.y, this.to.y), z: Math.max(this.from.z, this.to.z) }; }
  getCapacity() { const a = this.getMin(), b = this.getMax(); return (b.x - a.x + 1) * (b.y - a.y + 1) * (b.z - a.z + 1); }
  getSpan() { const a = this.getMin(), b = this.getMax(); return { x: b.x - a.x + 1, y: b.y - a.y + 1, z: b.z - a.z + 1 }; }
  isInside(p) { const a = this.getMin(), b = this.getMax(); return p.x >= a.x && p.x <= b.x && p.y >= a.y && p.y <= b.y && p.z >= a.z && p.z <= b.z; }
  translate(d) { this.from = { x: this.from.x + d.x, y: this.from.y + d.y, z: this.from.z + d.z }; this.to = { x: this.to.x + d.x, y: this.to.y + d.y, z: this.to.z + d.z }; }
  *getBlockLocationIterator() { const a = this.getMin(), b = this.getMax(); for (let x = a.x; x <= b.x; x++) for (let y = a.y; y <= b.y; y++) for (let z = a.z; z <= b.z; z++) yield { x, y, z }; }
  getClosest(p) { const a = this.getMin(), b = this.getMax(); return { x: Math.min(Math.max(Math.floor(p.x), a.x), b.x), y: Math.min(Math.max(Math.floor(p.y), a.y), b.y), z: Math.min(Math.max(Math.floor(p.z), a.z), b.z) }; }
  getFarthest(p) { const a = this.getMin(), b = this.getMax(); const f = (v, lo, hi) => (Math.abs(v - lo) > Math.abs(v - hi) ? lo : hi); return { x: f(p.x, a.x, b.x), y: f(p.y, a.y, b.y), z: f(p.z, a.z, b.z) }; }
  intersects(o) {
    const a = this.getMin(), b = this.getMax(), c = o.getMin(), d = o.getMax();
    if (b.x < c.x || d.x < a.x || b.y < c.y || d.y < a.y || b.z < c.z || d.z < a.z) return BlockVolumeIntersection.Disjoint;
    if (c.x >= a.x && d.x <= b.x && c.y >= a.y && d.y <= b.y && c.z >= a.z && d.z <= b.z) return BlockVolumeIntersection.Contains;
    return BlockVolumeIntersection.Intersects;
  }
  doesLocationTouchFaces(p) {
    const a = this.getMin(), b = this.getMax(), q = blockVec(p);
    if (!this.isInside(q)) return false;
    return q.x === a.x || q.x === b.x || q.y === a.y || q.y === b.y || q.z === a.z || q.z === b.z;
  }
  doesVolumeTouchFaces(o) {
    const a = this.getMin(), b = this.getMax(), c = o.getMin(), d = o.getMax();
    return this.intersects(o) !== BlockVolumeIntersection.Disjoint
      && (c.x <= a.x || d.x >= b.x || c.y <= a.y || d.y >= b.y || c.z <= a.z || d.z >= b.z);
  }
}

export class ListBlockVolume extends BlockVolumeBase {
  constructor(locations) { super(); this._l = new Map(); this.add(locations || []); }
  add(locs) { for (const p of locs) { const b = blockVec(p); this._l.set(b.x + "," + b.y + "," + b.z, b); } }
  remove(locs) { for (const p of locs) { const b = blockVec(p); this._l.delete(b.x + "," + b.y + "," + b.z); } }
  getCapacity() { return this._l.size; }
  *getBlockLocationIterator() { for (const p of this._l.values()) yield { ...p }; }
  translate(d) { const all = [...this._l.values()]; this._l.clear(); this.add(all.map((p) => ({ x: p.x + d.x, y: p.y + d.y, z: p.z + d.z }))); }
}

export class MolangVariableMap {
  constructor() { this._v = {}; }
  setFloat(k, v) { this._v[k] = v; }
  setVector3(k, v) { this._v[k] = v; }
  setColorRGB(k, c) { this._v[k] = c; }
  setColorRGBA(k, c) { this._v[k] = c; }
  setSpeedAndDirection(k, s, d) { this._v[k] = { s, d }; }
}

export class Vector { constructor(x, y, z) { this.x = x; this.y = y; this.z = z; } }
export class CommandResult { constructor(n) { this.successCount = n; } }
export class EntityDamageSource { constructor(o) { Object.assign(this, o); } }
