// La banda di un parametro (tendency mask): ogni grano estrae il proprio valore
// in [center - width/2, center + width/2], con distribuzione uniforme.
// width e deviation: un numero, un Env, una funzione del tempo, un GrtParam.
// deviation è la probabilità che la banda si applichi (deviation_probability di PGE, in 0..1);
// il default 1 è la semantica range-only di PGE: una banda dichiarata vale per ogni grano.
GrtBand {
	var width, deviation, rng;

	*new { |width = 0, deviation = 1, seed|
		^super.new.init(width, deviation, seed)
	}

	init { |argWidth, argDeviation, seed|
		width = argWidth;
		deviation = argDeviation;
		rng = GrtRng(seed);
	}

	// due estrazioni a ogni chiamata, anche a gate chiuso: l'i-esima resta legata all'i-esimo grano.
	// Con rand < p, una deviation sopra 1 vale sempre, sotto 0 o NaN vale mai: nessun clamp serve.
	apply { |center, t|
		var gate = rng.next < GrtParam.read(deviation, t);
		var u = rng.next;
		^if(gate) { center + ((u - 0.5) * GrtParam.read(width, t)) } { center }
	}
}
