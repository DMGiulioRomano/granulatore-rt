# Piano: granulatore in tempo reale e interattivo

Data: 2026-11-09. Stato: proposto, da approvare tappa per tappa. Ogni tappa parte dalla proposta delle sue suite di test; il codice arriva solo dopo la conferma.

## Obiettivo

Un granulatore in SuperCollider che:

- registra il microfono in un buffer circolare;
- genera grani letti a un ritardo dalla testina di scrittura;
- prende ogni parametro da un envelope, da un segnale di controllo dal vivo o da entrambi;
- riceve feature da UGen native, da FluCoMa e da librerie esterne via OSC.

## Cosa si prende da PGE

Si portano i concetti, non il codice. Riferimenti nel repo PythonGranularEngine:

| concetto | in PGE | qui |
|---|---|---|
| grano immutabile, parametri all'onset | `src/pge/core/grain.py:17-24` | evento della Routine, poi un Synth `grtGrain` |
| densità o `fill_factor` (densità = fill_factor / durata) | `docs/reference/yaml.md`, sezione Densità | `GrtScheduler` |
| distribuzione 0..1, sincrono/asincrono alla Truax | stessa sezione | `GrtScheduler` |
| bande `_range` (tendency mask) e `deviation_probability` | sezioni StreamConfig e DeviationProbability | `GrtBand` |
| pointer (start, speed, jitter, loop) | `src/pge/controllers/pointer_controller.py` | sostituito dal ritardo di lettura in `GrtLiveBuffer` |
| catalogo delle finestre come tabelle | `src/pge/controllers/window_registry.py:342-405` | `GrtWindows`: hanning, hamming, bartlett, blackman, blackman_harris, gaussian, kaiser, rectangle, sinc, half_sine, expodec, rexpodec |
| SynthDef del grano: lettura lineare, finestra come tabella, pan mid/side | `supercollider/pge_grain.scd` | `grtGrain`, con `OffsetOut` e lettura dal buffer live |
| voci con `num_voices` frazionario e strategy di offset | sezione Blocco Voices | tappa successiva alla prima versione |
| seed per componente | `src/pge/shared/seeding.py` | `thisThread.randSeed` per stream; `RandID`/`RandSeed` lato server |

Non si portano parser YAML, score writer, renderer offline, cache, visualizer ed export.

## Tappe

Ogni tappa è una fetta verticale con i propri test rossi.

### Tappa 0: infrastruttura dei test

Il runner headless e i comandi.

- `tests/run_tests.scd`: compila con `--include-path` le cartelle `Classes` e `tests`, esegue i `UnitTest` della cartella richiesta, stampa il riepilogo ed esce con codice 0 o 1.
- `Makefile` con i target `test` (unit), `test-nrt`, `test-rt`, `test-all`.
- CI su GitHub Actions con `supriya-project/setup-supercollider` (macOS).

Suite proposta, `TestGrtRunner`:

- un test volutamente rosso fa uscire `sclang` con codice 1;
- tolto quel test, l'uscita è 0;
- un errore di compilazione delle classi fa uscire con codice diverso da 0, non si blocca.

### Tappa 1: lo scheduler degli onset (unit, senza server)

`GrtScheduler` produce gli onset di uno stream in una finestra di tempo logico `[t0, t1)`, con densità, distribuzione e seed.

Suite proposta, `TestGrtScheduler`:

- con distribuzione 0 gli intervalli fra onset valgono esattamente 1/densità;
- con distribuzione 1, su 10.000 onset, la media degli intervalli è 1/densità entro l'1%;
- **continuità:** generare `[0, 1)` e poi `[1, 2)` dà gli stessi onset di `[0, 2)`. È la lezione di PGE: l'iteratore deve coincidere con il passaggio unico;
- **determinismo:** stesso seed, stessi onset; seed diverso, onset diversi;
- una densità che cambia fra due onset vale dall'onset successivo, mai a metà intervallo;
- densità 0 non produce onset e non va in ciclo infinito.

### Tappa 2: sorgenti di parametro e bande (unit)

`GrtParam` risponde al valore di un parametro al tempo t. Le sorgenti possibili sono costante, `Env`, segnale di controllo (letto tramite un lettore iniettato, così il test non ha bisogno del server) e combinazione base + modulazione.

Suite proposta, `TestGrtParam` e `TestGrtBand`:

- una costante risponde sempre lo stesso valore;
- un `Env` risponde come `Env.at(t)` nel tempo dello stream;
- un segnale di controllo risponde l'ultimo valore scritto dal lettore finto;
- la combinazione `base + depth * (controllo - 0.5)` resta nei limiti del parametro (clamp dichiarato, non silenzioso);
- la banda estrae valori dentro `[centro - ampiezza/2, centro + ampiezza/2]`, e la loro distribuzione è uniforme (test chi-quadro con seed fisso);
- con `deviation_probability` 0 la banda non si applica mai, con 1 sempre.

### Tappa 3: vincoli del ritardo di lettura (unit)

`GrtLiveBuffer` conosce la lunghezza del buffer N e calcola l'intervallo valido del ritardo di lettura per una data durata L, rapporto r e margine m.

Suite proposta, `TestGrtLiveBuffer`:

- r = 1: ritardo valido in `[m, N - L]`;
- r = 2, L = 0,1 s: il ritardo minimo è `0,1 s + m`, perché il grano supererebbe la testina;
- r = 0,5: il ritardo massimo è `N - 0,5 * L`;
- r negativo (lettura al contrario): i vincoli si scambiano come per r < 1 sul verso opposto;
- un ritardo richiesto fuori intervallo viene riportato al bordo, e il clamp si conta (contatore ispezionabile), non passa in silenzio;
- se l'intervallo è vuoto (grano più lungo del buffer) il grano viene scartato e contato.

### Tappa 4: le SynthDef `grtRec` e `grtGrain` (nrt)

Suite proposta, `TestGrtGrainNrt`. Sono render NRT con un segnale di test noto (una rampa lineare scritta nel buffer, così il valore letto rivela la posizione):

- il grano parte esattamente al campione dell'onset (`OffsetOut`): un impulso a t = 0,1234567 s cade sul campione atteso;
- con ritardo D il grano legge il campione `testina - D * sr` (tolleranza: errore d'interpolazione lineare);
- con rapporto 2 la rampa letta ha pendenza doppia;
- la finestra resa coincide con la tabella del catalogo entro 1e-4;
- il nodo si libera da solo alla fine del grano (nessun nodo residuo nel server).

### Tappa 5: catalogo delle finestre (unit + nrt)

Suite proposta, `TestGrtWindows`:

- ogni finestra del catalogo è una tabella di N punti che coincide con la formula di PGE entro 1e-6 (hanning, gaussian ed expodec per primi);
- ogni finestra vale 0 agli estremi, salvo rectangle ed expodec, che lo dichiarano;
- un nome sconosciuto dà errore con l'elenco dei nomi validi.

### Tappa 6: adapter delle feature (nrt + unit)

`GrtFeatures` è un registro di bus con nome. Si parte con due adapter.

- **Nativo:** `Amplitude` in dB per la forza, `SpecCentroid` in Hz per il colore, `Onsets` per gli attacchi al secondo, normalizzati 0..1 con intervalli dichiarati.
- **OSC:** riceve `/grt/feature/<nome> <valore>` e lo scrive sul bus.
- **FluCoMa:** terzo adapter (`FluidLoudness`, `FluidSpectralShape`, `FluidPitch`), saltato se non installato.

Suite proposta, `TestGrtFeatures`:

- NRT: un seno a -6 dBFS dà forza più alta di uno a -40 dBFS, e i valori normalizzati stanno in 0..1;
- NRT: un seno a 440 Hz dà colore più basso di un rumore bianco;
- unit: un messaggio OSC inviato a `NetAddr.localAddr` aggiorna il valore della feature con quel nome;
- unit: due adapter non possono scrivere la stessa feature (errore alla registrazione).

### Tappa 7: mapping (unit)

`GrtMap` porta 0..1 nell'intervallo del parametro con curva lineare o esponenziale e smoothing a un polo.

Suite proposta, `TestGrtMap`:

- lin e exp agli estremi danno esattamente min e max;
- exp richiede min e max dello stesso segno, altrimenti dà errore;
- lo smoothing con tempo τ raggiunge il 63% di un gradino dopo τ (tempo logico);
- un'inversione (`invert: true`) scambia gli estremi.

### Tappa 8: integrazione e primo suono (rt)

`GrtStream` unisce scheduler, parametri, buffer live e synth. Ci sono una patch in `examples/01-microfono-forza-densita.scd` e uno smoke test in `tests/rt/`:

- il server parte con l'input del microfono;
- per 10 secondi lo stream gira senza messaggi `late`;
- alla fine non restano nodi.

La verifica d'ascolto spetta all'utente.

### Tappa 9: bridge Python di esempio

`bridges/python/`: un processo che ascolta il microfono (sounddevice), calcola RMS e centroide con numpy e manda `/grt/feature/...` via python-osc. Ha test pytest sul calcolo delle feature e sul formato dei messaggi. Dimostra come agganciare qualunque libreria esterna (aubio, essentia).

## Prima misura da fare sull'M2 Max

Prima della tappa 8: quanti grani al secondo regge uno stream con un Synth per grano prima dei `late`, per durate di 20, 50 e 100 ms. Decide se e quando serve il motore server-side per gli stream densi (ADR 0002).

## Prerequisiti sull'M2 Max

- SuperCollider 3.14.1 o successivo.
- FluCoMa 1.0.10 per SuperCollider (opzionale), copiato nella cartella Extensions.
- Permesso microfono concesso a SuperCollider e al terminale da cui gira `sclang`.

## Fuori dalla prima versione

Voci multiple, registrazione e replay dei controlli con esportazione a envelope, hot reload della configurazione, motore server-side per gli stream densi, configurazione da file.
