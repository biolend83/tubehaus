class Dispatcher { constructor() {} dispatch() { throw new Error("no network in jsdom here"); } compose() { return this; } close() { return Promise.resolve(); } destroy() { return Promise.resolve(); } }
class Agent extends Dispatcher {}
class WebSocket { constructor() { throw new Error("no WebSocket"); } }
class DecoratorHandler { constructor(h) { this.handler = h; } }
module.exports = { DecoratorHandler, util: {}, errors: {}, Dispatcher, Agent, ProxyAgent: Agent, Pool: Agent, Client: Agent, WebSocket, interceptors: {}, getGlobalDispatcher: () => new Agent(), setGlobalDispatcher() {}, fetch: () => Promise.reject(new Error("no")), Headers: globalThis.Headers, Response: globalThis.Response, Request: globalThis.Request };
