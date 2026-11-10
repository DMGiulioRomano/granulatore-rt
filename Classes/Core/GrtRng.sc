// Il generatore casuale di un componente (stream, banda): una Routine con il proprio seed.
// La Routine ha il suo stato, che il rand di altro codice non sposta.
// seed nil: un seed estratto dal thread chiamante.
GrtRng {
	var routine;

	*new { |seed|
		^super.new.init(seed)
	}

	init { |seed|
		routine = Routine { loop { 1.0.rand.yield } };
		routine.randSeed = seed ?? { 2147483647.rand };
	}

	// uniforme in [0, 1)
	next { ^routine.next }
}
