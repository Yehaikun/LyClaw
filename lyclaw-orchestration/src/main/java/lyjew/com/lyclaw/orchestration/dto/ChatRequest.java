package lyjew.com.lyclaw.orchestration.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ChatRequest {

    private String sessionId;
    private List<Map<String, String>> messages;
    private boolean stream;
}
