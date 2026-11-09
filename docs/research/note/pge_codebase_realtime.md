# PGE verso il tempo reale: mappa della pipeline offline, ostacoli e seam

Stato del codice: HEAD di `main` = `1188dbc`, versione di pacchetto 9.1.0 ([pyproject.toml:18](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/pyproject.toml#L18)). I link sono relativi alla root del repository; `path:riga` indica la riga (o la prima riga dell'intervallo). Le misure stanno nella sezione 6, gli script e gli output nella scratchpad della sessione (percorsi assoluti citati lì). Vocabolario architetturale: modulo, interfaccia, profondità, seam, adapter; "un adapter = seam ipotetico, due adapter = seam reale"; test di cancellazione.

## 1. Mappa end-to-end della pipeline offline: moduli e chiamanti

### Takeaway
PGE è un compilatore offline in due fasi. Prima trasforma lo YAML in oggetti `Stream` (controller più `Parameter`). Poi materializza per ogni stream la lista completa di `Grain` immutabili, organizzata per voce. Uno di tre back-end consuma quella lista per intero e produce un file. Tutta la catena è sincrona, presuppone la durata nota e non ha clock né I/O audio. La lista dei grani è la rappresentazione intermedia su cui convergono tutti i consumatori.

### Cited Findings
**Punti d'ingresso**
- `src/main.py` è uno shim: ri-esporta `pge.cli.main` e lo chiama — [src/main.py:16-25](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/main.py#L16). Il console script `pge` punta a `pge.cli:main` — [pyproject.toml:37](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/pyproject.toml#L37).
- `cli.main()` legge `sys.argv` a mano ([src/pge/cli.py:236-583](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L236)). Poi chiama, in ordine:
  - `Generator(yaml_file, samples_dir=...)` ([cli.py:610](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L610)), `generator.load_yaml()` ([cli.py:613](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L613)), la depfile opzionale ([cli.py:621-622](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L621)) e `generator.create_elements()` ([cli.py:625](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L625));
  - `_build_renderer`, che delega ad `api.build_renderer` ([cli.py:42-178](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L42), [cli.py:627-649](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L627));
  - il GC della cache, solo con STEMS+cache ([cli.py:656-660](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L656));
  - `api.render(...)` ([cli.py:662-669](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L662));
  - gli export opzionali Reaper, Sonic Visualiser, grain JSON e partitura PDF ([cli.py:703-743](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/cli.py#L703)).
- L'API di libreria non chiama `sys.exit` né legge `sys.argv`, e segnala gli errori come eccezioni ([src/pge/api.py:4-15](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L4)). Le funzioni sono:
  - `load_generator`, che fa Generator + `load_yaml` + `create_elements` ([api.py:250-284](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L250));
  - `build_renderer` ([api.py:319-438](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L319));
  - `render`, che costruisce `RenderingEngine` con `StemsRenderMode` o `MixRenderMode` e cronometra il solo `engine.render` ([api.py:510-589](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L510), [api.py:559-575](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L559));
  - `render_file` ([api.py:592-637](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L592)) e gli export ([api.py:640-755](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L640)).
- `import pge` risolve in modo lazy `Generator`, `RenderingEngine` e `ScoreVisualizer` — [src/pge/__init__.py:34-46](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/__init__.py#L34).

**YAML e matematica**
- `Generator.load_yaml` legge con `yaml.safe_load` in UTF-8 ([generator.py:245-258](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L245)). Poi risolve gli stream importati con `file:` ([generator.py:134-135](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L134)), valuta le espressioni ([generator.py:137](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L137)) e legge `seed` ([generator.py:140](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L140)).
- Le espressioni matematiche NON sono valutate in `parameters/parser.py`, e in `src/` non esiste nessuna funzione `safe_eval` (zero occorrenze); CLAUDE.md la nomina comunque ([CLAUDE.md:83](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/CLAUDE.md#L83)).
  - Il lavoro lo fa `Generator._eval_math_expressions` ([generator.py:492-564](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L492)) con la regex `\(([a-zA-Z0-9+\-*/.() ]+)\)` ([generator.py:525](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L525)).
  - La valutazione è `eval(expr, {"__builtins__": {}}, safe_dict)`, con `abs`, `int`, `float`, `min`, `max`, `pow`, `pi`, `e` ([generator.py:531-543](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L531)).
  - Il passo gira una volta sola, sul documento intero, prima di creare gli stream.
- `create_elements` procede così:
  - genera un seed di sessione se lo YAML non ne ha ([generator.py:310-317](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L310)) e filtra solo/mute ([generator.py:321](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L321));
  - `_create_streams` → `_create_stream` costruisce `Stream(...)` ([generator.py:470](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L470)), registra il sample nel `FtableManager` ([generator.py:474](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L474)) e pre-registra tutte le finestre possibili ([generator.py:478](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L478), [generator.py:566-582](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L566));
  - la generazione dei grani è lazy e qui non avviene ([generator.py:480-485](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L480)).

**Stream, config, controller**
- `Stream.__init__` esegue questi passi ([src/pge/core/stream.py:164-243](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L164)):
  - legge la durata del sample dal file con `sf.info` ([stream.py:198](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L198); [src/pge/shared/utils.py:17-35](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/utils.py#L17));
  - costruisce `StreamConfig.from_yaml` ([stream.py:203-207](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L203)) e le normalizzazioni di unità ([stream.py:214-219](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L214));
  - crea i `Parameter` diretti con `ParameterOrchestrator` e `STREAM_PARAMETER_SCHEMA` ([stream.py:299-318](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L299));
  - istanzia i controller `PointerController`, `PitchController`, `DensityController`, `WindowController` ([stream.py:324-347](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L324)), poi `VoiceManager` con quattro assi di voice strategy ([stream.py:349-428](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L349), [stream.py:554-561](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L554)) e `GrainClipStrategy` ([stream.py:227-230](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L227));
  - lascia lo stato `_voices=[]`, `generated=False` ([stream.py:242-243](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L242)).
- `StreamContext` è frozen: `stream_id`, `onset`, `duration`, `sample`, `sample_dur_sec`, `output_sr`, `rng_group` ([src/pge/core/stream_config.py:77-92](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream_config.py#L77)). `StreamConfig` è frozen: `time_mode`, `distribution_mode`, `clip_strategy`, `seed` e altri ([stream_config.py:142-171](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream_config.py#L142)). Una `duration` assente vale la durata del sample ([stream_config.py:27-38](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream_config.py#L27)).

**Grain e voci**
- `Grain` è una dataclass frozen con `__slots__` e otto campi: `onset`, `duration`, `pointer_pos`, `pitch_ratio`, `volume`, `pan`, `sample_table`, `envelope_table`. Valida i tipi in `__post_init__` e definisce `__reduce__` per il pickle multiprocesso ([src/pge/core/grain.py:5-56](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/grain.py#L5)). Non porta né l'indice di voce né lo stream di appartenenza.
- `stream.voices` (`List[List[Grain]]`) è l'unica fonte di verità ed è lazy: la prima lettura genera tutto ([stream.py:1070-1085](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1070)). `grains` è una vista derivata, deprecata, ordinata per onset ([stream.py:1087-1149](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1087)).

**Rendering**
- `RenderingEngine.render` delega a `mode.execute` ([src/pge/rendering/rendering_engine.py:72-105](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/rendering_engine.py#L72)). `StemsRenderMode` chiama `renderer.render_streams` ([src/pge/rendering/render_mode.py:62-95](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/render_mode.py#L62)); `MixRenderMode` chiama `renderer.render_merged_streams` ([render_mode.py:98-129](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/render_mode.py#L98)).
- `AudioRenderer` è una ABC con due metodi astratti che producono un file ([src/pge/rendering/audio_renderer.py:22-119](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/audio_renderer.py#L22)). I tipi sono chiusi in `RendererFactory._VALID_TYPES = {'numpy', 'csound', 'supercollider'}` ([src/pge/rendering/renderer_factory.py:46](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/renderer_factory.py#L46)).
- NumPy:
  - `NumpyAudioRenderer` ([numpy_audio_renderer.py:47-505](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L47)) e `GrainRenderer` ([grain_renderer.py:34-150](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L34));
  - `SampleRegistry` ([sample_registry.py:22-98](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sample_registry.py#L22)) e `NumpyWindowRegistry` ([numpy_window_registry.py:50-139](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_window_registry.py#L50));
  - `numpy_parallel` ([numpy_parallel.py:1-33](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_parallel.py#L1)) e `dc_block` ([dc_blocker.py:36-89](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/dc_blocker.py#L36)).
- Csound: `CsoundRenderer._render` scrive lo score con `ScoreWriter.write_score` e poi lancia `csound` come subprocess ([csound_renderer.py:125-213](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_renderer.py#L125); [score_writer.py:49-71](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/score_writer.py#L49)). La sintassi sta in `CsoundEmitter` ([csound_emitter.py:58-234](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L58)); l'orchestra è [csound/main.orc:1-88](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L1).
- SuperCollider: `SuperColliderScoreWriter.build_bundles` ([sc_score_writer.py:127-169](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L127)) → `osc.write_nrt_score` ([osc.py:160-173](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/osc.py#L160)) → `scsynth -N` ([supercollider_renderer.py:229-253](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/supercollider_renderer.py#L229)). La SynthDef è `pgeGrain` ([supercollider/pge_grain.scd:55-95](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/supercollider/pge_grain.scd#L55)).

**Moduli trasversali**
- `FtableManager` è la symbol table condivisa fra i back-end ([ftable_manager.py:1-99](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/ftable_manager.py#L1)).
- `StreamCacheManager` calcola un fingerprint SHA-256 del dict YAML di ogni stream ([stream_cache_manager.py:125-147](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/stream_cache_manager.py#L125)).
- `seeding` deriva gli RNG ([seeding.py:47-101](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/seeding.py#L47)).
- Gli export (Reaper, SV, depfile, grain JSON) leggono `generator.streams` dopo il render ([api.py:675-755](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L675)). `collect_grain_counts` legge `voices` solo sugli stream `generated` ([api.py:474-507](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L474)).

**Dipendenze**
- Le dipendenze runtime sono solo `numpy`, `soundfile`, `matplotlib`, `PyYAML` ([pyproject.toml:24-27](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/pyproject.toml#L24)).
- Una ricerca su `src/`, `configs/`, `docs/` e Makefile per sounddevice, pyaudio, portaudio, jack, ctcsound, python-osc, rtmidi, mido, `-odac` e `-iadc` non trova nessun uso. L'unico `-odac`/`-iadc` è commentato nel file di prova [csound/sinus.csd:4-7](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/sinus.csd#L4), che scrive su file.
- Le sole menzioni di "real-time" riguardano il memory pool di scsynth in NRT ([docs/explanation/supercollider-backend.md:33](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/supercollider-backend.md#L33)).

### Inferences
- La lista materializzata di `Grain` è il formato di scambio di tutto il sistema: tre renderer, quattro export e il visualizer la leggono dopo che è stata costruita per intero. Un sistema in tempo reale deve trasformare questa lista in un flusso di eventi.
- Ingresso e uscita sono file in entrambe le direzioni: YAML e sample in ingresso, `.aif`/`.wav`/`.flac`, `.sco` e `.osc` in uscita. Non c'è nessun concetto di "adesso".

### Gaps
- Non ho letto in dettaglio i moduli di visualizzazione (`score_visualizer.py`, 1749 righe) né `export/`. Sono consumatori offline della lista e non stanno sul percorso audio.

## 2. Come un parametro ottiene il suo valore al tempo t, e dove potrebbe agganciarsi una sorgente live

### Takeaway
Ogni valore si campiona all'istante del grano, il cursore della voce relativo all'onset dello stream, dentro `Stream._create_grain` e nel loop di `generate_grains`. Un valore è una unione chiusa `float | Envelope`, smistata con `isinstance(..., Envelope)` in `resolve_param` e in altri 13 punti. Non esiste un'interfaccia "sorgente di valore" e oggi c'è un solo adapter di curva (`Envelope`): il seam è ipotetico e andrebbe introdotto in `resolve_param`/`Parameter._value` e nel parser. Due seam reali stanno vicino: `ProbabilityGate` (quattro adapter) e `InterpolationStrategy` (tre). Una sorgente live li userebbe solo come aggiunte, non come sostituti.

### Cited Findings
**Punto di campionamento**
- `_create_grain(elapsed_time, ...)` valuta, nell'ordine:
  - il verso del grano ([stream.py:865](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L865), [stream.py:917-959](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L917));
  - il pitch `self._pitch.calculate(elapsed_time, ...)` ([stream.py:871](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L871));
  - il pointer `calculate(elapsed_time, grain_dur, grain_reverse, voice_offset=...)` ([stream.py:882-884](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L882));
  - il volume `self.volume.get_value(elapsed_time)` ([stream.py:887](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L887)) e il pan ([stream.py:896](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L896));
  - l'onset `self.onset + elapsed_time + onset_offset` ([stream.py:899](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L899));
  - la finestra `select_window(elapsed_time)` ([stream.py:902](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L902)).
- Nel loop, allo stesso cursore, si valutano `grain_duration`, IOT e `scatter` della voce 0 ([stream.py:778-781](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L778)), `num_voices` ([stream.py:799](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L799)) e il `VoiceConfig` ([stream.py:811](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L811)). Il tempo è sempre "tempo-stream", da 0 a `duration` ([stream.py:784-787](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L784)).

**`Parameter.get_value(time)`**
- La sequenza è: valore base → banda di variazione → gate `deviation_probability` → `VariationStrategy` con `DistributionStrategy` → clamp ai bounds, con una riga di log a ogni clamp ([parameter.py:123-146](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L123), [parameter.py:188-205](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L188)).
- Il tipo del valore è `ParamInput = Union[float, int, Envelope]` ([parameter.py:37](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L37)).
- `resolve_param` fa `isinstance(param, Envelope)` → `param.evaluate(time)`, altrimenti `float(param)`; `None` vale 0.0 ([parameter.py:41-47](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L41)).

**Dispatch distribuito**
- `resolve_param` ha 13 chiamanti: `Parameter` e le voice strategy (pan 4, pitch 3, onset 3, pointer 2). Esempio: [voice_onset_strategy.py:80-83](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/voice_onset_strategy.py#L80).
- Chiamate dirette a `.evaluate(t)`: curve delle finestre ([window_selection_strategy.py:211](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_selection_strategy.py#L211), [window_selection_strategy.py:282](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_selection_strategy.py#L282)), pitch ([strategie.py:108](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/strategie.py#L108), [voice_pitch_strategy.py:445](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/voice_pitch_strategy.py#L445)) e gate ([probability_gate.py:101-105](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/probability_gate.py#L101)).
- I punti con `isinstance(..., Envelope)` sono 14. Fra questi:
  - [parser.py:374-375](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L374), [parser.py:490](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L490);
  - [parameter.py:45](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L45), [parameter.py:202](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L202);
  - [pointer_controller.py:133](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L133), [pointer_controller.py:551](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L551);
  - [stream.py:409](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L409), [voice_manager.py:295](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/voice_manager.py#L295);
  - [parameter_curve.py:58](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter_curve.py#L58), [gate_factory.py:225](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/gate_factory.py#L225), [score_writer.py:200](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/score_writer.py#L200).

**Il parser è un dispatch chiuso a due rami**
- `_parse_input` gestisce: `None`; `int`/`float` → `float`; `list`/`dict` → `create_scaled_envelope(raw, self.duration, self.time_mode)`; ogni altro tipo → `InvalidParameterError` ([parser.py:234-274](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L234)).
- La validazione clippa al parse i breakpoint degli envelope, oppure solleva `ParameterBoundError` in modalità strict ([parser.py:414-547](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L414)). Per qualunque altro tipo solleva `TypeError` ([parser.py:551](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L551)).
- Ogni `Parameter` riceve un `random.Random` derivato da (seed, rng_id, nome) ([parser.py:218-228](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L218); [seeding.py:68-91](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/seeding.py#L68)).

**Sorgenti di valore che esistono oggi**
- Costanti e `Envelope`, che coprono:
  - breakpoint con interpolazione per punto;
  - formato compatto con ripetizioni e distribuzioni temporali dei cicli;
  - BP group;
  - forma dict con `type` ([envelope.py:1-65](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/envelopes/envelope.py#L1)).
- Le interpolazioni sono Linear, Step e Cubic dietro l'ABC `InterpolationStrategy` ([envelope_interpolation.py:9-162](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/envelopes/envelope_interpolation.py#L9)). `Segment` è un'ABC con il solo `NormalSegment` ([envelope_segment.py:21-103](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/envelopes/envelope_segment.py#L21)).
- Variazione stocastica `_range` con jitter implicito di default ([parameter.py:156-186](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L156)).
- Gate `deviation_probability` con quattro adapter: Never, Always, Random, Envelope ([probability_gate.py:12-114](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/probability_gate.py#L12)), costruiti da `GateFactory` ([gate_factory.py:73-197](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/gate_factory.py#L73)).
- Voice strategy linear, geometric, stochastic e altre ([voice_onset_strategy.py:68-143](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/voice_onset_strategy.py#L68)).
- Strategie di finestra single, random, transition, multistate ([window_controller.py:31-45](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_controller.py#L31)).
- Strategie di densità `fill_factor` e `density` ([strategy_registry.py:20-23](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/strategy_registry.py#L20)).
- Loop del pointer ([pointer_controller.py:345-494](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L345)).

**Tempo normalizzato**
- Con `time_mode: normalized`, `create_scaled_envelope` moltiplica per la durata dello stream i tempi dei breakpoint, alla costruzione ([envelope.py:604-649](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/envelopes/envelope.py#L604)).
- Lo usano il parser ([parser.py:106](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L106), [parser.py:261-262](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L261)), i gate ([gate_factory.py:183-184](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/gate_factory.py#L183)), i kwarg delle voice strategy ([stream.py:109-110](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L109)), `chord_progression` ([stream.py:527-529](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L527)) e le strategie di finestra ([window_selection_strategy.py:390-401](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_selection_strategy.py#L390)).
- `time_distribution.py` serve solo al formato compatto: distribuisce N cicli su un `total_time` dichiarato, con il vincolo `sum(cycle_durations) == total_time` ([time_distribution.py:60-90](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/envelopes/time_distribution.py#L60)).

**Casi che presuppongono la curva completa**
- La posizione del pointer è `start + speed_ratio.integrate(0, t)`. Usa il valore grezzo `self.speed_ratio.value`, non `get_value`, e la forma chiusa integra sempre da 0 ([pointer_controller.py:543-555](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L543)).
- Il loop "dinamico" è riconosciuto dal tipo di `loop_start` ([pointer_controller.py:131-133](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L131)).
- `max_voices` è il ceiling del breakpoint massimo di `num_voices` e serve a pre-allocare le voci ([stream.py:406-412](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L406), [stream.py:769](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L769)).
- `ParameterCurve.classify` accetta solo `Envelope`, numero o `None`; altro → `TypeError` ([parameter_curve.py:37-74](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter_curve.py#L37)). Lo consumano due adapter, partitura e Sonic Visualiser, che il progetto dichiara seam reale ([docs/explanation/parameter-curve.md:36-40](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/parameter-curve.md#L36)).

### Inferences
**Nessuna interfaccia per una sorgente live**
- "Numero o `Envelope`" è l'interfaccia di fatto. Un oggetto live che imitasse `Envelope` (con `evaluate`, `integrate` e `breakpoints`) mentirebbe in almeno tre punti:
  - `breakpoints` per `max_voices`;
  - la validazione al parse;
  - `integrate(0, t)` per il pointer.
- Un oggetto con `__float__` passerebbe da `resolve_param` ma ignorerebbe il tempo, verrebbe classificato `CONSTANT` dalla partitura e farebbe saltare la posizione del pointer, calcolata come t × velocità corrente.

**Seam proposto: una sorgente di valore**
- Il punto giusto è `resolve_param` (con `Parameter._value`), più un terzo ramo in `GranularParser._parse_input` per la forma YAML.
- Test di cancellazione su `resolve_param`: cancellarla spargerebbe il dispatch in 13 chiamanti, quindi il modulo si guadagna il posto ed è lì che va esteso.
- Diventa un seam reale con due adapter: `Envelope`, la curva nota, e una sorgente live (bus OSC/MIDI con smoothing); la costante può restare un `float`.
- `Parameter` resta profondo e immutato: il gate, la banda, la distribuzione e il clamp continuano a valere sopra la sorgente live.

**Cosa cambierebbe insieme alla sorgente live**
- L'integrazione della velocità del pointer va resa incrementale: `pos += speed·dt` fra un grano e il successivo.
- I punti che decidono per tipo vanno convertiti in domande alla sorgente: "è variabile?", "qual è il massimo dichiarato?".
- La validazione dei bounds si sposta dal parse al runtime. `Parameter._clamp` esiste già, ma oggi scrive una riga di log per ogni clamp.

**Gate e partitura**
- Una probabilità controllata dal vivo sarebbe un quinto adapter di `ProbabilityGate`, che è già un seam reale.
- Per la partitura servirebbe un quarto `kind` di `ParameterCurve` ("live"), oppure un log dei controlli registrato da cui ridisegnare a posteriori.

### Gaps
- Non ho letto riga per riga `parameter_schema.py` e `parameter_definitions.py` (bounds e path YAML di ogni parametro), né le `VariationStrategy`. Il percorso di valutazione è chiaro, ma la lista completa delle chiavi controllabili dal vivo andrebbe estratta dallo schema.

## 3. Il loop di generazione: batch, dipendenza dalla durata, stato, generazione incrementale

### Takeaway
La generazione è un unico loop batch su tutta la durata dello stream, seguito da un post-processo di clipping. Nell'ordine di creazione procede per tempo (un passo = un grano per ogni voce attiva al suo cursore), ma memorizza per voce, e le voci hanno cursori indipendenti. L'ordine di creazione quindi non è l'ordine di onset: lo scarto misurato arriva a 4,14 s su `PGE_cim.yml`. Ritagliare dal corpo del loop un iteratore riprendibile, che restituisca gli stessi grani nello stesso ordine, è fattibile e verificabile. Emettere invece in ordine di onset richiede un buffer di riordino con un lookahead in principio illimitato, oppure un cambio di ordine che rompe la riproducibilità per seed.

### Cited Findings
**Struttura del loop**
- `generate_grains` alloca le liste per `max_voices` e un cursore per voce ([stream.py:766-774](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L766)), poi esegue `while any(c < self.duration for c in voice_cursors)` ([stream.py:776](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L776)).
- A ogni passo:
  - la voce 0 definisce `sync_iot` e `scatter` ([stream.py:778-781](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L778));
  - per ogni voce si calcola il fade frazionario di `num_voices` ([stream.py:799-808](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L799)), il `VoiceConfig`, `_create_grain` e l'append nella lista di quella voce ([stream.py:810-814](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L810));
  - si calcola l'IOT della voce, mescolando IOT sincrono e indipendente secondo `scatter` ([stream.py:816-823](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L816)).
- Alla fine `self._voices = clip_strategy.apply(all_voice_grains, self)` e `generated = True` ([stream.py:828-834](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L828)).
- La sola interfaccia verso i consumatori è la property `voices`, che genera tutto alla prima lettura ([stream.py:1070-1075](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1070)). Non c'è un'API per "avanza fino a t".
- Il clipping è un'ABC `apply(voices, stream)` sull'intera struttura ([grain_clip_strategy.py:33-39](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/grain_clip_strategy.py#L33)), ma il predicato è per grano: `onset < stream_end` e `onset + duration <= stream_end + margin` ([grain_clip_strategy.py:42-54](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/grain_clip_strategy.py#L42)).

**Dove serve la durata totale**
- Terminazione del loop ([stream.py:776](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L776), [stream.py:786](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L786)) e `stream_end` del clipping ([grain_clip_strategy.py:49](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/grain_clip_strategy.py#L49)).
- Scala del tempo normalizzato in tutti i costruttori di envelope ([parser.py:106](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L106), [parameter_orchestrator.py:242](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter_orchestrator.py#L242), [window_controller.py:143](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_controller.py#L143), [window_selection_strategy.py:390](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_selection_strategy.py#L390), [stream.py:462](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L462), [stream.py:527-529](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L527)).
- Default della durata uguale a quella del file audio ([stream_config.py:27-38](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream_config.py#L27)).
- Dimensione dei buffer dei renderer ([numpy_audio_renderer.py:240-250](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L240), [numpy_audio_renderer.py:313-322](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L313)) e fine dello score SC ([sc_score_writer.py:303-320](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L303)).
- Export Reaper e grain JSON ([reaper_project_writer.py:96](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/export/reaper_project_writer.py#L96), [grain_json_writer.py:79](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/export/grain_json_writer.py#L79)) e curva della densità effettiva ([stream.py:1050-1053](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1050)).

**Stato trasportato da un grano al successivo**
- I cursori di voce ([stream.py:774](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L774)).
- Il phase accumulator del loop del pointer: `_in_loop`, `_loop_absolute_pos`, `_last_linear_pos`, i bound precedenti ([pointer_controller.py:271-281](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L271), [pointer_controller.py:425-494](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L425)). Un solo controller per stream, condiviso da tutte le voci.
- Gli RNG per componente, condivisi fra le voci perché lo stesso oggetto `Parameter` o controller serve tutte le voci:
  - `iot` ([density_controller.py:70-74](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/density_controller.py#L70), estrazione a [density_controller.py:210](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/density_controller.py#L210));
  - uno per ogni `Parameter` ([parser.py:226](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L226)) e uno per ogni gate ([parameter_orchestrator.py:250-257](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter_orchestrator.py#L250));
  - `window` ([window_controller.py:145-150](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_controller.py#L145)) e `detune` ([pitch_controller.py:67-74](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pitch_controller.py#L67)).
- Gli RNG per voce delle strategy stocastiche ([seeding.py:47-65](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/seeding.py#L47)).
- Stato di rate limiting dei log ([density_controller.py:96-106](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/density_controller.py#L96); [pointer_controller.py:277-281](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L277)).
- La densità, invece, è causale: IOT = f(t) più un'estrazione casuale, senza altro stato ([density_controller.py:137-213](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/density_controller.py#L137)).

**Ordine voice-major e sue conseguenze**
- I grani si accodano nella lista della propria voce ([stream.py:814](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L814)).
- I renderer appiattiscono per voce: l'ordine delle somme float è ciò che rende un render riproducibile ([stream.py:1101-1107](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1101); [numpy_audio_renderer.py:258-266](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L258), [numpy_audio_renderer.py:324-329](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L324)).
- Lo score SC riordina per onset perché NRT richiede tempi monotoni ([sc_score_writer.py:244-263](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L244)). Lo score Csound è scritto per voce ([score_writer.py:131-138](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/score_writer.py#L131)).

**Disordine misurato fra ordine di creazione e ordine di onset**
- Metrica: per ogni grano creato, il massimo di (massimo onset dei grani creati prima − il suo onset), con gli script della sezione 6.
- `PGE_cim.yml`: 4,142 s su `stream16`. Lo stream ha 17 voci, con `scatter` e `distribution` che salgono a 1 ([configs/PGE_cim.yml:331-364](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/configs/PGE_cim.yml#L331)), quindi i cursori divergono ([stream.py:816-823](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L816)).
- `PGE_density_experiment.yml`: 1,197 s. È `onset_offset` lineare 0,4 s × 3 voci, stream `D5_d300_4v_onset_step0.4`.
- `PGE_12min.yml`: 0,117 s. `PGE_test.yml`: 0 (una voce).
- Grani creati contro grani tenuti dal clipping:
  - cim: 1.059.529 contro 1.059.373;
  - density_experiment: 172.755 contro 169.391;
  - 12min: 832.209 contro 831.350.
- Fonte: bench_rt_results.json (script locale, non conservato).

**Il progetto lo aveva già previsto**
- La generazione non è separabile dallo `Stream`: "I suoi ingressi sono lo stato dello Stream: venti attributi … L'accoppiamento cambierebbe nome, non misura" ([docs/explanation/stream-decomposition.md:273-279](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/stream-decomposition.md#L273)).
- Contratto non dichiarato su `window_table_map` ([stream-decomposition.md:291](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/stream-decomposition.md#L291)).
- Fra i segnali che riaprono la decisione: "un secondo consumatore della generazione — per esempio un backend che produce i grani a pezzi invece che per stream intero" ([stream-decomposition.md:336](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/stream-decomposition.md#L336)).

### Inferences
**Iteratore riprendibile, stessi grani**
- Ogni iterazione del `while` dipende solo dai cursori correnti e da oggetti con stato che avanzano nello stesso ordine. Un iteratore riprendibile, `advance(until_t)` o `iter_ticks()`, che esegue le stesse iterazioni nello stesso ordine produce gli stessi grani, con le stesse estrazioni RNG.
- È verificabile con un test di equivalenza: la concatenazione degli incrementi è uguale al batch. In questo modo il percorso batch diventa "svuota l'iteratore fino a `duration`", e il seam passa da un consumatore a due.

**Ordine di onset: due strade, entrambe costose**
- Buffer di riordino (min-heap per onset), che rilascia i grani più vecchi di "ultimo cursore minimo − orizzonte". L'orizzonte deve coprire l'offset massimo di voce più la divergenza da `scatter`, che non ha un limite superiore (4,1 s misurati) e diventa latenza o richiede un limite dichiarato.
- Avanzare sempre la voce col cursore minimo (coda di priorità sui cursori). Cambia l'interleaving delle estrazioni RNG condivise e quindi la realizzazione per un seed dato: è un cambio di semantica (il progetto lo traccia con `VARIATION_SEMANTICS_VERSION`, [stream_cache_manager.py:39-59](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/stream_cache_manager.py#L39)).

**Senza una durata nota**
- `time_mode: normalized` non è definito: va vietato in modalità live o riferito a una durata nominale dichiarata.
- `num_voices` live richiede un massimo dichiarato.
- Il clipping diventa un filtro per grano senza `stream_end`, oppure con un tempo di stop.
- L'integrale del pointer va reso incrementale.

### Gaps
- Non ho misurato la latenza di una singola iterazione (tick). Il costo per grano è una media per stream (sezione 6).
- Non ho verificato se esistano test che fissano l'ordine di creazione interno, oltre a quelli sull'ordine voice-major dei renderer.

## 4. I tre renderer: come rendono oggi e quanto sono lontani dal tempo reale

### Takeaway
Il renderer NumPy somma tutti i grani in un unico buffer grande quanto l'intero stream o mix. Poi applica un DC blocker non causale, il clip e scrive il file. Il suo nucleo per grano, `GrainRenderer.render`, è però puro e riusabile in un renderer a blocchi con accumulatore delle code. Csound ha un solo strumento `Grain` (`poscil` su tabella GEN01 più tabella finestra, `ksmps=1`): l'orchestra potrebbe girare con `-odac` ricevendo gli stessi i-statement come eventi live, ma non esiste nessuno strumento d'ingresso. SuperCollider emette già un `/s_new` per grano in bundle con timetag. Per un `scsynth` live mancano il trasporto UDP, i timetag NTP assoluti, la sincronizzazione del setup asincrono e un'uscita con offset sub-blocco.

### Cited Findings
**NumPy**
- Alloca `np.zeros((n_total, 2))` per l'intero extent: per stream in STEMS, per il mix in MIX ([numpy_audio_renderer.py:252-260](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L252), [numpy_audio_renderer.py:313-322](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L313)). Somma ogni grano, poi `dc_block`, `np.clip` e `sf.write` ([numpy_audio_renderer.py:267-273](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L267), [numpy_audio_renderer.py:330-339](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L330)).
- Gli onset sono arrotondati al campione: `round(onset * sr)` ([numpy_audio_renderer.py:347-353](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L347)).
- Per grano: `GrainRenderer.render(grain, sample_name, window_name)` e poi `overlap_add_clamped` ([numpy_audio_renderer.py:460-493](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L460)).
- `GrainRenderer` è dichiarato "Componente puro: nessuno stato mutabile, nessun side effect su disco" ([grain_renderer.py:38](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L38)). Rende l'intero grano in una volta:
  - `n_out = round(dur·sr)` ([grain_renderer.py:74](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L74));
  - indici `start + k·pitch_ratio·file_sr/output_sr`, avvolti modulo la lunghezza del sample ([grain_renderer.py:83-92](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L83));
  - interpolazione lineare ([grain_renderer.py:125-150](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L125)), finestra ([grain_renderer.py:100](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L100)), dB→lineare ([grain_renderer.py:104](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L104)), pan mid/side a potenza costante ([grain_renderer.py:116-122](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L116)).
- I sample: `SampleRegistry.load` legge l'intero file con `sf.read`, media i canali a mono e converte a float32 in cache ([sample_registry.py:57-68](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sample_registry.py#L57)). `get(name)` restituisce `(array, file_sr)` ([sample_registry.py:70-88](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sample_registry.py#L70)). `api.build_renderer` carica ogni sample della symbol table prima del render ([api.py:353-355](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L353)).
- Le finestre sono generate alla lunghezza esatta del grano e messe in cache per (nome canonico, n). Sotto 10 campioni nessuna finestra ([numpy_window_registry.py:47](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_window_registry.py#L47), [numpy_window_registry.py:104-115](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_window_registry.py#L104)).
- `dc_block` è una media mobile centrata a fase lineare su tutto il segnale. Il kernel è lungo `sr/20` campioni (2401 a 48 kHz), quindi ogni campione d'uscita legge circa 1200 campioni futuri; i bordi sono replicati, e sotto la lunghezza del kernel ripiega sulla media globale ([dc_blocker.py:59-89](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/dc_blocker.py#L59)).
- Il parallelismo usa un pool di processi `spawn` creato pigramente:
  - a chunk ordinati per onset dentro uno stream ([numpy_audio_renderer.py:405-451](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L405)) oppure a un task per stream in STEMS ([numpy_audio_renderer.py:140-212](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L140));
  - soglia di 1024 grani, con la nota "startup spawn ~100ms per worker vs ~8k grani/s del path sequenziale" ([numpy_parallel.py:50-53](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_parallel.py#L50)).
- Il docstring di `numpy_parallel` dice ancora che la generazione "consuma il `random` globale". Dalla #154 ogni componente ha un RNG derivato ([numpy_parallel.py:6-9](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_parallel.py#L6); [seeding.py:1-39](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/seeding.py#L1)).

**Csound**
- `main.orc` dichiara `sr=48000`, `kr=48000` (quindi `ksmps=1`), `nchnls=2`, `0dbfs=1` ([csound/main.orc:1-4](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L1)).
- `instr Grain` legge da p4 a p9: start in secondi, velocità, volume in dB, pan in gradi, tabella sample, tabella finestra ([main.orc:20-25](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L20)). Calcola `iSampleLen = ftlen/ftsr` e `iFreq = iSpeed/iSampleLen` ([main.orc:35](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L35), [main.orc:43](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L43)).
- L'inviluppo è `poscil:a(iAmp, 1/p3, iEnvTable)`; la sorgente è `poscil:a(aEnv, iFreq, iSampleTable, iStartNorm)`; segue il pan mid/side e `outc` ([main.orc:53-71](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L53)). È `poscil` lineare, non `poscil3` come dice il docstring di `GrainRenderer` ([grain_renderer.py:21](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L21)).
- Non c'è nessun opcode d'ingresso (`in`, `inch`) né alcuno strumento che scriva su una tabella ([main.orc:11-88](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L11)).
- Le tabelle: il sample è `f N 0 0 1 "file" 0 0 1` (GEN01, canale 1, [csound_emitter.py:89-95](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L89)); le finestre sono GEN09/16/20 da 1024 punti tramite `CsoundWindowEmitter` ([csound_emitter.py:52](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L52), [csound_emitter.py:97-145](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L97)).
- L'evento per grano è `i "Grain" onset dur pointer pitch vol pan sample_table env_table`, con onset e durata a 8 decimali ([csound_emitter.py:73-87](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L73)).
- L'esecuzione è `subprocess.run(['csound', '--env:...', '-m', N, orc, sco, '-o', out, '--logfile=...'])`: bloccante, verso file ([csound_renderer.py:186-213](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_renderer.py#L186)). Nessuna API Python di Csound.

**SuperCollider**
- La SynthDef `pgeGrain` ([pge_grain.scd:55-95](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/supercollider/pge_grain.scd#L55)):
  - legge il sample con `BufRd.ar` su `Phasor.ar` più `startSec·BufSampleRate`, modulo i frame, con interpolazione lineare ([pge_grain.scd:74-79](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/supercollider/pge_grain.scd#L74));
  - percorre la tabella finestra con `Line.ar(0, N-1, dur, doneAction: 2)` ([pge_grain.scd:85-86](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/supercollider/pge_grain.scd#L85));
  - esce con `Out.ar`, non `OffsetOut.ar` ([pge_grain.scd:93](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/supercollider/pge_grain.scd#L93)).
- Lo score: al tempo zero `/d_recv`, `/b_allocReadChannel` per i sample e `/b_alloc` + `/b_setn` per finestre da 4096 punti prese dalla stessa `NumpyWindowRegistry` ([sc_score_writer.py:175-226](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L175)).
- Poi un bundle per istante con `/s_new pgeGrain id 0 0 buf envBuf dur startSec rate amp panRad`; dB e gradi sono convertiti in Python ([sc_score_writer.py:142-169](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L142), [sc_score_writer.py:265-285](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L265)). Gli id dei nodi crescono da 1000 ([sc_score_writer.py:64](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L64)).
- `osc.py` scrive soltanto: niente parsing e niente trasporto UDP, per scelta ([osc.py:5-9](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/osc.py#L5)). I timetag sono "tempo trascorso dall'inizio del render" e un valore negativo è rifiutato ([osc.py:82-102](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/osc.py#L82)).
- `scsynth` gira con `-z <block>` (default 1), `-n 32768`, `-m` scalato sui nodi, `-N score _ out` ([supercollider_renderer.py:67-72](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/supercollider_renderer.py#L67), [supercollider_renderer.py:229-253](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/supercollider_renderer.py#L229)).
- La documentazione spiega il block size 1: col default 64 "gli onset dei grani si quantizzerebbero a 1.33 ms a 48 kHz" ([supercollider-backend.md:149-153](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/supercollider-backend.md#L149)).

### Inferences
**NumPy a blocchi**
- Un renderer a blocchi può riusare `GrainRenderer.render` e `overlap_add_clamped` così com'è: rende ogni grano intero al suo onset e lo somma in un accumulatore circolare di uscita lungo almeno la durata massima di un grano. A ogni callback emette i B campioni più vecchi e porta le code nei blocchi successivi.
- L'arrotondamento al campione resta possibile, con un offset dentro il blocco.
- Il `dc_block` attuale va sostituito con un filtro causale, per esempio un passa-alto a un polo, oppure si accetta circa 25 ms di latenza in più. Il clip a [-1, 1] è per campione e passa senza modifiche.
- Il pool `spawn` non è adatto a un callback audio a bassa latenza.

**Buffer d'ingresso live (NumPy)**
- La lettura ciclica modulo `n_source` ([grain_renderer.py:92](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L92)) si adatta per caso a un ring buffer.
- `pointer_pos` però è una posizione assoluta nel file. Con un buffer che si riscrive va tradotta in "secondi dietro la testina di scrittura".
- Siccome il grano si rende tutto al suo onset, i campioni sorgente devono già esistere: ritardo minimo ≥ durata × |pitch_ratio| più un blocco.

**Csound live**
- Si può tenere la stessa orchestra cambiando le opzioni: `-odac`, e `-iadc` per l'ingresso. Gli i-statement di `CsoundEmitter.grain_statement` vanno inviati come eventi in linea (`-L stdin`) o tramite API (`ctcsound`), con p2 relativo all'istante corrente e un lookahead.
- Serve un nuovo strumento d'ingresso che scriva in una tabella circolare (`inch` più `tablew`), e una semantica di lettura relativa alla testina.
- A `ksmps=1` il costo per campione è alto. Con `ksmps` > 1 gli onset si quantizzano al k-ciclo, salvo l'opzione di avvio sample-accurate di Csound 6 (conoscenza esterna, non verificata nel repo).

**SuperCollider live**
- I messaggi `/s_new` e di setup sono quelli giusti. Servono un socket UDP verso `scsynth -u <porta>`, timetag NTP assoluti (adesso + latenza) e `/sync` dopo i comandi asincroni di caricamento.
- Va scelto se usare `OffsetOut` (offset sub-blocco con block size 64) o tenere block size 1.
- Per l'ingresso live: un buffer con `/b_alloc` e un synth di registrazione (`RecordBuf`/`BufWr`) al posto di `/b_allocReadChannel`. È conoscenza di SuperCollider esterna al repo.

### Gaps
- Né `csound` né `scsynth`/`sclang` sono installati su questa macchina (`command -v` non li trova): non ho potuto misurare i due motori in tempo reale né verificare `-odac`/`-iadc`.
- La quota di carico di `ksmps=1` contro `ksmps=32` resta una stima esterna.

## 5. Architettura esistente da riusare

### Takeaway
Tre elementi sono già indipendenti dal target e passerebbero quasi intatti al tempo reale. La symbol table delle tabelle (`FtableManager`). Il catalogo delle finestre con i suoi due emitter (seam reale). La serializzazione per grano dei due motori: `CsoundEmitter.grain_statement` e `SuperColliderScoreWriter._grain_message`. Un emitter di eventi in tempo reale sarebbe un fratello naturale di `CsoundEmitter`. `StreamCacheManager` è riusabile solo per il fingerprint per stream, utile a ricaricare a caldo YAML modificato; la sua nozione di "dirty" si basa sui file e serve solo offline.

### Cited Findings
**`FtableManager`**
- Assegna i numeri di tabella e deduplica: `tables: {num: (tipo, chiave)}` con due soli tipi, `'sample'` e `'window'`, e "non scrive" ([ftable_manager.py:1-70](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/ftable_manager.py#L1)).
- I punti che smistano sul tipo sono sei:
  - [api.py:353-355](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L353);
  - [numpy_audio_renderer.py:432-435](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L432), [numpy_audio_renderer.py:499-505](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L499);
  - [csound_emitter.py:207-213](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L207);
  - [sc_score_writer.py:184-189](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L184);
  - [numpy_parallel.py:258-260](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_parallel.py#L258) e [numpy_parallel.py:340-342](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_parallel.py#L340).

**Catalogo delle finestre**
- `WindowSpec`, `WindowShape` e `WindowRegistry.WINDOWS` descrivono le finestre senza nominare un target ([window_registry.py:63-103](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_registry.py#L63), [window_registry.py:218-340](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_registry.py#L218)).
- L'ABC `WindowEmitter` (`supports`, `materialize`, `covered_names`) ha due adapter, `CsoundWindowEmitter` e `NumpyWindowEmitter` ([window_emitter.py:41-148](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/window_emitter.py#L41); [window_emitters.py:22-24](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/window_emitters.py#L22)). SuperCollider consuma gli array del target NumPy ([window_emitters.py:9-11](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/window_emitters.py#L9)).

**`CsoundEmitter`**
- È l'unico modulo che scrive sintassi `.sco` ([csound_emitter.py:1-38](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L1)) e si può iniettare in `ScoreWriter` ([score_writer.py:33-47](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/score_writer.py#L33)).
- Contratto: "ogni builder restituisce una riga di score completa, newline inclusa" ([csound_emitter.py:34-37](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L34)).

**`ProbabilityGate`**
- ABC con quattro adapter ([probability_gate.py:12-114](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/probability_gate.py#L12)).

**Registry e modalità di render**
- `StrategyRegistry` copre densità, clipping e voice strategy ([strategy_registry.py:20-38](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/strategy_registry.py#L20); [grain_clip_strategy.py:76-79](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/grain_clip_strategy.py#L76)). `RenderMode` ha due adapter ([render_mode.py:18-129](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/render_mode.py#L18)).
- `RenderingEngine.render` è un solo passaggio verso `mode.execute` ([rendering_engine.py:99-105](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/rendering_engine.py#L99)).

**`StreamCacheManager`**
- Calcola un fingerprint SHA-256 del dict YAML dello stream, ignorando `solo`/`mute`, insieme a backend, seed e versione semantica ([stream_cache_manager.py:36](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/stream_cache_manager.py#L36), [stream_cache_manager.py:125-205](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/stream_cache_manager.py#L125)).
- Uno stream è dirty se il fingerprint cambia o se il file `.aif` manca ([stream_cache_manager.py:240-261](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/stream_cache_manager.py#L240)).

**Uso dei grani e dei sample già in comune**
- La lista dei grani è identica per tutti i back-end, quindi le divergenze stanno nel rendering ([docs/how-to/add-renderer.md:93-99](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/how-to/add-renderer.md#L93)).
- La guida al nuovo renderer chiede di riusare `NumpyWindowRegistry` e `SampleRegistry` ([add-renderer.md:25-28](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/how-to/add-renderer.md#L25)).

### Inferences
**Profondità e test di cancellazione**
- `GrainRenderer` è un modulo profondo: interfaccia minima (grano, nome sample, nome finestra → array stereo) dietro cui stanno interpolazione, finestra, dB e pan.
- `CsoundEmitter` è profondo allo stesso modo per la sintassi.
- `RenderingEngine` è poco più di un passaggio: cancellarlo non sposterebbe complessità. Non conviene forzare il percorso live attraverso di lui.

**Riuso per il tempo reale**
- Un "emitter di eventi in tempo reale" per Csound sarebbe un fratello di `ScoreWriter`, non di `CsoundEmitter`: riuserebbe `grain_statement` e le righe `f`, cambiando solo destinazione (eventi in linea o API) e base temporale (p2 relativo ad adesso).
- Per SuperCollider, `_grain_message` e `_setup_messages` vanno estratti dal writer NRT verso un modulo condiviso fra la scrittura su file e l'invio UDP.
- Un terzo tipo di tabella in `FtableManager` (ingresso live) tocca i sei punti di smistamento. Registrare il buffer live come `'sample'` farebbe scrivere a Csound una GEN01 su un file inesistente ([csound_emitter.py:207-211](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L207)) e farebbe sollevare `SampleNotFoundError` al writer SC ([sc_score_writer.py:205-216](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L205)).
- `StreamCacheManager.compute_fingerprint` potrebbe dire quali stream ricostruire quando lo YAML cambia durante l'esecuzione (live-coding). `is_dirty` e il GC basati sui file non servono.

### Gaps
- Non ho letto `csound_window_emitter.py` e `numpy_window_emitter.py` riga per riga; mi affido alla documentazione di CLAUDE.md e all'ABC.

## 6. Velocità empirica: fattore di tempo reale della generazione e del rendering NumPy

### Takeaway
Su questa macchina (Intel i5-7360U, 2 core) la generazione dei grani costa 16–32 µs per grano, cioè 31–62 mila grani al secondo su un core. L'overlap-add NumPy costa circa 44–47 µs per grano con grani di 8–10 ms; il nucleo per grano ha una base fissa di circa 25 µs. In media `PGE_cim.yml` resta sotto il tempo reale (generazione 0,29, render 0,51). Nelle mezze secondi più dense lo supera: generazione 1,58×, overlap-add 2,69× il tempo reale. Gli altri tre config restano largamente sotto. Il collo di bottiglia è il costo Python per grano nelle punte di densità, non la durata.

### Cited Findings
**Metodo**
- Script in sola lettura sul repo: bench_rt.py (script locale, non conservato) e peak_rate.py (script locale, non conservato). Risultati in bench_rt_results.json (script locale, non conservato) e bench_rt_full.log (script locale, non conservato).
- Venv nella scratchpad: Python 3.11.17, numpy 2.4.6, soundfile 0.14.0. Il repo non ha un `.venv` in questo checkout, e `refs/` contiene solo `.gitkeep`: i sample sono sintetici, 10 s, 48 kHz mono PCM16, con lo stesso nome di quelli citati.
- I config non dichiarano `seed:`, quindi ogni run usa un seed di sessione e il numero di grani varia di qualche per mille fra run.
- Generazione forzata per stream leggendo `stream.voices` (lazy, #117). Render MIX replicato passo per passo da `render_merged_streams` con `jobs=1`, cronometrando ogni grano.
- Fattore di tempo reale (RTF) = secondi CPU / secondi audio. RTF di picco = massimo, su finestre da 0,5 s del tempo di onset, del costo per grano sommato e diviso per 0,5 s. Per la generazione uso il costo medio per grano di ogni stream; per il render il tempo misurato per grano.
- Macchina: Intel Core i5-7360U a 2,3 GHz, 2 core e 4 thread, 16 GB, macOS 13.7.8 (`sysctl`, `sw_vers`).

**Generazione**

| config | stream | grani | arco temporale (s) | setup (s) | generazione (s) | µs/grano | grani/s CPU | RTF medio | RTF picco 0,5 s |
|---|---|---|---|---|---|---|---|---|---|
| PGE_test.yml | 6 | 630 | 68,74 | 0,021 | 0,020 | 31,6 | 31,6k | 0,0003 | 0,0024 |
| PGE_density_experiment.yml | 46 | 169.391 | 413,0 | 0,091 | 2,73 | 16,1 | 62,1k | 0,0066 | 0,0215 |
| PGE_cim.yml | 21 | 1.058.883 | 92,47 | 0,078 | 27,04 | 25,5 | 39,2k | 0,292 | 1,58 |
| PGE_12min.yml | 132 | 831.353 | 722,0 | 0,295 | 18,71 | 22,5 | 44,4k | 0,026 | 0,41 |

  Fonte: bench_rt_results.json (script locale, non conservato).

- Stream peggiori di `PGE_cim.yml`: `stream23` (491.352 grani in 24,01 s, 30 voci, 20.465 grani/s audio) ha RTF di generazione 0,542 da solo; `stream21` (306.700 grani, 21 voci) 0,309. In `PGE_12min.yml` il peggiore, `S6_23_climax_stoch`, ha 0,050.
- Il secondo passaggio, con il tracciamento dell'ordine attivo, dà 25,6 s su cim e 19,4 s su 12min: rumore di qualche punto percentuale.

**Tasso di grani al secondo**

| config | grani/s medi | p95 su finestre da 0,5 s | picco 0,5 s | picco 0,1 s |
|---|---|---|---|---|
| PGE_cim.yml | 11.462 | 29.368 | 84.680 | 167.600 |
| PGE_12min.yml | 1.153 | 5.409 | 17.568 | 17.740 |

  Fonte: output di `peak_rate.py` in questa sessione.

**Render NumPy, MIX, `jobs=1`**

| config | grani | durata media grano (ms) | overlap-add (s) | µs/grano | dc_block (s) | clip + write (s) | totale (s) | RTF medio | RTF picco overlap-add |
|---|---|---|---|---|---|---|---|---|---|
| PGE_test.yml | 630 | 75,7 | 0,147 | 232,6 | 0,148 | 0,079 | 0,374 | 0,0054 | 0,014 |
| PGE_density_experiment.yml | 169.391 | 9,9 | 8,03 | 47,4 | 1,25 | 0,545 | 9,82 | 0,024 | 0,073 |
| PGE_cim.yml | 1.058.883 | 8,1 | 47,07 | 44,5 | 0,191 | 0,124 | 47,39 | 0,512 | 2,69 |

  Fonte: bench_rt_results.json (script locale, non conservato).

**Nucleo `GrainRenderer.render` (finestra hanning, pitch 0,5–2)**

| grano | 1 ms | 5 ms | 10 ms | 20 ms | 50 ms | 100 ms | 200 ms | 500 ms |
|---|---|---|---|---|---|---|---|---|
| µs/grano | 26,1 | 34,4 | 52,1 | 57,3 | 98,8 | 183,6 | 321,8 | 743,6 |
| ns/campione | 543 | 143 | 109 | 60 | 41 | 38 | 34 | 31 |

  Sotto i 20 ms domina il costo fisso per grano. Fonte: bench_rt_results.json (script locale, non conservato).

**Dove va il tempo della generazione**
- Profilo cProfile di `stream23` di cim, 491.532 grani. Il profiler gonfia i tempi assoluti (53,5 s profilati contro circa 13 s reali), quindi contano le proporzioni:
  - `Parameter.get_value`: 3.970.227 chiamate (circa 8 per grano), 31,9 s cumulativi, circa il 60%;
  - costruzione dei `Grain`: 5,3 s, circa il 10%, di cui 4,2 s nella validazione in `__post_init__`;
  - `VoiceManager.get_voice_config`: 4,9 s; `PointerController.calculate`: 4,2 s; `Envelope.evaluate`: 5,3 s;
  - corpo di `generate_grains`: 3,0 s.
- Fonte: profilo `profile_dense_stream` in bench_rt_results.json (script locale, non conservato).

**Confronto con le misure del progetto**
- Il modello di costo del repo è `t = a·N_grani + b·D_secondi`, con a ≈ 34 µs per grano e b ≈ 1,4 ms per secondo d'uscita, misurato su Apple M2 Max con Python 3.11. I due termini pareggiano intorno a 42 grani/s ([docs/explanation/costo-rendering.md:40-60](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/costo-rendering.md#L40)).
- Il caso di riferimento `PGE_cim.yml` ha 994.555 grani su 92,5 s: costruzione 9,73 s (9,8 µs/grano), overlap-add + scrittura 20,45 s, memoria circa 225 byte per grano ([costo-rendering.md:79](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/costo-rendering.md#L79), [costo-rendering.md:89-91](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/costo-rendering.md#L89)).
- Questa macchina è circa 2,6× più lenta nella generazione (25,5 contro 9,8 µs/grano) e circa 2,3× nel render (47,4 contro 20,45 s).

### Inferences
**Capacità di un core, e il problema delle punte**
- Su questa macchina un core regge circa 1/(25,5 + 44,5 µs) ≈ 14 mila grani/s di generazione più render con grani di circa 8 ms.
- `PGE_cim.yml` richiede 11,5 mila grani/s in media, 29 mila al 95° percentile e 85 mila nelle punte da 0,5 s: senza parallelismo o un motore nativo le punte superano il tempo reale di circa 2–6× (generazione + render).
- Le punte di generazione e di render scalano entrambe col numero di grani della finestra, quindi presumibilmente coincidono: richiesta di circa 4,3× il tempo reale nella mezza secondo peggiore.
- Su M2 Max, dividendo i numeri del documento, il costo sarebbe circa 9,8 + 20,6 µs ≈ 30 µs per grano, cioè circa 33 mila grani/s per core. Il 95° percentile di cim ci starebbe, le punte resterebbero sopra di circa 2,5×.

**Leve**
- La generazione a sola valutazione Python è circa un terzo del costo per grano. Le leve sono: meno chiamate a `get_value` per grano (otto oggi), validazione del `Grain` disattivabile sul percorso caldo, oppure delegare il DSP a Csound o SuperCollider, che toglie l'overlap-add da Python.
- In un sistema live la lista di grani non si materializza (circa 225 byte per grano): la memoria non è un ostacolo.

### Gaps
- Non ho misurato il render con `jobs > 1` (pool `spawn`), né quello di `PGE_12min.yml`.
- Nessuna misura di Csound o SuperCollider: binari assenti.
- Non ho misurato la variabilità per blocco (jitter, pause del GC), che conta per il tempo reale più della media.
- Il picco di generazione assegna a ogni grano il costo medio del suo stream: è un'approssimazione.
- I sample sintetici sono più lunghi o più corti degli originali. Il costo per grano non dipende dal contenuto, ma la lunghezza del buffer incide sulla cache, come nota [costo-rendering.md:146-147](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/costo-rendering.md#L146).

## 7. Ostacoli al tempo reale in ordine di gravità, e seam dove agganciare i componenti live

### Takeaway
I due ostacoli che bloccano tutto sono strutturali: la generazione batch a mondo chiuso, e il contratto dei renderer "file a fine render". Seguono l'assenza di una sorgente di valore live, il costo Python per grano nelle punte, l'assenza di qualunque percorso d'ingresso audio, l'ordine e lo stato condivisi fra le voci, e la precisione di scheduling negli engine. Dei quattro seam richiesti solo il renderer ha già un seam reale, `AudioRenderer` con tre adapter, ma ha la forma sbagliata. Gli altri tre (sorgente di valore, scheduler incrementale, buffer d'ingresso) oggi sono seam ipotetici con un solo adapter: diventerebbero reali proprio con l'adapter live.

### Cited Findings
**Ostacoli, dal più grave al meno grave**

1. **Generazione batch a mondo chiuso. Gravità: bloccante.**
   - Un solo `while` su tutta la durata, con il clipping come post-processo ([stream.py:755-836](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L755)).
   - L'unica interfaccia è la property lazy `voices`, che genera tutto ([stream.py:1070-1075](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1070)).
   - La durata è letta in circa una dozzina di punti, elencati nella sezione 3. `sample:` è una condizione d'esistenza dello stream ([stream.py:188-201](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L188), [stream.py:253-255](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L253)).
   - Il progetto stesso documenta che la generazione non è separabile dallo stato dello `Stream` ([stream-decomposition.md:273-279](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/stream-decomposition.md#L273)).

2. **Il renderer è un contratto "file a fine render". Gravità: bloccante.**
   - L'ABC rende solo stream interi su un `output_path` ([audio_renderer.py:47-98](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/audio_renderer.py#L47)).
   - NumPy: buffer dell'intero extent più DC blocker non causale ([numpy_audio_renderer.py:313-339](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L313); [dc_blocker.py:79-89](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/dc_blocker.py#L79)).
   - Csound e SC: subprocess bloccanti con `-o file` o `-N` ([csound_renderer.py:186-213](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_renderer.py#L186); [supercollider_renderer.py:229-253](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/supercollider_renderer.py#L229)).
   - `RenderResult` porta solo path e tempo trascorso ([api.py:185-197](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L185)).

3. **Nessuna sorgente di valore live. Gravità: alta.**
   - L'unione chiusa `float | Envelope` ([parameter.py:37-47](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L37)) e il parser a due rami con `TypeError` finale ([parser.py:234-274](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L234), [parser.py:551](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L551)).
   - 14 test di tipo su `Envelope`.
   - Integrale del pointer da 0 ([pointer_controller.py:543-555](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L543)) e `max_voices` dai breakpoint ([stream.py:406-412](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L406)).

4. **Costo Python per grano nelle punte di densità. Gravità: alta, dipende dal materiale.**
   - RTF di picco 1,58 (generazione) e 2,69 (overlap-add) su `PGE_cim.yml` (sezione 6).
   - Il parallelismo esistente è pensato per il batch: `spawn`, chunk per onset, soglia 1024 grani ([numpy_parallel.py:50-53](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_parallel.py#L50); [numpy_audio_renderer.py:429-451](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L429)).

5. **Nessun percorso d'ingresso audio. Gravità: alta.**
   - Uno stream esiste solo con un file sample ([stream.py:188-201](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L188); [utils.py:28-35](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/utils.py#L28)).
   - I bounds del loop valgono `sample_dur_sec` ([parameter_definitions.py:404-407](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter_definitions.py#L404)) e il pointer è assoluto in [0, durata del sample) ([pointer_controller.py:342-343](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L342)).
   - `SampleRegistry` legge file interi ([sample_registry.py:57-68](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sample_registry.py#L57)) e `GrainRenderer` rende il grano intero all'onset ([grain_renderer.py:74-97](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L74)).
   - `FtableManager` conosce due soli tipi di tabella ([ftable_manager.py:32-70](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/ftable_manager.py#L32)). Nessuno strumento d'ingresso in `main.orc` né nella SynthDef ([main.orc:11-88](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L11); [pge_grain.scd:55-95](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/supercollider/pge_grain.scd#L55)).

6. **Ordine e stato condivisi fra le voci. Gravità: media.**
   - Disordine misurato fino a 4,14 s.
   - RNG per componente e phase accumulator condivisi fra le voci e consumati nell'ordine di creazione (sezione 3).
   - Memorizzazione per voce, da cui dipende la riproducibilità delle somme ([stream.py:1101-1107](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1101)).

7. **Precisione di scheduling negli engine. Gravità: media.**
   - `ksmps=1` ([main.orc:1-2](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/csound/main.orc#L1)) e block size 1 di default in SC per evitare quantizzazioni da 1,33 ms ([supercollider-backend.md:149-153](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/supercollider-backend.md#L149)).
   - `Out.ar` invece di `OffsetOut.ar` ([pge_grain.scd:93](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/supercollider/pge_grain.scd#L93)).
   - Encoder OSC senza trasporto e con timetag solo relativi ([osc.py:5-9](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/osc.py#L5), [osc.py:82-102](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/osc.py#L82)).

8. **Effetti di I/O sul percorso caldo. Gravità: media-bassa.**
   - Ogni clamp di un `Parameter` scrive una riga sul clip log ([parameter.py:188-205](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L188)).
   - Altri log con rate limiting ([density_controller.py:162-193](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/density_controller.py#L162); [pointer_controller.py:496-541](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L496)).
   - `print` a costruzione e in render, compresa la riga di protocollo `[CACHE]` ([api.py:25-86](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L25); [numpy_audio_renderer.py:229](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_audio_renderer.py#L229)).

9. **Semantica di riproducibilità e cache. Gravità: bassa, ma cambia contratti.**
   - Seed per componente derivati con sha256 ([seeding.py:68-91](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/seeding.py#L68)) e fingerprint del testo YAML ([stream_cache_manager.py:125-147](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/stream_cache_manager.py#L125)): presuppongono un documento fisso.

**Seam, nel vocabolario della skill**

- **Sorgente di valore live.**
  - Dove: `resolve_param` / `Parameter._value` ([parameter.py:41-47](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L41), [parameter.py:152-154](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter.py#L152)), più `GranularParser._parse_input` ([parser.py:234-274](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parser.py#L234)).
  - Oggi: nessuna interfaccia; `Envelope` è l'unico adapter di curva, quindi il seam è ipotetico.
  - Seam reale vicino: `ProbabilityGate`, con quattro adapter ([probability_gate.py:12-114](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/probability_gate.py#L12)).

- **Scheduler incrementale.**
  - Dove: il corpo di `generate_grains` ([stream.py:776-823](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L776)), la property `voices` come unica interfaccia verso i consumatori ([stream.py:1070-1075](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L1070)) e l'interfaccia del clipping ([grain_clip_strategy.py:37](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/grain_clip_strategy.py#L37)).
  - Oggi: un solo consumatore, il batch. Il secondo è quello che [stream-decomposition.md:336](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/explanation/stream-decomposition.md#L336) prevede.

- **Renderer in tempo reale.**
  - Seam reale ma con la forma sbagliata: `AudioRenderer`, tre adapter, file in uscita ([audio_renderer.py:22-119](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/audio_renderer.py#L22); [renderer_factory.py:46](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/renderer_factory.py#L46)).
  - Pezzi riusabili:
    - `GrainRenderer.render` ([grain_renderer.py:52-122](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L52)) e `overlap_add_clamped` ([numpy_parallel.py:134](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/numpy_parallel.py#L134));
    - `CsoundEmitter.grain_statement` ([csound_emitter.py:73-87](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/csound_emitter.py#L73));
    - `SuperColliderScoreWriter._grain_message` e `_setup_messages` ([sc_score_writer.py:175-194](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L175), [sc_score_writer.py:265-285](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sc_score_writer.py#L265)).

- **Buffer d'ingresso live.**
  - Dove: `SampleRegistry.get()` consumato da `GrainRenderer.render` ([sample_registry.py:70-88](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/sample_registry.py#L70); [grain_renderer.py:77](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/grain_renderer.py#L77)). Più i tipi di `FtableManager` e la semantica di `pointer_pos`.
  - Oggi: un solo adapter, i file in RAM, quindi seam ipotetico.

### Inferences
**Sorgente di valore**
- È un seam ipotetico che diventa reale con l'adapter live. È una sorgente valutabile al tempo t, interrogabile per "è variabile", "massimo dichiarato" e "integrale incrementale", con almeno due adapter: curva nota e controllo live.
- Il test di cancellazione dice di estendere `resolve_param` e non aggiungere test di tipo nei 13 chiamanti.

**Scheduler**
- Un iteratore che restituisce i tick nello stesso ordine del loop rende il percorso batch un adapter dello stesso scheduler: due consumatori, cioè un seam reale.
- Il test d'interfaccia: lo svuotamento incrementale è identico byte per byte al batch.
- Al di sopra serve un buffer di riordino per onset con orizzonte dichiarato.

**Renderer: un nuovo seam a livello di evento grano**
- Il nuovo seam è "consumatore di eventi grano in ordine di onset, con un clock". Con il seam, `AudioRenderer` resterebbe come adattatore offline sopra di esso.
- Adapter possibili:
  1. renderer NumPy a blocchi con accumulatore delle code e un callback audio;
  2. Csound live con eventi in linea o API sulla stessa `main.orc`;
  3. `scsynth` live via UDP.
- Gli adapter 2 e 3 spostano il DSP fuori da Python e attaccano direttamente l'ostacolo 4.

**Buffer d'ingresso**
- Il ring buffer sarebbe il secondo adapter, ma l'interfaccia deve crescere: posizione della testina di scrittura, "disponibile fino a". Il contratto attuale (array immutabile più sample rate) non dice nulla del tempo.
- Lato engine il buffer vive nell'engine stesso: tabella Csound scritta da `inch`/`tablew`, buffer SC scritto da `RecordBuf`. Python invia solo i parametri relativi alla testina.

### Gaps
- Le gravità sono un giudizio motivato dalle evidenze, non una misura.
- Non ho prototipato nessun adapter. L'equivalenza "iteratore = batch" è una deduzione dal codice, non un test eseguito.

## 8. Conseguenze sulla superficie pubblica (per PGE-ls, PGE-ui, gl-ls)

### Takeaway
Una modalità in tempo reale cambierebbe quasi tutte le superfici pubbliche: chiavi YAML nuove o ridefinite, nuovi flag e tipi di renderer nella CLI, nuove classi d'errore runtime, e uscite che non sono più file. gl-ls segnalerebbe subito qualsiasi chiave nuova dentro un blocco engine, perché lì il vocabolario è chiuso.

### Cited Findings
- **YAML: chiavi oggi obbligatorie o legate a un file e a una durata.**
  - `sample` è una delle due condizioni d'esistenza di uno stream ([stream.py:253-255](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L253)).
  - `duration` assente vale la durata del sample ([stream_config.py:27-38](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream_config.py#L27)).
  - `time_mode: normalized` scala sulla durata ([docs/reference/yaml.md:320](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/reference/yaml.md#L320); [envelope.py:604-649](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/envelopes/envelope.py#L604)).
  - I bounds di `loop_*` valgono la durata del sample ([parameter_definitions.py:404-407](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/parameters/parameter_definitions.py#L404)).
  - `pointer.start` deve essere scalare ([pointer_controller.py:98-113](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/controllers/pointer_controller.py#L98)).
  - `voices.num_voices` definisce `max_voices` dai breakpoint ([stream.py:406-412](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/core/stream.py#L406)).
  - `seed` di primo livello ([generator.py:140](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/engine/generator.py#L140)) e `clip_strategy` a vocabolario chiuso ([grain_clip_strategy.py:15-22](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/strategies/grain_clip_strategy.py#L15)).
- **CLI.** `--renderer` accetta i tipi di `RendererFactory.available_types()`. Lo stesso elenco esce da `api.renderer_types()`, che "CLI, editor, language server" consultano ([api.py:239-247](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L239); [renderer_factory.py:46-57](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/rendering/renderer_factory.py#L46)). La usage string è difesa da un golden ([docs/how-to/add-renderer.md:44-46](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/docs/how-to/add-renderer.md#L44)).
- **Errori.** `EngineError` si divide in `ConfigError` ed `EngineRuntimeError`; gli errori di subprocess e di binario mancante sono già categorie a sé ([exceptions.py:87-118](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/exceptions.py#L87), [exceptions.py:729-954](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/shared/exceptions.py#L729)).
- **Formati e protocolli.**
  - La riga stdout `[CACHE] <id>: DIRTY|clean` è protocollo che PGE-ui parsa ([api.py:63-66](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L63)).
  - `RenderResult` porta `audio_paths`, `elapsed_seconds` e `grain_counts` ([api.py:185-197](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L185)).
  - Gli export (`.rpp`, `.sv`, grain JSON, PDF) presuppongono stream finiti ([api.py:640-755](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/src/pge/api.py#L640)).
- **gl-ls.** Ha un registry chiuso per `grain`, `pointer`, `pitch`, `voices`: "una chiave che il registry non conosce diventa `unknown-key`". Una chiave nuova "in gl-ls è subito un falso rosso". gl-ls rispecchia anche la riscrittura delle unità al parse (`_UNIT_SCALED`) ([.claude/rules/cross-repo-impact.md:10-22](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/.claude/rules/cross-repo-impact.md#L10)).

### Inferences
**Probabili aggiunte o modifiche (proposte, non decise)**
- YAML:
  - un blocco di sessione live a livello documento (dispositivi, sample rate, dimensione del blocco, latenza, porta OSC/MIDI);
  - sorgente di valore per parametro, per esempio una forma dict `{live: <indirizzo>, default:, smooth:, min:, max:}`;
  - sorgente d'ingresso per stream al posto di `sample:` (`input:` con canale e lunghezza del ring buffer);
  - pointer espresso come ritardo dalla testina di scrittura;
  - `max_voices` esplicito per `num_voices` live;
  - stream a durata aperta, con una nuova sentinella diversa da `duration: null`, che oggi significa "durata del sample";
  - divieto o ridefinizione di `time_mode: normalized` in live, e semantica di `seed` e `clip_strategy` senza `stream_end`.
- CLI: `--live`/`--realtime`, nuovi valori di `--renderer` (per esempio `numpy-rt`, `csound-rt`, `supercollider-rt`), `--input-device`, `--output-device`, `--block-size`, `--latency`, `--osc-port`, `--midi-port`, `--record` (registrazione su file), le opzioni `-odac`/`-iadc` per Csound e `-u <porta>` per scsynth.
- Errori: dispositivo audio non disponibile, sottoesecuzioni o xrun (avviso o errore), trasporto OSC/MIDI, ingresso live non disponibile, e un errore di configurazione per le chiavi incompatibili col live (per esempio `normalized` senza durata).
- Formati:
  - uno spazio di indirizzi OSC per i controlli, che diventa formato pubblico;
  - un protocollo di stato live (carico CPU, xrun, grani/s) al posto di `[CACHE]` per PGE-ui;
  - un log dei controlli ricevuti per rendere riproducibile una performance;
  - un `RenderResult` o un oggetto "sessione" senza `audio_paths` obbligatori.
- Impatto per repo, da verificare con le issue previste dalla regola cross-repo:
  - PGE-ls: schema, completamento e validazione delle nuove forme;
  - PGE-ui: controlli live, selettore del renderer, visualizzazione senza partitura precalcolata;
  - gl-ls: registry delle chiavi, bounds e mirror delle conversioni di unità.

### Gaps
- Non ho analizzato i tre repository a valle, come da consegna: l'impatto è dedotto solo dalla superficie di PGE e dalla regola [.claude/rules/cross-repo-impact.md](https://github.com/DMGiulioRomano/PythonGranularEngine/blob/1188dbc/.claude/rules/cross-repo-impact.md#L1).
