// bedrock client entity, run natively. one Def per entity type (animations, controllers, render
// controllers, scripts, all molang compiled once), one Inst per mob on screen. java calls tick
// once per frame with the query slots filled and gets bone matrices back, same layout kodel's
// own sampler writes, so KodelModelRender and kender draw it without knowing where it came from
use crate::{quat_from_euler, quat_to_mat4, Mat4};
use koperlib_molang::{Book, Ctx, Program, Val};
use serde_json::Value as J;
use std::collections::HashMap;
use std::sync::Arc;

// ── compiled definition ─────────────────────────────────────────────────────

struct Bone {
    parent: i32,
    pivot: [f32; 3],
    rot: [f32; 3], // bind euler, degrees
    pos: [f32; 3],
}

#[derive(Clone, Copy, PartialEq)]
enum Lerp {
    Linear,
    Catmull,
    Step,
}

// one axis triple of molang, most of the time three constants
struct Vec3P([Program; 3]);

struct Key {
    t: f32,
    pre: Vec3P,
    post: Vec3P,
    lerp: Lerp,
}

enum Chan {
    Fixed(Vec3P),
    Keys(Vec<Key>),
}

struct BoneAnim {
    bone: usize,
    rot: Option<Chan>,
    pos: Option<Chan>,
    scale: Option<Chan>,
    // relative_to.rotation == "entity": undo the parents' rotation
    rot_rel_entity: bool,
}

#[derive(Clone, Copy, PartialEq)]
enum LoopMode {
    Once,
    Loop,
    Hold,
}

struct Anim {
    length: f32,
    looping: LoopMode,
    time_update: Option<Program>,
    blend_weight: Option<Program>,
    start_delay: Option<Program>,
    loop_delay: Option<Program>,
    override_prev: bool,
    bones: Vec<BoneAnim>,
    timeline: Vec<(f32, Vec<Program>)>,
    sounds: Vec<(f32, u32)>,
    particles: Vec<(f32, u32)>,
}

// something that can be "played": an animation or a controller, by index
#[derive(Clone, Copy, PartialEq, Debug)]
enum Playable {
    Anim(usize),
    Ctrl(usize),
}

struct StateAnim {
    what: Playable,
    weight: Option<Program>,
}

struct State {
    anims: Vec<StateAnim>,
    transitions: Vec<(usize, Program)>,
    on_entry: Vec<Program>,
    on_exit: Vec<Program>,
    blend: f32,
    sounds: Vec<u32>,
    particles: Vec<u32>,
}

struct Ctrl {
    initial: usize,
    states: Vec<State>,
}

struct RenderCtrl {
    condition: Option<Program>,
    geometry: Option<Program>,
    textures: Vec<Program>,
    // the "materials" list flattened in order, each value is molang ("v.x ? Material.a : Material.b")
    materials: Vec<Program>,
    visibility: Vec<(String, Program)>,
    overlay: Option<[Program; 4]>,
    hurt_color: Option<[Program; 4]>,
}

pub struct Def {
    // position `this` starts at the pivot (vanilla legacy models, see sampling)
    legacy_pos: bool,
    book: Book,
    bones: Vec<Bone>,
    bone_names: Vec<String>,
    anims: Vec<Anim>,
    // full ids, lowercase, same order as anims: what playAnimation names
    anim_names: Vec<String>,
    // the client entity's short names -> full ids, lowercase
    short: HashMap<String, String>,
    ctrls: Vec<Ctrl>,
    animate: Vec<StateAnim>,
    initialize: Vec<Program>,
    pre_animation: Vec<Program>,
    // attachables: runs on the holder's variables (v.mainhand_visible = 1 and such)
    parent_setup: Vec<Program>,
    scale: Option<Program>,
    scale_xyz: [Option<Program>; 3],
    renders: Vec<RenderCtrl>,
    arrays: Vec<Vec<Val>>,
    textures: Vec<u32>,   // string id of "texture.<name>" -> index is the answer
    geometries: Vec<u32>,
    pub events: Vec<String>, // sound/particle effect names the java side plays
    pub errors: Vec<String>,
}

fn prog(book: &mut Book, v: &J, errors: &mut Vec<String>) -> Program {
    match v {
        J::Number(n) => Book::constant(n.as_f64().unwrap_or(0.0) as f32),
        J::Bool(b) => Book::constant(if *b { 1.0 } else { 0.0 }),
        J::String(s) => {
            let (p, e) = book.compile(s);
            if let Some(e) = e {
                errors.push(e);
            }
            p
        }
        _ => Book::constant(0.0),
    }
}

// a list of statements (pre_animation, on_entry, timeline...). bedrock glues the whole array into
// one expression, so a `c ? {` in one entry and its `};` ten entries later is fine there. each
// entry on its own first (a broken line stays one broken line), glued when that does not parse
pub(crate) fn script(book: &mut Book, list: &[J], errors: &mut Vec<String>) -> Vec<Program> {
    let mut mine = Vec::new();
    let mut one = Vec::new();
    for x in list {
        mine.push(prog(book, x, &mut one));
    }
    if one.is_empty() || list.len() < 2 || !list.iter().all(|x| x.is_string()) {
        errors.extend(one);
        return mine;
    }
    let mut glued = String::new();
    for x in list {
        let t = x.as_str().unwrap_or("").trim();
        if t.is_empty() {
            continue;
        }
        glued.push_str(t);
        // "v.a = 1" with no semicolon is a whole statement on its own, keep it one after gluing
        if !t.ends_with([';', '{', '}', '(', ',', '?', ':', '&', '|']) {
            glued.push(';');
        }
        glued.push('\n');
    }
    let (p, e) = book.compile(&glued);
    match e {
        None => vec![p],
        Some(_) => {
            errors.extend(one);
            mine
        }
    }
}

fn vec3(book: &mut Book, v: &J, errors: &mut Vec<String>, fill: f32) -> Vec3P {
    match v {
        J::Array(a) => {
            let g = |i: usize, book: &mut Book, errors: &mut Vec<String>| match a.get(i) {
                Some(x) => prog(book, x, errors),
                // [x] alone means the same x everywhere, blockbench writes that for uniform scale
                None if a.len() == 1 => prog(book, &a[0], errors),
                None => Book::constant(fill),
            };
            Vec3P([g(0, book, errors), g(1, book, errors), g(2, book, errors)])
        }
        // a single value for all three axes
        other => {
            let p = prog(book, other, errors);
            let q = prog(book, other, errors);
            let r = prog(book, other, errors);
            Vec3P([p, q, r])
        }
    }
}

fn chan(book: &mut Book, v: &J, errors: &mut Vec<String>, fill: f32) -> Option<Chan> {
    match v {
        J::Object(map) => {
            if let Some(vector) = map.get("vector") {
                return Some(Chan::Fixed(vec3(book, vector, errors, fill)));
            }
            let mut keys = Vec::new();
            for (t, val) in map {
                let Ok(time) = t.trim().parse::<f32>() else { continue };
                match val {
                    J::Object(k) => {
                        let lerp = match k.get("lerp_mode").and_then(|x| x.as_str()) {
                            Some("catmullrom") => Lerp::Catmull,
                            Some("step") => Lerp::Step,
                            _ => Lerp::Linear,
                        };
                        let post_src = k.get("post").or_else(|| k.get("vector")).or_else(|| k.get("pre"));
                        let pre_src = k.get("pre").or(post_src);
                        let (Some(pre_src), Some(post_src)) = (pre_src, post_src) else { continue };
                        keys.push(Key { t: time, pre: vec3(book, pre_src, errors, fill), post: vec3(book, post_src, errors, fill), lerp });
                    }
                    other => keys.push(Key { t: time, pre: vec3(book, other, errors, fill), post: vec3(book, other, errors, fill), lerp: Lerp::Linear }),
                }
            }
            if keys.is_empty() {
                return None;
            }
            keys.sort_by(|a, b| a.t.partial_cmp(&b.t).unwrap_or(std::cmp::Ordering::Equal));
            Some(Chan::Keys(keys))
        }
        J::Null => None,
        other => Some(Chan::Fixed(vec3(book, other, errors, fill))),
    }
}

fn last_key(c: &Option<Chan>) -> f32 {
    match c {
        Some(Chan::Keys(k)) => k.last().map(|k| k.t).unwrap_or(0.0),
        _ => 0.0,
    }
}

fn lower(s: &str) -> String {
    s.to_ascii_lowercase()
}

impl Def {
    pub fn build(src: &J) -> Def {
        let mut book = Book::default();
        let mut errors = Vec::new();
        let mut events: Vec<String> = Vec::new();
        let event_id = |events: &mut Vec<String>, name: &str| -> u32 {
            if let Some(i) = events.iter().position(|e| e == name) {
                return i as u32;
            }
            events.push(name.to_string());
            (events.len() - 1) as u32
        };

        let mut bones = Vec::new();
        let mut bone_names = Vec::new();
        if let Some(list) = src.get("bones").and_then(|b| b.as_array()) {
            for b in list {
                let f3 = |k: &str| {
                    let mut o = [0f32; 3];
                    if let Some(a) = b.get(k).and_then(|x| x.as_array()) {
                        for i in 0..3 {
                            o[i] = a.get(i).and_then(|x| x.as_f64()).unwrap_or(0.0) as f32;
                        }
                    }
                    o
                };
                bone_names.push(lower(b.get("name").and_then(|x| x.as_str()).unwrap_or("")));
                bones.push(Bone {
                    parent: b.get("parent").and_then(|x| x.as_i64()).unwrap_or(-1) as i32,
                    pivot: f3("pivot"),
                    rot: f3("rot"),
                    pos: f3("pos"),
                });
            }
        }
        let bone_index: HashMap<String, usize> = bone_names.iter().enumerate().map(|(i, n)| (n.clone(), i)).collect();

        // full ids first so controllers can point at each other in any order
        let empty = serde_json::Map::new();
        let anim_src = src.get("animations").and_then(|x| x.as_object()).unwrap_or(&empty);
        let ctrl_src = src.get("controllers").and_then(|x| x.as_object()).unwrap_or(&empty);
        let short = src.get("short").and_then(|x| x.as_object()).unwrap_or(&empty);
        let anim_ids: HashMap<String, usize> = anim_src.keys().enumerate().map(|(i, k)| (lower(k), i)).collect();
        let ctrl_ids: HashMap<String, usize> = ctrl_src.keys().enumerate().map(|(i, k)| (lower(k), i)).collect();
        // short names from the client entity + full ids work in lists
        let resolve = |name: &str| -> Option<Playable> {
            let n = lower(name);
            let full = short.iter().find(|(k, _)| lower(k) == n).and_then(|(_, v)| v.as_str()).map(lower).unwrap_or(n);
            if let Some(&i) = anim_ids.get(&full) {
                return Some(Playable::Anim(i));
            }
            ctrl_ids.get(&full).map(|&i| Playable::Ctrl(i))
        };

        let mut anims = Vec::new();
        let anim_names: Vec<String> = anim_src.keys().map(|k| lower(k)).collect();
        for (name, a) in anim_src {
            let mut bone_anims = Vec::new();
            if let Some(bmap) = a.get("bones").and_then(|x| x.as_object()) {
                for (bname, ch) in bmap {
                    let Some(&bi) = bone_index.get(&lower(bname.trim())) else { continue };
                    let rot_rel_entity = ch.get("relative_to").and_then(|r| r.get("rotation")).and_then(|x| x.as_str()) == Some("entity");
                    bone_anims.push(BoneAnim {
                        bone: bi,
                        rot: ch.get("rotation").and_then(|v| chan(&mut book, v, &mut errors, 0.0)),
                        pos: ch.get("position").and_then(|v| chan(&mut book, v, &mut errors, 0.0)),
                        scale: ch.get("scale").and_then(|v| chan(&mut book, v, &mut errors, 1.0)),
                        rot_rel_entity,
                    });
                }
            }
            let looping = match a.get("loop") {
                Some(J::Bool(true)) => LoopMode::Loop,
                Some(J::String(s)) if s == "hold_on_last_frame" => LoopMode::Hold,
                Some(J::String(s)) if s == "true" => LoopMode::Loop,
                _ => LoopMode::Once,
            };
            let mut length = a.get("animation_length").and_then(|x| x.as_f64()).map(|x| x as f32).unwrap_or(-1.0);
            if length < 0.0 {
                length = bone_anims.iter().map(|b| last_key(&b.rot).max(last_key(&b.pos)).max(last_key(&b.scale))).fold(0.0, f32::max);
            }
            let opt = |k: &str, book: &mut Book, errors: &mut Vec<String>| a.get(k).map(|v| prog(book, v, errors));
            let mut timeline = Vec::new();
            if let Some(tl) = a.get("timeline").and_then(|x| x.as_object()) {
                for (t, v) in tl {
                    let Ok(time) = t.parse::<f32>() else { continue };
                    let list: Vec<Program> = match v {
                        J::Array(xs) => script(&mut book, xs, &mut errors),
                        other => vec![prog(&mut book, other, &mut errors)],
                    };
                    timeline.push((time, list));
                }
            }
            let timed = |key: &str, events: &mut Vec<String>| -> Vec<(f32, u32)> {
                let mut out = Vec::new();
                if let Some(m) = a.get(key).and_then(|x| x.as_object()) {
                    for (t, v) in m {
                        let Ok(time) = t.parse::<f32>() else { continue };
                        let items: Vec<&J> = match v { J::Array(xs) => xs.iter().collect(), o => vec![o] };
                        for it in items {
                            if let Some(e) = it.get("effect").and_then(|x| x.as_str()) {
                                let loc = it.get("locator").and_then(|x| x.as_str()).unwrap_or("");
                                let name = if key == "sound_effects" { format!("sound:{e}") } else { format!("particle:{e}@{loc}") };
                                out.push((time, event_id(events, &name)));
                            }
                        }
                    }
                }
                out
            };
            let sounds = timed("sound_effects", &mut events);
            let particles = timed("particle_effects", &mut events);
            let _ = name;
            anims.push(Anim {
                length: length.max(0.0),
                looping,
                time_update: opt("anim_time_update", &mut book, &mut errors),
                blend_weight: opt("blend_weight", &mut book, &mut errors),
                start_delay: opt("start_delay", &mut book, &mut errors),
                loop_delay: opt("loop_delay", &mut book, &mut errors),
                override_prev: a.get("override_previous_animation").and_then(|x| x.as_bool()).unwrap_or(false),
                bones: bone_anims,
                timeline,
                sounds,
                particles,
            });
        }

        let state_anims = |list: Option<&J>, book: &mut Book, errors: &mut Vec<String>| -> Vec<StateAnim> {
            let mut out = Vec::new();
            match list {
                Some(J::Array(xs)) => {
                    for x in xs {
                        match x {
                            J::String(n) => {
                                if let Some(p) = resolve(n) { out.push(StateAnim { what: p, weight: None }); }
                            }
                            J::Object(m) => {
                                for (n, w) in m {
                                    if let Some(p) = resolve(n) { out.push(StateAnim { what: p, weight: Some(prog(book, w, errors)) }); }
                                }
                            }
                            _ => {}
                        }
                    }
                }
                Some(J::String(n)) => {
                    if let Some(p) = resolve(n) { out.push(StateAnim { what: p, weight: None }); }
                }
                _ => {}
            }
            out
        };

        let mut ctrls = Vec::new();
        for (_, c) in ctrl_src {
            let states_src = c.get("states").and_then(|x| x.as_object()).unwrap_or(&empty);
            let names: Vec<String> = states_src.keys().map(|k| lower(k)).collect();
            let mut states = Vec::new();
            for (_, s) in states_src {
                let mut transitions = Vec::new();
                if let Some(tl) = s.get("transitions").and_then(|x| x.as_array()) {
                    for t in tl {
                        if let Some(m) = t.as_object() {
                            for (to, cond) in m {
                                if let Some(i) = names.iter().position(|n| *n == lower(to)) {
                                    transitions.push((i, prog(&mut book, cond, &mut errors)));
                                }
                            }
                        }
                    }
                }
                let stmts = |k: &str, book: &mut Book, errors: &mut Vec<String>| -> Vec<Program> {
                    s.get(k).and_then(|x| x.as_array()).map(|xs| script(book, xs, errors)).unwrap_or_default()
                };
                let blend = match s.get("blend_transition") {
                    Some(J::Number(n)) => n.as_f64().unwrap_or(0.0) as f32,
                    // curve form {"0.0": 1, "0.2": 0}: its last key is how long the fade takes
                    Some(J::Object(m)) => m.keys().filter_map(|k| k.parse::<f32>().ok()).fold(0.0, f32::max),
                    _ => 0.0,
                };
                let effects = |k: &str, kind: &str, events: &mut Vec<String>| -> Vec<u32> {
                    s.get(k).and_then(|x| x.as_array()).map(|xs| xs.iter().filter_map(|x| {
                        let e = x.get("effect").and_then(|e| e.as_str())?;
                        let loc = x.get("locator").and_then(|l| l.as_str()).unwrap_or("");
                        Some(event_id(events, &if kind == "sound" { format!("sound:{e}") } else { format!("particle:{e}@{loc}") }))
                    }).collect()).unwrap_or_default()
                };
                states.push(State {
                    anims: state_anims(s.get("animations"), &mut book, &mut errors),
                    transitions,
                    on_entry: stmts("on_entry", &mut book, &mut errors),
                    on_exit: stmts("on_exit", &mut book, &mut errors),
                    blend,
                    sounds: effects("sound_effects", "sound", &mut events),
                    particles: effects("particle_effects", "particle", &mut events),
                });
            }
            let initial = c.get("initial_state").and_then(|x| x.as_str()).and_then(|n| names.iter().position(|s| *s == lower(n)))
                .or_else(|| names.iter().position(|s| s == "default")).unwrap_or(0);
            ctrls.push(Ctrl { initial, states });
        }

        let scripts = src.get("scripts");
        let stmt_list = |k: &str, book: &mut Book, errors: &mut Vec<String>| -> Vec<Program> {
            match scripts.and_then(|s| s.get(k)) {
                Some(J::Array(xs)) => script(book, xs, errors),
                Some(J::String(s)) => vec![prog(book, &J::String(s.clone()), errors)],
                _ => Vec::new(),
            }
        };
        let initialize = stmt_list("initialize", &mut book, &mut errors);
        let pre_animation = stmt_list("pre_animation", &mut book, &mut errors);
        let parent_setup = stmt_list("parent_setup", &mut book, &mut errors);
        let animate = state_anims(scripts.and_then(|s| s.get("animate")), &mut book, &mut errors);
        let scale = scripts.and_then(|s| s.get("scale")).map(|v| prog(&mut book, v, &mut errors));
        let scale_xyz = [
            scripts.and_then(|s| s.get("scale_x")).map(|v| prog(&mut book, v, &mut errors)),
            scripts.and_then(|s| s.get("scale_y")).map(|v| prog(&mut book, v, &mut errors)),
            scripts.and_then(|s| s.get("scale_z")).map(|v| prog(&mut book, v, &mut errors)),
        ];

        // resource names, the answers are indexes into these
        let names_of = |k: &str| -> Vec<String> {
            src.get(k).and_then(|x| x.as_array()).map(|xs| xs.iter().filter_map(|x| x.as_str().map(lower)).collect()).unwrap_or_default()
        };
        let textures: Vec<u32> = names_of("textures").iter().map(|n| book.string(&format!("texture.{n}"))).collect();
        let geometries: Vec<u32> = names_of("geometries").iter().map(|n| book.string(&format!("geometry.{n}"))).collect();

        let mut renders = Vec::new();
        let mut arrays: Vec<Vec<Val>> = Vec::new();
        if let Some(list) = src.get("render").and_then(|x| x.as_array()) {
            for (rci, rc) in list.iter().enumerate() {
                book.array_scope = format!("rc{rci}:");
                if let Some(arr) = rc.get("arrays").and_then(|x| x.as_object()) {
                    for (_, group) in arr {
                        let Some(group) = group.as_object() else { continue };
                        for (aname, values) in group {
                            let key = lower(aname);
                            let key = key.strip_prefix("array.").unwrap_or(&key).to_string();
                            let id = book.array(&key) as usize;
                            if arrays.len() <= id {
                                arrays.resize(id + 1, Vec::new());
                            }
                            arrays[id] = values.as_array().map(|xs| xs.iter().filter_map(|x| x.as_str()).map(|s| Val::Str(book.string(&lower(s)))).collect()).unwrap_or_default();
                        }
                    }
                }
                let color4 = |v: Option<&J>, book: &mut Book, errors: &mut Vec<String>| -> Option<[Program; 4]> {
                    let o = v?.as_object()?;
                    let g = |k: &str, book: &mut Book, errors: &mut Vec<String>| o.get(k).map(|x| prog(book, x, errors)).unwrap_or(Book::constant(0.0));
                    Some([g("r", book, errors), g("g", book, errors), g("b", book, errors), g("a", book, errors)])
                };
                let mut visibility = Vec::new();
                if let Some(pv) = rc.get("part_visibility").and_then(|x| x.as_array()) {
                    for item in pv {
                        if let Some(m) = item.as_object() {
                            for (pat, v) in m {
                                visibility.push((lower(pat), prog(&mut book, v, &mut errors)));
                            }
                        }
                    }
                }
                let mut materials = Vec::new();
                if let Some(ml) = rc.get("materials").and_then(|x| x.as_array()) {
                    for item in ml {
                        if let Some(m) = item.as_object() {
                            for (_, v) in m {
                                materials.push(prog(&mut book, v, &mut errors));
                            }
                        }
                    }
                }
                renders.push(RenderCtrl {
                    materials,
                    condition: rc.get("condition").map(|v| prog(&mut book, v, &mut errors)),
                    geometry: rc.get("geometry").map(|v| prog(&mut book, v, &mut errors)),
                    textures: rc.get("textures").and_then(|x| x.as_array()).map(|xs| xs.iter().map(|x| prog(&mut book, x, &mut errors)).collect()).unwrap_or_default(),
                    visibility,
                    overlay: color4(rc.get("overlay_color"), &mut book, &mut errors),
                    hurt_color: color4(rc.get("is_hurt_color"), &mut book, &mut errors),
                });
            }
        }
        book.array_scope.clear();
        // arrays may be named after they were used, keep the table as long as the book says
        if arrays.len() < book.arrays.len() {
            arrays.resize(book.arrays.len(), Vec::new());
        }

        let short: HashMap<String, String> = short.iter().filter_map(|(k, v)| v.as_str().map(|v| (lower(k), lower(v)))).collect();
        Def {
            legacy_pos: src.get("legacy_pos").and_then(|x| x.as_bool()).unwrap_or(false),
            book, bones, bone_names, anims, anim_names, short, ctrls, animate, initialize, pre_animation, parent_setup, scale, scale_xyz,
            renders, arrays, textures, geometries, events, errors,
        }
    }

    pub fn var_names(&self) -> &[String] {
        &self.book.vars
    }
    pub fn contexts(&self) -> &[String] {
        &self.book.contexts
    }

    pub fn strings(&self) -> &[String] {
        &self.book.strings
    }

    pub fn query_names(&self) -> &[String] {
        &self.book.queries
    }

    pub fn bone_count(&self) -> usize {
        self.bones.len()
    }

    pub fn anim_names(&self) -> &[String] {
        &self.anim_names
    }

    // a full animation id or a short name from the client entity's animations map
    pub fn anim_index(&self, name: &str) -> Option<usize> {
        let n = lower(name);
        self.anim_names.iter().position(|a| *a == n).or_else(|| {
            let full = self.short.get(&n)?;
            self.anim_names.iter().position(|a| a == full)
        })
    }
}

// one playAnimation / playanimation: a runtime controller bedrock appends to the entity's animations.
// keyed by controller name, a new one with the same name replaces it (the default name is shared, so
// by default a mob plays one of these at a time, like bedrock)
struct Played {
    key: String,
    anim: usize,
    run: AnimRun,
    stop: Program,
    // stop expression compiled against a copy of the def's book: names it adds read 0 / write nowhere
    blend_out: f32,
    // Some(t) = stopping, t seconds into the blend out
    out: Option<f32>,
}

// ── instance ────────────────────────────────────────────────────────────────

#[derive(Clone)]
struct AnimRun {
    time: f32,
    delay: f32,
    finished: bool,
    started: bool,
}

impl AnimRun {
    fn fresh() -> Self {
        AnimRun { time: 0.0, delay: -1.0, finished: false, started: false }
    }
}

#[derive(Clone)]
struct CtrlRun {
    state: usize,
    state_time: f32,
    // the state we are fading out of, and how far along (0..1 of its blend)
    prev: Option<usize>,
    fade: f32,
    fade_len: f32,
    entered: bool,
    prev_runs: Vec<AnimRun>,
}

pub struct Inst {
    def: Arc<Def>,
    vars: Vec<koperlib_molang::Val>,
    var_set: Vec<bool>,
    temps: Vec<Val>,
    rng: u32,
    initialized: bool,
    anim_runs: Vec<AnimRun>,
    ctrl_runs: Vec<CtrlRun>,
    // per bone accumulated offsets for this frame
    rot: Vec<[f32; 3]>,
    pos: Vec<[f32; 3]>,
    scl: Vec<[f32; 3]>,
    fired: Vec<u32>,
    // every render controller that drew this frame: [controller index, texture, geometry]
    pub layers: Vec<[i32; 3]>,
    // per render controller: what each "materials" entry picked last tick (string ids, -1 = not a string)
    pub rc_mats: Vec<Vec<i32>>,
    // per render controller: every texture of its "textures" list, def texture indices (-1 = none).
    // bedrock's multitexture materials stack them (horse + markings + armor, villager + biome + job)
    pub rc_tex: Vec<Vec<i32>>,
    // per render controller: its own part_visibility, one byte per bone (bedrock hides parts per controller,
    // the villager's level badge controller hides the whole villager for itself only)
    pub rc_vis: Vec<Vec<u8>>,
    // the holder for this tick only (attachables), java hands it over right before tick_c
    pub owner: *const Inst,
    remote: Vec<Option<Val>>,
    // contexts of the last tick, parent_setup runs with the same ones
    last_ctx: Vec<f32>,
    last_cstr: Vec<i32>,
    played: Vec<Played>,
}

pub struct Out<'a> {
    pub mats: &'a mut [f32],
    pub vis: &'a mut [u8],
    pub local: Option<&'a mut [f32]>,
    // [texture index, geometry index, scale bits, event count, overlay rgba packed, hurt rgba packed]
    pub info: &'a mut [i32],
    pub events: &'a mut [i32],
}

impl Inst {
    pub fn new(def: Arc<Def>, seed: u32) -> Inst {
        let n = def.bones.len();
        Inst {
            vars: vec![Val::default(); def.book.vars.len()],
            var_set: vec![false; def.book.vars.len()],
            temps: Vec::new(),
            rng: seed | 1,
            initialized: false,
            anim_runs: vec![AnimRun::fresh(); def.anims.len()],
            ctrl_runs: def.ctrls.iter().map(|c| CtrlRun { state: c.initial, state_time: 0.0, prev: None, fade: 1.0, fade_len: 0.0, entered: false, prev_runs: Vec::new() }).collect(),
            rot: vec![[0.0; 3]; n],
            pos: vec![[0.0; 3]; n],
            scl: vec![[1.0; 3]; n],
            fired: Vec::new(),
            layers: Vec::new(),
            rc_mats: def.renders.iter().map(|r| vec![-1; r.materials.len()]).collect(),
            rc_tex: def.renders.iter().map(|r| vec![-1; r.textures.len()]).collect(),
            rc_vis: def.renders.iter().map(|_| vec![1; def.bone_names.len()]).collect(),
            owner: std::ptr::null(),
            remote: vec![None; def.book.remotes.len()],
            last_ctx: Vec::new(),
            last_cstr: Vec::new(),
            played: Vec::new(),
            def,
        }
    }

    // start an animation from outside the entity's own list (entity.playAnimation, /playanimation).
    // stop: molang, default like bedrock's "query.any_animation_finished". false when the def lacks the anim
    pub fn play_animation(&mut self, anim: &str, blend_out: f32, stop: Option<&str>, controller: Option<&str>) -> bool {
        let Some(ai) = self.def.anim_index(anim) else { return false };
        let key = controller.filter(|c| !c.is_empty()).unwrap_or("__runtime_controller").to_string();
        let stop_src = stop.filter(|s| !s.trim().is_empty()).unwrap_or("query.any_animation_finished");
        let mut book = self.def.book.clone();
        let (stop, _) = book.compile(stop_src);
        self.played.retain(|p| p.key != key);
        self.played.push(Played { key, anim: ai, run: AnimRun::fresh(), stop, blend_out: blend_out.max(0.0), out: None });
        true
    }

    // test harness only: each controller's current state and every bone's animated rotation this frame
    #[cfg(test)]
    pub fn debug_states(&self) -> (Vec<usize>, Vec<[f32; 3]>) {
        (self.ctrl_runs.iter().map(|c| c.state).collect(), self.rot.clone())
    }

    #[cfg(test)]
    pub fn playing(&self) -> usize {
        self.played.len()
    }

    // bedrock runs an attachable's parent_setup on its holder. we run it on a copy of the holder's
    // variables in our own book and hand back what changed, names and strings matched by text
    pub fn parent_setup(&self, owner: &mut Inst) {
        let def = &self.def;
        if def.parent_setup.is_empty() {
            return;
        }
        let n = def.book.vars.len();
        let mut vars = vec![Val::default(); n];
        let mut set = vec![false; n];
        for (i, name) in def.book.vars.iter().enumerate() {
            if let Some(v) = owner.var_named(name, &def.book) {
                vars[i] = v;
                set[i] = true;
            }
        }
        let before = vars.clone();
        let mut temps = Vec::new();
        let mut rng = self.rng;
        {
            let mut cx = Ctx::new(&[], &mut vars, &mut set, &mut temps, &def.arrays, &mut rng);
            cx.context = &self.last_ctx;
            cx.cstr = &self.last_cstr;
            for p in &def.parent_setup {
                p.eval(&mut cx);
            }
        }
        let odef = owner.def.clone();
        for i in 0..n {
            if !set[i] || vars[i] == before[i] {
                continue;
            }
            let Some(oi) = odef.book.var_id(&def.book.vars[i]) else { continue };
            let v = match vars[i] {
                Val::Str(id) => Val::Str(def.book.strings.get(id as usize).and_then(|t| odef.book.string_id(t)).unwrap_or(u32::MAX)),
                v => v,
            };
            owner.vars[oi as usize] = v;
            owner.var_set[oi as usize] = true;
        }
    }

    pub fn var_value(&self, i: usize) -> Option<f32> {
        if !*self.var_set.get(i)? {
            return None;
        }
        Some(match self.vars[i] {
            Val::Str(id) => id as f32,
            v => v.num(),
        })
    }

    pub fn set_var(&mut self, i: usize, v: f32) {
        if i < self.vars.len() {
            self.vars[i] = Val::Num(v);
            self.var_set[i] = true;
        }
    }

    // a variable of this actor as another book sees it: strings go through their text
    fn var_named(&self, name: &str, into: &Book) -> Option<Val> {
        let i = self.def.book.var_id(name)? as usize;
        if !self.var_set.get(i).copied().unwrap_or(false) {
            return None;
        }
        Some(match self.vars[i] {
            Val::Str(id) => Val::Str(self.def.book.strings.get(id as usize).and_then(|t| into.string_id(t)).unwrap_or(u32::MAX)),
            v => v,
        })
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn tick(&mut self, queries: &[f32], dt: f32, out: Out) -> usize {
        self.tick_s(queries, &[], dt, out)
    }

    #[allow(dead_code)]
    pub fn tick_s(&mut self, queries: &[f32], qstr: &[i32], dt: f32, out: Out) -> usize {
        self.tick_c(queries, qstr, &[], &[], dt, out)
    }

    pub fn tick_c(&mut self, queries: &[f32], qstr: &[i32], context: &[f32], cstr: &[i32], dt: f32, out: Out) -> usize {
        let def = self.def.clone();
        let n = def.bones.len();
        for i in 0..n {
            self.rot[i] = [0.0; 3];
            self.pos[i] = [0.0; 3];
            self.scl[i] = [1.0; 3];
        }
        self.fired.clear();
        let mut vars = std::mem::take(&mut self.vars);
        let mut var_set = std::mem::take(&mut self.var_set);
        let mut temps = std::mem::take(&mut self.temps);
        let mut rng = self.rng;
        let owner = std::mem::replace(&mut self.owner, std::ptr::null());
        // SAFETY: java passes a live handle of another instance and ticks on one thread
        let owner = if owner.is_null() || std::ptr::eq(owner, self) { None } else { Some(unsafe { &*owner }) };
        self.last_ctx.clear();
        self.last_ctx.extend_from_slice(context);
        self.last_cstr.clear();
        self.last_cstr.extend_from_slice(cstr);
        let mut remote = std::mem::take(&mut self.remote);
        for (i, name) in def.book.remotes.iter().enumerate() {
            remote[i] = owner.and_then(|o| o.var_named(name, &def.book));
        }
        {
            let mut cx = Ctx::new(queries, &mut vars, &mut var_set, &mut temps, &def.arrays, &mut rng);
            cx.qstr = qstr;
            cx.context = context;
            cx.cstr = cstr;
            cx.remote = &remote;
            cx.delta_time = dt;
            if !self.initialized {
                self.initialized = true;
                for p in &def.initialize {
                    p.eval(&mut cx);
                }
            }
            for p in &def.pre_animation {
                p.eval(&mut cx);
            }
            for sa in &def.animate {
                let w = match &sa.weight {
                    Some(p) => p.num(&mut cx).max(0.0),
                    None => 1.0,
                };
                self.play(&def, sa.what, w, dt, &mut cx, 0);
            }
            self.tick_played(&def, dt, &mut cx);

            // render controllers: first one whose condition holds decides texture and geometry,
            // every one that applies gets to hide parts
            let mut tex = -1i32;
            let mut geo = -1i32;
            for v in out.vis.iter_mut().take(n) {
                *v = 1;
            }
            let mut overlay = 0i32;
            let mut hurt = 0i32;
            self.layers.clear();
            for (ri, rc) in def.renders.iter().enumerate() {
                if let Some(c) = &rc.condition {
                    if !c.eval(&mut cx).truthy() {
                        continue;
                    }
                }
                // bedrock draws every controller that passes, each with its own geometry + texture
                let mut t_here = -1i32;
                let mut g_here = -1i32;
                if let Some(t) = rc.textures.first() {
                    if let Val::Str(s) = t.eval(&mut cx) {
                        t_here = def.textures.iter().position(|x| *x == s).map(|i| i as i32).unwrap_or(-1);
                    }
                }
                if let Some(g) = &rc.geometry {
                    if let Val::Str(s) = g.eval(&mut cx) {
                        g_here = def.geometries.iter().position(|x| *x == s).map(|i| i as i32).unwrap_or(-1);
                    }
                }
                self.layers.push([ri as i32, t_here, g_here]);
                for (ti, t) in rc.textures.iter().enumerate() {
                    self.rc_tex[ri][ti] = match t.eval(&mut cx) {
                        Val::Str(s) => def.textures.iter().position(|x| *x == s).map(|i| i as i32).unwrap_or(-1),
                        _ => -1,
                    };
                }
                for (mi, m) in rc.materials.iter().enumerate() {
                    self.rc_mats[ri][mi] = match m.eval(&mut cx) {
                        Val::Str(s) if s != u32::MAX => s as i32,
                        _ => -1,
                    };
                }
                // the first controller that draws is the base: its part_visibility is the model's. the others
                // keep theirs for their own layer (kodel_br_layer_vis), they never hide the base
                let base = tex < 0 && geo < 0;
                if tex < 0 {
                    tex = t_here;
                }
                if geo < 0 {
                    geo = g_here;
                }
                let vis = &mut self.rc_vis[ri];
                for v in vis.iter_mut() {
                    *v = 1;
                }
                for (pat, p) in &rc.visibility {
                    let on = p.eval(&mut cx).truthy() as u8;
                    for (i, name) in def.bone_names.iter().enumerate() {
                        if glob(pat, name) {
                            if let Some(v) = vis.get_mut(i) {
                                *v = on;
                            }
                        }
                    }
                }
                if base {
                    for (i, v) in vis.iter().enumerate() {
                        if let Some(o) = out.vis.get_mut(i) {
                            *o = *v;
                        }
                    }
                }
                let pack = |c: &[Program; 4], cx: &mut Ctx| -> i32 {
                    let ch = |p: &Program, cx: &mut Ctx| ((p.num(cx).clamp(0.0, 1.0) * 255.0) as i32) & 0xFF;
                    (ch(&c[3], cx) << 24) | (ch(&c[0], cx) << 16) | (ch(&c[1], cx) << 8) | ch(&c[2], cx)
                };
                if let Some(c) = &rc.overlay { overlay = pack(c, &mut cx); }
                if let Some(c) = &rc.hurt_color { hurt = pack(c, &mut cx); }
            }
            let mut scale = [1f32; 3];
            if let Some(s) = &def.scale {
                let v = s.num(&mut cx);
                scale = [v, v, v];
            }
            for (i, s) in def.scale_xyz.iter().enumerate() {
                if let Some(p) = s {
                    scale[i] *= p.num(&mut cx);
                }
            }
            if out.info.len() >= 8 {
                out.info[0] = tex;
                out.info[1] = geo;
                out.info[2] = scale[0].to_bits() as i32;
                out.info[3] = self.fired.len().min(out.events.len()) as i32;
                out.info[4] = overlay;
                out.info[5] = hurt;
                out.info[6] = scale[1].to_bits() as i32;
                out.info[7] = scale[2].to_bits() as i32;
            }
        }
        for (i, e) in self.fired.iter().take(out.events.len()).enumerate() {
            out.events[i] = *e as i32;
        }
        self.vars = vars;
        self.remote = remote;
        self.var_set = var_set;
        self.temps = temps;
        self.rng = rng;

        self.compose(&def, out.mats, out.local);
        n
    }

    // runtime controllers on top of the animate list, each on its own clock (the same animation may be
    // playing from the entity's own list too). they stop on their stop expression and fade out
    fn tick_played(&mut self, def: &Def, dt: f32, cx: &mut Ctx) {
        if self.played.is_empty() {
            return;
        }
        let mut list = std::mem::take(&mut self.played);
        list.retain_mut(|p| {
            let w = match p.out {
                Some(t) if p.blend_out > 0.0 => 1.0 - (t / p.blend_out),
                Some(_) => 0.0,
                None => 1.0,
            };
            if w <= 0.0 {
                return false;
            }
            let live = std::mem::replace(&mut self.anim_runs[p.anim], p.run.clone());
            self.play_anim_ex(def, p.anim, w, dt, cx, true);
            p.run = std::mem::replace(&mut self.anim_runs[p.anim], live);
            if let Some(t) = p.out.as_mut() {
                *t += dt;
            } else {
                cx.any_finished = p.run.finished;
                cx.all_finished = p.run.finished;
                cx.anim_time = p.run.time;
                if p.stop.eval(cx).truthy() {
                    if p.blend_out <= 0.0 {
                        return false;
                    }
                    p.out = Some(0.0);
                }
            }
            true
        });
        self.played = list;
    }

    fn play(&mut self, def: &Def, what: Playable, weight: f32, dt: f32, cx: &mut Ctx, depth: u32) {
        if depth > 8 || weight <= 0.0 {
            return;
        }
        match what {
            Playable::Anim(i) => self.play_anim(def, i, weight, dt, cx),
            Playable::Ctrl(ci) => self.play_ctrl(def, ci, weight, dt, cx, depth),
        }
    }

    fn play_ctrl(&mut self, def: &Def, ci: usize, weight: f32, dt: f32, cx: &mut Ctx, depth: u32) {
        let ctrl = &def.ctrls[ci];
        if ctrl.states.is_empty() {
            return;
        }
        let mut run = self.ctrl_runs[ci].clone();
        if !run.entered {
            run.entered = true;
            for p in &ctrl.states[run.state].on_entry {
                p.eval(cx);
            }
            self.enter_state(def, &ctrl.states[run.state]);
        }
        // the state's own animations finishing is what all/any_animation_finished ask about
        let (all, any) = self.finished_flags(def, &ctrl.states[run.state]);
        cx.all_finished = all;
        cx.any_finished = any;
        cx.state_time = run.state_time;
        let mut next = None;
        for (to, cond) in &ctrl.states[run.state].transitions {
            if cond.eval(cx).truthy() {
                next = Some(*to);
                break;
            }
        }
        if let Some(to) = next {
            if to != run.state {
                for p in &ctrl.states[run.state].on_exit {
                    p.eval(cx);
                }
                let blend = ctrl.states[run.state].blend;
                run.prev = if blend > 0.0 { Some(run.state) } else { None };
                run.fade = 0.0;
                run.fade_len = blend;
                // keep the outgoing anims' clocks so they fade from where they were
                run.prev_runs = ctrl.states[run.state].anims.iter().filter_map(|sa| match sa.what { Playable::Anim(a) => Some(self.anim_runs[a].clone()), _ => None }).collect();
                run.state = to;
                run.state_time = 0.0;
                for p in &ctrl.states[to].on_entry {
                    p.eval(cx);
                }
                // new state restarts its animations from zero
                for sa in &ctrl.states[to].anims {
                    if let Playable::Anim(a) = sa.what {
                        self.anim_runs[a] = AnimRun::fresh();
                    }
                }
                self.enter_state(def, &ctrl.states[to]);
            }
        }
        run.state_time += dt;
        let mix = if run.prev.is_some() && run.fade_len > 0.0 {
            run.fade = (run.fade + dt / run.fade_len).min(1.0);
            if run.fade >= 1.0 {
                run.prev = None;
            }
            run.fade
        } else {
            1.0
        };
        cx.state_time = run.state_time;
        if let Some(prev) = run.prev {
            let mut k = 0;
            for sa in &ctrl.states[prev].anims {
                let w = sa.weight.as_ref().map(|p| p.num(cx).max(0.0)).unwrap_or(1.0) * weight * (1.0 - mix);
                if let Playable::Anim(a) = sa.what {
                    // the fading copy runs on its own saved clock, the new state may be using the same anim
                    if let Some(saved) = run.prev_runs.get_mut(k) {
                        let live = std::mem::replace(&mut self.anim_runs[a], saved.clone());
                        self.play_anim(def, a, w, dt, cx);
                        *saved = std::mem::replace(&mut self.anim_runs[a], live);
                    }
                    k += 1;
                } else {
                    self.play(def, sa.what, w, dt, cx, depth + 1);
                }
            }
        }
        self.ctrl_runs[ci] = run.clone();
        for sa in &ctrl.states[run.state].anims {
            let w = sa.weight.as_ref().map(|p| p.num(cx).max(0.0)).unwrap_or(1.0) * weight * mix;
            self.play(def, sa.what, w, dt, cx, depth + 1);
        }
    }

    fn enter_state(&mut self, _def: &Def, st: &State) {
        self.fired.extend(st.sounds.iter().copied());
        self.fired.extend(st.particles.iter().copied());
    }

    fn finished_flags(&self, def: &Def, st: &State) -> (bool, bool) {
        let mut all = true;
        let mut any = false;
        let mut seen = false;
        for sa in &st.anims {
            if let Playable::Anim(a) = sa.what {
                if def.anims[a].looping == LoopMode::Loop {
                    continue;
                }
                seen = true;
                let f = self.anim_runs[a].finished;
                all &= f;
                any |= f;
            }
        }
        (seen && all, any)
    }

    fn play_anim(&mut self, def: &Def, ai: usize, weight: f32, dt: f32, cx: &mut Ctx) {
        self.play_anim_ex(def, ai, weight, dt, cx, false);
    }

    // hold_end: a play-once animation keeps its last frame instead of letting go (fading a runtime one out)
    fn play_anim_ex(&mut self, def: &Def, ai: usize, weight: f32, dt: f32, cx: &mut Ctx, hold_end: bool) {
        let anim = &def.anims[ai];
        let mut run = self.anim_runs[ai].clone();
        if !run.started {
            run.started = true;
            run.delay = anim.start_delay.as_ref().map(|p| p.num(cx)).unwrap_or(0.0);
        }
        if run.delay > 0.0 {
            run.delay -= dt;
            self.anim_runs[ai] = run;
            return;
        }
        let before = run.time;
        cx.anim_time = run.time;
        run.time = match &anim.time_update {
            Some(p) => p.num(cx),
            None => run.time + dt,
        };
        let mut t = run.time;
        if anim.length > 0.0 && t > anim.length {
            match anim.looping {
                LoopMode::Loop => {
                    let gap = anim.loop_delay.as_ref().map(|p| p.num(cx)).unwrap_or(0.0).max(0.0);
                    let cycle = anim.length + gap;
                    t %= cycle;
                    if t > anim.length {
                        t = anim.length;
                    }
                    // wrapping restarts the timeline and effects
                    if run.time % cycle < before % cycle {
                        run.time = t;
                    }
                }
                LoopMode::Hold => {
                    t = anim.length;
                    run.finished = true;
                }
                LoopMode::Once if hold_end => {
                    t = anim.length;
                    run.finished = true;
                }
                LoopMode::Once => {
                    run.finished = true;
                    self.anim_runs[ai] = run;
                    return;
                }
            }
        }
        let w = weight * anim.blend_weight.as_ref().map(|p| p.num(cx).max(0.0)).unwrap_or(1.0);
        cx.anim_time = t;

        // timeline and effects: fire everything whose time we just passed
        let lo = if t < before { -1.0 } else { before };
        for (kt, list) in &anim.timeline {
            if *kt > lo && *kt <= t || (*kt == 0.0 && before == 0.0 && t >= 0.0 && !run.finished && lo < 0.0) {
                for p in list {
                    p.eval(cx);
                }
            }
        }
        for (kt, e) in anim.sounds.iter().chain(anim.particles.iter()) {
            if *kt > lo && *kt <= t {
                self.fired.push(*e);
            }
        }
        self.anim_runs[ai] = run;
        if w <= 0.0 {
            return;
        }

        for ba in &anim.bones {
            let b = ba.bone;
            if anim.override_prev {
                self.rot[b] = [0.0; 3];
                self.pos[b] = [0.0; 3];
                self.scl[b] = [1.0; 3];
            }
            if let Some(c) = &ba.rot {
                let v = sample(c, t, cx, self.rot[b]);
                for k in 0..3 {
                    self.rot[b][k] += v[k] * w;
                }
                if ba.rot_rel_entity {
                    // undo what the parents turned so this bone keeps facing with the body
                    let mut p = def.bones[b].parent;
                    while p >= 0 {
                        for k in 0..3 {
                            self.rot[b][k] -= self.rot[p as usize][k] + def.bones[p as usize].rot[k];
                        }
                        p = def.bones[p as usize].parent;
                    }
                }
            }
            if let Some(c) = &ba.pos {
                // `this` on a position channel is the offset so far. vanilla's legacy models (the wolf) start it
                // at the bone's pivot in old model space (x, y - 24, z) instead: their setup animations say
                // "-14 - this" meaning "stay where the pivot puts you". mojang's own addon mobs start at 0
                let pv = if def.legacy_pos { def.bones[b].pivot } else { [0.0, 24.0, 0.0] };
                let this = [self.pos[b][0] + pv[0], self.pos[b][1] + pv[1] - 24.0, self.pos[b][2] + pv[2]];
                let v = sample(c, t, cx, this);
                for k in 0..3 {
                    self.pos[b][k] += v[k] * w;
                }
            }
            if let Some(c) = &ba.scale {
                let v = sample(c, t, cx, self.scl[b]);
                for k in 0..3 {
                    self.scl[b][k] *= 1.0 + (v[k] - 1.0) * w;
                }
            }
        }
    }

    // same matrix build as kodel's own sample_pose: T(pos) T(pivot) R S T(-pivot), parent first
    fn compose(&self, def: &Def, out: &mut [f32], local: Option<&mut [f32]>) {
        let n = def.bones.len();
        let mut world: Vec<Mat4> = Vec::with_capacity(n);
        // the scale each bone ends up with in the world. a bone whose own animation sets a scale gets
        // exactly that one, not times its parent's: A&S draws the mooshroom at 0.01 and scales both
        // root and head by 100, multiplied that was a head 100 times too big. a bone without its own
        // scale still inherits (scale 0 on a parent hides the children, packs rely on that)
        let mut acc: Vec<[f32; 3]> = Vec::with_capacity(n);
        let d = std::f32::consts::PI / 180.0;
        for i in 0..n {
            let b = &def.bones[i];
            let r = [b.rot[0] + self.rot[i][0], b.rot[1] + self.rot[i][1], b.rot[2] + self.rot[i][2]];
            let p = [b.pos[0] + self.pos[i][0], b.pos[1] + self.pos[i][1], b.pos[2] + self.pos[i][2]];
            let mut s = self.scl[i];
            let rodzic = if b.parent >= 0 && (b.parent as usize) < acc.len() { acc[b.parent as usize] } else { [1.0; 3] };
            let wlasna = s.iter().any(|v| (v - 1.0).abs() > 1e-6);
            if wlasna && rodzic.iter().all(|v| v.abs() > 1e-6) && rodzic.iter().any(|v| (v - 1.0).abs() > 1e-6) {
                acc.push(s);
                for k in 0..3 {
                    s[k] /= rodzic[k];
                }
            } else {
                acc.push([rodzic[0] * s[0], rodzic[1] * s[1], rodzic[2] * s[2]]);
            }
            // bedrock turns x and z the other way round from the textbook (blockbench and geckolib both
            // flip them). without this a cow's 90 degree body put the udders on its back
            let q = quat_from_euler(-r[0] * d, r[1] * d, -r[2] * d);
            let m = Mat4::translation(p)
                .mul(Mat4::translation(b.pivot))
                .mul(quat_to_mat4(q))
                .mul(Mat4::scale(s))
                .mul(Mat4::translation([-b.pivot[0], -b.pivot[1], -b.pivot[2]]));
            let w = if b.parent >= 0 && (b.parent as usize) < world.len() { world[b.parent as usize].mul(m) } else { m };
            world.push(w);
        }
        for (i, m) in world.iter().enumerate() {
            let at = i * 16;
            if at + 16 <= out.len() {
                out[at..at + 16].copy_from_slice(&m.0);
            }
        }
        // raw offsets for callers that animate a model they own (the java player model)
        if let Some(l) = local {
            for i in 0..n {
                let at = i * 9;
                if at + 9 > l.len() {
                    break;
                }
                l[at..at + 3].copy_from_slice(&self.rot[i]);
                l[at + 3..at + 6].copy_from_slice(&self.pos[i]);
                l[at + 6..at + 9].copy_from_slice(&self.scl[i]);
            }
        }
    }
}

// cur = what the earlier animations left on this channel, molang's `this`. vanilla look_at_target
// writes "q.target_x_rotation - this" so the head ends up exactly on the target whatever ran before
fn sample(c: &Chan, t: f32, cx: &mut Ctx, cur: [f32; 3]) -> [f32; 3] {
    let ev = |v: &Vec3P, cx: &mut Ctx| {
        let mut o = [0f32; 3];
        for i in 0..3 {
            cx.this = cur[i];
            o[i] = v.0[i].num(cx);
        }
        cx.this = 0.0;
        o
    };
    match c {
        Chan::Fixed(v) => ev(v, cx),
        Chan::Keys(keys) => {
            if keys.len() == 1 || t <= keys[0].t {
                return ev(&keys[0].pre, cx);
            }
            let last = keys.len() - 1;
            if t >= keys[last].t {
                return ev(&keys[last].post, cx);
            }
            let mut k = 0;
            while k + 1 < keys.len() && keys[k + 1].t <= t {
                k += 1;
            }
            let (a, b) = (&keys[k], &keys[k + 1]);
            let span = (b.t - a.t).max(1e-6);
            let u = (t - a.t) / span;
            cx.key_frame_lerp = u;
            if a.lerp == Lerp::Step {
                return ev(&a.post, cx);
            }
            let va = ev(&a.post, cx);
            let vb = ev(&b.pre, cx);
            if a.lerp == Lerp::Catmull || b.lerp == Lerp::Catmull {
                let v0 = if k > 0 { ev(&keys[k - 1].post, cx) } else { va };
                let v3 = if k + 2 < keys.len() { ev(&keys[k + 2].pre, cx) } else { vb };
                let (u2, u3) = (u * u, u * u * u);
                let mut o = [0f32; 3];
                for i in 0..3 {
                    o[i] = 0.5 * (2.0 * va[i] + (-v0[i] + vb[i]) * u + (2.0 * v0[i] - 5.0 * va[i] + 4.0 * vb[i] - v3[i]) * u2
                        + (-v0[i] + 3.0 * va[i] - 3.0 * vb[i] + v3[i]) * u3);
                }
                return o;
            }
            [va[0] + (vb[0] - va[0]) * u, va[1] + (vb[1] - va[1]) * u, va[2] + (vb[2] - va[2]) * u]
        }
    }
}

// "*" and "arm*" style part names, everything lowercase already
fn glob(pat: &str, name: &str) -> bool {
    if pat == "*" {
        return true;
    }
    match pat.find('*') {
        None => pat == name,
        Some(i) => {
            let (head, tail) = (&pat[..i], &pat[i + 1..]);
            name.len() >= head.len() + tail.len() && name.starts_with(head) && name.ends_with(tail)
        }
    }
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod korpus;
