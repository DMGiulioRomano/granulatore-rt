// Gli onset di uno stream nel tempo logico, con densità e distribuzione alla Truax.
// density e distribution rispondono a value(t): un numero, una funzione, un GrtParam.
GrtScheduler {
	var density, distribution, silenceStep, next, rng;
	var <clamps = 0;  // quante volte la distribuzione è stata riportata in 0..1

	*new { |density = 1, distribution = 0, seed, start = 0, silenceStep = 0.01|
		^super.new.init(density, distribution, seed, start, silenceStep)
	}

	init { |argDensity, argDistribution, seed, start, argSilenceStep|
		density = argDensity;
		distribution = argDistribution;
		silenceStep = argSilenceStep;
		next = start;
		// RNG proprio dello stream: una Routine ha il suo stato, che il rand di altro codice non sposta
		rng = Routine { loop { 1.0.rand.yield } };
		rng.randSeed = seed ?? { 2147483647.rand };
	}

	// gli onset in [cursore, t1); il cursore resta sul primo onset da t1 in poi,
	// così finestre contigue danno gli stessi onset di un passaggio unico
	onsetsUntil { |t1|
		var onsets = [];
		while { next < t1 } {
			var d = density.value(next);
			if(d > 0) {
				onsets = onsets.add(next);
				next = next + this.prIntervallo(next, d);
			} {
				// silenzio: si riprova a passo fisso, legato al tempo e non alla finestra (continuità)
				next = next + silenceStep;
			};
		};
		^onsets
	}

	// Truax: (1 - d) · media + d · U(0, 2 · media), letto all'onset t.
	// Si estrae a ogni onset anche con d = 0: l'i-esima estrazione resta legata all'i-esimo onset.
	prIntervallo { |t, densita|
		var media = 1 / densita;
		var grezza = distribution.value(t);
		// fuori da 0..1 gli intervalli possono diventare negativi: si riporta nei limiti e si conta
		var d = grezza.clip(0, 1);
		if(d != grezza) { clamps = clamps + 1 };
		^(1 - d) * media + (d * rng.next * 2 * media)
	}
}
