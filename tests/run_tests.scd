// Runner headless dei UnitTest di una cartella. Si lancia con tests/run.sh.
(
var cartella = thisProcess.argv[0];
var dir = File.realpath(cartella);
var classes = UnitTest.allSubclasses.select { |c|
	// tutti e due i lati risolti: filenameSymbol tiene il path come è stato passato
	File.realpath(c.filenameSymbol.asString).beginsWith(dir +/+ "")
}.sort { |a, b| a.name < b.name };

fork({
	var metodi = 0;
	UnitTest.reset;
	classes.do { |c|
		c.setUpClass;
		c.findTestMethods.do { |m|
			var t = c.new;
			// UnitTest non cattura le eccezioni: qui diventano fallimenti, e tearDown gira lo stesso
			{ t.runTestMethod(m, false) }.try { |e|
				t.failed(m, e.errorString);
				{ t.tearDown }.try { |e2| t.failed(m, "tearDown: " ++ e2.errorString) };
			};
			metodi = metodi + 1;
		};
		c.tearDownClass;
	};
	UnitTest.failures.do { |r| Post << "FAIL: "; r.report };
	"RIEPILOGO %: classi %, metodi %, asserzioni passate %, fallite %".format(
		cartella, classes.size, metodi, UnitTest.passes.size, UnitTest.failures.size
	).postln;
	if(UnitTest.failures.isEmpty) { 0.exit } { 1.exit };
}, AppClock);
)
