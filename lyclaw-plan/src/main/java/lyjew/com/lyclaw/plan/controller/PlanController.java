package lyjew.com.lyclaw.plan.controller;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.ReflectionFeedback;
import lyjew.com.lyclaw.task.ReviseRequest;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final TaskPlanner taskPlanner;
    private final InterceptorChain interceptorChain;
    private final ModelProvider modelProvider;

    public PlanController(TaskPlanner taskPlanner, InterceptorChain interceptorChain,
                          ModelProvider modelProvider) {
        this.taskPlanner = taskPlanner;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
    }

    @PostMapping("/plan")
    public ResponseEntity<TaskPlan> plan(@RequestBody PlanRequest request) {
        ChatContext context = buildContext(request);
        TaskPlan plan = taskPlanner.plan(context, request.getUserIntent());
        return ResponseEntity.ok(plan);
    }

    @PostMapping("/revise")
    public ResponseEntity<TaskPlan> revise(@RequestBody ReviseRequest request) {
        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .suggestedStrategy(request.getFeedback())
                .adjustedPrompt(request.getReason())
                .build();
        TaskPlan revised = taskPlanner.revise(request.getCurrentPlan(), feedback);
        return ResponseEntity.ok(revised);
    }

    @GetMapping("/progress/{planId}")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable String planId) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("planId", planId);
        progress.put("status", "RUNNING");
        progress.put("progress", 0.5);
        progress.put("currentNode", "dag-root-1");
        return ResponseEntity.ok(progress);
    }

    private ChatContext buildContext(PlanRequest request) {
        Session session = Session.builder()
                .sessionId(request.getSessionId())
                .build();
        MemoryContent memory = new MemoryContent("", "", false, List.of(), 0.0);
        ChatRequest chatRequest = ChatRequest.builder()
                .sessionId(request.getSessionId())
                .messages(new ArrayList<>())
                .build();
        return new ChatContext(chatRequest, session, memory,
                new ArrayList<>(), interceptorChain, modelProvider);
    }
}
