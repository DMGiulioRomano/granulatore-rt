// Il valore di un parametro all'onset t, nel tempo dello stream.
// Risponde a value(t), come chiede GrtScheduler.
//   base:      numero, Env o funzione del tempo
//   control:   lettore del segnale di controllo, risponde a value e dà 0..1
//   depth:     ampiezza della modulazione nelle unità del parametro
//   band:      larghezza della banda (GrtBand), deviation: probabilità che si applichi
//   min/max:   il clamp dichiarato; ogni correzione si conta in clamps
// Solo base: la base. Solo control: il valore grezzo del lettore (le unità le darà GrtMap).
// Tutti e due: base + depth · (controllo - 0,5).
// L'ordine è quello di PGE: centro, gate e banda, poi il clamp di sicurezza.
GrtParam {
	var base, control, depth, min, max, banda;
	var <clamps = 0;

	*new { |base, control, depth = 0, min = -inf, max = inf, band, deviation = 1, seed|
		^super.new.init(base, control, depth, min, max, band, deviation, seed)
	}

	init { |argBase, argControl, argDepth, argMin, argMax, band, deviation, seed|
		base = argBase;
		control = argControl;
		depth = argDepth;
		min = argMin;
		max = argMax;
		if(band.notNil) { banda = GrtBand(band, deviation, seed) };
	}

	value { |t|
		var centro = this.prCentro(t);
		^this.prLimita(if(banda.isNil) { centro } { banda.apply(centro, t) })
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
