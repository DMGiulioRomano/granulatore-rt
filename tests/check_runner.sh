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

# verifica <descrizione> <cartella> <codice atteso, o !0> [testi attesi nell'output...]
verifica() {
	descr=$1 cartella=$2 atteso=$3
	shift 3
	out=$("$HERE/run.sh" "$cartella" 2>&1)
	code=$?
	ok=1 mancante=
	if [ "$atteso" = "!0" ]; then [ "$code" -ne 0 ] || ok=0; else [ "$code" -eq "$atteso" ] || ok=0; fi
	for testo in "$@"; do
		printf '%s\n' "$out" | grep -qF -- "$testo" || { ok=0; mancante=$testo; }
	done
	if [ $ok -eq 1 ]; then
		echo "ok      $descr"
	else
		echo "FALLITO $descr (uscita $code, attesa $atteso${mancante:+, testo mancante: $mancante})"
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

fixture verde <<'EOF'
FixtureTest : UnitTest {
	test_uno { this.assert(true, "uno") }
	test_due { this.assert(true, "due") }
}
EOF
# il conteggio esatto smaschera un filtro che non seleziona nulla (falso verde)
verifica "tutti verdi: esce con 0 e conta i metodi eseguiti" "$TMP/verde" 0 "metodi 2,"

# UnitTest non cattura le eccezioni: senza il runner la Routine morirebbe senza contarle
fixture eccezione <<'EOF'
FixtureTest : UnitTest {
	tearDown { "TEARDOWN ESEGUITO".postln }
	test_a_esplode { Error("boom").throw }
	test_b_dopo { "SECONDO ESEGUITO".postln; this.assert(true, "dopo") }
}
EOF
verifica "un'eccezione è un fallimento, tearDown e test successivi girano" "$TMP/eccezione" 1 \
	"boom" "TEARDOWN ESEGUITO" "SECONDO ESEGUITO" "metodi 2,"

[ $falliti -eq 0 ] && echo "runner: tutti i meta-test passano" || echo "runner: $falliti meta-test falliti"
[ $falliti -eq 0 ]
