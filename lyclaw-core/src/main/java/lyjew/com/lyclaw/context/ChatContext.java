package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatContext {

    private final ChatRequest request;
    private Session session;
    private final MemoryContent memory;
    private final List<Message> messages;
    private final List<ToolDefinition> toolDefinitions;
    private final InterceptorChain interceptorChain;
    private final ModelProvider modelProvider;
    private ChatResult result;
    private final TraceContext tracing;
    private final Map<String, Object> attributes = new HashMap<>();

    public ChatContext(ChatRequest request, Session session,
                       MemoryContent memory, List<ToolDefinition> toolDefinitions,
                       InterceptorChain interceptorChain,
                       ModelProvider modelProvider) {
        this.request = request;
        this.session = session;
        this.memory = memory;
        this.messages = new ArrayList<>(session.getMessages());
        this.toolDefinitions = toolDefinitions;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
        this.tracing = new TraceContext();
    }

    public ChatRequest getRequest() { return request; }

    public Session getSession() { return session; }

    public void setSession(Session session) { this.session = session; }

    public MemoryContent getMemory() { return memory; }

    public List<Message> getMessages() { return messages; }

    public List<ToolDefinition> getToolDefinitions() { return toolDefinitions; }

    public InterceptorChain getInterceptorChain() { return interceptorChain; }

    public ModelProvider getModelProvider() { return modelProvider; }

    public ChatResult getResult() { return result; }

    public void setResult(ChatResult result) { this.result = result; }

    public TraceContext getTracing() { return tracing; }

    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }
}
