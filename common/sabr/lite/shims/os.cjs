// navigator.hardwareConcurrency in jsdom is os.cpus().length: the host's count (__cores), else 8
const n = () => (typeof globalThis.__cores === "number" && globalThis.__cores > 0 ? globalThis.__cores : 8);
const cpus = () => Array.from({ length: n() }, () => ({ model: "", speed: 0, times: {} }));
const os = { cpus, platform: () => "win32", type: () => "Windows_NT", release: () => "10.0.0", arch: () => "x64", EOL: "\r\n", tmpdir: () => "/tmp", homedir: () => "/", hostname: () => "localhost", totalmem: () => 8 * 2 ** 30, freemem: () => 4 * 2 ** 30, availableParallelism: n, endianness: () => "LE" };
module.exports = os;
