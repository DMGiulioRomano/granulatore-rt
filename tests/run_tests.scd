// Runner headless dei UnitTest di una cartella. Si lancia con tests/run.sh.
(
var cartella = thisProcess.argv[0];
var dir, classes;

if(cartella.isNil or: { File.type(cartella) != \directory }) {
	"ERRORE: la cartella dei test % non esiste".format(cartella ? "(mancante)").postln;
	2.exit;
	// exit chiude il processo solo dopo che l'interprete è tornato: nessun codice dopo
	^nil
};
dir = File.realpath(cartella);
classes = UnitTest.allSubclasses.select { |c|
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
	if(metodi == 0) { "*** NESSUN TEST in % ***".format(cartella).postln };
	if(UnitTest.failures.isEmpty) { 0.exit } { 1.exit };
}, AppClock);
)
