# Test del granulatore. Variabili opzionali, per esempio:
#   make test SCLANG=/percorso/sclang TIMEOUT=300
# Default in tests/run.sh: sclang di /Applications/SuperCollider.app, scadenza 120 s.

.PHONY: test test-nrt test-rt test-runner test-all

# livello 1: logica in sclang, senza server
test:
	tests/run.sh tests/unit

# livello 2: SynthDef rese in NRT
test-nrt:
	tests/run.sh tests/nrt

# livello 3: server e microfono, manuale
test-rt:
	tests/run.sh tests/rt

# meta-test del runner stesso
test-runner:
	tests/check_runner.sh

# il gate prima di commit, push, PR e tag
test-all: test-runner test test-nrt
