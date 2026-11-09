# Controllo interattivo dei parametri di sintesi in tempo reale: protocolli, mapping, smoothing, modelli ibridi score+live

Stato delle fonti al 9 novembre 2026. Macchina target: macOS 13.7.8 Ventura, Intel x86_64. Versioni, date e wheel dei pacchetti Python sono stati verificati il 2026-11-09 interrogando l'API JSON di PyPI (`https://pypi.org/pypi/<pacchetto>/json`) e, per python-osc, ispezionando il wheel 1.10.2. Le fonti datate sono segnalate come tali.

## 1. Protocolli di controllo e librerie Python: OSC 1.0/1.1, MIDI 1.0, MIDI 2.0/UMP, MPE (risoluzione, latenza, jitter, manutenzione)

### Takeaway
OSC è il bus naturale per il layer di controllo di PGE. Trasporta float32/float64 senza quantizzazione a 7 bit, ha un namespace gerarchico con pattern matching e bundle atomici con timetag NTP, che permettono di scambiare jitter con una latenza costante. Si usa UDP su localhost o LAN; TCP, con framing SLIP in OSC 1.1, serve solo dove un messaggio perso è inaccettabile. Il bersaglio percettivo classico è latenza ≤ 10 ms e variazione ≤ 1 ms (Wessel & Wright).

MIDI 1.0 resta lo strato d'ingresso per i controller hardware: 7 bit, oppure 14 bit tramite coppie MSB/LSB o NRPN. MPE serve per l'espressione per-nota. MIDI 2.0/UMP esiste in CoreMIDI anche su Ventura a livello di event list, ma le API per gli endpoint UMP richiedono macOS 15 e il lato Python è immaturo. python-rtmidi (ultima release novembre 2023) e mido (ottobre 2024) gestiscono solo MIDI 1.0; l'unico ponte credibile verso UMP è pylibremidi (gennaio 2026, wheel x86_64 solo per cp310–cp312). python-osc 1.10.2 (aprile 2026) è la libreria OSC Python di riferimento.

### Cited Findings

#### OSC 1.0 (specifica 2002)
- **Allineamento.** Tipi e pacchetti sono allineati a 32 bit: "The size of every atomic data type in OSC is a multiple of 32 bits"; "The size of an OSC packet is always a multiple of 4". — [OSC 1.0 spec](https://opensoundcontrol.stanford.edu/spec-1_0.html)
- **Struttura del messaggio.** Un messaggio è composto da address pattern, type tag string (inizia con `,`) e argomenti.
  - Tipi standard: `i` int32, `f` float32, `s` string, `b` blob.
  - Tipi non standard: `h` int64, `t` timetag, `d` double, `S` symbol, `c` char, `r` RGBA, `m` messaggio MIDI di 4 byte, `T`/`F`/`N`/`I` (True/False/Nil/Infinitum, senza byte di argomento), `[` `]` array.
  - "an OSC application should discard any message whose OSC Type Tag String contains any unrecognized OSC Type Tags". — [OSC 1.0 spec](https://opensoundcontrol.stanford.edu/spec-1_0.html)
- **Pattern matching.** L'indirizzo si spezza in parti su `/` e il pattern deve avere lo stesso numero di parti.
  - `?` vale un carattere, `*` zero o più caratteri, `[...]` una lista o un range (`!` nega), `{foo,bar}` un'alternativa.
  - Tutti i metodi che corrispondono ricevono gli stessi argomenti, in ordine non specificato. — [OSC 1.0 spec](https://opensoundcontrol.stanford.edu/spec-1_0.html)
- **Bundle e timetag.** Il bundle è la stringa `#bundle`, poi un timetag a 64 bit, poi elementi (int32 size più contenuto). I bundle sono annidabili, con timetag interno ≥ esterno.
  - Il timetag è in formato NTP: 32 bit di secondi dal 1° gennaio 1900 e 32 bit di frazione, "to a precision of about 200 picoseconds".
  - Il valore 1 ("63 zero bits followed by a one") significa "immediatamente".
  - Timetag passato o presente: "the OSC Server should invoke the methods immediately". Timetag futuro: il server conserva il bundle e lo esegue all'istante indicato.
  - "OSC Messages in the same OSC Bundle are atomic" (non vale per i bundle annidati); il dispatch segue l'ordine nel pacchetto. — [OSC 1.0 spec](https://opensoundcontrol.stanford.edu/spec-1_0.html)
- **Trasporto.** La specifica non impone un trasporto: "An OSC packet can be naturally represented by a datagram by a network protocol such as UDP". Su stream TCP, "the stream should begin with an int32 giving the size of the first packet" (framing a prefisso di lunghezza). — [OSC 1.0 spec](https://opensoundcontrol.stanford.edu/spec-1_0.html)

#### OSC 1.1 (Freed & Schmeder, NIME 2009; fonte datata ma riferimento della 1.1)
- **Compatibilità.** "The basic encoding format has not changed between OSC 1.0 and 1.1". — [Freed & Schmeder 2009](https://opensoundcontrol.stanford.edu/files/2009-NIME-OSC-1.1.pdf)
- **Wildcard `//`.** OSC 1.1 introduce `//`, preso da XPath, che attraversa più livelli: "This overcomes the limitation of the OSC 1.0 '*' operator that only matches up to '/' boundaries". — [Freed & Schmeder 2009](https://opensoundcontrol.stanford.edu/files/2009-NIME-OSC-1.1.pdf)
- **Tipi obbligatori in 1.1.** `i`, `f`, `s`, `b`, più `T` (True), `F` (False), `N` (Null), `I` e `t` (timetag NTP).
  - `I` è Impulse, "aka 'bang', used for event triggers... This type was named 'Infinitum' in OSC 1.0 optional types".
  - Gli autori indicano il supporto a questi tipi come "the biggest burden the 1.1 imposes on OSC 1.0 implementers". — [Freed & Schmeder 2009](https://opensoundcontrol.stanford.edu/files/2009-NIME-OSC-1.1.pdf)
- **Semantica dei timetag.** Gli autori registrano tre usi incompatibili e decidono di "simply not specifying time tag semantics at all in OSC 1.1", mantenendo il bit meno significativo riservato a "immediately". Due le ragioni:
  - nella messaggistica unidirezionale "the sender of OSC messages cannot know how far ahead in time to schedule OSC messages because it cannot learn of the network latency statistics seen by the receiver";
  - nessun OS desktop garantisce "end-to-end communications latency guarantees and controlled, low jitter". — [Freed & Schmeder 2009](https://opensoundcontrol.stanford.edu/files/2009-NIME-OSC-1.1.pdf)
- **Delivery.** "OSC messages may be sent directly without the need for framing in message oriented protocols such as UDP". Gli stream (TCP, seriale) "are now required to employ SLIP (RFC1055) with a double END character encoding". — [Freed & Schmeder 2009](https://opensoundcontrol.stanford.edu/files/2009-NIME-OSC-1.1.pdf)
- **Discovery.** Avviene via DNS-SD/Zeroconf come `_osc._udp` o `_osc._tcp`, con chiavi TXT `version`, `framing=slip`, `uri`, `types`. — [Freed & Schmeder 2009](https://opensoundcontrol.stanford.edu/files/2009-NIME-OSC-1.1.pdf)

#### Best practice OSC (Schmeder, Freed, Wessel, Linux Audio Conference 2010; fonte datata)
- **Forward synchronization.** Con clock sincronizzati, il timetag serve a schedulare gli eventi con ritardi fissi che coprono il ritardo di trasporto. "The delays must be known and bounded"; l'implementazione è una priority queue (defer / execute / fault). — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **Jitter.** Se i dati sono campionati in modo isocrono, "it is possible to use filters to smooth jitter". Altrimenti, se l'errore di sincronizzazione del clock è minore del jitter di trasporto (caso frequente), i timestamp permettono di eliminarlo. — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **UDP.** I pacchetti possono arrivare fuori ordine ("OSC bundle timestamps can be used to recover the correct order"). Oltre la MTU la frammentazione aggiunge ritardo di riassemblaggio. — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **TCP e framing.** Il prefisso di lunghezza "requires a totally assured transport such as TCP/IP or USB-Serial"; SLIP è "robust to transmission errors" ed è preferito. "Assured transports must be used for any mission critical application of OSC and generally this means TCP/IP". — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **Stato, non delta.** Le codifiche con stato (inviare +1/-1 alle transizioni di un pulsante) si rompono su UDP quando un pacchetto va perso. Con reti da 1 Gbit/s "the simplicity of state-free representations is often more valuable than saving network bandwidth". — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **Precisione e atomicità.** "a 0.5 microsecond temporal precision is sufficient for control of any audio synthesis algorithm" (salvo applicazioni esoteriche). Per l'atomicità servono "careful use of double-buffering, lock-free queues and local memory-barrier operations". — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)

#### Bersagli di latenza e jitter
- **Wessel & Wright (NIME 2001 / CMJ 2002; fonte datata ma canonica).** "We place the acceptable upper bound on the computer's audible reaction to gesture at 10 milliseconds (ms) and the systems described in this paper provide for measured latencies nearer 7 ms. Low variation of latency is critical and we argue that the range of variation should not exceed 1 ms." — [Wessel & Wright](https://arxiv.org/pdf/2010.01570); [NIME 2001](https://nime.org/proc/nime2001_wessel/)
- **Timetag contro jitter.** "Time tags allow one to implement a scheduling discipline that reduces jitter by trading it for latency". — [Wessel & Wright](https://arxiv.org/pdf/2010.01570)
- **Jack et al. 2016.** Su uno strumento percussivo, la condizione a latenza zero è giudicata più positivamente di "10ms with jitter" e di 20 ms in sei misure di qualità; la performance temporale non cambia in modo significativo. — [Jack et al. 2016](https://instrumentslab.org/data/andrew/jack_am2016.pdf)
- **LAC 2010.** Il flam ha una precisione di 1 ms; la latenza tollerabile per una sincronizzazione ritmica stretta fra due esecutori è 10 ms (Chafe et al. 2004). — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)

#### MIDI 1.0
- **Linea seriale.** 31,25 kbaud ±1%, con 8 bit di dati più start e stop: 320 µs per byte. Fonti: riproduzioni non ufficiali del testo della specifica. — [midi-connector.txt (MIT)](https://www.ftp.ai.mit.edu/pub/pinouts/midi/midi-connector.txt); [MIDIbox wiki](https://www.midibox.org/dokuwiki/midi_specification)
- **CC a 14 bit.** È una coppia di controller a 7 bit, con l'MSB su un controller e l'LSB su "MSB controller + 32". — [Max, xctlout](https://docs.cycling74.com/reference/xctlout). È lecito inviare il solo LSB se l'MSB non cambia. — [VCV Community](https://community.vcvrack.com/t/14-bit-midi-in-1-0/1779/95)
- **NRPN.** CC 99 (MSB del parametro) e CC 98 (LSB) selezionano il parametro; il valore arriva su CC 6 (Data Entry MSB) e, opzionalmente, CC 38 (LSB). NRPN e CC a 14 bit sono "two different protocols" dentro la specifica. — [Grid docs, NRPN](https://grid-documentation.onrender.com/wiki/actions/midi/nrpn-midi); [Elektronauts](https://www.elektronauts.com/t/midi-bank-change/1545); [VCV Community](https://community.vcvrack.com/t/14-bit-midi-in-1-0/1779?page=5)

#### MIDI 2.0 / UMP
- **Specifica.** I valori dei controller sono parole a 32 bit. — [midi.org](https://midi.org/?p=6489). I timestamp JR (Jitter Reduction) sono opzionali. — [MIDI Association, forum spec](https://midi.org/community/midi-specifications/midi-2-0-specs). La specifica ufficiale M2-104-UM v1.1.1 ha una sezione dedicata al JR clock. — [AMEI, PDF spec](https://amei.or.jp/midistandardcommittee/MIDI2.0/MIDI2.0-DOCS/M2-104-UM_v1-1-1_UMP_and_MIDI_2-0_Protocol_Specification.pdf)
- **Timestamp JR.** Il mittente trasmette periodicamente il proprio clock e ogni messaggio porta un timestamp a 32 µs di risoluzione (fonte secondaria). — [Performer Magazine](https://performermag.com/?p=32088). I timestamp JR riducono solo il jitter, non la latenza né la sincronizzazione fra dispositivi. — [Hauptwerk forum, che cita materiale MIDI Association](https://forum.hauptwerk.com/viewtopic.php?p=130191)
- **CoreMIDI (Apple).**
  - UMP è la struttura comune a MIDI 1 e 2, e "the group field in UMP allows you to send up to 16 combined MIDI streams at the same time".
  - "By default, Core MIDI adopts the MIDI 2 specification and transparently converts sent MIDI data into the packet format and protocol a MIDI destination specifies... legacy MIDI destinations receive MIDIPacketList. MIDI 2 destinations receive MIDIEventList."
  - Invio con `MIDIEventListInit` / `MIDIEventListAdd` / `MIDISendEventList`; ricezione con `MIDIDestinationCreateWithProtocol`.
  - "The system calls MIDIReceiveBlock on a high-priority thread, so perform real-time safe and nonblocking operations within the block"; l'esempio Apple accoda gli eventi in una FIFO consumata dal main thread. — [Apple, Incorporating MIDI 2 into your apps](https://developer.apple.com/documentation/coremidi/incorporating-midi-2-into-your-apps)
- **Endpoint UMP.** `MIDIUMPEndpointManager` (endpoint UMP, function block, notifiche di aggiunta, rimozione e aggiornamento) è disponibile da "macOS: 15.0.0", quindi non su Ventura. — [Apple, MIDIUMPEndpointManager](https://developer.apple.com/documentation/coremidi/midiumpendpointmanager)
- **Blog di terze parti (marzo 2026, non verificato).** Sostiene che:
  - `MIDISendEventList` esiste da macOS 11;
  - `MIDIUMPEndpointManager` e `MIDICIDeviceManager` arrivano con macOS 15 (rilevati negli header SDK);
  - macOS 26.4 aggiunge Network MIDI 2.0 su UDP;
  - CoreMIDI è "transport-only", senza MIDI-CI Discovery né Property Exchange di alto livello.

  Altre fonti parlano genericamente di supporto MIDI 2.0 da macOS 11. — [midi2kit blog](https://midi2kit-github-io.pages.dev/blog/2026-03-29-macos15-midi2-modernization.html)
- **Segnalazione aneddotica.** Un dispositivo USB MIDI 2.0 risultava con `hasMidi2Support()` false su macOS. — [JUCE forum](https://forum.juce.com/t/making-clean-ump-on-mac/69010)

#### MPE
- **Principio.** È un'estensione ufficiale di MIDI 1.0, ratificata dalla MMA nel gennaio 2018. Ogni nota ha il proprio canale, quindi pitch bend, pressure e timbro sono per-nota. — [Roger Linn Design, What is MPE](https://rogerlinndesign.com/support/support-linnstrument-what-is-mpe); [midi.org forum](https://midi.org/community/midi-specifications/how-midi-mpe-pitch-bend-works)
- **Zone.** Una zona è un gruppo di canali contigui con un master e dei member; le zone sono al massimo due (Lower con master sul canale 1, Upper con master sul 16) e si configurano con l'MPE Configuration Message via RPN 6. Il pitch bend di default è 48 semitoni sui member e 2 sul master (dato da un post del forum midi.org, non dal PDF ufficiale). — [midi.org forum](https://midi.org/community/midi-specifications/how-midi-mpe-pitch-bend-works); [copia della specifica RP-53](https://midimpe.neocities.org/rp53spec.pdf)

#### Librerie Python (verificate il 2026-11-09)
- **python-osc 1.10.2** (2026-04-02, Python ≥ 3.10, wheel in puro Python, quindi va bene su Intel).
  - Offre "UDP and TCP blocking/threading/forking/asyncio server implementations", "UDP and TCP clients, including asyncio support", "TCP support for 1.0 and 1.1 protocol formats" e un bundle builder con `IMMEDIATELY`. — [PyPI python-osc](https://pypi.org/project/python-osc/)
- **python-osc, ispezione del wheel 1.10.2.**
  - Il `Dispatcher` ha l'opzione `strict_timing` ("Whether to automatically schedule messages with future timetags"; con False "messages are dispatched immediately regardless of their timetag"). È implementata con `time.sleep(timed_msg.time - now)` nel percorso sincrono e `await asyncio.sleep(...)` in quello asincrono.
  - Il client TCP usa di default `MODE_1_1` (SLIP).
  - Il parser ha rami per i type tag `i h f d s b r m t T F N` e per gli array; non ho trovato un ramo per `I` (Impulse, obbligatorio in OSC 1.1). — [sorgente python-osc](https://github.com/attwad/python-osc/tree/master/pythonosc) (ispezione del wheel PyPI)
- **pyliblo3 0.17.0** (2026-10-07) è attivo, ma i wheel macOS sono solo arm64; l'ultima release con wheel x86_64 è la 0.13.0 (cp38), quindi su Intel serve la build da sorgente con liblo. — [PyPI pyliblo3](https://pypi.org/project/pyliblo3/)
- **Alternative OSC.** aiosc 0.3 (2026-07-11, "4 - Beta", minimale su asyncio); oscpy 0.6.0 (2021-06-21, ferma); osc4py3 1.0.8 (2018-08-03, ferma). — [PyPI aiosc](https://pypi.org/project/aiosc/); [PyPI oscpy](https://pypi.org/project/oscpy/); [PyPI osc4py3](https://pypi.org/project/osc4py3/)
- **python-rtmidi 1.5.8** (2023-11-20): nessuna release successiva. I classifier arrivano a Python 3.12 e i wheel macOS x86_64 (10.9+) esistono solo per cp38–cp312. Nessuna menzione di MIDI 2.0. — [PyPI python-rtmidi](https://pypi.org/project/python-rtmidi/)
- **mido 1.3.3** (2024-10-25): puro Python, backend RtMidi (default), PortMidi e Pygame, "full support for all 18 messages defined by the MIDI standard". Nessuna menzione di MIDI 2.0. — [PyPI mido](https://pypi.org/project/mido/)
- **RtMidi e UMP.** Un'issue del 2023 dice che per UMP RtMidi deve aspettare che i backend supportino MIDI 2.0. — [thestk/rtmidi #307](https://github.com/thestk/rtmidi/issues/307) (letta tramite riassunto di ricerca)
- **libremidi / pylibremidi.**
  - libremidi è C++20, derivata da RtMidi e ModernMIDI, e "finally supports MIDI 2 on all desktop platforms", con backend CoreMIDI UMP. Il supporto MIDI 2 copre il solo I/O realtime, non gli SMF.
  - pylibremidi 5.4.3 (2026-01-18) si descrive così: "A modern C++ MIDI 1 / MIDI 2 real-time & file I/O library. Supports Windows, macOS, Linux and WebMIDI". Ha wheel macOS x86_64 solo per cp310–cp312.
  - Il changelog cita il binding Python, una C API, un "Network backend to send MIDI 1 and UMP packets over OSC" e il parsing MIDI 2 tramite cmidi2. — [PyPI pylibremidi](https://pypi.org/project/pylibremidi/); [libremidi su GitHub](https://github.com/celtera/libremidi)
- **Altro per MIDI 2.0 in Python.**
  - pymidi2: implementazione UMP in puro Python su GitHub (creata il 2025-02-15, ultimo push il 2025-08-05), assente da PyPI al 2026-11-09. — [ecosyste.ms, pymidi2](https://awesome.ecosyste.ms/projects/github.com%2Ftitouanc%2Fpymidi2)
  - pymididefs 0.6.0 (2026-10-01): solo costanti MIDI 1.0/2.0. — [PyPI pymididefs](https://pypi.org/project/pymididefs/)
  - Una tesi MIT presenta una libreria Python per costruire e inviare UMP via CoreMIDI. — [DSpace@MIT](https://dspace.mit.edu/handle/1721.1/151396)

### Inferences
- **Costo e risoluzione del MIDI.** Un messaggio MIDI DIN di 3 byte costa 3 × 320 µs = 0,96 ms, e una raffica di CC si serializza a circa 1 ms ciascuno (calcolo). Con 7 bit (128 passi), un pitch su 24 semitoni ha un passo di circa 0,19 semitoni e un pointer su un campione di 60 s un passo di circa 0,47 s (calcolo). Per pitch, pointer e durata servono CC a 14 bit, NRPN, pitch bend, oppure uno smoothing che mascheri il gradino (vedi sezione 3).
- **Namespace OSC per PGE.** Messaggi di stato assoluto, non delta (Schmeder et al. 2010), strutturati per sfruttare il pattern matching, ad esempio:
  - `/pge/<stream_id>/<param>`, `/pge/<stream_id>/<param>/range`, `/pge/<stream_id>/playhead`;
  - un controllo di gruppo come `/pge/*/density`.
- **Timetag come tempo di grano.** Il motore dovrebbe tradurre un bundle con timetag in "tempo di grano", cioè applicare il cambio al primo grano con onset ≥ timetag. Per sorgenti con lookahead (sequencer, generatori, replay) questo dà precisione sub-ms con una latenza costante aggiunta (forward synchronization). Per un esecutore umano, la latenza aggiunta deve restare entro il budget di 10 ms: si usa "immediately" e il cambio va al grano successivo.
- **strict_timing in python-osc.** Mette uno sleep nel percorso di ricezione: il dispatch dipende dalla granularità dello sleep del sistema operativo ed è sganciato dal clock audio. Per collocare i cambi sul tempo dei grani conviene `strict_timing=False` e uno scheduling interno che legge il timetag.
- **Vincolo Python sulla macchina target.** python-rtmidi e pylibremidi hanno wheel x86_64 solo fino a cp312. Per il layer di controllo conviene fissare Python 3.12, compatibile con python-osc (≥ 3.10), watchdog 7 (≥ 3.11) e websockets 17 (≥ 3.11).
- **MIDI 2.0 non serve alla v1.** Se la rappresentazione interna dei controlli è un float normalizzato con unità, MIDI 1 a 7/14 bit, MPE e MIDI 2 diventano adattatori ai bordi. MPE (un canale per nota, con pitch e pressure per nota) si presta a pilotare voci o stream di PGE per-nota; è un'ipotesi di design, non verificata.

### Gaps
- Non ho trovato misure citabili di latenza e jitter per USB-MIDI o CoreMIDI su macOS, né per OSC su Wi-Fi.
- La disponibilità ufficiale di `MIDIEventList` / `MIDISendEventList` da macOS 11 non è confermata da Apple: la pagina restituisce metadati di disponibilità vuoti, e il dato viene solo da un blog di terzi. Il supporto MIDI-CI e Property Exchange su Ventura non è verificato.
- Non ho verificato la superficie dell'API Python di pylibremidi per UMP; per x86_64 non esistono wheel cp313+.
- Non ho consultato il testo ufficiale MIDI 1.0: l'assegnazione standard delle coppie a 14 bit (MSB 0–31 / LSB 32–63) è nota, ma qui è verificata solo indirettamente. La versione della specifica MPE (M1-100-UM v1.1, aprile 2022) e l'uso di CC74 per l'asse Y non sono verificati.
- OSC su WebSocket non ha una specifica formale trovata. Non è verificato se le implementazioni usino un frame WS per pacchetto OSC o SLIP.

## 2. Teoria del mapping: one-to-one, one-to-many, many-to-one, multi-layer; libmapper, Wekinator, interpolazione di preset

### Takeaway
Il mapping definisce lo strumento. Negli esperimenti di Hunt, i mapping complessi (many-to-many, cross-coupled) danno risultati migliori sui compiti complessi e diventano più coinvolgenti con la pratica, mentre l'one-to-one si capisce subito ma ristagna. L'architettura robusta è a più strati, con parametri intermedi "astratti" o percettivi che disaccoppiano controller e sintesi.

Per realizzare il many-to-many senza codificarlo a mano esistono tre strumenti:
- **libmapper** (2.6.1, giugno 2026, wheel universal2 compatibile con Intel/Ventura): un grafo di rete con espressioni (storia, `ema`, sorgenti convergenti);
- **Wekinator**: regressione appresa da esempi, via OSC;
- **la Metasurface di Bencina**: interpolazione di snapshot di preset su una superficie 2D.

### Cited Findings
- **Tesi centrale di Hunt et al.** "In this paper we challenge the assumption that an electronic instrument consists solely of an interface and a sound generator"; il mapping "can define the very essence of an instrument". Versione NIME 2002 (pp. 88–93) e versione rivista su JNMR 32(4), 2003. — [Hunt, Wanderley, Paradis, NIME 2002](https://nime.org/proc/nime2002_hunt/); [PDF Zenodo](https://zenodo.org/records/1176424/files/nime2002_088.pdf)
- **Esperimento a due slider.** La stessa interfaccia e lo stesso oscillatore vengono provati con mapping one-to-one oppure cross-coupled: "the second slider (being moved for amplitude control) was used to mildly offset the pitch". Con l'one-to-one i soggetti capiscono subito come funziona; il mapping accoppiato è "like an instrument". "Only the mapping was altered, and this had a psychological effect on the players." — [Hunt et al. 2002](https://zenodo.org/records/1176424/files/nime2002_088.pdf)
- **Interfacce mouse / slider / multiparametrica** (lavoro riassunto da Hunt & Kirk 2000, *Trends in Gestural Control of Music*, IRCAM):
  - per l'interfaccia multiparametrica "The test scores in general were much higher than those for the other two interfaces, for all but the simplest tests", con miglioramento nel tempo;
  - "If an interface allows the simultaneous control of many parameters, maybe it really is easier to perform the more complex tasks, and harder to accurately isolate individual parameters." — [Hunt et al. 2002](https://zenodo.org/records/1176424/files/nime2002_088.pdf)
- **Test su tre mapping** (FM stereo: ampiezza, frequenza, pan, rapporto e indice di modulazione): (a) one-to-one; (b) one-to-one con "energia" (gesto di "bowing"); (c) many-to-many con energia.
  - Con l'one-to-one "Users tended to learn the parameter associations very quickly but then struggle to achieve any improvement in their performance or expressive output".
  - Con il many-to-many "the appeal of this instrument was that it was not instantly mastered but required effort". — [Hunt et al. 2002](https://zenodo.org/records/1176424/files/nime2002_088.pdf)
- **Studio sul clarinetto WX7** (Wanderley e collaboratori): "By just changing the mapping layer between the controller and the synthesis algorithm, it was indeed possible to completely change the instrumental behaviour and thus the instrument's feel to the performer". — [Hunt et al. 2002](https://zenodo.org/records/1176424/files/nime2002_088.pdf)
- **Modello multi-layer.**
  - "the separation of the mapping layer into two independent layers, coupled by an intermediate set of user-defined (or 'abstract') parameters", implementato in jMax/ESCHER con oggetti di interpolazione di Norbert Schnell.
  - "for the same set of intermediate parameters and synthesis variables, the second mapping layer is independent of the choice of controller being used".
  - Il modello è stato esteso a tre strati da Hunt & Myatt e da Arfib e collaboratori. — [Hunt et al. 2002](https://zenodo.org/records/1176424/files/nime2002_088.pdf)
- **Rovan et al. 1997.** Il lavoro d'origine della tassonomia one-to-one / divergente / convergente è "Instrumental Gestural Mapping Strategies as Expressivity Determinants in Computer Music Performance" (Kansei Workshop, Genova); non sono riuscito a scaricare il testo. — [IDMIL archive PDF](https://www-archive.idmil.org/_media/publications/1997/rovan_1997_kansei.pdf)
- **Mapping many-to-one in libmapper.** Le sorgenti si indicano con `x$0`, `x$1`, ... (in ordine alfabetico), e `x$$` è l'ultima sorgente aggiornata. Il prefisso `_` impedisce che una sorgente scateni la valutazione, per esempio `y=x$0+_x$1`. — [libmapper, expression syntax](https://github.com/libmapper/libmapper/blob/main/doc/expression_syntax.md)
- **One-to-many in OSC.** Il pattern matching OSC realizza "one-to-many mappings between patterns and groups of messages", per esempio `/channel/*/gain`. — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **Verfaille, Wanderley, Depalle (JNMR 35(1):71–93, 2006).** Propongono una strategia di mapping per il controllo gestuale e adattivo degli effetti con "two levels and two layers for each level": il primo livello è il controllo adattivo, con un layer di combinazione delle feature e un layer di condizionamento del segnale di controllo; il secondo è il controllo gestuale. — [JNMR (DOI 10.1080/09298210600696881)](https://informahealthcare.com/doi/full/10.1080/09298210600696881); [IDMIL](https://www.idmil.org/?p=2851)
- **libmapper: pacchetto.** Versione 2.6.1 (2026-06-29) con wheel `py3-none-macosx_10_10_universal2`: "pip install libmapper". La libreria è in C, con binding per C++, C#, Python, Java, Rust, SuperCollider, Max e Pure Data. — [PyPI libmapper](https://pypi.org/project/libmapper/)
- **libmapper: API Python.**
  - Si crea `mpr.Device(name)` e si chiama `dev.poll(block_ms)` "every 100 ms or less".
  - `add_signal(direction, name, length, type, unit, min, max, handler)`: indicare min e max abilita "fast linear-scaled connections".
  - L'handler ha firma `(sig, event, id, val, time)`. Le istanze del segnale (`reserve_instances`) hanno modalità di stealing NONE, OLDEST e NEWEST.
  - I map in codice sono "mostly used when building user interfaces". — [libmapper Python tutorial](https://github.com/libmapper/libmapper/blob/main/doc/tutorials/tutorial_python.md)
- **libmapper: linguaggio di espressione.**
  - Storia con `x{-n}` e `y{-n}` (massimo 100 campioni); filtro IIR `y = y{-1} * 0.9 + x * 0.1`; `ema(x, w)` come "cheap low-pass filter".
  - `schmitt(x,a,b)` per l'isteresi; media mobile con `x.history(10).mean()`; timetag `t_x`.
  - Vettori e riduzioni su istanze (`alive`, `muted`, `x.instance.mean()`). — [libmapper, expression syntax](https://github.com/libmapper/libmapper/blob/main/doc/expression_syntax.md)
- **Webmapper.** La GUI webmapper usa i binding Python e offre metodi convergenti predefiniti (somma, prodotto, media). — [webmapper](https://github.com/libmapper/webmapper) (letta tramite riassunto di ricerca)
- **Wekinator.** Porta di input di default 6448, porta di output 12000, output sull'indirizzo `/wek/outputs`, modalità "All continuous (regression)". Le fonti sono guide di corsi e un esempio ChucK, non la documentazione ufficiale. — [CCRMA course guide](https://ccrma.stanford.edu/~sjavvaji/356/hw3/GUIDE.md); [esempio ChucK wek-outputs](https://chuck.stanford.edu/doc/examples/ai/wekinator/outputs/wek-outputs.ck)
- **Metasurface (Bencina, NIME 2005).** È un'interfaccia di mapping "two-to-many" basata su snapshot di impostazioni di parametri. Li interpola con l'interpolazione natural neighbour, un metodo locale basato sulla tassellazione di Voronoi che offre "increased predictability and the ability to represent multi-scale surfaces". È implementata in AudioMulch. — [Bencina 2005](https://nime.org/proc/nime2005_bencina/)
- **OSC e mapping.** I nomi simbolici di OSC "simplif[y] controller mapping" e il namespace gerarchico aiuta a gestire la complessità. — [Wessel & Wright](https://arxiv.org/pdf/2010.01570)

### Inferences
- **Tre strati per PGE.**
  - (L1) ingressi grezzi normalizzati in 0..1 con unità e range dichiarati: OSC, MIDI, feature del microfono, generatori;
  - (L2) parametri astratti o percettivi di performance, per esempio "densità della tessitura", "granulosità", "posizione nel testo sonoro", "dispersione", "brillantezza";
  - (L3) parametri motore per stream: `density`, `grain.duration`, pitch, `pointer`, pan, bande `*_range`.

  Il mapping L2→L3 può essere dichiarato per stream nello YAML, così cambiare controller tocca solo L1→L2. È la proprietà di indipendenza descritta da Hunt et al.
- **Cross-coupling.** Conviene progettare accoppiamenti deliberati: per esempio, un'"energia" che alza insieme `density` e la banda di pitch, invece di un fader per parametro. I risultati di Hunt suggeriscono che è questo a rendere il sistema "uno strumento".
- **libmapper conviene solo con altri programmi in rete** (webmapper, Max, SC). Per un controllo interno al processo basta un piccolo grafo con la stessa semantica (sorgenti convergenti, storia, `ema`, isteresi) e nessuna dipendenza C.
- **Snapshot interpolabili.** Interpolare preset (Metasurface o una variante più semplice come la distanza inversa) è immediato per parametri scalari in L2 o L3. Per gli envelope serve prima un ricampionamento su una griglia comune. Conviene interpolare valori di L2, che sono pochi e percettivamente omogenei.

### Gaps
- Non ho letto il testo completo di Rovan et al. 1997 né il capitolo di Hunt & Kirk 2000: le definizioni originali di "divergent/convergent" sono citate per titolo.
- Non ho consultato la documentazione ufficiale di Wekinator (stato di manutenzione, dipendenza da Java, compatibilità con Ventura nel 2026).
- Non ho coperto Momeni & Wessel 2003, Van Nort, Wanderley & Depalle (CMJ 2014), Arfib et al. 2002 (spazi percettivi) né i toolkit ML per il mapping (FluCoMa, RapidLib, ml.lib, scikit-learn in-process).

## 3. Smoothing dei parametri: zipper noise, one-pole, rampe lineari, control-rate vs audio-rate, campionamento all'onset del grano

### Takeaway
Lo zipper noise nasce quando un parametro salta una volta per blocco. Ci sono due rimedi standard:
- **smoother one-pole (esponenziale):** costante di tempo τ, raggiunge circa il 63% in τ e non arriva mai del tutto al bersaglio;
- **rampa lineare per blocco:** durata finita e prevedibile.

Nella sintesi granulare la soluzione naturale è campionare i parametri una sola volta all'onset del grano. Il `GrainBuf` di SuperCollider lo fa per tutti gli argomenti: ogni grano è finestrato, quindi un cambio diventa una differenza fra grani successivi e non un click. Per PGE, dove `Grain` è un dato immutabile, il controllo live di densità, durata, pitch, pointer e pan può restare a risoluzione di onset. Lo smoothing per-campione serve solo per i parametri continui applicati all'uscita (gain, filtri) o per un'eventuale modulazione dentro il grano. Resta utile uno smoothing nel dominio dei controlli, valutato agli onset, per non sentire i gradini di un controller a 7 bit.

### Cited Findings
- **Parametri all'onset in GrainBuf.** "All args except numChannels and trigger are polled at grain creation time". Il trigger è "a kr or ar trigger to start a new grain. If ar, grains after the start of the synth are sample accurate"; `maxGrains` "is set at the UGens init time and can't be modified". — [SuperCollider, GrainBuf](https://doc.sccode.org/Classes/GrainBuf.html)
- **Lag in SuperCollider.** `lagTime` è il "60 dB lag time in seconds", descritto come "the time required for the filter to converge to within 0.01% of a value". Lag "is essentially the same as OnePole except that instead of supplying the coefficient directly, it is calculated from a 60 dB lag time"; "For linear and other alternatives, see VarLag". Nota: −60 dB corrisponde allo 0,1%, non allo 0,01%; l'incoerenza è nella pagina stessa. — [SuperCollider, Lag](https://doc.sccode.org/Classes/Lag.html)
- **Smoothing nel dominio dei controlli.** In NodeProxy (JITLib) `lag` imposta tempi di lag per argomento ("identical to setRates"), e `xset` è "set/map with a crossfade into the new setting". — [SuperCollider, NodeProxy](https://doc.sccode.org/Classes/NodeProxy.html)
- **Pratica corrente (fonti di livello forum o documentazione di librerie; attribuzione puntuale da verificare):**
  - zipper noise è il sintomo tipico di un controllo cambiato senza smoothing, quando il valore si aggiorna una volta per buffer;
  - un one-pole con costante di tempo τ raggiunge circa il 63% del bersaglio in τ, con coefficiente `exp(-1/(τ·fs))`;
  - un one-pole "never fully settles" e il codice si ferma a una soglia (per esempio 0,001), mentre la rampa lineare arriva esattamente al bersaglio ed è più facile da disattivare;
  - la durata della rampa va espressa in secondi e ricalcolata solo quando cambia il sample rate;
  - tempi tipici citati: 10–50 ms;
  - un commentatore ritiene il one-pole debole contro lo zipper perché rallenta vicino al bersaglio.

  — [KVR forum](https://www.kvraudio.com/forum/viewtopic.php?p=8638179); [JUCE forum](https://forum.juce.com/t/still-glitchy-zipping-noises-after-using-smoothedvalue/59589); [beamer-core smoothing.rs](https://docs.rs/crate/beamer-core/0.2.3/source/src/smoothing.rs); [blog absolute-azimuth](https://absolute-azimuth.pages.dev/posts/audio/tremolo); [moForte GeoShred, parameter smoothing](https://moforte.com/geoShredAssets7000/help/effects/parameter-smoothing.html)
- **Smoothing nel layer di mapping.** In libmapper è disponibile `ema(x, w)` oppure l'IIR esplicito `y = y{-1} * 0.9 + x * 0.1`. — [libmapper, expression syntax](https://github.com/libmapper/libmapper/blob/main/doc/expression_syntax.md)
- **Ricostruzione dei controlli.** Per stream di controllo campionati in modo isocrono si può filtrare il jitter. Con il campionamento adattivo, "Interpolation between frames can be used to recover a smooth control signal in reconstruction". — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **Rampa di ritorno in Logic.** In modalità Touch, al rilascio "the automation parameter returns to its original (recorded) value" con la "Ramp Time" impostata nelle preferenze. È il precedente di una rampa di ritorno quando un parametro torna dal live allo score (manuale di Logic Pro 9.1.6, fonte datata). — [Apple, Logic Pro 9 automation modes](https://help.apple.com/logicpro/mac/9.1.6/en/logicpro/usermanual/chapter_28_section_2.html)
- **PGE.** `Grain` è una frozen dataclass da non mutare dopo la creazione, e `time_mode: normalized` mappa 0..1 sulla durata al momento della generazione dei grani. — [PGE CLAUDE.md](/Users/giuliodemattia/github/DMGiulioRomano/PythonGranularEngine/CLAUDE.md)

### Inferences
- **Conversione T60 ↔ τ.** Il tempo a −60 dB di un one-pole vale T60 = τ · ln(1000) ≈ 6,91 · τ (calcolo). Un Lag di SuperCollider con lagTime 0,1 s equivale quindi a τ ≈ 14,5 ms.
- **Il control rate effettivo è il grain rate.** Con densità bassa (per esempio 2 grani/s) un cambio live si sente solo dal grano successivo, quindi la latenza percepita arriva fino a 1/densità più la durata dell'attacco della finestra. Per gesti espressivi a bassa densità può servire un percorso diverso: per esempio, il gain d'uscita smussato per-campione, o grani anticipati.
- **Smoothing agli onset.** Il modello consigliato per PGE è una rampa o un one-pole calcolati sulla traiettoria del controllo e valutati all'istante d'onset di ciascun grano, non per-campione. Il costo è trascurabile e il risultato resta deterministico, perché dipende dal tempo logico e non dal wall-clock.
  - Pitch e densità vanno smussati in dominio logaritmico (semitoni, log della densità), il pan in dominio equal-power.
  - Il pointer va trattato a parte: il pointer live è una posizione, e smussarlo introduce una "inerzia" che può essere voluta (scrubbing) o no.
- **La modulazione dentro il grano** (glissando intra-grano, pan che si muove durante il grano) richiede curve per-campione dentro il grano, quindi un `Grain` che porta funzioni e non scalari. È un'estensione separata, perché rompe la de-zipperizzazione naturale.

### Gaps
- Non ho trovato una fonte accademica autorevole sui tempi di rampa consigliati per tipo di parametro, solo valori di forum o blog (10–50 ms).
- Non ho trovato soglie percettive per i gradini nei parametri granulari, come la quantizzazione del pitch a 7 bit o i salti del pointer.
- L'articolo di Truax 1988 (CMJ), con il controllo in PODX via rampe e preset, non è accessibile; il riferimento a Roads (*Microsound*) sulla modulazione intra-grano non è verificato in questa ricerca.

## 4. Sorgenti live oltre l'esecutore: controllo adattivo da feature del microfono (A-DAFx) e sorgenti generative

### Takeaway
Il controllo adattivo è un campo formalizzato. Verfaille, Wanderley e Depalle (2006) lo descrivono come estrazione delle feature, poi combinazione, poi condizionamento (scala, smoothing, isteresi), poi mapping, eventualmente sommato al controllo gestuale. Il precedente granulare diretto sono le opere ecosistemiche di Di Scipio, dove l'interazione avviene a livello di segnale ("sound is the interface").

La latenza è dominata dalle finestre di analisi. RMS e centroide costano circa una finestra (1024 campioni a 44,1 kHz sono circa 23 ms). L'onset costa hop più rilevazione e spesso arriva in ritardo di oltre una finestra. Il pitch ha una finestra minima inversamente proporzionale alla frequenza più bassa. Lato Python su Intel/Ventura la situazione è debole: aubio è fermo dal 2019, senza wheel, ed essentia ha wheel solo per macOS 15.

Le sorgenti generative (LFO, random walk, mappe caotiche, Markov) sono economiche e, se seminate e valutate in tempo logico, deterministiche.

### Cited Findings
- **Schema di Verfaille et al. 2006.** Due livelli: il controllo adattivo, con un layer di combinazione delle feature e un layer di condizionamento del segnale di controllo, e il controllo gestuale. — [JNMR 2006](https://informahealthcare.com/doi/full/10.1080/09298210600696881); [IDMIL](https://www.idmil.org/?p=2851)
- **Di Scipio 2003.** "'Sound is the interface': from interactive to ecosystemic signal processing", *Organised Sound* 8(3):269–277. L'interazione è collocata a livello del segnale, così che gli scambi di informazione hanno natura sonora. — [Meric & Solomos (HAL)](https://halshs.archives-ouvertes.fr/hal-00770097v1/file/Meric_Solomos.pdf)
- **Compromesso latenza/accuratezza del pitch.** Molti algoritmi di pitch tracking affrontano "a fundamental tradeoff between accuracy and latency"; la finestra minima è "inversely related to the lowest frequency to be detected"; una latenza sotto i 10 ms è un bersaglio accettato per l'interazione. — [NIME 2014](http://www.nime.org/proceedings/2014/nime2014_336.pdf)
- **Somma delle latenze.** Le latenze del pitch tracking si sommano, a partire dall'"algorithmic delay" dell'algoritmo; YIN è usato come riferimento. — [Derrien, DAFx 2014](https://www.dafx14.fau.de/papers/dafx14_olivier_derrien_a_very_low_latency_pitch_.pdf)
- **Esempio illustrativo (progetto studentesco).** YIN in tempo reale con hop di 10 ms e finestra di 25 ms; con finestre troppo corte "the YIN can barely work". — [Univ. Rochester, progetto studenti](https://hajim.rochester.edu/ece/sites/zduan/teaching/ece477/projects/2014/Minhao_Zhang_ReportFinal.pdf)
- **Esperienza pratica con gli onset.** Molti trigger risultavano in ritardo di più di una finestra FFT (circa 46 ms con 2048 campioni a 44,1 kHz), e finestre più piccole perdevano eventi. — [IRCAM forum](https://discussion.forum.ircam.fr/t/too-far-behind/2000)
- **Default di aubio.** `buf_size=1024`, `hop_size=512` a 44,1 kHz; YIN è uno dei metodi di pitch disponibili. — [aubio docs](https://aubio.readthedocs.io/en/latest/py_analysis.html). aubio 0.4.9 risale al 2019-02-08, è l'ultima release e non ha wheel: serve la build da sorgente. — [PyPI aubio](https://pypi.org/project/aubio/)
- **essentia.** L'ultima versione è 2.1b6.dev1438 (2026-05-19), con wheel macOS solo per cp314 e macOS 15 (x86_64 e arm64), quindi nessun wheel per Ventura. — [PyPI essentia](https://pypi.org/project/essentia/)
- **Mappe caotiche.** Bidlack, "Chaotic systems as simple (but complex) compositional algorithms", *CMJ* 16(3):33–47, 1992. Nella tesi del 1990 esamina i sistemi di Hénon e di Lorenz, la standard map e Hénon-Heiles come generatori di materiale. "the symbolic specification of many of these systems is simple to the point of apparent triviality, while their behavior can be extremely rich and complex". — [Bidlack, Music From Chaos](https://www.ex-tempore.org/bidlack/bidlack.htm); [tesi su eScholarship](https://escholarship.org/content/qt8p24s3v0/qt8p24s3v0.pdf)
- **Catene di Markov.** Ames, "The Markov Process as a Compositional Model: A Survey and Tutorial", *Leonardo* 22(2):175–187, 1989 (survey e tutorial). — [Project MUSE](https://muse.jhu.edu/article/601837/summary); [MIT OCW, bibliografia 21M.380](https://ocw.mit.edu/courses/21m-380-music-and-technology-algorithmic-and-generative-music-spring-2010/5b647dd599011daa3c1bc62e0c65f928_MIT21M_380S10_lec_ref.pdf)
- **Processi invece di eventi.** Wessel & Wright descrivono una metafora in cui il gesto controlla processi e non eventi: "each musical event can be precisely timed, regardless of the latency or jitter of the gestural interface. Once a given process is made audible, its rhythm is not dependent on the performer in an event-by-event way". — [Wessel & Wright](https://arxiv.org/pdf/2010.01570)
- **Seeding in NumPy.** NumPy permette di generare flussi casuali indipendenti e riproducibili: "spawn new (with very high probability) independent BitGenerator and Generator instances via their spawn() method". La probabilità di collisione è circa n² · 2⁻¹²⁸, e si raccomanda un seed a 128 bit via `secrets`. — [NumPy, parallel RNG](https://numpy.org/doc/stable/reference/random/parallel.html)
- **Seeding in PGE.** Ogni componente ha un seed derivato da `hashlib.sha256(f"{seed}:{stream_id}:{componente}")`, senza random globale. Senza `seed:` viene loggato un seed di sessione che rende il render "ricostruibile a posteriori". — [PGE docs/reference/yaml.md](/Users/giuliodemattia/github/DMGiulioRomano/PythonGranularEngine/docs/reference/yaml.md)

### Inferences
- **Catena adattiva per PGE.**
  - L'analisi gira in un thread o processo separato, con blocchi da 256 a 1024 campioni.
  - Feature: RMS in dB, flag e frequenza degli onset, f0 con confidenza, centroide spettrale.
  - Condizionamento: normalizzazione su range calibrati in sound-check, smoothing one-pole, isteresi a due soglie (come `schmitt` di libmapper), gate sulla confidenza del pitch.
  - Le feature entrano nello strato L2 come qualunque altra sorgente.
- **Budget di latenza (calcoli).** RMS: una finestra (da 10 a 50 ms secondo la scelta). Centroide: un frame FFT (1024 campioni a 44,1 kHz sono circa 23,2 ms; l'hop di 512 circa 11,6 ms). Pitch: circa 2 periodi della frequenza minima (25 ms per 80 Hz). Onset: hop più peak picking, in pratica anche oltre 40 ms secondo l'esperienza IRCAM. Ne segue che il controllo adattivo è adatto a parametri di tessitura (densità, dispersione, brillantezza), non a trigger ritmici sincroni.
- **Retroazione.** Se il microfono capta l'uscita di PGE, la catena si chiude in retroazione. Di Scipio la usa come principio compositivo; altrove servono gating, ducking o compensazione.
- **Generatori seminati.** I generatori (LFO, random walk, mappe logistica o di Hénon, catene di Markov sulle "regioni" di un preset) dovrebbero essere seminati con lo stesso schema per componente di PGE (sha256 di seed, stream e componente) e valutati in tempo logico, cioè all'onset del grano o su una griglia di controllo derivata dal tempo di score. Così il replay e il re-render sono deterministici.

### Gaps
- Ho letto di Verfaille et al. 2006 e di Di Scipio 2003 solo abstract e fonti secondarie. Non ho verificato il design DSP di Di Scipio (amplitude following che governa i parametri granulari), visto solo in fonti secondarie.
- Non ho trovato misure end-to-end della latenza di aubio o essentia in Python. Non ho valutato librerie alternative (librosa, pensata per l'offline; modelli neurali di pitch).
- Non ho cercato fonti per LFO e random walk come sorgenti di controllo: sono pratiche comuni ma qui restano senza citazione. Non ho verificato le mappe caotiche come generatori di segnali di controllo, oltre che di note.

## 5. Modelli ibridi envelope + live: offset/scala, tendency mask controllate live, time-warping del playhead, commutazione score/live per parametro

### Takeaway
Ci sono quattro pattern componibili, tutti con precedenti diretti:
- **(a) Envelope come baseline più offset o scala live.** È una modulation matrix; i modi Touch e Latch dell'automazione DAW definiscono la sostituzione temporanea o permanente.
- **(b) Live sui limiti delle tendency mask** (Koenig, Truax, CMask). Le bande `*_range` di PGE sono già tendency mask, quindi controllare live la loro larghezza o ancora è naturale.
- **(c) Time-warping del playhead dello score.** `time_mode: normalized` lo rende quasi gratuito.
- **(d) Commutazione score/live per parametro con crossfade** (`xset` e `fadeTime` di JITLib, rampa di ritorno di Logic).

ossia score dimostra la partitura interattiva, con trigger, condizioni e porzioni avviate fuori dalla timeline via OSC.

### Cited Findings
- **Tendency mask in CMask.** In CMask (Bartetzki, generatore stocastico per Csound) la tendency mask è "an area bounded by two time-variant boundaries": il valore del parametro (pitch, ampiezza, durata...) viene estratto a caso dentro il range consentito in quel momento, e l'applicazione principale sono tessiture e suoni granulari. La bibliografia del manuale cita come software affini il Project I e il Project II di Koenig e i programmi POD di Truax. — [CMask Manual](https://abartetzki.users.ak.tu-berlin.de/CMaskMan/CMask-Manual.htm)
- **Truax e la granulazione in tempo reale.** Truax realizzò la granulazione in tempo reale nel 1986 con "a digital signal processor controlled by a microcomputer" dentro l'ambiente PODX, usandola per *Riverrun*. Valori tipici: grani da 10 a 30 ms e densità "from several hundred to several thousand grains per second". — [Truax, Granular Synthesis](https://www.sfu.ca/~truax/gran.html). Truax studiò con G. M. Koenig all'Institute of Sonology di Utrecht nei primi anni '70. — [Canadian Encyclopedia](https://www.thecanadianencyclopedia.ca/en/article/barry-truax-emc)
- **Parametri ed envelope in PGE.**
  - Un parametro può essere scalare, envelope `[[t, v], ...]` (con interpolazione per segmento lineare, `cubic` o `step`), envelope di envelope, o banda `_range` (larghezza, `distribution_mode`, `range_anchor`, unità assoluta o relativa).
  - `time_mode: normalized` mappa 0..1 sulla durata.
  - La documentazione nota che con Csound "Le tendency mask restano stocastiche per natura". — [PGE docs/reference/yaml.md](/Users/giuliodemattia/github/DMGiulioRomano/PythonGranularEngine/docs/reference/yaml.md)
- **Modalità di automazione in Logic** (Logic Pro 9.1.6, fonte datata):
  - **Off:** "Off disables the current track automation data without deleting it".
  - **Read:** "automates the current track, using the existing automation data".
  - **Touch:** riproduce come Read; mentre il controllo è toccato, i movimenti sostituiscono i dati esistenti; al rilascio "the automation parameter returns to its original (recorded) value" con la Ramp Time delle preferenze.
  - **Latch:** "basically works like Touch mode, but the current value replaces any existing automation data" dopo il rilascio.
  - **Write:** "existing track automation data is erased as the playhead passes it".

  — [Apple, Logic Pro 9 automation modes](https://help.apple.com/logicpro/mac/9.1.6/en/logicpro/usermanual/chapter_28_section_2.html)
- **Combinazione di sorgenti in libmapper.** Un mapping convergente può sommare baseline e live con `y=x$0+_x$1`, dove la sorgente con `_` non scatena la valutazione; esistono anche variabili utente persistenti e un'inizializzazione eseguita una volta sola. — [libmapper, expression syntax](https://github.com/libmapper/libmapper/blob/main/doc/expression_syntax.md)
- **Cross-coupling come modulazione additiva.** Nell'esperimento di Hunt lo slider dell'ampiezza "was used to mildly offset the pitch". — [Hunt et al. 2002](https://zenodo.org/records/1176424/files/nime2002_088.pdf)
- **Commutazioni morbide in JITLib.** `xset` fa un crossfade verso i nuovi valori; `fadeTime` è "The overlap time between successive source replacements"; `quant` "quantizes replacement of sources". — [SuperCollider, NodeProxy](https://doc.sccode.org/Classes/NodeProxy.html)
- **ossia score.**
  - È un sequencer intermediale che invia e riceve OSC, MIDI, DMX, HTTP e seriale, ed espone OSCQuery. — [ossia.io](https://ossia.io)
  - I trigger fanno partire porzioni di partitura solo quando arriva un evento esterno. — [ossia, LAC 2020](https://lac2020.sciencesconf.org/315992/lac2020_ossia.pdf)
  - Una sezione può essere staccata dalla timeline e avviata più tardi, anche "in response to an OSC message". — [ossia score docs, out of time](https://ossia.io/score-docs/common-practices/3-out-of-time.html)
  - Le condizioni producono partiture ramificate, con un formalismo traducibile in reti di Petri o automi temporizzati. — [OSSIA (HAL)](https://hal.archives-ouvertes.fr/hal-01245957)
- **Transizioni temporizzate in TidalCycles.** Le transizioni "In" specificano in quanti cicli completare il passaggio (per esempio `xfadeIn`). — [TidalCycles, transitions](https://tidalcycles.org/docs/reference/transitions)

### Inferences
- **Formula unica per parametro.** Per ogni (stream, parametro) il valore usato dal grano all'onset t è:
  `p(t) = clamp(combine(base(τ(t)), live(t)))`, dove:
  - `base` è l'envelope YAML valutato al tempo di score τ;
  - `τ(t) = τ0 + ∫ rate(t) dt` è il playhead, con `rate` = 1 di default, controllabile live (rallentare, fermare, invertire) oppure sostituibile da una posizione di scrub;
  - `combine` è uno fra `replace`, `add` (offset in unità naturali: semitoni, secondi, grani/s), `mul` (scala, utile per la densità) o `mix(α)` (interpolazione fra baseline e live).
- **Stati della sorgente per parametro** (sul modello di Logic):
  - SCORE: solo baseline;
  - LIVE-TOUCH: il live sostituisce o modula mentre è attivo e al rilascio torna alla baseline con una rampa (Touch);
  - LIVE-LATCH: resta l'ultimo valore live (Latch);
  - RECORD: come Touch o Latch, ma registrando nel log (sezione 6).

  Le transizioni sono rampe valutate agli onset (sezione 3).
- **Tendency mask live.** `X` e `X_range` (con `range_anchor` e `distribution_mode`) sono parametri distinti, ciascuno con la propria baseline e il proprio controllo live. Un gesto di "apertura" allarga la banda di pitch o di durata; un altro sposta il centro. È la versione live della maschera di Koenig, Truax e CMask, senza nuovi concetti nello YAML.
- **Playhead e pointer sono due cose diverse.** Il playhead dello score è il tempo con cui si leggono gli envelope. Il pointer è la posizione di lettura nel campione, ed è a sua volta un parametro con baseline. Lo scrubbing del playhead sposta coerentemente tutti gli envelope di uno stream; uno scrub del pointer sposta solo la lettura del materiale. Con `time_mode: normalized` il playhead normalizzato 0..1 è la variabile da controllare.
- **Partiture "in attesa".** Il modello ossia (trigger e condizioni) suggerisce di permettere nello YAML segmenti che attendono un evento OSC prima di proseguire; è una possibile estensione futura.

### Gaps
- Non ho trovato letteratura accademica specifica su "envelope più offset live" nella sintesi granulare, solo la pratica DAW, synth e live coding.
- La modalità Trim di Logic (offset relativo sull'automazione esistente) è emersa solo da un riassunto di ricerca: non è verificata su una pagina Apple.
- Non sono accessibili i dettagli del controllo di PODX in tempo reale (range, preset, rampe; Truax 1988, CMJ).
- Non ho trattato il score following (Antescofo) come forma di time-warping guidato dall'esecutore.

## 6. Riproducibilità e stato: registrazione e replay dei flussi di controllo, export verso score/envelope, determinismo con random seminato, preset e snapshot

### Takeaway
Il controllo va registrato come eventi con timestamp in tempo assoluto, accompagnati dal tempo logico di score. Il formato può essere OSC su file con framing SLIP, come raccomandato da CNMAT, oppure JSONL. Il replay usa la forward synchronization, riscrivendo i timestamp con una trasformazione lineare.

Per il re-render offline a qualità più alta, i flussi registrati si convertono in envelope a breakpoint nello YAML di PGE.

Il determinismo ha tre requisiti:
- RNG seminato per componente (PGE lo ha già, con sha256 di seed, stream e componente);
- log completo di tutti gli ingressi live;
- valutazione in tempo logico, perché il jitter d'arrivo cambia quale grano vede quale valore.

Un limite è strutturale: se il re-render cambia la densità, cambia il numero di estrazioni casuali e l'identità campione per campione si perde. Si conserva la "stessa gestualità", non lo stesso segnale.

### Cited Findings
- **Registrazione su file (LAC 2010).**
  - Un file è "a type of serial stream", e per i file è raccomandato il framing SLIP, robusto a corruzione e troncamento.
  - "When a recorded stream of OSC messages is replayed, the original timestamps are rewritten according to a simple linear transformation".
  - Le registrazioni d'archivio "should always use absolute time values".
  - Il progetto OSC-StreamDB realizza registrazione, riproduzione e interrogazione multi-stream (/play, /filter, /index). — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **MIME type per i file OSC.** OSC 1.1 definisce `Content-type: application/osc; framing=slip version=1.0 | 1.1 uri=... types=...` e considera il file pointer come un trasporto seriale. — [Freed & Schmeder 2009](https://opensoundcontrol.stanford.edu/files/2009-NIME-OSC-1.1.pdf)
- **Risoluzione dei timetag.** Sono NTP a 64 bit con una precisione di circa 200 ps. — [OSC 1.0 spec](https://opensoundcontrol.stanford.edu/spec-1_0.html). In libmapper ogni aggiornamento ha un timetag (`t_x`, `mpr.Time()`). — [libmapper, expression syntax](https://github.com/libmapper/libmapper/blob/main/doc/expression_syntax.md); [libmapper Python tutorial](https://github.com/libmapper/libmapper/blob/main/doc/tutorials/tutorial_python.md)
- **Semantica di scrittura dell'automazione.** Write cancella al passaggio del playhead, Latch sostituisce dopo il rilascio, Touch sostituisce solo mentre il controllo è toccato. — [Apple, Logic Pro 9 automation modes](https://help.apple.com/logicpro/mac/9.1.6/en/logicpro/usermanual/chapter_28_section_2.html)
- **Spawn in NumPy.** Lo spawn di `SeedSequence` o `Generator` dà flussi indipendenti: "This feature lets you make local decisions about when and how to split up streams without coordination between processes". — [NumPy, parallel RNG](https://numpy.org/doc/stable/reference/random/parallel.html)
- **Seeding in PGE.**
  - `seed:` opzionale; senza seed viene loggato un seed di sessione.
  - RNG per componente da sha256 di seed, stream e componente; `rng_group` condivide la sequenza fra stream.
  - "draw dell'IOT async avviene per grano e dipende da `avg_iot`": con `density` diversa la sequenza diverge.
  - Il renderer Csound non è bit-identico nemmeno con il seed.
  - La cache stems include il seed nel fingerprint. — [PGE docs/reference/yaml.md](/Users/giuliodemattia/github/DMGiulioRomano/PythonGranularEngine/docs/reference/yaml.md)
- **Snapshot di parametri.** La Metasurface interpola snapshot di impostazioni dei parametri. — [Bencina 2005](https://nime.org/proc/nime2005_bencina/). In JITLib lo stato dei controlli vive nella NodeMap ("NodeProxy behaves like its NodeMap"), leggibile con `getKeysValues`, ed è il precedente di uno snapshot. — [SuperCollider, NodeProxy](https://doc.sccode.org/Classes/NodeProxy.html)

### Inferences
- **Formato di log consigliato** (JSONL o OSC+SLIP), un record per evento:
  - `wall_ns` (clock monotono), `score_t` (τ applicato), `stream_id`, `param`, `value`, `source` (performer/mic/gen/replay), `combine_mode`, `session_seed`, `config_hash`;
  - all'avvio uno snapshot completo dello stato (YAML risolto più valori live), poi keyframe periodici per poter saltare a un punto del log.

  Con la valutazione all'onset conviene registrare anche il valore effettivamente usato da ogni grano, almeno in debug: è la verità di riferimento per il replay esatto.
- **Export verso lo YAML.** Per ogni coppia (stream, parametro) si genera un envelope `[[τ_i, v_i], ...]`, con `step` per i cambi discreti e interpolazione lineare per le rampe, ottenute dal log.
  - I breakpoint vanno ridotti con una tolleranza (per esempio con un algoritmo di semplificazione di polilinee; scelta mia, non da fonte).
  - Anche le bande `_range` live diventano envelope di `X_range`, e il playhead deformato si "cuoce" ricampionando gli envelope sul nuovo asse τ.
  - Il risultato è uno YAML derivato (o un overlay) che il render offline NumPy o Csound può rendere a qualità più alta, con più voci, finestre migliori e un sample rate più alto.
- **Livelli di riproducibilità da dichiarare.**
  - (1) Replay esatto: stessa configurazione, stesso seed, stesso log in tempo logico, stessa densità.
  - (2) Re-render gestuale: stesse traiettorie, ma densità o voci diverse, quindi altre estrazioni casuali.
  - (3) Riproducibilità solo con NumPy, dato il limite noto di Csound.
- **Preset.** Un preset è uno snapshot dei parametri L2/L3 più le modalità di combinazione e lo stato score/live per parametro. Il seed non va interpolato: si conserva, oppure si esplicita che un preset reinizializza gli RNG.

### Gaps
- Non ho cercato strumenti Python mantenuti per registrare e riprodurre sessioni OSC (per esempio `oscdump`/`oscsend` di liblo) né lo stato attuale di OSC-StreamDB.
- Non ho verificato il salvataggio e caricamento dello stato in Open Stage Control, né i formati di gesto GDIF e SDIF.
- Non ho verificato come Sonic Pi rende deterministico il random in un sistema live (precedente potenzialmente utile).

## 7. Modifica live della configurazione (live coding / hot reload): JITLib, TidalCycles, Sonic Pi, Csound, e cosa servirebbe per l'hot reload di un YAML

### Takeaway
I sistemi di live coding convergono su quattro meccanismi:
- **(1)** rivalutare il codice sostituisce la definizione di un processo che continua a suonare;
- **(2)** la sostituzione è schedulata su un confine musicale (`quant` in JITLib, ciclo in Tidal, iterazione del `live_loop` in Sonic Pi);
- **(3)** un crossfade o una transizione copre la discontinuità (`fadeTime`, `xfadeIn`);
- **(4)** un lookahead di scheduling (in Sonic Pi 0,5 s di default secondo il forum) compra stabilità temporale.

Csound accetta nuovo codice d'orchestra via UDP durante la performance.

Per PGE, l'hot reload dello YAML segue una pipeline: watch del file (watchdog con FSEvents), parse e validazione fuori dal percorso audio, diff strutturale per `stream_id` (deepdiff), poi classificazione dei cambi:
- **applicabili live:** valori, envelope e bande, applicati dal grano successivo con rampa;
- **da crossfade:** sorgente audio, finestra, struttura delle voci; si istanzia un nuovo stream e si fa un crossfade;
- **da riavvio:** sample rate, canali, renderer.

### Cited Findings
- **JITLib / NodeProxy.**
  - `fadeTime`: "The overlap time between successive source replacements".
  - `quant`: "quantizes replacement of sources"; `clock` per "beat accurate replacement".
  - `schedAfterFade` esegue "fadeTime + latency seconds after the next timepoint on the quant grid".
  - Il setter di `source` "Play[s] a new synth through proxy and release/remove[s] any old ones".
  - Con `awake = false` "a change of the source does not start a new synth immediately"; `xset` fa un crossfade dei parametri. — [SuperCollider, NodeProxy](https://doc.sccode.org/Classes/NodeProxy.html)
- **TidalCycles.**
  - Le transizioni sono "functions for switching between patterns", con varianti "In" per il numero di cicli.
  - `anticipate` mette in coda un pattern "to be triggered (or dropped) in after 8 cycles".
  - `jumpIn` è "a sharp jump transition after the specified number of cycles", e `jumpIn'` transita solo al confine di ciclo.
  - Le firme variano fra le versioni. — [TidalCycles, transitions](https://tidalcycles.org/docs/reference/transitions); [Hackage, Sound.Tidal.Transition 1.9.2](https://hackage-content-origin.haskell.org/package/tidal-1.9.2/docs/Sound-Tidal-Transition.html)
- **Sonic Pi.**
  - I `live_loop` si ridefiniscono al volo: "whilst they're still running, you can change what they do". — [Sonic Pi tutorial A.02 (copia Debian)](https://sources.debian.org/src/sonic-pi/3.2.2~repack-7/etc/doc/tutorial/A.02-live-coding.md)
  - Il default di schedule-ahead è 500 ms, modificabile con `set_sched_ahead_time!`. Il codice modificato in un `live_loop` entra in vigore secondo il sched-ahead e la durata del ciclo (fonti di forum). — [in-thread.sonic-pi.net](https://in-thread.sonic-pi.net/t/expected-behavior-of-use-sched-ahead-time/7011); [in-thread, live loop reloaded](https://in-thread.sonic-pi.net/t/live-loop-reloaded-as-in-ableton-live/4725)
  - Il modello temporale formalizzato distingue tempo reale e "virtual time". — [Aaron, Orchard, Blackwell, FARM 2014](https://www.cs.kent.ac.uk/people/staff/dao7/publ/farm14-sonicpi.pdf)
- **Csound.**
  - Il server UDP si abilita con `--port=N`, anche senza CSD, e riceve codice d'orchestra. — [Csound manual, UDP server](https://csound.com/manual/overview/udp-server/)
  - Dalle API: `compileOrc(orc)` e `UDPServerStart(port)`; "the orchestra can be re-compiled at any time, or individual instruments can be compiled at any time, during a running performance". — [libcsound, Csound 6 API](https://libcsound.readthedocs.io/en/latest/csound6.html)
  - Il plugin VS Code invia ORC e SCO via UDP a 127.0.0.1:10000. — [Csound forum, VSCode plugin](https://forum.csound.com/t/csnd-ann-csound-vscode-plugin-0-3-0/319). cslc usa la porta 8099. — [cslc-csd](https://github.com/ErrorThink/cslc-csd). C'è anche csound-live-code di Steven Yi. — [npm @kunstmusik/csound-live-code](https://www.npmjs.com/package/@kunstmusik/csound-live-code)
- **watchdog.**
  - Versione 7.1.0 (2026-11-05), Python ≥ 3.11, wheel macOS x86_64 da cp311 a cp315.
  - Su macOS usa FSEvents e kqueue; con kqueue serve alzare il limite dei file descriptor.
  - Per il CPython free-threaded "a full thread safety audit has not been completed, in particular this affects the macOS FSEvents interface".
  - Il README ha una sezione sugli editor come Vim, che non modificano il file sul posto. — [PyPI watchdog](https://pypi.org/project/watchdog/)
- **deepdiff.** Versione 9.1.0 (2026-05-15), "Deep Difference and Search of any Python object/data. Recreate objects by adding adding deltas to each other". — [PyPI deepdiff](https://pypi.org/project/deepdiff/)
- **PGE già ragiona per stream.** `StreamCacheManager` calcola un fingerprint dello YAML per stream e "only dirty streams re-render" (con `STEMS=true CACHE=true RENDERER=csound`); il sistema di voci tiene `stream.voices` come unica fonte di verità. — [PGE CLAUDE.md](/Users/giuliodemattia/github/DMGiulioRomano/PythonGranularEngine/CLAUDE.md)

### Inferences
- **Pipeline di hot reload per PGE:**
  - (1) watch della directory, non del file, per gestire i salvataggi atomici per rename (caso Vim) con debounce di 100–300 ms;
  - (2) parse, `safe_eval` e validazione completa in un worker; se c'è un errore si mantiene la configurazione in esecuzione e si notifica la UI (come un errore di valutazione nel live coding);
  - (3) diff per `stream_id`, riusando il fingerprint per stream già esistente;
  - (4) classificazione e applicazione.
- **Live senza crossfade:** scalari, envelope, bande `_range`, probabilità. Si applicano al prossimo onset, con rampa se il valore corrente salta. Per un envelope cambiato sotto un playhead in corsa, il nuovo envelope si valuta al τ corrente.
- **Con crossfade di stream:** sorgente audio (il buffer si carica in background e si swappa all'onset), forma della finestra, numero o strategia delle voci, cambio di renderer per stream. Si istanziano un nuovo stream e un fade-out dello stream vecchio, come il `fadeTime` di JITLib, eventualmente quantizzati a un confine (per esempio la fine di un grano lungo o una griglia di score), come `quant`.
- **Strutturali:** aggiunta o rimozione di stream (fade-in o fade-out). Una durata modificata in `time_mode: normalized` richiede una decisione esplicita: conservare il τ assoluto o la posizione normalizzata.
- **Riavvio:** sample rate, numero di canali, backend audio.
- **Registrare ogni reload.** Un reload è un evento di controllo: hash della configurazione nel log della sezione 6, così il replay sa quale YAML era attivo a ogni istante.

### Gaps
- Non ho trovato un design pubblicato di "hot reload di una configurazione dichiarativa" per motori audio: la pipeline è inferita dai sistemi di live coding.
- Non ho verificato FaustLive e altri sistemi con hot-swap di DSP compilato.
- Il default di sched-ahead di Sonic Pi (0,5 s) viene da fonti di forum, non dalla documentazione ufficiale. Non ho verificato le firme correnti delle transizioni Tidal nell'ultima release.

## 8. Interfacce utente per il controllo: Open Stage Control, TouchOSC, web UI via WebSocket, GUI Python (Dear PyGui, Qt), separazione UI/audio

### Takeaway
Sull'Intel Ventura target la via più solida è una superficie OSC esterna che parla UDP al motore:
- **Open Stage Control:** GPLv3, v1.31.2, build macOS x64. Il repository GitHub è stato archiviato il 2026-09-23 e lo sviluppo prosegue su framagit.
- **TouchOSC:** commerciale, macOS 10.13+ Intel, OSC su UDP e TCP più MIDI.

Una UI nel browser richiede un ponte WebSocket ↔ OSC (websockets 17.4). Per la scoperta automatica dei parametri, OSCQuery espone il namespace via HTTP, con WebSocket opzionale.

Le GUI Python nello stesso processo hanno vincoli di packaging su questa macchina. Dear PyGui ha abbandonato i wheel macOS x86_64 dopo la 2.1.1 (novembre 2025). PySide6 6.12 richiede macOS 14, quindi su Ventura si resta a 6.11.x. In ogni caso la UI va tenuta fuori dal processo o dal thread che schedula i grani: comunica via OSC o FIFO lock-free.

### Cited Findings
- **Open Stage Control: identità e build.** "Libre and modular OSC / MIDI controller"; il repository è su framagit (jean-emmanuel/open-stage-control); la documentazione copre widget, custom module, configurazione MIDI e client/editor. — [Open Stage Control](https://openstagecontrol.ammd.net/). Ultima versione v1.31.2, con build macOS Intel (x64) e Apple Silicon (arm64), Linux (rpm, deb, zip), Windows (zip) e pacchetto Node.js; licenza GNU/GPLv3. — [Open Stage Control, download](https://openstagecontrol.ammd.net/download/)
- **Open Stage Control: GitHub archiviato.** Il repository GitHub è archiviato e in sola lettura dal 2026-09-23; la release v1.31.0 è "just a fake release to notify older versions that still check github for new releases". — [GitHub releases](https://github.com/jean-emmanuel/open-stage-control/releases)
- **TouchOSC.**
  - "next generation modular control surface": desktop su macOS 10.13+ (Intel e Apple silicon), Windows 7+ a 64 bit, Linux x86 e ARM64, più iOS e Android.
  - OSC su UDP e TCP; MIDI cablato, wireless e virtuale; TouchOSC Bridge.
  - Editor integrato con editing di rete sincronizzato; "lightweight and fast scripting engine" (il linguaggio non è nominato in pagina).
  - Licenza desktop una tantum; build desktop 1.5.4.269. — [hexler.net/touchosc](https://hexler.net/touchosc)
- **OSCQuery.**
  - La proposta Vidvox specifica un server HTTP 1.1 che espone il namespace; HTTP e WebSocket possono stare su porte diverse (HOST_INFO); la scoperta via Zeroconf usa `_oscjson._tcp`.
  - Lo scopo è generare automaticamente interfacce di controllo remoto, e la comunicazione bidirezionale con notifiche via WebSocket è opzionale. — [Vidvox OSCQueryProposal](https://github.com/Vidvox/OSCQueryProposal); [VIDVOX docs, VDMX OSCQuery](https://docs.vidvox.net/vdmx/vdmx_oscquery); [VVOSCQueryProtocol docs](https://www.vidvox.net/rays_oddsnends/VVOSCQueryProtocol_Doxygen/html/index.html)
  - ossia score supporta dispositivi OSCQuery. — [ossia score docs, OSCQuery device](https://ossia.io/score-docs/devices/oscquery-device.html)
  - Lato Python: pyossia è fermo alla 1.0.0-b10 (2017-11-03, "3 - Alpha") — [PyPI pyossia](https://pypi.org/project/pyossia/); il fork Python di tinyoscquery è dichiarato "work in progress" e non è su PyPI con quel nome. — [tinyoscquery PR](https://github.lionck.workers.dev/cyberkitsune/tinyoscquery/pull/3/files)
- **websockets.** Versione 17.4 (2026-10-30), Python ≥ 3.11, wheel macOS x86_64 disponibili. — [PyPI websockets](https://pypi.org/project/websockets/)
- **Dear PyGui.** La 2.3.1 (2026-05-01) ha wheel macOS solo `macosx_13_0_arm64` (cp310–cp314). L'ultima release con wheel x86_64 è la 2.1.1 (2025-11-14, `macosx_10_6_x86_64`, cp38–cp314); dalla 2.2 (2026-02-17) solo arm64. — [PyPI dearpygui](https://pypi.org/project/dearpygui/)
- **PySide6.**
  - 6.12.0 (2026-10-08): solo `macosx_14_0_universal2`, cioè macOS 14+.
  - 6.11.3 (2026-10-26) e il resto della serie 6.10/6.11: `macosx_13_0_universal2`, compatibili con Ventura.
  - 6.9.x: macOS 12+. — [PyPI PySide6](https://pypi.org/project/PySide6/)
- **Separazione fra thread.** Apple prescrive operazioni "real-time safe and nonblocking" nel `MIDIReceiveBlock` (thread ad alta priorità), con passaggio degli eventi a una coda consumata dal main thread. — [Apple, Incorporating MIDI 2](https://developer.apple.com/documentation/coremidi/incorporating-midi-2-into-your-apps). Le best practice OSC indicano double-buffering, code lock-free e memory barrier per l'atomicità. — [Schmeder et al. 2010](https://lac.linuxaudio.org/2010/papers/37.pdf)
- **GUI di mapping.** webmapper è la GUI di libmapper per creare e modificare mapping in rete. — [webmapper](https://github.com/libmapper/webmapper)

### Inferences
- **Architettura consigliata.**
  - Il processo motore (audio più scheduler dei grani) espone un namespace OSC su UDP localhost, eventualmente anche un endpoint OSCQuery per l'auto-discovery.
  - Le UI (Open Stage Control, TouchOSC, browser, GUI Python) sono processi separati e inviano solo messaggi di stato assoluto.
  - Il ritorno motore → UI (playhead τ, valori effettivi, meter, stato score/live per parametro) avviene a bassa frequenza, per esempio 20–30 Hz.
  - Così la UI non contende né il GIL né la CPU del thread che schedula i grani, e qualunque superficie OSC diventa intercambiabile.
- **Web UI.** Il browser non apre socket UDP, quindi serve un ponte Python (websockets o aiohttp) che traduca fra JSON (o OSC binario in frame WS) e OSC UDP. In alternativa si usa direttamente Open Stage Control, che è già un server web.
- **Vincoli di packaging su Intel Ventura.** Dear PyGui va fissato alla 2.1.1 con Python ≤ 3.14; PySide6 va mantenuto sotto 6.12. Una UI nel browser o una superficie esterna evita del tutto questi vincoli.
- **Superficie di riferimento.** Open Stage Control (gratuito, multipiattaforma, x64 disponibile) è la scelta per prototipare. Va però monitorato il trasloco su framagit, perché i tool che controllavano GitHub non vedranno più gli aggiornamenti.

### Gaps
- Non ho verificato il protocollo client/server di Open Stage Control (WebSocket), il salvataggio dello stato e la versione minima di macOS della build x64.
- Il linguaggio di scripting di TouchOSC (ampiamente riportato come Lua) non è confermato dalla pagina consultata.
- Non ho trovato benchmark di reattività delle GUI Python sotto carico audio. Il threading e il GIL sono fuori dal perimetro di questa ricerca e non sono stati approfonditi.
