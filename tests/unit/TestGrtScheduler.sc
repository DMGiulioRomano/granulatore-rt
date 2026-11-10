TestGrtScheduler : UnitTest {

	// intervalli fra onset consecutivi
	intervalli { |onsets| ^onsets.differentiate.drop(1) }

	test_sincrono_intervalli_pari_a_uno_su_densita {
		var onsets = GrtScheduler(density: 20, distribution: 0, seed: 1).onsetsUntil(1.0);
		// un array vuoto passerebbe il confronto a vuoto
		this.assert(onsets.size >= 19, "almeno 19 onset in un secondo a densità 20 (sono %)".format(onsets.size));
		this.assertArrayFloatEquals(this.intervalli(onsets), 1/20, "intervalli = 1/densità", within: 1e-9);
	}
}
