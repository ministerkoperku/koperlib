// @minecraft/server-ui. java turns these into vanilla dialogs (the ones 1.21.6 added), the
// answer comes back a few ticks later as a "form" message and resolves the promise
const K = globalThis.__koper;
const nope = (what) => { throw new Error("koperlib bedrock: " + what + " is not implemented yet"); };
const ENUMS = [];
const enumOf = (pairs) => { const e = Object.assign(Object.create(null), pairs); ENUMS.push(e); return e; };
const __koperFill = (e, more) => { for (const k in more) if (!(k in e)) e[k] = more[k]; };
const __koperSeal = () => { for (const e of ENUMS) Object.freeze(e); };
const __koperStub = (cls, m) => { Object.defineProperty(cls.prototype, m, { value() { nope(cls.name + "." + m); }, writable: true, configurable: true }); };
const waiting = new Map();

export const FormCancelationReason = enumOf({ UserBusy: "UserBusy", UserClosed: "UserClosed" });
export const FormRejectReason = enumOf({ MalformedResponse: "MalformedResponse", PlayerQuit: "PlayerQuit", ServerShutdown: "ServerShutdown" });

export class FormRejectError extends Error { constructor(reason) { super("form rejected: " + reason); this.name = "FormRejectError"; this.reason = reason; } }

const txt = (m) => K.rawToComponent(m);

function send(player, form) {
  return new Promise((ok, bad) => {
    const fid = K.ask("form", { e: player.id, f: form });
    if (fid === undefined || fid === null) { bad(new FormRejectError("MalformedResponse")); return; }
    waiting.set(fid, { ok, bad, kind: form.kind });
  });
}

K.on("form", (m) => {
  const w = waiting.get(m.fid);
  if (!w) return undefined;
  waiting.delete(m.fid);
  if (m.reject) { w.bad(new FormRejectError(m.reject)); return undefined; }
  if (w.dd) { w.dd(m); return undefined; }
  const res = { canceled: !!m.canceled, cancelationReason: m.canceled ? (m.reason || "UserClosed") : undefined };
  if (w.kind === "modal") res.formValues = m.canceled ? undefined : m.vals;
  else res.selection = m.canceled ? undefined : m.sel;
  w.ok(res);
  return undefined;
});

// ── 2.x data driven ui: MessageBox, CustomForm and the observables they bind ──────────────────
// bedrock keeps these screens open and live, java's dialogs close on any button. so a CustomForm
// button runs its onClick, writes every field back into its observable and the form is done;
// good enough for welcome boxes, settings pages and shops, which is what packs use them for
const val = (x) => (x !== null && typeof x === "object" && typeof x.getData === "function" ? x.getData() : x);
const ddTxt = (x) => txt(val(x));

class Observable {
  constructor(data, options) { this._d = data; this._subs = new Set(); this._opts = options; }
  getData() { return this._d; }
  setData(d) { this._d = d; for (const f of [...this._subs]) { try { f(d); } catch (e) { console.error("observable subscriber threw", e); } } }
  subscribe(cb) { this._subs.add(cb); return cb; }
  unsubscribe(cb) { return this._subs.delete(cb); }
}
export class ObservableBoolean extends Observable {}
export class ObservableNumber extends Observable {}
export class ObservableString extends Observable { getFilteredText() { return this._d; } }
export class ObservableUIRawMessage extends Observable {}

function sendDD(player, form, onAnswer) {
  return new Promise((ok, bad) => {
    const fid = K.ask("form", { e: player.id, f: form });
    if (fid === undefined || fid === null) { bad(new FormRejectError("MalformedResponse")); return; }
    waiting.set(fid, { ok, bad, kind: form.kind, dd: (m) => {
      if (m.reject) { bad(new FormRejectError(m.reject)); return; }
      ok(onAnswer(m));
    } });
  });
}

export class MessageBox {
  constructor(player, title) { this._p = player; this._f = { kind: "message", title: ddTxt(title), body: txt(""), b1: txt("gui.ok"), b2: null }; this._on = false; }
  body(t) { this._f.body = ddTxt(t); return this; }
  button1(label) { this._f.b1 = ddTxt(label); return this; }
  button2(label) { this._f.b2 = ddTxt(label); return this; }
  isShowing() { return this._on; }
  close() { if (this._on) { this._on = false; K.ask("form.close", { e: this._p.id }); } }
  show() {
    this._on = true;
    return sendDD(this._p, this._f, (m) => {
      this._on = false;
      // the sample packs say it: button1 answers 1, button2 answers 0
      if (m.canceled) return { closeReason: m.reason === "UserBusy" ? "UserBusy" : "ClientClosed", selection: undefined };
      return { closeReason: "ClientClosed", selection: m.sel === 0 ? 1 : 0 };
    });
  }
}

export class CustomForm {
  constructor(player, title) { this._p = player; this._title = title; this._items = []; this._clicks = []; this._binds = []; this._on = false; this._x = false; }
  _vis(o) { return o && o.visible !== undefined && !val(o.visible); }
  button(label, onClick, options) { if (!this._vis(options)) { this._items.push({ t: "button", label: ddTxt(label), n: this._clicks.length }); this._clicks.push(onClick); } return this; }
  closeButton() { this._x = true; return this; }
  divider(options) { if (!this._vis(options)) this._items.push({ t: "divider" }); return this; }
  spacer() { return this; }
  header(text, options) { if (!this._vis(options)) this._items.push({ t: "header", label: ddTxt(text) }); return this; }
  label(text, options) { if (!this._vis(options)) this._items.push({ t: "label", label: ddTxt(text) }); return this; }
  image() { return this; }
  textField(label, text, options) {
    if (this._vis(options)) return this;
    this._items.push({ t: "text", label: ddTxt(label), ph: txt(""), def: String(val(text) ?? "") });
    this._binds.push((v) => text && text.setData && text.setData(String(v ?? "")));
    return this;
  }
  toggle(label, toggled, options) {
    if (this._vis(options)) return this;
    this._items.push({ t: "toggle", label: ddTxt(label), def: !!val(toggled) });
    this._binds.push((v) => toggled && toggled.setData && toggled.setData(!!v));
    return this;
  }
  slider(label, value, min, max, options) {
    if (this._vis(options)) return this;
    this._items.push({ t: "slider", label: ddTxt(label), min: val(min), max: val(max), step: val(options && options.step) || 1, def: val(value) ?? val(min) });
    this._binds.push((v) => value && value.setData && value.setData(Number(v)));
    return this;
  }
  dropdown(label, value, items, options) {
    if (this._vis(options)) return this;
    const vals = items.map((i) => i.value);
    const at = Math.max(0, vals.indexOf(val(value)));
    this._items.push({ t: "dropdown", label: ddTxt(label), opts: items.map((i) => ddTxt(i.label)), def: at });
    this._binds.push((v) => value && value.setData && value.setData(vals[v] ?? vals[0]));
    return this;
  }
  isShowing() { return this._on; }
  close() { if (this._on) { this._on = false; K.ask("form.close", { e: this._p.id }); } }
  show() {
    this._on = true;
    const form = { kind: "custom", title: ddTxt(this._title), items: this._items };
    return sendDD(this._p, form, (m) => {
      this._on = false;
      if (m.canceled) return m.reason === "UserBusy" ? "UserBusy" : "ClientClosed";
      (m.vals || []).forEach((v, i) => { if (this._binds[i]) this._binds[i](v); });
      const f = this._clicks[m.sel];
      if (typeof f === "function") { try { f(); } catch (e) { console.error("CustomForm button threw", e); } }
      return "ClientClosed";
    });
  }
}

// modal options come in two shapes: old positional (label, placeholder, default) and the new {defaultValue, tooltip}
const pick = (x, key) => (x !== null && typeof x === "object" && !Array.isArray(x) ? x[key] : x);

export class ActionFormData {
  constructor() { this._f = { kind: "action", title: txt(""), body: txt(""), items: [] }; }
  title(t) { this._f.title = txt(t); return this; }
  body(t) { this._f.body = txt(t); return this; }
  button(text, iconPath) { this._f.items.push({ t: "button", label: txt(text), icon: iconPath }); return this; }
  divider() { this._f.items.push({ t: "divider" }); return this; }
  header(text) { this._f.items.push({ t: "header", label: txt(text) }); return this; }
  label(text) { this._f.items.push({ t: "label", label: txt(text) }); return this; }
  show(player) { return send(player, this._f); }
}

export class MessageFormData {
  constructor() { this._f = { kind: "message", title: txt(""), body: txt(""), b1: txt(""), b2: txt("") }; }
  title(t) { this._f.title = txt(t); return this; }
  body(t) { this._f.body = txt(t); return this; }
  button1(t) { this._f.b1 = txt(t); return this; }
  button2(t) { this._f.b2 = txt(t); return this; }
  show(player) { return send(player, this._f); }
}

export class ModalFormData {
  constructor() { this._f = { kind: "modal", title: txt(""), items: [], submit: txt("gui.done") }; }
  title(t) { this._f.title = txt(t); return this; }
  submitButton(t) { this._f.submit = txt(t); return this; }
  textField(label, placeholder, opts) {
    this._f.items.push({ t: "text", label: txt(label), ph: txt(placeholder), def: String(pick(opts, "defaultValue") ?? "") });
    return this;
  }
  dropdown(label, options, opts) {
    this._f.items.push({ t: "dropdown", label: txt(label), opts: options.map(txt), def: pick(opts, "defaultValueIndex") ?? 0 });
    return this;
  }
  slider(label, min, max, stepOrOpts, def) {
    const o = typeof stepOrOpts === "object" ? stepOrOpts : { valueStep: stepOrOpts, defaultValue: def };
    this._f.items.push({ t: "slider", label: txt(label), min, max, step: o.valueStep || 1, def: o.defaultValue ?? min });
    return this;
  }
  toggle(label, opts) { this._f.items.push({ t: "toggle", label: txt(label), def: !!pick(opts, "defaultValue") }); return this; }
  divider() { this._f.items.push({ t: "divider" }); return this; }
  header(text) { this._f.items.push({ t: "header", label: txt(text) }); return this; }
  label(text) { this._f.items.push({ t: "label", label: txt(text) }); return this; }
  show(player) { return send(player, this._f); }
}

export const uiManager = { closeAllForms(player) { K.ask("form.close", { e: player.id }); } };
