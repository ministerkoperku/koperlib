// behavior pack animations and animation controllers, run on the server. no bones here: a bp
// animation is a timeline of commands ("/effect @s speed"), events ("@s koper:enrage") and molang
// ("v.phase = 2;"), a controller is states with transitions, on_entry and on_exit of the same three.
// the entity's scripts.animate list decides what runs. compiled once per entity type like kodel's
// client actor, java fills the query slots every tick and gets back only the commands and events
// that fired (almost always none)
use koperlib_molang::{Book, Ctx, Program, Val};
use serde_json::Value as J;
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Clone, Copy, PartialEq, Debug)]
enum Gra {
    Anim(usize),
    Ctrl(usize),
}

enum Krok {
    Molang(Program),
    // index into Def.fired: a "/command" or an "@s event" line, handed to java as text
    Wyslij(u32),
}

struct Anim {
    length: f32,
    looping: bool,
    time_update: Option<Program>,
    timeline: Vec<(f32, Vec<Krok>)>,
}

struct Stan {
    anims: Vec<(Gra, Option<Program>)>,
    transitions: Vec<(usize, Program)>,
    on_entry: Vec<Krok>,
    on_exit: Vec<Krok>,
}

struct Ctrl {
    initial: usize,
    states: Vec<Stan>,
}

pub struct Def {
    book: Book,
    anims: Vec<Anim>,
    ctrls: Vec<Ctrl>,
    animate: Vec<(Gra, Option<Program>)>,
    initialize: Vec<Krok>,
    pre: Vec<Krok>,
    pub fired: Vec<String>,
    pub errors: Vec<String>,
}

fn lower(s: &str) -> String {
    s.to_ascii_lowercase()
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

// one list of lines: commands and events go to java as text, molang runs here. consecutive molang lines
// are glued when they only parse together, the way bedrock reads a whole array as one expression
fn kroki(book: &mut Book, v: Option<&J>, fired: &mut Vec<String>, errors: &mut Vec<String>) -> Vec<Krok> {
    let list: Vec<J> = match v {
        Some(J::Array(xs)) => xs.clone(),
        Some(J::String(s)) => vec![J::String(s.clone())],
        _ => return Vec::new(),
    };
    let mut out = Vec::new();
    let mut molang: Vec<String> = Vec::new();
    let flush = |molang: &mut Vec<String>, out: &mut Vec<Krok>, book: &mut Book, errors: &mut Vec<String>| {
        if molang.is_empty() {
            return;
        }
        let mut one = Vec::new();
        let singles: Vec<Program> = molang.iter().map(|m| prog(book, &J::String(m.clone()), &mut one)).collect();
        if one.is_empty() || molang.len() < 2 {
            errors.extend(one);
            out.extend(singles.into_iter().map(Krok::Molang));
        } else {
            let glued: String = molang.iter().map(|m| {
                let t = m.trim();
                if t.ends_with([';', '{', '}', '(', ',', '?', ':', '&', '|']) { format!("{t}\n") } else { format!("{t};\n") }
            }).collect();
            let (p, e) = book.compile(&glued);
            if e.is_none() {
                out.push(Krok::Molang(p));
            } else {
                errors.extend(one);
                out.extend(singles.into_iter().map(Krok::Molang));
            }
        }
        molang.clear();
    };
    for x in &list {
        let Some(s) = x.as_str() else { continue };
        let t = s.trim();
        if t.is_empty() {
            continue;
        }
        if t.starts_with('/') || t.starts_with('@') {
            flush(&mut molang, &mut out, book, errors);
            let id = match fired.iter().position(|f| f == t) {
                Some(i) => i,
                None => {
                    fired.push(t.to_string());
                    fired.len() - 1
                }
            };
            out.push(Krok::Wyslij(id as u32));
        } else {
            molang.push(t.to_string());
        }
    }
    flush(&mut molang, &mut out, book, errors);
    out
}

impl Def {
    // {"animations": {id: json}, "controllers": {id: json}, "short": {name: id}, "scripts": {"animate": [..], "initialize": [..], "pre_animation": [..]}}
    pub fn build(src: &J) -> Def {
        let mut book = Book::default();
        let mut errors = Vec::new();
        let mut fired = Vec::new();
        let empty = serde_json::Map::new();
        let anim_src = src.get("animations").and_then(|x| x.as_object()).unwrap_or(&empty);
        let ctrl_src = src.get("controllers").and_then(|x| x.as_object()).unwrap_or(&empty);
        let short = src.get("short").and_then(|x| x.as_object()).unwrap_or(&empty);
        let anim_ids: HashMap<String, usize> = anim_src.keys().enumerate().map(|(i, k)| (lower(k), i)).collect();
        let ctrl_ids: HashMap<String, usize> = ctrl_src.keys().enumerate().map(|(i, k)| (lower(k), i)).collect();
        let resolve = |name: &str| -> Option<Gra> {
            let n = lower(name);
            let full = short.iter().find(|(k, _)| lower(k) == n).and_then(|(_, v)| v.as_str()).map(lower).unwrap_or(n);
            anim_ids.get(&full).map(|&i| Gra::Anim(i)).or_else(|| ctrl_ids.get(&full).map(|&i| Gra::Ctrl(i)))
        };
        let lista = |v: Option<&J>, book: &mut Book, errors: &mut Vec<String>| -> Vec<(Gra, Option<Program>)> {
            let mut out = Vec::new();
            match v {
                Some(J::Array(xs)) => {
                    for x in xs {
                        match x {
                            J::String(n) => {
                                if let Some(g) = resolve(n) { out.push((g, None)); }
                            }
                            J::Object(m) => {
                                for (n, w) in m {
                                    if let Some(g) = resolve(n) { out.push((g, Some(prog(book, w, errors)))); }
                                }
                            }
                            _ => {}
                        }
                    }
                }
                Some(J::String(n)) => {
                    if let Some(g) = resolve(n) { out.push((g, None)); }
                }
                _ => {}
            }
            out
        };

        let mut anims = Vec::new();
        for (_, a) in anim_src {
            let mut timeline = Vec::new();
            if let Some(tl) = a.get("timeline").and_then(|x| x.as_object()) {
                for (t, v) in tl {
                    let Ok(time) = t.trim().parse::<f32>() else { continue };
                    timeline.push((time, kroki(&mut book, Some(v), &mut fired, &mut errors)));
                }
            }
            timeline.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
            let mut length = a.get("animation_length").and_then(|x| x.as_f64()).map(|x| x as f32).unwrap_or(-1.0);
            if length < 0.0 {
                length = timeline.last().map(|t| t.0).unwrap_or(0.0);
            }
            let looping = matches!(a.get("loop"), Some(J::Bool(true))) || a.get("loop").and_then(|x| x.as_str()) == Some("true");
            anims.push(Anim {
                length,
                looping,
                time_update: a.get("anim_time_update").map(|v| prog(&mut book, v, &mut errors)),
                timeline,
            });
        }

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
                states.push(Stan {
                    anims: lista(s.get("animations"), &mut book, &mut errors),
                    transitions,
                    on_entry: kroki(&mut book, s.get("on_entry"), &mut fired, &mut errors),
                    on_exit: kroki(&mut book, s.get("on_exit"), &mut fired, &mut errors),
                });
            }
            let initial = c.get("initial_state").and_then(|x| x.as_str()).and_then(|n| names.iter().position(|s| *s == lower(n)))
                .or_else(|| names.iter().position(|s| s == "default")).unwrap_or(0);
            ctrls.push(Ctrl { initial, states });
        }
        let scripts = src.get("scripts");
        let animate = lista(scripts.and_then(|s| s.get("animate")), &mut book, &mut errors);
        let initialize = kroki(&mut book, scripts.and_then(|s| s.get("initialize")), &mut fired, &mut errors);
        let pre = kroki(&mut book, scripts.and_then(|s| s.get("pre_animation")), &mut fired, &mut errors);
        Def { book, anims, ctrls, animate, initialize, pre, fired, errors }
    }

    pub fn queries(&self) -> &[String] {
        &self.book.queries
    }

    pub fn strings(&self) -> &[String] {
        &self.book.strings
    }
}

#[derive(Clone, Default)]
struct AnimRun {
    time: f32,
    finished: bool,
}

#[derive(Clone)]
struct CtrlRun {
    state: usize,
    time: f32,
    entered: bool,
}

pub struct Inst {
    def: Arc<Def>,
    vars: Vec<Val>,
    var_set: Vec<bool>,
    temps: Vec<Val>,
    rng: u32,
    started: bool,
    anims: Vec<AnimRun>,
    ctrls: Vec<CtrlRun>,
    out: Vec<u32>,
}

impl Inst {
    pub fn new(def: Arc<Def>, seed: u32) -> Inst {
        Inst {
            vars: vec![Val::default(); def.book.vars.len()],
            var_set: vec![false; def.book.vars.len()],
            temps: Vec::new(),
            rng: seed | 1,
            started: false,
            anims: vec![AnimRun::default(); def.anims.len()],
            ctrls: def.ctrls.iter().map(|c| CtrlRun { state: c.initial, time: 0.0, entered: false }).collect(),
            out: Vec::new(),
            def,
        }
    }

    // one server tick. returns indices into def.fired, in order
    pub fn tick(&mut self, queries: &[f32], qstr: &[i32], dt: f32) -> &[u32] {
        let def = self.def.clone();
        self.out.clear();
        let mut vars = std::mem::take(&mut self.vars);
        let mut set = std::mem::take(&mut self.var_set);
        let mut temps = std::mem::take(&mut self.temps);
        let mut rng = self.rng;
        {
            let arrays: Vec<Vec<Val>> = Vec::new();
            let mut cx = Ctx::new(queries, &mut vars, &mut set, &mut temps, &arrays, &mut rng);
            cx.qstr = qstr;
            cx.delta_time = dt;
            if !self.started {
                self.started = true;
                run(&def.initialize, &mut cx, &mut self.out);
            }
            run(&def.pre, &mut cx, &mut self.out);
            for (g, cond) in &def.animate {
                if let Some(c) = cond {
                    if !c.eval(&mut cx).truthy() {
                        continue;
                    }
                }
                self.gra(&def, *g, dt, &mut cx, 0);
            }
        }
        self.vars = vars;
        self.var_set = set;
        self.temps = temps;
        self.rng = rng;
        &self.out
    }

    fn gra(&mut self, def: &Def, g: Gra, dt: f32, cx: &mut Ctx, depth: u32) {
        if depth > 8 {
            return;
        }
        match g {
            Gra::Anim(a) => self.anim(def, a, dt, cx),
            Gra::Ctrl(c) => self.ctrl(def, c, dt, cx, depth),
        }
    }

    fn anim(&mut self, def: &Def, ai: usize, dt: f32, cx: &mut Ctx) {
        let a = &def.anims[ai];
        let mut run = self.anims[ai].clone();
        if run.finished && !a.looping {
            return;
        }
        let before = run.time;
        cx.anim_time = run.time;
        run.time = match &a.time_update {
            Some(p) => p.num(cx),
            None => run.time + dt,
        };
        let mut t = run.time;
        let mut wrapped = false;
        if a.length > 0.0 && t >= a.length {
            if a.looping {
                t %= a.length;
                run.time = t;
                wrapped = true;
            } else {
                t = a.length;
                run.finished = true;
            }
        }
        cx.anim_time = t;
        for (kt, steps) in &a.timeline {
            // what we passed this tick; on a wrap the tail of the last loop and the head of this one
            let hit = if wrapped { *kt > before || *kt <= t } else { (*kt > before || (before == 0.0 && *kt == 0.0)) && *kt <= t };
            if hit {
                run_steps(steps, cx, &mut self.out);
            }
        }
        self.anims[ai] = run;
    }

    fn ctrl(&mut self, def: &Def, ci: usize, dt: f32, cx: &mut Ctx, depth: u32) {
        let c = &def.ctrls[ci];
        if c.states.is_empty() {
            return;
        }
        let mut run = self.ctrls[ci].clone();
        if !run.entered {
            run.entered = true;
            self.enter(def, &c.states[run.state], cx);
        }
        let (all, any) = self.finished(def, &c.states[run.state]);
        cx.all_finished = all;
        cx.any_finished = any;
        cx.state_time = run.time;
        let mut next = None;
        for (to, cond) in &c.states[run.state].transitions {
            if cond.eval(cx).truthy() {
                next = Some(*to);
                break;
            }
        }
        if let Some(to) = next {
            if to != run.state {
                run_steps(&c.states[run.state].on_exit, cx, &mut self.out);
                run.state = to;
                run.time = 0.0;
                self.enter(def, &c.states[to], cx);
            }
        }
        run.time += dt;
        cx.state_time = run.time;
        self.ctrls[ci] = run.clone();
        for (g, cond) in &c.states[run.state].anims {
            if let Some(w) = cond {
                if !w.eval(cx).truthy() {
                    continue;
                }
            }
            self.gra(def, *g, dt, cx, depth + 1);
        }
    }

    fn enter(&mut self, def: &Def, st: &Stan, cx: &mut Ctx) {
        // a state starts its animations from zero, their timelines fire again
        for (g, _) in &st.anims {
            if let Gra::Anim(a) = g {
                self.anims[*a] = AnimRun::default();
            }
        }
        let _ = def;
        run_steps(&st.on_entry, cx, &mut self.out);
    }

    fn finished(&self, def: &Def, st: &Stan) -> (bool, bool) {
        let (mut all, mut any, mut seen) = (true, false, false);
        for (g, _) in &st.anims {
            if let Gra::Anim(a) = g {
                if def.anims[*a].looping {
                    continue;
                }
                seen = true;
                all &= self.anims[*a].finished;
                any |= self.anims[*a].finished;
            }
        }
        (seen && all, any)
    }
}

fn run(steps: &[Krok], cx: &mut Ctx, out: &mut Vec<u32>) {
    run_steps(steps, cx, out)
}

fn run_steps(steps: &[Krok], cx: &mut Ctx, out: &mut Vec<u32>) {
    for s in steps {
        match s {
            Krok::Molang(p) => {
                p.eval(cx);
            }
            Krok::Wyslij(i) => out.push(*i),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn fired(d: &Def, ids: &[u32]) -> Vec<String> {
        ids.iter().map(|i| d.fired[*i as usize].clone()).collect()
    }

    #[test]
    fn robot_in_water_runs_on_exit_commands() {
        // microsoft's robot sample, bp side
        let d = Arc::new(Def::build(&json!({
            "controllers": {"controller.animation.robot.in_water": {"states": {
                "default": {"transitions": [{"in_water": "query.is_in_water_or_rain"}]},
                "in_water": {"on_exit": ["/effect @s regeneration 2 4 true", "/playsound random.fizz @a[r=16]"],
                             "transitions": [{"default": "query.is_in_water_or_rain == 0"}]}}}},
            "short": {"in_water": "controller.animation.robot.in_water"},
            "scripts": {"animate": ["in_water"]}
        })));
        assert!(d.errors.is_empty(), "{:?}", d.errors);
        let slot = d.queries().iter().position(|q| q == "is_in_water_or_rain").unwrap();
        let mut i = Inst::new(d.clone(), 1);
        let mut q = vec![0f32; d.queries().len()];
        assert!(i.tick(&q, &[], 0.05).is_empty());
        q[slot] = 1.0;
        assert!(i.tick(&q, &[], 0.05).is_empty(), "entering water fires nothing");
        q[slot] = 0.0;
        let out = i.tick(&q, &[], 0.05).to_vec();
        assert_eq!(fired(&d, &out), vec!["/effect @s regeneration 2 4 true", "/playsound random.fizz @a[r=16]"]);
    }

    #[test]
    fn timeline_events_molang_and_loops() {
        let d = Arc::new(Def::build(&json!({
            "animations": {
                "animation.boss.attack": {"animation_length": 1.0, "timeline": {
                    "0.0": ["v.swings = (v.swings ?? 0) + 1;"], "0.5": ["@s koper:hit", "/say bam"]}},
                "animation.boss.tick": {"loop": true, "animation_length": 0.5, "timeline": {"0.25": "@s koper:pulse"}}
            },
            "controllers": {"controller.animation.boss": {"initial_state": "idle", "states": {
                "idle": {"transitions": [{"attack": "q.has_target"}]},
                "attack": {"animations": ["attack"], "on_entry": ["@s koper:roar"],
                           "transitions": [{"idle": "q.all_animations_finished"}]}}}},
            "short": {"attack": "animation.boss.attack", "tick": "animation.boss.tick", "brain": "controller.animation.boss"},
            "scripts": {"animate": ["brain", {"tick": "q.is_alive"}]}
        })));
        assert!(d.errors.is_empty(), "{:?}", d.errors);
        let names = d.queries().to_vec();
        let set = |q: &mut Vec<f32>, n: &str, v: f32| { let i = names.iter().position(|x| x == n).unwrap(); q[i] = v; };
        let mut q = vec![0f32; names.len()];
        set(&mut q, "is_alive", 1.0);
        set(&mut q, "has_target", 1.0);
        let mut i = Inst::new(d.clone(), 1);
        let mut all = Vec::new();
        for k in 0..24 {
            // the target goes away after the attack started: it plays out once and the brain goes idle
            if k == 2 { set(&mut q, "has_target", 0.0); }
            let out = i.tick(&q, &[], 0.05).to_vec();
            all.extend(fired(&d, &out));
        }
        assert_eq!(all.iter().filter(|x| *x == "@s koper:roar").count(), 1);
        assert_eq!(all.iter().filter(|x| *x == "@s koper:hit").count(), 1, "{all:?}");
        assert!(all.iter().filter(|x| *x == "@s koper:pulse").count() >= 2, "looping timeline fires every loop: {all:?}");
        let sw = d.book.var_id("swings").unwrap() as usize;
        assert_eq!(i.vars[sw].num(), 1.0);
    }
}
