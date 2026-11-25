#!/bin/bash
# libmpv and the libraries it needs, copied into one folder and set to find each other there ($ORIGIN): the
# libmpv-linux-<arch>.tar.gz the app downloads once when the system has no libmpv (MpvDownload.kt). Left to the system, as AppImages do: the C
# library, graphics (GL, Vulkan, X11, Wayland, DRM, VA-API, VDPAU), sound servers (ALSA, PulseAudio, PipeWire,
# JACK) and fonts; those belong to the machine and its drivers. Where the bundled copy won't load (a system much
# older than the build's), the app says to install libmpv.
set -euo pipefail
out=$1
src=${MPV_LIB:-}; [ -n "$src" ] || src=$(ldconfig -p | awk '/libmpv\.so\.2 /{print $NF; exit}')
[ -n "$src" ] || { echo "no libmpv.so.2 (apt install libmpv2)"; exit 1; }
mkdir -p "$out"
# exact names (the part before ".so"): what every desktop Linux has, or what must match the machine's own drivers
skip='^(ld-linux-.*|libc|libm|libmvec|libdl|libpthread|librt|libresolv|libutil|libanl|libnss_.*|libgcc_s|libstdc\+\+|libGL|libGLX|libGLX_.*|libGLdispatch|libEGL|libEGL_.*|libGLESv2|libOpenGL|libgbm|libdrm|libglapi|libX11|libX11-xcb|libxcb|libasound|libpipewire-0\.3|libjack|libfontconfig|libfreetype|libexpat|libz|libdbus-1|libsystemd|libudev|libglib-2\.0|libgobject-2\.0|libgio-2\.0|libgmodule-2\.0|libgcc_s)$'
cp -L "$src" "$out/libmpv.so.2"
# every library libmpv needs, the ones from the list above left out
ldd "$src" | awk '/=> \//{print $1, $3}' | while read -r name path; do
  base=${name%%.so*}
  if [[ $base =~ $skip ]]; then continue; fi
  cp -L "$path" "$out/$name"
done
for f in "$out"/*.so*; do chmod u+w "$f"; patchelf --set-rpath '$ORIGIN' "$f"; done
# check: loads from the bundle, and nothing it needs is missing
missing=$(LD_LIBRARY_PATH= ldd "$out/libmpv.so.2" | grep 'not found' || true)
[ -z "$missing" ] || { echo "$missing"; exit 1; }
python3 - "$out/libmpv.so.2" <<'PY'
import ctypes, sys
m = ctypes.CDLL(sys.argv[1]); m.mpv_client_api_version.restype = ctypes.c_ulong
print("libmpv api", hex(m.mpv_client_api_version()))
PY
ls "$out" | wc -l; du -sh "$out"
