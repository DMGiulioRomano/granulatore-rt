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

	// un bus non ancora scritto o un messaggio OSC sbagliato: il parametro torna alla base
	test_controllo_nan_vale_neutro_e_si_conta {
		var modulato = GrtParam(base: 1000, control: { 0/0 }, depth: 400);
		var solo = GrtParam(control: { 0/0 });
		this.assertEquals(modulato.value(0), 1000, "NaN vale 0,5: la base");
		this.assertEquals(solo.value(0), 0.5, "da solo vale 0,5");
		this.assert(modulato.clamps == 1 and: { solo.clamps == 1 }, "il NaN si conta");
	}

	// { ~forza } prima che ~forza esista: il lettore dà nil
	test_controllo_nil_vale_neutro_e_si_conta {
		var p = GrtParam(base: 1000, control: { nil }, depth: 400);
		this.assertEquals(p.value(0), 1000, "nil vale 0,5: la base");
		this.assertEquals(p.clamps, 1, "il nil si conta");
	}

	// un Env nudo vale come sorgente ovunque, non solo come base
	test_env_accettato_in_ogni_sorgente {
		var banda = GrtBand(width: Env([0, 10], [1]), deviation: Env([1, 1], [1]), seed: 1);
		var onsets = GrtScheduler(Env([10, 10], [1]), Env([0, 0], [1]), seed: 1).onsetsUntil(1);
		var valori = Array.fill(200, { banda.apply(0, 0.5) });
		this.assert(valori.every { |x| x.abs <= 2.5 } and: { valori.asSet.size > 1 },
			"larghezza Env a t = 0,5 vale 5: valori diversi in [-2,5, 2,5]");
		this.assertArrayFloatEquals(onsets.differentiate.drop(1), 1/10, "densità e distribuzione Env", within: 1e-9);
	}

	// stesso protocollo value(t) dello scheduler: un GrtParam ne è la densità senza adattatori
	test_grtparam_come_densita_dello_scheduler {
		var densita = GrtParam(base: Env([10, 10], [1]));
		var onsets = GrtScheduler(densita, 0, seed: 1).onsetsUntil(1);
		this.assert(onsets.size >= 9, "onset generati (sono %)".format(onsets.size));
		this.assertArrayFloatEquals(onsets.differentiate.drop(1), 1/10, "intervalli 1/10", within: 1e-9);
	}

	test_deviation_e_seed_arrivano_alla_banda {
		var mai = GrtParam(base: 300, band: 200, deviation: 0, seed: 1);
		var a = GrtParam(base: 300, band: 200, deviation: 0.5, seed: 6);
		var b = GrtParam(base: 300, band: 200, deviation: 0.5, seed: 6);
		this.assert(Array.fill(500, { mai.value(0) }).every(_ == 300), "deviation 0: sempre la base");
		this.assertEquals(Array.fill(500, { a.value(0) }), Array.fill(500, { b.value(0) }), "stesso seed, stessa sequenza");
	}

	// l'ordine di PGE: centro, gate, banda, poi il clamp di sicurezza
	test_banda_poi_clamp {
		var p = GrtParam(base: 0, band: 10, deviation: 1, min: -1, max: 1, seed: 3);
		var valori = Array.fill(1000, { p.value(0) });
		this.assert(valori.every { |x| x >= -1 and: { x <= 1 } }, "ogni valore nei limiti");
		this.assert(valori.asSet.size > 2, "la banda si applica: non solo i due estremi");
		this.assert(p.clamps > 0, "il clamp dopo la banda si conta");
	}
}
