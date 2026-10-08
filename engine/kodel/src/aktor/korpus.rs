// every client entity and attachable of a real resource pack, built and ticked the way BrTyp and
// BrAktorzy do it. KOPER_BEDROCK_RP=<rp folder>[:<more rp folders, later ones win>] cargo test -p kodel
// korpus -- --nocapture. without the variable it does nothing, packs are not in the repo.
// prints what would go wrong in game: molang that does not compile, names that resolve to nothing,
// transitions to states that do not exist, matrices that blow up
use super::*;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

// bedrock json allows // and /* */ comments and trailing commas, gson lenient eats them on the java side
pub(crate) fn lenient(raw: &str) -> String {
    let b = raw.trim_start_matches('\u{feff}').as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let (mut i, mut in_str) = (0, false);
    while i < b.len() {
        let c = b[i];
        if in_str {
            out.push(c);
            if c == b'\\' && i + 1 < b.len() {
                out.push(b[i + 1]);
                i += 2;
                continue;
            }
            if c == b'"' {
                in_str = false;
            }
            i += 1;
            continue;
        }
        if c == b'"' {
            in_str = true;
        } else if c == b'/' && b.get(i + 1) == Some(&b'/') {
            while i < b.len() && b[i] != b'\n' {
                i += 1;
            }
            continue;
        } else if c == b'/' && b.get(i + 1) == Some(&b'*') {
            i += 2;
            while i + 1 < b.len() && !(b[i] == b'*' && b[i + 1] == b'/') {
                i += 1;
            }
            i += 2;
            continue;
        } else if c == b',' {
            let mut j = i + 1;
            while j < b.len() && (b[j] as char).is_whitespace() {
                j += 1;
            }
            if j < b.len() && (b[j] == b'}' || b[j] == b']') {
                i += 1;
                continue;
            }
        }
        out.push(c);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[derive(Default)]
pub(crate) struct Paczka {
    pub entities: Vec<(PathBuf, J)>,
    pub attachables: Vec<(PathBuf, J)>,
    pub animations: HashMap<String, J>,
    pub controllers: HashMap<String, J>,
    pub renders: HashMap<String, J>,
    pub geometries: HashMap<String, J>,
}

fn files(dir: &Path, into: &mut Vec<PathBuf>) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            files(&p, into);
        } else if p.extension().map(|x| x == "json").unwrap_or(false) {
            into.push(p);
        }
    }
}

// geometry the way BrPaczki.geometries reads it, 1.8 "geometry.a:geometry.b" names included
pub(crate) fn geometries(j: &J, into: &mut HashMap<String, J>) {
    if let Some(list) = j.get("minecraft:geometry").and_then(|x| x.as_array()) {
        for g in list {
            if let Some(id) = g.get("description").and_then(|d| d.get("identifier")).and_then(|x| x.as_str()) {
                into.insert(id.to_string(), g.clone());
            }
        }
    }
    if let Some(m) = j.as_object() {
        for (k, v) in m {
            if k.starts_with("geometry.") && v.is_object() {
                let mut g = v.clone();
                if let (Some((_, parent)), Some(o)) = (k.split_once(':'), g.as_object_mut()) {
                    o.insert("koper:rodzic".into(), J::String(parent.trim().to_string()));
                }
                into.insert(k.split(':').next().unwrap().to_string(), g);
            }
        }
    }
}

pub(crate) fn read(rp: &Path) -> Paczka {
    let mut p = Paczka::default();
    let mut all = Vec::new();
    files(rp, &mut all);
    all.sort();
    for f in all {
        let Ok(raw) = std::fs::read_to_string(&f) else { continue };
        // first value only, like bedrock: some packs glue a {"do not steal"} after the real object
        let fixed = lenient(&raw);
        let Some(Ok(j)) = serde_json::Deserializer::from_str(&fixed).into_iter::<J>().next() else { continue };
        let top = f.strip_prefix(rp).ok().and_then(|r| r.components().next()).map(|c| c.as_os_str().to_string_lossy().to_string()).unwrap_or_default();
        let put = |from: Option<&J>, into: &mut HashMap<String, J>| {
            if let Some(m) = from.and_then(|x| x.as_object()) {
                for (k, v) in m {
                    into.insert(k.clone(), v.clone());
                }
            }
        };
        match top.as_str() {
            "entity" => {
                if let Some(d) = j.get("minecraft:client_entity").and_then(|c| c.get("description")) {
                    p.entities.push((f.clone(), d.clone()));
                }
            }
            "attachables" => {
                if let Some(d) = j.get("minecraft:attachable").and_then(|c| c.get("description")) {
                    p.attachables.push((f.clone(), d.clone()));
                }
            }
            "animations" => put(j.get("animations"), &mut p.animations),
            "animation_controllers" => put(j.get("animation_controllers"), &mut p.controllers),
            "render_controllers" => put(j.get("render_controllers"), &mut p.renders),
            "models" => geometries(&j, &mut p.geometries),
            _ => {}
        }
    }
    p
}

// bones as BrTyp hands them over: name, parent index, pivot, bind rotation, no offset
pub(crate) fn bones(geo: &J) -> J {
    let list = geo.get("bones").and_then(|b| b.as_array()).cloned().unwrap_or_default();
    let names: Vec<String> = list.iter().map(|b| b.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string()).collect();
    let spoczynek = |b: &J| {
        let v = |k: &str, i: usize| b.get(k).and_then(|a| a.get(i)).and_then(|x| x.as_f64()).unwrap_or(0.0);
        serde_json::json!([v("rotation", 0) + v("bind_pose_rotation", 0), v("rotation", 1) + v("bind_pose_rotation", 1), v("rotation", 2) + v("bind_pose_rotation", 2)])
    };
    let mut out = Vec::new();
    for b in &list {
        let parent = b.get("parent").and_then(|x| x.as_str()).and_then(|p| names.iter().position(|n| n.eq_ignore_ascii_case(p))).map(|i| i as i64).unwrap_or(-1);
        out.push(serde_json::json!({
            "name": b.get("name").cloned().unwrap_or(J::Null),
            "parent": parent,
            "pivot": b.get("pivot").cloned().unwrap_or(serde_json::json!([0, 0, 0])),
            // rest rotation = rotation + bind_pose_rotation (old 1.8 geometry), same as KodelConverters.spoczynek
            "rot": spoczynek(b),
            "pos": [0, 0, 0],
        }));
    }
    J::Array(out)
}

#[derive(Default)]
pub(crate) struct Raport {
    pub types: usize,
    pub built: usize,
    pub molang: BTreeMap<String, usize>,
    pub missing_anim: BTreeMap<String, usize>,
    pub missing_render: BTreeMap<String, usize>,
    pub missing_geo: BTreeMap<String, usize>,
    pub bad_state: BTreeMap<String, usize>,
    pub lost_bones: BTreeMap<String, usize>,
    pub nan: Vec<String>,
}

// BrTyp.common: only what this entity names, looked up in its own pack first, then the stack
pub(crate) fn source(packs: &[Paczka], d: &J, geo: Option<&J>, r: &mut Raport) -> J {
    let find = |id: &str, pick: &dyn Fn(&Paczka) -> &HashMap<String, J>| -> Option<J> {
        packs.iter().rev().find_map(|p| pick(p).get(id).cloned())
    };
    let empty = serde_json::Map::new();
    let mut short = d.get("animations").and_then(|x| x.as_object()).cloned().unwrap_or_default();
    let mut scripts = d.get("scripts").cloned().unwrap_or(serde_json::json!({}));
    // BrTyp.legacyKontrolery: a 1.8 entity's "animation_controllers" list all run, under their full ids
    if let Some(list) = d.get("animation_controllers").and_then(|x| x.as_array()) {
        let mut animate = scripts.get("animate").and_then(|x| x.as_array()).cloned().unwrap_or_default();
        for el in list {
            let fulls: Vec<String> = match el {
                J::String(s) => vec![s.clone()],
                J::Object(m) => m.values().filter_map(|v| v.as_str().map(String::from)).collect(),
                _ => vec![],
            };
            for f in fulls {
                short.insert(f.clone(), J::String(f.clone()));
                animate.push(J::String(f));
            }
        }
        if let Some(o) = scripts.as_object_mut() {
            o.insert("animate".into(), J::Array(animate));
        }
    }
    let short = &short;
    let mut anims = serde_json::Map::new();
    let mut ctrls = serde_json::Map::new();
    for (_, full) in short {
        let Some(full) = full.as_str() else { continue };
        if let Some(a) = find(full, &|p| &p.animations) {
            anims.insert(full.to_string(), a);
        } else if let Some(c) = find(full, &|p| &p.controllers) {
            for (_, st) in c.get("states").and_then(|s| s.as_object()).unwrap_or(&empty) {
                for t in st.get("transitions").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
                    for (to, _) in t.as_object().cloned().unwrap_or_default() {
                        if !c.get("states").and_then(|s| s.as_object()).map(|s| s.keys().any(|k| k.eq_ignore_ascii_case(&to))).unwrap_or(false) {
                            *r.bad_state.entry(format!("{full} -> {to}")).or_default() += 1;
                        }
                    }
                }
            }
            ctrls.insert(full.to_string(), c);
        } else {
            *r.missing_anim.entry(full.to_string()).or_default() += 1;
        }
    }
    let mut render = Vec::new();
    for rc in d.get("render_controllers").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
        let (name, cond) = match &rc {
            J::String(s) => (s.clone(), None),
            J::Object(m) => match m.iter().next() {
                Some((k, v)) => (k.clone(), Some(v.clone())),
                None => continue,
            },
            _ => continue,
        };
        match find(&name, &|p| &p.renders) {
            Some(mut c) => {
                if let (Some(cond), Some(o)) = (cond, c.as_object_mut()) {
                    o.insert("condition".into(), cond);
                }
                render.push(c);
            }
            None => *r.missing_render.entry(name).or_default() += 1,
        }
    }
    let keys = |k: &str| -> Vec<J> { d.get(k).and_then(|x| x.as_object()).map(|m| m.keys().map(|k| J::String(k.clone())).collect()).unwrap_or_default() };
    serde_json::json!({
        "bones": geo.map(bones).unwrap_or(J::Array(vec![])),
        "animations": anims,
        "controllers": ctrls,
        "short": short,
        "scripts": scripts,
        "textures": keys("textures"),
        "geometries": keys("geometry"),
        "render": render,
    })
}

// the queries a mob standing around answers. everything else reads 0 like an absent query
fn answer(name: &str, frame: usize) -> f32 {
    let t = frame as f32 / 20.0;
    match name {
        "is_on_ground" | "is_alive" | "has_rider" | "is_first_person" => if name == "is_on_ground" || name == "is_alive" { 1.0 } else { 0.0 },
        "life_time" | "time_stamp" => t,
        "modified_distance_moved" | "walk_distance" => t * 2.0,
        "modified_move_speed" | "ground_speed" => 0.5,
        "head_x_rotation" => (t * 3.0).sin() * 20.0,
        "head_y_rotation" | "target_y_rotation" => (t * 2.0).sin() * 30.0,
        "body_y_rotation" | "yaw_speed" => 10.0,
        "health" => 20.0,
        "max_health" => 20.0,
        "frame_alpha" => 0.5,
        "scale" => 1.0,
        _ => 0.0,
    }
}

// BrPaczki.dziedzicz: parent's bones, a child bone of the same name replaces it, new ones at the end
pub(crate) fn resolve(all: &HashMap<String, J>, id: &str, depth: u32) -> Option<J> {
    let g = all.get(id)?;
    let Some(parent) = g.get("koper:rodzic").and_then(|x| x.as_str()) else { return Some(g.clone()) };
    let Some(p) = (if depth < 8 { resolve(all, parent, depth + 1) } else { None }) else { return Some(g.clone()) };
    let mine = g.get("bones").and_then(|b| b.as_array()).cloned().unwrap_or_default();
    let name = |b: &J| b.get("name").and_then(|x| x.as_str()).unwrap_or("").to_ascii_lowercase();
    let mut bones: Vec<J> = p.get("bones").and_then(|b| b.as_array()).cloned().unwrap_or_default()
        .into_iter().map(|b| mine.iter().find(|c| name(c) == name(&b)).cloned().unwrap_or(b)).collect();
    for c in &mine {
        if !bones.iter().any(|b| name(b) == name(c)) {
            bones.push(c.clone());
        }
    }
    let mut out = p.clone();
    out["bones"] = J::Array(bones);
    Some(out)
}

pub(crate) fn run(packs: &[Paczka], bases: usize, show: bool) -> Raport {
    let mut r = Raport::default();
    let mut all_geos: HashMap<String, J> = HashMap::new();
    for p in packs {
        for (k, v) in &p.geometries {
            all_geos.insert(k.clone(), v.clone());
        }
    }
    for (pi, p) in packs.iter().enumerate().skip(bases) {
        for (f, d) in p.entities.iter().chain(p.attachables.iter()) {
            r.types += 1;
            let id = d.get("identifier").and_then(|x| x.as_str()).unwrap_or("?");
            let geo_map = d.get("geometry").and_then(|x| x.as_object()).cloned().unwrap_or_default();
            let mut any = false;
            for (_, gid) in &geo_map {
                let gid = gid.as_str().unwrap_or("");
                let merged = resolve(&all_geos, gid, 0);
                let geo = merged.as_ref();
                if geo.is_none() {
                    *r.missing_geo.entry(gid.to_string()).or_default() += 1;
                }
                let src = source(&packs[..=pi], d, geo, &mut r);
                // an animation moving a bone the geometry does not have: fine on bedrock too, but a lot of
                // them on one geometry means we lost bones (inheritance, wrong geometry picked)
                if let Some(g) = geo {
                    let have: Vec<String> = g.get("bones").and_then(|b| b.as_array()).map(|b| b.iter().filter_map(|x| x.get("name").and_then(|n| n.as_str())).map(|n| n.to_ascii_lowercase()).collect()).unwrap_or_default();
                    for (aname, a) in src.get("animations").and_then(|x| x.as_object()).cloned().unwrap_or_default() {
                        for (bn, _) in a.get("bones").and_then(|x| x.as_object()).cloned().unwrap_or_default() {
                            if !have.contains(&bn.trim().to_ascii_lowercase()) {
                                *r.lost_bones.entry(format!("{gid} / {aname} / {bn}")).or_default() += 1;
                            }
                        }
                    }
                }
                let def = Arc::new(Def::build(&src));
                for e in &def.errors {
                    *r.molang.entry(e.clone()).or_default() += 1;
                    if show {
                        println!("  {id} ({}): {e}", f.file_name().unwrap().to_string_lossy());
                    }
                }
                let n = def.bone_count();
                let names = def.query_names().to_vec();
                let mut inst = Inst::new(def, 7);
                let mut mats = vec![0f32; n * 16];
                let mut vis = vec![0u8; n];
                let mut info = [0i32; 8];
                let mut ev = [0i32; 16];
                for frame in 0..200 {
                    let q: Vec<f32> = names.iter().map(|q| answer(q, frame)).collect();
                    inst.tick(&q, 0.05, Out { mats: &mut mats, vis: &mut vis, local: None, info: &mut info, events: &mut ev });
                    if mats.iter().any(|x| !x.is_finite()) {
                        r.nan.push(format!("{id} frame {frame}"));
                        break;
                    }
                }
                if std::env::var("KOPER_BEDROCK_STAN").ok().as_deref() == Some(id) {
                    let (st, rot) = inst.debug_states();
                    println!("{id}: controller states {st:?}");
                    for (i, b) in def_names(&src).iter().enumerate() {
                        println!("  {b}: {:?}", rot.get(i));
                    }
                }
                any = true;
            }
            if any {
                r.built += 1;
            }
        }
    }
    r
}

fn def_names(src: &J) -> Vec<String> {
    src.get("bones").and_then(|b| b.as_array()).map(|b| b.iter().map(|x| x.get("name").and_then(|n| n.as_str()).unwrap_or("?").to_string()).collect()).unwrap_or_default()
}

pub(crate) fn print(r: &Raport) {
    let top = |title: &str, m: &BTreeMap<String, usize>| {
        let mut v: Vec<_> = m.iter().collect();
        v.sort_by(|a, b| b.1.cmp(a.1));
        println!("{title}: {} distinct, {} total", m.len(), m.values().sum::<usize>());
        for (k, n) in v.iter().take(25) {
            println!("  {n:4}  {k}");
        }
    };
    println!("types {} built {}", r.types, r.built);
    top("molang errors", &r.molang);
    top("animations/controllers named but nowhere", &r.missing_anim);
    top("render controllers named but nowhere", &r.missing_render);
    top("geometry named but nowhere", &r.missing_geo);
    top("transitions to unknown states", &r.bad_state);
    top("animated bones the geometry lacks", &r.lost_bones);
    println!("non finite matrices: {} {:?}", r.nan.len(), r.nan.iter().take(10).collect::<Vec<_>>());
}

// koperlib's built in stand ins, the same file BrPaczki puts at the bottom of the stack
pub(crate) fn wbudowane() -> Paczka {
    let f = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../modules/koperlib-kodel/src/main/resources/koperlib_kodel/bedrock_wbudowane.json");
    let j: J = serde_json::from_str(&std::fs::read_to_string(f).unwrap()).unwrap();
    let mut p = Paczka::default();
    let put = |k: &str, into: &mut HashMap<String, J>| {
        for (id, v) in j.get(k).and_then(|x| x.as_object()).cloned().unwrap_or_default() {
            into.insert(id, v);
        }
    };
    put("animations", &mut p.animations);
    put("animation_controllers", &mut p.controllers);
    put("render_controllers", &mut p.renders);
    p
}

#[test]
fn builtins_compile_and_look() {
    let w = wbudowane();
    let d = serde_json::json!({
        "identifier": "koper:test", "geometry": {"default": "geometry.t"}, "textures": {"default": "x"},
        "animations": {"look": "animation.common.look_at_target"}, "scripts": {"animate": ["look"]},
        "render_controllers": ["controller.render.default"]
    });
    let geo = serde_json::json!({"bones": [{"name": "body", "pivot": [0, 0, 0]}, {"name": "head", "parent": "body", "pivot": [0, 24, 0]}]});
    let mut r = Raport::default();
    let src = source(&[w], &d, Some(&geo), &mut r);
    assert!(r.missing_anim.is_empty() && r.missing_render.is_empty());
    let def = Arc::new(Def::build(&src));
    assert!(def.errors.is_empty(), "{:?}", def.errors);
    let names = def.query_names().to_vec();
    let q: Vec<f32> = names.iter().map(|n| if n == "target_x_rotation" { 20.0 } else if n == "target_y_rotation" { 30.0 } else { 0.0 }).collect();
    let mut i = Inst::new(def, 1);
    let mut mats = vec![0f32; 32];
    let mut local = vec![0f32; 18];
    let mut vis = vec![0u8; 2];
    let mut info = [0i32; 8];
    let mut ev = [0i32; 4];
    i.tick(&q, 0.05, Out { mats: &mut mats, vis: &mut vis, local: Some(&mut local), info: &mut info, events: &mut ev });
    assert!((local[9] - 20.0).abs() < 1e-4 && (local[10] - 30.0).abs() < 1e-4, "head {:?}", &local[9..12]);
    assert_eq!(info[0], 0, "default render controller picks Texture.default");
}

#[test]
fn every_entity_in_a_folder() {
    let Ok(dirs) = std::env::var("KOPER_BEDROCK_RP") else { return };
    let mut packs = vec![wbudowane()];
    if let Ok(v) = std::env::var("KOPER_BEDROCK_VANILLA") {
        let mut v = read(Path::new(&v));
        v.entities.clear();
        v.attachables.clear();
        v.geometries.retain(|k, _| { let k = k.to_ascii_lowercase(); !k.starts_with("geometry.humanoid") || k.starts_with("geometry.humanoid.armor") });
        packs.push(v);
    }
    let bases = packs.len();
    packs.extend(dirs.split(':').filter(|s| !s.is_empty()).map(|d| read(Path::new(d))));
    let start = std::time::Instant::now();
    let r = run(&packs, bases, std::env::var("KOPER_BEDROCK_RP_LOUD").is_ok());
    print(&r);
    println!("in {:?}", start.elapsed());
}
