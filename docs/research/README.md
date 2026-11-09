# Ricerca di partenza

La ricerca del 2026-11-09 da cui nascono il piano e gli ADR di questo repo. Era stata fatta per capire se PythonGranularEngine potesse diventare un sistema in tempo reale. La risposta è in DMGiulioRomano/PythonGranularEngine#306.

- [report.md](report.md): la sintesi.
- [note/](note/): le sei note di partenza:
  - fondamenti dell'audio real-time;
  - algoritmi di granulazione dal vivo;
  - Python e i suoi limiti;
  - Csound e SuperCollider in tempo reale;
  - controllo e mapping;
  - analisi del codice di PGE.

Avvertenze:

- Le misure sono state fatte su un Mac Intel i5-7360U con macOS 13, non sull'M2 Max dove si lavora. Gli script usati non sono stati conservati.
- I riferimenti al codice di PGE puntano al commit `1188dbc`.
- Il report prende come architettura di riferimento PGE in Python che invia eventi a scsynth. Questo repo ha scelto invece di vivere tutto in SuperCollider: vedi [ADR 0001](../adr/0001-tutto-in-supercollider.md).
