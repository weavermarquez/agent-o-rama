package com.rpl.agent.react;

import com.rpl.agentorama.AgentNode;
import com.rpl.agentorama.ToolInfo;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Factory class for creating tools that use Exa's MCP (Model Context Protocol) server.
 *
 * <p>This factory bridges LangChain4j's MCP client with agent-o-rama's ToolInfo abstraction,
 * allowing Exa's search capabilities to be used as tools within the agent framework.
 *
 * <p>The Exa MCP server runs as a local subprocess (via npx) and communicates with this Java code
 * through standard input/output. The server itself handles all HTTP communication with Exa's API.
 */
public class ExaMcpToolsFactory {

  private static McpClient mcpClient;

  /**
   * Creates a list of tools by connecting to the Exa MCP server and converting its tools to
   * ToolInfo objects.
   *
   * @param exaApiKey The Exa API key (will be passed to the MCP server as environment variable)
   * @return List of ToolInfo objects representing Exa's search capabilities
   */
  public static List<ToolInfo> createTools(String exaApiKey) {
    // Initialize MCP client if not already done
    if (mcpClient == null) {
      mcpClient = initializeMcpClient(exaApiKey);
    }

    // Fetch tool specifications from the Exa MCP server
    List<ToolSpecification> mcpTools = mcpClient.listTools();

    // Convert MCP tool specifications to ToolInfo objects
    List<ToolInfo> tools = new ArrayList<>();
    for (ToolSpecification toolSpec : mcpTools) {
      tools.add(createToolInfoFromMcp(toolSpec));
    }

    return tools;
  }

  /**
   * Extracts just the tool specifications for passing to the chat model.
   *
   * @param exaApiKey The Exa API key
   * @return List of ToolSpecification objects
   */
  public static List<ToolSpecification> createToolSpecifications(String exaApiKey) {
    return createTools(exaApiKey).stream()
        .map(ToolInfo::getToolSpecification)
        .collect(Collectors.toList());
  }

  /**
   * Initializes the MCP client that connects to the Exa MCP server.
   *
   * <p>The server is run as a subprocess using npx, with the EXA_API_KEY passed as an environment
   * variable.
   *
   * @param exaApiKey The Exa API key
   * @return Initialized MCP client
   */
  private static McpClient initializeMcpClient(String exaApiKey) {
    // Create stdio transport that runs the Exa MCP server as a subprocess
    McpTransport transport =
        new StdioMcpTransport.Builder()
            .command(List.of("npx", "-y", "exa-mcp-server"))
            .environment(Map.of("EXA_API_KEY", exaApiKey))
            .logEvents(true)
            .build();

    // Create and return the MCP client
    return new DefaultMcpClient.Builder()
        .key("exa")
        .transport(transport)
        .build();
  }

  /**
   * Converts an MCP tool specification to a ToolInfo object that can be used by agent-o-rama.
   *
   * <p>The resulting ToolInfo delegates tool execution to the MCP client, which handles
   * communication with the Exa MCP server.
   *
   * @param toolSpec The MCP tool specification
   * @return ToolInfo object wrapping the MCP tool
   */
  private static ToolInfo createToolInfoFromMcp(ToolSpecification toolSpec) {
    return ToolInfo.createWithContext(
        toolSpec,
        (AgentNode agentNode, Object unused, Map<String, Object> arguments) -> {
          try {
            // Convert arguments to JSON string for the ToolExecutionRequest
            String argumentsJson = convertArgumentsToJson(arguments);

            // Create a ToolExecutionRequest for the MCP client
            ToolExecutionRequest request =
                ToolExecutionRequest.builder()
                    .name(toolSpec.name())
                    .arguments(argumentsJson)
                    .build();

            // Execute the tool via the MCP client
            ToolExecutionResult result = mcpClient.executeTool(request);

            // Extract the result as string
            Object resultObj = result.result();
            return resultObj != null ? resultObj.toString() : "";
          } catch (Exception e) {
            return "Error executing " + toolSpec.name() + ": " + e.getMessage();
          }
        });
  }

  /**
   * Converts a map of arguments to a JSON string for MCP tool execution.
   *
   * @param arguments The arguments map
   * @return JSON string representation
   */
  private static String convertArgumentsToJson(Map<String, Object> arguments) {
    // Simple JSON construction - for production, consider using Jackson ObjectMapper
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : arguments.entrySet()) {
      if (!first) {
        json.append(",");
      }
      json.append("\"").append(entry.getKey()).append("\":");
      Object value = entry.getValue();
      if (value instanceof String) {
        json.append("\"").append(escapeJson((String) value)).append("\"");
      } else if (value instanceof Number || value instanceof Boolean) {
        json.append(value);
      } else {
        json.append("\"").append(value.toString()).append("\"");
      }
      first = false;
    }
    json.append("}");
    return json.toString();
  }

  /**
   * Escapes special characters in JSON strings.
   *
   * @param s The string to escape
   * @return Escaped string
   */
  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /** Closes the MCP client connection. Should be called on shutdown. */
  public static void close() {
    if (mcpClient != null) {
      try {
        mcpClient.close();
      } catch (Exception e) {
        // Log or ignore
      }
    }
  }
}
