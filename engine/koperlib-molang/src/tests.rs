// examples straight from the creator docs syntax guide, plus the stuff packs actually write
use super::*;

fn run(src: &str) -> f32 {
    run_with(src, &[], |_| {})
}

fn run_with(src: &str, q: &[(&str, f32)], setup: impl FnOnce(&mut Book)) -> f32 {
    let mut b = Book::default();
    setup(&mut b);
    let (p, err) = b.compile(src);
    assert!(err.is_none(), "{:?}", err);
    let mut qs = vec![0f32; b.queries.len()];
    for (k, v) in q {
        if let Some(i) = b.queries.iter().position(|x| x == k) {
            qs[i] = *v;
        }
    }
    let mut vars = vec![Val::default(); b.vars.len()];
    let mut set = vec![false; b.vars.len()];
    let mut temps = Vec::new();
    let mut rng = 7u32;
    let arrays: Vec<Vec<Val>> = vec![];
    let mut cx = Ctx::new(&qs, &mut vars, &mut set, &mut temps, &arrays, &mut rng);
    p.num(&mut cx)
}

#[test]
fn simple_and_constant_folded() {
    assert_eq!(run("1 + 2 * 3"), 7.0);
    assert_eq!(run("(1 + 2) * 3"), 9.0);
    assert_eq!(run("-2 * -3"), 6.0);
    assert_eq!(run("10 / 0"), 0.0);
    let mut b = Book::default();
    assert!(b.compile("math.sin(90) * 2 + math.pi * 0").0.is_const());
    assert!((run("math.sin(90)") - 1.0).abs() < 1e-6);
    assert!((run("math.cos(180)") + 1.0).abs() < 1e-6);
    assert_eq!(run("math.clamp(5, 0, 3)"), 3.0);
    assert_eq!(run("math.min_angle(270)"), -90.0);
    assert_eq!(run("math.lerprotate(350, 10, 0.5)"), 0.0);
    assert_eq!(run("math.ease_in_quad(0, 10, 0.5)"), 2.5);
    assert_eq!(run("math.hermite_blend(0.5)"), 0.5);
    assert_eq!(run("true && !false"), 1.0);
}

#[test]
fn statements_and_return() {
    assert!((run("temp.moo = math.sin(90); temp.baa = math.cos(0); return temp.moo * temp.moo + temp.baa;") - 2.0).abs() < 1e-6);
    // no return with statements = 0, per the docs
    assert_eq!(run("t.a = 5;"), 0.0);
    assert_eq!(run("t.a = 5; t.b = t.a * 2; return t.b;"), 10.0);
}

#[test]
fn ternary_is_right_associative() {
    // 1.18.20+: A ? B : C ? D : E == A ? B : (C ? D : E)
    assert_eq!(run("0 ? 1 : 1 ? 2 : 3"), 2.0);
    assert_eq!(run("0 ? 1 : 0 ? 2 : 3"), 3.0);
    assert_eq!(run("1 ? 5"), 5.0);
    assert_eq!(run("0 ? 5"), 0.0);
    // && before ||, comparison before equality
    assert_eq!(run("1 || 0 && 0"), 1.0);
    assert_eq!(run("1 < 2 == 1"), 1.0);
}

#[test]
fn loops_break_continue() {
    // fibonacci from the syntax guide
    assert_eq!(run("v.x = 1; v.y = 1; loop(10, { t.x = v.x + v.y; v.x = v.y; v.y = t.x; }); return v.y;"), 144.0);
    assert_eq!(run("v.x = 1; v.y = 1; loop(10, {t.x = v.x + v.y; v.x = v.y; v.y = t.x; (v.y > 20) ? break;}); return v.y;"), 21.0);
    assert_eq!(run("v.x = 0; loop(10, {loop(10, {v.x = v.x + 1; (v.x > 5) ? break;});}); return v.x;"), 15.0);
    assert_eq!(run("v.x = 0; loop(10, { (v.x > 5) ? continue; v.x = v.x + 1; }); return v.x;"), 6.0);
}

#[test]
fn coalesce_and_variables() {
    assert!((run("variable.x = (variable.y ?? 1.2) + 0.3; return variable.x;") - 1.5).abs() < 1e-6);
    assert_eq!(run("v.y = 4; return v.y ?? 9;"), 4.0);
    // case insensitive and aliases
    assert_eq!(run("VARIABLE.Moo = 3; return v.moo;"), 3.0);
}

#[test]
fn queries_are_slots() {
    assert_eq!(run_with("q.is_on_ground ? 5 : query.health", &[("is_on_ground", 1.0), ("health", 7.0)], |_| {}), 5.0);
    assert_eq!(run_with("q.is_on_ground ? 5 : query.health", &[("is_on_ground", 0.0), ("health", 7.0)], |_| {}), 7.0);
    let mut b = Book::default();
    b.compile("q.is_item_equipped('main_hand') && query.is_name_any('dinnerbone', 'grumm')");
    assert!(b.queries.contains(&"is_item_equipped('main_hand')".to_string()), "{:?}", b.queries);
    assert!(b.queries.contains(&"is_name_any('dinnerbone','grumm')".to_string()), "{:?}", b.queries);
}

#[test]
fn strings_and_resources() {
    assert_eq!(run("'minecraft:pig' == 'minecraft:pig'"), 1.0);
    assert_eq!(run("'a' != 'b'"), 1.0);
    let mut b = Book::default();
    let (p, e) = b.compile("q.is_baby ? Texture.baby : Texture.default");
    assert!(e.is_none());
    let qs = vec![0f32; b.queries.len()];
    let (mut vars, mut set, mut temps, mut rng) = (vec![], vec![], vec![], 1u32);
    let arrays: Vec<Vec<Val>> = vec![];
    let mut cx = Ctx::new(&qs, &mut vars, &mut set, &mut temps, &arrays, &mut rng);
    assert_eq!(p.eval(&mut cx), Val::Str(b.string_id("texture.default").unwrap()));
}

#[test]
fn arrays_wrap_and_clamp() {
    let mut b = Book::default();
    let (p, _) = b.compile("array.skins[q.variant]");
    let arr = vec![vec![Val::Num(10.0), Val::Num(20.0), Val::Num(30.0)]];
    let variant = b.queries.iter().position(|x| x == "variant").unwrap();
    for (v, want) in [(1.0, 20.0), (4.0, 20.0), (-3.0, 10.0)] {
        let mut qs = vec![0f32; b.queries.len()];
        qs[variant] = v;
        let (mut vars, mut set, mut temps, mut rng) = (vec![], vec![], vec![], 1u32);
        let mut cx = Ctx::new(&qs, &mut vars, &mut set, &mut temps, &arr, &mut rng);
        assert_eq!(p.num(&mut cx), want);
    }
}

#[test]
fn junk_becomes_zero_with_an_error() {
    let mut b = Book::default();
    let (p, e) = b.compile("math.nope(1)");
    assert!(e.is_some());
    assert_eq!(p.const_value(), Some(0.0));
    // the stuff blockbench exports without thinking
    assert_eq!(run("1.0f + 2"), 3.0);
    assert_eq!(run("v.x = 1; ;; return v.x;"), 1.0);
    assert_eq!(run("v.a->q.health"), 0.0);
}

#[test]
fn realistic_player_style_expressions() {
    let src = "variable.tcos0 = (math.cos(query.modified_distance_moved * 38.17) * query.modified_move_speed / variable.gliding_speed_value) * 57.3;";
    let mut b = Book::default();
    let (_, e) = b.compile(src);
    assert!(e.is_none(), "{e:?}");
    assert!(run_with("math.cos(q.life_time * 20) * 5 + (q.is_sneaking ? 30 : 0)", &[("is_sneaking", 1.0)], |_| {}) > 34.9);
}

// in_range/any/all/approx_eq are plain compares, they must work on other queries too
#[test]
fn pure_queries_on_runtime_values() {
    let q = [("ground_speed", 2.5f32), ("variant", 3.0)];
    assert_eq!(run_with("q.in_range(q.ground_speed, 0.1, 4)", &q, |_| {}), 1.0);
    assert_eq!(run_with("q.in_range(q.ground_speed, 3, 4)", &q, |_| {}), 0.0);
    assert_eq!(run_with("q.any(q.variant, 1, 2, 3)", &q, |_| {}), 1.0);
    assert_eq!(run_with("q.any(q.variant, 1, 2)", &q, |_| {}), 0.0);
    assert_eq!(run_with("q.all(3, q.variant, 3)", &q, |_| {}), 1.0);
    assert_eq!(run_with("q.approx_eq(q.ground_speed, 2.5)", &q, |_| {}), 1.0);
    assert_eq!(run("q.any('a', 'b', 'a')"), 1.0);
    let mut b = Book::default();
    b.compile("q.in_range(q.ground_speed, 0, 1)");
    assert_eq!(b.queries, vec!["ground_speed".to_string()]);
}

// A&S: t.left_arm = q.bone_origin('leftarm'); v.trs = q.bone_orientation_trs('rightleg'); then .y and .r.x
#[test]
fn struct_queries_spread_into_fields() {
    let q = [("bone_origin('leftarm').y", 22.0), ("bone_orientation_trs('rightleg').r.x", -30.0)];
    assert_eq!(run_with("t.left_arm = q.bone_origin('leftarm'); return t.left_arm.y - 2;", &q, |_| {}), 20.0);
    assert_eq!(run_with("v.trs = q.bone_orientation_trs('rightleg'); t.a = v.trs.r.x; return t.a;", &q, |_| {}), -30.0);
}
