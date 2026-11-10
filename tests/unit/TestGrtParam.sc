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

	// il lettore finto sta al posto di { bus.getSynchronous }: nessun server
	test_solo_controllo_ultimo_valore_del_lettore {
		var scritto = 0.25;
		var p = GrtParam(control: { scritto });
		this.assertEquals(p.value(0), 0.25, "l'ultimo valore scritto");
		scritto = 0.8;
		this.assertEquals(p.value(0), 0.8, "segue il lettore quando cambia");
	}

	// il controllo modula attorno alla base; a 0,5 è neutro
	test_base_piu_modulazione {
		var c = 0.5;
		var p = GrtParam(base: 1000, control: { c }, depth: 400);
		var valori = [0, 0.5, 1].collect { |x| c = x; p.value(0) };
		this.assertArrayFloatEquals(valori, [800, 1000, 1200], "base + depth · (controllo - 0,5)");
	}

	test_clamp_dichiarato_e_contato {
		var c = 1;
		var p = GrtParam(base: 1000, control: { c }, depth: 4000, min: 20, max: 2000);
		this.assertEquals(p.value(0), 2000, "3000 riportato al massimo");
		c = 0;
		this.assertEquals(p.value(0), 20, "-1000 riportato al minimo");
		this.assertEquals(p.clamps, 2, "ogni clamp si conta");
		c = 0.5;
		p.value(0);
		this.assertEquals(p.clamps, 2, "nei limiti nessun clamp");
	}
}
