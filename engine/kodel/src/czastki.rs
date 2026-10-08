// bedrock particle effects, simulated natively. one Def per effect json, one Emitter per spawned
// effect. java ticks an emitter once per frame and gets back flat quads (position, size, rotation,
// uv, colour, facing) to draw. everything follows the creator docs particle component pages
use koperlib_molang::{Book, Ctx, Program, Val};
use serde_json::Value as J;
use std::sync::Arc;

struct V3([Program; 3]);

impl V3 {
    fn eval(&self, cx: &mut Ctx) -> [f32; 3] {
        [self.0[0].num(cx), self.0[1].num(cx), self.0[2].num(cx)]
    }
}

fn prog(b: &mut Book, v: Option<&J>, def: f32, errs: &mut Vec<String>) -> Program {
    match v {
        Some(J::Number(n)) => Book::constant(n.as_f64().unwrap_or(0.0) as f32),
        Some(J::Bool(x)) => Book::constant(if *x { 1.0 } else { 0.0 }),
        Some(J::String(s)) => {
            let (p, e) = b.compile(s);
            if let Some(e) = e { errs.push(e); }
            p
        }
        _ => Book::constant(def),
    }
}

fn v3(b: &mut Book, v: Option<&J>, def: [f32; 3], errs: &mut Vec<String>) -> V3 {
    match v {
        Some(J::Array(a)) => V3([
            prog(b, a.first(), def[0], errs),
            prog(b, a.get(1), def[1], errs),
            prog(b, a.get(2), def[2], errs),
        ]),
        Some(other @ (J::Number(_) | J::String(_))) => V3([prog(b, Some(other), 0.0, errs), prog(b, Some(other), 0.0, errs), prog(b, Some(other), 0.0, errs)]),
        _ => V3([Book::constant(def[0]), Book::constant(def[1]), Book::constant(def[2])]),
    }
}

enum Life {
    Once(Program),
    Looping(Program, Program),
    Expression(Program, Program),
}

enum Rate {
    Instant(Program),
    Steady(Program, Program),
    Manual(Program),
}

enum Dir {
    Out,
    In,
    Vec(V3),
}

enum Shape {
    Point { offset: V3, dir: Option<V3> },
    Sphere { offset: V3, radius: Program, surface: bool, dir: Dir },
    Box { offset: V3, half: V3, surface: bool, dir: Dir },
    Disc { offset: V3, radius: Program, surface: bool, dir: Dir, normal: V3 },
    Custom { offset: V3, dir: V3 },
    Aabb { surface: bool, dir: Dir },
}

#[derive(Clone, Copy, PartialEq)]
enum Facing {
    RotateXyz, RotateY, LookatXyz, LookatY, DirectionX, DirectionY, DirectionZ, EmitterXy, EmitterXz, EmitterYz, LookatDirection,
}

enum Uv {
    Fixed { uv: [Program; 2], size: [Program; 2] },
    Flip { base: [Program; 2], size: [f32; 2], step: [f32; 2], fps: f32, max: Program, stretch: bool, looping: bool },
}

enum Tint {
    None,
    Rgba([Program; 4]),
    Gradient(Vec<(f32, [f32; 4])>, Program),
}

enum CurveKind {
    Linear(Vec<Program>),
    Bezier(Vec<Program>),
    Catmull(Vec<Program>),
    Chain(Vec<(f32, Program, Program, Program, Program)>), // t, left value, right value, left slope, right slope
}

struct Curve {
    var: u32,
    kind: CurveKind,
    input: Program,
    range: Program,
}

// event bodies. nested sequence/randomize kept as a tree
enum Ev {
    Seq(Vec<Ev>),
    Rand(Vec<(f32, Ev)>),
    Node { effect: Option<(String, u8)>, sound: Option<String>, expr: Option<Program> },
}

pub struct Def {
    book: Book,
    pub texture: String,
    pub material: String,
    curves: Vec<Curve>,
    em_create: Option<Program>,
    em_update: Option<Program>,
    life: Life,
    rate: Rate,
    shape: Shape,
    local_pos: bool,
    speed: Option<V3>,
    speed_scalar: Option<Program>,
    spin: (Program, Program),
    p_life: Program,
    p_expire: Option<Program>,
    kill_plane: Option<[f32; 4]>,
    accel: Option<V3>,
    drag: Program,
    rot_accel: Program,
    rot_drag: Program,
    param_pos: Option<V3>,
    param_dir: Option<V3>,
    param_rot: Option<Program>,
    size: [Program; 2],
    facing: Facing,
    custom_dir: Option<V3>,
    min_speed: f32,
    uv: Uv,
    tex_size: [f32; 2],
    tint: Tint,
    lit: bool,
    per_render: Option<Program>,
    events: Vec<(String, Ev)>,
    em_create_ev: Vec<String>,
    em_expire_ev: Vec<String>,
    em_timeline: Vec<(f32, Vec<String>)>,
    p_create_ev: Vec<String>,
    p_expire_ev: Vec<String>,
    p_timeline: Vec<(f32, Vec<String>)>,
    // slots of the built-in variables
    v: Vars,
    pub errors: Vec<String>,
}

struct Vars {
    em_age: u32, em_life: u32, em_r: [u32; 4], ent_scale: u32,
    p_age: u32, p_life: u32, p_r: [u32; 4],
}

fn strs(v: Option<&J>) -> Vec<String> {
    match v {
        Some(J::String(s)) => vec![s.clone()],
        Some(J::Array(a)) => a.iter().filter_map(|x| x.as_str().map(|s| s.to_string())).collect(),
        _ => Vec::new(),
    }
}

fn timeline(v: Option<&J>) -> Vec<(f32, Vec<String>)> {
    let mut out: Vec<(f32, Vec<String>)> = v.and_then(|x| x.as_object()).map(|m| m.iter().filter_map(|(k, e)| k.parse::<f32>().ok().map(|t| (t, strs(Some(e))))).collect()).unwrap_or_default();
    out.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    out
}

fn color(v: &J) -> [f32; 4] {
    match v {
        J::String(s) => {
            let h = s.trim_start_matches('#');
            let n = u32::from_str_radix(h, 16).unwrap_or(0xFFFFFF);
            if h.len() >= 8 {
                [((n >> 16) & 255) as f32 / 255.0, ((n >> 8) & 255) as f32 / 255.0, (n & 255) as f32 / 255.0, ((n >> 24) & 255) as f32 / 255.0]
            } else {
                [((n >> 16) & 255) as f32 / 255.0, ((n >> 8) & 255) as f32 / 255.0, (n & 255) as f32 / 255.0, 1.0]
            }
        }
        J::Array(a) => {
            let g = |i: usize, d: f32| a.get(i).and_then(|x| x.as_f64()).map(|x| x as f32).unwrap_or(d);
            [g(0, 1.0), g(1, 1.0), g(2, 1.0), g(3, 1.0)]
        }
        _ => [1.0; 4],
    }
}

fn dir(b: &mut Book, v: Option<&J>, errs: &mut Vec<String>) -> Dir {
    match v {
        Some(J::String(s)) if s == "inwards" => Dir::In,
        Some(J::Array(_)) => Dir::Vec(v3(b, v, [0.0; 3], errs)),
        _ => Dir::Out,
    }
}

fn event(b: &mut Book, v: &J, errs: &mut Vec<String>) -> Ev {
    if let Some(seq) = v.get("sequence").and_then(|x| x.as_array()) {
        return Ev::Seq(seq.iter().map(|x| event(b, x, errs)).collect());
    }
    if let Some(r) = v.get("randomize").and_then(|x| x.as_array()) {
        return Ev::Rand(r.iter().map(|x| (x.get("weight").and_then(|w| w.as_f64()).unwrap_or(1.0) as f32, event(b, x, errs))).collect());
    }
    let effect = v.get("particle_effect").and_then(|p| {
        let e = p.get("effect")?.as_str()?.to_string();
        let t = match p.get("type").and_then(|x| x.as_str()).unwrap_or("emitter") {
            "emitter_bound" => 1u8,
            "particle" => 2,
            "particle_with_velocity" => 3,
            _ => 0,
        };
        Some((e, t))
    });
    let sound = v.get("sound_effect").and_then(|s| s.get("event_name")).and_then(|x| x.as_str()).map(|s| s.to_string());
    let expr = v.get("expression").map(|x| prog(b, Some(x), 0.0, errs));
    Ev::Node { effect, sound, expr }
}

impl Def {
    pub fn build(src: &J) -> Def {
        let pe = src.get("particle_effect").unwrap_or(src);
        let d = pe.get("description");
        let brp = d.and_then(|x| x.get("basic_render_parameters"));
        let mut b = Book::default();
        let mut errs = Vec::new();
        let c = pe.get("components").cloned().unwrap_or(J::Null);
        let comp = |k: &str| c.get(k);

        let v = Vars {
            em_age: b.var("emitter_age"), em_life: b.var("emitter_lifetime"),
            em_r: [b.var("emitter_random_1"), b.var("emitter_random_2"), b.var("emitter_random_3"), b.var("emitter_random_4")],
            ent_scale: b.var("entity_scale"),
            p_age: b.var("particle_age"), p_life: b.var("particle_lifetime"),
            p_r: [b.var("particle_random_1"), b.var("particle_random_2"), b.var("particle_random_3"), b.var("particle_random_4")],
        };

        let mut curves = Vec::new();
        if let Some(cs) = pe.get("curves").and_then(|x| x.as_object()) {
            for (name, cv) in cs {
                let var = b.var(name.to_ascii_lowercase().trim_start_matches("variable.").trim_start_matches("v."));
                let ty = cv.get("type").and_then(|x| x.as_str()).unwrap_or("linear");
                let list = |b: &mut Book, errs: &mut Vec<String>| -> Vec<Program> {
                    cv.get("nodes").and_then(|x| x.as_array()).map(|a| a.iter().map(|n| prog(b, Some(n), 0.0, errs)).collect()).unwrap_or_default()
                };
                let kind = match ty {
                    "bezier" => CurveKind::Bezier(list(&mut b, &mut errs)),
                    "catmull_rom" => CurveKind::Catmull(list(&mut b, &mut errs)),
                    "bezier_chain" => {
                        let mut nodes = Vec::new();
                        if let Some(m) = cv.get("nodes").and_then(|x| x.as_object()) {
                            for (t, n) in m {
                                let Ok(t) = t.parse::<f32>() else { continue };
                                let value = n.get("value");
                                let lv = prog(&mut b, n.get("left_value").or(value), 0.0, &mut errs);
                                let rv = prog(&mut b, n.get("right_value").or(value), 0.0, &mut errs);
                                let slope = n.get("slope");
                                let ls = prog(&mut b, n.get("left_slope").or(slope), 0.0, &mut errs);
                                let rs = prog(&mut b, n.get("right_slope").or(slope), 0.0, &mut errs);
                                nodes.push((t, lv, rv, ls, rs));
                            }
                        }
                        nodes.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
                        CurveKind::Chain(nodes)
                    }
                    _ => CurveKind::Linear(list(&mut b, &mut errs)),
                };
                curves.push(Curve { var, kind, input: prog(&mut b, cv.get("input"), 0.0, &mut errs), range: prog(&mut b, cv.get("horizontal_range"), 1.0, &mut errs) });
            }
        }

        let init = comp("minecraft:emitter_initialization");
        let em_create = init.and_then(|i| i.get("creation_expression")).map(|x| prog(&mut b, Some(x), 0.0, &mut errs));
        let em_update = init.and_then(|i| i.get("per_update_expression")).map(|x| prog(&mut b, Some(x), 0.0, &mut errs));

        let life = if let Some(l) = comp("minecraft:emitter_lifetime_looping") {
            Life::Looping(prog(&mut b, l.get("active_time"), 10.0, &mut errs), prog(&mut b, l.get("sleep_time"), 0.0, &mut errs))
        } else if let Some(l) = comp("minecraft:emitter_lifetime_expression") {
            Life::Expression(prog(&mut b, l.get("activation_expression"), 1.0, &mut errs), prog(&mut b, l.get("expiration_expression"), 0.0, &mut errs))
        } else {
            let l = comp("minecraft:emitter_lifetime_once");
            Life::Once(prog(&mut b, l.and_then(|x| x.get("active_time")), 10.0, &mut errs))
        };
        let rate = if let Some(r) = comp("minecraft:emitter_rate_instant") {
            Rate::Instant(prog(&mut b, r.get("num_particles"), 10.0, &mut errs))
        } else if let Some(r) = comp("minecraft:emitter_rate_steady") {
            Rate::Steady(prog(&mut b, r.get("spawn_rate"), 1.0, &mut errs), prog(&mut b, r.get("max_particles"), 50.0, &mut errs))
        } else if let Some(r) = comp("minecraft:emitter_rate_manual") {
            Rate::Manual(prog(&mut b, r.get("max_particles"), 50.0, &mut errs))
        } else {
            Rate::Instant(Book::constant(1.0))
        };
        let sv = |k: &str, b: &mut Book, errs: &mut Vec<String>| -> Option<J> { comp(k).cloned().map(|x| { let _ = (&b, &errs); x }) };
        let shape = if let Some(s) = sv("minecraft:emitter_shape_sphere", &mut b, &mut errs) {
            Shape::Sphere { offset: v3(&mut b, s.get("offset"), [0.0; 3], &mut errs), radius: prog(&mut b, s.get("radius"), 1.0, &mut errs),
                surface: s.get("surface_only").and_then(|x| x.as_bool()).unwrap_or(false), dir: dir(&mut b, s.get("direction"), &mut errs) }
        } else if let Some(s) = sv("minecraft:emitter_shape_box", &mut b, &mut errs) {
            Shape::Box { offset: v3(&mut b, s.get("offset"), [0.0; 3], &mut errs), half: v3(&mut b, s.get("half_dimensions"), [0.0; 3], &mut errs),
                surface: s.get("surface_only").and_then(|x| x.as_bool()).unwrap_or(false), dir: dir(&mut b, s.get("direction"), &mut errs) }
        } else if let Some(s) = sv("minecraft:emitter_shape_disc", &mut b, &mut errs) {
            let normal = match s.get("plane_normal") {
                Some(J::String(a)) if a == "x" => V3([Book::constant(1.0), Book::constant(0.0), Book::constant(0.0)]),
                Some(J::String(a)) if a == "z" => V3([Book::constant(0.0), Book::constant(0.0), Book::constant(1.0)]),
                Some(J::Array(_)) => v3(&mut b, s.get("plane_normal"), [0.0, 1.0, 0.0], &mut errs),
                _ => V3([Book::constant(0.0), Book::constant(1.0), Book::constant(0.0)]),
            };
            Shape::Disc { offset: v3(&mut b, s.get("offset"), [0.0; 3], &mut errs), radius: prog(&mut b, s.get("radius"), 1.0, &mut errs),
                surface: s.get("surface_only").and_then(|x| x.as_bool()).unwrap_or(false), dir: dir(&mut b, s.get("direction"), &mut errs), normal }
        } else if let Some(s) = sv("minecraft:emitter_shape_custom", &mut b, &mut errs) {
            Shape::Custom { offset: v3(&mut b, s.get("offset"), [0.0; 3], &mut errs), dir: v3(&mut b, s.get("direction"), [0.0; 3], &mut errs) }
        } else if let Some(s) = sv("minecraft:emitter_shape_entity_aabb", &mut b, &mut errs) {
            Shape::Aabb { surface: s.get("surface_only").and_then(|x| x.as_bool()).unwrap_or(false), dir: dir(&mut b, s.get("direction"), &mut errs) }
        } else {
            let s = comp("minecraft:emitter_shape_point").cloned().unwrap_or(J::Null);
            Shape::Point { offset: v3(&mut b, s.get("offset"), [0.0; 3], &mut errs), dir: s.get("direction").map(|d| v3(&mut b, Some(d), [0.0; 3], &mut errs)) }
        };
        let local_pos = comp("minecraft:emitter_local_space").and_then(|l| l.get("position")).and_then(|x| x.as_bool()).unwrap_or(false);

        let (speed, speed_scalar) = match comp("minecraft:particle_initial_speed") {
            Some(J::Array(_)) => (Some(v3(&mut b, comp("minecraft:particle_initial_speed"), [0.0; 3], &mut errs)), None),
            Some(x) => (None, Some(prog(&mut b, Some(x), 0.0, &mut errs))),
            None => (None, None),
        };
        let spin_c = comp("minecraft:particle_initial_spin").cloned().unwrap_or(J::Null);
        let spin = (prog(&mut b, spin_c.get("rotation"), 0.0, &mut errs), prog(&mut b, spin_c.get("rotation_rate"), 0.0, &mut errs));
        let lt = comp("minecraft:particle_lifetime_expression").cloned().unwrap_or(J::Null);
        let p_life = prog(&mut b, lt.get("max_lifetime"), 1.0, &mut errs);
        let p_expire = lt.get("expiration_expression").map(|x| prog(&mut b, Some(x), 0.0, &mut errs));
        let kill_plane = comp("minecraft:particle_kill_plane").and_then(|x| x.as_array()).map(|a| {
            let g = |i: usize| a.get(i).and_then(|x| x.as_f64()).unwrap_or(0.0) as f32;
            [g(0), g(1), g(2), g(3)]
        });
        let md = comp("minecraft:particle_motion_dynamic").cloned().unwrap_or(J::Null);
        let accel = md.get("linear_acceleration").map(|x| v3(&mut b, Some(x), [0.0; 3], &mut errs));
        let drag = prog(&mut b, md.get("linear_drag_coefficient"), 0.0, &mut errs);
        let rot_accel = prog(&mut b, md.get("rotation_acceleration"), 0.0, &mut errs);
        let rot_drag = prog(&mut b, md.get("rotation_drag_coefficient"), 0.0, &mut errs);
        let mp = comp("minecraft:particle_motion_parametric").cloned();
        let param_pos = mp.as_ref().and_then(|m| m.get("relative_position")).map(|x| v3(&mut b, Some(x), [0.0; 3], &mut errs));
        let param_dir = mp.as_ref().and_then(|m| m.get("direction")).map(|x| v3(&mut b, Some(x), [0.0; 3], &mut errs));
        let param_rot = mp.as_ref().and_then(|m| m.get("rotation")).map(|x| prog(&mut b, Some(x), 0.0, &mut errs));

        let bb = comp("minecraft:particle_appearance_billboard").cloned().unwrap_or(J::Null);
        let size_arr = bb.get("size").and_then(|x| x.as_array()).cloned().unwrap_or_default();
        let size = [prog(&mut b, size_arr.first(), 0.1, &mut errs), prog(&mut b, size_arr.get(1), 0.1, &mut errs)];
        let facing = match bb.get("facing_camera_mode").and_then(|x| x.as_str()).unwrap_or("rotate_xyz") {
            "rotate_y" => Facing::RotateY, "lookat_xyz" => Facing::LookatXyz, "lookat_y" => Facing::LookatY,
            "direction_x" => Facing::DirectionX, "direction_y" => Facing::DirectionY, "direction_z" => Facing::DirectionZ,
            "emitter_transform_xy" => Facing::EmitterXy, "emitter_transform_xz" => Facing::EmitterXz, "emitter_transform_yz" => Facing::EmitterYz,
            "lookat_direction" => Facing::LookatDirection,
            _ => Facing::RotateXyz,
        };
        let dm = bb.get("direction");
        let custom_dir = dm.filter(|d| d.get("mode").and_then(|m| m.as_str()) == Some("custom_direction")).and_then(|d| d.get("custom_direction")).map(|x| v3(&mut b, Some(x), [0.0; 3], &mut errs));
        let min_speed = dm.and_then(|d| d.get("min_speed_threshold")).and_then(|x| x.as_f64()).unwrap_or(0.01) as f32;
        let uvj = bb.get("uv").cloned().unwrap_or(J::Null);
        let tex_size = [uvj.get("texture_width").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32, uvj.get("texture_height").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32];
        let pair = |b: &mut Book, v: Option<&J>, d: f32, errs: &mut Vec<String>| -> [Program; 2] {
            let a = v.and_then(|x| x.as_array()).cloned().unwrap_or_default();
            [prog(b, a.first(), d, errs), prog(b, a.get(1), d, errs)]
        };
        let uv = if let Some(f) = uvj.get("flipbook") {
            let fl = |k: &str, i: usize| f.get(k).and_then(|x| x.as_array()).and_then(|a| a.get(i)).and_then(|x| x.as_f64()).unwrap_or(0.0) as f32;
            Uv::Flip {
                base: pair(&mut b, f.get("base_UV"), 0.0, &mut errs),
                size: [fl("size_UV", 0), fl("size_UV", 1)],
                step: [fl("step_UV", 0), fl("step_UV", 1)],
                fps: f.get("frames_per_second").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32,
                max: prog(&mut b, f.get("max_frame"), 1.0, &mut errs),
                stretch: f.get("stretch_to_lifetime").and_then(|x| x.as_bool()).unwrap_or(false),
                looping: f.get("loop").and_then(|x| x.as_bool()).unwrap_or(false),
            }
        } else {
            Uv::Fixed { uv: pair(&mut b, uvj.get("uv"), 0.0, &mut errs), size: pair(&mut b, uvj.get("uv_size"), tex_size[0].max(1.0), &mut errs) }
        };

        let tint = match comp("minecraft:particle_appearance_tinting").and_then(|t| t.get("color")) {
            Some(J::Object(m)) if m.contains_key("gradient") => {
                let mut stops = Vec::new();
                match m.get("gradient") {
                    Some(J::Array(a)) => {
                        let n = a.len().max(2) - 1;
                        for (i, c) in a.iter().enumerate() { stops.push((i as f32 / n as f32, color(c))); }
                    }
                    Some(J::Object(o)) => {
                        for (k, c) in o { if let Ok(t) = k.parse::<f32>() { stops.push((t, color(c))); } }
                        stops.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
                        // gradient keys are in interpolant units, normalise to the last one
                        if let Some(last) = stops.last().map(|s| s.0).filter(|l| *l > 0.0) {
                            for s in stops.iter_mut() { s.0 /= last; }
                        }
                    }
                    _ => {}
                }
                Tint::Gradient(stops, prog(&mut b, m.get("interpolant"), 0.0, &mut errs))
            }
            Some(J::Array(a)) if a.iter().any(|x| x.is_string()) => Tint::Rgba([
                prog(&mut b, a.first(), 1.0, &mut errs), prog(&mut b, a.get(1), 1.0, &mut errs), prog(&mut b, a.get(2), 1.0, &mut errs), prog(&mut b, a.get(3), 1.0, &mut errs)]),
            Some(c) => {
                let k = color(c);
                Tint::Rgba([Book::constant(k[0]), Book::constant(k[1]), Book::constant(k[2]), Book::constant(k[3])])
            }
            None => Tint::None,
        };
        let lit = comp("minecraft:particle_appearance_lighting").is_some();
        let per_render = comp("minecraft:particle_initialization").and_then(|i| i.get("per_render_expression")).map(|x| prog(&mut b, Some(x), 0.0, &mut errs));

        let mut events = Vec::new();
        if let Some(m) = pe.get("events").and_then(|x| x.as_object()) {
            for (k, v) in m { events.push((k.clone(), event(&mut b, v, &mut errs))); }
        }
        let ele = comp("minecraft:emitter_lifetime_events").cloned().unwrap_or(J::Null);
        let ple = comp("minecraft:particle_lifetime_events").cloned().unwrap_or(J::Null);

        Def {
            texture: brp.and_then(|x| x.get("texture")).and_then(|x| x.as_str()).unwrap_or("").to_string(),
            material: brp.and_then(|x| x.get("material")).and_then(|x| x.as_str()).unwrap_or("particles_alpha").to_string(),
            curves, em_create, em_update, life, rate, shape, local_pos, speed, speed_scalar, spin, p_life, p_expire, kill_plane,
            accel, drag, rot_accel, rot_drag, param_pos, param_dir, param_rot, size, facing, custom_dir, min_speed, uv, tex_size, tint, lit,
            per_render, events,
            em_create_ev: strs(ele.get("creation_event")), em_expire_ev: strs(ele.get("expiration_event")), em_timeline: timeline(ele.get("timeline")),
            p_create_ev: strs(ple.get("creation_event")), p_expire_ev: strs(ple.get("expiration_event")), p_timeline: timeline(ple.get("timeline")),
            v, book: b, errors: errs,
        }
    }

    pub fn lit(&self) -> bool {
        self.lit
    }
}

// ── running ─────────────────────────────────────────────────────────────────

#[derive(Clone)]
struct P {
    pos: [f32; 3],
    vel: [f32; 3],
    rot: f32,
    rot_rate: f32,
    age: f32,
    life: f32,
    r: [f32; 4],
    dir: [f32; 3],
    fired: usize,
}

pub struct Emitter {
    def: Arc<Def>,
    vars: Vec<Val>,
    var_set: Vec<bool>,
    temps: Vec<Val>,
    rng: u32,
    pub origin: [f32; 3],
    pub aabb: [f32; 6],
    age: f32,
    loop_len: f32,
    sleep: f32,
    sleeping: bool,
    pending: f32,
    parts: Vec<P>,
    pub expired: bool,
    started: bool,
    tl_fired: usize,
    // sub effects / sounds the java side has to start: (kind 0 effect 1 sound, name, x, y, z)
    pub requests: Vec<(u8, String, [f32; 3])>,
}

fn rnd(s: &mut u32) -> f32 {
    let mut x = if *s == 0 { 0x2545_F491 } else { *s };
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *s = x;
    (x >> 8) as f32 / (1u32 << 24) as f32
}

fn norm(v: [f32; 3]) -> [f32; 3] {
    let l = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt();
    if l < 1e-6 { [0.0; 3] } else { [v[0] / l, v[1] / l, v[2] / l] }
}

impl Emitter {
    pub fn new(def: Arc<Def>, origin: [f32; 3], seed: u32) -> Emitter {
        let n = def.book.vars.len();
        Emitter {
            vars: vec![Val::default(); n], var_set: vec![false; n], temps: Vec::new(), rng: seed | 1,
            origin, aabb: [0.0; 6], age: 0.0, loop_len: 0.0, sleep: 0.0, sleeping: false, pending: 0.0,
            parts: Vec::new(), expired: false, started: false, tl_fired: 0, requests: Vec::new(), def,
        }
    }

    pub fn alive(&self) -> bool {
        !(self.expired && self.parts.is_empty())
    }

    fn set(&mut self, slot: u32, v: f32) {
        self.vars[slot as usize] = Val::Num(v);
        self.var_set[slot as usize] = true;
    }

    fn fire(&mut self, names: &[String], at: [f32; 3]) {
        let def = self.def.clone();
        for n in names {
            if let Some((_, ev)) = def.events.iter().find(|(k, _)| k == n) {
                self.run_event(ev, at);
            }
        }
    }

    fn run_event(&mut self, ev: &Ev, at: [f32; 3]) {
        match ev {
            Ev::Seq(v) => for e in v { self.run_event(e, at); },
            Ev::Rand(v) => {
                let total: f32 = v.iter().map(|(w, _)| *w).sum();
                let mut pick = rnd(&mut self.rng) * total;
                for (w, e) in v {
                    if pick <= *w { self.run_event(e, at); break; }
                    pick -= w;
                }
            }
            Ev::Node { effect, sound, expr } => {
                if let Some(p) = expr {
                    let def = self.def.clone();
                    let mut rng = self.rng;
                    let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
                    p.eval(&mut cx);
                    let _ = def;
                    self.rng = rng;
                }
                if let Some((e, _)) = effect { self.requests.push((0, e.clone(), at)); }
                if let Some(s) = sound { self.requests.push((1, s.clone(), at)); }
            }
        }
    }

    fn curves(&mut self) {
        let def = self.def.clone();
        for c in &def.curves {
            let mut rng = self.rng;
            let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
            let range = c.range.num(&mut cx).max(1e-6);
            let t = (c.input.num(&mut cx) / range).clamp(0.0, 1.0);
            let val = match &c.kind {
                CurveKind::Linear(n) if !n.is_empty() => {
                    let f = t * (n.len() - 1) as f32;
                    let i = (f as usize).min(n.len() - 1);
                    let j = (i + 1).min(n.len() - 1);
                    let (a, b) = (n[i].num(&mut cx), n[j].num(&mut cx));
                    a + (b - a) * (f - i as f32)
                }
                CurveKind::Bezier(n) if n.len() >= 4 => {
                    let (p0, p1, p2, p3) = (n[0].num(&mut cx), n[1].num(&mut cx), n[2].num(&mut cx), n[3].num(&mut cx));
                    let u = 1.0 - t;
                    u * u * u * p0 + 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t * p3
                }
                CurveKind::Catmull(n) if n.len() >= 4 => {
                    // first and last nodes only shape the tangents
                    let segs = n.len() - 3;
                    let f = t * segs as f32;
                    let i = (f as usize).min(segs - 1);
                    let u = f - i as f32;
                    let (p0, p1, p2, p3) = (n[i].num(&mut cx), n[i + 1].num(&mut cx), n[i + 2].num(&mut cx), n[i + 3].num(&mut cx));
                    0.5 * (2.0 * p1 + (-p0 + p2) * u + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u * u + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u * u * u)
                }
                CurveKind::Chain(n) if !n.is_empty() => {
                    let tt = c.input.num(&mut cx);
                    if tt <= n[0].0 { n[0].1.num(&mut cx) } else if tt >= n[n.len() - 1].0 { n[n.len() - 1].2.num(&mut cx) } else {
                        let mut k = 0;
                        while k + 1 < n.len() && n[k + 1].0 <= tt { k += 1; }
                        let (a, b) = (&n[k], &n[k + 1]);
                        let span = (b.0 - a.0).max(1e-6);
                        let u = (tt - a.0) / span;
                        let (p0, p3) = (a.2.num(&mut cx), b.1.num(&mut cx));
                        let p1 = p0 + a.4.num(&mut cx) * span / 3.0;
                        let p2 = p3 - b.3.num(&mut cx) * span / 3.0;
                        let v = 1.0 - u;
                        v * v * v * p0 + 3.0 * v * v * u * p1 + 3.0 * v * u * u * p2 + u * u * u * p3
                    }
                }
                _ => 0.0,
            };
            self.rng = rng;
            self.vars[c.var as usize] = Val::Num(val);
            self.var_set[c.var as usize] = true;
        }
    }

    fn spawn_one(&mut self) {
        let def = self.def.clone();
        let r = [rnd(&mut self.rng), rnd(&mut self.rng), rnd(&mut self.rng), rnd(&mut self.rng)];
        for i in 0..4 { self.set(def.v.p_r[i], r[i]); }
        self.set(def.v.p_age, 0.0);
        let mut rng = self.rng;
        let mut r2 = self.rng.wrapping_mul(747_796_405).wrapping_add(2_891_336_453) | 1;
        let (offset, dir) = {
            let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
            let pick_dir = |d: &Dir, p: [f32; 3], cx: &mut Ctx| -> [f32; 3] {
                match d { Dir::Out => norm(p), Dir::In => { let n = norm(p); [-n[0], -n[1], -n[2]] }, Dir::Vec(v) => v.eval(cx) }
            };
            match &def.shape {
                Shape::Point { offset, dir } => {
                    let o = offset.eval(&mut cx);
                    let d = dir.as_ref().map(|d| d.eval(&mut cx)).unwrap_or([0.0; 3]);
                    (o, d)
                }
                Shape::Sphere { offset, radius, surface, dir } => {
                    let o = offset.eval(&mut cx);
                    let rad = radius.num(&mut cx);
                    let (u, v, w) = (rnd(&mut r2), rnd(&mut r2), rnd(&mut r2));
                    let th = u * std::f32::consts::TAU;
                    let ph = (2.0 * v - 1.0).acos();
                    let rr = if *surface { rad } else { rad * w.cbrt() };
                    let p = [rr * ph.sin() * th.cos(), rr * ph.cos(), rr * ph.sin() * th.sin()];
                    let d = pick_dir(dir, p, &mut cx);
                    ([o[0] + p[0], o[1] + p[1], o[2] + p[2]], d)
                }
                Shape::Box { offset, half, surface, dir } => {
                    let o = offset.eval(&mut cx);
                    let h = half.eval(&mut cx);
                    let mut p = [(rnd(&mut r2) * 2.0 - 1.0) * h[0], (rnd(&mut r2) * 2.0 - 1.0) * h[1], (rnd(&mut r2) * 2.0 - 1.0) * h[2]];
                    if *surface {
                        let ax = (rnd(&mut r2) * 3.0) as usize % 3;
                        p[ax] = if rnd(&mut r2) < 0.5 { -h[ax] } else { h[ax] };
                    }
                    let d = pick_dir(dir, p, &mut cx);
                    ([o[0] + p[0], o[1] + p[1], o[2] + p[2]], d)
                }
                Shape::Disc { offset, radius, surface, dir, normal } => {
                    let o = offset.eval(&mut cx);
                    let n = norm(normal.eval(&mut cx));
                    let rad = radius.num(&mut cx);
                    let a = rnd(&mut r2) * std::f32::consts::TAU;
                    let rr = if *surface { rad } else { rad * rnd(&mut r2).sqrt() };
                    // two axes spanning the disc plane
                    let up = if n[1].abs() < 0.99 { [0.0, 1.0, 0.0] } else { [1.0, 0.0, 0.0] };
                    let t1 = norm([up[1] * n[2] - up[2] * n[1], up[2] * n[0] - up[0] * n[2], up[0] * n[1] - up[1] * n[0]]);
                    let t2 = [n[1] * t1[2] - n[2] * t1[1], n[2] * t1[0] - n[0] * t1[2], n[0] * t1[1] - n[1] * t1[0]];
                    let (c, s) = (a.cos() * rr, a.sin() * rr);
                    let p = [t1[0] * c + t2[0] * s, t1[1] * c + t2[1] * s, t1[2] * c + t2[2] * s];
                    let d = pick_dir(dir, p, &mut cx);
                    ([o[0] + p[0], o[1] + p[1], o[2] + p[2]], d)
                }
                Shape::Custom { offset, dir } => (offset.eval(&mut cx), dir.eval(&mut cx)),
                Shape::Aabb { surface, dir } => {
                    let b = self.aabb;
                    let mut p = [b[0] + rnd(&mut r2) * (b[3] - b[0]), b[1] + rnd(&mut r2) * (b[4] - b[1]), b[2] + rnd(&mut r2) * (b[5] - b[2])];
                    if *surface {
                        let ax = (rnd(&mut r2) * 3.0) as usize % 3;
                        p[ax] = if rnd(&mut r2) < 0.5 { b[ax] } else { b[ax + 3] };
                    }
                    let c = [(b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, (b[2] + b[5]) / 2.0];
                    let rel = [p[0] - c[0], p[1] - c[1], p[2] - c[2]];
                    let d = pick_dir(dir, rel, &mut cx);
                    ([p[0] - self.origin[0], p[1] - self.origin[1], p[2] - self.origin[2]], d)
                }
            }
        };
        self.rng = rng;
        let mut rng = self.rng;
        let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
        let life = def.p_life.num(&mut cx).max(0.0);
        let vel = if let Some(v) = &def.speed {
            v.eval(&mut cx)
        } else {
            let s = def.speed_scalar.as_ref().map(|p| p.num(&mut cx)).unwrap_or(0.0);
            let d = norm(dir);
            [d[0] * s, d[1] * s, d[2] * s]
        };
        let rot = def.spin.0.num(&mut cx);
        let rot_rate = def.spin.1.num(&mut cx);
        self.rng = rng;
        let pos = if def.local_pos { offset } else { [self.origin[0] + offset[0], self.origin[1] + offset[1], self.origin[2] + offset[2]] };
        self.parts.push(P { pos, vel, rot, rot_rate, age: 0.0, life, r, dir: norm(if vel == [0.0; 3] { dir } else { vel }), fired: 0 });
        let at = self.world(pos);
        let ev = def.p_create_ev.clone();
        self.fire(&ev, at);
    }

    fn world(&self, p: [f32; 3]) -> [f32; 3] {
        if self.def.local_pos { [p[0] + self.origin[0], p[1] + self.origin[1], p[2] + self.origin[2]] } else { p }
    }

    // advances everything by dt and writes quads: 16 floats each
    // x y z, w h, rotation deg, u0 v0 u1 v1, r g b a, facing, then direction packed in 3 more below
    pub fn tick(&mut self, dt: f32, cam: [f32; 3], out: &mut Vec<f32>) {
        let def = self.def.clone();
        self.requests.clear();
        if !self.started {
            self.started = true;
            for i in 0..4 { let r = rnd(&mut self.rng); self.set(def.v.em_r[i], r); }
            self.set(def.v.ent_scale, 1.0);
            let mut rng = self.rng;
            {
                let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
                if let Some(p) = &def.em_create { p.eval(&mut cx); }
                self.loop_len = match &def.life { Life::Once(a) | Life::Looping(a, _) => a.num(&mut cx), Life::Expression(..) => f32::MAX };
                self.sleep = match &def.life { Life::Looping(_, s) => s.num(&mut cx), _ => 0.0 };
            }
            self.rng = rng;
            let ev = def.em_create_ev.clone();
            let o = self.origin;
            self.fire(&ev, o);
            if let Rate::Instant(n) = &def.rate {
                let mut rng = self.rng;
                let count = { let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng); n.num(&mut cx) as i32 };
                self.rng = rng;
                self.set(def.v.em_age, 0.0);
                for _ in 0..count.clamp(0, 4096) { self.spawn_one(); }
            }
        }

        if !self.expired {
            self.age += dt;
            self.set(def.v.em_age, self.age);
            let ll = self.loop_len;
            self.set(def.v.em_life, ll);
            self.curves();
            let mut rng = self.rng;
            let (active, expire) = {
                let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
                if let Some(p) = &def.em_update { p.eval(&mut cx); }
                match &def.life {
                    Life::Expression(a, e) => (a.num(&mut cx) != 0.0, e.num(&mut cx) != 0.0),
                    _ => (true, false),
                }
            };
            self.rng = rng;
            // timeline of the emitter loop
            while self.tl_fired < def.em_timeline.len() && def.em_timeline[self.tl_fired].0 <= self.age {
                let names = def.em_timeline[self.tl_fired].1.clone();
                let o = self.origin;
                self.fire(&names, o);
                self.tl_fired += 1;
            }
            let mut emitting = active && !self.sleeping;
            match &def.life {
                Life::Once(_) if self.age >= self.loop_len => { self.expired = true; emitting = false; }
                Life::Looping(..) => {
                    if !self.sleeping && self.age >= self.loop_len {
                        if self.sleep > 0.0 { self.sleeping = true; self.age = 0.0; } else { self.restart_loop(); }
                    } else if self.sleeping && self.age >= self.sleep {
                        self.sleeping = false;
                        self.restart_loop();
                    }
                }
                Life::Expression(..) if expire => { self.expired = true; emitting = false; }
                _ => {}
            }
            if self.expired {
                let ev = def.em_expire_ev.clone();
                let o = self.origin;
                self.fire(&ev, o);
            }
            if emitting {
                if let Rate::Steady(rate, max) = &def.rate {
                    let mut rng = self.rng;
                    let (r, m) = { let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng); (rate.num(&mut cx), max.num(&mut cx)) };
                    self.rng = rng;
                    self.pending += r * dt;
                    while self.pending >= 1.0 && (self.parts.len() as f32) < m {
                        self.pending -= 1.0;
                        self.spawn_one();
                    }
                    if self.pending > 1.0 { self.pending = 1.0; }
                }
            }
        }

        // particles
        let mut i = 0;
        while i < self.parts.len() {
            let mut p = self.parts[i].clone();
            p.age += dt;
            for k in 0..4 { self.set(def.v.p_r[k], p.r[k]); }
            self.set(def.v.p_age, p.age);
            self.set(def.v.p_life, p.life);
            self.curves();
            let mut rng = self.rng;
            let mut dead = p.life > 0.0 && p.age >= p.life;
            let quad;
            {
                let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
                if let Some(e) = &def.p_expire { if e.num(&mut cx) != 0.0 { dead = true; } }
                if let Some(a) = &def.accel {
                    let acc = a.eval(&mut cx);
                    let drag = def.drag.num(&mut cx);
                    for k in 0..3 { p.vel[k] += (acc[k] - drag * p.vel[k]) * dt; }
                }
                let ra = def.rot_accel.num(&mut cx) - def.rot_drag.num(&mut cx) * p.rot_rate;
                p.rot_rate += ra * dt;
                p.rot += p.rot_rate * dt;
                for k in 0..3 { p.pos[k] += p.vel[k] * dt; }
                if let Some(pp) = &def.param_pos {
                    let rel = pp.eval(&mut cx);
                    p.pos = if def.local_pos { rel } else { [self.origin[0] + rel[0], self.origin[1] + rel[1], self.origin[2] + rel[2]] };
                }
                if let Some(pr) = &def.param_rot { p.rot = pr.num(&mut cx); }
                if let Some(pd) = &def.param_dir { p.dir = norm(pd.eval(&mut cx)); }
                else if let Some(cd) = &def.custom_dir { p.dir = norm(cd.eval(&mut cx)); }
                else {
                    let sp = (p.vel[0] * p.vel[0] + p.vel[1] * p.vel[1] + p.vel[2] * p.vel[2]).sqrt();
                    if sp > def.min_speed { p.dir = norm(p.vel); }
                }
                if let Some(pr) = &def.per_render { pr.eval(&mut cx); }
                quad = quad_of(&def, self.origin, &p, &mut cx, cam);
            }
            self.rng = rng;
            if let Some(kp) = def.kill_plane {
                let rel = if def.local_pos { p.pos } else { [p.pos[0] - self.origin[0], p.pos[1] - self.origin[1], p.pos[2] - self.origin[2]] };
                if kp[0] * rel[0] + kp[1] * rel[1] + kp[2] * rel[2] + kp[3] > 0.0 { dead = true; }
            }
            while p.fired < def.p_timeline.len() && def.p_timeline[p.fired].0 <= p.age {
                let names = def.p_timeline[p.fired].1.clone();
                let at = self.world(p.pos);
                self.fire(&names, at);
                p.fired += 1;
            }
            if dead {
                let names = def.p_expire_ev.clone();
                let at = self.world(p.pos);
                self.fire(&names, at);
                self.parts.swap_remove(i);
                continue;
            }
            out.extend_from_slice(&quad);
            self.parts[i] = p;
            i += 1;
        }
    }

    fn restart_loop(&mut self) {
        let def = self.def.clone();
        self.age = 0.0;
        self.tl_fired = 0;
        for i in 0..4 { let r = rnd(&mut self.rng); self.set(def.v.em_r[i], r); }
        let mut rng = self.rng;
        {
            let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng);
            if let Life::Looping(a, s) = &def.life {
                self.loop_len = a.num(&mut cx);
                self.sleep = s.num(&mut cx);
            }
        }
        self.rng = rng;
        if let Rate::Instant(n) = &def.rate {
            let mut rng = self.rng;
            let count = { let mut cx = Ctx::new(&[], &mut self.vars, &mut self.var_set, &mut self.temps, &[], &mut rng); n.num(&mut cx) as i32 };
            self.rng = rng;
            for _ in 0..count.clamp(0, 4096) { self.spawn_one(); }
        }
    }

}

fn quad_of(def: &Def, origin: [f32; 3], p: &P, cx: &mut Ctx, _cam: [f32; 3]) -> [f32; 19] {
    {
        let w = def.size[0].num(cx);
        let h = def.size[1].num(cx);
        let (tw, th) = (def.tex_size[0].max(1e-6), def.tex_size[1].max(1e-6));
        let (u0, v0, us, vs) = match &def.uv {
            Uv::Fixed { uv, size } => (uv[0].num(cx), uv[1].num(cx), size[0].num(cx), size[1].num(cx)),
            Uv::Flip { base, size, step, fps, max, stretch, looping } => {
                let maxf = max.num(cx).max(1.0);
                let frame = if *stretch && p.life > 0.0 {
                    (p.age / p.life * maxf).floor()
                } else {
                    (p.age * fps).floor()
                };
                let frame = if *looping { frame % maxf } else { frame.min(maxf - 1.0) };
                (base[0].num(cx) + step[0] * frame, base[1].num(cx) + step[1] * frame, size[0], size[1])
            }
        };
        let rgba = match &def.tint {
            Tint::None => [1.0; 4],
            Tint::Rgba(c) => [c[0].num(cx), c[1].num(cx), c[2].num(cx), c[3].num(cx)],
            Tint::Gradient(stops, interp) => {
                let t = interp.num(cx);
                if stops.is_empty() { [1.0; 4] } else if t <= stops[0].0 { stops[0].1 } else if t >= stops[stops.len() - 1].0 { stops[stops.len() - 1].1 } else {
                    let mut k = 0;
                    while k + 1 < stops.len() && stops[k + 1].0 <= t { k += 1; }
                    let (a, b) = (&stops[k], &stops[k + 1]);
                    let f = (t - a.0) / (b.0 - a.0).max(1e-6);
                    [a.1[0] + (b.1[0] - a.1[0]) * f, a.1[1] + (b.1[1] - a.1[1]) * f, a.1[2] + (b.1[2] - a.1[2]) * f, a.1[3] + (b.1[3] - a.1[3]) * f]
                }
            }
        };
        let pos = if def.local_pos { [p.pos[0] + origin[0], p.pos[1] + origin[1], p.pos[2] + origin[2]] } else { p.pos };
        [pos[0], pos[1], pos[2], w, h, p.rot, u0 / tw, v0 / th, (u0 + us) / tw, (v0 + vs) / th,
         rgba[0], rgba[1], rgba[2], rgba[3], def.facing as u8 as f32, p.dir[0], p.dir[1], p.dir[2], 0.0]
    }
}

pub const QUAD_FLOATS: usize = 19;

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn steady_emitter_with_gravity_and_gradient() {
        let src = json!({"format_version": "1.10.0", "particle_effect": {
            "description": {"identifier": "t:puff", "basic_render_parameters": {"material": "particles_alpha", "texture": "textures/particle/particles"}},
            "curves": {"variable.size": {"type": "linear", "input": "v.particle_age / v.particle_lifetime", "nodes": [0.1, 0.5]}},
            "components": {
                "minecraft:emitter_rate_steady": {"spawn_rate": 20, "max_particles": 100},
                "minecraft:emitter_lifetime_looping": {"active_time": 1},
                "minecraft:emitter_shape_sphere": {"radius": 0.5, "direction": "outwards"},
                "minecraft:particle_lifetime_expression": {"max_lifetime": "1 + v.particle_random_1"},
                "minecraft:particle_initial_speed": 2,
                "minecraft:particle_motion_dynamic": {"linear_acceleration": [0, -9.8, 0], "linear_drag_coefficient": 0.5},
                "minecraft:particle_appearance_billboard": {"size": ["v.size", "v.size"], "facing_camera_mode": "lookat_xyz",
                    "uv": {"texture_width": 128, "texture_height": 128, "flipbook": {"base_UV": [56, 88], "size_UV": [8, 8], "step_UV": [-8, 0], "frames_per_second": 8, "max_frame": 8, "stretch_to_lifetime": true}}},
                "minecraft:particle_appearance_tinting": {"color": {"gradient": {"0.0": "#FFFFFFFF", "1.0": "#00FF0000"}, "interpolant": "v.particle_age / v.particle_lifetime"}}
            }}});
        let def = Arc::new(Def::build(&src));
        assert!(def.errors.is_empty(), "{:?}", def.errors);
        let mut e = Emitter::new(def, [10.0, 64.0, 10.0], 7);
        let mut out = Vec::new();
        let mut max = 0;
        for _ in 0..60 {
            out.clear();
            e.tick(1.0 / 20.0, [0.0; 3], &mut out);
            max = max.max(out.len() / QUAD_FLOATS);
        }
        assert!(max > 5 && max <= 100, "{max}");
        let q = &out[0..QUAD_FLOATS];
        assert!(q[3] >= 0.1 && q[3] <= 0.5, "size {}", q[3]);
        assert!(q[6] >= 0.0 && q[8] <= 64.0 / 128.0 + 1e-3, "uv {} {}", q[6], q[8]);
        assert!(q[1] < 64.0 + 2.0);
        assert!(e.alive());
    }

    #[test]
    fn instant_once_expires_and_fires_events() {
        let src = json!({"particle_effect": {
            "description": {"identifier": "t:pop", "basic_render_parameters": {"material": "particles_blend", "texture": "textures/particle/x"}},
            "events": {"boom": {"sequence": [{"sound_effect": {"event_name": "random.pop"}}, {"particle_effect": {"effect": "t:puff", "type": "emitter"}}]}},
            "components": {
                "minecraft:emitter_rate_instant": {"num_particles": 12},
                "minecraft:emitter_lifetime_once": {"active_time": 0.2},
                "minecraft:emitter_lifetime_events": {"expiration_event": "boom"},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 0.5},
                "minecraft:particle_appearance_billboard": {"size": [0.2, 0.2]}
            }}});
        let def = Arc::new(Def::build(&src));
        let mut e = Emitter::new(def, [0.0; 3], 3);
        let mut out = Vec::new();
        e.tick(0.05, [0.0; 3], &mut out);
        assert_eq!(out.len() / QUAD_FLOATS, 12);
        let mut saw = 0;
        for _ in 0..20 {
            out.clear();
            e.tick(0.05, [0.0; 3], &mut out);
            saw += e.requests.len();
        }
        assert_eq!(saw, 2, "sound + sub effect once");
        assert!(!e.alive());
    }

    // KOPER_PARTICLES_DIR = any folder of particle jsons you own. builds and runs every one
    #[test]
    fn every_effect_in_a_folder() {
        let Ok(dir) = std::env::var("KOPER_PARTICLES_DIR") else { return };
        let mut files = Vec::new();
        let mut stack = vec![std::path::PathBuf::from(dir)];
        while let Some(d) = stack.pop() {
            for e in std::fs::read_dir(d).unwrap().flatten() {
                let p = e.path();
                if p.is_dir() { stack.push(p); } else if p.extension().map(|x| x == "json").unwrap_or(false) { files.push(p); }
            }
        }
        let (mut ok, mut errs, mut quads) = (0, 0, 0usize);
        let start = std::time::Instant::now();
        for f in &files {
            let Ok(j) = serde_json::from_slice::<J>(&std::fs::read(f).unwrap()) else { continue };
            if j.get("particle_effect").is_none() { continue; }
            let def = Arc::new(Def::build(&j));
            if !def.errors.is_empty() { errs += 1; println!("{}: {:?}", f.display(), &def.errors[..1]); }
            ok += 1;
            let mut e = Emitter::new(def, [0.0, 64.0, 0.0], 11);
            let mut out = Vec::new();
            for _ in 0..60 { out.clear(); e.tick(1.0 / 30.0, [0.0; 3], &mut out); quads += out.len() / QUAD_FLOATS; }
        }
        println!("particles: {ok} effects, {errs} with molang errors, {quads} quads in {:?}", start.elapsed());
    }
}

