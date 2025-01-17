package net.stemmaweb.parser;

import net.stemmaweb.rest.ERelations;
import net.stemmaweb.rest.Nodes;
import net.stemmaweb.rest.RelationType;
import net.stemmaweb.services.DatabaseService;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.Node;
import org.w3c.dom.*;

import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parser for the GraphML that Stemmarest itself produces.
 *
 * Created by tla on 17/02/2017.
 */
public class GraphMLParser {
    private GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
    private GraphDatabaseService db = dbServiceProvider.getDatabase();

    /**
     * Parses a GraphML file representing either an entire tradition, or a single tradition
     * section. Returns the ID of the object (either tradition or section) that was created.
     *
     * @param filestream - an InputStream with the XML data
     * @param traditionNode - a Node to represent the tradition this data belongs to
     * @return a Response object carrying a JSON dictionary {"parentId": <ID>}
     */

    public Response parseGraphML(InputStream filestream, Node traditionNode)
    {
        // We will use a DOM parser for this
        Document doc = Util.openFileStream(filestream);
        if (doc == null)
            return Response.serverError().entity("No document found").build();

        Element rootEl = doc.getDocumentElement();
        rootEl.normalize();

        // Get the data keys and their types; the map entries are e.g.
        // "dn0" -> ["neolabel", "string"]
        HashMap<String, String[]> dataKeys = new HashMap<>();
        NodeList keyNodes = rootEl.getElementsByTagName("key");
        for (int i = 0; i < keyNodes.getLength(); i++) {
            NamedNodeMap keyAttrs = keyNodes.item(i).getAttributes();
            String[] dataInfo = new String[]{keyAttrs.getNamedItem("attr.name").getNodeValue(),
                    keyAttrs.getNamedItem("attr.type").getNodeValue()};
            dataKeys.put(keyAttrs.getNamedItem("id").getNodeValue(), dataInfo);
        }

        String parentId = null;
        String parentLabel = null;
        ArrayList<Node> sectionNodes = new ArrayList<>();
        HashSet<String> sigla = new HashSet<>();
        // Keep track of XML ID to Neo4J ID mapping for all nodes
        HashMap<String, Node> entityMap = new HashMap<>();

        // Now get to work with node and relationship creation.
        try (Transaction tx = db.beginTx()) {
            // The UUID of the tradition that was passed in for parsing
            String tradId = traditionNode.getProperty("id").toString();
            NodeList entityNodes = rootEl.getElementsByTagName("node");
            for (int i = 0; i < entityNodes.getLength(); i++) {
                org.w3c.dom.Node entityXML = entityNodes.item(i);
                NamedNodeMap entityAttrs = entityXML.getAttributes();
                String xmlId = entityAttrs.getNamedItem("id").getNodeValue();
                NodeList dataNodes = ((Element) entityXML).getElementsByTagName("data");
                HashMap<String, Object> nodeProperties = returnProperties(dataNodes, dataKeys);
                if (!nodeProperties.containsKey("neolabel"))
                    return Response.status(Response.Status.BAD_REQUEST).entity("Node without label found").build();
                String neolabel = nodeProperties.remove("neolabel").toString();
                String[] entityLabel = neolabel.replace("[", "").replace("]", "").split(",\\s+");

                // If there is already a different tradition with this tradition ID, we are making a
                // duplicate and the real ID of this one was set in Root.java; if not, fix our tradition
                // node to match the one in the GraphML.
                if (neolabel.contains("TRADITION")) {
                    if (parentLabel != null) {
                        // We apparently have two TRADITION nodes. Abort.
                        // n.b. Old code, not sure if it was needed or just misunderstanding transactions
                        // entityMap.values().forEach(Node::delete);
                        // tx.success();
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity("Multiple TRADITION nodes in input").build();
                    }

                    String fileTraditionId = nodeProperties.get("id").toString();
                    Node existingTradition = db.findNode(Nodes.TRADITION, "id", fileTraditionId);
                    if (existingTradition == null) {
                        // Set the ID of the new tradition node to match the old ID.
                        traditionNode.setProperty("id", fileTraditionId);
                        tradId = fileTraditionId;
                    } // else there is another tradition with the original ID, so this is a duplicate
                    // and needs its new ID.

                    // This node is already created, but we need to reset its properties according to
                    // what is in the GraphML file. We also save this ID as the parent ID that was created.
                    for (String p : nodeProperties.keySet())
                        if (!p.equals("id"))
                            traditionNode.setProperty(p, nodeProperties.get(p));
                    parentId = tradId;
                    parentLabel = "tradition";
                    entityMap.put(xmlId, traditionNode);
                } else {
                    // Now we have the information of the XML, we can create the node.
                    Node entity = db.createNode();
                    for (String l : entityLabel)
                        entity.addLabel(Nodes.valueOf(l));
                    nodeProperties.forEach(entity::setProperty);
                    entityMap.put(xmlId, entity);
                    // Save section node(s), in case we are uploading individual sections and need to connect
                    // them to our tradition node
                    if (neolabel.contains("SECTION")) sectionNodes.add(entity);
                }
            }

            // Check the parent type
            if (parentLabel == null) // i.e. if it hasn't been set to "tradition"
                if (sectionNodes.size() == 0)
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Neither TRADITION nor SECTION found in input").build();
                else
                    parentLabel = "section";

            // If we have seen multiple sections but no tradition, error out.
            if (sectionNodes.size() > 1 && parentLabel.equals("section"))
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Multiple SECTION nodes but no TRADITION in input").build();


            // Next go through all the edges and create them between the nodes. Keep track of the
            // relation types we have seen.
            NodeList edgeNodes = rootEl.getElementsByTagName("edge");
            HashSet<String> seenRelationTypes = new HashSet<>();
            for (int i = 0; i < edgeNodes.getLength(); i++) {
                NamedNodeMap edgeAttrs = edgeNodes.item(i).getAttributes();
                String sourceXmlId = edgeAttrs.getNamedItem("source").getNodeValue();
                String targetXmlId = edgeAttrs.getNamedItem("target").getNodeValue();
                Node source = entityMap.get(sourceXmlId);
                Node target = entityMap.get(targetXmlId);
                NodeList dataNodes = ((Element) edgeNodes.item(i)).getElementsByTagName("data");
                HashMap<String, Object> edgeProperties = returnProperties(dataNodes, dataKeys);
                if (!edgeProperties.containsKey("neolabel"))
                    return Response.serverError().entity("Node without label found").build();
                String neolabel = edgeProperties.remove("neolabel").toString();
                // If this is a SEQUENCE relation, track the sigla so we can be sure the witnesses
                // exist (they are not exported for sections.)
                if (neolabel.equals("SEQUENCE")) {
                    for (String layer : edgeProperties.keySet()) {
                        sigla.addAll(Arrays.asList((String[]) edgeProperties.get(layer)));
                    }
                } else if (neolabel.equals("RELATED")) {
                    if (!edgeProperties.containsKey("type"))
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity("Relation defined without a type").build();
                    seenRelationTypes.add(edgeProperties.get("type").toString());
                }
                Relationship newRel = source.createRelationshipTo(target, ERelations.valueOf(neolabel));
                edgeProperties.forEach(newRel::setProperty);
            }

            // Connect our new section to an existing tradition node, and to the last existing section,
            // if this is a section-only upload.
            if (parentLabel.equals("section")) {
                Node newSection = sectionNodes.get(0);
                ArrayList<Node> existingSections = DatabaseService.getSectionNodes(tradId, db);
                assert (existingSections != null); // We should have already errored if this will be null.
                if (existingSections.size() > 0) {
                    Node lastExisting = existingSections.get(existingSections.size() - 1);
                    lastExisting.createRelationshipTo(newSection, ERelations.NEXT);
                }
                traditionNode.createRelationshipTo(newSection, ERelations.PART);
                parentId = String.valueOf(newSection.getId());
            }

            // Reset the section IDs stored on each reading to the ID of the newly created node
            for (Node r : entityMap.values().stream().filter(x -> x.hasLabel(Nodes.READING)).collect(Collectors.toList())) {
                String rSectId = r.getProperty("section_id").toString();
                r.setProperty("section_id", entityMap.get(rSectId).getId());
            }

            // Ensure that all witnesses we have encountered actually exist.
            for (String sigil : sigla) {
                Util.findOrCreateExtant(traditionNode, sigil);
            }

            // Ensure that all the relation types we have encountered actually exist.
            ArrayList<String> existingTypes = new ArrayList<>();
            traditionNode.getRelationships(ERelations.HAS_RELATION_TYPE, Direction.OUTGOING)
                    .forEach(x -> existingTypes.add(x.getEndNode().getProperty("name").toString()));
            for (String rtype : seenRelationTypes) {
                if (!existingTypes.contains(rtype)) {
                    Response rtResult = new RelationType(tradId, rtype).makeDefaultType();
                    if (rtResult.getStatus() == Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                        return rtResult;
                }
            }

            // Sanity check: if we created any relationship-less nodes, delete them again.
            entityMap.values().stream().filter(n -> !n.hasRelationship()).forEach(Node::delete);

            tx.success();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().build();
        }

        String response = String.format("{\"parentId\":\"%s\",\"parentLabel\":\"%s\"}", parentId, parentLabel);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }


    private HashMap<String, Object> returnProperties (NodeList dataNodes, HashMap<String, String[]> dataKeys) {
        HashMap<String, Object> nodeProperties = new HashMap<>();
        for (int j = 0; j < dataNodes.getLength(); j++) {
            org.w3c.dom.Node datumXML = dataNodes.item(j);
            String keyCode = datumXML.getAttributes().getNamedItem("key").getNodeValue();
            String keyVal = datumXML.getTextContent();
            String[] keyInfo = dataKeys.get(keyCode);
            Object propValue;
            // These datatypes need to be kept in sync with exporter.GraphMLExporter
            switch (keyInfo[1]) {
                case "boolean":
                    propValue = Boolean.valueOf(keyVal);
                    break;
                case "long":
                    propValue = Long.valueOf(keyVal);
                    break;
                case "stringarray":
                    propValue = keyVal.replace("[", "").replace("]", "").split(",\\s+");
                    break;
                default: // e.g. "string"
                    propValue = keyVal;

            }
            nodeProperties.put(keyInfo[0], propValue);
        }
        return nodeProperties;
    }

}
