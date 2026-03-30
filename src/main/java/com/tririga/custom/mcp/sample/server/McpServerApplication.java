package com.tririga.custom.mcp.sample.server;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.tririga.custom.mcp.sample.server.service.TririgaAdminAPIQueryTools;
import com.tririga.custom.mcp.sample.server.service.TririgaOSLCService;
import com.tririga.custom.mcp.sample.server.service.TririgaWFAnalysisService;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
    
    // @Bean
    // public ToolCallbackProvider calculatorTools(CalculatorService calculator) {
    //     return MethodToolCallbackProvider.builder().toolObjects(calculator).build();
    // }

    @Bean
    public ToolCallbackProvider tririgaOSLCTools(TririgaOSLCService tririgaOSLC) {
        return MethodToolCallbackProvider.builder().toolObjects(tririgaOSLC).build();
    }

    @Bean
    public ToolCallbackProvider tririgaQueryTools(TririgaAdminAPIQueryTools tririgaAdminAPIQuery) {
        return MethodToolCallbackProvider.builder().toolObjects(tririgaAdminAPIQuery).build();
    }

    @Bean
    public ToolCallbackProvider tririgaWFDevTools(TririgaWFAnalysisService tririgaWFDev) {
        return MethodToolCallbackProvider.builder().toolObjects(tririgaWFDev).build();
    }
}