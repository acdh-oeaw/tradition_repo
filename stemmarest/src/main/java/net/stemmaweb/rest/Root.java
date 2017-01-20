package net.stemmaweb.rest;

import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;
import net.stemmaweb.model.TraditionModel;
import net.stemmaweb.model.UserModel;
import net.stemmaweb.parser.CollateXParser;
import net.stemmaweb.parser.TEIParallelSegParser;
import net.stemmaweb.services.DatabaseService;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import net.stemmaweb.parser.GraphMLParser;
import net.stemmaweb.parser.TabularParser;
import org.neo4j.graphdb.*;

import javax.ws.rs.*;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The root of the REST hierarchy. Deals with system-wide collections of
 * objects.
 *
 * @author tla
 */
@Path("/")
public class Root {
    private GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
    private GraphDatabaseService db = dbServiceProvider.getDatabase();

    /**
     * Delegated API calls
     */

    @Path("/tradition/{tradId}")
    public Tradition getTradition(@PathParam("tradId") String tradId) {
        return new Tradition(tradId);
    }
    @Path("/user/{userId}")
    public User getUser(@PathParam("userId") String userId) {
        return new User(userId);
    }
    @Path("/reading/{readingId}")
    public Reading getReading(@PathParam("readingId") String readingId) {
        return new Reading(readingId);
    }
    @Path("/usernode")
    public UserNode getUserNode() {
        return new UserNode();
    }
    /*
     * Resource creation calls
     */

    /**
     * Imports a tradition by given GraphML file and meta data
     *
     * @return Http Response with the id of the imported tradition on success or
     *         an ERROR in JSON format
     * @throws XMLStreamException for bad XML data
     */
    @POST
    @Path("/tradition")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    public Response importGraphMl(@DefaultValue("") @FormDataParam("name") String name,
                                  @FormDataParam("filetype") String filetype,
                                  @FormDataParam("language") String language,
                                  @DefaultValue("LR") @FormDataParam("direction") String direction,
                                  @DefaultValue("a.c.") @FormDataParam("layerlabel") String layerlabel,
                                  @FormDataParam("public") String is_public,
                                  @FormDataParam("userId") String userId,
                                  @FormDataParam("empty") String empty,
                                  @FormDataParam("file") InputStream uploadedInputStream,
                                  @FormDataParam("file") FormDataContentDisposition fileDetail) throws IOException,
            XMLStreamException {

        if (!DatabaseService.userExists(userId, db)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Error: No user with this id exists")
                    .build();
        }

        if (fileDetail == null && uploadedInputStream == null && empty == null) {
            // No file to parse
            String response = "{\"error\":\"No file found\"}";
            return Response.status(Response.Status.BAD_REQUEST).entity(response).build();
        }

        String tradId;
        try {
            tradId = this.createTradition(name, direction, language, is_public, layerlabel);
            this.linkUserToTradition(userId, tradId);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(String.format("{\"error\":\"%s\"}", e.getMessage()))
                    .build();
        }

        // Return now if we have no file to parse
        if (empty != null)
            return Response.status(Response.Status.CREATED).entity("{\"tradId\":\"" + tradId + "\"}").build();

        // Otherwise, parse the file we have been given
        Response result = null;
        if (filetype.equals("csv"))
            // Pass it off to the CSV reader
            result = new TabularParser().parseCSV(uploadedInputStream, tradId, ',');
        if (filetype.equals("tsv"))
            // Pass it off to the CSV reader with tab separators
            result = new TabularParser().parseCSV(uploadedInputStream, tradId, '\t');
        if (filetype.startsWith("xls"))
            // Pass it off to the Excel reader
            result = new TabularParser().parseExcel(uploadedInputStream, tradId, filetype);
        if (filetype.equals("teips"))
            result = new TEIParallelSegParser().parseTEIParallelSeg(uploadedInputStream, tradId);
        // TODO we need to parse TEI double-endpoint attachment from CTE
        if (filetype.equals("collatex"))
            // Pass it off to the CollateX parser
            result = new CollateXParser().parseCollateX(uploadedInputStream, tradId);
        if (filetype.equals("graphml"))
            // Pass it off to the somewhat legacy GraphML parser
            result = new GraphMLParser().parseGraphML(uploadedInputStream, userId, tradId);
        // If we got this far, it was an unrecognized filetype.
        if (result == null)
            result = Response.status(Response.Status.BAD_REQUEST).entity("Unrecognized file type " + filetype).build();

        // If the result wasn't a success, delete the tradition node before returning the result.
        if (result.getStatus() > 201) {
            Tradition failedTradition = new Tradition(tradId);
            failedTradition.deleteTraditionById();
        }

        return result;
    }

    /*
     * Collection calls
     */

    /**
     * Gets a list of all the complete traditions in the database.
     *
     * @return Http Response 200 and a list of tradition models in JSON on
     *         success or Http Response 500
     */
    @GET
    @Path("/traditions")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    public Response getAllTraditions(@DefaultValue("false") @QueryParam("public") Boolean publiconly) {
        List<TraditionModel> traditionList = new ArrayList<>();

        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodeList;
            if (publiconly)
                nodeList = db.findNodes(Nodes.TRADITION, "is_public", true);
            else
                nodeList = db.findNodes(Nodes.TRADITION);
            nodeList.forEachRemaining(t -> traditionList.add(new TraditionModel(t)));
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.ok(traditionList).build();
    }

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    public Response getAllUsers() {
        List<UserModel> userList = new ArrayList<>();

        try (Transaction tx = db.beginTx()) {

            db.findNodes(Nodes.USER)
                    .forEachRemaining(t -> userList.add(new UserModel(t)));
            tx.success();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.ok(userList).build();
    }

    private String createTradition(String name, String direction, String language, String isPublic, String layerlabel)
            throws Exception {
        String tradId = UUID.randomUUID().toString();
        try (Transaction tx = db.beginTx()) {
            // Make the tradition node
            /*
            traditionNode = db.findNode(Nodes.TRADITION, "name", name);
            if (traditionNode != null) {
                tx.failure();
                throw new Exception("A tradition named '" + name + "' already exists!");
            }
            */
            Node traditionNode = db.createNode(Nodes.TRADITION);
            traditionNode.setProperty("id", tradId);
            traditionNode.setProperty("name", name);
            traditionNode.setProperty("direction", direction);
            traditionNode.setProperty("layerlabel", layerlabel);
            if (language != null) {
                traditionNode.setProperty("language", language);
            }
            if (isPublic != null) {
                traditionNode.setProperty("is_public", isPublic.equals("true"));
            }
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Could not create a new tradition!");
        }
        return tradId;
    }

    private void linkUserToTradition(String userId, String tradId) throws Exception {
        try (Transaction tx = db.beginTx()) {
            Node userNode = db.findNode(Nodes.USER, "id", userId);
            if (userNode == null) {
                tx.failure();
                throw new Exception("There is no user with ID " + userId + "!");
            }
            Node traditionNode = db.findNode(Nodes.TRADITION, "id", tradId);
            if (traditionNode == null) {
                tx.failure();
                throw new Exception("There is no tradition with ID " + tradId + "!");
            }
            userNode.createRelationshipTo(traditionNode, ERelations.OWNS_TRADITION);
            tx.success();
        }
    }
}