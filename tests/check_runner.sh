#!/bin/sh
# Meta-test del runner. Sta fuori dal runner: se il runner uscisse sempre con 0,
# ingoierebbe anche il fallimento di un suo meta-test scritto come UnitTest.
# Le fixture si generano in una cartella temporanea, mai committate come .sc:
# una classe rotta nell'albero del quark romperebbe ogni compilazione.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
falliti=0

# fixture <nome>: scrive lo stdin in $TMP/<nome>/Fixture.sc
fixture() {
	mkdir -p "$TMP/$1"
	cat > "$TMP/$1/Fixture.sc"
}

# verifica <descrizione> <cartella> <codice atteso, o !0> [testo atteso nell'output]
verifica() {
	out=$("$HERE/run.sh" "$2" 2>&1)
	code=$?
	ok=1
	if [ "$3" = "!0" ]; then [ "$code" -ne 0 ] || ok=0; else [ "$code" -eq "$3" ] || ok=0; fi
	if [ $# -ge 4 ] && ! printf '%s\n' "$out" | grep -qF -- "$4"; then ok=0; fi
	if [ $ok -eq 1 ]; then
		echo "ok      $1"
	else
		echo "FALLITO $1 (uscita $code, attesa $3${4:+, testo atteso: $4})"
		printf '%s\n' "$out" | tail -n 20 | sed 's/^/        | /'
		falliti=$((falliti + 1))
	fi
}

fixture rosso <<'EOF'
FixtureTest : UnitTest {
	test_verde { this.assert(true, "verde") }
	test_rosso { this.assert(false, "rosso voluto") }
}
EOF
verifica "un test rosso fa uscire con 1" "$TMP/rosso" 1 "rosso voluto"

[ $falliti -eq 0 ] && echo "runner: tutti i meta-test passano" || echo "runner: $falliti meta-test falliti"
[ $falliti -eq 0 ]
