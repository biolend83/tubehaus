const S = new Proxy(function () {}, { get: (t, k) => (k === "__esModule" ? false : k === "prototype" ? {} : S), apply: () => S, construct: () => ({}) });
module.exports = S;
