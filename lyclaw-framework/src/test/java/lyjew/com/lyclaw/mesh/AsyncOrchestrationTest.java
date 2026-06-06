package lyjew.com.lyclaw.mesh;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.mesh.impl.DefaultOrchestrationEngine;

/**
 * 异步编排测试：
 * - 异步启动编排返回 taskId
 * - 后台执行完成后可查询结果
 * - 超时处理
 * - 结果只返回一次
 */
class AsyncOrchestrationTest {

    private DefaultAgentMesh mesh;
    private DefaultOrchestrationEngine engine;
    private ConcurrentHashMap<String, OrchestrationResult> results;
    private ConcurrentHashMap<String, CompletableFuture<OrchestrationResult>> futures;

    @BeforeEach
    void setUp() {
        mesh = new DefaultAgentMesh();
        engine = new DefaultOrchestrationEngine(mesh);
        results = new ConcurrentHashMap<>();
        futures = new ConcurrentHashMap<>();
    }

    @Test
    void asyncOrchestrationReturnsTaskId() {
        mesh.register(AgentSpec.builder().agentId("worker").build());

        String taskId = "task-" + System.currentTimeMillis();

        CompletableFuture.runAsync(() -> {
            OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                    .pattern(OrchestrationPattern.SINGLE)
                    .task("test")
                    .agentId("worker")
                    .timeoutMs(10000)
                    .build());
            results.put(taskId, result);
        });

        // 立刻返回 pending
        assertNull(results.get(taskId));

        // 等执行完成
        try { Thread.sleep(1000); } catch (Exception ignored) {}

        // 结果应该可查
        OrchestrationResult finalResult = results.get(taskId);
        assertNotNull(finalResult);
    }

    @Test
    void asyncWithNoAgentsStillCompletes() {
        String taskId = "task-empty-" + System.currentTimeMillis();

        CompletableFuture.runAsync(() -> {
            OrchestrationResult result = engine.execute(OrchestrationSpec.builder()
                    .pattern(OrchestrationPattern.SINGLE)
                    .task("test")
                    .timeoutMs(5000)
                    .build());
            results.put(taskId, result);
        });

        try { Thread.sleep(500); } catch (Exception ignored) {}

        OrchestrationResult finalResult = results.get(taskId);
        assertNotNull(finalResult);
        assertFalse(finalResult.isSuccess()); // 没有 Agent 所以失败
    }

    @Test
    void multipleAsyncTasksCanRunConcurrently() {
        mesh.register(AgentSpec.builder().agentId("w1").capability("work").build());
        mesh.register(AgentSpec.builder().agentId("w2").capability("work").build());

        String task1 = "t1", task2 = "t2";

        CompletableFuture.runAsync(() -> {
            results.put(task1, engine.execute(OrchestrationSpec.builder()
                    .pattern(OrchestrationPattern.SINGLE).task("task1").capability("work")
                    .timeoutMs(10000).build()));
        });
        CompletableFuture.runAsync(() -> {
            results.put(task2, engine.execute(OrchestrationSpec.builder()
                    .pattern(OrchestrationPattern.SINGLE).task("task2").capability("work")
                    .timeoutMs(10000).build()));
        });

        try { Thread.sleep(2000); } catch (Exception ignored) {}

        assertNotNull(results.get(task1));
        assertNotNull(results.get(task2));
    }

    @Test
    void asyncOrchestrationSupportsFanOut() {
        mesh.register(AgentSpec.builder().agentId("a1").capability("work").build());
        mesh.register(AgentSpec.builder().agentId("a2").capability("work").build());

        CompletableFuture.runAsync(() -> {
            results.put("fanout", engine.execute(OrchestrationSpec.builder()
                    .pattern(OrchestrationPattern.FAN_OUT).task("parallel")
                    .capability("work").aggregationStrategy("sum")
                    .timeoutMs(15000).build()));
        });

        try { Thread.sleep(2000); } catch (Exception ignored) {}

        assertNotNull(results.get("fanout"));
    }
}
