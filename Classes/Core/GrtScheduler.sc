// Gli onset di uno stream nel tempo logico, con densità e distribuzione alla Truax.
// density e distribution rispondono a value(t): un numero, una funzione, un GrtParam.
GrtScheduler {
	var density, distribution, next;

	*new { |density = 1, distribution = 0, seed, start = 0|
		^super.new.init(density, distribution, seed, start)
	}

	init { |argDensity, argDistribution, seed, start|
		density = argDensity;
		distribution = argDistribution;
		next = start;
	}

	// gli onset in [cursore, t1); il cursore avanza fino a t1
	onsetsUntil { |t1|
		var onsets = [];
		while { next < t1 } {
			onsets = onsets.add(next);
			next = next + (1 / density.value(next));
		};
		^onsets
	}
}
