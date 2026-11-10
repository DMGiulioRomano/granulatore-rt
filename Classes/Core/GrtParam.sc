// Il valore di un parametro all'onset t, nel tempo dello stream.
// Risponde a value(t), come chiede GrtScheduler.
//   base:    numero, Env o funzione del tempo
//   control: lettore del segnale di controllo, risponde a value e dà 0..1
//   depth:   ampiezza della modulazione nelle unità del parametro
//   min/max: il clamp dichiarato; ogni correzione si conta in clamps
// Solo base: la base. Solo control: il valore grezzo del lettore (le unità le darà GrtMap).
// Tutti e due: base + depth · (controllo - 0,5).
GrtParam {
	var base, control, depth, min, max;
	var <clamps = 0;

	*new { |base, control, depth = 0, min = -inf, max = inf|
		^super.newCopyArgs(base, control, depth, min, max)
	}

	value { |t|
		^this.prLimita(this.prCentro(t))
	}

	prCentro { |t|
		^case
		{ control.isNil } { this.prLeggi(base, t) }
		{ base.isNil } { this.prControllo }
		{ this.prLeggi(base, t) + (depth * (this.prControllo - 0.5)) }
	}

	// un controllo che non dà un numero (bus non ancora scritto, OSC sbagliato) vale 0,5,
	// il neutro: il parametro torna alla base invece di diventare NaN
	prControllo {
		var c = control.value;
		^if(c.isNaN) { clamps = clamps + 1; 0.5 } { c }
	}

	prLimita { |x|
		var limitato = x.clip(min, max);
		if(limitato != x) { clamps = clamps + 1 };
		^limitato
	}

	// su un Env value(t) restituisce l'Env stesso (lo fa ogni Object): si legge con at(t)
	prLeggi { |sorgente, t|
		^if(sorgente.isKindOf(Env)) { sorgente.at(t) } { sorgente.value(t) }
	}
}
