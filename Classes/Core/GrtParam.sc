// Il valore di un parametro all'onset t, nel tempo dello stream.
// Risponde a value(t), come chiede GrtScheduler.
//   base:    numero, Env o funzione del tempo
//   control: lettore del segnale di controllo, risponde a value e dà 0..1
//   depth:   ampiezza della modulazione nelle unità del parametro
// Solo base: la base. Solo control: il valore grezzo del lettore (le unità le darà GrtMap).
// Tutti e due: base + depth · (controllo - 0,5).
GrtParam {
	var base, control, depth;

	*new { |base, control, depth = 0|
		^super.newCopyArgs(base, control, depth)
	}

	value { |t|
		^case
		{ control.isNil } { this.prLeggi(base, t) }
		{ base.isNil } { control.value }
		{ this.prLeggi(base, t) + (depth * (control.value - 0.5)) }
	}

	// su un Env value(t) restituisce l'Env stesso (lo fa ogni Object): si legge con at(t)
	prLeggi { |sorgente, t|
		^if(sorgente.isKindOf(Env)) { sorgente.at(t) } { sorgente.value(t) }
	}
}
