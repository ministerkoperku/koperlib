use super::*;
use serde_json::json;

fn def(j: J) -> Arc<Def> {
    let d = Def::build(&j);
    assert!(d.errors.is_empty(), "{:?}", d.errors);
    Arc::new(d)
}

fn tick(i: &mut Inst, q: &[f32], dt: f32, bones: usize) -> (Vec<f32>, Vec<f32>, Vec<u8>, [i32; 8]) {
    let mut mats = vec![0f32; bones * 16];
    let mut local = vec![0f32; bones * 9];
    let mut vis = vec![0u8; bones];
    let mut info = [0i32; 8];
    let mut ev = [0i32; 8];
    i.tick(q, dt, Out { mats: &mut mats, vis: &mut vis, local: Some(&mut local), info: &mut info, events: &mut ev });
    (mats, local, vis, info)
}

fn legs() -> J {
    json!({
        "bones": [
            {"name": "body", "parent": -1, "pivot": [0, 12, 0], "rot": [0, 0, 0], "pos": [0, 0, 0]},
            {"name": "leg", "parent": 0, "pivot": [0, 6, 0], "rot": [10, 0, 0], "pos": [0, 0, 0]}
        ],
        "animations": {
            "animation.t.walk": {"loop": true, "animation_length": 1.0, "bones": {
                "leg": {"rotation": {"0.0": [0, 0, 0], "0.5": ["math.sin(90) * 40", 0, 0], "1.0": [0, 0, 0]}}}},
            "animation.t.hop": {"loop": false, "animation_length": 0.5, "bones": {"body": {"position": [0, "q.anim_time * 2", 0]}}},
            "animation.t.look": {"loop": true, "bones": {"body": {"rotation": [0, "q.head_y_rotation", 0]}}}
        },
        "controllers": {
            "controller.animation.t.move": {"initial_state": "default", "states": {
                "default": {"animations": ["walk"], "transitions": [{"jumping": "!q.is_on_ground"}]},
                "jumping": {"animations": ["hop"], "transitions": [{"default": "q.all_animations_finished && q.is_on_ground"}],
                            "on_entry": ["v.jumps = (v.jumps ?? 0) + 1;"]}
            }}
        },
        "short": {"walk": "animation.t.walk", "hop": "animation.t.hop", "look": "animation.t.look", "move": "controller.animation.t.move"},
        "scripts": {"initialize": ["v.jumps = 0;"], "animate": ["move", {"look": "q.is_on_ground"}]},
        "textures": ["default", "angry"],
        "render": [{"textures": ["q.is_angry ? Texture.angry : Texture.default"], "part_visibility": [{"*": true}, {"leg": "!q.is_baby"}]}]
    })
}

#[test]
fn controller_animation_and_render() {
    let d = def(legs());
    let names = d.query_names().to_vec();
    let slot = |n: &str| names.iter().position(|x| x == n).unwrap();
    let mut q = vec![0f32; names.len()];
    q[slot("is_on_ground")] = 1.0;
    q[slot("head_y_rotation")] = 30.0;
    let mut i = Inst::new(d.clone(), 1);

    // half way through walk: leg rotation = 10 bind + 40
    let (_, local, vis, info) = tick(&mut i, &q, 0.5, 2);
    assert!((local[9] - 40.0).abs() < 1e-3, "leg rot x {}", local[9]);
    assert!((local[1] - 30.0).abs() < 1e-3, "body look {}", local[1]);
    assert_eq!(vis, vec![1, 1]);
    assert_eq!(info[0], 0);

    // leave the ground -> jumping state, hop plays, look is off
    q[slot("is_on_ground")] = 0.0;
    q[slot("is_angry")] = 1.0;
    q[slot("is_baby")] = 1.0;
    let (_, local, vis, info) = tick(&mut i, &q, 0.25, 2);
    assert!(local[1].abs() < 1e-6);
    assert_eq!(vis, vec![1, 0]);
    assert_eq!(info[0], 1);
    assert!(local[4] > 0.0, "hop should move the body up, got {}", local[4]);

    // hop finishes and we land -> back to default
    q[slot("is_on_ground")] = 1.0;
    tick(&mut i, &q, 0.5, 2);
    let (_, local, _, _) = tick(&mut i, &q, 0.01, 2);
    assert!(local[4].abs() < 1e-6, "back on the ground, no hop offset: {}", local[4]);
    let jumps = d.book.var_id("jumps").unwrap() as usize;
    assert_eq!(i.vars[jumps].num(), 1.0);
}

#[test]
fn matrices_match_kodel_layout() {
    let d = def(legs());
    let mut i = Inst::new(d.clone(), 1);
    let q = vec![1f32; d.query_names().len()];
    let (mats, _, _, _) = tick(&mut i, &q, 0.0, 2);
    // body has no rotation at t=0 and look reads 1 degree, so it is close to identity + no offset
    assert!((mats[15] - 1.0).abs() < 1e-6);
    assert!(mats[12].abs() < 1.0 && mats[14].abs() < 1.0);
}

#[test]
fn catmull_and_pre_post() {
    let j = json!({
        "bones": [{"name": "b", "parent": -1, "pivot": [0,0,0], "rot": [0,0,0], "pos": [0,0,0]}],
        "animations": {"a": {"loop": true, "animation_length": 2.0, "bones": {"b": {"position": {
            "0.0": {"post": [0, 0, 0], "lerp_mode": "catmullrom"},
            "1.0": {"pre": [10, 0, 0], "post": [20, 0, 0], "lerp_mode": "catmullrom"},
            "2.0": {"post": [0, 0, 0], "lerp_mode": "catmullrom"}}}}}},
        "short": {"a": "a"},
        "scripts": {"animate": ["a"]}
    });
    let d = def(j);
    let mut i = Inst::new(d, 1);
    let (_, l, _, _) = tick(&mut i, &[], 0.999, 1);
    assert!((l[3] - 10.0).abs() < 0.2, "{}", l[3]);
    let (_, l, _, _) = tick(&mut i, &[], 0.002, 1);
    assert!((l[3] - 20.0).abs() < 0.2, "{}", l[3]);
}

// a busy entity: lots of animations and controllers, only a few in use. measures a tick
#[test]
fn many_animations_stay_cheap() {
    let mut anims = serde_json::Map::new();
    for k in 0..400 {
        anims.insert(format!("animation.t.a{k}"), json!({"loop": true, "animation_length": 1.0, "bones": {
            "leg": {"rotation": {"0.0": [0, 0, 0], "0.5": [format!("math.sin(q.life_time * {k}) * 30"), 0, 0], "1.0": [0, 0, 0]}},
            "body": {"position": [0, "math.cos(q.anim_time * 360) * 0.5", 0]}}}));
    }
    let mut short = serde_json::Map::new();
    let mut animate = Vec::new();
    for k in 0..40 {
        short.insert(format!("a{k}"), json!(format!("animation.t.a{k}")));
        animate.push(json!({format!("a{k}"): "q.is_moving * 0.1"}));
    }
    let src = json!({
        "bones": [{"name": "body", "parent": -1, "pivot": [0, 12, 0]}, {"name": "leg", "parent": 0, "pivot": [0, 6, 0]}],
        "animations": anims, "short": short, "scripts": {"animate": animate, "pre_animation": ["v.x = q.life_time * 2;"]}
    });
    let d = def(src);
    let mut i = Inst::new(d.clone(), 3);
    let names = d.query_names().to_vec();
    let mut q = vec![0f32; names.len()];
    let moving = names.iter().position(|n| n == "is_moving").unwrap();
    q[moving] = 1.0;
    let start = std::time::Instant::now();
    for f in 0..600 {
        if let Some(lt) = names.iter().position(|n| n == "life_time") { q[lt] = f as f32 / 60.0; }
        tick(&mut i, &q, 1.0 / 60.0, 2);
    }
    let per = start.elapsed().as_secs_f64() / 600.0 * 1e6;
    println!("40 live animations: {per:.1} us per tick");
    assert!(per < 500.0);
}

// vanilla bedrock cow: body lies down with a +90 x turn, the udder cube sits on the front face of
// the upright box. after the turn it has to be under the belly, back half. it used to end up on the spine
#[test]
fn bedrock_x_turn_puts_the_udder_underneath() {
    let j = json!({
        "bones": [{"name": "body", "parent": -1, "pivot": [0, 19, 2], "rot": [90, 0, 0], "pos": [0, 0, 0]}],
        "short": {}, "scripts": {}
    });
    let d = def(j);
    let mut i = Inst::new(d, 1);
    let (m, _, _, _) = tick(&mut i, &[], 0.0, 1);
    let p = [0.0f32, 14.0, -5.5];
    let at = |r: usize| m[r] * p[0] + m[4 + r] * p[1] + m[8 + r] * p[2] + m[12 + r];
    let (x, y, z) = (at(0), at(1), at(2));
    assert!(x.abs() < 1e-3, "x {x}");
    assert!((y - 11.5).abs() < 1e-3, "udder should hang low, y {y}");
    assert!((z - 7.0).abs() < 1e-3, "udder should be at the back, z {z}");
}

// attachables read their holder (c.owning_entity->v.x) and which slot they sit in (c.item_slot)
#[test]
fn attachable_reads_its_holder() {
    let bones = json!([
        {"name": "body", "parent": -1, "pivot": [0, 0, 0], "rot": [0, 0, 0], "pos": [0, 0, 0]},
        {"name": "blade", "parent": 0, "pivot": [0, 0, 0], "rot": [0, 0, 0], "pos": [0, 0, 0]}
    ]);
    let holder = def(json!({"bones": bones, "scripts": {"initialize": ["v.shown = 1;", "v.pose = 'sword';"]}}));
    let att = def(json!({
        "bones": bones,
        "scripts": {"pre_animation": [
            "v.seen = 0; v.kind = 0;",
            "c.item_slot == 'main_hand' ? { v.seen = c.owning_entity -> v.shown; v.kind = c.owning_entity -> v.pose; };",
            "v.missing = (c.owning_entity -> v.nope) ?? 7;"
        ]},
        "render": [{"part_visibility": [{"*": true}, {"body": "v.seen"}, {"blade": "v.kind == 'sword' && v.missing == 7"}]}]
    }));
    let mut h = Inst::new(holder, 1);
    tick(&mut h, &[], 0.05, 2);
    let slot = att.contexts().iter().position(|c| c == "item_slot").unwrap();
    let main = att.strings().iter().position(|s| s == "main_hand").unwrap() as i32;
    let ctx = vec![0f32; att.contexts().len()];
    let mut cstr = vec![-1i32; att.contexts().len()];
    let mut a = Inst::new(att, 2);

    let run = |a: &mut Inst, owner: *const Inst, ctx: &[f32], cstr: &[i32]| {
        let (mut mats, mut vis, mut info, mut ev) = (vec![0f32; 32], vec![0u8; 2], [0i32; 8], [0i32; 8]);
        a.owner = owner;
        a.tick_c(&[], &[], ctx, cstr, 0.05, Out { mats: &mut mats, vis: &mut vis, local: None, info: &mut info, events: &mut ev });
        vis
    };
    // no slot, no holder: nothing
    assert_eq!(run(&mut a, std::ptr::null(), &ctx, &cstr), vec![0, 0]);
    // main hand but the holder is gone for this tick
    cstr[slot] = main;
    assert_eq!(run(&mut a, std::ptr::null(), &ctx, &cstr), vec![0, 0]);
    // main hand with a holder: its number and its string both arrive
    assert_eq!(run(&mut a, &h, &ctx, &cstr), vec![1, 1]);
    // the owner only lasts one tick
    assert_eq!(run(&mut a, std::ptr::null(), &ctx, &cstr), vec![0, 0]);
}

// parent_setup writes into the holder, only what it changed, strings by their text
#[test]
fn parent_setup_reaches_the_holder() {
    let bones = json!([{"name": "body", "parent": -1, "pivot": [0, 0, 0], "rot": [0, 0, 0], "pos": [0, 0, 0]}]);
    let holder = def(json!({"bones": bones, "scripts": {"initialize": ["v.shown = 0; v.kind = 'none'; v.keep = 5;"]},
        "render": [{"part_visibility": [{"body": "v.shown && v.kind == 'block' && v.keep == 5"}]}]}));
    let att = def(json!({"bones": bones, "scripts": {
        "parent_setup": "c.item_slot == 'main_hand' ? { v.shown = 1; v.kind = 'block'; };"}}));
    let slot = att.contexts().iter().position(|c| c == "item_slot").unwrap();
    let main = att.strings().iter().position(|s| s == "main_hand").unwrap() as i32;
    let mut h = Inst::new(holder, 1);
    let mut a = Inst::new(att.clone(), 2);
    let (mut mats, mut vis, mut info, mut ev) = (vec![0f32; 16], vec![0u8; 1], [0i32; 8], [0i32; 8]);
    let ctx = vec![0f32; att.contexts().len()];
    let mut cstr = vec![-1i32; att.contexts().len()];
    cstr[slot] = main;
    let (_, _, v0, _) = tick(&mut h, &[], 0.05, 1);
    assert_eq!(v0, vec![0]);
    a.tick_c(&[], &[], &ctx, &cstr, 0.05, Out { mats: &mut mats, vis: &mut vis, local: None, info: &mut info, events: &mut ev });
    a.parent_setup(&mut h);
    let (_, _, v1, _) = tick(&mut h, &[], 0.05, 1);
    assert_eq!(v1, vec![1]);
}

// two render controllers, both with an "Array.textures": each reads its own (A&S golem body vs cracks)
#[test]
fn arrays_stay_in_their_controller() {
    let d = def(json!({
        "bones": [{"name": "body", "parent": -1, "pivot": [0, 0, 0], "rot": [0, 0, 0], "pos": [0, 0, 0]}],
        "textures": ["default", "cracks"],
        "render": [
            {"arrays": {"textures": {"Array.textures": ["Texture.default"]}}, "textures": ["Array.textures[0]"]},
            {"arrays": {"textures": {"Array.textures": ["Texture.cracks"]}}, "textures": ["Array.textures[0]"]}
        ]
    }));
    let mut i = Inst::new(d, 1);
    let (_, _, _, info) = tick(&mut i, &[], 0.05, 1);
    assert_eq!(info[0], 0, "the first controller picks the body texture");
    assert_eq!(i.layers.iter().map(|l| l[1]).collect::<Vec<_>>(), vec![0, 1]);
}

// entity.playAnimation: an animation the entity does not list, on top of what it does, stopping
// when it finishes and fading out; the same controller name replaces the one playing
#[test]
fn play_animation_runtime_controller() {
    let j = json!({
        "bones": [{"name": "arm", "parent": -1, "pivot": [0,0,0], "rot": [0,0,0], "pos": [0,0,0]}],
        "animations": {
            "animation.t.idle": {"loop": true, "animation_length": 1.0, "bones": {"arm": {"rotation": [5, 0, 0]}}},
            "animation.t.wave": {"animation_length": 0.5, "bones": {"arm": {"rotation": [0, 0, "90"]}}},
            "animation.t.nod": {"loop": true, "animation_length": 1.0, "bones": {"arm": {"rotation": [30, 0, 0]}}}
        },
        "short": {"idle": "animation.t.idle"},
        "scripts": {"animate": ["idle"]}
    });
    let d = def(j);
    let mut i = Inst::new(d, 1);
    assert!(!i.play_animation("animation.t.nope", 0.0, None, None));
    assert!(i.play_animation("animation.t.wave", 0.2, None, None));
    let (_, l, _, _) = tick(&mut i, &[], 0.1, 1);
    assert!((l[0] - 5.0).abs() < 1e-3 && (l[2] - 90.0).abs() < 1e-3, "idle and wave together {:?}", &l[0..3]);
    // past its end: finished, blend out starts from the last frame
    let (_, l, _, _) = tick(&mut i, &[], 0.5, 1);
    assert!(l[2] > 0.0, "fading, not gone: {}", l[2]);
    for _ in 0..5 { tick(&mut i, &[], 0.1, 1); }
    let (_, l, _, _) = tick(&mut i, &[], 0.1, 1);
    assert!(l[2].abs() < 1e-3 && i.playing() == 0, "faded out: {} playing {}", l[2], i.playing());
    // a looping one with a stop expression that never holds keeps going; same key replaces it
    assert!(i.play_animation("animation.t.nod", 0.0, Some("0"), Some("talk")));
    assert!(i.play_animation("idle", 0.0, Some("0"), Some("talk")));
    assert_eq!(i.playing(), 1);
    let (_, l, _, _) = tick(&mut i, &[], 0.1, 1);
    assert!((l[0] - 10.0).abs() < 1e-3, "idle twice, nod replaced: {}", l[0]);
}
