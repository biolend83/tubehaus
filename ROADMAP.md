# Roadmap

What's planned and where help is welcome. Plans, not promises: priorities change with what people ask for in
[issues](https://github.com/biolend83/tubehaus/issues) and [discussions](https://github.com/biolend83/tubehaus/discussions).

## Next

- **A TubeHaus icon of its own.** The current one is a red play button that looks too much like YouTube's
- **Better search**
- **Translation review by native speakers** for the machine-assisted languages ([#5](https://github.com/biolend83/tubehaus/issues/5))
- **Smoother public instances**: lighter on the server under a crowd, clearer messages when it's full

## Help wanted

Small, self-contained tasks are labelled
[`good first issue`](https://github.com/biolend83/tubehaus/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22).
Bigger ones are labelled [`help wanted`](https://github.com/biolend83/tubehaus/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22).
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) shows where things live in the code.

Also always welcome:

- Reverse proxy examples besides Caddy (nginx, Traefik)
- Reports of setups that work: server, devices, TV browsers ([#7](https://github.com/biolend83/tubehaus/issues/7))
- Tests for the server (`tests/test_server.py`)

## Not planned

- **A personal recommendation algorithm.** Nothing learns from what you watch, on purpose. (Shorts ranking is
  the one exception, and it stays on your server)
- **Playing age-restricted videos.** That needs a Google account, which TubeHaus never uses
- **An Invidious account system.** Since 1.6 the server asks YouTube itself and answers in Invidious' API format, but
  Invidious' own accounts, login and its classic pages are not part of it
