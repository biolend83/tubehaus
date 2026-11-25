const EE = require("./events.cjs");
class Stream extends EE {}
class Readable extends Stream { push() { return true; } read() { return null; } pipe(d) { return d; } }
class Writable extends Stream { constructor(o) { super(); } write(c, e, cb) { if (this._write) this._write(c, e, typeof cb === "function" ? cb : () => {}); return true; } end(c) { if (c) this.write(c); if (this._final) this._final(() => {}); this.emit("finish"); return this; } }
class Duplex extends Readable {} class Transform extends Duplex {} class PassThrough extends Transform {}
module.exports = { Stream, Readable, Writable, Duplex, Transform, PassThrough, pipeline: () => {}, finished: () => {} };
