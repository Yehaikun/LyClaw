package lyjew.com.lyclaw.adapter.deepseek;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.client.ModelApiClient;
import lyjew.com.lyclaw.dto.request.OpenAIRequest;
import lyjew.com.lyclaw.dto.response.OpenAIResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.parser.ParserImpl.OpenAIResponseParser;
import lyjew.com.lyclaw.template.AbstractModelAdapter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DeepSeekOpenAIAdapter extends AbstractModelAdapter {

    private static final String ENDPOINT = "/chat/completions";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private final ModelApiClient httpClient;
    private final OpenAIResponseParser responseParser;
    private final ObjectMapper objectMapper;

    public DeepSeekOpenAIAdapter(ModelApiClient httpClient,
                                 OpenAIResponseParser responseParser,
                                 ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProvider() {
        return "deepseek-openai";
    }

    @Override
    protected String getDefaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    @Override
    protected String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 2.5);
    }

    @Override
    public boolean validate() {
        if (!isConfigured()) {
            return false;
        }
        try {
            ChatRequest testRequest = ChatRequest.builder()
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content("hi")
                            .build()))
                    .maxTokens(1)
                    .build();

            ModelResponse response = chat(testRequest);
            return response != null
                    && response.getUsage() != null
                    && response.getUsage().getTotalTokens() > 0;
        } catch (Exception e) {
            log.warn("[{}] 连接验证失败: {}", getProvider(), e.getMessage());
            return false;
        }
    }

    @Override
    protected Object buildRequest(ChatRequest request) {
        return buildOpenAIRequest(request, request.isStream());
    }

    @Override
    protected Object buildStreamRequest(ChatRequest request) {
        return buildOpenAIRequest(request, true);
    }

    private OpenAIRequest buildOpenAIRequest(ChatRequest request, boolean stream) {
        String modelName = (request.getModel() != null && !request.getModel().isEmpty())
                ? request.getModel() : this.model;

        OpenAIRequest.OpenAIRequestBuilder builder = OpenAIRequest.builder()
                .model(modelName)
                .stream(stream);

        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            builder.maxTokens(request.getMaxTokens());
        }
        if (request.getTemperature() != null) {
            builder.temperature(clampTemperature(request.getTemperature(), 0.0, 2.0));
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            builder.stop(request.getStopSequences());
        }
        builder.messages(buildMessages(request));
        if (request.hasTools()) {
            builder.tools(buildTools(request.getTools()));
            builder.toolChoice(resolveToolChoice(request));
        }
        if (request.isThinkingEnabled()) {
            builder.thinking(OpenAIRequest.Thinking.builder()
                    .type("enabled")
                    .build());
            builder.reasoningEffort(
                    request.getThinkingBudget() != null && request.getThinkingBudget() > 8000
                            ? "high" : "medium");
        }

        return builder.build();
    }

    private List<OpenAIRequest.Message> buildMessages(ChatRequest request) {
        List<OpenAIRequest.Message> messages = new ArrayList<>();

        if (request.hasSystemPrompt()) {
            OpenAIRequest.Message systemMsg = new OpenAIRequest.Message();
            systemMsg.setRole("system");
            systemMsg.setContent(request.getSystemPrompt());
            messages.add(systemMsg);
        }

        for (Message msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) {
                continue;
            }

            OpenAIRequest.Message oaiMsg = new OpenAIRequest.Message();
            oaiMsg.setRole(msg.getRole());
            oaiMsg.setContent(msg.getContent());

            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null
                    && !msg.getToolCallId().isEmpty()) {
                oaiMsg.setToolCallId(msg.getToolCallId());
            } else if ("tool".equals(msg.getRole()) && msg.getToolCalls() != null
                    && !msg.getToolCalls().isEmpty()) {
                oaiMsg.setToolCallId(msg.getToolCalls().get(0).getToolCallId());
            }

            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null
                    && !msg.getToolCalls().isEmpty()) {
                List<OpenAIRequest.ToolCall> toolCalls = msg.getToolCalls().stream()
                        .map(tc -> {
                            OpenAIRequest.FunctionCall fc = new OpenAIRequest.FunctionCall();
                            fc.setName(tc.getName());
                            fc.setArguments(tc.getArguments());

                            OpenAIRequest.ToolCall oaiTc = new OpenAIRequest.ToolCall();
                            oaiTc.setId(tc.getToolCallId());
                            oaiTc.setType("function");
                            oaiTc.setFunction(fc);
                            return oaiTc;
                        })
                        .collect(Collectors.toList());
                oaiMsg.setToolCalls(toolCalls);
            }

            messages.add(oaiMsg);
        }

        return messages;
    }

    private List<Map<String, Object>> buildTools(List<ToolDefinition> toolDefs) {
        return toolDefs.stream()
                .map(td -> {
                    Map<String, Object> function = new HashMap<>();
                    function.put("name", resolveDisplayName(td));
                    function.put("description", td.getDescription());
                    function.put("parameters", td.getParameters());

                    Map<String, Object> tool = new HashMap<>();
                    tool.put("type", "function");
                    tool.put("function", function);
                    return tool;
                })
                .collect(Collectors.toList());
    }

    private Object resolveToolChoice(ChatRequest request) {
        Object rawChoice = request.getToolChoice();

        if (rawChoice instanceof String) {
            String tc = (String) rawChoice;
            if ("auto".equals(tc) || "none".equals(tc) || "required".equals(tc)) {
                return tc;
            }
            if (!tc.isEmpty()) {
                Map<String, Object> function = new HashMap<>();
                function.put("name", tc);

                Map<String, Object> choice = new HashMap<>();
                choice.put("type", "function");
                choice.put("function", function);
                return choice;
            }
        }

        if (rawChoice instanceof Map) {
            return rawChoice;
        }

        return "auto";
    }

    @Override
    protected Object parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR, "响应为空");
        }
        return responseParser.parse(rawResponse);
    }

    @Override
    protected ModelResponse toUnifiedResponse(Object apiResponse) {
        OpenAIResponse resp = (OpenAIResponse) apiResponse;

        OpenAIResponse.Choice firstChoice = resp.getFirstChoice();
        if (firstChoice == null) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                    "响应中没有 choices 数据");
        }

        OpenAIResponse.ResponseMessage message = firstChoice.getMessage();

        ModelResponse.ModelResponseBuilder builder = ModelResponse.builder()
                .id(resp.getId())
                .content(message.getContent())
                .model(resp.getModel())
                .finishReason(firstChoice.getFinishReason());

        if (resp.getUsage() != null) {
            builder.usage(Usage.of(
                    resp.getUsage().getPromptTokens(),
                    resp.getUsage().getCompletionTokens()));
        }

        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            List<ModelResponse.ToolCallRequest> toolCalls = message.getToolCalls().stream()
                    .map(tc -> ModelResponse.ToolCallRequest.builder()
                            .id(tc.getId())
                            .name(tc.getFunction().getName())
                            .arguments(tc.getFunction().getArguments())
                            .build())
                    .collect(Collectors.toList());
            builder.toolCalls(toolCalls);
        }

        return builder.build();
    }

    @Override
    protected String sendRequest(Object apiRequest) {
        String url = baseUrl + ENDPOINT;
        Map<String, String> headers = buildHeaders();

        try {
            String body = objectMapper.writeValueAsString(apiRequest);
            return httpClient.post(url, headers, body);
        } catch (JsonProcessingException e) {
            throw ModelException.of(ErrorCode.MODEL_INVALID_REQUEST,
                    "请求序列化失败: " + e.getMessage());
        }
    }

    @Override
    protected Flux<String> sendStreamRequest(Object apiRequest) {
        String url = baseUrl + ENDPOINT;
        Map<String, String> headers = buildHeaders();

        try {
            String body = objectMapper.writeValueAsString(apiRequest);
            return httpClient.postStream(url, headers, body);
        } catch (JsonProcessingException e) {
            return Flux.error(ModelException.of(ErrorCode.MODEL_INVALID_REQUEST,
                    "请求序列化失败: " + e.getMessage()));
        }
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private String resolveDisplayName(ToolDefinition td) {
        return (td.getDisplayName() != null && !td.getDisplayName().isEmpty())
                ? td.getDisplayName() : td.getName();
    }

    private double clampTemperature(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
        if (rawSSE == null || rawSSE.isEmpty()) return List.of();
        List<ModelResponse.ToolCallRequest> result = new ArrayList<>();

        for (String line : rawSSE.split("\n")) {
            String json = stripSseDataPrefix(line);
            if (json == null) continue;

            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode delta = root.path("choices").get(0).path("delta");
                JsonNode tcNode = delta.get("tool_calls");
                if (tcNode == null || !tcNode.isArray()) continue;

                for (JsonNode tc : tcNode) {
                    int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                    String id = tc.has("id") ? tc.get("id").asText() : null;
                    JsonNode func = tc.get("function");
                    String name = (func != null && func.has("name")) ? func.get("name").asText() : null;
                    String args = (func != null && func.has("arguments")) ? func.get("arguments").asText() : null;

                    ModelResponse.ToolCallRequest existing = findOrCreate(result, idx, id, name);
                    if (args != null && !args.isEmpty()) {
                        existing.appendArguments(args);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    @Override
    public String extractSsePlainText(String rawSSE) {
        if (rawSSE == null || rawSSE.isEmpty()) return "";
        StringBuilder text = new StringBuilder();

        for (String line : rawSSE.split("\n")) {
            String json = stripSseDataPrefix(line);
            if (json == null) continue;
            try {
                JsonNode delta = objectMapper.readTree(json)
                        .path("choices").get(0).path("delta");
                JsonNode content = delta.get("content");
                if (content != null && !content.isNull()) {
                    text.append(content.asText());
                }
            } catch (Exception ignored) {}
        }
        return text.toString();
    }

    @Override
    public String extractSseTokenUsage(String rawSSE) {
        String[] lines = rawSSE.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String json = stripSseDataPrefix(lines[i]);
            if (json == null) continue;
            try {
                JsonNode usage = objectMapper.readTree(json).get("usage");
                if (usage != null) {
                    long prompt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0;
                    long completion = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0;
                    long total = usage.has("total_tokens") ? usage.get("total_tokens").asLong() : 0;
                    return "prompt=" + prompt + " completion=" + completion + " total=" + total;
                }
            } catch (Exception ignored) {}
        }
        return "prompt=0 completion=0 total=0";
    }

    private String stripSseDataPrefix(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring(5).trim();
        }
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) return null;
        return trimmed;
    }

    private ModelResponse.ToolCallRequest findOrCreate(
            List<ModelResponse.ToolCallRequest> list,
            int index, String id, String name) {

        for (ModelResponse.ToolCallRequest tcr : list) {
            if (tcr.getIndex() == index) {
                return tcr;
            }
        }
        ModelResponse.ToolCallRequest tcr = ModelResponse.ToolCallRequest.builder()
                .index(index)
                .id(id != null ? id : "")
                .name(name != null ? name : "")
                .arguments("")
                .build();
        list.add(tcr);
        return tcr;
    }
}
