// Gli onset di uno stream nel tempo logico, con densità e distribuzione alla Truax.
// density e distribution rispondono a value(t): un numero, una funzione, un GrtParam.
GrtScheduler {
	var density, distribution, next, rng;

	*new { |density = 1, distribution = 0, seed, start = 0|
		^super.new.init(density, distribution, seed, start)
	}

	init { |argDensity, argDistribution, seed, start|
		density = argDensity;
		distribution = argDistribution;
		next = start;
		// RNG proprio dello stream: una Routine ha il suo stato, che il rand di altro codice non sposta
		rng = Routine { loop { 1.0.rand.yield } };
		rng.randSeed = seed ?? { 2147483647.rand };
	}

	// gli onset in [cursore, t1); il cursore avanza fino a t1
	onsetsUntil { |t1|
		var onsets = [];
		while { next < t1 } {
			onsets = onsets.add(next);
			next = next + this.prIntervallo(next);
		};
		^onsets
	}

	// Truax: (1 - d) · media + d · U(0, 2 · media), letto all'onset t.
	// Si estrae a ogni onset anche con d = 0: l'i-esima estrazione resta legata all'i-esimo onset.
	prIntervallo { |t|
		var media = 1 / density.value(t);
		var d = distribution.value(t);
		^(1 - d) * media + (d * rng.next * 2 * media)
	}
}
