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
		var iot = this.intervalli(GrtScheduler(density: 50, distribution: 1, seed: 42).onsetsUntil(200));
		this.assert(iot.size > 9999, "almeno 10.000 intervalli (sono %)".format(iot.size));
		this.assertFloatEquals(iot.mean, 1/50, "media entro l'1%", within: 0.01 / 50);
	}
}
