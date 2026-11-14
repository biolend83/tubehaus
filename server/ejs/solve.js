// yt-dlp's EJS solver (yt.solver.*.min.js beside this file, release 0.8.0, Unlicense) on YouTube's
// player script, for tubehaus_server.py. Runs under deno or node:
//   <runtime> solve.js <this dir> load  <pre.js>   stdin: the player script -> writes its preprocessed form, prints "ok"
//   <runtime> solve.js <this dir> solve <pre.js>   stdin: [{"type":"n"|"sig","challenges":[...]}] -> prints the answers (JSON)
const isDeno = typeof Deno !== 'undefined';
const [dir, mode, pre] = isDeno ? Deno.args : process.argv.slice(2);
const read = (p) => isDeno ? Deno.readTextFileSync(p) : require('fs').readFileSync(p, 'utf8');
const write = (p, s) => isDeno ? Deno.writeTextFileSync(p, s) : require('fs').writeFileSync(p, s);
const stdin = async () => isDeno ? await new Response(Deno.stdin.readable).text() : require('fs').readFileSync(0, 'utf8');
(0, eval)(read(dir + '/yt.solver.lib.min.js') + '\n;var meriyah=lib.meriyah,astring=lib.astring;\n' + read(dir + '/yt.solver.core.min.js') + '\n;globalThis.__jsc=jsc;');
(async () => {
  const input = await stdin();
  if (mode === 'load') {
    const o = globalThis.__jsc({ type: 'player', player: input, requests: [], output_preprocessed: true });
    write(pre, o.preprocessed_player);
    console.log('ok');
  } else {
    console.log(JSON.stringify(globalThis.__jsc({ type: 'preprocessed', preprocessed_player: read(pre), requests: JSON.parse(input) })));
  }
})();
