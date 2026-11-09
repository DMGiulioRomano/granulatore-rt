# Le feature arrivano su bus con nome, da adapter intercambiabili

Ogni feature (forza, colore, attacchi, altezza, ...) è un bus di controllo con un nome, normalizzato 0..1. La scrive un adapter: UGen native di SuperCollider, FluCoMa, oppure una libreria esterna che ascolta il microfono per conto suo e manda `/grt/feature/<nome> <valore>` via OSC. Il granulatore legge solo i bus per nome e non sa quale adapter li alimenta. Così una libreria di feature extraction nuova si aggiunge scrivendo un adapter, senza toccare scheduler e mapping.

## Consequences

- FluCoMa è una dipendenza opzionale: senza, restano gli adapter nativi e OSC. I test che usano FluCoMa si saltano se le sue classi non ci sono.
- Gli adapter esterni via OSC hanno una latenza propria (cattura, analisi, rete), che si somma alla latenza del server: vanno dichiarati come sorgenti lente nel mapping.
- La normalizzazione 0..1 avviene nell'adapter, che conosce l'unità nativa della feature (dB, Hz, onset/s); il mapping lavora solo su 0..1.
