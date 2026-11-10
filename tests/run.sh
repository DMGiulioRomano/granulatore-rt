#!/bin/sh
# Esegue i UnitTest di una cartella: tests/run.sh <cartella>
# Variabili: SCLANG (percorso di sclang).
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SCLANG=${SCLANG:-/Applications/SuperCollider.app/Contents/MacOS/sclang}
exec "$SCLANG" --include-path "$ROOT/Classes" --include-path "$1" "$ROOT/tests/run_tests.scd" "$1" </dev/null
