# granulatore-rt

Granulazione in tempo reale del microfono in SuperCollider, con parametri guidati da envelope, da controlli dal vivo o da entrambi.

## Language

### Grani e stream

**Grano**:
Un frammento di suono letto dal buffer live con finestra, rapporto di lettura, ampiezza e pan fissati all'onset; non cambia dopo la nascita.
_Avoid_: particella, evento

**Onset**:
L'istante in cui un grano nasce e legge i propri parametri.
_Avoid_: trigger, attacco

**Stream**:
Una sorgente indipendente di grani con i propri parametri e la propria Routine di scheduling.
_Avoid_: layer, voce

**Voce**:
Una delle copie parallele di uno stream, con offset propri di onset, pitch, pointer e pan.
_Avoid_: stream, canale

**Densità**:
Il numero medio di grani al secondo di uno stream.
_Avoid_: rate, frequenza dei grani

**Distribuzione**:
La miscela fra onset sincroni (0) e asincroni (1) nel modello di Truax.
_Avoid_: jitter temporale, casualità

### Buffer live

**Buffer live**:
Il buffer circolare in cui il microfono viene scritto di continuo.
_Avoid_: delay line, sample

**Testina di scrittura**:
La posizione del buffer live in cui il campione corrente viene scritto.
_Avoid_: write pointer, record head

**Ritardo di lettura**:
La distanza nel passato, rispetto alla testina di scrittura, da cui un grano inizia a leggere.
_Avoid_: pointer, posizione

**Rapporto di lettura**:
La velocità a cui un grano legge il buffer live rispetto alla velocità di scrittura; sopra 1 traspone in su.
_Avoid_: pitch, speed

### Parametri e controllo

**Sorgente di parametro**:
Ciò che risponde al valore di un parametro all'onset: una costante, un envelope, un segnale di controllo o una loro combinazione.
_Avoid_: modulatore, input

**Segnale di controllo**:
Un valore con nome, normalizzato 0..1, aggiornato di continuo da un adapter.
_Avoid_: CC, control bus, modulazione

**Feature**:
Un segnale di controllo ricavato dall'analisi dell'audio d'ingresso.
_Avoid_: descrittore, metrica

**Adapter di feature**:
Un componente che produce feature da una tecnologia precisa: UGen native, FluCoMa o OSC esterno.
_Avoid_: estrattore, driver

**Mapping**:
La traduzione di un segnale di controllo nell'intervallo di un parametro, con curva e smoothing.
_Avoid_: scaling, conversione

**Banda**:
L'intervallo attorno al valore centrale di un parametro entro cui ogni grano estrae il proprio valore; è la tendency mask di Truax e Koenig.
_Avoid_: range, jitter, deviazione

**Finestra**:
La curva d'ampiezza applicata al grano, letta da una tabella del catalogo.
_Avoid_: envelope (riservato alla traiettoria di un parametro nel tempo)

**Envelope**:
La traiettoria scritta di un parametro nel tempo dello stream.
_Avoid_: finestra, curva
