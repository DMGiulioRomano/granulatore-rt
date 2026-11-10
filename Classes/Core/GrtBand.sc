// La banda di un parametro (tendency mask): ogni grano estrae il proprio valore
// in [center - width/2, center + width/2], con distribuzione uniforme.
// width risponde a value(t): un numero, una funzione, un GrtParam.
GrtBand {
	var width, rng;

	*new { |width = 0, seed|
		^super.new.init(width, seed)
	}

	init { |argWidth, seed|
		width = argWidth;
		rng = Routine { loop { 1.0.rand.yield } };
		rng.randSeed = seed ?? { 2147483647.rand };
	}

	apply { |center, t|
		^center + ((rng.next - 0.5) * width.value(t))
	}
}
