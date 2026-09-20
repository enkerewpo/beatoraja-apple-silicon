#!/bin/bash
# Launch script for beatoraja.app.
#
# This calls the JRE inside the bundle directly instead of using the jpackage launcher:
#   1. jpackage cannot set a working directory, and Finder starts apps with CWD=/, so
#      relative paths in core (font/VL-Gothic-Regular.ttf, skin/default/select.json)
#      all miss. Java cannot chdir, so the directory has to be right before the JVM starts.
#   2. The jpackage launcher looks up its .cfg by its own filename, so renaming it to slot a
#      wrapper in front breaks it. Its classpath and internal-file resolution also differ
#      from plain java: with the same installation directory, gradle run works while the
#      jpackage launcher reports missing files.
# Calling java ourselves keeps the classpath, working directory and JVM flags identical to
# the development setup.

set -u

# The script's own directory must be resolved before cd, or dirname ends up relative to the
# new directory.
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_ROOT="$(cd "$BIN_DIR/.." && pwd)"
JAVA="$APP_ROOT/runtime/Contents/Home/bin/java"

if [ ! -x "$JAVA" ]; then
    echo "no JRE inside the bundle: $JAVA" >&2
    exit 1
fi

for candidate in \
    "${BEATORAJA_ROOT:-}" \
    "$HOME/Games/beatoraja0.8.8-modernchic" \
    "$HOME/Games/beatoraja" \
    "$HOME/beatoraja"; do
    if [ -n "$candidate" ] && [ -d "$candidate/skin" ]; then
        cd "$candidate" || exit 1
        exec "$JAVA" \
            -XstartOnFirstThread \
            -Xms512m -Xmx2g -XX:+UseZGC \
            -Dbeatoraja.root="$candidate" \
            -cp "$APP_ROOT/app/*" \
            com.starxh.beatoraja.desktop.DesktopLauncher "$@"
    fi
done

echo "No beatoraja installation found (needs a skin/ subdirectory)." >&2
echo "Set BEATORAJA_ROOT to point at one, e.g." >&2
echo "  BEATORAJA_ROOT=/path/to/beatoraja open -a beatoraja" >&2
exit 1
