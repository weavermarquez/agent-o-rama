package com.rpl.agent.react;

import com.rpl.agentorama.*;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;

/**
 * ReAct (Reasoning and Acting) Agent Module using agent-o-rama framework with Exa search.
 *
 * <p>This module implements a conversational agent that can reason about user queries and take
 * actions using Exa's search tools via MCP (Model Context Protocol). It demonstrates the ReAct
 * pattern where the agent alternates between reasoning about what to do and taking actions.
 */
public class ReActModule extends AgentModule {

  @Override
  protected void defineAgents(AgentTopology topology) {
    topology.declareAgentObject("openai-api-key", System.getenv("OPENAI_API_KEY"));
    topology.declareAgentObject("exa-api-key", System.getenv("EXA_API_KEY"));

    topology.declareAgentObjectBuilder(
        "openai",
        (AgentObjectSetup setup) -> {
          String apiKey = (String) setup.getAgentObject("openai-api-key");
          return OpenAiChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").build();
        });

    // Create tools using Exa MCP
    String exaApiKey = System.getenv("EXA_API_KEY");
    topology.newToolsAgent("tools", ExaMcpToolsFactory.createTools(exaApiKey));

    topology.newAgent("ReActAgent")
            .node("chat", "chat", (AgentNode agentNode, List<Object> inputMessages) -> {
              // allow messages to be strings so this can be invoked more easily from the UI
              List<ChatMessage> messages = new ArrayList();
              for(Object m: inputMessages) {
                if(m instanceof String) {
                  messages.add(new UserMessage((String) m));
                } else {
                  messages.add((ChatMessage) m);
                }
              }
              ChatModel openai = agentNode.getAgentObject("openai");
              AgentClient tools = agentNode.getAgentClient("tools");

              String exaKey = (String) agentNode.getAgentObject("exa-api-key");
              ChatRequest request =
                  ChatRequest.builder()
                      .messages(messages)
                      .toolSpecifications(ExaMcpToolsFactory.createToolSpecifications(exaKey))
                      .build();
              ChatResponse response = openai.chat(request);
              AiMessage aiMessage = response.aiMessage();
              List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();

              if (toolCalls != null && !toolCalls.isEmpty()) {
                List<ToolExecutionResultMessage> toolResults = tools.invoke(toolCalls);

                List<ChatMessage> nextMessages = new ArrayList<>(messages);
                nextMessages.add(aiMessage);
                nextMessages.addAll(toolResults);
                agentNode.emit("chat", nextMessages);
              } else {
                agentNode.result(aiMessage.text());
              }
            });
  }
}
