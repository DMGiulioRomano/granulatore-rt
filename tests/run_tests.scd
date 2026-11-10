// Runner headless dei UnitTest di una cartella. Si lancia con tests/run.sh.
(
var dir = File.realpath(thisProcess.argv[0]);
var classes = UnitTest.allSubclasses.select { |c|
	// tutti e due i lati risolti: filenameSymbol tiene il path come è stato passato
	File.realpath(c.filenameSymbol.asString).beginsWith(dir +/+ "")
};

fork({
	UnitTest.reset;
	classes.do { |c| c.run(false, false) };
	UnitTest.failures.do { |r| Post << "FAIL: "; r.report };
	if(UnitTest.failures.isEmpty) { 0.exit } { 1.exit };
}, AppClock);
)
