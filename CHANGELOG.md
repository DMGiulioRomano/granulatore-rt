# Changelog

Il formato segue [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/) e il progetto usa il [Semantic Versioning](https://semver.org/lang/it/).

## [Unreleased]

### Added

- Struttura del repository in forma di quark, con README, CLAUDE.md e glossario (CONTEXT.md).
- ADR 0001-0003: tutto in SuperCollider, scheduler in sclang con un Synth per grano, feature su bus con nome.
- Piano a tappe con le suite di test proposte (`docs/plans/`).
- Ricerca di partenza (report e sei note) in `docs/research/`, spostata da PythonGranularEngine.
- Tappa 0, infrastruttura dei test: runner headless `tests/run_tests.scd` lanciato da `tests/run.sh`, con codici d'uscita 0/1/2, scadenza esterna a sclang, eccezioni ed errori di `setUpClass` contati come fallimenti e avviso per le cartelle senza test.
- Meta-test del runner in shell (`tests/check_runner.sh`), con fixture generate in una cartella temporanea.
- `Makefile` con i target `test`, `test-nrt`, `test-rt`, `test-runner` e `test-all`.
- CI su GitHub Actions (macos-15, SuperCollider 3.14.1 compilato con `setup-supercollider`) che esegue `make test-all`.
- Tappa 1, `GrtScheduler`: onset di uno stream nel tempo logico, con densità e distribuzione alla Truax lette all'onset, RNG proprio dello stream, cursore che rende le finestre contigue identiche a un passaggio unico, densità ≤ 0 come silenzio ricontrollato ogni `silenceStep`, distribuzione fuori da 0..1 o NaN e densità infinita corrette e contate (`clamps`).
