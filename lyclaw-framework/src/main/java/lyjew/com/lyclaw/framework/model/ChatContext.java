package lyjew.com.lyclaw.framework.model;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatContext {

    private ChatRequest request;
    private Session session;
    private List<Message> messages;
    private List<ToolDefinition> toolDefinitions;
    private ChatResult result;
    private final Map<String, Object> attributes = new HashMap<>();

    public ChatContext() {
    }

    public ChatContext(ChatRequest request, Session session,
                       List<ToolDefinition> toolDefinitions) {
        this.request = request;
        this.session = session;
        this.toolDefinitions = toolDefinitions;
    }

    public ChatRequest getRequest() {
        return request;
    }

    public void setRequest(ChatRequest request) {
        this.request = request;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public List<ToolDefinition> getToolDefinitions() {
        return toolDefinitions;
    }

    public void setToolDefinitions(List<ToolDefinition> toolDefinitions) {
        this.toolDefinitions = toolDefinitions;
    }

    public ChatResult getResult() {
        return result;
    }

    public void setResult(ChatResult result) {
        this.result = result;
    }

    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }
}
