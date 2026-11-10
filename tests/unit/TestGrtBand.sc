TestGrtBand : UnitTest {

	estrai { |banda, n, center = 300, t = 0| ^Array.fill(n, { banda.apply(center, t) }) }

	test_valori_dentro_la_banda_e_uniformi {
		var valori = this.estrai(GrtBand(width: 200, seed: 11), 10000);
		var classi = Array.fill(10, 0);
		var chi2;
		this.assert(valori.every { |x| x >= 200 and: { x <= 400 } }, "ogni valore in [300 - 100, 300 + 100]");
		valori.do { |x| var i = ((x - 200) / 20).floor.asInteger.clip(0, 9); classi[i] = classi[i] + 1 };
		chi2 = classi.sum { |o| (o - 1000).squared / 1000 };
		// gradi di libertà 9, α = 0,01: valore critico 21,67
		this.assert(chi2 < 21.67, "distribuzione uniforme: chi-quadro % sotto 21,67 (classi %)".format(chi2.round(0.01), classi));
	}

	// deviation_probability di PGE, qui in 0..1 invece che in 0..100
	test_deviation_zero_mai_uno_sempre {
		var deviati = { |p| this.estrai(GrtBand(width: 200, deviation: p, seed: 4), 10000).count { |x| x != 300 } };
		this.assertEquals(deviati.(0), 0, "deviation 0: la banda non si applica mai");
		this.assertEquals(deviati.(1), 10000, "deviation 1: sempre");
		this.assertFloatEquals(deviati.(0.3) / 10000, 0.3, "deviation 0,3: tre volte su dieci", within: 0.02);
	}
}
