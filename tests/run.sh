#!/bin/sh
# Esegue i UnitTest di una cartella: tests/run.sh <cartella>
# Variabili: SCLANG (percorso di sclang), TIMEOUT (secondi, default 120).
# Uscita: 0 tutto verde, 1 fallimenti o classi che non compilano, 2 errore d'uso, 142 scadenza.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SCLANG=${SCLANG:-/Applications/SuperCollider.app/Contents/MacOS/sclang}
TIMEOUT=${TIMEOUT:-120}
# senza questo controllo l'exec fallito di perl uscirebbe con 0: falso verde
[ -x "$SCLANG" ] || { echo "ERRORE: sclang non trovato in $SCLANG (imposta SCLANG)" >&2; exit 2; }
# La scadenza sta fuori da sclang: un ciclo infinito blocca anche i suoi clock.
# perl perché macOS non ha timeout(1); allo scadere SIGALRM chiude sclang (142).
perl -e 'alarm shift; exec @ARGV' "$TIMEOUT" \
	"$SCLANG" --include-path "$ROOT/Classes" --include-path "$1" "$ROOT/tests/run_tests.scd" "$1" </dev/null
code=$?
[ $code -eq 142 ] && echo "ERRORE: scadenza di $TIMEOUT s superata in $1 (un test non ritorna?)" >&2
exit $code
