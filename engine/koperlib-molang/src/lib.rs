// molang for koperlib. text goes in once, gets parsed, folded and turned into a tree of ops that
// only ever touches slot indices at run time. no string lookups, no hashing, no allocation per
// eval. queries are slots java fills once per frame, variables are slots on the instance.
// grammar and semantics follow the bedrock creator docs (syntax guide + math/query tables)
use std::collections::HashMap;

mod parse;
pub use parse::parse_error;

// ── values ─────────────────────────────────────────────────────────────────

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Val {
    Num(f32),
    // interned string id in the Book. resources (geometry.x, texture.x...) are strings too
    Str(u32),
}

impl Val {
    #[inline]
    pub fn num(self) -> f32 {
        match self {
            Val::Num(n) => n,
            Val::Str(_) => 0.0,
        }
    }
    #[inline]
    pub fn truthy(self) -> bool {
        match self {
            Val::Num(n) => n != 0.0,
            Val::Str(_) => true,
        }
    }
}

impl Default for Val {
    fn default() -> Self {
        Val::Num(0.0)
    }
}

// queries the evaluator answers itself because they depend on where the expression runs
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Inner {
    AnimTime,
    DeltaTime,
    AllAnimationsFinished,
    AnyAnimationFinished,
    KeyFrameLerpTime,
    StateTime,
    This,
}

#[derive(Clone, Debug)]
pub enum Op {
    Const(Val),
    Query(u32),       // external slot java fills
    Inner(Inner),
    Var(u32),         // variable.* on the instance
    Temp(u32),        // temp.*
    Context(u32),     // context.* (read only, index into ctx.context)
    Remote(u32),      // c.owning_entity->v.name, the holder's variable. index into book.remotes
    ArrayGet(u32, Box<Op>), // array.<name>[i] from a render controller
    SetVar(u32, Box<Op>),
    SetTemp(u32, Box<Op>),
    Neg(Box<Op>),
    Not(Box<Op>),
    Bin(BinOp, Box<Op>, Box<Op>),
    And(Box<Op>, Box<Op>),
    Or(Box<Op>, Box<Op>),
    Cond(Box<Op>, Box<Op>, Option<Box<Op>>),
    Coalesce(Box<Op>, Box<Op>),
    Math(MathFn, Vec<Op>),
    Block(Vec<Op>),
    Return(Box<Op>),
    Loop(Box<Op>, Box<Op>),
    Break,
    Continue,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum BinOp {
    Add, Sub, Mul, Div, Lt, Le, Gt, Ge, Eq, Ne,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum MathFn {
    Abs, Acos, Asin, Atan, Atan2, Ceil, Clamp, CopySign, Cos, DieRoll, DieRollInt, Exp, Floor,
    HermiteBlend, InverseLerp, Lerp, LerpRotate, Ln, Max, Min, MinAngle, Mod, Pow, Random,
    RandomInt, Round, Sign, Sin, Sqrt, Trunc, Ease(Ease),
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Ease {
    InBack, InBounce, InCirc, InCubic, InElastic, InExpo, InOutBack, InOutBounce, InOutCirc,
    InOutCubic, InOutElastic, InOutExpo, InOutQuad, InOutQuart, InOutQuint, InOutSine, InQuad,
    InQuart, InQuint, InSine, OutBack, OutBounce, OutCirc, OutCubic, OutElastic, OutExpo,
    OutQuad, OutQuart, OutQuint, OutSine,
}

// ── the book: names -> slots, shared by every expression of one definition ─

#[derive(Default, Debug, Clone)]
pub struct Book {
    pub strings: Vec<String>,
    string_ids: HashMap<String, u32>,
    pub queries: Vec<String>,     // "is_on_ground" or "is_item_equipped('main_hand')"
    query_ids: HashMap<String, u32>,
    pub vars: Vec<String>,
    var_ids: HashMap<String, u32>,
    pub temps: Vec<String>,
    temp_ids: HashMap<String, u32>,
    pub contexts: Vec<String>,
    context_ids: HashMap<String, u32>,
    pub arrays: Vec<String>,
    array_ids: HashMap<String, u32>,
    // arrays belong to one render controller: two controllers may both call theirs "Array.textures"
    // (A&S's golem body and its cracks) and must not read each other's. set per controller
    pub array_scope: String,
    // variable names read off the owning entity (attachables peeking at their holder)
    pub remotes: Vec<String>,
    remote_ids: HashMap<String, u32>,
}

fn intern(list: &mut Vec<String>, ids: &mut HashMap<String, u32>, key: &str) -> u32 {
    if let Some(&id) = ids.get(key) {
        return id;
    }
    let id = list.len() as u32;
    list.push(key.to_string());
    ids.insert(key.to_string(), id);
    id
}

impl Book {
    pub fn string(&mut self, s: &str) -> u32 {
        intern(&mut self.strings, &mut self.string_ids, s)
    }
    pub fn string_id(&self, s: &str) -> Option<u32> {
        self.string_ids.get(s).copied()
    }
    pub fn query(&mut self, key: &str) -> u32 {
        intern(&mut self.queries, &mut self.query_ids, key)
    }
    pub fn var(&mut self, name: &str) -> u32 {
        intern(&mut self.vars, &mut self.var_ids, name)
    }
    pub fn var_id(&self, name: &str) -> Option<u32> {
        self.var_ids.get(name).copied()
    }
    pub fn temp(&mut self, name: &str) -> u32 {
        intern(&mut self.temps, &mut self.temp_ids, name)
    }
    pub fn context(&mut self, name: &str) -> u32 {
        intern(&mut self.contexts, &mut self.context_ids, name)
    }
    pub fn array(&mut self, name: &str) -> u32 {
        let key = format!("{}{}", self.array_scope, name);
        intern(&mut self.arrays, &mut self.array_ids, &key)
    }
    pub fn remote(&mut self, name: &str) -> u32 {
        intern(&mut self.remotes, &mut self.remote_ids, name)
    }

    // compile one expression. a broken one compiles to a constant 0 and the error comes back
    // separately, same as bedrock: bad molang is 0 and the content keeps going
    pub fn compile(&mut self, src: &str) -> (Program, Option<String>) {
        match parse::parse(src, self) {
            Ok(op) => (Program { op: fold(op) }, None),
            Err(e) => (Program { op: Op::Const(Val::Num(0.0)) }, Some(format!("{e} in `{src}`"))),
        }
    }

    // json numbers and booleans skip the parser entirely
    pub fn constant(v: f32) -> Program {
        Program { op: Op::Const(Val::Num(v)) }
    }
}

#[derive(Clone, Debug)]
pub struct Program {
    pub op: Op,
}

impl Program {
    pub fn is_const(&self) -> bool {
        matches!(self.op, Op::Const(_))
    }
    pub fn const_value(&self) -> Option<f32> {
        match self.op {
            Op::Const(v) => Some(v.num()),
            _ => None,
        }
    }
    #[inline]
    pub fn eval(&self, cx: &mut Ctx) -> Val {
        cx.returned = None;
        let v = cx.run(&self.op);
        match cx.returned.take() {
            Some(r) => r,
            None => v,
        }
    }
    #[inline]
    pub fn num(&self, cx: &mut Ctx) -> f32 {
        if let Op::Const(v) = self.op {
            return v.num();
        }
        self.eval(cx).num()
    }
}

// ── constant folding ───────────────────────────────────────────────────────

fn cnum(op: &Op) -> Option<f32> {
    match op {
        Op::Const(Val::Num(n)) => Some(*n),
        _ => None,
    }
}

fn fold(op: Op) -> Op {
    match op {
        Op::Neg(a) => {
            let a = fold(*a);
            match cnum(&a) {
                Some(n) => Op::Const(Val::Num(-n)),
                None => Op::Neg(Box::new(a)),
            }
        }
        Op::Not(a) => {
            let a = fold(*a);
            match cnum(&a) {
                Some(n) => Op::Const(Val::Num(if n == 0.0 { 1.0 } else { 0.0 })),
                None => Op::Not(Box::new(a)),
            }
        }
        Op::Bin(b, l, r) => {
            let (l, r) = (fold(*l), fold(*r));
            if let (Some(x), Some(y)) = (cnum(&l), cnum(&r)) {
                return Op::Const(Val::Num(bin(b, x, y)));
            }
            Op::Bin(b, Box::new(l), Box::new(r))
        }
        Op::And(l, r) => Op::And(Box::new(fold(*l)), Box::new(fold(*r))),
        Op::Or(l, r) => Op::Or(Box::new(fold(*l)), Box::new(fold(*r))),
        Op::Cond(c, t, e) => {
            let c = fold(*c);
            let t = fold(*t);
            let e = e.map(|e| Box::new(fold(*e)));
            if let Some(n) = cnum(&c) {
                return if n != 0.0 { t } else { e.map(|b| *b).unwrap_or(Op::Const(Val::Num(0.0))) };
            }
            Op::Cond(Box::new(c), Box::new(t), e)
        }
        Op::Math(f, args) => {
            let args: Vec<Op> = args.into_iter().map(fold).collect();
            let random = matches!(f, MathFn::Random | MathFn::RandomInt | MathFn::DieRoll | MathFn::DieRollInt);
            if !random && args.iter().all(|a| cnum(a).is_some()) {
                let nums: Vec<f32> = args.iter().map(|a| cnum(a).unwrap()).collect();
                let mut dummy = 0u32;
                return Op::Const(Val::Num(math(f, &nums, &mut dummy)));
            }
            Op::Math(f, args)
        }
        // a block is worth 0 unless something inside returns, so it never collapses into its body
        Op::Block(v) => Op::Block(v.into_iter().map(fold).collect()),
        Op::Return(a) => Op::Return(Box::new(fold(*a))),
        Op::SetVar(i, a) => Op::SetVar(i, Box::new(fold(*a))),
        Op::SetTemp(i, a) => Op::SetTemp(i, Box::new(fold(*a))),
        Op::Coalesce(a, b) => Op::Coalesce(Box::new(fold(*a)), Box::new(fold(*b))),
        Op::Loop(n, b) => Op::Loop(Box::new(fold(*n)), Box::new(fold(*b))),
        Op::ArrayGet(a, i) => Op::ArrayGet(a, Box::new(fold(*i))),
        other => other,
    }
}

#[inline]
fn bin(b: BinOp, x: f32, y: f32) -> f32 {
    let t = |c: bool| if c { 1.0 } else { 0.0 };
    match b {
        BinOp::Add => x + y,
        BinOp::Sub => x - y,
        BinOp::Mul => x * y,
        BinOp::Div => if y == 0.0 { 0.0 } else { x / y },
        BinOp::Lt => t(x < y),
        BinOp::Le => t(x <= y),
        BinOp::Gt => t(x > y),
        BinOp::Ge => t(x >= y),
        BinOp::Eq => t(x == y),
        BinOp::Ne => t(x != y),
    }
}

// ── running ────────────────────────────────────────────────────────────────

pub struct Ctx<'a> {
    pub queries: &'a [f32],
    // same slots, but a string answer: book string id, -1 = no string (use the float),
    // -2 = a string this book never saw, equal to nothing
    pub qstr: &'a [i32],
    pub vars: &'a mut [Val],
    pub var_set: &'a mut [bool],
    pub temps: &'a mut Vec<Val>,
    pub context: &'a [f32],
    // same idea as qstr: book string id, -1 = the float, -2 = a string this book never saw
    pub cstr: &'a [i32],
    // owner's variables by book.remotes index, None = owner has no such variable (or no owner)
    pub remote: &'a [Option<Val>],
    // render controller arrays: array id -> values
    pub arrays: &'a [Vec<Val>],
    pub anim_time: f32,
    pub delta_time: f32,
    pub state_time: f32,
    pub key_frame_lerp: f32,
    pub all_finished: bool,
    pub any_finished: bool,
    pub this: f32,
    pub rng: &'a mut u32,
    returned: Option<Val>,
    flow: Flow,
}

#[derive(Clone, Copy, PartialEq)]
enum Flow {
    Go,
    Break,
    Continue,
}

impl<'a> Ctx<'a> {
    pub fn new(queries: &'a [f32], vars: &'a mut [Val], var_set: &'a mut [bool], temps: &'a mut Vec<Val>,
               arrays: &'a [Vec<Val>], rng: &'a mut u32) -> Self {
        Ctx {
            queries, qstr: &[], vars, var_set, temps, context: &[], cstr: &[], remote: &[], arrays,
            anim_time: 0.0, delta_time: 0.0, state_time: 0.0, key_frame_lerp: 0.0,
            all_finished: false, any_finished: false, this: 0.0, rng,
            returned: None, flow: Flow::Go,
        }
    }

    fn stopped(&self) -> bool {
        self.returned.is_some() || self.flow != Flow::Go
    }

    fn run(&mut self, op: &Op) -> Val {
        match op {
            Op::Const(v) => *v,
            Op::Query(i) => match self.qstr.get(*i as usize).copied() {
                Some(s) if s >= 0 => Val::Str(s as u32),
                Some(-2) => Val::Str(u32::MAX),
                _ => Val::Num(self.queries.get(*i as usize).copied().unwrap_or(0.0)),
            },
            Op::Inner(k) => Val::Num(match k {
                Inner::AnimTime => self.anim_time,
                Inner::DeltaTime => self.delta_time,
                Inner::StateTime => self.state_time,
                Inner::KeyFrameLerpTime => self.key_frame_lerp,
                Inner::AllAnimationsFinished => self.all_finished as i32 as f32,
                Inner::AnyAnimationFinished => self.any_finished as i32 as f32,
                Inner::This => self.this,
            }),
            Op::Var(i) => self.vars.get(*i as usize).copied().unwrap_or_default(),
            Op::Temp(i) => self.temps.get(*i as usize).copied().unwrap_or_default(),
            Op::Context(i) => match self.cstr.get(*i as usize).copied() {
                Some(s) if s >= 0 => Val::Str(s as u32),
                Some(-2) => Val::Str(u32::MAX),
                _ => Val::Num(self.context.get(*i as usize).copied().unwrap_or(0.0)),
            },
            Op::Remote(i) => self.remote.get(*i as usize).copied().flatten().unwrap_or(Val::Num(0.0)),
            Op::ArrayGet(a, idx) => {
                let i = self.run(idx).num();
                match self.arrays.get(*a as usize) {
                    Some(arr) if !arr.is_empty() => {
                        // docs: c style cast, negatives clamp to 0, big ones wrap
                        let k = if i < 0.0 { 0 } else { (i as usize) % arr.len() };
                        arr[k]
                    }
                    _ => Val::Num(0.0),
                }
            }
            Op::SetVar(i, v) => {
                let v = self.run(v);
                if let Some(slot) = self.vars.get_mut(*i as usize) {
                    *slot = v;
                    self.var_set[*i as usize] = true;
                }
                v
            }
            Op::SetTemp(i, v) => {
                let v = self.run(v);
                let i = *i as usize;
                if self.temps.len() <= i {
                    self.temps.resize(i + 1, Val::default());
                }
                self.temps[i] = v;
                v
            }
            Op::Neg(a) => Val::Num(-self.run(a).num()),
            Op::Not(a) => Val::Num(if self.run(a).truthy() { 0.0 } else { 1.0 }),
            Op::Bin(b, l, r) => {
                let (x, y) = (self.run(l), self.run(r));
                match (b, x, y) {
                    // strings only know == and !=
                    (BinOp::Eq, Val::Str(a), Val::Str(c)) => Val::Num((a == c) as i32 as f32),
                    (BinOp::Ne, Val::Str(a), Val::Str(c)) => Val::Num((a != c) as i32 as f32),
                    (BinOp::Eq, Val::Str(_), _) | (BinOp::Eq, _, Val::Str(_)) => Val::Num(0.0),
                    (BinOp::Ne, Val::Str(_), _) | (BinOp::Ne, _, Val::Str(_)) => Val::Num(1.0),
                    _ => Val::Num(bin(*b, x.num(), y.num())),
                }
            }
            Op::And(l, r) => Val::Num((self.run(l).truthy() && self.run(r).truthy()) as i32 as f32),
            Op::Or(l, r) => Val::Num((self.run(l).truthy() || self.run(r).truthy()) as i32 as f32),
            Op::Cond(c, t, e) => {
                if self.run(c).truthy() {
                    self.run(t)
                } else if let Some(e) = e {
                    self.run(e)
                } else {
                    Val::Num(0.0)
                }
            }
            Op::Coalesce(a, b) => {
                let set = match &**a {
                    Op::Var(i) => self.var_set.get(*i as usize).copied().unwrap_or(false),
                    Op::Temp(i) => (*i as usize) < self.temps.len(),
                    Op::Remote(i) => self.remote.get(*i as usize).copied().flatten().is_some(),
                    _ => true,
                };
                if set { self.run(a) } else { self.run(b) }
            }
            Op::Math(f, args) => {
                let mut buf = [0f32; 3];
                let n = args.len().min(3);
                for k in 0..n {
                    buf[k] = self.run(&args[k]).num();
                }
                Val::Num(math(*f, &buf[..n], self.rng))
            }
            Op::Block(list) => {
                for s in list {
                    self.run(s);
                    if self.stopped() {
                        break;
                    }
                }
                Val::Num(0.0)
            }
            Op::Return(v) => {
                let v = self.run(v);
                self.returned = Some(v);
                v
            }
            Op::Loop(n, body) => {
                let count = (self.run(n).num() as i32).clamp(0, 1024);
                for _ in 0..count {
                    self.run(body);
                    if self.returned.is_some() {
                        break;
                    }
                    if self.flow == Flow::Break {
                        self.flow = Flow::Go;
                        break;
                    }
                    self.flow = Flow::Go;
                }
                Val::Num(0.0)
            }
            Op::Break => {
                self.flow = Flow::Break;
                Val::Num(0.0)
            }
            Op::Continue => {
                self.flow = Flow::Continue;
                Val::Num(0.0)
            }
        }
    }
}

#[inline]
fn rand01(rng: &mut u32) -> f32 {
    // xorshift, one per instance. molang random only needs to look random
    let mut x = if *rng == 0 { 0x9E37_79B9 } else { *rng };
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *rng = x;
    (x >> 8) as f32 / (1u32 << 24) as f32
}

fn math(f: MathFn, a: &[f32], rng: &mut u32) -> f32 {
    let g = |i: usize| a.get(i).copied().unwrap_or(0.0);
    let deg = std::f32::consts::PI / 180.0;
    match f {
        MathFn::Abs => g(0).abs(),
        MathFn::Acos => g(0).clamp(-1.0, 1.0).acos() / deg,
        MathFn::Asin => g(0).clamp(-1.0, 1.0).asin() / deg,
        MathFn::Atan => g(0).atan() / deg,
        MathFn::Atan2 => g(0).atan2(g(1)) / deg,
        MathFn::Ceil => g(0).ceil(),
        MathFn::Clamp => g(0).max(g(1)).min(g(2)),
        MathFn::CopySign => g(0).abs().copysign(g(1)),
        MathFn::Cos => (g(0) * deg).cos(),
        MathFn::Sin => (g(0) * deg).sin(),
        MathFn::DieRoll => {
            let n = g(0).max(0.0) as i32;
            (0..n).map(|_| g(1) + rand01(rng) * (g(2) - g(1))).sum()
        }
        MathFn::DieRollInt => {
            let n = g(0).max(0.0) as i32;
            let (lo, hi) = (g(1).round() as i32, g(2).round() as i32);
            (0..n).map(|_| (lo + (rand01(rng) * ((hi - lo + 1) as f32)) as i32).min(hi) as f32).sum()
        }
        MathFn::Exp => g(0).exp(),
        MathFn::Floor => g(0).floor(),
        MathFn::HermiteBlend => {
            let t = g(0);
            3.0 * t * t - 2.0 * t * t * t
        }
        MathFn::InverseLerp => {
            let (s, e, v) = (g(0), g(1), g(2));
            if e == s { 0.0 } else { (v - s) / (e - s) }
        }
        MathFn::Lerp => g(0) + (g(1) - g(0)) * g(2),
        MathFn::LerpRotate => {
            let (s, e, t) = (min_angle(g(0)), min_angle(g(1)), g(2));
            let mut d = e - s;
            if d > 180.0 { d -= 360.0 }
            if d < -180.0 { d += 360.0 }
            s + d * t
        }
        MathFn::Ln => if g(0) <= 0.0 { 0.0 } else { g(0).ln() },
        MathFn::Max => g(0).max(g(1)),
        MathFn::Min => g(0).min(g(1)),
        MathFn::MinAngle => min_angle(g(0)),
        MathFn::Mod => if g(1) == 0.0 { 0.0 } else { g(0) % g(1) },
        MathFn::Pow => g(0).powf(g(1)),
        MathFn::Random => g(0) + rand01(rng) * (g(1) - g(0)),
        MathFn::RandomInt => {
            let (lo, hi) = (g(0).round() as i32, g(1).round() as i32);
            if hi <= lo { lo as f32 } else { (lo + (rand01(rng) * ((hi - lo + 1) as f32)) as i32).min(hi) as f32 }
        }
        MathFn::Round => g(0).round(),
        MathFn::Sign => if g(0) >= 0.0 { 1.0 } else { -1.0 },
        MathFn::Sqrt => if g(0) < 0.0 { 0.0 } else { g(0).sqrt() },
        MathFn::Trunc => g(0).trunc(),
        MathFn::Ease(e) => {
            let (s, end, t) = (g(0), g(1), g(2).clamp(0.0, 1.0));
            s + (end - s) * ease(e, t)
        }
    }
}

fn min_angle(a: f32) -> f32 {
    let mut x = (a + 180.0) % 360.0;
    if x < 0.0 {
        x += 360.0;
    }
    x - 180.0
}

// the usual easing curves, t in 0..1 -> 0..1
pub fn ease(e: Ease, t: f32) -> f32 {
    use std::f32::consts::PI;
    let c1 = 1.70158f32;
    let c2 = c1 * 1.525;
    let c3 = c1 + 1.0;
    let c4 = (2.0 * PI) / 3.0;
    let c5 = (2.0 * PI) / 4.5;
    let out_bounce = |x: f32| {
        let (n1, d1) = (7.5625f32, 2.75f32);
        if x < 1.0 / d1 {
            n1 * x * x
        } else if x < 2.0 / d1 {
            let x = x - 1.5 / d1;
            n1 * x * x + 0.75
        } else if x < 2.5 / d1 {
            let x = x - 2.25 / d1;
            n1 * x * x + 0.9375
        } else {
            let x = x - 2.625 / d1;
            n1 * x * x + 0.984375
        }
    };
    match e {
        Ease::InSine => 1.0 - ((t * PI) / 2.0).cos(),
        Ease::OutSine => ((t * PI) / 2.0).sin(),
        Ease::InOutSine => -((PI * t).cos() - 1.0) / 2.0,
        Ease::InQuad => t * t,
        Ease::OutQuad => 1.0 - (1.0 - t) * (1.0 - t),
        Ease::InOutQuad => if t < 0.5 { 2.0 * t * t } else { 1.0 - (-2.0 * t + 2.0).powi(2) / 2.0 },
        Ease::InCubic => t * t * t,
        Ease::OutCubic => 1.0 - (1.0 - t).powi(3),
        Ease::InOutCubic => if t < 0.5 { 4.0 * t * t * t } else { 1.0 - (-2.0 * t + 2.0).powi(3) / 2.0 },
        Ease::InQuart => t.powi(4),
        Ease::OutQuart => 1.0 - (1.0 - t).powi(4),
        Ease::InOutQuart => if t < 0.5 { 8.0 * t.powi(4) } else { 1.0 - (-2.0 * t + 2.0).powi(4) / 2.0 },
        Ease::InQuint => t.powi(5),
        Ease::OutQuint => 1.0 - (1.0 - t).powi(5),
        Ease::InOutQuint => if t < 0.5 { 16.0 * t.powi(5) } else { 1.0 - (-2.0 * t + 2.0).powi(5) / 2.0 },
        Ease::InExpo => if t == 0.0 { 0.0 } else { 2f32.powf(10.0 * t - 10.0) },
        Ease::OutExpo => if t == 1.0 { 1.0 } else { 1.0 - 2f32.powf(-10.0 * t) },
        Ease::InOutExpo => {
            if t == 0.0 { 0.0 } else if t == 1.0 { 1.0 } else if t < 0.5 { 2f32.powf(20.0 * t - 10.0) / 2.0 } else { (2.0 - 2f32.powf(-20.0 * t + 10.0)) / 2.0 }
        }
        Ease::InCirc => 1.0 - (1.0 - t * t).max(0.0).sqrt(),
        Ease::OutCirc => (1.0 - (t - 1.0).powi(2)).max(0.0).sqrt(),
        Ease::InOutCirc => {
            if t < 0.5 { (1.0 - (1.0 - (2.0 * t).powi(2)).max(0.0).sqrt()) / 2.0 } else { ((1.0 - (-2.0 * t + 2.0).powi(2)).max(0.0).sqrt() + 1.0) / 2.0 }
        }
        Ease::InBack => c3 * t * t * t - c1 * t * t,
        Ease::OutBack => 1.0 + c3 * (t - 1.0).powi(3) + c1 * (t - 1.0).powi(2),
        Ease::InOutBack => {
            if t < 0.5 { ((2.0 * t).powi(2) * ((c2 + 1.0) * 2.0 * t - c2)) / 2.0 } else { ((2.0 * t - 2.0).powi(2) * ((c2 + 1.0) * (t * 2.0 - 2.0) + c2) + 2.0) / 2.0 }
        }
        Ease::InElastic => {
            if t == 0.0 { 0.0 } else if t == 1.0 { 1.0 } else { -(2f32.powf(10.0 * t - 10.0)) * ((t * 10.0 - 10.75) * c4).sin() }
        }
        Ease::OutElastic => {
            if t == 0.0 { 0.0 } else if t == 1.0 { 1.0 } else { 2f32.powf(-10.0 * t) * ((t * 10.0 - 0.75) * c4).sin() + 1.0 }
        }
        Ease::InOutElastic => {
            if t == 0.0 { 0.0 } else if t == 1.0 { 1.0 } else if t < 0.5 {
                -(2f32.powf(20.0 * t - 10.0) * ((20.0 * t - 11.125) * c5).sin()) / 2.0
            } else {
                (2f32.powf(-20.0 * t + 10.0) * ((20.0 * t - 11.125) * c5).sin()) / 2.0 + 1.0
            }
        }
        Ease::InBounce => 1.0 - out_bounce(1.0 - t),
        Ease::OutBounce => out_bounce(t),
        Ease::InOutBounce => if t < 0.5 { (1.0 - out_bounce(1.0 - 2.0 * t)) / 2.0 } else { (1.0 + out_bounce(2.0 * t - 1.0)) / 2.0 },
    }
}

#[cfg(test)]
mod tests;
