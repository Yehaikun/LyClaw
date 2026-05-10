package lyjew.com.lyclaw.adapter;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ModelAdapter {

    ModelResponse chat(ChatRequest request);

    Flux<String> chatStream(ChatRequest request);

    int countTokens(String text);

    boolean validate();

    String getProvider();

    boolean isConfigured();

    void configure(ModelConfig config);

    String getModel();

    String getBaseUrl();

    default List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
        return List.of();
    }

    default String extractSsePlainText(String rawSSE) {
        return "";
    }

    default String extractSseTokenUsage(String rawSSE) {
        return "prompt=0 completion=0 total=0";
    }
}
