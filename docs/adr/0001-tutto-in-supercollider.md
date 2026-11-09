# Tutto in SuperCollider, scsynth possiede ADC e DAC

Il granulatore vive interamente in SuperCollider. scsynth apre il dispositivo audio, registra il microfono, analizza e suona i grani; sclang fa da scheduler e da livello di controllo. Non c'è un processo Python che genera i grani, come proposto nell'analisi di PGE.

Il motivo: in Python il GIL e le pause del garbage collector rendono inaffidabile qualunque thread audio (misure in DMGiulioRomano/PythonGranularEngine#306), e un PGE live in Python sarebbe stato una seconda architettura dentro PGE. sclang è nato per lo scheduling con tempo logico e latenza del server, e il TDD resta possibile: test di logica con UnitTest senza server, test delle SynthDef in NRT.

## Considered Options

- PGE in Python che manda un `/s_new` per grano a scsynth: conserva il codice di PGE, ma lega il tempo reale a un repo offline e aggiunge un processo e un protocollo.
- Callback audio compilato in Python (Numba o CFFI): controllo totale del DSP, ma tutto il motore da scrivere e distribuire.

## Consequences

- La semantica di PGE (bande, distribuzione, voci, finestre) va riscritta in sclang e verificata con test propri: non c'è parità automatica con il render offline di PGE.
- Le librerie esterne di feature extraction parlano con il granulatore solo via OSC (vedi ADR 0003).
