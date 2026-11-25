class WebSocket { constructor() { throw new Error("no WebSocket"); } }
WebSocket.CONNECTING = 0; WebSocket.OPEN = 1; WebSocket.CLOSING = 2; WebSocket.CLOSED = 3;
module.exports = WebSocket; module.exports.WebSocket = WebSocket;
