package net.stemmaweb.stemmaserver.benachmarktests;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

import net.stemmaweb.rest.Reading;
import net.stemmaweb.rest.Relation;
import net.stemmaweb.rest.Tradition;
import net.stemmaweb.rest.User;
import net.stemmaweb.rest.Witness;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import net.stemmaweb.services.GraphMLToNeo4JParser;
import net.stemmaweb.stemmaserver.JerseyTestServerFactory;
import net.stemmaweb.stemmaserver.OSDetector;

import org.junit.BeforeClass;
import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;

import com.carrotsearch.junitbenchmarks.annotation.AxisRange;
import com.carrotsearch.junitbenchmarks.annotation.BenchmarkMethodChart;

@AxisRange(min = 0, max = 1)
@BenchmarkMethodChart(filePrefix = "benchmark/benchmark-1000kNodes")
public class Benchmark1000kNodes extends BenachmarkTests {
	
	@BeforeClass
	public static void prepareTheDatabase(){

		/*
		 * Fill the Testbench with a nice graph 9 users 2 traditions 5 witnesses with degree 10
		 */
		initDatabase();
	}

	public static void initDatabase() {
		RandomGraphGenerator rgg = new RandomGraphGenerator();

		GraphDatabaseServiceProvider.setImpermanentDatabase();
		GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
		
		GraphDatabaseService db = dbServiceProvider.getDatabase();
		
		
		userResource = new User();
		traditionResource = new Tradition();
		witnessResource = new Witness();
		readingResoruce = new Reading();
		relationResource = new Relation();
		importResource = new GraphMLToNeo4JParser();
		
		jerseyTest = JerseyTestServerFactory.newJerseyTestServer()
				.addResource(userResource)
				.addResource(traditionResource)
				.addResource(witnessResource)
				.addResource(relationResource)
				.addResource(readingResoruce).create();
		try {
			jerseyTest.setUp();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		rgg.role(db, 10, 100, 10, 100);
		
		if (OSDetector.isWin())
			filename = "src\\TestXMLFiles\\ReadingstestTradition.xml";
		else
			filename = "src/TestXMLFiles/ReadingstestTradition.xml";
		
		try {
			tradId = importResource.parseGraphML(filename, "1").getEntity().toString().replace("{\"tradId\":", "").replace("}", "");
		} catch (FileNotFoundException f) {
			// this error should not occur
			assertTrue(false);
		}
		
		ExecutionEngine engine = new ExecutionEngine(db);
		ExecutionResult result = engine.execute("match (w:WORD {dn15:'showers'}) return w");
		Iterator<Node> nodes = result.columnAs("w");
		duplicateReadingNodeId = nodes.next().getId();

		result = engine.execute("match (w:WORD {dn15:'the root'}) return w");
		nodes = result.columnAs("w");
		theRoot = nodes.next().getId();
		
		result = engine.execute("match (w:WORD {dn15:'unto me'}) return w");
		nodes = result.columnAs("w");
		untoMe = nodes.next().getId();
		
	}
	
	
	
}
