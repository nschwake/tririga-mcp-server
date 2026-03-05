# MREF OSLC MCP Server

> ⚠️ **Work in Progress** - This project is under active development. Testing is not yet complete. Use at your own risk in production environments.

A Model Context Protocol (MCP) server that provides AI assistants like Claude with direct access to IBM MREF's OSLC (Open Services for Lifecycle Collaboration) API, enabling intelligent facility management, work order creation, asset tracking, and location management through natural language.

## 🎯 What It Does

This MCP server exposes MREF's OSLC API as a collection of tools that Large Language Models can use to:

- **Query Resources**: Search for work tasks, locations, assets, people, and other MREF records
- **Create Records**: Generate work orders, tasks, and other records with proper workflow actions
- **Update Records**: Modify existing records with state transitions and field updates
- **Discover Schema**: Dynamically explore available resource types, fields, and capabilities
- **Execute Workflows**: Trigger MREF workflow actions like "Create Draft", "Complete", "Retire"
- **Manage Locations**: Search buildings, floors, spaces, and navigate location hierarchies
- **Track Assets**: Query and manage equipment, devices, and physical assets

The LLM can interact with MREF using natural language - no need to know OSLC syntax, MREF internals, or API details.

## 🏗️ Architecture

```
┌─────────────┐
│   Claude    │  Natural Language: "Create a work order for HVAC repair"
│     AI      │
└──────┬──────┘
       │ MCP Protocol
       │
┌──────▼──────────────────────────────┐
│   MREF OSLC MCP Server           │
│   ├─ Tool Discovery (findShape)     │
│   ├─ Schema Discovery (discover)    │
│   ├─ Query Tools (query, search)    │
│   ├─ CRUD Tools (create, update)    │
│   └─ Workflow Tools (actions)       │
└──────┬──────────────────────────────┘
       │ OSLC HTTP/REST
       │
┌──────▼──────────────────────────────┐
│   IBM MREF Platform              │
│   ├─ Work Management                │
│   ├─ Space & Location               │
│   ├─ Asset Management                │
│   └─ Custom Applications             │
└─────────────────────────────────────┘
```

## ✨ Key Features

### 🔍 **Smart Discovery**
- Automatically catalogs all OSLC service providers and resource shapes
- Discovers query capabilities, creation factories, and available fields
- Handles both built-in and custom MREF resources
- No hardcoded assumptions - adapts to any MREF configuration

### 🎨 **Multiple Query Capabilities**
Resources can have multiple specialized query endpoints (e.g., "Building Lookup", "Floor Lookup", "General Locations"). The server exposes all of them, letting the LLM choose the most appropriate one.

### 📦 **Response Optimization**
- Automatically parses verbose OSLC RDF/XML responses
- Removes redundant namespace declarations and boilerplate
- Reduces response size by ~75% on average
- Preserves ALL data - no truncation or limiting
- Returns clean, LLM-friendly formats

### 🔐 **Security**
- Supports user-scoped authentication (per-session credentials)
- XXE attack prevention in XML parsing
- Input validation on all parameters
- Comprehensive error handling and logging

### 🧩 **OSLC Compliant**
- Reads query capabilities from service catalog (not hardcoded patterns)
- Reads creation factories from service catalog
- Supports MREF's PATCH-via-POST update pattern
- Handles MREF-specific conventions (LR suffix for inline children)

## 🚀 Quick Start

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- Access to a MREF instance with OSLC enabled
- MREF user credentials with appropriate permissions

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/MREF-oslc-mcp-server.git
   cd MREF-oslc-mcp-server
   ```

2. **Configure MREF connection**
   
   Set environment variables:
   ```bash
   export MREF_URL=https://your-MREF-server.com
   export MREF_USER=your-username
   export MREF_PASS=your-password
   ```
   
   Or create `application.properties`:
   ```properties
   MREF_URL=https://your-MREF-server.com
   MREF_USER=your-username
   MREF_PASS=your-password
   ```

3. **Build the project**
   ```bash
   mvn clean package
   ```

4. **Run the server**
   ```bash
   java -jar target/MREF-oslc-mcp-server.jar
   ```

The server will start on `http://localhost:8080` by default.

### Using with Claude Desktop

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "MREF": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/MREF-oslc-mcp-server.jar"
      ],
      "env": {
        "MREF_URL": "https://your-MREF-server.com",
        "MREF_USER": "your-username",
        "MREF_PASS": "your-password"
      }
    }
  }
}
```

## 📚 Example Usage

Once connected, you can interact with MREF through natural language:

**Example 1: Create a Work Order**
```
User: "Create a work order to fix the HVAC system in Building A, due by end of week"

Claude: I'll create that work order for you.
→ Calls findShape("work task")
→ Calls discoverResource("triWorkTask") to see available fields
→ Calls createWorkTask(
    title="Fix HVAC System - Building A",
    description="HVAC repair needed",
    plannedStart="2026-03-04T08:00:00",
    plannedEnd="2026-03-07T17:00:00",
    action="Create Draft"
  )
→ Returns: Work order 147665710 created successfully
```

**Example 2: Search for Locations**
```
User: "Find all conference rooms on the 3rd floor"

Claude: Let me search for those conference rooms.
→ Calls discoverResource("triLocation")
→ Sees multiple query capabilities available
→ Calls queryByUrl(
    "http://host/oslc/spq/triFloorandSpaceLookupQC",
    "spi:triLevelNU=3"
  )
→ Returns: Found 5 conference rooms on floor 3
```

**Example 3: Update Work Task Status**
```
User: "Mark work task 147665710 as complete"

Claude: I'll complete that work task.
→ Calls readResource("triWorkTask", "147665710") to get current state
→ Calls getAvailableActions("triWorkTask", "147665710")
→ Sees available actions: ["Save", "Complete", "Cancel"]
→ Calls updateWorkTask(recordId="147665710", action="Complete")
→ Returns: Work task completed successfully
```

## 🛠️ Available Tools

The server provides 30+ tools organized into categories:

### Discovery & Schema
- `findShape` - Search for resource types by keyword
- `discoverResource` - Get complete field catalog for a resource
- `describeShape` - Get detailed shape information

### Query & Search
- `queryResource` - General resource query with filtering
- `queryByUrl` - Query using a specific query capability
- `searchWorkTasks` - Full-text search across work tasks
- `queryMyAssignedWorkTasks` - Get user's assigned tasks
- `lookupBuildings` - Search for buildings
- `lookupFloorsAndSpaces` - Search for floors and spaces

### CRUD Operations
- `createResource` - Create any MREF record
- `createWorkTask` - Convenience method for work tasks
- `readResource` - Fetch full record details
- `updateResource` - Update any MREF record
- `updateWorkTask` - Convenience method for work tasks
- `deleteResource` - Delete a record

### Workflow & Actions
- `getAvailableActions` - Get valid actions for a record's current state
- `getWorkTaskStatuses` - Get all valid status values
- `getPriorities` - Get all priority levels
- `getTaskTypes` - Get all task types

### Utilities
- `oslcFetch` - Fetch any OSLC URL directly
- `refreshCatalog` - Reload the service catalog
- `refreshShape` - Reload a specific resource shape

## 🏗️ Project Structure

```
src/main/java/com/microsoft/mcp/sample/server/
├── McpServerApplication.java          # Spring Boot main application
├── config/
│   └── StartupConfig.java            # Startup configuration
├── controller/
│   └── HealthController.java         # Health check endpoint
├── exception/
│   └── GlobalExceptionHandler.java   # Exception handling
├── oslc/
│   ├── OslcResponseParser.java       # XML response parser
│   ├── OslcServiceCatalog.java       # Service provider catalog
│   ├── OslcShape.java                # Resource shape parser
│   ├── OslcShapeEntry.java           # Catalog entry
│   ├── OslcProperty.java             # Field metadata
│   ├── OslcJsonBuilder.java          # JSON request builder
│   └── OslcCreateResult.java         # Creation response
└── service/
    ├── MREFOSLCService.java       # Main MCP service (30+ tools)
    └── CalculatorService.java        # Example service (unused)
```

## 🔧 Configuration

### Environment Variables / Application Properties

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `MREF_URL` | MREF base URL | Yes | - |
| `MREF_USER` | MREF username | Yes | - |
| `MREF_PASS` | MREF password | Yes | - |

### Logging

Configure logging in `src/main/resources/logback.xml`:

```xml
<configuration>
    <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.err</target>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="STDERR" />
    </root>
</configuration>
```

**Important**: For STDIO-based MCP servers, all logging MUST go to stderr, never stdout.

## 📊 Performance Optimizations

### Response Size Reduction
- **Query results**: ~75% reduction (XML → parsed format)
- **Single records**: ~20-40% reduction
- **Reference data**: ~80-95% reduction
- **All data preserved** - no truncation

Example: 130 status records
- Before: 42,000+ characters of XML
- After: ~8,000 characters of clean data
- Reduction: 81%

### HTTP Client Optimization
- Single shared HttpClient instance (connection pooling)
- 10-second connection timeout
- 30-second request timeout
- Automatic redirect following

### Caching
- Service catalog built once at startup
- Resource shapes cached after first load
- In-memory concurrent hash maps for thread safety

## 🧪 Testing Status

> ⚠️ **Testing Not Complete**

### ✅ Tested & Working
- Basic CRUD operations (create, read, update, delete)
- Query operations with filtering
- Resource discovery and schema inspection
- Multiple query capability support
- Response parsing and size reduction
- Error handling for common scenarios

### ⚙️ In Progress
- Full integration test suite
- Performance benchmarking
- Edge case handling
- Custom resource type testing
- Concurrent operation testing
- Session management testing

### 📋 TODO
- Unit test coverage (target: 80%+)
- Load testing
- Security audit
- Documentation review
- Production deployment guide

## 🐛 Known Issues

1. **Custom Resource Types**: Limited testing with customer-specific (cst-prefixed) resources
2. **Inline Child Creation**: Complex nested record creation needs more testing
3. **Large Result Sets**: Performance with 1000+ record queries needs optimization
4. **Session Cleanup**: Per-session credential cleanup not fully implemented

## 🤝 Contributing

Contributions are welcome! Please note this is a work in progress.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Built with [Spring AI](https://spring.io/projects/spring-ai) for MCP server capabilities
- Uses [Model Context Protocol](https://modelcontextprotocol.io/) for AI integration
- Designed for [IBM MREF](https://www.ibm.com/products/MREF) facility management platform

## 📞 Support

- **Issues**: Please report bugs and feature requests via [GitHub Issues](https://github.com/yourusername/MREF-oslc-mcp-server/issues)
- **Discussions**: For questions and discussions, use [GitHub Discussions](https://github.com/yourusername/MREF-oslc-mcp-server/discussions)

## 🗺️ Roadmap

- [ ] Complete test coverage
- [ ] Production hardening
- [ ] Advanced query builder
- [ ] Batch operations support
- [ ] Real-time notifications
- [ ] GraphQL support
- [ ] Docker containerization
- [ ] Kubernetes deployment examples
- [ ] OAuth2 authentication support
- [ ] Multi-tenant support

---

**Status**: 🚧 Work in Progress | **Version**: 0.1.0-alpha | **Last Updated**: March 2026
