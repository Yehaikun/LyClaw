package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.model.ChatRequest;
import reactor.core.publisher.Flux;

public interface Engine {

    String getName();

    boolean supports(ChatRequest request);

    Flux<String> execute(ChatRequest request);

    EngineMetadata getMetadata();
}
