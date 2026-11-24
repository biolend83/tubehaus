class EventEmitter {
  constructor() { this._ev = new Map(); }
  on(n, f) { if (!this._ev) this._ev = new Map(); (this._ev.get(n) || this._ev.set(n, []).get(n)).push(f); return this; }
  addListener(n, f) { return this.on(n, f); }
  once(n, f) { const w = (...a) => { this.off(n, w); f(...a); }; return this.on(n, w); }
  off(n, f) { const l = this._ev && this._ev.get(n); if (l) { const i = l.indexOf(f); if (i >= 0) l.splice(i, 1); } return this; }
  removeListener(n, f) { return this.off(n, f); }
  removeAllListeners(n) { if (this._ev) n ? this._ev.delete(n) : this._ev.clear(); return this; }
  emit(n, ...a) { const l = this._ev && this._ev.get(n); if (!l || !l.length) { if (n === "error") throw a[0]; return false; } for (const f of l.slice()) f.apply(this, a); return true; }
  listeners(n) { return ((this._ev && this._ev.get(n)) || []).slice(); } listenerCount(n) { return this.listeners(n).length; }
  setMaxListeners() { return this; }
}
EventEmitter.EventEmitter = EventEmitter; EventEmitter.once = (e, n) => new Promise((ok) => e.once(n, ok));
module.exports = EventEmitter;
