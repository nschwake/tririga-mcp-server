package com.tririga.custom;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tririga.custom.mcp.sample.server.config.TririgaApiConfig;
import com.tririga.custom.mcp.sample.server.model.WorkflowTracingStep;
import com.tririga.custom.mcp.sample.server.service.TririgaDatabaseService;
import com.tririga.custom.mcp.sample.server.service.TririgaSessionManager;
import com.tririga.custom.mcp.sample.server.service.TririgaWFAnalysisService;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class GraphTest {
    private Properties properties;

    @BeforeEach
    public void setUp() throws IOException {
        properties = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new IOException("Unable to find application.properties");
            }
            properties.load(is);
        }
    }

    @Test
    public void testManualLoad() {
        String user = properties.getProperty("mref.user");
            System.out.println(">>> Loaded mref.user: " + user);
        String pass = properties.getProperty("mref.user");
        String url = properties.getProperty("mref.user");
        assertNotNull(user);
         assertNotNull(pass);
          assertNotNull(url);
    }
    @Test
    public void testGraph() {

        TririgaApiConfig config = new TririgaApiConfig();
         String user = properties.getProperty("mref.user");
            
        String pass = properties.getProperty("mref.pass");
        String url = properties.getProperty("mref.url");
        config.setTririgaPass(pass);
        config.setTririgaUrl(url);
        config.setTririgaUser(user);

         
        HttpClient httpClient = HttpClient.newHttpClient();
        TririgaSessionManager sessionManager = new TririgaSessionManager(httpClient, config);
        
     
        TririgaDatabaseService databaseService = new TririgaDatabaseService(httpClient, config, sessionManager);
        
        TririgaWFAnalysisService wfAService = new TririgaWFAnalysisService(databaseService);


        DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> dag = wfAService.generateWorkflowTrace("triWorkTask - Synchronous -  triRevise");
        assertNotNull(dag);
        System.out.println("Returning DAG with: "+dag.vertexSet().size()+ " elements:");
        System.out.println(dag.toString());
         DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> dag2 = wfAService.generateWorkflowTrace("triWorkTask - Synchronous -  triRevise","WF_CALL_FLOW");
        assertNotNull(dag2);
        System.out.println("Returning DAG with: "+dag2.vertexSet().size()+ " elements:");
        System.out.println(dag2.toString());
        DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> dag3 = wfAService.generateCustomWorkflowTrace("triWorkTask - Synchronous -  triRevise","1","28","9");
        assertNotNull(dag3);
        System.out.println("Returning DAG with: "+dag3.vertexSet().size()+ " elements:");
        System.out.println(dag3.toString());

    }
}