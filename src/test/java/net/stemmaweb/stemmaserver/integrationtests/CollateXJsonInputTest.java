package net.stemmaweb.stemmaserver.integrationtests;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.TestCase;
import net.stemmaweb.model.ReadingModel;
import net.stemmaweb.model.SectionModel;
import net.stemmaweb.model.WitnessModel;
import net.stemmaweb.rest.ERelations;
import net.stemmaweb.services.DatabaseService;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import net.stemmaweb.stemmaserver.Util;
import org.codehaus.jettison.json.JSONObject;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.test.TestGraphDatabaseFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class CollateXJsonInputTest extends TestCase {

    private GraphDatabaseService db;
    private JerseyTest jerseyTest;

    private String tradId;
    private String sectId;


    public void setUp() throws Exception {
        super.setUp();
        db = new GraphDatabaseServiceProvider(new TestGraphDatabaseFactory().newImpermanentDatabase()).getDatabase();
        Util.setupTestDB(db, "1");

        // Create a JerseyTestServer for the necessary REST API calls
        jerseyTest = Util.setupJersey();

        ClientResponse jerseyResult = Util.createTraditionFromFileOrString(jerseyTest, "Tradition", "LR", "1",
                "src/TestFiles/Matthew-407.json", "cxjson");
        assertEquals(Response.Status.CREATED.getStatusCode(), jerseyResult.getStatus());
        tradId = Util.getValueFromJson(jerseyResult, "tradId");
        List<SectionModel> testSections = jerseyTest.resource().path("/tradition/" + tradId + "/sections")
                .get(new GenericType<List<SectionModel>>() {});
        sectId = testSections.get(0).getId();
    }

    public void testParseCollateX() throws Exception {
        // Check for correct number of readings and ranks
        List<ReadingModel> allreadings = jerseyTest.resource().path("/tradition/" + tradId + "/readings")
                .get(new GenericType<List<ReadingModel>>() {});
        assertEquals(381, allreadings.size());
        for (ReadingModel r: allreadings)
            if (r.getIs_end())
                assertEquals(87L, r.getRank(), 0);

        // Check for witnesses and their layers
        List<WitnessModel> allwits = jerseyTest.resource().path("/tradition/" + tradId + "/witnesses")
                .get(new GenericType<List<WitnessModel>>() {});
        assertEquals(22, allwits.size());


        // Dive into the database and check that there are no redundant witness paths
        try (Transaction tx = db.beginTx()) {
            List<Relationship> sequences = DatabaseService.returnTraditionSection(sectId, db).relationships()
                    .stream().filter(x -> x.getType().toString().equals("SEQUENCE")).collect(Collectors.toList());
            for (Relationship r : sequences) {
                if (r.hasProperty("witnesses")) {
                    ArrayList<String> mainwits = new ArrayList<>(Arrays.asList((String[]) r.getProperty("witnesses")));
                    for (String p : r.getPropertyKeys()) {
                        if (p.equals("witnesses")) continue;
                        for (String w : (String[]) r.getProperty(p)) {
                            if (mainwits.contains(w)) fail();
                        }
                    }
                }
            }
            tx.success();
        }

        // Check a witness text
        String witExpected = "Դրձեալ ի թվականուես հայոց. ի ն՟է՟. ամին. զօրաժողով լինէր ազգն արապաց. ուռհա և ամ եդեսացւոց " +
                "աշխարհն ահագին բազմուբ անցեալ ընդ մեծ գետն եփրատ. և եկեալ ի վր ամուր քաղաքին որ կոչի սամուսատ. և " +
                "ե֊լաներ ի պատերազմն զօրապետն հոռոմոց որում անուն ասէին պառա֊կամանոս. այր զօրաւոր և քաջ. և ի դուռն " +
                "քաղաքին բաղխէին զմիմեանս. և աւուր այնմիկ հարին տաճկունք զօրսն հոռոմոց և արարին կոտորած մեծ առ դրան " +
                "քաղաքին և յետ աւուրց ինչ առաւ քաղաքն սամուսատ մերձ ի քաղաքն ուռհա։";
        String witAcExpected = "Դրձեալ ի թվականուես հայոց. ի ն՟է՟. ամին. զօրաժողով լինէր ազգն արապաց. ուռհա և ամ եդեսացւոց " +
                "աշխարհն ահագին բազմուբ անցեալ ընդ մեծ գետն եփրատ. և եկեալ ի վր ամուր քաղաքին որ կոչի սամուսատ. և " +
                "ե֊լաներ ի պատերազմն զօրապետն հոռոմոց որում անուն ասէին պառա֊կամանոս. այր զօրաւոր և քաջ. և ի դուռն " +
                "քաղաքին բաղխէին զմիմեանս. և աւուր այնմիկ տաճկունք զօրսն հոռոմոց և արարին կոտորած մեծ առ դրան քաղաքին և " +
                "յետ աւուրց ինչ առաւ քաղաքն սամուսատ մերձ ի քաղաքն ուռհա։";
        String witObtained = Util.getValueFromJson(jerseyTest.resource()
                .path("/tradition/" + tradId + "/section/" + sectId + "/witness/G/text")
                .get(ClientResponse.class), "text");
        String witAcObtained = Util.getValueFromJson(jerseyTest.resource()
                .path("/tradition/" + tradId + "/section/" + sectId + "/witness/G/text")
                .queryParam("layer", "a.c.").get(ClientResponse.class), "text");
        assertEquals(witExpected, witObtained);
        assertEquals(witAcExpected, witAcObtained);

        // Check that page information etc. is preserved
        String firstPage = "004";
        String firstLine = "l102379579";
        String firstContext = "text/body/ab";
        List<ReadingModel> gReadings = jerseyTest.resource().path("/tradition/" + tradId + "/witness/G/readings")
                .get(new GenericType<List<ReadingModel>>() {});
        assertEquals(77, gReadings.size());
        JSONObject token = new JSONObject(gReadings.get(0).getExtra());
        assertEquals(firstPage, token.getJSONObject("G").getJSONObject("page").getString("n"));
        assertEquals(firstLine, token.getJSONObject("G").getJSONObject("line").getString("xml:id"));
        assertEquals(firstContext, token.getJSONObject("G").getString("context"));

        // Check that the display token is used in the .dot output, if it exists
        ClientResponse resp = jerseyTest.resource().path("/tradition/" + tradId + "/section/" + sectId + "/dot")
                .get(ClientResponse.class);
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        String dotStr = resp.getEntity(String.class);
        assertTrue(dotStr.contains("label=\"Դարձեալ\"];")); // no HTML
        assertTrue(dotStr.contains("label=\"&\"];")); // no HTML, no escaping
        assertTrue(dotStr.contains("label=<Դարձ<O>ել</O>>];")); // HTML, no quotes

        // Now check that everything works with normal forms turned on
        resp = jerseyTest.resource().path("/tradition/" + tradId + "/section/" + sectId + "/dot")
                .queryParam("show_normal", "true")
                .get(ClientResponse.class);
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        dotStr = resp.getEntity(String.class);
        assertTrue(dotStr.contains("label=<<FONT COLOR=\"red\">Դ</FONT>արձեալ>];")); // HTML, no normal form
        assertTrue(dotStr.contains("label=<Դ<O>ր</O>ձլ<BR/><FONT COLOR=\"grey\">Դարձեալ</FONT>>];")); // HTML, normal form
        assertTrue(dotStr.contains("label=\"Դարձեալ\"];"));  // no HTML, no normal form
        assertTrue(dotStr.contains("label=<Դարձեալ<BR/><FONT COLOR=\"grey\">դարձեալ</FONT>>];")); // no HTML, normal form
        assertTrue(dotStr.contains("label=<&amp;<BR/><FONT COLOR=\"grey\">և</FONT>>];")); // no HTML / HTML escape, normal form

    }

    public void testNoRedundantWitnesses() {
        Traverser sTrav = DatabaseService.returnTraditionSection(sectId, db);
        try (Transaction tx = db.beginTx()) {
            for (Relationship r : sTrav.relationships())
                if (r.getType().equals(ERelations.SEQUENCE) && r.hasProperty("witnesses")) {
                    Iterable<String> layers = r.getPropertyKeys();
                    for (String w : (String[]) r.getProperty("witnesses"))
                        for (String l : layers)
                            if (!l.equals("witnesses")) {
                                ArrayList<String> ew = new ArrayList<>(Arrays.asList((String[]) r.getProperty(l)));
                                assertFalse(ew.contains(w));
                            }
                }
            tx.success();
        }
    }

    public void testAddSection() {
        // Add another section
        String newSectId = Util.getValueFromJson(Util.addSectionToTradition(jerseyTest, tradId,
                "src/TestFiles/Matthew-401.json", "cxjson", "AM 401"), "parentId");
        ClientResponse response = jerseyTest.resource()
                .path("/tradition/" + tradId + "/section/" + sectId + "/orderAfter/" + newSectId)
                .put(ClientResponse.class);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        List<SectionModel> ourSections = jerseyTest.resource().path("/tradition/" + tradId + "/sections/")
                .get(new GenericType<List<SectionModel>>() {});
        assertEquals(2, ourSections.size());
        assertEquals(newSectId, ourSections.get(0).getId());
        assertEquals(sectId, ourSections.get(1).getId());
        assertEquals("AM 401", ourSections.get(0).getName());

        List<WitnessModel> allwits = jerseyTest.resource().path("/tradition/" + tradId + "/witnesses")
                .get(new GenericType<List<WitnessModel>>() {});
        assertEquals(23, allwits.size());
    }

    public void testSectionBySection() {
        FormDataMultiPart form = new FormDataMultiPart();
        // Make an empty tradition
        form.field("empty", "true");
        form.field("name", "ժամանակագրութիւն Մատթէոսի Ուռհայեցւոյ");
        form.field("userId", "1");
        String newTradId = Util.getValueFromJson(jerseyTest.resource().path("/tradition")
                .type(MediaType.MULTIPART_FORM_DATA_TYPE)
                .post(ClientResponse.class, form), "tradId");
        List<WitnessModel> allwits = jerseyTest.resource().path("/tradition/" + newTradId + "/witnesses")
                .get(new GenericType<List<WitnessModel>>() {});
        assertEquals(0, allwits.size());

        // Add the first section and check number of witnesses
        String firstSect = Util.getValueFromJson(Util.addSectionToTradition(jerseyTest, newTradId,
                "src/TestFiles/Matthew-401.json", "cxjson", "AM 401"), "parentId");
        allwits = jerseyTest.resource().path("/tradition/" + newTradId + "/witnesses")
                .get(new GenericType<List<WitnessModel>>() {});
        assertEquals(15, allwits.size());

        // Add the second section and do the same
        String secondSect = Util.getValueFromJson(Util.addSectionToTradition(jerseyTest, newTradId,
                "src/TestFiles/Matthew-407.json", "cxjson", "AM 407"), "parentId");
        allwits = jerseyTest.resource().path("/tradition/" + newTradId + "/witnesses")
                .get(new GenericType<List<WitnessModel>>() {});
        assertEquals(23, allwits.size());

        // Check section ordering
        List<SectionModel> ourSections = jerseyTest.resource().path("/tradition/" + newTradId + "/sections/")
                .get(new GenericType<List<SectionModel>>() {});
        assertEquals(2, ourSections.size());
        assertEquals(firstSect, ourSections.get(0).getId());
        assertEquals(secondSect, ourSections.get(1).getId());
        assertEquals("AM 401", ourSections.get(0).getName());
        assertEquals("AM 407", ourSections.get(1).getName());
    }

    /** For diagnostic use when parsing a section fails
    public void testSomething() throws Exception {
        String newSectId = Util.getValueFromJson(Util.addSectionToTradition(jerseyTest, tradId,
                "src/TestFiles/Matthew-418.json", "cxjson", "AM 401"), "parentId");
        ClientResponse response = jerseyTest.resource()
                .path("/tradition/" + tradId + "/section/" + sectId + "/orderAfter/" + newSectId)
                .put(ClientResponse.class);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    } **/

    public void tearDown() throws Exception {
        db.shutdown();
        jerseyTest.tearDown();
        super.tearDown();
    }
}
