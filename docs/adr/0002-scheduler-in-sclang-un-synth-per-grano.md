# Scheduler in sclang, un Synth per grano

Nella prima versione ogni grano è un Synth creato da una Routine di sclang con `s.bind`, alla latenza del server, e la SynthDef usa `OffsetOut`. Ogni grano legge i parametri al proprio onset, uno per uno, come in PGE. Questa scelta tiene la semantica per grano (bande, voci, seed) nel linguaggio, dove si testa senza server.

## Considered Options

- Grani generati dentro scsynth (trigger `Impulse`/`Dust`, Demand UGens, `GrainBuf`): densità molto più alte, ma semantica per grano più povera e test possibili solo in NRT. Resta la via per gli stream densi, da aggiungere dietro la stessa interfaccia di stream quando le misure lo richiederanno.

## Consequences

- La densità massima sostenibile (grani/s prima dei messaggi `late`) è un limite da misurare sull'M2 Max: è il primo numero del piano.
- La reazione ai controlli dal vivo arriva in ritardo di una latenza del server (tipicamente 50-200 ms). L'audio d'ingresso no: il ritardo di lettura si risolve dentro il synth al momento dell'onset.
