package org.janelia.flyem.neuprintprocedures;

import apoc.convert.Json;
import apoc.create.Create;
import apoc.refactor.GraphRefactoring;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.janelia.flyem.neuprinter.NeuPrinterMain;
import org.janelia.flyem.neuprinter.Neo4jImporter;
import org.janelia.flyem.neuprinter.SynapseMapper;
import org.janelia.flyem.neuprinter.model.BodyWithSynapses;
import org.janelia.flyem.neuprinter.model.Neuron;
import org.janelia.flyem.neuprinter.model.Skeleton;
import org.janelia.flyem.neuprinter.model.SortBodyByNumberOfSynapses;
import org.janelia.flyem.neuprinter.model.SynapseCounter;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.driver.v1.Config;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.types.Node;
import org.neo4j.driver.v1.types.Relationship;
import org.neo4j.harness.junit.Neo4jRule;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.neo4j.driver.v1.Values.parameters;

public class CleaveNeuronsTest {

    @Rule
    public Neo4jRule neo4j = new Neo4jRule()
            .withProcedure(ProofreaderProcedures.class)
            .withProcedure(GraphRefactoring.class)
            .withFunction(Json.class)
            .withProcedure(Create.class);

    //TODO: write tests for exception/error handling.

    @Test
    public void shouldCleaveNeurons() {
        String cleaveInstructionJson = "{\"Action\": \"cleave\", \"NewBodyId\": 5555, \"OrigBodyId\": 8426959, " +
                "\"NewBodySize\": 2778831, \"NewBodySynapses\": [" +
                "{\"Type\": \"pre\", \"Location\": [ 4287, 2277, 1542 ]}," +
                "{\"Type\": \"post\", \"Location\": [ 4222, 2402, 1688 ]}" +
                "]}";

        List<Neuron> neuronList = NeuPrinterMain.readNeuronsJson("src/test/resources/smallNeuronList.json");
        SynapseMapper mapper = new SynapseMapper();
        List<BodyWithSynapses> bodyList = mapper.loadAndMapBodies("src/test/resources/smallBodyListWithExtraRois.json");
        HashMap<String, List<String>> preToPost = mapper.getPreToPostMap();
        bodyList.sort(new SortBodyByNumberOfSynapses());

        File swcFile1 = new File("src/test/resources/8426959.swc");
        List<Skeleton> skeletonList = NeuPrinterMain.createSkeletonListFromSwcFileArray(new File[]{swcFile1});

        try (Driver driver = GraphDatabase.driver(neo4j.boltURI(), Config.build().withoutEncryption().toConfig())) {

            Session session = driver.session();
            String dataset = "test";

            Neo4jImporter neo4jImporter = new Neo4jImporter(driver);
            neo4jImporter.prepDatabase(dataset);

            neo4jImporter.addNeurons(dataset, neuronList);

            neo4jImporter.addConnectsTo(dataset, bodyList);
            neo4jImporter.addSynapsesWithRois(dataset, bodyList);
            neo4jImporter.addSynapsesTo(dataset, preToPost);
            neo4jImporter.addNeuronRois(dataset, bodyList);
            neo4jImporter.addSynapseSets(dataset, bodyList);
            neo4jImporter.createMetaNode(dataset);
            neo4jImporter.addAutoNames(dataset,0);
            neo4jImporter.addSkeletonNodes(dataset, skeletonList);

            List<Object> neurons = session.writeTransaction(tx ->
                    tx.run("CALL proofreader.cleaveNeuronFromJson($cleaveJson,\"test\") YIELD nodes RETURN nodes", parameters("cleaveJson", cleaveInstructionJson)).single().get(0).asList());

            Node neuron1 = (Node) neurons.get(0);
            Node neuron2 = (Node) neurons.get(1);

            Gson gson = new Gson();
            CleaveAction cleaveAction = gson.fromJson(cleaveInstructionJson, CleaveAction.class);

            //check properties on new nodes
            Assert.assertEquals(cleaveAction.getNewBodyId(), neuron1.asMap().get("bodyId"));
            Assert.assertEquals(cleaveAction.getNewBodySize(), neuron1.asMap().get("size"));
            Assert.assertEquals(1L, neuron1.asMap().get("pre"));
            Assert.assertEquals(1L, neuron1.asMap().get("post"));
            Assert.assertEquals(cleaveAction.getOriginalBodyId(), neuron2.asMap().get("bodyId"));
            Assert.assertEquals(14766999L, neuron2.asMap().get("size"));
            Assert.assertEquals(1L, neuron2.asMap().get("pre"));
            Assert.assertEquals(0L, neuron2.asMap().get("post"));
            Assert.assertEquals("Dm", neuron2.asMap().get("type"));
            Assert.assertEquals("final", neuron2.asMap().get("status"));

            //check labels
            Assert.assertTrue(neuron1.hasLabel("Neuron"));
            Assert.assertTrue(neuron1.hasLabel(dataset));
            String[] roiArray1 = new String[]{"seven_column_roi", "roiB", "roiA"};
            for (String roi : roiArray1) {
                Assert.assertTrue(neuron1.hasLabel(roi));
            }
            Assert.assertTrue(neuron2.hasLabel("Neuron"));
            Assert.assertTrue(neuron2.hasLabel(dataset));
            String[] roiArray2 = new String[]{"seven_column_roi", "roiA"};
            for (String roi : roiArray2) {
                Assert.assertTrue(neuron2.hasLabel(roi));
            }
            Assert.assertFalse(neuron2.hasLabel("roiB"));

            //should delete all skeletons
            Assert.assertFalse(session.run("MATCH (n:Skeleton) RETURN n").hasNext());
            Assert.assertFalse(session.run("MATCH (n:SkelNode) RETURN n").hasNext());

            //all properties on ghost node should be prefixed with "cleaved", all labels removed, only relationships to history node
            Node prevOrigNode = session.run("MATCH (n{cleavedBodyId:$bodyId}) RETURN n", parameters("bodyId", 8426959)).single().get(0).asNode();

            Map<String, Object> node1Properties = prevOrigNode.asMap();

            for (String propertyName : node1Properties.keySet()) {
                if (!propertyName.equals("timeStamp")) {
                    Assert.assertTrue(propertyName.startsWith("cleaved"));
                }
            }

            Assert.assertFalse(prevOrigNode.labels().iterator().hasNext());

            List<Record> prevOrigNodeRelationships = session.run("MATCH (n{cleavedBodyId:$bodyId})-[r]->() RETURN r", parameters("bodyId", 8426959)).list();
            List<Record> origNeuronHistoryNode = session.run("MATCH (n{bodyId:$bodyId})-[:From]->(h:History) RETURN h", parameters("bodyId", 8426959)).list();
            List<Record> newNeuronHistoryNode = session.run("MATCH (n{bodyId:$bodyId})-[:From]->(h:History) RETURN h", parameters("bodyId", 5555)).list();

            Assert.assertEquals(2, prevOrigNodeRelationships.size());
            Assert.assertEquals(1, origNeuronHistoryNode.size());
            Node historyNodeOrig = (Node) origNeuronHistoryNode.get(0).asMap().get("h");
            Node historyNodeNew = (Node) newNeuronHistoryNode.get(0).asMap().get("h");

            Relationship r1 = (Relationship) prevOrigNodeRelationships.get(0).asMap().get("r");
            Relationship r2 = (Relationship) prevOrigNodeRelationships.get(1).asMap().get("r");
            Assert.assertTrue(r1.hasType("CleavedTo") && r2.hasType("CleavedTo"));
            Assert.assertTrue( (r1.endNodeId() == historyNodeOrig.id() && r2.endNodeId() == historyNodeNew.id()) ||
                    (r2.endNodeId() == historyNodeOrig.id() && r1.endNodeId() == historyNodeNew.id()));

            //check connectsto relationships
            Long origTo26311Weight = session.run("MATCH (n{bodyId:8426959})-[r:ConnectsTo]->(m{bodyId:26311}) RETURN r.weight").single().get(0).asLong();
            Assert.assertEquals(new Long(1), origTo26311Weight);
            Long origTo2589725Weight = session.run("MATCH (n{bodyId:8426959})-[r:ConnectsTo]->(m{bodyId:2589725}) RETURN r.weight").single().get(0).asLong();
            Assert.assertEquals(new Long(1), origTo2589725Weight);
            Long origTo831744Weight = session.run("MATCH (n{bodyId:8426959})-[r:ConnectsTo]->(m{bodyId:831744}) RETURN r.weight").single().get(0).asLong();
            Assert.assertEquals(new Long(1), origTo831744Weight);

            Long newTo26311Weight = session.run("MATCH (n{bodyId:5555})-[r:ConnectsTo]->(m{bodyId:26311}) RETURN r.weight").single().get(0).asLong();
            Assert.assertEquals(new Long(2), newTo26311Weight);
            Long newTo2589725Weight = session.run("MATCH (n{bodyId:5555})-[r:ConnectsTo]->(m{bodyId:2589725}) RETURN r.weight").single().get(0).asLong();
            Assert.assertEquals(new Long(1), newTo2589725Weight);
            Long newTo831744Weight = session.run("MATCH (n{bodyId:5555})-[r:ConnectsTo]->(m{bodyId:831744}) RETURN r.weight").single().get(0).asLong();
            Assert.assertEquals(new Long(1), newTo831744Weight);

            //check synapseCountsPerRoi
            String newSynapseCountPerRoi = session.writeTransaction(tx ->
                    tx.run("MATCH (n:test{bodyId:5555}) RETURN n.synapseCountPerRoi").single().get(0).asString());
            String origSynapseCountPerRoi = session.writeTransaction(tx ->
                    tx.run("MATCH (n:test{bodyId:8426959}) RETURN n.synapseCountPerRoi").single().get(0).asString());
            Map<String,SynapseCounter> newSynapseCountMap = gson.fromJson(newSynapseCountPerRoi, new TypeToken<Map<String,SynapseCounter>>() {}.getType());
            Map<String,SynapseCounter> origSynapseCountMap = gson.fromJson(origSynapseCountPerRoi, new TypeToken<Map<String,SynapseCounter>>() {}.getType());

            Assert.assertEquals(3, newSynapseCountMap.keySet().size());
            Assert.assertEquals(1, newSynapseCountMap.get("roiA").getPre());
            Assert.assertEquals(1, newSynapseCountMap.get("roiB").getPost());

            Assert.assertEquals(2, origSynapseCountMap.keySet().size());
            Assert.assertEquals(0, origSynapseCountMap.get("seven_column_roi").getPost());
            Assert.assertEquals(1, origSynapseCountMap.get("roiA").getPre());

            //check synapse sets
            List<Record> origSynapseSetList = session.writeTransaction(tx ->
                    tx.run("MATCH (n:Neuron{bodyId:8426959})-[r:Contains]-(m:SynapseSet)-[:Contains]->(l:Synapse) RETURN m,count(l)").list());
            Assert.assertEquals(1, origSynapseSetList.size());
            Assert.assertEquals(1, origSynapseSetList.get(0).get("count(l)").asInt());

            List<Record> newSynapseSetList = session.writeTransaction(tx ->
                    tx.run("MATCH (n:Neuron{bodyId:5555})-[r:Contains]-(m:SynapseSet)-[:Contains]->(l:Synapse) RETURN m,count(l)").list());
            Assert.assertEquals(1, newSynapseSetList.size());
            Assert.assertEquals(2, newSynapseSetList.get(0).get("count(l)").asInt());

            //check that everything except Meta node has time stamp and all have dataset label except the cleaved ghost body
            Integer countOfNodesWithoutTimeStamp = session.readTransaction(tx -> {
                return tx.run("MATCH (n) WHERE (NOT exists(n.timeStamp) AND NOT n:Meta) RETURN count(n)").single().get(0).asInt();
            });
            Assert.assertEquals(new Integer(0), countOfNodesWithoutTimeStamp);
            Integer countOfNodesWithoutDatasetLabel = session.readTransaction(tx -> {
                return tx.run("MATCH (n) WHERE NOT n:test RETURN count(n)").single().get(0).asInt();
            });
            Assert.assertEquals(new Integer(1), countOfNodesWithoutDatasetLabel);

        }
    }

}
