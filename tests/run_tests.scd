// Runner headless dei UnitTest di una cartella. Si lancia con tests/run.sh.
// Esegue le sottoclassi di UnitTest definite sotto la cartella, stampa il riepilogo
// ed esce con 0 (tutto verde), 1 (fallimenti) o 2 (errore d'uso).
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
	// un errore in setUpClass o tearDownClass è un fallimento della classe, non un blocco del run
	var diClasse = { |c, selettore|
		{ c.perform(selettore); true }.try { |e|
			c.new.failed(c.class.findRespondingMethodFor(selettore), e.errorString);
			false
		}
	};

	UnitTest.reset;
	classes.do { |c|
		if(diClasse.(c, \setUpClass)) {
			c.findTestMethods.do { |m|
				var t = c.new;
				// UnitTest non cattura le eccezioni: qui diventano fallimenti, e tearDown gira lo stesso
				{ t.runTestMethod(m, false) }.try { |e|
					t.failed(m, e.errorString);
					{ t.tearDown }.try { |e2| t.failed(m, "tearDown: " ++ e2.errorString) };
				};
				metodi = metodi + 1;
			};
			diClasse.(c, \tearDownClass);
		};
	};

	if(UnitTest.failures.notEmpty) { "\nFALLIMENTI:".postln };
	UnitTest.failures.do { |r| Post << "  "; r.report(true) };
	"RIEPILOGO %: classi %, metodi %, asserzioni passate %, fallite %".format(
		cartella, classes.size, metodi, UnitTest.passes.size, UnitTest.failures.size
	).postln;
	if(metodi == 0) { "*** NESSUN TEST in % ***".format(cartella).postln };
	if(UnitTest.failures.isEmpty) { 0.exit } { 1.exit };
}, AppClock);
)
