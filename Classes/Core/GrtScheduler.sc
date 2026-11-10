// Gli onset di uno stream nel tempo logico, con densità e distribuzione alla Truax.
// density e distribution rispondono a value(t): un numero, una funzione, un GrtParam.
GrtScheduler {
	var density, distribution, silenceStep, next = 0, rng;
	// quante volte un valore fuori dominio è stato corretto:
	// distribuzione fuori da 0..1 o NaN, densità infinita
	var <clamps = 0;

	*new { |density = 1, distribution = 0, seed, silenceStep = 0.01|
		^super.new.init(density, distribution, seed, silenceStep)
	}

	init { |argDensity, argDistribution, seed, argSilenceStep|
		density = argDensity;
		distribution = argDistribution;
		silenceStep = argSilenceStep;
		rng = GrtRng(seed);
	}

	// gli onset in [cursore, t1); il cursore resta sul primo onset da t1 in poi,
	// così finestre contigue danno gli stessi onset di un passaggio unico
	onsetsUntil { |t1|
		var onsets = [];
		while { next < t1 } {
			var d = density.value(next);
			// inf darebbe un intervallo nullo e il cursore non avanzerebbe più
			if(d == inf) { clamps = clamps + 1 };
			if(d > 0 and: { d < inf }) {
				onsets = onsets.add(next);
				next = next + this.prIntervallo(next, d);
			} {
				// silenzio (anche NaN): si riprova a passo fisso, legato al tempo e non alla finestra
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
		// fuori da 0..1 gli intervalli possono diventare negativi, con NaN il cursore diventa NaN
		var d = if(grezza.isNaN) { 0 } { grezza.clip(0, 1) };
		if(d != grezza) { clamps = clamps + 1 };
		^(1 - d) * media + (d * rng.next * 2 * media)
	}
}
