// Il valore di un parametro all'onset t, nel tempo dello stream.
// Risponde a value(t), come chiede GrtScheduler.
GrtParam {
	var base;

	*new { |base|
		^super.newCopyArgs(base)
	}

	value { |t|
		^this.prLeggi(base, t)
	}

	// su un Env value(t) restituisce l'Env stesso (lo fa ogni Object): si legge con at(t)
	prLeggi { |sorgente, t|
		^if(sorgente.isKindOf(Env)) { sorgente.at(t) } { sorgente.value(t) }
	}
}
