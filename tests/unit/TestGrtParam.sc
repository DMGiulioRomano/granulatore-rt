TestGrtParam : UnitTest {

	test_costante_sempre_uguale {
		var p = GrtParam(base: 5);
		this.assertEquals([0, 1.5, 1000].collect { |t| p.value(t) }, [5, 5, 5], "5 a qualunque t");
	}

	// su un Env value(t) restituisce l'Env stesso: va letto con at(t)
	test_env_come_env_at_nel_tempo_dello_stream {
		var env = Env([0, 10], [2]);
		var p = GrtParam(base: env);
		this.assertArrayFloatEquals([0, 1, 2, 5].collect { |t| p.value(t) }, [0, 5, 10, 10],
			"come Env.at(t); dopo la fine resta il valore finale");
	}

	// è la ricetta di fill_factor: { |t| ff.value(t) / dur.value(t) }
	test_funzione_del_tempo {
		this.assertEquals(GrtParam(base: { |t| t * 3 }).value(2), 6, "la funzione riceve t");
	}
}
