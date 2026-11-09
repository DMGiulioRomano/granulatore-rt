# granulatore-rt

Granulatore in tempo reale e interattivo per SuperCollider. Prende il suono dal microfono, lo registra in un buffer circolare e lo granula dal vivo. I parametri dei grani seguono envelope scritti, segnali di controllo dal vivo (controller, feature estratte dal microfono, generatori), oppure una combinazione dei due.

Il modello dei grani viene da [PythonGranularEngine](https://github.com/DMGiulioRomano/PythonGranularEngine) (PGE), che resta un sistema di composizione offline. Il motivo per cui il tempo reale non sta in PGE è spiegato in DMGiulioRomano/PythonGranularEngine#306. Qui non c'è codice Python di PGE: i concetti sono riscritti in SuperCollider.

Stato: progettazione. Il piano è in [docs/plans/](docs/plans/), le decisioni architetturali in [docs/adr/](docs/adr/), il glossario in [CONTEXT.md](CONTEXT.md).

## Architettura in breve

```
 ADC ─► grtRec (BufWr + Phasor) ─► buffer circolare ─┐
    │        testina di scrittura su un bus ─────────┤
    └─► analisi (UGen native, FluCoMa) ─► bus delle feature
                                                     │
 OSC esterno (aubio, essentia, ...) ─► bus delle feature
                                                     ▼
 sclang: una Routine per stream ─ all'onset legge i parametri ─► s.bind ─► grtGrain ─► DAC
          (envelope, controllo dal vivo, o tutti e due)           (uno per grano, OffsetOut)
```

- **scsynth** possiede ADC e DAC, il buffer circolare, l'analisi e i synth dei grani.
- **sclang** fa da scheduler: decide i grani con un anticipo pari alla latenza del server e li fa partire al campione con i timetag.

## Struttura

Il repo segue la convenzione dei quark di SuperCollider:

| cartella | contenuto |
|---|---|
| `granulatore-rt.quark` | metadati del quark |
| `Classes/Core/` | scheduler dei grani, sorgenti di parametro, bande (tendency mask), seed |
| `Classes/Live/` | buffer circolare e vincoli del ritardo di lettura |
| `Classes/Features/` | adapter delle feature: UGen native, FluCoMa, OSC esterno |
| `Classes/Mapping/` | normalizzazione, curve, smoothing, combinazione con gli envelope |
| `Classes/Synths/` | SynthDef definite in classi, caricabili anche dai test |
| `HelpSource/Classes/` | help `.schelp` delle classi |
| `tests/unit/` | test della logica, senza server |
| `tests/nrt/` | test delle SynthDef con render non real-time, verificati sul file |
| `tests/rt/` | smoke test con server acceso e microfono (manuali) |
| `examples/` | patch suonabili |
| `bridges/python/` | esempio di feature extractor esterno che parla OSC |
| `docs/` | piani (`plans/`) e decisioni (`adr/`) |

Prefisso delle classi: `Grt` (per esempio `GrtScheduler`, `GrtLiveBuffer`).

## Requisiti

- SuperCollider 3.14.1 o successivo.
- Opzionale: FluCoMa 1.0.10 per SuperCollider, per le feature avanzate. Senza FluCoMa restano disponibili le feature basate sulle UGen native.

## Test

Il progetto si sviluppa in TDD. I comandi (`make test`, `make test-nrt`, `make test-rt`) arrivano con la prima tappa del piano.

## Licenza

Unlicense, come PGE: vedi [LICENSE](LICENSE).
