/// KoperLib Scripting — Lua 5.4 VM (mlua, vendored).
///
/// Each FullPack namespace gets one isolated Lua state.
/// Crash in one script → caught by Rust → logged → game continues.
///
/// API design goal: match or exceed Bedrock Scripting API coverage,
/// while being simpler to use (no module imports required).
use mlua::prelude::*;
use serde_json::Value as JsonValue;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

static NEXT_KFX_HANDLE: AtomicU64 = AtomicU64::new(1);
// ── VM ─────────────────────────────────────────────────────────────────────

pub struct LuaVm {
    lua: Lua,
    loaded: HashMap<String, ()>,
    // error throttling: message → last logged time
    last_errors: HashMap<String, Instant>,
    // millis-since-epoch after which the running chunk gets killed. 0 = no limit.
    // an instruction hook is the only thing that can stop a spinning Lua loop — a watchdog
    // thread can't touch a running VM without corrupting it
    deadline: Arc<AtomicU64>,
    epoch: Instant,
    budget_ms: u64,
}

unsafe impl Send for LuaVm {}

#[derive(Clone, Copy, Default)]
struct ScriptCallbacks {
    cmd: usize,
    query: usize,
}

// all known event handler names — must match getLuaFunctionName() on the Java side
const EVENT_KEYS: &[&str] = &[
    "on_use",
    "on_hit",
    "on_damage",
    "on_death",
    "on_place",
    "on_break",
    "on_spawn",
    "on_tick",
    "on_interact",
    "on_target",
    "on_equip",
    "on_unequip",
    "on_consume",
    "on_craft",
    "on_ai",
];

impl LuaVm {
    pub fn new() -> LuaResult<Self> {
        let lua = Lua::new();
        install_koper_api(&lua)?;
        // per-script handler table: _KOPER_SCRIPTS["abs/path"] = {on_use=fn, ...}
        lua.globals().set("_KOPER_SCRIPTS", lua.create_table()?)?;
        // upcall slot — 0 until Java calls koper_script_set_upcall
        lua.set_app_data::<ScriptCallbacks>(ScriptCallbacks::default());

        let epoch = Instant::now();
        let deadline = Arc::new(AtomicU64::new(0));
        let watch = deadline.clone();
        // every 20k instructions is cheap enough to not show up in profiles but tight enough
        // that a `while true do end` dies in single-digit ms past its budget
        lua.set_hook(
            LuaHookTriggers::new().every_nth_instruction(20_000),
            move |_lua, _dbg| {
                let at = watch.load(Ordering::Relaxed);
                if at != 0 && epoch.elapsed().as_millis() as u64 >= at {
                    return Err(LuaError::RuntimeError(
                        "koperlib: script ran past scriptTimeoutMs and was killed".into(),
                    ));
                }
                Ok(mlua::VmState::Continue)
            },
        );

        Ok(Self {
            lua,
            loaded: HashMap::new(),
            last_errors: HashMap::new(),
            deadline,
            epoch,
            budget_ms: 0,
        })
    }

    fn arm(&self) {
        if self.budget_ms == 0 {
            return;
        }
        let at = self.epoch.elapsed().as_millis() as u64 + self.budget_ms;
        self.deadline.store(at.max(1), Ordering::Relaxed);
    }

    fn disarm(&self) {
        self.deadline.store(0, Ordering::Relaxed);
    }

    pub fn load(&mut self, path: &str) -> LuaResult<()> {
        if self.loaded.contains_key(path) {
            return Ok(());
        }
        let src =
            std::fs::read_to_string(path).map_err(|e| LuaError::RuntimeError(e.to_string()))?;

        // clear any event globals left by a previous load so they don't bleed
        for k in EVENT_KEYS {
            self.lua.globals().set(*k, LuaValue::Nil)?;
        }

        self.lua.load(&src).exec()?;

        // snapshot whatever event handlers this script defined, then wipe globals
        // so the next script loaded into the same VM doesn't see them
        let scripts: LuaTable = self.lua.globals().get("_KOPER_SCRIPTS")?;
        let module: LuaTable = self.lua.create_table()?;
        for k in EVENT_KEYS {
            if let Ok(f) = self.lua.globals().get::<LuaFunction>(*k) {
                module.set(*k, f)?;
                self.lua.globals().set(*k, LuaValue::Nil)?;
            }
        }
        scripts.set(path, module)?;

        self.loaded.insert(path.to_string(), ());
        Ok(())
    }
}

// ── koper standard library ─────────────────────────────────────────────────

fn install_koper_api(lua: &Lua) -> LuaResult<()> {
    let koper = lua.create_table()?;

    // ── koper.log / koper.print ───────────────────────────────────────────
    koper.set(
        "log",
        lua.create_function(|_, (level, msg): (String, String)| {
            eprintln!("[KoperScript/{level}] {msg}");
            Ok(())
        })?,
    )?;
    koper.set(
        "print",
        lua.create_function(|_, msg: String| {
            eprintln!("[KoperScript] {msg}");
            Ok(())
        })?,
    )?;

    // ── koper.math ────────────────────────────────────────────────────────
    let math = lua.create_table()?;
    math.set(
        "vec3",
        lua.create_function(|lua, (x, y, z): (f64, f64, f64)| {
            let t = lua.create_table()?;
            t.set("x", x)?;
            t.set("y", y)?;
            t.set("z", z)?;
            Ok(t)
        })?,
    )?;
    math.set(
        "distance",
        lua.create_function(
            |_, (ax, ay, az, bx, by, bz): (f64, f64, f64, f64, f64, f64)| {
                Ok(((bx - ax).powi(2) + (by - ay).powi(2) + (bz - az).powi(2)).sqrt())
            },
        )?,
    )?;
    math.set(
        "normalize",
        lua.create_function(|lua, (x, y, z): (f64, f64, f64)| {
            let len = (x * x + y * y + z * z).sqrt().max(1e-9);
            let t = lua.create_table()?;
            t.set("x", x / len)?;
            t.set("y", y / len)?;
            t.set("z", z / len)?;
            Ok(t)
        })?,
    )?;
    math.set(
        "lerp",
        lua.create_function(|_, (a, b, t): (f64, f64, f64)| Ok(a + (b - a) * t))?,
    )?;
    // TODO(fullpack-api): raycast — Rust+Java exist, Lua bridge still fake
    math.set(
        "raycast",
        lua.create_function(|lua, _opts: LuaValue| {
            let t = lua.create_table()?;
            t.set("hit", false)?;
            Ok(t)
        })?,
    )?;
    koper.set("math", math)?;

    // ── koper.events ─────────────────────────────────────────────────────
    // Event callbacks are stored Lua-side in a table; Java calls them via
    // koper_script_call("on_entity_damage" etc.) after hoisting the event name.
    let events = lua.create_table()?;
    let listeners: LuaTable = lua.create_table()?;
    let listeners_clone = listeners.clone();
    events.set("_listeners", listeners.clone())?;
    events.set(
        "on",
        lua.create_function(move |lua, (event, cb): (String, LuaFunction)| {
            // one name, many callbacks. used to overwrite, which quietly killed the first listener
            // whenever two scripts in a pack cared about the same thing (kui_demo did exactly that)
            let bucket: LuaTable = match listeners_clone.get::<LuaTable>(event.clone()) {
                Ok(existing) => existing,
                Err(_) => {
                    let fresh = lua.create_table()?;
                    listeners_clone.set(event.clone(), fresh.clone())?;
                    fresh
                }
            };
            bucket.push(cb)?;
            Ok(())
        })?,
    )?;
    events.set(
        "fire",
        lua.create_function(|lua, (event, data): (String, LuaValue)| {
            let koper: LuaTable = lua.globals().get("koper")?;
            let evts: LuaTable = koper.get("events")?;
            let listeners: LuaTable = evts.get("_listeners")?;
            if let Ok(bucket) = listeners.get::<LuaTable>(event) {
                for cb in bucket.sequence_values::<LuaFunction>().flatten() {
                    let _ = cb.call::<LuaValue>(data.clone());
                }
            }
            Ok(())
        })?,
    )?;
    events.set(
        "fire_json",
        lua.create_function(|lua, (event, raw): (String, String)| {
            let json: JsonValue = serde_json::from_str(&raw)
                .map_err(|error| LuaError::RuntimeError(format!("invalid event json: {error}")))?;
            let data = json_to_lua(lua, &json)?;
            let koper: LuaTable = lua.globals().get("koper")?;
            let evts: LuaTable = koper.get("events")?;
            let fire: LuaFunction = evts.get("fire")?;
            fire.call::<()>((event, data))
        })?,
    )?;
    koper.set("events", events)?;

    // ── koper.world ───────────────────────────────────────────────────────
    let world = lua.create_table()?;
    // log alias — koper.world.log(msg)
    world.set(
        "log",
        lua.create_function(|_, msg: String| {
            eprintln!("[Script] {msg}");
            Ok(())
        })?,
    )?;
    world.set(
        "get_block",
        lua.create_function(|lua, (_x, _y, _z): (f64, f64, f64)| {
            let t = lua.create_table()?;
            t.set("id", "minecraft:air")?;
            Ok(t)
        })?,
    )?;
    // set_block writes to command queue — Java applies it to the world
    world.set(
        "set_block",
        lua.create_function(|lua, (x, y, z, id): (f64, f64, f64, String)| {
            push_cmd(&lua, &format!("set_block:{x:.0},{y:.0},{z:.0}:{id}"))
        })?,
    )?;
    // get_time reads from koper._world injected by Java before each call
    world.set(
        "get_time",
        lua.create_function(|lua, ()| {
            let koper: LuaTable = lua.globals().get("koper")?;
            let w: LuaTable = koper
                .get::<LuaTable>("_world")
                .unwrap_or_else(|_| lua.create_table().unwrap());
            Ok(w.get::<i64>("time").unwrap_or(0))
        })?,
    )?;
    // set_time routes through run_cmd since no dedicated handler exists
    world.set(
        "set_time",
        lua.create_function(|lua, t: i64| push_cmd(&lua, &format!("run_cmd:time set {t}")))?,
    )?;
    // is_day reads from koper._world
    world.set(
        "is_day",
        lua.create_function(|lua, ()| {
            let koper: LuaTable = lua.globals().get("koper")?;
            let w: LuaTable = koper
                .get::<LuaTable>("_world")
                .unwrap_or_else(|_| lua.create_table().unwrap());
            Ok(w.get::<bool>("day").unwrap_or(false))
        })?,
    )?;
    world.set(
        "get_entities_in_radius",
        lua.create_function(|lua, (x, y, z, r): (f64, f64, f64, f64)| {
            let args = lua.create_table()?;
            args.set(1, x)?;
            args.set(2, y)?;
            args.set(3, z)?;
            args.set(4, r)?;
            let raw = addon_call(lua, "world", "entities_raw", LuaValue::Table(args))?;
            let out = lua.create_table()?;
            if let LuaValue::Table(list) = raw {
                let mut n = 1;
                for entry in list.sequence_values::<LuaTable>() {
                    let data = entry?;
                    out.set(n, entity_from_data(lua, &data)?)?;
                    n += 1;
                }
            }
            Ok(out)
        })?,
    )?;
    // play_sound(x, y, z, sound_id, vol, pitch)
    world.set(
        "play_sound",
        lua.create_function(
            |lua, (x, y, z, id, vol, pitch): (f64, f64, f64, String, f64, f64)| {
                push_cmd(
                    &lua,
                    &format!("play_sound:{x:.4},{y:.4},{z:.4}:{id}:{vol:.4},{pitch:.4}"),
                )
            },
        )?,
    )?;
    // spawn_particle(id, x, y, z [, count])
    world.set(
        "spawn_particle",
        lua.create_function(
            |lua, (id, x, y, z, count): (String, f64, f64, f64, Option<i32>)| {
                push_cmd(
                    &lua,
                    &format!("particle:{id}:{x:.4},{y:.4},{z:.4}:{}", count.unwrap_or(1)),
                )
            },
        )?,
    )?;
    // spawn_particles alias
    world.set(
        "spawn_particles",
        lua.create_function(
            |lua, (id, x, y, z, count): (String, f64, f64, f64, Option<i32>)| {
                push_cmd(
                    &lua,
                    &format!("particle:{id}:{x:.4},{y:.4},{z:.4}:{}", count.unwrap_or(1)),
                )
            },
        )?,
    )?;
    world.set(
        "summon",
        lua.create_function(|lua, (id, x, y, z): (String, f64, f64, f64)| {
            let _ = push_cmd(&lua, &format!("summon:{id}:{x:.4},{y:.4},{z:.4}"));
            lua.create_table() // returns stub entity
        })?,
    )?;
    // play_sound_at alias (same as play_sound)
    world.set(
        "play_sound_at",
        lua.create_function(
            |lua, (x, y, z, id, vol, pitch): (f64, f64, f64, String, f64, f64)| {
                push_cmd(
                    &lua,
                    &format!("play_sound:{x:.4},{y:.4},{z:.4}:{id}:{vol:.4},{pitch:.4}"),
                )
            },
        )?,
    )?;
    world.set(
        "explosion",
        lua.create_function(
            |lua, (x, y, z, power, fire): (f64, f64, f64, f32, Option<bool>)| {
                push_cmd(
                    &lua,
                    &format!(
                        "explosion:{x:.4},{y:.4},{z:.4}:{power:.4}:{}",
                        if fire.unwrap_or(false) {
                            "true"
                        } else {
                            "false"
                        }
                    ),
                )
            },
        )?,
    )?;
    world.set(
        "set_weather",
        lua.create_function(|lua, weather: String| push_cmd(&lua, &format!("weather:{weather}")))?,
    )?;
    world.set(
        "spawn_xp",
        lua.create_function(|lua, (x, y, z, amount): (f64, f64, f64, i32)| {
            push_cmd(&lua, &format!("spawn_xp:{x:.4},{y:.4},{z:.4}:{amount}"))
        })?,
    )?;
    // fill_blocks(x1,y1,z1, x2,y2,z2, block_id) — fills a region
    world.set(
        "fill_blocks",
        lua.create_function(
            |lua, (x1, y1, z1, x2, y2, z2, id): (i64, i64, i64, i64, i64, i64, String)| {
                push_cmd(&lua, &format!("fill:{x1},{y1},{z1}:{x2},{y2},{z2}:{id}"))
            },
        )?,
    )?;
    // drop_item(x, y, z, item_id [, count]) — spawns item entity
    world.set(
        "drop_item",
        lua.create_function(
            |lua, (x, y, z, item_id, count): (f64, f64, f64, String, Option<i32>)| {
                push_cmd(
                    &lua,
                    &format!(
                        "drop_item:{x:.4},{y:.4},{z:.4}:{item_id}:{}",
                        count.unwrap_or(1)
                    ),
                )
            },
        )?,
    )?;
    // World event registry
    let world_events: LuaTable = lua.create_table()?;
    world.set("_events", world_events.clone())?;
    world.set(
        "fire_event",
        lua.create_function(|lua, (event, data): (String, LuaValue)| {
            let koper: LuaTable = lua.globals().get("koper")?;
            let w: LuaTable = koper.get("world")?;
            let evts: LuaTable = w.get("_events")?;
            evts.set(event, data)?;
            Ok(())
        })?,
    )?;
    world.set(
        "get_events",
        lua.create_function(|lua, ()| {
            let koper: LuaTable = lua.globals().get("koper")?;
            let w: LuaTable = koper.get("world")?;
            let evts: LuaTable = w.get("_events")?;
            // Return proxy with contains()
            let proxy = lua.create_table()?;
            proxy.set("_data", evts.clone())?;
            proxy.set(
                "contains",
                lua.create_function(|_, (this, key): (LuaTable, String)| {
                    let data: LuaTable = this.get("_data")?;
                    Ok(data
                        .get::<LuaValue>(key)
                        .is_ok_and(|v| !matches!(v, LuaValue::Nil)))
                })?,
            )?;
            proxy.set(
                "get",
                lua.create_function(|_, (this, key): (LuaTable, String)| {
                    let data: LuaTable = this.get("_data")?;
                    data.get::<LuaValue>(key)
                })?,
            )?;
            Ok(proxy)
        })?,
    )?;
    koper.set("world", world)?;

    // ── koper.Entity constructor (for mocks/testing) ──────────────────────
    let entity_meta = lua.create_table()?;
    entity_meta.set(
        "new",
        lua.create_function(|lua, id: String| make_entity_table(lua, &id, 20.0, 20.0))?,
    )?;
    koper.set("Entity", entity_meta)?;

    // ── koper.entity — utility namespace for static helpers ───────────────
    // Allows scripts to write: koper.entity.get_pos(entity) etc.
    let entity_utils = lua.create_table()?;
    entity_utils.set(
        "get_pos",
        lua.create_function(|_, e: LuaTable| e.get::<LuaTable>("_pos"))?,
    )?;
    entity_utils.set(
        "get_health",
        lua.create_function(|_, e: LuaTable| e.get::<f64>("_health"))?,
    )?;
    entity_utils.set(
        "get_max_health",
        lua.create_function(|_, e: LuaTable| e.get::<f64>("_max_health"))?,
    )?;
    entity_utils.set(
        "get_id",
        lua.create_function(|_, e: LuaTable| e.get::<String>("_id"))?,
    )?;
    entity_utils.set(
        "get_name",
        lua.create_function(|_, e: LuaTable| e.get::<String>("_name"))?,
    )?;
    entity_utils.set(
        "get_look_dir",
        lua.create_function(|lua, e: LuaTable| {
            e.get::<LuaTable>("_look_dir").or_else(|_| {
                let t = lua.create_table()?;
                t.set("x", 0.0)?;
                t.set("y", 0.0)?;
                t.set("z", 1.0)?;
                Ok(t)
            })
        })?,
    )?;
    entity_utils.set(
        "apply_effect",
        lua.create_function(|lua, (e, eid, ticks, amp): (LuaTable, String, i32, i32)| {
            let uuid: String = e.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("effect:{uuid}:{eid}:{ticks}:{amp}"))
        })?,
    )?;
    entity_utils.set(
        "teleport",
        lua.create_function(|lua, (e, x, y, z): (LuaTable, f64, f64, f64)| {
            let uuid: String = e.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("teleport:{uuid}:{x:.4},{y:.4},{z:.4}"))
        })?,
    )?;
    koper.set("entity", entity_utils)?;

    // ── koper.physics — entity-level physics ─────────────────────────────
    let physics = lua.create_table()?;
    // launch(entity, vx, vy, vz) — adds velocity to entity, works NOW
    physics.set(
        "launch",
        lua.create_function(|lua, (entity, vx, vy, vz): (LuaTable, f64, f64, f64)| {
            let uuid: String = entity.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("launch:{uuid}:{vx:.4},{vy:.4},{vz:.4}"))
        })?,
    )?;
    // spawn_projectile({entity_type, x, y, z, vx, vy, vz}) — fires an entity with velocity
    physics.set(
        "spawn_projectile",
        lua.create_function(|lua, opts: LuaTable| {
            let id: String = opts
                .get("entity_type")
                .unwrap_or_else(|_| "minecraft:arrow".to_string());
            let x: f64 = opts.get("x").unwrap_or(0.0);
            let y: f64 = opts.get("y").unwrap_or(64.0);
            let z: f64 = opts.get("z").unwrap_or(0.0);
            let vx: f64 = opts.get("vx").unwrap_or(0.0);
            let vy: f64 = opts.get("vy").unwrap_or(0.0);
            let vz: f64 = opts.get("vz").unwrap_or(0.0);
            push_cmd(
                &lua,
                &format!("spawn_proj:{id}:{x:.4},{y:.4},{z:.4}:{vx:.4},{vy:.4},{vz:.4}"),
            )
        })?,
    )?;
    // neither of these is built. gravity is per world in the engine, not per region, and
    // there is no ragdoll system at all. saying so beats returning nothing and looking fine
    physics.set(
        "spawn_ragdoll",
        lua.create_function(|_, _opts: LuaValue| {
            Err::<(), _>(LuaError::RuntimeError(
            "koper.physics.spawn_ragdoll is not built yet. spawn a small kontraption and push it \
             with koper.kontra.apply_impulse if you want something to flop around".into()))
        })?,
    )?;
    physics.set(
        "set_gravity_zone",
        lua.create_function(|_, _opts: LuaValue| {
            Err::<(), _>(LuaError::RuntimeError(
            "koper.physics.set_gravity_zone is not built yet: khysics gravity is per dimension, \
             not per region. set \"gravity\" for the dimension in your pack's khysics json".into()))
        })?,
    )?;
    koper.set("physics", physics)?;

    // ── koper.kontra — kontraktion control from Lua ───────────────────────
    // events fire via koper.events.on("kontra_spawn"/tick/destroy, fn)
    // Java calls those when KoperPhysicsEvents fires
    let kontra = lua.create_table()?;
    // apply_force(id, fx, fy, fz) — continuous force this tick
    kontra.set(
        "apply_force",
        lua.create_function(|lua, (id, fx, fy, fz): (i64, f64, f64, f64)| {
            push_cmd(&lua, &format!("kontra_force:{id}:{fx:.4},{fy:.4},{fz:.4}"))
        })?,
    )?;
    // apply_impulse(id, ix, iy, iz) — instant velocity kick
    kontra.set(
        "apply_impulse",
        lua.create_function(|lua, (id, ix, iy, iz): (i64, f64, f64, f64)| {
            push_cmd(
                &lua,
                &format!("kontra_impulse:{id}:{ix:.4},{iy:.4},{iz:.4}"),
            )
        })?,
    )?;
    // self_right(id) — toss up + gentle torque to upright
    kontra.set(
        "self_right",
        lua.create_function(|lua, id: i64| push_cmd(&lua, &format!("kontra_selfright:{id}")))?,
    )?;
    // destroy(id) — nuke it (blocks don't drop back)
    kontra.set(
        "destroy",
        lua.create_function(|lua, id: i64| push_cmd(&lua, &format!("kontra_destroy:{id}")))?,
    )?;
    // restore(id) — land and restore blocks to world
    kontra.set(
        "restore",
        lua.create_function(|lua, id: i64| push_cmd(&lua, &format!("kontra_restore:{id}")))?,
    )?;
    koper.set("kontra", kontra)?;

    // ── koper.particles ───────────────────────────────────────────────────
    let particles = lua.create_table()?;
    particles.set(
        "spawn",
        lua.create_function(
            |lua, (id, x, y, z, count): (String, f64, f64, f64, Option<i32>)| {
                push_cmd(
                    &lua,
                    &format!("particle:{id}:{x:.4},{y:.4},{z:.4}:{}", count.unwrap_or(1)),
                )
            },
        )?,
    )?;
    // burst(id, x, y, z, count, spread) — scatter particles in sphere
    particles.set(
        "burst",
        lua.create_function(
            |lua, (id, x, y, z, count, spread): (String, f64, f64, f64, i32, f64)| {
                push_cmd(
                    &lua,
                    &format!("particle_burst:{id}:{x:.4},{y:.4},{z:.4}:{count}:{spread:.4}"),
                )
            },
        )?,
    )?;
    // line(id, x1,y1,z1, x2,y2,z2, steps) — particles along a line
    particles.set("line", lua.create_function(|lua, (id, x1,y1,z1, x2,y2,z2, steps): (String, f64,f64,f64, f64,f64,f64, i32)| {
        push_cmd(&lua, &format!("particle_line:{id}:{x1:.4},{y1:.4},{z1:.4}:{x2:.4},{y2:.4},{z2:.4}:{steps}"))
    })?)?;
    koper.set("particles", particles)?;

    // ── koper.network — broadcast and targeted messaging ──────────────────
    let kfx = lua.create_table()?;
    kfx.set(
        "spawn",
        lua.create_function(
            |lua, (id, sx, sy, sz, ex, ey, ez): (String, f64, f64, f64, f64, f64, f64)| {
                push_cmd(
                    &lua,
                    &format!("kfx_spawn:{id}:{sx:.4},{sy:.4},{sz:.4}:{ex:.4},{ey:.4},{ez:.4}"),
                )
            },
        )?,
    )?;
    kfx.set(
        "spawn_json",
        lua.create_function(
            |lua, (json, sx, sy, sz, ex, ey, ez): (String, f64, f64, f64, f64, f64, f64)| {
                let safe = cmd_escape(&json);
                push_cmd(
                    &lua,
                    &format!(
                        "kfx_spawn_json:{sx:.4},{sy:.4},{sz:.4}:{ex:.4},{ey:.4},{ez:.4}:{safe}"
                    ),
                )
            },
        )?,
    )?;
    kfx.set(
        "spawn_program",
        lua.create_function(
            |lua, (spec, sx, sy, sz, ex, ey, ez): (LuaTable, f64, f64, f64, f64, f64, f64)| {
                let json = kfx_effect_json(spec)?;
                let safe = cmd_escape(&json);
                push_cmd(
                    &lua,
                    &format!(
                        "kfx_spawn_json:{sx:.4},{sy:.4},{sz:.4}:{ex:.4},{ey:.4},{ez:.4}:{safe}"
                    ),
                )
            },
        )?,
    )?;
    kfx.set(
        "cast",
        lua.create_function(
            |lua, (player, id, range): (LuaTable, String, Option<f64>)| {
                let pos: LuaTable = player.get("_pos")?;
                let look: LuaTable = player.get("_look_dir")?;
                let px: f64 = pos.get("x").unwrap_or(0.0);
                let py: f64 = pos.get("y").unwrap_or(0.0);
                let pz: f64 = pos.get("z").unwrap_or(0.0);
                let lx: f64 = look.get("x").unwrap_or(0.0);
                let ly: f64 = look.get("y").unwrap_or(0.0);
                let lz: f64 = look.get("z").unwrap_or(1.0);
                let r = range.unwrap_or(12.0);
                let sx = px + lx * 0.8;
                let sy = py + 1.55 + ly * 0.8;
                let sz = pz + lz * 0.8;
                let ex = px + lx * r;
                let ey = py + 1.55 + ly * r;
                let ez = pz + lz * r;
                push_cmd(
                    &lua,
                    &format!("kfx_spawn:{id}:{sx:.4},{sy:.4},{sz:.4}:{ex:.4},{ey:.4},{ez:.4}"),
                )
            },
        )?,
    )?;
    kfx.set(
        "cast_json",
        lua.create_function(
            |lua, (player, json, range): (LuaTable, String, Option<f64>)| {
                let pos: LuaTable = player.get("_pos")?;
                let look: LuaTable = player.get("_look_dir")?;
                let px: f64 = pos.get("x").unwrap_or(0.0);
                let py: f64 = pos.get("y").unwrap_or(0.0);
                let pz: f64 = pos.get("z").unwrap_or(0.0);
                let lx: f64 = look.get("x").unwrap_or(0.0);
                let ly: f64 = look.get("y").unwrap_or(0.0);
                let lz: f64 = look.get("z").unwrap_or(1.0);
                let r = range.unwrap_or(12.0);
                let sx = px + lx * 0.8;
                let sy = py + 1.55 + ly * 0.8;
                let sz = pz + lz * 0.8;
                let ex = px + lx * r;
                let ey = py + 1.55 + ly * r;
                let ez = pz + lz * r;
                let safe = cmd_escape(&json);
                push_cmd(
                    &lua,
                    &format!(
                        "kfx_spawn_json:{sx:.4},{sy:.4},{sz:.4}:{ex:.4},{ey:.4},{ez:.4}:{safe}"
                    ),
                )
            },
        )?,
    )?;
    kfx.set(
        "cast_program",
        lua.create_function(
            |lua, (player, spec, range): (LuaTable, LuaTable, Option<f64>)| {
                let pos: LuaTable = player.get("_pos")?;
                let look: LuaTable = player.get("_look_dir")?;
                let px: f64 = pos.get("x").unwrap_or(0.0);
                let py: f64 = pos.get("y").unwrap_or(0.0);
                let pz: f64 = pos.get("z").unwrap_or(0.0);
                let lx: f64 = look.get("x").unwrap_or(0.0);
                let ly: f64 = look.get("y").unwrap_or(0.0);
                let lz: f64 = look.get("z").unwrap_or(1.0);
                let r = range.unwrap_or(12.0);
                let sx = px + lx * 0.8;
                let sy = py + 1.55 + ly * 0.8;
                let sz = pz + lz * 0.8;
                let ex = px + lx * r;
                let ey = py + 1.55 + ly * r;
                let ez = pz + lz * r;
                let json = kfx_effect_json(spec)?;
                let safe = cmd_escape(&json);
                push_cmd(
                    &lua,
                    &format!(
                        "kfx_spawn_json:{sx:.4},{sy:.4},{sz:.4}:{ex:.4},{ey:.4},{ez:.4}:{safe}"
                    ),
                )
            },
        )?,
    )?;
    kfx.set(
        "graph",
        lua.create_function(|lua, id: String| make_kfx_graph(lua, &id))?,
    )?;
    koper.set("kfx", kfx)?;

    let network = lua.create_table()?;
    // broadcast(msg) — sends chat to everyone on the server
    network.set(
        "broadcast",
        lua.create_function(|lua, msg: String| {
            let safe = msg.replace('\n', "\\n").replace(':', "\\:");
            push_cmd(&lua, &format!("net_broadcast:{safe}"))
        })?,
    )?;
    // send_to_player(uuid, msg) — direct message to a specific player
    network.set(
        "send_to_player",
        lua.create_function(|lua, (uuid, msg): (String, String)| {
            let safe = msg.replace('\n', "\\n").replace(':', "\\:");
            push_cmd(&lua, &format!("msg:{uuid}:{safe}"))
        })?,
    )?;
    // send_title_all(title, subtitle) — title screen for everyone
    network.set(
        "send_title_all",
        lua.create_function(|lua, (title, subtitle): (String, Option<String>)| {
            let t = title.replace('\n', " ").replace(':', "\\:");
            let s = subtitle
                .unwrap_or_default()
                .replace('\n', " ")
                .replace(':', "\\:");
            push_cmd(&lua, &format!("title_all:{t}:{s}"))
        })?,
    )?;
    // TODO(fullpack-api): on_receive — custom C2S channel not wired yet
    // channels ride the normal event bus: registering here is the same as
    // koper.events.on("net:<channel>", fn), java fires that when a client sends on it
    network.set(
        "on_receive",
        lua.create_function(|lua, (ch, cb): (String, LuaFunction)| {
            // straight through koper.events.on so it gets the same many-listeners-per-name deal
            let koper: LuaTable = lua.globals().get("koper")?;
            let events: LuaTable = koper.get("events")?;
            let on: LuaFunction = events.get("on")?;
            on.call::<()>((format!("net:{ch}"), cb))?;
            Ok(())
        })?,
    )?;
    koper.set("network", network)?;

    // ── koper.ui ──────────────────────────────────────────────────────────
    // this builder never got an implementation and KUI replaced it. building half a widget
    // toolkit here would duplicate a system that already works, so it points at that instead
    let ui = lua.create_table()?;
    ui.set(
        "screen",
        lua.create_function(|_, _title: String| {
            Err::<(), _>(LuaError::RuntimeError(
            "koper.ui.screen does not exist. define a page in your pack's guis/ folder and open it \
             with koper.gui.open(player, \"ns:page\") — see docs/KUI.md".into()))
        })?,
    )?;
    koper.set("ui", ui)?;

    // ── koper.gui — mutate the open container from a gui handler ───────────
    // slots are 1-indexed to match e.slots. uuid is pulled from koper._ctx.player so Java knows
    // whose open menu to poke. no-op unless a kui container gui is actually open for this player.
    let gui = lua.create_table()?;
    gui.set(
        "consume",
        lua.create_function(|lua, (slot, count): (i64, Option<i64>)| {
            let uuid = gui_uuid(&lua);
            push_cmd(
                &lua,
                &format!("gui_consume:{uuid}:{slot}:{}", count.unwrap_or(1)),
            )
        })?,
    )?;
    gui.set(
        "clear_slot",
        lua.create_function(|lua, slot: i64| {
            let uuid = gui_uuid(&lua);
            push_cmd(&lua, &format!("gui_clear:{uuid}:{slot}"))
        })?,
    )?;
    gui.set(
        "set_slot",
        lua.create_function(|lua, (slot, id, count): (i64, String, Option<i64>)| {
            let uuid = gui_uuid(&lua);
            push_cmd(
                &lua,
                &format!("gui_set_slot:{uuid}:{slot}:{id}:{}", count.unwrap_or(1)),
            )
        })?,
    )?;
    gui.set(
        "clear_all",
        lua.create_function(|lua, ()| {
            let uuid = gui_uuid(&lua);
            push_cmd(&lua, &format!("gui_clear_all:{uuid}"))
        })?,
    )?;
    // set(widget, value) — push a new value into a widget on the open screen/hud (context player)
    gui.set(
        "set",
        lua.create_function(|lua, (widget, value): (String, LuaValue)| {
            let uuid = gui_uuid(&lua);
            push_cmd(
                &lua,
                &format!("gui_wset:{uuid}:{widget}:{}", lua_to_str(&value)),
            )
        })?,
    )?;
    // open(player, id) — pop a gui for a player (works from item/block scripts too)
    gui.set(
        "open",
        lua.create_function(|lua, (player, id): (LuaTable, String)| {
            let uuid: String = player.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("gui_open:{uuid}:{id}"))
        })?,
    )?;
    gui.set(
        "close",
        lua.create_function(|lua, player: LuaTable| {
            let uuid: String = player.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("gui_close:{uuid}"))
        })?,
    )?;
    // hud(player, id, show) — show/hide a layout as a screen overlay
    gui.set(
        "hud",
        lua.create_function(
            |lua, (player, id, show): (LuaTable, String, Option<bool>)| {
                let uuid: String = player.get("_uuid_str").unwrap_or_default();
                push_cmd(
                    &lua,
                    &format!(
                        "gui_hud:{uuid}:{id}:{}",
                        if show.unwrap_or(true) { "1" } else { "0" }
                    ),
                )
            },
        )?,
    )?;
    koper.set("gui", gui)?;

    // ── koper.commands ────────────────────────────────────────────────────
    let commands = lua.create_table()?;
    // java overwrites this at vm setup with the real brigadier backed one. if you ever see this
    // error it means the addon module failed to install, not that the feature is missing
    commands.set("register", lua.create_function(|_, (_id, _opts): (String, LuaValue)| {
        Err::<(), _>(LuaError::RuntimeError(
            "koper.commands.register was not installed by java — the addon module failed to load".into()))
    })?)?;
    // commands.run(cmd) — executes a Minecraft command via server
    commands.set(
        "run",
        lua.create_function(|lua, cmd: String| {
            let safe = cmd.replace('\n', " ");
            push_cmd(&lua, &format!("run_cmd:{safe}"))
        })?,
    )?;
    koper.set("commands", commands)?;

    // ── koper.mixin ───────────────────────────────────────────────────────
    // a mixin inside a hot reloaded pack is a contradiction: class loading happens once, so it
    // would need a full restart to change. this was never going to work and now says so
    let mixin = lua.create_table()?;
    let mixin_dead = |what: &'static str| {
        move |_: &Lua, _: LuaMultiValue| -> LuaResult<()> {
            Err(LuaError::RuntimeError(format!(
                "koper.mixin.{what} does not exist. packs cannot carry mixins because they are hot \
                 reloaded. use a @KoperHook in the pack's java/ folder, or write a real fabric mod")))
        }
    };
    mixin.set("inject", lua.create_function(mixin_dead("inject"))?)?;
    mixin.set("override", lua.create_function(mixin_dead("override"))?)?;
    koper.set("mixin", mixin)?;

    // ── koper.data — in-vm only until NBT bridge ───────────────────────
    // In-VM map simulates persistence; Java replaces with real NBT bridge.
    let data_store: LuaTable = lua.create_table()?;
    let data_store_clone = data_store.clone();
    let data = lua.create_table()?;
    data.set(
        "set",
        lua.create_function(move |_, (key, value): (String, LuaValue)| {
            data_store_clone.set(key, value)?;
            Ok(())
        })?,
    )?;
    let data_store_clone2 = data_store.clone();
    data.set(
        "get",
        lua.create_function(move |_, (key, default): (String, LuaValue)| {
            Ok(data_store_clone2.get::<LuaValue>(key).unwrap_or(default))
        })?,
    )?;
    let data_store_clone3 = data_store.clone();
    data.set(
        "remove",
        lua.create_function(move |_, key: String| {
            data_store_clone3.set(key, LuaValue::Nil)?;
            Ok(())
        })?,
    )?;
    koper.set("data", data)?;

    let addons = lua.create_table()?;
    addons.set(
        "call",
        lua.create_function(
            |lua, (module, function, args): (String, String, LuaValue)| {
                addon_call(lua, &module, &function, args)
            },
        )?,
    )?;
    koper.set("addons", addons)?;
    koper.set(
        "_addon_call",
        lua.create_function(
            |lua, (module, function, args): (String, String, LuaValue)| {
                addon_call(lua, &module, &function, args)
            },
        )?,
    )?;

    // ── koper.register_attribute ──────────────────────────────────────────
    // attributes have to exist before the world loads, so a running script is far too late.
    // erroring beats a silent no-op: the author finds out now instead of wondering for an hour
    koper.set("register_attribute", lua.create_function(|_, (_name, _opts): (String, LuaValue)| {
        Err::<(), _>(LuaError::RuntimeError(
            "koper.register_attribute does not exist: attributes must be registered before the world \
             loads. use the \"attributes\" field in your item or entity json instead".into()))
    })?)?;

    // ── koper._ctx — event context (populated by Java before each call) ──────
    koper.set("_ctx", lua.create_table()?)?;

    // ── koper._cmds — command queue drained by Java after each exec ──────────
    koper.set("_cmds", lua.create_table()?)?;

    lua.globals().set("koper", koper)?;

    // ── Global helpers ────────────────────────────────────────────────────
    // vec3(x,y,z) shorthand
    lua.globals().set(
        "vec3",
        lua.create_function(|lua, (x, y, z): (f64, f64, f64)| {
            let t = lua.create_table()?;
            t.set("x", x)?;
            t.set("y", y)?;
            t.set("z", z)?;
            Ok(t)
        })?,
    )?;

    Ok(())
}

/// Push a command string.
/// If Java registered a synchronous upcall stub → call it immediately (no drain needed, zero alloc).
/// Otherwise fall back to the koper._cmds queue — Java drains after exec.
fn push_cmd(lua: &Lua, cmd: &str) -> LuaResult<()> {
    // app_data stores the raw upcall fn ptr (0 = not registered)
    if let Some(callbacks) = lua.app_data_ref::<ScriptCallbacks>() {
        if callbacks.cmd != 0 {
            // SAFETY: Java set this to a valid Panama upcall stub for (ptr, len)
            // The stub lives in Arena.global() — never freed. cmd is valid for this call frame.
            let upcall: extern "C" fn(*const u8, i32) =
                unsafe { std::mem::transmute(callbacks.cmd) };
            upcall(cmd.as_ptr(), cmd.len() as i32);
            return Ok(());
        }
    }
    // fallback — queue mode (no upcall registered, e.g. unit tests)
    let koper: LuaTable = lua.globals().get("koper")?;
    let cmds: LuaTable = koper.get("_cmds")?;
    let n = cmds.raw_len() + 1;
    cmds.raw_set(n, cmd)?;
    Ok(())
}

fn cmd_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'%' => out.push_str("%25"),
            b':' => out.push_str("%3A"),
            b'\n' => out.push_str("%0A"),
            b'\r' => out.push_str("%0D"),
            _ => out.push(b as char),
        }
    }
    out
}

/// KFX v2 commands never put user-controlled text directly between delimiters.
/// Each UTF-8 field carries its byte length and RFC 4648 Base64 payload, allowing
/// Java to reject truncation/corruption before it parses JSON.
fn push_kfx_command(lua: &Lua, opcode: &str, fields: &[String]) -> LuaResult<()> {
    let mut command = String::from(opcode);
    for field in fields {
        command.push(':');
        command.push_str(&field.len().to_string());
        command.push(':');
        command.push_str(&base64_encode(field.as_bytes()));
    }
    push_cmd(lua, &command)
}

fn base64_encode(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let a = chunk[0];
        let b = chunk.get(1).copied().unwrap_or(0);
        let c = chunk.get(2).copied().unwrap_or(0);
        out.push(ALPHABET[(a >> 2) as usize] as char);
        out.push(ALPHABET[(((a & 0x03) << 4) | (b >> 4)) as usize] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[(((b & 0x0f) << 2) | (c >> 6)) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[(c & 0x3f) as usize] as char
        } else {
            '='
        });
    }
    out
}

fn next_kfx_handle() -> i64 {
    let raw = NEXT_KFX_HANDLE.fetch_add(1, Ordering::Relaxed) & i64::MAX as u64;
    raw.max(1) as i64
}

fn infer_kfx_binding(lua: &Lua, value: LuaValue) -> LuaResult<LuaTable> {
    let typed = lua.create_table()?;
    // { input = "color" } forwards the including graph's own input instead of a fixed constant.
    if let LuaValue::Table(table) = &value {
        if let Ok(name) = table.get::<String>("input") {
            typed.set("input", name)?;
            return Ok(typed);
        }
    }
    let kind = match &value {
        LuaValue::Integer(_) => "integer",
        LuaValue::Number(_) => "number",
        LuaValue::String(text) if text.to_str()?.starts_with('#') => "color",
        LuaValue::String(_) => "text",
        _ => {
            return Err(LuaError::RuntimeError(
                "KFX include bindings support integers, numbers, colors and text".into(),
            ))
        }
    };
    typed.set("type", kind)?;
    typed.set("value", value)?;
    Ok(typed)
}

fn kfx_graph_state(this: &LuaTable) -> LuaResult<LuaTable> {
    this.get("_graph")
}

fn make_kfx_handle(lua: &Lua, id: i64) -> LuaResult<LuaTable> {
    let handle = lua.create_table()?;
    handle.set("id", id)?;
    handle.set("_active", true)?;
    handle.set(
        "stop",
        lua.create_function(|lua, this: LuaTable| {
            let id: i64 = this.get("id")?;
            this.set("_active", false)?;
            push_cmd(lua, &format!("kfx_stop:{id}"))
        })?,
    )?;
    handle.set(
        "set",
        lua.create_function(|lua, (this, name, value): (LuaTable, String, LuaValue)| {
            let id: i64 = this.get("id")?;
            push_kfx_command(
                lua,
                "kfx_handle_set",
                &[id.to_string(), name, lua_value_to_json(value)?],
            )
        })?,
    )?;
    handle.set(
        "reanchor",
        lua.create_function(
            |lua, (this, start, finish): (LuaTable, LuaValue, LuaValue)| {
                let id: i64 = this.get("id")?;
                push_kfx_command(
                    lua,
                    "kfx_handle_anchor",
                    &[
                        id.to_string(),
                        lua_value_to_json(start)?,
                        lua_value_to_json(finish)?,
                    ],
                )
            },
        )?,
    )?;
    handle.set(
        "detach",
        lua.create_function(|lua, this: LuaTable| {
            let id: i64 = this.get("id")?;
            push_kfx_command(lua, "kfx_handle_detach", &[id.to_string()])
        })?,
    )?;
    handle.set(
        "signal",
        lua.create_function(
            |lua, (this, name, data): (LuaTable, String, Option<LuaValue>)| {
                let id: i64 = this.get("id")?;
                push_kfx_command(
                    lua,
                    "kfx_handle_signal",
                    &[
                        id.to_string(),
                        name,
                        lua_value_to_json(data.unwrap_or(LuaValue::Nil))?,
                    ],
                )
            },
        )?,
    )?;
    let meta = lua.create_table()?;
    meta.set("__metatable", "KFX handle")?;
    handle.set_metatable(Some(meta));
    Ok(handle)
}

fn make_kfx_graph(lua: &Lua, id: &str) -> LuaResult<LuaTable> {
    let root = lua.create_table()?;
    root.set("version", 2)?;
    root.set("id", id)?;
    root.set("inputs", lua.create_table()?)?;
    root.set("nodes", lua.create_table()?)?;
    root.set("outputs", lua.create_table()?)?;
    let budget = lua.create_table()?;
    budget.set("max_particles", 256)?;
    budget.set("lifetime", 40)?;
    root.set("budget", budget)?;

    let graph = lua.create_table()?;
    graph.set("_graph", root)?;
    graph.set("_handlers", lua.create_table()?)?;
    graph.set(
        "input",
        lua.create_function(
            |lua, (this, name, kind, default): (LuaTable, String, String, LuaValue)| {
                let root = kfx_graph_state(&this)?;
                let inputs: LuaTable = root.get("inputs")?;
                let input = lua.create_table()?;
                input.set("type", kind)?;
                input.set("default", default)?;
                inputs.set(name, input)?;
                Ok(this)
            },
        )?,
    )?;
    graph.set("include", lua.create_function(|lua, (this, alias, graph_id, bindings): (LuaTable, String, String, Option<LuaTable>)| {
        let root = kfx_graph_state(&this)?;
        let includes = match root.get::<LuaTable>("include") {
            Ok(value) => value,
            Err(_) => { let value = lua.create_table()?; root.set("include", value.clone())?; value }
        };
        let include = lua.create_table()?;
        include.set("as", alias)?;
        include.set("graph", graph_id)?;
        if let Some(bindings) = bindings {
            let typed = lua.create_table()?;
            for pair in bindings.pairs::<String, LuaValue>() {
                let (name, value) = pair?;
                typed.set(name, infer_kfx_binding(lua, value)?)?;
            }
            include.set("bind", typed)?;
        }
        includes.push(include)?;
        Ok(this)
    })?)?;
    graph.set("node", lua.create_function(|lua, (this, node_id, kind, properties): (LuaTable, String, String, Option<LuaTable>)| {
        let root = kfx_graph_state(&this)?;
        let nodes: LuaTable = root.get("nodes")?;
        let node = lua.create_table()?;
        node.set("type", kind)?;
        if let Some(properties) = properties {
            for pair in properties.pairs::<LuaValue, LuaValue>() {
                let (name, value) = pair?;
                node.set(name, value)?;
            }
        }
        nodes.set(node_id, node)?;
        Ok(this)
    })?)?;
    graph.set(
        "link",
        lua.create_function(
            |_, (this, node_id, property, target): (LuaTable, String, String, LuaValue)| {
                let root = kfx_graph_state(&this)?;
                let nodes: LuaTable = root.get("nodes")?;
                let node: LuaTable = nodes.get(node_id)?;
                node.set(property, target)?;
                Ok(this)
            },
        )?,
    )?;
    graph.set(
        "output",
        lua.create_function(|_, (this, node_id): (LuaTable, String)| {
            let root = kfx_graph_state(&this)?;
            let outputs: LuaTable = root.get("outputs")?;
            outputs.push(node_id)?;
            Ok(this)
        })?,
    )?;
    graph.set(
        "on",
        lua.create_function(
            |_, (this, event, callback): (LuaTable, String, LuaFunction)| {
                let handlers: LuaTable = this.get("_handlers")?;
                handlers.set(event, callback)?;
                Ok(this)
            },
        )?,
    )?;
    graph.set(
        "budget",
        lua.create_function(|_, (this, max_particles, lifetime): (LuaTable, i64, i64)| {
            let root = kfx_graph_state(&this)?;
            let budget: LuaTable = root.get("budget")?;
            budget.set("max_particles", max_particles)?;
            budget.set("lifetime", lifetime)?;
            Ok(this)
        })?,
    )?;
    graph.set(
        "register",
        lua.create_function(|lua, this: LuaTable| {
            let json = lua_value_to_json(LuaValue::Table(kfx_graph_state(&this)?))?;
            push_kfx_command(lua, "kfx_graph_declare", &[json])?;
            Ok(this)
        })?,
    )?;
    graph.set(
        "play",
        lua.create_function(|lua, (this, options): (LuaTable, Option<LuaTable>)| {
            let id = next_kfx_handle();
            let graph_json = lua_value_to_json(LuaValue::Table(kfx_graph_state(&this)?))?;
            let options_json = match options {
                Some(value) => lua_value_to_json(LuaValue::Table(value))?,
                None => "{}".to_string(),
            };
            push_kfx_command(
                lua,
                "kfx_graph_play",
                &[id.to_string(), graph_json, options_json],
            )?;
            let handle = make_kfx_handle(lua, id)?;
            let handlers: LuaTable = this.get("_handlers")?;
            let koper: LuaTable = lua.globals().get("koper")?;
            let events: LuaTable = koper.get("events")?;
            let on: LuaFunction = events.get("on")?;
            for pair in handlers.pairs::<String, LuaFunction>() {
                let (event, callback) = pair?;
                let routed = if event.starts_with("kfx:") {
                    event
                } else {
                    format!("kfx:{event}")
                };
                let live_handle = handle.clone();
                let listener = lua.create_function(move |_, data: LuaTable| {
                    let active = live_handle.get::<bool>("_active").unwrap_or(false);
                    let event_handle = data.get::<i64>("handle").unwrap_or(0);
                    let own_handle = live_handle.get::<i64>("id").unwrap_or(0);
                    if active && event_handle == own_handle {
                        callback.call::<()>((data, live_handle.clone()))?;
                    }
                    Ok(())
                })?;
                on.call::<()>((routed, listener))?;
            }
            Ok(handle)
        })?,
    )?;
    let meta = lua.create_table()?;
    meta.set("__metatable", "KFX graph")?;
    graph.set_metatable(Some(meta));
    Ok(graph)
}

// ── query helpers ──────────────────────────────────────────────────────────
// every "read" the api used to fake now goes down this path: rust asks java over the
// synchronous upcall and gets real json back. same bridge koper.pstate rides.

fn uuid_of(t: &LuaTable) -> String {
    t.get::<String>("_uuid_str").unwrap_or_default()
}

fn ask(lua: &Lua, function: &str, args: &[String]) -> LuaResult<LuaValue> {
    let packed = lua.create_table()?;
    for (i, a) in args.iter().enumerate() {
        packed.set(i + 1, a.as_str())?;
    }
    addon_call(lua, "mob", function, LuaValue::Table(packed))
}

fn ask_bool(lua: &Lua, function: &str, args: &[String]) -> LuaResult<bool> {
    Ok(matches!(ask(lua, function, args)?, LuaValue::Boolean(true)))
}

// java hands back plain data; rebuild a real entity object so the methods still work
fn entity_from_data(lua: &Lua, data: &LuaTable) -> LuaResult<LuaTable> {
    let id: String = data
        .get("id")
        .unwrap_or_else(|_| "minecraft:pig".to_string());
    let health: f64 = data.get("health").unwrap_or(20.0);
    let max: f64 = data.get("max_health").unwrap_or(20.0);

    let e = make_entity_table(lua, &id, health, max)?;
    if let Ok(u) = data.get::<String>("uuid") {
        e.set("_uuid_str", u)?;
    }
    if let Ok(n) = data.get::<String>("name") {
        e.set("_name", n)?;
    }
    let pos = lua.create_table()?;
    pos.set("x", data.get::<f64>("x").unwrap_or(0.0))?;
    pos.set("y", data.get::<f64>("y").unwrap_or(64.0))?;
    pos.set("z", data.get::<f64>("z").unwrap_or(0.0))?;
    e.set("_pos", pos)?;
    Ok(e)
}

fn held_stack(lua: &Lua, this: &LuaTable, hand: &str) -> LuaResult<LuaTable> {
    let raw = ask(lua, "held", &[uuid_of(this), hand.to_string()])?;
    let LuaValue::Table(data) = raw else {
        return make_item_table(lua, "minecraft:air", 1);
    };
    let id: String = data
        .get("id")
        .unwrap_or_else(|_| "minecraft:air".to_string());
    let count: i32 = data.get("count").unwrap_or(1);
    let stack = make_item_table(lua, &id, count)?;
    if let Ok(d) = data.get::<i32>("durability") {
        stack.set("_durability", d)?;
    }
    Ok(stack)
}

fn addon_call(lua: &Lua, module: &str, function: &str, args: LuaValue) -> LuaResult<LuaValue> {
    let Some(callbacks) = lua.app_data_ref::<ScriptCallbacks>() else {
        return Ok(LuaValue::Nil);
    };
    if callbacks.query == 0 {
        return Ok(LuaValue::Nil);
    }
    let payload = format!(
        "{{\"module\":\"{}\",\"function\":\"{}\",\"args\":{}}}",
        json_escape(module),
        json_escape(function),
        lua_value_to_json(args)?
    );
    let mut out = vec![0u8; 8192];
    let query: extern "C" fn(*const u8, i32, *mut u8, i32) -> i32 =
        unsafe { std::mem::transmute(callbacks.query) };
    let n = query(
        payload.as_ptr(),
        payload.len() as i32,
        out.as_mut_ptr(),
        out.len() as i32,
    );
    if n <= 0 {
        return Ok(LuaValue::Nil);
    }
    let text = std::str::from_utf8(&out[..n as usize])
        .map_err(|e| LuaError::RuntimeError(e.to_string()))?;
    let parsed: JsonValue = serde_json::from_str(text)
        .map_err(|e| LuaError::RuntimeError(format!("bad addon json: {e}")))?;
    json_to_lua(lua, &parsed)
}

fn json_to_lua(lua: &Lua, value: &JsonValue) -> LuaResult<LuaValue> {
    match value {
        JsonValue::Null => Ok(LuaValue::Nil),
        JsonValue::Bool(b) => Ok(LuaValue::Boolean(*b)),
        JsonValue::Number(n) => {
            if let Some(i) = n.as_i64() {
                Ok(LuaValue::Integer(i))
            } else {
                Ok(LuaValue::Number(n.as_f64().unwrap_or(0.0)))
            }
        }
        JsonValue::String(s) => Ok(LuaValue::String(lua.create_string(s)?)),
        JsonValue::Array(arr) => {
            let t = lua.create_table()?;
            for (i, v) in arr.iter().enumerate() {
                t.raw_set(i + 1, json_to_lua(lua, v)?)?;
            }
            Ok(LuaValue::Table(t))
        }
        JsonValue::Object(obj) => {
            let t = lua.create_table()?;
            for (k, v) in obj {
                t.set(k.as_str(), json_to_lua(lua, v)?)?;
            }
            Ok(LuaValue::Table(t))
        }
    }
}

fn kfx_effect_json(spec: LuaTable) -> LuaResult<String> {
    let has_shape = !matches!(spec.get::<LuaValue>("shape")?, LuaValue::Nil);
    let has_program = !matches!(spec.get::<LuaValue>("program")?, LuaValue::Nil);
    let raw = lua_value_to_json(LuaValue::Table(spec))?;
    if has_shape || has_program {
        Ok(raw)
    } else {
        Ok(format!("{{\"shape\":\"ritual_beam\",\"program\":{raw}}}"))
    }
}

fn lua_value_to_json(value: LuaValue) -> LuaResult<String> {
    match value {
        LuaValue::Nil => Ok("null".to_string()),
        LuaValue::Boolean(v) => Ok(if v { "true" } else { "false" }.to_string()),
        LuaValue::Integer(v) => Ok(v.to_string()),
        LuaValue::Number(v) => Ok(if v.is_finite() {
            v.to_string()
        } else {
            "0".to_string()
        }),
        LuaValue::String(v) => Ok(format!("\"{}\"", json_escape(v.to_str()?.as_ref()))),
        LuaValue::Table(t) => lua_table_to_json(t),
        LuaValue::Function(_)
        | LuaValue::Thread(_)
        | LuaValue::UserData(_)
        | LuaValue::LightUserData(_)
        | LuaValue::Error(_) => Ok("null".to_string()),
        other => Err(LuaError::RuntimeError(format!(
            "cannot encode {:?} as json",
            other.type_name()
        ))),
    }
}

fn lua_table_to_json(t: LuaTable) -> LuaResult<String> {
    let len = t.raw_len();
    if len > 0 && table_is_array(&t, len)? {
        let mut out = String::from("[");
        for i in 1..=len {
            if i > 1 {
                out.push(',');
            }
            out.push_str(&lua_value_to_json(t.raw_get::<LuaValue>(i)?)?);
        }
        out.push(']');
        return Ok(out);
    }

    let mut out = String::from("{");
    let mut first = true;
    for pair in t.pairs::<LuaValue, LuaValue>() {
        let (key, value) = pair?;
        let name = match key {
            LuaValue::String(s) => s.to_str()?.to_string(),
            LuaValue::Integer(i) => i.to_string(),
            LuaValue::Number(n) => n.to_string(),
            _ => continue,
        };
        if !first {
            out.push(',');
        }
        first = false;
        out.push('"');
        out.push_str(&json_escape(&name));
        out.push_str("\":");
        out.push_str(&lua_value_to_json(value)?);
    }
    out.push('}');
    Ok(out)
}

fn table_is_array(t: &LuaTable, len: usize) -> LuaResult<bool> {
    for pair in t.clone().pairs::<LuaValue, LuaValue>() {
        let (key, _) = pair?;
        match key {
            LuaValue::Integer(i) if i >= 1 && (i as usize) <= len => {}
            _ => return Ok(false),
        }
    }
    Ok(true)
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 4);
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

/// Pull the acting player's uuid out of koper._ctx.player (set by Java before a gui event).
/// Empty string if there's no player in context — the Java side then just drops the command.
fn gui_uuid(lua: &Lua) -> String {
    (|| -> LuaResult<String> {
        let koper: LuaTable = lua.globals().get("koper")?;
        let ctx: LuaTable = koper.get("_ctx")?;
        let player: LuaTable = ctx.get("player")?;
        player.get::<String>("_uuid_str")
    })()
    .unwrap_or_default()
}

/// Stringify a Lua value for a command payload (numbers, bools, strings).
fn lua_to_str(v: &LuaValue) -> String {
    match v {
        LuaValue::String(s) => String::from_utf8_lossy(&s.as_bytes()).into_owned(),
        LuaValue::Integer(i) => i.to_string(),
        LuaValue::Number(n) => n.to_string(),
        LuaValue::Boolean(b) => b.to_string(),
        _ => String::new(),
    }
}

/// Build an entity table that scripts receive as the `entity` / `player` argument.
/// Java will eventually pass real values via Panama; this shape is the contract.
pub fn make_entity_table(lua: &Lua, id: &str, health: f64, max_health: f64) -> LuaResult<LuaTable> {
    let e = lua.create_table()?;
    e.set("_id", id)?;
    e.set("_health", health)?;
    e.set("_max_health", max_health)?;
    e.set("_entity_id", 0)?;
    e.set("_pos", {
        let t = lua.create_table()?;
        t.set("x", 0.0)?;
        t.set("y", 64.0)?;
        t.set("z", 0.0)?;
        t
    })?;
    e.set("_name", id)?;
    e.set("_data", lua.create_table()?)?;
    e.set("_memory", lua.create_table()?)?;

    // ── getters ───────────────────────────────────────────────────────────
    e.set(
        "get_id",
        lua.create_function(|_, this: LuaTable| this.get::<String>("_id"))?,
    )?;
    e.set(
        "get_entity_id",
        lua.create_function(|_, this: LuaTable| this.get::<i64>("_entity_id"))?,
    )?;
    e.set(
        "get_name",
        lua.create_function(|_, this: LuaTable| this.get::<String>("_name"))?,
    )?;
    e.set(
        "set_name",
        lua.create_function(|lua, (this, name): (LuaTable, String)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            this.set("_name", name.clone())?;
            let safe = name.replace('\n', " ").replace(':', "\\:");
            push_cmd(&lua, &format!("set_name:{uuid}:{safe}"))
        })?,
    )?;
    e.set(
        "get_health",
        lua.create_function(|_, this: LuaTable| this.get::<f64>("_health"))?,
    )?;
    e.set(
        "get_max_health",
        lua.create_function(|_, this: LuaTable| this.get::<f64>("_max_health"))?,
    )?;
    e.set(
        "set_health",
        lua.create_function(|lua, (this, v): (LuaTable, f64)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            this.set("_health", v)?;
            push_cmd(&lua, &format!("set_health:{uuid}:{v:.4}"))
        })?,
    )?;
    e.set(
        "get_pos",
        lua.create_function(|_, this: LuaTable| this.get::<LuaTable>("_pos"))?,
    )?;

    // ── actions ───────────────────────────────────────────────────────────
    e.set(
        "heal",
        lua.create_function(|_, (this, amt): (LuaTable, f64)| {
            let hp: f64 = this.get("_health")?;
            let max: f64 = this.get("_max_health")?;
            this.set("_health", (hp + amt).min(max))?;
            Ok(())
        })?,
    )?;
    e.set(
        "damage",
        lua.create_function(|_, (this, amt, _src): (LuaTable, f64, LuaValue)| {
            let hp: f64 = this.get("_health")?;
            this.set("_health", (hp - amt).max(0.0))?;
            Ok(())
        })?,
    )?;
    e.set(
        "kill",
        lua.create_function(|lua, this: LuaTable| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            this.set("_health", 0.0)?;
            push_cmd(&lua, &format!("kill_entity:{uuid}"))
        })?,
    )?;

    e.set(
        "add_effect",
        lua.create_function(
            |lua, (this, eid, ticks, amp): (LuaTable, String, i32, i32)| {
                let uuid: String = this.get("_uuid_str").unwrap_or_default();
                push_cmd(&lua, &format!("effect:{uuid}:{eid}:{ticks}:{amp}"))
            },
        )?,
    )?;
    e.set(
        "remove_effect",
        lua.create_function(|lua, (this, eid): (LuaTable, String)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("effect_remove:{uuid}:{eid}"))
        })?,
    )?;
    // reads go through the synchronous query bridge, same one pstate uses. these used to lie.
    e.set(
        "has_effect",
        lua.create_function(|lua, (this, id): (LuaTable, String)| {
            Ok(ask_bool(lua, "has_effect", &[uuid_of(&this), id])?)
        })?,
    )?;

    e.set(
        "get_target",
        lua.create_function(|lua, this: LuaTable| {
            let raw = ask(lua, "target", &[uuid_of(&this)])?;
            match raw {
                LuaValue::Table(t) => Ok(LuaValue::Table(entity_from_data(lua, &t)?)),
                _ => Ok(LuaValue::Nil),
            }
        })?,
    )?;

    e.set(
        "set_target",
        lua.create_function(|lua, (this, tgt): (LuaTable, LuaValue)| {
            let who = uuid_of(&this);
            let target = match &tgt {
                LuaValue::Table(t) => uuid_of(t),
                LuaValue::String(s) => s.to_str()?.to_string(),
                _ => String::new(),
            };
            push_cmd(&lua, &format!("set_target:{who}:{target}"))
        })?,
    )?;

    e.set(
        "move_to",
        lua.create_function(|lua, (this, pos, spd): (LuaTable, LuaTable, Option<f64>)| {
            let uuid = uuid_of(&this);
            let x: f64 = pos.get("x").unwrap_or(0.0);
            let y: f64 = pos.get("y").unwrap_or(0.0);
            let z: f64 = pos.get("z").unwrap_or(0.0);
            push_cmd(
                &lua,
                &format!(
                    "move_to:{uuid}:{x:.4},{y:.4},{z:.4}:{:.4}",
                    spd.unwrap_or(1.0)
                ),
            )
        })?,
    )?;

    e.set(
        "attack",
        lua.create_function(|lua, (this, tgt): (LuaTable, LuaTable)| {
            push_cmd(
                &lua,
                &format!("attack:{}:{}", uuid_of(&this), uuid_of(&tgt)),
            )
        })?,
    )?;

    e.set(
        "play_animation",
        lua.create_function(|lua, (this, name): (LuaTable, String)| {
            let safe = name.replace(':', "_");
            push_cmd(&lua, &format!("play_anim:{}:{safe}", uuid_of(&this)))
        })?,
    )?;

    e.set(
        "get_bone_transforms",
        lua.create_function(|lua, this: LuaTable| ask(lua, "bones", &[uuid_of(&this)]))?,
    )?;
    e.set(
        "get_look_dir",
        lua.create_function(|lua, this: LuaTable| {
            // Return _look_dir if Java injected it, otherwise default forward
            if let Ok(ld) = this.get::<LuaTable>("_look_dir") {
                return Ok(ld);
            }
            let t = lua.create_table()?;
            t.set("x", 0.0)?;
            t.set("y", 0.0)?;
            t.set("z", 1.0)?;
            Ok(t)
        })?,
    )?;
    // get_look_direction alias
    e.set(
        "get_look_direction",
        lua.create_function(|lua, this: LuaTable| {
            if let Ok(ld) = this.get::<LuaTable>("_look_dir") {
                return Ok(ld);
            }
            let t = lua.create_table()?;
            t.set("x", 0.0)?;
            t.set("y", 0.0)?;
            t.set("z", 1.0)?;
            Ok(t)
        })?,
    )?;
    // teleport(x, y, z)
    e.set(
        "teleport",
        lua.create_function(|lua, (this, x, y, z): (LuaTable, f64, f64, f64)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("teleport:{uuid}:{x:.4},{y:.4},{z:.4}"))
        })?,
    )?;
    // send_message(text) — chat message to player
    e.set(
        "send_message",
        lua.create_function(|lua, (this, text): (LuaTable, String)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            let safe = text.replace('\n', "\\n").replace(':', "\\:");
            push_cmd(&lua, &format!("msg:{uuid}:{safe}"))
        })?,
    )?;
    // give(item_id [, count])
    e.set(
        "give",
        lua.create_function(
            |lua, (this, item_id, count): (LuaTable, String, Option<i32>)| {
                let uuid: String = this.get("_uuid_str").unwrap_or_default();
                push_cmd(
                    &lua,
                    &format!("give:{uuid}:{item_id}:{}", count.unwrap_or(1)),
                )
            },
        )?,
    )?;

    e.set(
        "has_tag",
        lua.create_function(|lua, (this, tag): (LuaTable, String)| {
            Ok(ask_bool(lua, "has_tag", &[uuid_of(&this), tag])?)
        })?,
    )?;
    e.set(
        "add_tag",
        lua.create_function(|lua, (this, tag): (LuaTable, String)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("tag_add:{uuid}:{tag}"))
        })?,
    )?;
    e.set(
        "remove_tag",
        lua.create_function(|lua, (this, tag): (LuaTable, String)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("tag_remove:{uuid}:{tag}"))
        })?,
    )?;

    e.set(
        "say",
        lua.create_function(|lua, (this, text, _opts): (LuaTable, String, LuaValue)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            let safe = text.replace('\n', "\\n").replace(':', "\\:");
            push_cmd(&lua, &format!("msg:{uuid}:{safe}"))
        })?,
    )?;
    // get_uuid returns the real injected UUID string
    e.set(
        "get_uuid",
        lua.create_function(|_, this: LuaTable| {
            Ok(this
                .get::<String>("_uuid_str")
                .unwrap_or_else(|_| "00000000-0000-0000-0000-000000000000".into()))
        })?,
    )?;

    // entity state flags — Java injects _sneaking/_sprinting/_on_ground/_in_water before each call
    e.set(
        "is_sneaking",
        lua.create_function(|_, this: LuaTable| {
            Ok(this.get::<bool>("_sneaking").unwrap_or(false))
        })?,
    )?;
    e.set(
        "is_sprinting",
        lua.create_function(|_, this: LuaTable| {
            Ok(this.get::<bool>("_sprinting").unwrap_or(false))
        })?,
    )?;
    e.set(
        "is_on_ground",
        lua.create_function(|_, this: LuaTable| {
            Ok(this.get::<bool>("_on_ground").unwrap_or(true))
        })?,
    )?;
    e.set(
        "is_in_water",
        lua.create_function(|_, this: LuaTable| {
            Ok(this.get::<bool>("_in_water").unwrap_or(false))
        })?,
    )?;
    e.set(
        "is_player",
        lua.create_function(|_, this: LuaTable| {
            Ok(this.get::<bool>("_is_player").unwrap_or(false))
        })?,
    )?;

    // ── per-entity persistent data ─────────────────────────────────────────
    e.set(
        "get_data",
        lua.create_function(|_, (this, key): (LuaTable, String)| {
            let store: LuaTable = this.get("_data")?;
            Ok(store.get::<LuaValue>(key).unwrap_or(LuaValue::Nil))
        })?,
    )?;
    e.set(
        "set_data",
        lua.create_function(|_, (this, key, value): (LuaTable, String, LuaValue)| {
            let store: LuaTable = this.get("_data")?;
            store.set(key, value)?;
            Ok(())
        })?,
    )?;
    e.set(
        "remove_data",
        lua.create_function(|_, (this, key): (LuaTable, String)| {
            let store: LuaTable = this.get("_data")?;
            store.set(key, LuaValue::Nil)?;
            Ok(())
        })?,
    )?;

    // ── memory system ─────────────────────────────────────────────────────
    e.set(
        "get_memory",
        lua.create_function(|_, (this, uuid): (LuaTable, String)| {
            let mem: LuaTable = this.get("_memory")?;
            Ok(mem.get::<LuaValue>(uuid).unwrap_or(LuaValue::Nil))
        })?,
    )?;
    e.set(
        "remember",
        lua.create_function(|_, (this, uuid, data): (LuaTable, String, LuaValue)| {
            let mem: LuaTable = this.get("_memory")?;
            mem.set(uuid, data)?;
            Ok(())
        })?,
    )?;

    // ── player-only extras ─────────────────────────────────────────────────
    // send_message already defined above with proper push_cmd — don't overwrite it here
    e.set(
        "give_item",
        lua.create_function(
            |lua, (this, item_id, count): (LuaTable, String, Option<i32>)| {
                let uuid: String = this.get("_uuid_str").unwrap_or_default();
                push_cmd(
                    &lua,
                    &format!("give:{uuid}:{item_id}:{}", count.unwrap_or(1)),
                )
            },
        )?,
    )?;
    e.set(
        "get_inventory",
        lua.create_function(|lua, this: LuaTable| ask(lua, "inventory", &[uuid_of(&this)]))?,
    )?;
    e.set(
        "kick",
        lua.create_function(|lua, (this, reason): (LuaTable, String)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            let safe = reason.replace('\n', " ").replace(':', "\\:");
            push_cmd(&lua, &format!("kick:{uuid}:{safe}"))
        })?,
    )?;
    e.set(
        "set_gamemode",
        lua.create_function(|lua, (this, mode): (LuaTable, String)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("set_gamemode:{uuid}:{mode}"))
        })?,
    )?;
    e.set(
        "ignite",
        lua.create_function(|lua, (this, ticks): (LuaTable, i32)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("ignite:{uuid}:{ticks}"))
        })?,
    )?;
    e.set(
        "freeze",
        lua.create_function(|lua, (this, ticks): (LuaTable, i32)| {
            let uuid: String = this.get("_uuid_str").unwrap_or_default();
            push_cmd(&lua, &format!("freeze:{uuid}:{ticks}"))
        })?,
    )?;
    e.set(
        "show_title",
        lua.create_function(
            |lua, (this, title, subtitle): (LuaTable, String, Option<String>)| {
                let uuid: String = this.get("_uuid_str").unwrap_or_default();
                let t = title.replace('\n', " ").replace(':', "\\:");
                let s = subtitle
                    .unwrap_or_default()
                    .replace('\n', " ")
                    .replace(':', "\\:");
                push_cmd(&lua, &format!("title:{uuid}:{t}:{s}"))
            },
        )?,
    )?;

    // ── item held ─────────────────────────────────────────────────────────
    e.set(
        "get_main_hand",
        lua.create_function(|lua, this: LuaTable| held_stack(lua, &this, "main"))?,
    )?;
    e.set(
        "get_off_hand",
        lua.create_function(|lua, this: LuaTable| held_stack(lua, &this, "off"))?,
    )?;

    Ok(e)
}

/// Build an item table (held item, inventory slot, etc.)
pub fn make_item_table(lua: &Lua, id: &str, count: i32) -> LuaResult<LuaTable> {
    let t = lua.create_table()?;
    t.set("_id", id)?;
    t.set("_count", count)?;
    t.set("_data", lua.create_table()?)?;

    t.set(
        "get_id",
        lua.create_function(|_, this: LuaTable| this.get::<String>("_id"))?,
    )?;
    t.set(
        "get_count",
        lua.create_function(|_, this: LuaTable| this.get::<i32>("_count"))?,
    )?;
    t.set(
        "get_durability",
        lua.create_function(|_, _this: LuaTable| Ok(100i32))?,
    )?;
    // stacks don't carry an identity of their own, so we act on whichever hand holds them.
    // _holder/_hand are stamped by java when the stack is handed to a handler
    t.set(
        "set_durability",
        lua.create_function(|lua, (this, v): (LuaTable, i32)| {
            let holder: String = this.get("_holder").unwrap_or_default();
            if holder.is_empty() {
                return Ok(());
            }
            let hand: String = this.get("_hand").unwrap_or_else(|_| "main".to_string());
            this.set("_durability", v)?;
            push_cmd(&lua, &format!("set_dura:{holder}:{hand}:{v}"))
        })?,
    )?;

    t.set(
        "get_holder",
        lua.create_function(|lua, this: LuaTable| {
            let holder: String = this.get("_holder").unwrap_or_default();
            if holder.is_empty() {
                return Ok(LuaValue::Nil);
            }
            let raw = ask(lua, "by_uuid", &[holder])?;
            match raw {
                LuaValue::Table(t) => Ok(LuaValue::Table(entity_from_data(lua, &t)?)),
                _ => Ok(LuaValue::Nil),
            }
        })?,
    )?;
    t.set(
        "get_data",
        lua.create_function(|_, (this, key): (LuaTable, String)| {
            let store: LuaTable = this.get("_data")?;
            Ok(store.get::<LuaValue>(key).unwrap_or(LuaValue::Nil))
        })?,
    )?;
    t.set(
        "set_data",
        lua.create_function(|_, (this, key, val): (LuaTable, String, LuaValue)| {
            let store: LuaTable = this.get("_data")?;
            store.set(key, val)?;
            Ok(())
        })?,
    )?;
    Ok(t)
}

// ── C-ABI exports ─────────────────────────────────────────────────────────

fn vm_from_handle(handle: i64) -> Option<&'static mut LuaVm> {
    if handle == 0 {
        return None;
    }
    Some(unsafe { &mut *(handle as *mut LuaVm) })
}

pub fn scripting_create_vm() -> i64 {
    match LuaVm::new() {
        Ok(vm) => Box::into_raw(Box::new(vm)) as i64,
        Err(e) => {
            eprintln!("[KoperScripting] Failed to create VM: {e}");
            0
        }
    }
}

pub fn scripting_destroy_vm(handle: i64) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut LuaVm));
        }
    }
}

pub fn scripting_load(handle: i64, path: &str) -> i32 {
    match vm_from_handle(handle) {
        None => 1,
        Some(vm) => match vm.load(path) {
            Ok(()) => 0,
            Err(e) => {
                eprintln!("[KoperScripting] Load error in '{path}': {e}");
                1
            }
        },
    }
}

pub fn scripting_exec(handle: i64, code: &str) -> i32 {
    match vm_from_handle(handle) {
        None => 1,
        Some(vm) => {
            vm.arm();
            let result = vm.lua.load(code).exec();
            vm.disarm();
            match result {
                Ok(()) => 0,
                Err(e) => {
                    let msg = e.to_string();
                    let now = Instant::now();
                    let throttle = Duration::from_secs(5);
                    let should_log = vm
                        .last_errors
                        .get(&msg)
                        .map(|t| now.duration_since(*t) >= throttle)
                        .unwrap_or(true);
                    if should_log {
                        eprintln!("[KoperScripting] Exec error: {msg}");
                        vm.last_errors.insert(msg, now);
                    }
                    1
                }
            }
        }
    }
}

/// Wall-clock budget for one exec, in millis. 0 disables the kill switch.
/// Java pushes this from KoperLibConfig.scriptTimeoutMs right after the VM is created.
pub fn scripting_set_timeout(handle: i64, ms: i64) {
    if let Some(vm) = vm_from_handle(handle) {
        vm.budget_ms = if ms <= 0 { 0 } else { ms as u64 };
    }
}

/// Register a Panama upcall stub so Rust can call Java synchronously from push_cmd.
/// fn_ptr is the raw address of a `void(const uint8_t*, int32_t)` Java upcall stub.
/// Pass 0 to unregister (falls back to queue mode).
pub fn scripting_set_upcall(handle: i64, fn_ptr: usize) {
    if let Some(vm) = vm_from_handle(handle) {
        let mut callbacks = vm
            .lua
            .app_data_ref::<ScriptCallbacks>()
            .map(|v| *v)
            .unwrap_or_default();
        callbacks.cmd = fn_ptr;
        vm.lua.set_app_data::<ScriptCallbacks>(callbacks);
    }
}

pub fn scripting_set_query_upcall(handle: i64, fn_ptr: usize) {
    if let Some(vm) = vm_from_handle(handle) {
        let mut callbacks = vm
            .lua
            .app_data_ref::<ScriptCallbacks>()
            .map(|v| *v)
            .unwrap_or_default();
        callbacks.query = fn_ptr;
        vm.lua.set_app_data::<ScriptCallbacks>(callbacks);
    }
}

/// Drains koper._cmds from the VM: serializes all entries as newline-separated strings,
/// writes them to buf (null-terminated), clears the list, and returns the byte count
/// (excluding null terminator). Returns -1 on error. buf must be at least buf_len bytes.
pub fn scripting_drain_commands(handle: i64, buf: *mut u8, buf_len: usize) -> i32 {
    if buf.is_null() || buf_len == 0 {
        return -1;
    }
    let vm = match vm_from_handle(handle) {
        None => return -1,
        Some(v) => v,
    };
    let result: LuaResult<String> = (|| {
        let koper: LuaTable = vm.lua.globals().get("koper")?;
        let cmds: LuaTable = koper.get("_cmds")?;
        let mut out = String::new();
        for v in cmds.sequence_values::<String>() {
            if let Ok(s) = v {
                if !out.is_empty() {
                    out.push('\n');
                }
                out.push_str(&s);
            }
        }
        // Clear the list
        koper.set("_cmds", vm.lua.create_table()?)?;
        Ok(out)
    })();
    match result {
        Err(e) => {
            eprintln!("[KoperScripting] drain_commands error: {e}");
            -1
        }
        Ok(s) => {
            let bytes = s.as_bytes();
            let write_len = bytes.len().min(buf_len.saturating_sub(1));
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), buf, write_len);
                *buf.add(write_len) = 0;
            }
            write_len as i32
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn markdown_fences(markdown: &str, language: &str) -> Vec<String> {
        let marker = format!("```{language}");
        let mut examples = Vec::new();
        let mut remaining = markdown;
        while let Some(marker_start) = remaining.find(&marker) {
            let after_marker = &remaining[marker_start + marker.len()..];
            let Some(body_start) = after_marker.find('\n') else { break };
            let body = &after_marker[body_start + 1..];
            let Some(body_end) = body.find("```") else { break };
            examples.push(body[..body_end].trim().to_owned());
            remaining = &body[body_end + 3..];
        }
        examples
    }

    // used to overwrite silently, so this is the whole point
    #[test]
    fn every_listener_on_one_name_gets_called() {
        let vm = scripting_create_vm();
        assert!(vm != 0, "no vm");

        let code = r#"
            koper._hits = 0
            koper.events.on("test:ping", function(e) koper._hits = koper._hits + 1 end)
            koper.events.on("test:ping", function(e) koper._hits = koper._hits + 10 end)
            koper.events.on("test:other", function(e) koper._hits = koper._hits + 100 end)
            koper.events.fire("test:ping", {})
            if koper._hits ~= 11 then error("wanted 11 got " .. tostring(koper._hits)) end
        "#;
        assert_eq!(scripting_exec(vm, code), 0, "lua blew up, see stderr");

        scripting_destroy_vm(vm);
    }

    #[test]
    fn firing_a_name_nobody_listens_to_is_fine() {
        let vm = scripting_create_vm();
        assert_eq!(
            scripting_exec(vm, "koper.events.fire(\"test:nobody\", {})"),
            0
        );
        scripting_destroy_vm(vm);
    }

    #[test]
    fn kfx_builder_emits_graph_play_and_returns_a_live_handle() {
        let vm = scripting_create_vm();
        let code = r##"
            local fx = koper.kfx.graph("test:lua_spell")
            fx:include("ring", "test:json_ring", { accent = "#55ccff" })
            fx:node("sparks", "koper_lib:render/particles", { count = 30 })
            fx:output("sparks")
            fx:budget(64, 40)
            local impacts = 0
            fx:on("impact", function(event, handle)
                impacts = impacts + 1
                if event.handle ~= handle.id then error("handler got wrong handle") end
            end)
            local h = fx:play({ start = {0,1,0}, finish = {0,1,8} })
            if type(h.id) ~= "number" or h.id <= 0 then error("missing positive handle") end
            koper.events.fire("kfx:impact", { handle = h.id })
            koper.events.fire("kfx:impact", { handle = h.id + 1 })
            if impacts ~= 1 then error("impact route did not filter its live handle") end
            h:signal("charge:phase", { amount = 0.7, text = "a:b" })
        "##;

        assert_eq!(scripting_exec(vm, code), 0, "lua builder failed");
        let mut bytes = vec![0u8; 32 * 1024];
        let written = scripting_drain_commands(vm, bytes.as_mut_ptr(), bytes.len());
        assert!(written > 0);
        let commands = std::str::from_utf8(&bytes[..written as usize]).unwrap();
        assert!(commands.contains("kfx_graph_play:"), "{commands}");
        assert!(commands.contains("kfx_handle_signal:"), "{commands}");
        assert!(
            !commands.contains("a:b"),
            "new commands must not carry raw delimiter fields"
        );
        scripting_destroy_vm(vm);
    }

    #[test]
    fn kfx_json_events_reach_lua_as_structured_data() {
        let vm = scripting_create_vm();
        let code = r#"
            local seen = nil
            koper.events.on("kfx:impact", function(event) seen = event end)
            koper.events.fire_json("kfx:impact", '{"handle":9,"position":{"x":1.5},"kind":"block"}')
            if seen.handle ~= 9 or seen.position.x ~= 1.5 or seen.kind ~= "block" then
                error("impact event lost structured fields")
            end
        "#;
        assert_eq!(scripting_exec(vm, code), 0);
        scripting_destroy_vm(vm);
    }

    #[test]
    fn every_kfx_guide_lua_fence_executes_in_the_real_vm() {
        let guide = include_str!("../../../docs/KFX_PARTICLE_ENGINE.md");
        let examples = markdown_fences(guide, "lua");
        assert!(!examples.is_empty(), "KFX guide needs Lua examples");

        for (index, example) in examples.iter().enumerate() {
            let vm = scripting_create_vm();
            let prelude = r#"
                local function fake_entity(id)
                    return {
                        _pos = {x=0, y=64, z=0},
                        _look_dir = {x=0, y=0, z=1},
                        get_entity_id = function(self) return id end
                    }
                end
                player = fake_entity(1)
                target = fake_entity(2)
                cast_seed = 8844
                sx, sy, sz, ex, ey, ez = 0, 64, 0, 0, 64, 8
            "#;
            assert_eq!(scripting_exec(vm, prelude), 0, "Lua docs prelude failed");
            assert_eq!(
                scripting_exec(vm, example),
                0,
                "KFX guide Lua fence {} failed:\n{}",
                index + 1,
                example
            );
            scripting_destroy_vm(vm);
        }
    }
}
