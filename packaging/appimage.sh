#!/usr/bin/env bash
# Builds The Grid as one AppImage file for Ubuntu (and other x86_64 Linux):
#
#   packaging/appimage.sh          ->  dist/The-Grid-1.0.0-x86_64.AppImage
#
# Same recipe as EVIA Paradigma's packaging/appimage.sh: jpackage makes an app image with its own
# trimmed Java runtime (so Java need not be installed), appimagetool wraps it in one file.
# appimagetool runs with --appimage-extract-and-run, so FUSE (libfuse2) is not needed to build.
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="The Grid"; ID=the-grid; VERSION=1.0.0
FLATLAF_VERSION=3.8; FLATLAF_SHA1=5e06f19b28ef1fa1543fa99ac632903aa2cede6b
ARCH="${ARCH:-$(uname -m)}"
B=build; OUT=dist
say() { printf '\n>> %s\n' "$*"; }
die() { printf '\n!! %s\n' "$*" >&2; exit 1; }
for t in javac jar jpackage curl; do command -v $t > /dev/null || die "$t is missing (sudo apt install openjdk-21-jdk curl)"; done

# ---- FlatLaf (Apache 2.0), from Maven Central, checked
FL="$B/lib/flatlaf-$FLATLAF_VERSION.jar"
mkdir -p "$B/lib"
if ! echo "$FLATLAF_SHA1  $FL" | sha1sum -c --status 2> /dev/null; then
  say "Downloading FlatLaf $FLATLAF_VERSION"
  curl -fsSL -o "$FL" "https://repo1.maven.org/maven2/com/formdev/flatlaf/$FLATLAF_VERSION/flatlaf-$FLATLAF_VERSION.jar"
  echo "$FLATLAF_SHA1  $FL" | sha1sum -c --status || die "flatlaf jar is damaged"
fi

# ---- compile and jar (Java 17 bytecode)
say "Compiling"
rm -rf "$B/classes" "$B/input" "$B/jpackage" "$B/appimage"
javac --release 17 -d "$B/classes" src/brblocks/*.java
mkdir -p "$B/input"
cp "$FL" "$B/input/"
printf 'Main-Class: brblocks.BuildrootBlocks\nClass-Path: flatlaf-%s.jar\n' "$FLATLAF_VERSION" > "$B/manifest.txt"
jar --create --file "$B/input/$ID.jar" --manifest "$B/manifest.txt" -C "$B/classes" .

# ---- icon
java packaging/MakeIcon.java "$B/$ID.png"

# ---- app image with a trimmed runtime
say "jpackage app image"
jpackage --type app-image --dest "$B/jpackage" --name "$ID" --app-version "$VERSION" \
  --input "$B/input" --main-jar "$ID.jar" --main-class brblocks.BuildrootBlocks \
  --icon "$B/$ID.png" --vendor "EVIA" --description "Buildroot builds as EVIA Paradigma blocks" \
  --add-modules java.base,java.desktop,java.prefs \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6" \
  --java-options "-Dawt.useSystemAAFontSettings=on -Dsun.java2d.uiScale.enabled=true"
IMAGE="$B/jpackage/$ID"
# Ubuntu's JDK ships big .debuginfo files next to its libraries; the app needs none of them
find "$IMAGE" -name '*.debuginfo' -delete
command -v strip > /dev/null && find "$IMAGE/lib/runtime" -name '*.so' -exec strip --strip-unneeded {} + || true

# ---- AppDir
APPDIR="$B/appimage/$ID.AppDir"
mkdir -p "$APPDIR/usr"
cp -a "$IMAGE/." "$APPDIR/usr/"
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/usr/bin/env bash
# Entry point of the AppImage. The runtime mounts the bundle and sets APPDIR.
HERE="${APPDIR:-$(dirname "$(readlink -f "$0")")}"
exec "$HERE/usr/bin/the-grid" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"
cat > "$APPDIR/$ID.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=$NAME
GenericName=Buildroot block viewer
Comment=Shows a Buildroot build as EVIA Paradigma-style blocks
Exec=AppRun %f
Icon=$ID
Categories=Development;
Terminal=false
StartupWMClass=brblocks-BuildrootBlocks
X-AppImage-Version=$VERSION
DESKTOP
cp "$B/$ID.png" "$APPDIR/$ID.png"
install -Dm644 "$B/$ID.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/$ID.png"
install -Dm644 "$APPDIR/$ID.desktop" "$APPDIR/usr/share/applications/$ID.desktop"

say "Smoke test through AppRun"
APPDIR="$APPDIR" "$APPDIR/AppRun" --version | grep -q "$NAME $VERSION" || die "the app did not start from the AppDir"

# ---- appimagetool
TOOL="$B/appimagetool-$ARCH.AppImage"
if [ ! -x "$TOOL" ]; then
  say "Downloading appimagetool"
  curl -fsSL -o "$TOOL" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
  chmod +x "$TOOL"
fi
mkdir -p "$OUT"
TARGET="$OUT/The-Grid-$VERSION-$ARCH.AppImage"
rm -f "$TARGET"
ARCH="$ARCH" "$TOOL" --appimage-extract-and-run --no-appstream "$APPDIR" "$TARGET"
chmod +x "$TARGET"

say "Smoke test of the finished AppImage"
"$TARGET" --appimage-extract-and-run --version | grep -q "$NAME $VERSION" || die "the AppImage did not start"
say "Done: $TARGET ($(du -h "$TARGET" | cut -f1))"
