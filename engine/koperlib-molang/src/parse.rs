// text -> Op. hand written pratt-ish descent, precedence from the syntax guide after the
// 1.18.20 fixes: ?: lowest (right assoc), then ??, ||, &&, == !=, < <= > >=, + -, * /, unary
use crate::{BinOp, Book, Ease, Inner, MathFn, Op, Val};

#[derive(Clone, Debug, PartialEq)]
enum Tok {
    Num(f32),
    Str(String),
    Id(String),
    Sym(&'static str),
    End,
}

pub fn parse_error(src: &str) -> Option<String> {
    let mut b = Book::default();
    parse(src, &mut b).err()
}

fn lex(src: &str) -> Result<Vec<Tok>, String> {
    let c: Vec<char> = src.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    const SYMS: [&str; 26] = [
        "??", "->", "&&", "||", "==", "!=", "<=", ">=", "<", ">", "+", "-", "*", "/", "!", "?", ":",
        "(", ")", "{", "}", "[", "]", ",", ";", "=",
    ];
    'outer: while i < c.len() {
        let ch = c[i];
        if ch.is_whitespace() {
            i += 1;
            continue;
        }
        if ch.is_ascii_digit() || (ch == '.' && i + 1 < c.len() && c[i + 1].is_ascii_digit()) {
            let start = i;
            while i < c.len() && (c[i].is_ascii_digit() || c[i] == '.') {
                i += 1;
            }
            if i < c.len() && (c[i] == 'e' || c[i] == 'E') && i + 1 < c.len()
                && (c[i + 1].is_ascii_digit() || ((c[i + 1] == '-' || c[i + 1] == '+') && i + 2 < c.len() && c[i + 2].is_ascii_digit())) {
                i += 2;
                while i < c.len() && c[i].is_ascii_digit() {
                    i += 1;
                }
            }
            let text: String = c[start..i].iter().collect();
            // "1.0f" shows up in packs written by people who also write c
            if i < c.len() && (c[i] == 'f' || c[i] == 'F') {
                i += 1;
            }
            out.push(Tok::Num(text.parse::<f32>().map_err(|_| format!("bad number {text}"))?));
            continue;
        }
        if ch == '\'' {
            let start = i + 1;
            i += 1;
            while i < c.len() && c[i] != '\'' {
                i += 1;
            }
            if i >= c.len() {
                return Err("string never closes".into());
            }
            out.push(Tok::Str(c[start..i].iter().collect()));
            i += 1;
            continue;
        }
        if ch.is_alphabetic() || ch == '_' {
            let start = i;
            while i < c.len() && (c[i].is_alphanumeric() || c[i] == '_' || c[i] == '.') {
                i += 1;
            }
            let id: String = c[start..i].iter().collect::<String>().to_ascii_lowercase();
            // "return0.0" is glued together in real packs and bedrock eats it, so we do too
            if let Some(rest) = id.strip_prefix("return") {
                if !rest.is_empty() && rest.chars().all(|x| x.is_ascii_digit() || x == '.') {
                    out.push(Tok::Id("return".into()));
                    out.push(Tok::Num(rest.parse::<f32>().map_err(|_| format!("bad number {rest}"))?));
                    continue;
                }
            }
            out.push(Tok::Id(id.trim_end_matches('.').to_string()));
            continue;
        }
        for s in SYMS {
            let sc: Vec<char> = s.chars().collect();
            if c[i..].starts_with(&sc) {
                out.push(Tok::Sym(s));
                i += sc.len();
                continue 'outer;
            }
        }
        return Err(format!("unexpected '{ch}'"));
    }
    out.push(Tok::End);
    Ok(out)
}

struct P<'a> {
    t: Vec<Tok>,
    i: usize,
    b: &'a mut Book,
}

pub(crate) fn parse(src: &str, b: &mut Book) -> Result<Op, String> {
    let t = lex(src)?;
    let mut p = P { t, i: 0, b };
    let mut stmts = Vec::new();
    let mut had_semicolon = false;
    while p.peek() != &Tok::End {
        if p.eat(";") {
            had_semicolon = true;
            continue;
        }
        stmts.push(p.statement()?);
        if p.eat(";") {
            had_semicolon = true;
        } else if p.peek() != &Tok::End {
            return Err(format!("expected ; got {:?}", p.peek()));
        }
    }
    if stmts.is_empty() {
        return Ok(Op::Const(Val::Num(0.0)));
    }
    // one bare expression returns itself. with statements only an explicit return counts
    if !had_semicolon && stmts.len() == 1 {
        return Ok(stmts.pop().unwrap());
    }
    Ok(Op::Block(stmts))
}

impl<'a> P<'a> {
    fn peek(&self) -> &Tok {
        &self.t[self.i]
    }
    fn next(&mut self) -> Tok {
        let t = self.t[self.i].clone();
        if self.i < self.t.len() - 1 {
            self.i += 1;
        }
        t
    }
    fn eat(&mut self, s: &str) -> bool {
        if let Tok::Sym(x) = self.peek() {
            if *x == s {
                self.i += 1;
                return true;
            }
        }
        false
    }
    fn want(&mut self, s: &str) -> Result<(), String> {
        if self.eat(s) { Ok(()) } else { Err(format!("expected {s} got {:?}", self.peek())) }
    }

    fn statement(&mut self) -> Result<Op, String> {
        if let Tok::Id(id) = self.peek().clone() {
            match id.as_str() {
                "return" => {
                    self.next();
                    return Ok(Op::Return(Box::new(self.expr()?)));
                }
                "break" => { self.next(); return Ok(Op::Break); }
                "continue" => { self.next(); return Ok(Op::Continue); }
                _ => {}
            }
        }
        self.expr()
    }

    fn block(&mut self) -> Result<Op, String> {
        // after '{'
        let mut v = Vec::new();
        while !self.eat("}") {
            if self.peek() == &Tok::End {
                return Err("{ never closes".into());
            }
            if self.eat(";") {
                continue;
            }
            v.push(self.statement()?);
            self.eat(";");
        }
        Ok(Op::Block(v))
    }

    fn expr(&mut self) -> Result<Op, String> {
        let left = self.ternary()?;
        if self.eat("=") {
            let value = self.expr()?;
            // context.* is read only, remote actor writes go nowhere: assign() evaluates and drops those
            return Ok(self.assign(left, value));
        }
        Ok(left)
    }

    fn ternary(&mut self) -> Result<Op, String> {
        let c = self.coalesce()?;
        if self.eat("?") {
            let t = self.branch()?;
            if self.eat(":") {
                let e = self.ternary_branch()?;
                return Ok(Op::Cond(Box::new(c), Box::new(t), Some(Box::new(e))));
            }
            return Ok(Op::Cond(Box::new(c), Box::new(t), None));
        }
        Ok(c)
    }

    // the else side of ?: may hold an assignment or a block too, right associative
    fn ternary_branch(&mut self) -> Result<Op, String> {
        if self.eat("{") {
            return self.block();
        }
        let v = self.ternary()?;
        if self.eat("=") {
            let value = self.expr()?;
            return Ok(self.assign(v, value));
        }
        Ok(v)
    }

    fn branch(&mut self) -> Result<Op, String> {
        if self.eat("{") {
            return self.block();
        }
        if let Tok::Id(id) = self.peek().clone() {
            match id.as_str() {
                "break" => { self.next(); return Ok(Op::Break); }
                "continue" => { self.next(); return Ok(Op::Continue); }
                "return" => { self.next(); return Ok(Op::Return(Box::new(self.expr()?))); }
                _ => {}
            }
        }
        let v = self.coalesce_or_ternary()?;
        if self.eat("=") {
            let value = self.expr()?;
            return Ok(self.assign(v, value));
        }
        Ok(v)
    }

    // "a ? b ? c : d : e" nests, so the true branch parses a full ternary itself
    fn coalesce_or_ternary(&mut self) -> Result<Op, String> {
        self.ternary()
    }

    fn coalesce(&mut self) -> Result<Op, String> {
        let mut l = self.or()?;
        while self.eat("??") {
            let r = self.or()?;
            l = Op::Coalesce(Box::new(l), Box::new(r));
        }
        Ok(l)
    }
    fn or(&mut self) -> Result<Op, String> {
        let mut l = self.and()?;
        while self.eat("||") {
            let r = self.and()?;
            l = Op::Or(Box::new(l), Box::new(r));
        }
        Ok(l)
    }
    fn and(&mut self) -> Result<Op, String> {
        let mut l = self.eq()?;
        while self.eat("&&") {
            let r = self.eq()?;
            l = Op::And(Box::new(l), Box::new(r));
        }
        Ok(l)
    }
    fn eq(&mut self) -> Result<Op, String> {
        let mut l = self.cmp()?;
        loop {
            let op = if self.eat("==") { BinOp::Eq } else if self.eat("!=") { BinOp::Ne } else { break };
            let r = self.cmp()?;
            l = Op::Bin(op, Box::new(l), Box::new(r));
        }
        Ok(l)
    }
    fn cmp(&mut self) -> Result<Op, String> {
        let mut l = self.add()?;
        loop {
            let op = if self.eat("<=") { BinOp::Le } else if self.eat(">=") { BinOp::Ge }
                else if self.eat("<") { BinOp::Lt } else if self.eat(">") { BinOp::Gt } else { break };
            let r = self.add()?;
            l = Op::Bin(op, Box::new(l), Box::new(r));
        }
        Ok(l)
    }
    fn add(&mut self) -> Result<Op, String> {
        let mut l = self.mul()?;
        loop {
            let op = if self.eat("+") { BinOp::Add } else if self.eat("-") { BinOp::Sub } else { break };
            let r = self.mul()?;
            l = Op::Bin(op, Box::new(l), Box::new(r));
        }
        Ok(l)
    }
    fn mul(&mut self) -> Result<Op, String> {
        let mut l = self.unary()?;
        loop {
            let op = if self.eat("*") { BinOp::Mul } else if self.eat("/") { BinOp::Div } else { break };
            let r = self.unary()?;
            l = Op::Bin(op, Box::new(l), Box::new(r));
        }
        Ok(l)
    }
    fn unary(&mut self) -> Result<Op, String> {
        if self.eat("-") {
            return Ok(Op::Neg(Box::new(self.unary()?)));
        }
        if self.eat("+") {
            return self.unary();
        }
        if self.eat("!") {
            return Ok(Op::Not(Box::new(self.unary()?)));
        }
        self.postfix()
    }
    fn postfix(&mut self) -> Result<Op, String> {
        let mut v = self.primary()?;
        // v.target->q.health : another actor. only the holder of an attachable is reachable
        // (c.owning_entity->v.x), everything else has no actor graph here and reads 0
        while self.eat("->") {
            let rhs = self.primary()?;
            v = match (&v, &rhs) {
                (Op::Context(c), Op::Var(r)) if self.b.contexts[*c as usize] == "owning_entity" => {
                    let name = self.b.vars[*r as usize].clone();
                    Op::Remote(self.b.remote(&name))
                }
                _ => Op::Const(Val::Num(0.0)),
            };
        }
        Ok(v)
    }

    fn args(&mut self) -> Result<Vec<Op>, String> {
        let mut out = Vec::new();
        if self.eat(")") {
            return Ok(out);
        }
        loop {
            out.push(self.expr()?);
            if self.eat(")") {
                break;
            }
            self.want(",")?;
        }
        Ok(out)
    }

    fn primary(&mut self) -> Result<Op, String> {
        match self.next() {
            Tok::Num(n) => Ok(Op::Const(Val::Num(n))),
            Tok::Str(s) => Ok(Op::Const(Val::Str(self.b.string(&s)))),
            Tok::Sym("(") => {
                let v = self.expr()?;
                self.want(")")?;
                Ok(v)
            }
            Tok::Sym("{") => self.block(),
            Tok::Id(id) => self.ident(&id),
            t => Err(format!("unexpected {t:?}")),
        }
    }

    fn ident(&mut self, id: &str) -> Result<Op, String> {
        match id {
            "true" => return Ok(Op::Const(Val::Num(1.0))),
            "false" => return Ok(Op::Const(Val::Num(0.0))),
            "this" => return Ok(Op::Inner(Inner::This)),
            "loop" => {
                self.want("(")?;
                let n = self.expr()?;
                self.want(",")?;
                let body = self.expr()?;
                self.want(")")?;
                return Ok(Op::Loop(Box::new(n), Box::new(body)));
            }
            "for_each" => {
                // needs actor arrays, which the render side does not have. parse and do nothing
                self.want("(")?;
                self.args()?;
                return Ok(Op::Const(Val::Num(0.0)));
            }
            _ => {}
        }
        let (head, rest) = match id.find('.') {
            Some(d) => (&id[..d], &id[d + 1..]),
            None => return Err(format!("unknown identifier {id}")),
        };
        match head {
            "query" | "q" => self.query(rest),
            "variable" | "v" => Ok(Op::Var(self.b.var(rest))),
            "temp" | "t" => Ok(Op::Temp(self.b.temp(rest))),
            "context" | "c" => Ok(Op::Context(self.b.context(rest))),
            "math" => self.math(rest),
            "geometry" | "texture" | "material" => Ok(Op::Const(Val::Str(self.b.string(id)))),
            "array" => {
                let a = self.b.array(rest);
                self.want("[")?;
                let i = self.expr()?;
                self.want("]")?;
                Ok(Op::ArrayGet(a, Box::new(i)))
            }
            _ => Err(format!("unknown identifier {id}")),
        }
    }

    fn assign(&mut self, target: Op, value: Op) -> Op {
        let set = |t: &Op, v: Op| match t {
            Op::Var(i) => Op::SetVar(*i, Box::new(v)),
            Op::Temp(i) => Op::SetTemp(*i, Box::new(v)),
            _ => v,
        };
        // the few queries that hand back a struct (bone_origin -> .x .y .z, bone_orientation_trs ->
        // .t .r .s each with .x .y .z). we have no struct values: the struct is its fields, each one a
        // variable of its own ("left_arm.y" is how t.left_arm.y already reads) with its own query slot
        if let Op::Query(slot) = &value {
            let key = self.b.queries[*slot as usize].clone();
            let fields: &[&str] = if key.starts_with("bone_origin(") || key.starts_with("bone_pivot(") {
                &["x", "y", "z"]
            } else if key.starts_with("bone_orientation_trs(") {
                &["t.x", "t.y", "t.z", "r.x", "r.y", "r.z", "s.x", "s.y", "s.z"]
            } else {
                &[]
            };
            let name = match &target {
                Op::Var(i) => Some((true, self.b.vars[*i as usize].clone())),
                Op::Temp(i) => Some((false, self.b.temps[*i as usize].clone())),
                _ => None,
            };
            if let (false, Some((is_var, name))) = (fields.is_empty(), name) {
                let mut out = vec![set(&target, value.clone())];
                for f in fields {
                    let q = Op::Query(self.b.query(&format!("{key}.{f}")));
                    let field = format!("{name}.{f}");
                    out.push(if is_var { Op::SetVar(self.b.var(&field), Box::new(q)) } else { Op::SetTemp(self.b.temp(&field), Box::new(q)) });
                }
                return Op::Block(out);
            }
        }
        set(&target, value)
    }

    fn query(&mut self, name: &str) -> Result<Op, String> {
        let inner = match name {
            "anim_time" => Some(Inner::AnimTime),
            "delta_time" => Some(Inner::DeltaTime),
            "all_animations_finished" => Some(Inner::AllAnimationsFinished),
            "any_animation_finished" => Some(Inner::AnyAnimationFinished),
            "key_frame_lerp_time" => Some(Inner::KeyFrameLerpTime),
            "state_time" => Some(Inner::StateTime),
            _ => None,
        };
        let has_args = matches!(self.peek(), Tok::Sym("("));
        if let Some(k) = inner {
            if has_args {
                self.next();
                self.args()?;
            }
            return Ok(Op::Inner(k));
        }
        if !has_args {
            return Ok(Op::Query(self.b.query(name)));
        }
        self.next();
        let args = self.args()?;
        // the pure ones need no entity, they are just compares. their args are usually other
        // queries, which would make them slotless and read 0 forever
        if let Some(op) = pure(name, &args) {
            return Ok(op);
        }
        // constant args get their own slot: "is_item_equipped('main_hand')". java answers it by
        // parsing the key back. anything computed at run time can't have a slot, it reads 0
        let mut key = String::from(name);
        key.push('(');
        for (n, a) in args.iter().enumerate() {
            if n > 0 {
                key.push(',');
            }
            match crate::fold(a.clone()) {
                Op::Const(Val::Num(x)) => key.push_str(&format!("{x}")),
                Op::Const(Val::Str(s)) => {
                    key.push('\'');
                    key.push_str(&self.b.strings[s as usize].clone());
                    key.push('\'');
                }
                _ => key.push('?'),
            }
        }
        key.push(')');
        Ok(Op::Query(self.b.query(&key)))
    }

    fn math(&mut self, name: &str) -> Result<Op, String> {
        if name == "pi" {
            return Ok(Op::Const(Val::Num(std::f32::consts::PI)));
        }
        let f = match name {
            "abs" => MathFn::Abs, "acos" => MathFn::Acos, "asin" => MathFn::Asin, "atan" => MathFn::Atan,
            "atan2" => MathFn::Atan2, "ceil" => MathFn::Ceil, "clamp" => MathFn::Clamp,
            "copy_sign" => MathFn::CopySign, "cos" => MathFn::Cos, "die_roll" => MathFn::DieRoll,
            "die_roll_integer" => MathFn::DieRollInt, "exp" => MathFn::Exp, "floor" => MathFn::Floor,
            "hermite_blend" => MathFn::HermiteBlend, "inverse_lerp" => MathFn::InverseLerp,
            "lerp" => MathFn::Lerp, "lerprotate" => MathFn::LerpRotate, "ln" => MathFn::Ln,
            "max" => MathFn::Max, "min" => MathFn::Min, "min_angle" => MathFn::MinAngle, "mod" => MathFn::Mod,
            "pow" => MathFn::Pow, "random" => MathFn::Random, "random_integer" => MathFn::RandomInt,
            "round" => MathFn::Round, "sign" => MathFn::Sign, "sin" => MathFn::Sin, "sqrt" => MathFn::Sqrt,
            "trunc" => MathFn::Trunc,
            "ease_in_back" => MathFn::Ease(Ease::InBack), "ease_in_bounce" => MathFn::Ease(Ease::InBounce),
            "ease_in_circ" => MathFn::Ease(Ease::InCirc), "ease_in_cubic" => MathFn::Ease(Ease::InCubic),
            "ease_in_elastic" => MathFn::Ease(Ease::InElastic), "ease_in_expo" => MathFn::Ease(Ease::InExpo),
            "ease_in_out_back" => MathFn::Ease(Ease::InOutBack), "ease_in_out_bounce" => MathFn::Ease(Ease::InOutBounce),
            "ease_in_out_circ" => MathFn::Ease(Ease::InOutCirc), "ease_in_out_cubic" => MathFn::Ease(Ease::InOutCubic),
            "ease_in_out_elastic" => MathFn::Ease(Ease::InOutElastic), "ease_in_out_expo" => MathFn::Ease(Ease::InOutExpo),
            "ease_in_out_quad" => MathFn::Ease(Ease::InOutQuad), "ease_in_out_quart" => MathFn::Ease(Ease::InOutQuart),
            "ease_in_out_quint" => MathFn::Ease(Ease::InOutQuint), "ease_in_out_sine" => MathFn::Ease(Ease::InOutSine),
            "ease_in_quad" => MathFn::Ease(Ease::InQuad), "ease_in_quart" => MathFn::Ease(Ease::InQuart),
            "ease_in_quint" => MathFn::Ease(Ease::InQuint), "ease_in_sine" => MathFn::Ease(Ease::InSine),
            "ease_out_back" => MathFn::Ease(Ease::OutBack), "ease_out_bounce" => MathFn::Ease(Ease::OutBounce),
            "ease_out_circ" => MathFn::Ease(Ease::OutCirc), "ease_out_cubic" => MathFn::Ease(Ease::OutCubic),
            "ease_out_elastic" => MathFn::Ease(Ease::OutElastic), "ease_out_expo" => MathFn::Ease(Ease::OutExpo),
            "ease_out_quad" => MathFn::Ease(Ease::OutQuad), "ease_out_quart" => MathFn::Ease(Ease::OutQuart),
            "ease_out_quint" => MathFn::Ease(Ease::OutQuint), "ease_out_sine" => MathFn::Ease(Ease::OutSine),
            _ => return Err(format!("unknown math.{name}")),
        };
        self.want("(")?;
        let args = self.args()?;
        Ok(Op::Math(f, args))
    }
}

fn pure(name: &str, args: &[Op]) -> Option<Op> {
    let bx = |o: &Op| Box::new(o.clone());
    let eq = |a: &Op, b: &Op| Op::Bin(BinOp::Eq, bx(a), bx(b));
    let chain = |ops: Vec<Op>, and: bool| {
        ops.into_iter().reduce(|l, r| if and { Op::And(Box::new(l), Box::new(r)) } else { Op::Or(Box::new(l), Box::new(r)) })
    };
    match name {
        "in_range" if args.len() == 3 => Some(Op::And(
            Box::new(Op::Bin(BinOp::Ge, bx(&args[0]), bx(&args[1]))),
            Box::new(Op::Bin(BinOp::Le, bx(&args[0]), bx(&args[2]))))),
        "any" if args.len() >= 2 => chain(args[1..].iter().map(|a| eq(&args[0], a)).collect(), false),
        "all" if args.len() >= 2 => chain(args[1..].iter().map(|a| eq(&args[0], a)).collect(), true),
        "approx_eq" if args.len() >= 2 => chain(args[1..].iter().map(|a| Op::Bin(BinOp::Lt,
            Box::new(Op::Math(MathFn::Abs, vec![Op::Bin(BinOp::Sub, bx(&args[0]), bx(a))])),
            Box::new(Op::Const(Val::Num(1e-6))))).collect(), true),
        _ => None,
    }
}
