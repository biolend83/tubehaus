# TubeHaus on Samsung and LG TVs (experimental)

Small TV apps that open **your own TubeHaus server**. Enter its address once (the one you open in a
browser, for example `http://192.168.1.10:8796`); after that the app opens it by itself, with three
seconds to press a button and change it. On these TVs the TubeHaus web app switches to its TV mode:
arrows move, OK opens, Back goes back (and closes the app on the first screen), and the remote's
play / pause / fast-forward / rewind keys work in the player.

They are not in the Samsung or LG stores. They are installed the way developers install their own
apps, which needs a computer on the same network. Both are new and not tested on many TVs yet:
reports are welcome in the [issues](https://github.com/biolend83/tubehaus/issues).

The files are attached to each server release: `TubeHaus-webOS-<version>.ipk` and
`TubeHaus-Tizen-project-<version>.zip`.

## LG (webOS)

1. On the TV, install **Developer Mode** from the LG Content Store, sign in with an LG developer
   account (free) and turn Developer Mode and the Key Server on. The TV restarts.
2. On the computer, install the webOS CLI: `npm install -g @webos-tools/cli`.
3. Add the TV: `ares-setup-device` (its IP address, the passphrase the Developer Mode app shows).
4. Install: `ares-install -d <tv name> TubeHaus-webOS-<version>.ipk`.

Developer Mode stays on for 50 hours at a time; the Developer Mode app extends it.

## Samsung (Tizen)

Samsung TVs only install apps signed with a certificate made for that TV, so the project is
attached for you to build:

1. Install **Tizen Studio** with the *TV Extensions* and the *Samsung Certificate Extension*
   (Package Manager).
2. On the TV, open Apps, type `12345` with the remote, turn **Developer mode** on and enter your
   computer's IP address. Restart the TV.
3. In Tizen Studio: *Device Manager* → connect the TV; *Certificate Manager* → a new **Samsung**
   certificate for the TV.
4. Unzip `TubeHaus-Tizen-project-<version>.zip`, import it (*File → Import → Tizen → Tizen
   Project*, or `tizen package -t wgt -s <your certificate profile> -- <folder>` on the command
   line), then *Run As → Tizen Web Application* to install it on the TV.

## How it works

`start.html` is the whole app: it keeps the address in the TV app's storage and opens the server
page directly. Your server needs nothing extra; the TV mode, the remote keys and closing the app on
Back are in the web app itself (`web/y/tv.js`).
