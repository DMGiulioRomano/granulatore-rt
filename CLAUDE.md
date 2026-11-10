# CLAUDE.md

Istruzioni per Claude Code in questo repository.

## Panoramica

Granulatore in tempo reale e interattivo in SuperCollider: microfono in un buffer circolare, grani letti dal vivo, parametri da envelope, da segnali di controllo o da entrambi. I concetti vengono da PythonGranularEngine (PGE), ma qui non c'è codice Python di PGE. Architettura: [README.md](README.md). Decisioni: [docs/adr/](docs/adr/). Glossario: [CONTEXT.md](CONTEXT.md), da usare con i termini esatti.

## Comportamento

- **Lingua:** rispondi sempre in italiano. No emoji, mai.
- **Prima di modificare una classe esistente**, leggi chi la usa e i suoi test.
- **Design prima del codice:** non scrivere codice di produzione senza aver proposto il design e le suite di test e aver avuto conferma.

## TDD

- Usa la skill `/tdd`: test rosso, conferma del fallimento, poi il codice minimo che lo fa passare. Mai test e produzione nello stesso passo.
- Tre livelli di test, ognuno con il suo posto:
  1. `tests/unit/`: logica in sclang senza server (scheduler, parametri, bande, mapping, vincoli del ritardo, seed). Veloci, in CI.
  2. `tests/nrt/`: SynthDef rese in non real-time (`Score.recordNRT`) e verificate sul file campione per campione: onset, finestra, ritardo dalla testina. Deterministici, in CI.
  3. `tests/rt/`: server acceso e microfono. Manuali: la verifica d'ascolto spetta all'utente.
- La logica va dove si può testare a livello 1. Il server deve restare sottile: tutto ciò che non deve per forza stare vicino all'audio sta in sclang.
- Il tempo nei test di livello 1 è sempre logico (un clock iniettato), mai il wall clock.
- Le classi FluCoMa possono mancare: i test che le usano si saltano se `\FluidLoudness.asClass` è nil, non falliscono.

## Test gate

Prima di ogni commit, push, PR o tag: `make test-all` deve passare (meta-test del runner, livelli 1 e 2). Comandi e codici d'uscita sono nel README, sezione Test.

## Macchina

L'utente lavora su un Apple M2 Max (arm64, 32 GB) con SuperCollider installato. Misure e benchmark valgono solo se fatti lì: se la sessione gira su un'altra macchina (`uname -m`), dichiaralo.

## Note di implementazione

- **Un grano è immutabile e si campiona all'onset:** tutti i parametri si leggono una volta quando il grano nasce. Lo smoothing riguarda la traiettoria dei controlli fra un onset e l'altro, non l'interno del grano.
- **Il pointer è un ritardo dalla testina di scrittura**, mai una posizione assoluta: il synth del grano legge la posizione della testina dal bus nell'istante in cui nasce.
- **Vincoli del ritardo:** con rapporto di lettura r, durata L, buffer N e margine m, si richiede D >= (r-1)*L + m quando r > 1, e D <= N - (1-r)*L quando r < 1. Il controllo vive in `Classes/Live/`, non nel synth.
- **Grani al campione:** i grani si creano con `s.bind` e con la latenza del server, e usano `OffsetOut`, mai `Out`.
- **Feature:** ogni adapter (UGen native, FluCoMa, OSC esterno) scrive su un bus con nome, normalizzato 0..1. Il granulatore legge solo i bus e non conosce l'adapter.
