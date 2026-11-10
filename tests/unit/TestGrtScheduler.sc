TestGrtScheduler : UnitTest {

	// intervalli fra onset consecutivi
	intervalli { |onsets| ^onsets.differentiate.drop(1) }

	test_sincrono_intervalli_pari_a_uno_su_densita {
		var onsets = GrtScheduler(density: 20, distribution: 0, seed: 1).onsetsUntil(1.0);
		// un array vuoto passerebbe il confronto a vuoto
		this.assert(onsets.size >= 19, "almeno 19 onset in un secondo a densità 20 (sono %)".format(onsets.size));
		this.assertArrayFloatEquals(this.intervalli(onsets), 1/20, "intervalli = 1/densità", within: 1e-9);
	}

	// Truax: intervallo uniforme in [0, 2·media], non esponenziale
	test_asincrono_intervalli_in_zero_due_su_densita {
		var iot = this.intervalli(GrtScheduler(density: 10, distribution: 1, seed: 7).onsetsUntil(100));
		this.assert(iot.size > 500, "abbastanza intervalli (sono %)".format(iot.size));
		this.assert(iot.every { |x| x >= 0 and: { x <= (2/10) } }, "ogni intervallo in [0, 2/densità]");
		this.assert(iot.maxItem - iot.minItem > (1/10), "gli intervalli variano davvero");
	}

	test_asincrono_media_intervalli_uno_su_densita {
		// 250 s a densità 50 sono circa 12.500 onset: se ne tengono esattamente 10.000 intervalli
		var iot = this.intervalli(GrtScheduler(density: 50, distribution: 1, seed: 42).onsetsUntil(250)).keep(10000);
		this.assert(iot.size == 10000, "10.000 intervalli (sono %)".format(iot.size));
		this.assertFloatEquals(iot.mean, 1/50, "media entro l'1%", within: 0.01 / 50);
	}

	test_stesso_seed_stessi_onset_anche_con_altro_rand_in_mezzo {
		var a = GrtScheduler(density: 20, distribution: 1, seed: 99);
		var b = GrtScheduler(density: 20, distribution: 1, seed: 99);
		var onsetsA = a.onsetsUntil(5);
		var onsetsB = Array.fill(5, { |i| 1000.rand; b.onsetsUntil(i + 1) }).flatten;
		this.assertEquals(onsetsB, onsetsA, "il rand di altro codice non sposta la sequenza dello stream");
	}

	// la lezione di PGE: l'iteratore deve coincidere con il passaggio unico
	test_continuita_finestre_spezzate_uguali_al_passaggio_unico {
		var densita = { |t| 10 + (40 * t) };
		var unico = GrtScheduler(densita, 1, seed: 5).onsetsUntil(2);
		var due = GrtScheduler(densita, 1, seed: 5);
		var tante = GrtScheduler(densita, 1, seed: 5);
		this.assert(unico.size > 50, "abbastanza onset da confrontare (sono %)".format(unico.size));
		this.assertEquals(due.onsetsUntil(1) ++ due.onsetsUntil(2), unico, "[0,1) ++ [1,2) == [0,2)");
		// come una Routine che chiede un blocco di 10 ms alla volta
		this.assertEquals((1..200).collect { |i| tante.onsetsUntil(i / 100) }.flatten, unico,
			"200 finestre da 10 ms == [0,2)");
	}

	test_densita_nuova_vale_dall_onset_successivo {
		var onsets = GrtScheduler({ |t| if(t < 0.7) { 2 } { 100 } }, 0, seed: 1).onsetsUntil(1.05);
		this.assertFloatEquals(onsets[1], 0.5, "secondo onset a 0,5");
		this.assertFloatEquals(onsets[2], 1.0, "l'intervallo nato a 0,5 resta 1/2 anche se a 0,7 la densità cambia");
		this.assertFloatEquals(onsets[3] - onsets[2], 1/100, "dall'onset 1,0 vale la densità nuova", within: 1e-9);
	}

	// se andasse in ciclo infinito, la scadenza di tests/run.sh lo fermerebbe
	test_densita_zero_nessun_onset {
		var s = GrtScheduler(density: 0, distribution: 0.5, seed: 1);
		this.assertEquals(s.onsetsUntil(100), [], "nessun onset in [0, 100)");
	}

	// un controllo dal vivo che riporta su la densità fa ripartire lo stream entro un passo
	test_dopo_il_silenzio_riparte_entro_un_passo {
		// 1,003 non è multiplo di nessun passo plausibile: un passo più lungo di 10 ms si vede
		var onsets = GrtScheduler({ |t| if(t < 1.003) { 0 } { 10 } }, 0, seed: 1).onsetsUntil(2);
		this.assert(onsets.notEmpty, "lo stream riparte");
		this.assert(onsets.first >= 1.003 and: { onsets.first <= (1.003 + 0.01 + 1e-9) },
			"primo onset entro silenceStep (10 ms) dopo 1,003 s: è %".format(onsets.first));
	}

	// con d > 1 gli intervalli potrebbero essere negativi: onset che tornano indietro
	test_distribuzione_fuori_limiti_riportata_e_contata {
		var sopra = GrtScheduler(density: 10, distribution: 1.5, seed: 3);
		var sotto = GrtScheduler(density: 10, distribution: -0.5, seed: 3);
		var iotSopra = this.intervalli(sopra.onsetsUntil(20));
		var iotSotto = this.intervalli(sotto.onsetsUntil(20));
		var dentro;
		this.assert(iotSopra.every { |x| x >= 0 and: { x <= (2/10) } }, "1,5 vale 1: intervalli in [0, 2/densità]");
		this.assertArrayFloatEquals(iotSotto, 1/10, "-0,5 vale 0: intervalli sincroni", within: 1e-9);
		this.assert(sopra.clamps > 0 and: { sotto.clamps > 0 }, "il clamp si conta, non passa in silenzio");
		dentro = GrtScheduler(density: 10, distribution: 0.5, seed: 3);
		dentro.onsetsUntil(20);
		this.assertEquals(dentro.clamps, 0, "nei limiti nessun clamp");
	}

	// un valore dal vivo non finito non deve fermare lo stream né bloccarlo
	test_distribuzione_nan_vale_zero_e_si_conta {
		var s = GrtScheduler(density: 10, distribution: { 0/0 }, seed: 1);
		var primi = s.onsetsUntil(5);
		this.assertArrayFloatEquals(this.intervalli(primi), 1/10, "NaN vale 0: sincrono", within: 1e-9);
		this.assert(s.onsetsUntil(10).notEmpty, "lo stream non si spegne");
		this.assert(s.clamps > 0, "il NaN si conta");
	}

	// fill_factor / 0 dà inf: intervallo nullo, il cursore non avanzerebbe più
	test_densita_infinita_silenzio_contato {
		var s = GrtScheduler(density: inf, distribution: 0, seed: 1);
		this.assertEquals(s.onsetsUntil(1), [], "nessun onset, e la chiamata ritorna");
		this.assert(s.clamps > 0, "la densità infinita si conta");
	}

	test_seed_diverso_onset_diversi {
		var a = GrtScheduler(density: 20, distribution: 1, seed: 1).onsetsUntil(5);
		var b = GrtScheduler(density: 20, distribution: 1, seed: 2).onsetsUntil(5);
		this.assert(a != b, "seed diversi danno onset diversi");
	}
}
