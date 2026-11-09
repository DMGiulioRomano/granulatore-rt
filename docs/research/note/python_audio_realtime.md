# Python per l'audio real-time full-duplex (microfono in, audio out): ecosistema e limiti, stato al 9 novembre 2026

Convenzioni. Le date di release, le versioni Python supportate e i tag delle wheel sono stati letti dall'API JSON di PyPI e dall'API GitHub il 2026-11-09. Con "misura locale" si intende un benchmark eseguito in questa sessione su una macchina della stessa classe del target: Intel Core i5-7360U a 2,3 GHz, macOS 13.7.8, Python 3.9.6 di Apple (`/usr/bin/python3`), NumPy 2.0.2, sounddevice 0.5.6 con PortAudio V19.7.0-devel incluso nella wheel, dispositivo "Built-in Output", 48 kHz, stereo float32, `latency='low'`, 3 s per configurazione e uscita silenziosa. Si è misurato solo l'output: il microfono non è stato aperto, per non far scattare il permesso TCC di macOS. Gli script sono due:

- [L1] = `scripts/rt_bench.py` (script locale, non conservato)
- [L2] = `scripts/numba_cfunc_pa.py` (script locale, non conservato)

Le misure sono osservazioni singole su una macchina, non dati pubblicati.

## 1. DSP dentro un callback audio Python: GIL, GC, allocazioni, overhead NumPy, block size minimi, buone pratiche

### Takeaway
Su questo Mac Intel un callback sounddevice scritto in NumPy regge 64 frame a 48 kHz (1,33 ms) con 32 grani, senza underrun e con il 4-6% del budget per blocco. Il calcolo quindi non è il collo di bottiglia. Il limite vero è il GIL. Basta un solo thread Python CPU-bound nello stesso processo per ritardare l'ingresso nel callback di circa 5 ms, cioè lo switch interval di default, e far scendere lo stream al 35% del tempo reale a 64 e a 128 frame. Una raccolta GC di generazione 2 su un heap grande vale lo stesso discorso (58 ms misurati). In nessuno di questi casi PortAudio su CoreAudio ha alzato il flag di underflow.

### Cited Findings
- Budget per blocco a 48 kHz: 64 frame = 1333 µs, 128 = 2667 µs, 256 = 5333 µs, 512 = 10667 µs — [misura locale L1][L1]
- Costo di una chiamata NumPy su array float32 di 64-512 campioni:
  - `np.multiply(a, b, out=o)`: 0,61-1,03 µs;
  - `a*b` con allocazione: 0,56-0,86 µs;
  - copia `o[:] = a`: 0,32-0,56 µs;
  - creazione di una slice-view: 0,17-0,18 µs;
  - `a.sum()`: 1,85-2,50 µs;
  - chiamata a una funzione Python vuota: 84 ns.

  A queste dimensioni il costo è quasi tutto overhead per chiamata e non dipende quasi da N. Inoltre `out=` non risulta più veloce dell'allocazione — [misura locale L1][L1]
- Un loop per campione scritto in Python costa 21,1 µs per 64 campioni su ndarray (circa 0,33 µs a campione) e 3,7 µs con una list comprehension su liste; per 256 campioni costa 82,6 µs e 15,5 µs — [misura locale L1][L1]
- Blocco granulare con un loop Python sui grani (2 ufunc e 2 slice per grano): circa 1,5 µs per grano per blocco, quasi indipendente da N. I tempi misurati sono G=8 13-15 µs, G=32 49-63 µs, G=128 192-237 µs, cioè il 14% del budget a 64 frame e il 2% a 512. La versione vettorizzata (`np.take` su matrici di indici G×N, poi `multiply` e `sum(axis=0, out=)`) costa 99 µs per G=128 a N=64 (7%) ma 768 µs a N=512 (7%): conviene solo con blocchi piccoli e molti grani — [misura locale L1][L1]
- Stream live con callback vuoto: nessun underflow a 64, 128, 256 e 512 frame.
  - Latenza d'uscita riportata da `stream.latency`: 4,8 ms (sia a 64 sia a 128 frame), 7,4 ms a 256, 12,8 ms a 512.
  - Latenza di default del dispositivo: 3,7 ms (low) e 13,9 ms (high).
  - `cpu_load` di PortAudio: 0,025 / 0,016 / 0,012 / 0,012, cioè circa 33 / 43 / 64 / 128 µs di costo fisso per callback del percorso Python (trampolino CFFI, wrapper sounddevice, funzione utente).

  — [misura locale L1][L1]
- Con 32 grani nel callback il corpo Python (mediana/p99/max) dura 61/104/122 µs a 64 frame, 75/97/141 µs a 128, 89/126/140 µs a 256 e 243/343/349 µs a 512. Zero underflow; `cpu_load` 0,063 / 0,043 / 0,025 / 0,030 — [misura locale L1][L1]
- Ritardo d'ingresso nel callback, misurato da `timeInfo.currentTime` di PortAudio al ritorno di una `Pa_GetStreamTime()` sulla prima riga del callback. Comprende il trampolino CFFI, l'acquisizione del GIL e la costruzione degli array NumPy fatta da sounddevice. A carico nullo vale: mediana 30-98 µs, p99 72-401 µs, massimo fino a 459 µs — [misura locale L1][L1]
- Jitter dell'intervallo fra callback: la media coincide sempre con il valore nominale, ma a 64 frame la deviazione standard è 1,43 ms con massimo 2,96 ms. I callback arrivano a coppie, e con blocksize 64 la latenza riportata è la stessa che a 128. A 128 frame sd 0,79 ms; a 512 frame sd 3,1 ms con massimo 11,8 ms — [misura locale L1][L1]
- Contesa sul GIL. È stato aggiunto un thread Python CPU-bound nello stesso processo, con lo switch interval di default (0,005 s secondo `sys.getswitchinterval()`).
  - Il ritardo d'ingresso sale a una mediana di circa 5,1 ms, con massimi fra 7 e 15 ms.
  - A 128 frame lo stream esegue 397 callback in 3 s invece di circa 1125 (35% del tempo reale); l'intervallo medio è 7,64 ms contro 2,67 nominali.
  - A 64 frame: 784 callback invece di circa 2250 (35%). A 256 frame: 533 invece di circa 563 (95%).
  - `cpu_load` 1,78 / 1,81 / 0,91.
  - `status.output_underflow` è rimasto sempre a 0.

  — [misura locale L1][L1]
- Con `sys.setswitchinterval(0.0005)` lo stesso scenario a 128 frame resta in tempo reale (1127 callback). Ritardo d'ingresso: mediana 622 µs, massimo circa 1,0 ms. Corpo del callback: p99 356 µs, massimo 710 µs. `cpu_load` 0,231 — [misura locale L1][L1]
- `sys.setswitchinterval` imposta la durata ideale dei timeslice fra thread Python; "the actual value can be higher, especially if long-running internal functions or methods are used", e la scelta del thread che parte spetta al sistema operativo ("The interpreter doesn't have its own scheduler") — [docs Python: sys](https://docs.python.org/3.15/library/sys.html#sys.setswitchinterval)
- NumPy rilascia il GIL nei loop interni solo sopra i 500 elementi: `NPY_BEGIN_THREADS_THRESHOLDED(loop_size)` esegue `PyEval_SaveThread()` se `loop_size > 500` — [numpy ndarraytypes.h](https://github.com/numpy/numpy/blob/main/numpy/_core/include/numpy/ndarraytypes.h). Nelle prove con contesa il corpo del callback ha toccato massimi di 5,1-5,2 ms. A 256 frame anche il p99 è salito a 5149 µs: lì `outdata.fill(0)` lavora su 256×2 = 512 elementi — [misura locale L1][L1]
- CFFI, nel build con GIL, rilascia il GIL prima di chiamare la libreria C ("On the GIL-enabled build, CFFI will release the GIL before calling into a C library") — [CFFI overview, Thread Safety](https://cffi.readthedocs.io/en/latest/overview.html#thread-safety). Altrove la stessa documentazione ripete: "C function calls are done with the GIL released" — [CFFI reference](https://cffi.readthedocs.io/en/latest/ref.html)
- Garbage collector (Python 3.9, soglie (700, 10, 10)):
  - `gc.collect(2)` costa 2,9 ms con un heap piccolo e 58 ms con circa 600.000 oggetti tracciati; gen0 e gen1 costano circa 0 ms;
  - durante 300.000 allocazioni persistenti di dict le raccolte automatiche sono state 392 di gen0 (massimo 0,01 ms), 35 di gen1 (massimo 0,06 ms) e nessuna di gen2;
  - dopo `gc.freeze()` (918.302 oggetti congelati) `gc.collect(2)` costa circa 0 ms, e si è vista una sola gen2 automatica da 0,79 ms.

  — [misura locale L1][L1]
- `gc.freeze()` "Freeze all the objects tracked by the garbage collector; move them to a permanent generation and ignore them in all the future collections"; `set_threshold(0)` disabilita la raccolta — [docs Python: gc](https://docs.python.org/3.15/library/gc.html#gc.freeze)
- Indicazioni ufficiali di sounddevice in caso di under- o overflow: aumentare `latency` e/o `blocksize`, ed evitare nel callback tutto ciò che può bloccarlo a lungo, "e.g. extensive computations, waiting for another thread, reading/writing files, network connections, etc." — [sounddevice, CallbackFlags](https://python-sounddevice.readthedocs.io/en/latest/api/misc.html#sounddevice.CallbackFlags)
- Sui parametri dello stream, la documentazione dice:
  - `blocksize=0` (il default) chiede a PortAudio un numero di frame "optimal (and possibly varying)";
  - con alcune host API un blocksize diverso da zero "may introduce an additional layer of buffering which could introduce additional latency";
  - `latency='high'` è il default ed è il più robusto;
  - "by default Audacity specifies a desired latency of 0.1 seconds".

  — [sounddevice, Stream](https://python-sounddevice.readthedocs.io/en/latest/api/streams.html#sounddevice.Stream)
- `Stream.cpu_load` vale 1.0 quando il callback consuma tutto il tempo CPU disponibile per restare in tempo reale, e "The value may exceed 1.0" — [sounddevice.py](https://github.com/spatialaudio/python-sounddevice/blob/master/src/sounddevice.py)
- Matthias Geier, maintainer di sounddevice, contro gli artefatti sotto carico Python propone "a callback function implemented in C (or another compiled language) and make sure that the callback function never takes the GIL", rimandando a rtmixer. Su Cython aggiunge: "A similar thing might even work with Cython, but I've not tried this" — [sounddevice issue #309](https://github.com/spatialaudio/python-sounddevice/issues/309)
- Un utente che integrava sounddevice in un'applicazione grafica: "callback mode is not possible here due to the GIL" — [sounddevice issue #384](https://github.com/spatialaudio/python-sounddevice/issues/384)
- pastream: "libraries like Pillow which may greedily grab and hold the GIL subsequently cause audio overruns/underruns" (informazione del 2017-2024) — [pastream su PyPI](https://pypi.org/project/pastream/)
- `time.perf_counter` su macOS usa `mach_absolute_time()` con risoluzione di 1 ns — [misura locale L1][L1]

### Inferences
- Per PGE il calcolo NumPy di qualche decina o centinaio di grani per blocco sta nel budget anche a 64-128 frame su questo Intel del 2017. Il rischio sono le pause non deterministiche:
  - il GIL tenuto da un altro thread Python;
  - una gen2 del GC su un heap grande;
  - NumPy che rilascia il GIL sopra i 500 elementi e apre una finestra in cui un altro thread può tenerlo fino a 5 ms.
- In un processo che fa anche altro (GUI, OSC, scheduling, parsing YAML), una soglia ragionevole per un callback NumPy è 256-512 frame con 10-20 ms di latenza. 64-128 frame sono realistici solo in un processo audio dedicato, senza altri thread CPU-bound, con `gc.freeze()` e `gc.disable()` (o soglie alte) e lo switch interval ridotto.
- Su CoreAudio non conviene fidarsi di `status.output_underflow`. In queste prove il segnale affidabile è stato `cpu_load > 1`, insieme al conteggio dei callback rispetto al tempo trascorso.
- Su Built-in Output 64 frame non danno meno latenza di 128 (4,8 ms in entrambi i casi): si paga solo il doppio del costo fisso per callback. Probabilmente l'HAL usa buffer di almeno 128 frame e PortAudio li adatta; non è verificato.
- Buone pratiche che ne seguono:
  - preallocare tutto (buffer di mix, temporanei, indici, inviluppi tabulati);
  - usare ufunc con `out=`;
  - non creare oggetti Python per grano nel percorso caldo: meglio una struttura "struct of arrays" in array NumPy che istanze per grano. L'attuale `Grain` dataclass frozen di PGE, per costruzione, crea un oggetto tracciato dal GC per grano;
  - non usare lock né code bloccanti nel callback;
  - chiamare `gc.freeze()` dopo il setup e `gc.collect()` esplicitamente nei momenti sicuri;
  - abbassare lo switch interval;
  - strumentare con `time.perf_counter()` e `stream.cpu_load`.
- Il callback di sounddevice è una funzione Python, quindi deve riacquisire il GIL a ogni blocco anche se le chiamate PortAudio via CFFI lo rilasciano. Questo spiega il comportamento misurato sotto contesa.

### Gaps
- Il full-duplex (microfono, callback, uscita) non è stato misurato: manca il costo aggiuntivo del buffer d'ingresso e dell'apertura dello stream di input su CoreAudio.
- Non ho trovato fonti pubblicate con misure sistematiche di block size minimi o di jitter per callback NumPy. L'articolo MDPI "Programming Real-Time Sound in Python" (Applied Sciences 2020, https://www.mdpi.com/2076-3417/10/12/4214) ha restituito HTTP 403 e non è stato letto.
- Le misure sono su Python 3.9.6. Su 3.11 e successivi il codice Python puro è più veloce, ma non ho verificato che costo per chiamata NumPy e dinamica GIL/switch interval restino invariati.
- Non ho misurato le soglie e i tempi del GC in 3.14.5+ e 3.15, che sono tornati al GC generazionale di 3.13 (vedi sezione 5).

## 2. Librerie di I/O audio: manutenzione, ultima release, versioni Python, macOS (Intel)

### Takeaway
python-sounddevice resta il riferimento. È mantenuto (0.5.6 del 17 agosto 2026), ha una sola wheel `universal2` con PortAudio incluso che si installa su qualsiasi Python ≥3.7 e anche su macOS 13 Intel, e offre un `Stream` full-duplex con callback. Il callback però è Python e prende il GIL. Le uniche soluzioni pronte con callback in C sono rtmixer e pastream, entrambe ferme da metà 2024. PyAudio è fermo al 2023 e su Mac va compilato contro PortAudio, mentre Homebrew non produce più bottle Intel. pedalboard `AudioStream` fa streaming live in C++, ma non esegue DSP Python nel thread audio.

### Cited Findings
Matrice per macOS 13 Intel (x86_64), ultima release su PyPI al 2026-11-09:

| Pacchetto | Ultima release | requires_python | Wheel macOS x86_64 nell'ultima release | Fonte |
|---|---|---|---|---|
| sounddevice | 0.5.6 (2026-08-17) | >=3.7 | sì: `py3-none-macosx_10_6_universal2` | [PyPI](https://pypi.org/project/sounddevice/) |
| PyAudio | 0.2.14 (2023-11-07) | n/d | no: nessuna wheel macOS, solo sdist (wheel binarie cp38-cp313 per altre piattaforme) | [PyPI](https://pypi.org/project/PyAudio/) |
| rtmixer | 0.1.7 (2024-07-02) | >=3.6 | sì: `cp38-abi3-macosx_10_9_x86_64`, più wheel PyPy | [PyPI](https://pypi.org/project/rtmixer/) |
| JACK-Client | 0.5.7 (2026-09-23) | >=3.7 | Python puro (`py3`), richiede JACK installato | [PyPI](https://pypi.org/project/JACK-Client/) |
| miniaudio (pyminiaudio) | 1.72 (2026-11-07) | >=3.11 | sì: macosx_10_9/10_13/10_15_x86_64; anche cp314t | [PyPI](https://pypi.org/project/miniaudio/) |
| pedalboard | 0.9.26 (2026-10-07) | >=3.10 | sì: macosx_10_14_x86_64 e universal2; cp310-cp315 più cp314t/cp315t | [PyPI](https://pypi.org/project/pedalboard/) |
| SoundCard | 0.4.6 (2026-04-12) | >=3.5 | Python puro (`py3`) | [PyPI](https://pypi.org/project/SoundCard/) |
| pastream | 0.2.0.post2 (2024-04-14) | n/d | (non verificato) | [PyPI](https://pypi.org/project/pastream/) |
| pa-ringbuffer | 0.1.4 (2020-08-27) | n/d | (non verificato) | [PyPI](https://pypi.org/project/pa-ringbuffer/) |
| numpy | 2.5.4 (2026-10-10) | >=3.12 | sì: macosx_10_13 e 10_15_x86_64, più varianti macosx_14_0_x86_64; anche cp314t/cp315t | [PyPI](https://pypi.org/project/numpy/) |
| soundfile | 0.14.0 (2026-06-06) | >=3.10 | sì: `py2.py3-none-macosx_10_9_x86_64` | [PyPI](https://pypi.org/project/soundfile/) |
| cffi | 2.1.1 (2026-08-03) | >=3.10 | sì: macosx_10_15_x86_64; anche cp314t/cp315t | [PyPI](https://pypi.org/project/cffi/) |

- Attività GitHub di sounddevice: ultimo commit il 2026-10-26, 206 issue aperte, 1282 stelle. Le release 0.5.3-0.5.6 sono di manutenzione: `explicit_sample_format` per WASAPI, wheel Windows ARM64, fix di rilevamento dell'architettura — [GitHub releases sounddevice](https://github.com/spatialaudio/python-sounddevice/releases)
- PortAudio incluso nella wheel sounddevice 0.5.6 su macOS: "PortAudio V19.7.0-devel, revision unknown" — [misura locale L1][L1]
- rtmixer: il README dice "This is work in progress!". Il callback "is implemented in C (and compiled with the help of CFFI) and doesn't invoke the Python interpreter, therefore avoiding waiting for things like garbage collection and the GIL"; "Runs on any Python version where CFFI is available" — [README rtmixer](https://github.com/spatialaudio/python-rtmixer). Il repo ha solo bump automatici (ultimo commit il 2026-11-02, cibuildwheel), 5 issue aperte e 78 stelle — [GitHub rtmixer](https://github.com/spatialaudio/python-rtmixer)
- API di rtmixer: classi `Mixer`, `Recorder` e `MixerAndRecorder`; metodi `play_buffer`, `play_ringbuffer`, `record_buffer`, `record_ringbuffer`, `cancel(action, time)`, `stats` e `fetch_and_reset_stats`. Sono azioni programmate che Python accoda al callback C. Non c'è DSP arbitrario — [rtmixer.py](https://github.com/spatialaudio/python-rtmixer/blob/master/src/rtmixer.py)
- pastream si descrive come "GIL-less Portaudio Streams for Python": il callback è in C e serve gli interrupt audio senza mai prendere il GIL — [pastream su PyPI](https://pypi.org/project/pastream/)
- JACK-Client: ultimo commit il 2026-11-02, 15 issue aperte; release 0.5.6 e 0.5.7 a settembre 2026 dopo due anni di pausa (0.5.5 del 2024-11-01) — [GitHub jackclient-python](https://github.com/spatialaudio/jackclient-python); [PyPI](https://pypi.org/project/JACK-Client/)
- pyminiaudio: ultimo commit il 2026-11-07 ("removed stale python 3.10 builds"), 2 issue aperte. Il README cita i "generators for the Audio playback and recording" — playback e registrazione si alimentano con generatori Python, come in `device.start(stream)` — [GitHub pyminiaudio](https://github.com/irmen/pyminiaudio)
- pedalboard `AudioStream`:
  - "Introduced in v0.7.0 for macOS and Windows. Linux support introduced in v0.9.14"; i metodi `read()` e `write()` esistono da v0.9.12;
  - parametri: `input_device_name`, `output_device_name`, `plugins`, `sample_rate`, `buffer_size`, `allow_feedback=False`, `num_input_channels=1`, `num_output_channels=2`;
  - un thread di background si sveglia ogni 5 ms e propaga al thread audio le modifiche alla catena di plugin "without taking the GIL, which would be expensive" (con `try_lock`);
  - `run()`, `read()` e `write()` rilasciano il GIL (`py::gil_scoped_release`) e scambiano audio tramite FIFO;
  - se Python non legge in tempo, `read()` segnala "frames of audio data were dropped since the last call to read() ... call read() as quickly as possible or increase your buffer size".

  — [AudioStream.h](https://github.com/spotify/pedalboard/blob/master/pedalboard/io/AudioStream.h)
- pedalboard 0.9.25 (2026-09-09) ha aggiunto il supporto a Python 3.15. L'ultimo commit è del 2026-11-03; 148 issue aperte — [GitHub releases pedalboard](https://github.com/spotify/pedalboard/releases)
- PyAudio: l'ultima release è la 0.2.14 del 2023-11-07; su macOS non ci sono wheel su PyPI — [PyPI PyAudio](https://pypi.org/project/PyAudio/)
- Homebrew su Intel:
  - "Homebrew has stopped building new bottles for Intel systems, and will remove the ability to run Homebrew on Intel systems in or after September 2027";
  - a settembre 2026 Intel x86_64 è "Tier 3: macOS Big Sur 11 through Tahoe 26";
  - "macOS Tahoe 26 is the final version of macOS to run on Intel hardware";
  - dove mancano i bottle, `brew doctor` suggerisce MacPorts per Intel con Sequoia 15 o precedente.

  — [Homebrew Support Tiers](https://docs.brew.sh/Support-Tiers)
- SoundCard (bastibe): ultimo commit il 2026-06-17, 51 issue aperte — [GitHub SoundCard](https://github.com/bastibe/SoundCard)
- Pavimento Python 3.9 dell'engine:
  - numpy 2.5.x richiede Python ≥3.12 (la serie 2.4.x pubblica wheel da cp311) — [PyPI numpy](https://pypi.org/project/numpy/);
  - su Python 3.9 pip ha risolto numpy 2.0.2 — [misura locale L1][L1];
  - soundfile 0.14.0 e cffi 2.x richiedono ≥3.10 — [PyPI soundfile](https://pypi.org/project/soundfile/); [PyPI cffi](https://pypi.org/project/cffi/);
  - Python 3.9 è a fine vita dal 2025-10-31 e Python 3.10 dal 2026-10-01 — [endoflife.date Python](https://endoflife.date/python);
  - il Python di sistema di macOS 13.7.8 è il 3.9.6 — [misura locale L1][L1].

### Inferences
- Sul Mac target la combinazione che si installa senza compilare nulla e copre il full-duplex è sounddevice più numpy più soundfile. Con PortAudio incluso nella wheel non serve Homebrew. Il resto dello stack moderno richiede Python ≥3.10-3.12. Il pavimento 3.9 di PGE è già incompatibile con le ultime versioni di numpy, soundfile, cffi, pyo, numba, miniaudio, pedalboard e DawDreamer.
- Fra le varianti x86_64 di numpy, le `macosx_14_0_x86_64` non si installano su macOS 13; pip sceglie le `macosx_10_13/10_15`. Il ragionamento si basa sui tag delle wheel, non è verificato con numpy 2.5.
- rtmixer è il modello giusto (callback C con ring buffer, Python che accoda comandi), ma non fa granulazione: per PGE vale come esempio di architettura o punto di partenza da estendere, non come motore.
- pedalboard va bene per catene di effetti (incluse le VST3/AU) su ingresso live con controllo dei parametri da Python, non per un granulatore custom scritto in Python: `read()`/`write()` aggiungono il buffering di una FIFO e lasciano Python fuori dal thread audio.
- PyAudio su Intel macOS 13 richiede PortAudio compilato a mano o via MacPorts, perché Homebrew non costruisce più bottle Intel: è sconsigliabile.

### Gaps
- Non ho verificato se `pastream` e `pa-ringbuffer` hanno wheel macOS x86_64 per l'ultima release, né l'attività del repo tgarc/pastream.
- Non ho trovato misure pubblicate di latenza o jitter per PyAudio, miniaudio o JACK-Client su macOS.
- Non ho verificato se JACK2 per macOS è installabile e mantenuto su Intel Ventura nel 2026.

## 3. Motori DSP controllati da Python: pyo, SignalFlow, supriya, ctcsound, DawDreamer, libpd

### Takeaway
pyo e SignalFlow sono vivi, entrambi con nuove release a ottobre 2026, ed entrambi hanno granulatori in C/C++ e oggetti per registrare l'ingresso live in un buffer. Sul Mac target però:

- SignalFlow 0.6.0 ha wheel `macosx_12_0_x86_64` per cp39-cp315, quindi si installa e rispetta perfino il pavimento 3.9 di PGE;
- pyo 1.1.0 pubblica wheel macOS solo arm64 e richiede Python ≥3.11, quindi su Intel va compilato dal sorgente.

Il thread audio di pyo prende il GIL a ogni buffer; quello di SignalFlow no, salvo per i nodi scritti in Python. DawDreamer è un renderer offline, e i binding Python di libpd hanno wheel solo per Linux.

### Cited Findings
- pyo:
  - release 1.1.0 del 2026-10-09, la prima dopo la 1.0.5 del 2023-03-26;
  - `requires_python` "<4,>=3.11", wheel cp311-cp315;
  - wheel macOS solo `macosx_13_0_arm64`, nessuna x86_64; c'è lo sdist;
  - la 1.0.5 aveva wheel `macosx_12_0_x86_64` per cp37-cp311.

  — [PyPI pyo](https://pypi.org/project/pyo/)
- La documentazione di pyo dice "The current version supports Python 3.11 to 3.14" — [download.rst](https://github.com/belangeo/pyo/blob/HEAD/documentation/source/download.rst). Su macOS indica la compilazione con Homebrew: `brew install liblo libsndfile portaudio portmidi`, poi `python3 -m build --config-setting="--build-option=--use-coreaudio" --config-setting="--build-option=--use-double"` — [compiling.rst](https://github.com/belangeo/pyo/blob/HEAD/documentation/source/compiling.rst)
- ChangeLog di pyo:
  - 2026-05-18: "Fixed GCC 15/C23 incompatibilities";
  - 2026-05-24: "context-manager support to the Server object";
  - 2026-05-30: "PEP 489 multi-phase module initialization";
  - 2026-06-02: "PEP 489 support for multiple sub-interpreters";
  - 2026-07-07: "Migrated process-global static Python types to interpreter-local, heap-allocated types" e "Fixed memory leaks in PyObject_Call statements";
  - 2026-07-14: "Added CallAlways, which calls a Python function every processing frame";
  - 2026-10-09: tag 1.1.0;
  - 2026-10-16: fix di Harmonizer.

  — [ChangeLog pyo](https://github.com/belangeo/pyo/blob/HEAD/ChangeLog)
- Attività: l'ultimo commit di pyo è del 2026-10-25, con 33 issue aperte e 1458 stelle — [GitHub pyo](https://github.com/belangeo/pyo). pyo non è su conda-forge: nessun pacchetto osx-64 — [anaconda.org API conda-forge/pyo](https://api.anaconda.org/package/conda-forge/pyo)
- Issue #271 di pyo, "install failure on OSX Ventura", ancora aperta: su un MacBook Intel i9 con Ventura e Python 3.11, la wheel 1.0.5 fallisce con `Library not loaded: /usr/local/opt/flac/lib/libFLAC.12.dylib`, cioè un path Homebrew cablato. Un altro utente segnala lo stesso problema con `libportmidi`; l'autore promette un nuovo installer (gennaio 2024) — [pyo issue #271](https://github.com/belangeo/pyo/issues/271)
- Nel motore pyo, `Server_process_buffers()` chiama `PyGILState_Ensure()` a ogni buffer, e viene invocata dai callback PortAudio `pa_callback_interleaved` e `pa_callback_nonInterleaved` — [servermodule.c](https://github.com/belangeo/pyo/blob/HEAD/src/engine/servermodule.c); [ad_portaudio.c](https://github.com/belangeo/pyo/blob/HEAD/src/engine/ad_portaudio.c)
- Oggetti granulari di pyo:
  - `Granulator(table, env, pitch, pos, dur, grains=8, ...)`;
  - `Granule(table, env, dens=50, pitch, pos, dur, ...)`, con `setSync` per passare fra modo sincrono e asincrono;
  - `Particle`;
  - `Particle2`, "An even more full control granular synthesis generator" con un filtro per grano della stessa implementazione di `Biquad`;
  - `Looper`.

  Per i buffer live ci sono `Input`, `NewTable`, `TableRec` (scrive in una tabella e alla fine emette un trigger), `TrigTableRec` e `DataTable`. Esempio di server full-duplex: `Server(sr=48000, nchnls=8, buffersize=512, duplex=1)` — [tableprocess.py](https://github.com/belangeo/pyo/blob/HEAD/pyo/lib/tableprocess.py); [tables.py](https://github.com/belangeo/pyo/blob/HEAD/pyo/lib/tables.py); [generators.py](https://github.com/belangeo/pyo/blob/HEAD/pyo/lib/generators.py); [server.py](https://github.com/belangeo/pyo/blob/HEAD/pyo/lib/server.py)
- SignalFlow:
  - 0.6.0 del 2026-10-10: "Improved thread-safe implementation of graph changes, via `AudioGraphChange`", "Added support for multiple `AudioIn` instances, via `AudioInputManager`", "Many CPU optimisations", "Updated minimum Python version to 3.9, and extended support/build up to 3.15";
  - 0.5.0 del 2024-10-28 ha sostituito libsoundio con miniaudio.

  — [GitHub releases SignalFlow](https://github.com/ideoforms/signalflow/releases)
- Wheel SignalFlow 0.6.0: `macosx_12_0_x86_64` e `macosx_12_0_arm64` per cp39-cp315, nessuna free-threaded — [PyPI signalflow](https://pypi.org/project/signalflow/). Ultimo commit il 2026-10-21 ("Add Gain node"), 30 issue aperte — [GitHub SignalFlow](https://github.com/ideoforms/signalflow)
- Nodi granulari di SignalFlow:
  - `Granulator(buffer, clock, pos, duration=0.1, amplitude, pan, rate, max_grains=2048, wrap=false, ...)` "Generates a grain from the given buffer each time a trigger is received on the clock input";
  - `SegmentedGranulator`;
  - `BufferRecorder`: "Records the input to a buffer. feedback controls overdub".

  — [doc Granulator](https://github.com/ideoforms/signalflow/blob/HEAD/docs/library/buffer/granulation/granulator/index.md); [doc BufferRecorder](https://github.com/ideoforms/signalflow/blob/HEAD/docs/library/buffer/bufferrecorder/index.md)
- Nei binding SignalFlow, `AudioGraph.start/stop/destroy` girano con `py::call_guard<py::gil_scoped_release>`. Un nodo definito in Python acquisisce invece il GIL dal thread audio (`py::gil_scoped_acquire`) per chiamare il suo `process`; il codice commenta che le eccezioni Python lì "cannot" essere propagate — [graph.cpp](https://github.com/ideoforms/signalflow/blob/HEAD/source/src/python/graph.cpp); [pynode.cpp](https://github.com/ideoforms/signalflow/blob/HEAD/source/src/python/pynode.cpp)
- supriya: 26.10b1 del 2026-10-20, `requires_python` >=3.11, Python puro, classifier fino a 3.15. Ultimo push il 2026-11-06, 18 issue aperte. È un'API Python per scsynth di SuperCollider, che un altro ricercatore approfondisce — [PyPI supriya](https://pypi.org/project/supriya/); [GitHub supriya](https://github.com/supriya-project/supriya)
- ctcsound su PyPI è fermo alla 6.17.1 (2022-02-25) e il repo all'ultimo commit del 2023-05-31 — [PyPI ctcsound](https://pypi.org/project/ctcsound/); [GitHub ctcsound](https://github.com/csound/ctcsound). Esiste ctcsound7 0.8.0 (2024-12-21), "Python bindings to the Csound API, supporting all versions of csound higher or equal to 6.18", `requires_python` >=3.9 — [PyPI ctcsound7](https://pypi.org/project/ctcsound7/)
- DawDreamer:
  - 0.9.0 del 2026-08-12, `requires_python` >=3.11, wheel `macosx_11_0_x86_64` e arm64 per cp311-cp314, nessuno sdist — [PyPI dawdreamer](https://pypi.org/project/dawdreamer/);
  - gli esempi del README usano `engine.render(4.)`, cioè un render di N secondi; il README non menziona "real-time" né "realtime" — [README DawDreamer](https://github.com/DBraun/DawDreamer);
  - ultimo commit il 2026-11-01, 50 issue aperte.
- libpd: pylibpd 0.14.1 (2025-12-24) su PyPI ha solo wheel `manylinux_2_38_x86_64` per cp312 e cp313, niente macOS — [PyPI pylibpd](https://pypi.org/project/pylibpd/). Il repo libpd ha l'ultimo commit il 2026-06-21 ("bump to version 0.16.1") — [GitHub libpd](https://github.com/libpd/libpd)

### Inferences
- Per una granulazione live sul Mac target, SignalFlow è oggi l'opzione Python-first più installabile:
  - wheel x86_64, Python 3.9-3.15;
  - DSP in C++ senza GIL per i nodi built-in;
  - `AudioIn`, `BufferRecorder` e `Granulator` coprono il caso "registra il microfono e granula";
  - Python costruisce il grafo e modula i parametri.

  Va evitato il nodo Python custom nel percorso audio, perché reintroduce il GIL.
- pyo ha il catalogo granulare più ricco (`Granule` sincrono/asincrono, `Particle2` con filtro per grano), ma su Intel macOS 13 le strade sono due, entrambe da verificare: la wheel 1.0.5 su Python ≤3.11, con il bug noto dei dylib di #271, oppure la 1.1.0 compilata dal sorgente con dipendenze che Homebrew non fornisce più come bottle Intel.
- Siccome pyo prende il GIL a ogni buffer, la contesa misurata nella sezione 1 vale anche per pyo. Un thread Python CPU-bound nello stesso processo, per esempio un generatore di eventi di PGE, può far saltare il server pyo. Il nuovo `CallAlways` esegue Python a ogni buffer per costruzione.
- DawDreamer e libpd non sono candidati per l'I/O real-time sul Mac target: il primo per scelta di progetto (render offline), il secondo per mancanza di wheel macOS.

### Gaps
- Non ho trovato documentazione che dica se il callback di pyo rilascia il GIL durante il DSP C fra un oggetto e l'altro, né misure di latenza o jitter di pyo e SignalFlow su macOS.
- Non ho verificato se pyo 1.1.0 si compila davvero su Intel macOS 13 con dipendenze da MacPorts o compilate a mano, né se la wheel 1.0.5 funziona dopo `brew install flac portmidi`.
- Non ho controllato se DawDreamer abbia un modo di playback real-time non documentato nel README.
- Non ho trovato granulatori in supriya e ctcsound oltre a quelli dei motori sottostanti: fuori scope.

## 4. Accelerare o aggirare Python nel percorso caldo: Numba, Cython nogil, estensione C/C++/Rust, mypyc

### Takeaway
È verificato su questa macchina: una funzione Numba `@cfunc` può essere passata direttamente a `Pa_OpenStream` come callback PortAudio. Lo si fa attraverso l'handle CFFI di sounddevice, che però è privato. Così regge 32, 64 e 128 frame al 100% del tempo reale anche con un thread Python CPU-bound attivo, cioè proprio lo scenario che fa crollare il callback Python al 35%. Il problema per il Mac target è che Numba ha smesso di pubblicare wheel Intel macOS da 0.63.0 (dicembre 2025). Restano conda-forge, che compila ancora osx-64, oppure numba ≤0.62.x su Python ≤3.13. In alternativa si usa un callback C/C++ via CFFI o Cython. Per un'estensione Rust (PyO3 con cpal) non ho trovato progetti di riferimento.

### Cited Findings
- Esperimento locale L2: `@cfunc(types.intc(voidptr, CPointer(float32), ulong, voidptr, ulong, CPointer(float64)), nopython=True)`, con stato e tabella sorgente passati come `userData`. Il puntatore è quello di un array NumPy (`state.ctypes.data`), letto dentro la cfunc con `carray`. La funzione è aperta con `sd._lib.Pa_OpenStream(..., ffi.cast("PaStreamCallback*", pa_cb.address), ...)`, usando `sd._ffi` e `sd._lib`, attributi privati di sounddevice. Il carico è un toy di 32 grani letti per campione con finestra triangolare. Risultati (numba 0.60.0, Python 3.9.6):
  - 64 frame: 2269 callback su circa 2268 attese (100,0%) senza thread concorrente; 2332 su circa 2326 (100,3%) con il thread CPU-bound;
  - 128 frame: 1136/1135 senza thread, 1168/1166 con il thread;
  - 32 frame con il thread: 4644/4634;
  - `cpu_load` massimo ≤0,005 (circa 7 µs per callback a 64 frame); latenza 4,8 ms (64 e 128) e 4,1 ms (32).

  — [misura locale L2][L2]
- Il confronto con il callback Python di sounddevice nello stesso scenario (thread CPU-bound, switch interval di default) è nella sezione 1: 35% del tempo reale a 64 e 128 frame, `cpu_load` circa 1,8. A vuoto il callback Python costa già 0,025 di `cpu_load` a 64 frame — [misura locale L1][L1]
- Documentazione Numba: `@cfunc` crea un callback C con un'unica firma obbligatoria. L'oggetto espone l'attributo `address` da passare a librerie C/C++ e un wrapper ctypes per i test; per gli array si passano puntatore e dimensione e si ricostruisce una vista con `carray` — [Numba, Creating C callbacks with @cfunc](https://numba.readthedocs.io/en/stable/user/cfunc.html)
- Note di rilascio Numba 0.63.0: "Official support for the Intel x86_64 macOS platform (also known as osx-64) has been deprecated and moved to Tier 2 ... Continuous integration will no longer test this platform and new releases will not include binary artifacts for osx-64 ... Users on Intel Macs should use the last officially supported release (0.62.x)" (riferimento numba/numba#10187) — [Numba 0.63.0 release notes](https://numba.readthedocs.io/en/stable/release/0.63.0-notes.html); [numba issue #10187](https://github.com/numba/numba/issues/10187)
- Wheel Numba su PyPI:
  - 0.62.1 (2025-09-29) è l'ultima con `macosx_10_15_x86_64`, per cp310-cp313; 0.63.0 (2025-12-09) e successive non hanno wheel x86_64 per macOS;
  - 0.68.1 (2026-10-27) supporta Python 3.10-3.15 ma su macOS solo `macosx_12_0_arm64`; ha anche wheel cp314t;
  - 0.60.0 (2024-06-13) ha `cp39-macosx_10_9_x86_64` ed è l'ultima con Python 3.9;
  - llvmlite 0.45.1 (2025-10-01) è l'ultima con wheel x86_64 macOS.

  — [PyPI numba](https://pypi.org/project/numba/); [PyPI llvmlite](https://pypi.org/project/llvmlite/)
- conda-forge continua a compilare numba e llvmlite per osx-64: gli ultimi file osx-64 sono numba 0.68.1rc2 (caricato il 2026-11-03) e llvmlite 0.50.0rc3 (2026-10-30) — [anaconda.org conda-forge/numba](https://anaconda.org/conda-forge/numba); [anaconda.org conda-forge/llvmlite](https://anaconda.org/conda-forge/llvmlite)
- Numba 0.68.0 (2026-09-30) "now supports Python 3.15"; 0.67.0 ha aggiunto il supporto a NumPy 2.5 — [GitHub releases numba](https://github.com/numba/numba/releases)
- Cython: 3.3.1 del 2026-11-01, `requires_python` >=3.9, wheel `cp39-abi3-macosx_10_9_x86_64` e supporto cp315 — [PyPI Cython](https://pypi.org/project/Cython/). Esiste un wrapper Cython di PortAudio ("cysounddevice") con un `_stream_callback` a livello C, ma non è su PyPI — [cysounddevice docs](https://cython-sounddevice.readthedocs.io/en/latest/reference/streams.html)
- Il maintainer di sounddevice non ha provato la strada Cython ("might even work with Cython, but I've not tried this") — [sounddevice issue #309](https://github.com/spatialaudio/python-sounddevice/issues/309)
- Esempi già esistenti di "codice compilato padrone del thread audio, Python che manda comandi":
  - rtmixer: callback C via CFFI e ring buffer di PortAudio — [README rtmixer](https://github.com/spatialaudio/python-rtmixer);
  - pedalboard: thread JUCE, FIFO e thread di propagazione ogni 5 ms senza GIL — [AudioStream.h](https://github.com/spotify/pedalboard/blob/master/pedalboard/io/AudioStream.h);
  - SignalFlow: grafo C++ con `AudioGraphChange` thread-safe — [GitHub releases SignalFlow](https://github.com/ideoforms/signalflow/releases).
- Ring buffer SPSC in Rust per l'audio:
  - `rtrb-basedrop`, con letture e scritture "lock-free and wait-free" — [docs.rs rtrb_basedrop](https://docs.rs/rtrb-basedrop);
  - `mutringbuf`, buffer lock-free single-producer single-consumer sviluppato per lo stream processing audio real-time — [docs.rs mutringbuf](https://docs.rs/mutringbuf);
  - `rt-ring`, senza allocazioni, lock né IO nel percorso caldo — [docs.rs rt-ring](https://docs.rs/crate/rt-ring/0.1.0).
- Il ring buffer di rtmixer non è pensato per passare dati fra due thread Python — [rtmixer API docs](https://python-rtmixer.readthedocs.io/en/0.1.2/api.html)
- pippi ("Computer music with Python") 2.0.0b16 (2026-02-09), `requires_python` >=3.8, ospitato su sourcehut — [PyPI pippi](https://pypi.org/project/pippi/); [sourcehut pippi](https://git.sr.ht/~hecanjog/pippi)

### Inferences
- Per PGE l'architettura minima e robusta in un solo processo è questa:
  - un callback compilato (Numba `@cfunc`, oppure C via CFFI in modalità API come rtmixer, oppure Cython `nogil`) che legge parametri e grani attivi da array NumPy preallocati, condivisi via puntatore;
  - Python come control plane, che scrive in un ring buffer SPSC di comandi (nuovo grano: onset, durata, posizione, pitch, ampiezza, finestra);
  - il callback che li consuma senza lock.

  La parte "sound design" di PGE (YAML, envelope, strategy, voices) resta Python; il loop dei grani e la lettura dal buffer live passano al codice compilato.
- La cfunc in modalità nopython non tocca oggetti Python e non ha bisogno del GIL: è coerente con l'immunità misurata alla contesa. Va però evitato tutto ciò che in Numba potrebbe allocare o sollevare eccezioni Python nella cfunc.
- Scrivere un valore float64 allineato è atomico su x86-64, ma aggiornare più campi in modo coerente richiede un protocollo, per esempio un ring di comandi o un doppio buffer con indice: è un'inferenza non verificata con fonti. L'uso di `sd._lib`/`sd._ffi` è API privata. Per qualcosa di stabile conviene un piccolo modulo CFFI proprio, in modalità API, che dichiari `Pa_OpenStream`.
- Il problema delle wheel Numba su Intel spinge verso conda-forge, oppure verso C/Cython compilati localmente, perché il toolchain Xcode CLT c'è su macOS 13. Per PGE, che supporta Python 3.9, numba 0.60.0 è l'ultima versione installabile da PyPI su quell'interprete.
- PyO3 con cpal è fattibile in linea di principio (cpal possiede il thread audio, Python spinge comandi in un ring rtrb), ma non ho trovato progetti Python esistenti da imitare. È la strada più costosa da costruire e da distribuire, perché richiede di compilare wheel macOS x86_64.
- mypyc compila Python annotato in un'estensione che usa comunque gli oggetti e il runtime CPython. Per questo non dovrebbe permettere un callback senza GIL; sarebbe utile solo per accelerare il control plane. È un'inferenza, non verificata con fonti in questa sessione.

### Gaps
- Non ho trovato esempi pubblicati di Numba `@cfunc` usata come callback PortAudio. L'unica prova è L2, eseguita su numba 0.60.0 e Python 3.9.6; non ho verificato numba 0.62.x o conda-forge 0.68 su Intel.
- Non ho misure di Cython `nogil` come callback PortAudio, né di progetti PyO3 con cpal pubblicati.
- Mancano fonti sul comportamento di mypyc rispetto al GIL.
- Non ho misurato quanto costa compilare la cfunc all'avvio: all'import può volerci qualche secondo.

## 5. Evoluzione di CPython rilevante per il real-time: free-threading, subinterpreter, JIT, GC incrementale

### Takeaway
Free-threading:
- 3.13 l'ha introdotto come sperimentale;
- dalla 3.14 è "officially supported but still optional" (fase II della PEP 779), con una penalità del 5-10% sul codice single-thread;
- la 3.15, rilasciata il 9 ottobre 2026, lo installa di default negli installer macOS ufficiali e aggiunge un ABI stabile apposito (abi3t), ma il passaggio a build di default (fase III) è ancora "undecided".

Il GC incrementale, che avrebbe ridotto le pause, è uscito solo con 3.14.0-3.14.4: è stato ritirato in 3.14.5 e non c'è in 3.15. Ora è una PEP in bozza (848) per la 3.16. Il JIT resta sperimentale e in 3.14 non è disponibile nei build free-threaded. NumPy dichiara esplicitamente di non supportare i subinterpreter. Non ho trovato resoconti di audio real-time con Python free-threaded.

### Cited Findings
- Python 3.15.0 è uscito il 9 ottobre 2026; 3.15.2 e 3.14.10 il 25 ottobre 2026; 3.13.17 il 21 ottobre 2026 — [python.org, Python Releases for macOS](https://www.python.org/downloads/macos/); [endoflife.date Python](https://endoflife.date/python)
- 3.14: "The free-threaded build of Python is now supported and no longer experimental. This is the start of phase II where free-threaded Python is officially supported but still optional". Inoltre "Any decision to transition to phase III, with free-threading as the default or sole build of Python is still undecided" — [What's New in Python 3.14](https://docs.python.org/3.14/whatsnew/3.14.html); [accettazione PEP 779](https://discuss.python.org/t/84319/123)
- 3.14: il free-threaded mode "initially added in 3.13" è stato completato; lo specializing adaptive interpreter (PEP 659) è ora attivo anche in free-threaded; "The performance penalty on single-threaded code in free-threaded mode is now roughly 5-10%, depending on the platform and C compiler used" — [What's New in Python 3.14](https://docs.python.org/3.14/whatsnew/3.14.html)
- 3.15:
  - "The official macOS binaries now install free-threading support by default";
  - PEP 803 introduce lo "Stable ABI for Free-Threaded Builds" (abi3t), che finora andava compilato separatamente (`cp315t`);
  - mimalloc è l'allocatore di default per le allocazioni raw "for better performance on free-threaded builds".

  — [What's New in Python 3.15](https://docs.python.org/3.15/whatsnew/3.15.html); [PEP 803](https://peps.python.org/pep-0803/)
- Wheel free-threaded (tag ABI `cp314t`/`cp315t`) nell'ultima release:
  - presenti per numpy, cffi e pedalboard (cp314t e cp315t), numba e miniaudio (cp314t);
  - assenti per signalflow, pyo, dawdreamer e soundfile;
  - sounddevice e soundfile sono wheel Python pure basate su CFFI.

  — [PyPI numpy](https://pypi.org/project/numpy/); [PyPI cffi](https://pypi.org/project/cffi/); [PyPI pedalboard](https://pypi.org/project/pedalboard/); [PyPI numba](https://pypi.org/project/numba/); [PyPI signalflow](https://pypi.org/project/signalflow/); [PyPI pyo](https://pypi.org/project/pyo/)
- Il modulo `_multiarray_umath` di NumPy dichiara `Py_mod_multiple_interpreters = Py_MOD_MULTIPLE_INTERPRETERS_NOT_SUPPORTED` da Python 3.12 e `Py_mod_gil = Py_MOD_GIL_NOT_USED` da 3.13 ("supports running without an active GIL") — [numpy multiarraymodule.c](https://github.com/numpy/numpy/blob/main/numpy/_core/src/multiarray/multiarraymodule.c)
- PEP 734 ("Multiple Interpreters in the Stdlib") è Final per la 3.14, che introduce il modulo `concurrent.interpreters` — [PEP 734](https://peps.python.org/pep-0734/); [What's New in Python 3.14](https://docs.python.org/3.14/whatsnew/3.14.html). pyo ha aggiunto il supporto ai subinterpreter (PEP 489) nel 2026 — [ChangeLog pyo](https://github.com/belangeo/pyo/blob/HEAD/ChangeLog)
- GC in 3.14.0-3.14.4: il collector di cicli era incrementale, con "maximum pause times are reduced by an order of magnitude or more for larger heaps" e due sole generazioni. "Python 3.14.0-3.14.4 shipped with a new incremental GC. However, due to a number of reports of significant memory pressure in production environments, it has been reverted back to the generational GC from 3.13. This is the GC now used in Python 3.14.5 and later" — [What's New in Python 3.14](https://docs.python.org/3.14/whatsnew/3.14.html); [cpython issue #142516](https://github.com/python/cpython/issues/142516)
- 3.15 usa il GC generazionale di 3.13 — [What's New in Python 3.15](https://docs.python.org/3.15/whatsnew/3.15.html). La PEP 848 "Generational Incremental Garbage Collection" è in Draft per la 3.16, creata il 16 settembre 2026 — [PEP 848](https://peps.python.org/pep-0848/)
- JIT in 3.14: è incluso nei binari ufficiali macOS e Windows come sperimentale, "not recommended for production use", e si attiva con `PYTHON_JIT=1`. L'impatto va "from 10% slower to 20% faster", e "Free-threaded builds do not support JIT compilation" — [What's New in Python 3.14](https://docs.python.org/3.14/whatsnew/3.14.html)
- JIT in 3.15: su pyperformance dà un miglioramento medio geometrico del 7-8% su Linux x86-64 e dell'11-12% su macOS AArch64 rispetto al tail-calling interpreter, con una forbice "from roughly 15% slowdown to over 100% speedup" — [What's New in Python 3.15](https://docs.python.org/3.15/whatsnew/3.15.html); [doesjitgobrrr run 2026-09-25](https://www.doesjitgobrrr.com/run/2026-09-25). La PEP 744 (JIT Compilation) è ancora Draft e la PEP 836 ("JIT Go Brrr") è stata ritirata — [indice PEP](https://peps.python.org/api/peps.json)
- Una ricerca mirata non ha trovato resoconti di callback audio Python su build free-threaded. Le fonti trovate risolvono il problema con callback C (rtmixer, pastream), non con il free-threading — [README rtmixer](https://github.com/spatialaudio/python-rtmixer); [pastream su PyPI](https://pypi.org/project/pastream/)

### Inferences
- Il free-threading eliminerebbe proprio il ritardo da contesa misurato nella sezione 1 (circa 5 ms): il callback non aspetterebbe un GIL tenuto da altri thread. Però:
  - pyo e SignalFlow non hanno wheel free-threaded;
  - numba free-threaded non esiste per Intel macOS;
  - si paga il 5-10% sul single-thread;
  - non elimina le pause del GC.

  Per PGE è un'opzione da sperimentare con sounddevice, numpy e cffi su 3.14t/3.15t (che hanno tutti il supporto), non una base su cui contare.
- I subinterpreter non servono a un generatore di grani in NumPy, perché NumPy rifiuta il caricamento nei subinterpreter.
- Il GC incrementale ritirato fa sì che nel 2026 le pause di gen2 restino quelle del modello generazionale (decine di ms su heap grandi). Servono quindi `gc.freeze()` e il controllo manuale.
- Sul Mac Intel del target il JIT è al più un guadagno modesto sul control plane, e i dati 3.15 pubblicati riguardano Linux x86-64 e macOS ARM. Non tocca il problema del determinismo nel callback.

### Gaps
- Non ho una cifra ufficiale per la penalità single-thread del free-threading in 3.15: il What's New 3.15 non la riporta.
- Non ho verificato se in 3.15 il JIT funziona con il build free-threaded.
- Non ho riletto in questa sessione se il GC del build free-threaded sia stop-the-world, come descritto nella PEP 703. Se lo è, fermerebbe anche un callback che esegue Python.
- Non ho verificato cosa succede importando in un interprete free-threaded un'estensione senza dichiarazione `Py_mod_gil` (nel 3.13 il GIL si riattiva; da confermare per 3.15).
- Non ho trovato benchmark di audio real-time su free-threaded Python.
- Non ho verificato la versione minima di macOS degli installer python.org 3.14 e 3.15.

## 6. Offloading a livello di processo: multiprocessing, shared memory, ring buffer

### Takeaway
Un processo separato per la generazione dei grani, che riempie un ring buffer in `multiprocessing.shared_memory`, isola il callback dal GC e dal GIL del generatore. Costa però almeno la profondità del ring in latenza aggiuntiva, e per la granulazione dell'ingresso live richiede che anche il segnale del microfono attraversi il confine fra processi. Il maintainer di sounddevice sconsiglia il multiprocessing "unless it is absolutely necessary" e preferisce un callback compilato. Non ho trovato misure pubblicate.

### Cited Findings
- Matthias Geier, maintainer di sounddevice:
  - con multiprocessing importare `sounddevice` in un solo processo e comunicare con una coda;
  - "In general, I would advise to avoid multiprocessing unless it is absolutely necessary";
  - alternativa proposta: un callback in C che non prenda mai il GIL (rtmixer).

  Nella stessa issue un utente nota artefatti con `sd.play` "when there is additional load on Python" — [sounddevice issue #309](https://github.com/spatialaudio/python-sounddevice/issues/309)
- `multiprocessing.shared_memory` offre blocchi di memoria condivisa fra processi, utilizzabili come buffer di array NumPy — [docs Python: multiprocessing.shared_memory](https://docs.python.org/3/library/multiprocessing.shared_memory.html)
- `gc.freeze()` è documentato anche per i processi che fanno `fork()` senza `exec()`, per evitare il copy-on-write nei figli — [docs Python: gc](https://docs.python.org/3.15/library/gc.html#gc.freeze)
- pa-ringbuffer (0.1.4, 2020) espone in Python il ring buffer lock-free di PortAudio — [PyPI pa-ringbuffer](https://pypi.org/project/pa-ringbuffer/). Il ring buffer di rtmixer serve a passare dati fra Python e il callback C, non fra due thread Python — [rtmixer API docs](https://python-rtmixer.readthedocs.io/en/0.1.2/api.html)
- Un callback sounddevice che si limita a copiare dati (simile al callback vuoto) costa circa 33-43 µs per blocco a 64-128 frame sul Mac target — [misura locale L1][L1]
- NumPy non è caricabile nei subinterpreter: per il parallelismo restano processi o thread — [numpy multiarraymodule.c](https://github.com/numpy/numpy/blob/main/numpy/_core/src/multiarray/multiarraymodule.c)

### Inferences
- Latenza: con un ring fra processi la latenza in uscita si allunga quanto il riempimento del ring. Per la granulazione live il percorso diventa microfono, processo I/O, shared memory, worker, shared memory, processo I/O, uscita: realisticamente uno o due blocchi in più per tratta, per esempio da 2×5,3 ms a 256 frame. Questo è accettabile per tessiture granulari, meno per un gioco strettamente "playable".
- Il processo audio deve restare privo di altri thread Python CPU-bound, altrimenti torna la contesa misurata nella sezione 1. La combinazione più solida è un callback compilato (cfunc o C) nel processo I/O che legge da shared memory scritta da un worker Python con NumPy.
- Per PGE ha senso far generare i grani al worker "in anticipo" sui tempi d'evento (look-ahead di qualche blocco), lasciando al callback solo il mix. Per la lettura del buffer live il worker deve conoscere la posizione di scrittura corrente: va fatta passare nella shared memory come indice monotono.

### Gaps
- Non ho trovato misure pubblicate di latenza o jitter per pipeline audio Python basate su `multiprocessing.shared_memory`.
- Non ho misurato localmente il costo di sincronizzazione fra processi (polling, semafori, `multiprocessing.Event`) su macOS.

## 7. Esempi concreti di granulazione real-time o di ingresso live in Python, e architetture scelte

### Takeaway
Tutti i sistemi Python che granulano davvero in tempo reale tengono il loop dei grani in codice compilato: C in pyo, C++ in SignalFlow, e C per il mix in rtmixer. Python si limita a costruire il grafo e a modulare i parametri. Non ho trovato progetti open source che granulino il microfono con il loop dei grani scritto in Python o NumPy. La prova di fattibilità più vicina allo stack attuale di PGE è l'esperimento Numba cfunc di questa sessione.

### Cited Findings
- pyo, granulazione dell'ingresso live tutta dentro il motore C: `Input`, poi `TableRec` verso una `NewTable`, poi `Granulator`/`Granule`/`Particle2` che leggono la tabella. I parametri (`pitch`, `pos`, `dur`, `dens`) accettano float o PyoObject, cioè segnali di controllo calcolati in C, e ogni grano campiona i parametri al proprio inizio ("Each grain samples the current value of this stream at the beginning of its envelope and holds it until the end of the grain") — [tableprocess.py](https://github.com/belangeo/pyo/blob/HEAD/pyo/lib/tableprocess.py); [tables.py](https://github.com/belangeo/pyo/blob/HEAD/pyo/lib/tables.py); [generators.py](https://github.com/belangeo/pyo/blob/HEAD/pyo/lib/generators.py)
- SignalFlow, esempio ufficiale `granulation-example.py`:
  - `RandomImpulse` fa da clock, con frequenza modulata da `WhiteNoise` fra 1 e 50 Hz;
  - `pos` e `pan` sono `RandomUniform` triggerati dal clock, `rate` è `RandomExponential(0.5, 2.0)`;
  - `duration` vale 0.5 e l'inviluppo è `EnvelopeBuffer("triangle")`;
  - la sorgente è un file caricato in un `Buffer`.

  Per l'ingresso live ci sono `AudioIn` (più istanze dalla 0.6.0) e `BufferRecorder` con overdub — [granulation-example.py](https://github.com/ideoforms/signalflow/blob/HEAD/examples/granulation-example.py); [doc BufferRecorder](https://github.com/ideoforms/signalflow/blob/HEAD/docs/library/buffer/bufferrecorder/index.md); [GitHub releases SignalFlow](https://github.com/ideoforms/signalflow/releases)
- Una ricerca su progetti "real-time granular synthesis Python live microphone" non ha trovato repository Python pertinenti. Fra i risultati c'erano mgrains (un granulatore con ingresso live, ma in Web Audio e non in Python) e lavori accademici non Python (GrainProc, NIME 2013) — [mgrains (awesome.ecosyste.ms)](https://awesome.ecosyste.ms/projects/github.com%2Fgdamdam%2Fmgrains); [GrainProc, NIME 2013](https://nime.org/proc/sanganeria2013/index.html)
- pippi è un toolkit di computer music in Python, citato in mailing list per l'elaborazione del suono; non ne ho verificato l'architettura real-time — [PyPI pippi](https://pypi.org/project/pippi/)
- Prova di concetto locale: un callback Numba `@cfunc` con 32 grani per campione, stato e tabella in un array NumPy condiviso via `userData`, al 100% del tempo reale a 32, 64 e 128 frame anche con un thread Python CPU-bound — [misura locale L2][L2]

### Inferences
- Le strade per PGE, in ordine di costo d'implementazione:
  1. Mappare la superficie YAML di PGE sui nodi di un motore esistente. SignalFlow (`AudioIn`, `BufferRecorder`, `Granulator`) si installa sul Mac target; pyo (`TableRec`, `Granule`/`Particle2`) su Intel va compilato.
  2. Tenere il generatore di grani PGE in NumPy dentro un callback sounddevice a 256-512 frame in un processo dedicato, con `gc.freeze()` e switch interval basso.
  3. Spostare il loop dei grani in una cfunc Numba o in C/Cython, con Python che scrive comandi ed eventi in array condivisi.

  La strada 3 è quella che lascia a PGE il controllo totale (finestre del catalogo `WindowSpec`, strategy, voices) senza dipendere dalla semantica di un motore terzo.
- In tutte e tre le strade il "sound design" (YAML, envelope, scheduling delle voci) può restare Python, a patto che giri fuori dal thread audio e comunichi senza lock.

### Gaps
- Non ho trovato repository open source che granulino l'ingresso del microfono con il loop dei grani in Python o NumPy, né descrizioni architetturali pubblicate di questi casi. Gli esempi live di pyo e SignalFlow non sono stati eseguiti né misurati sul Mac target.

