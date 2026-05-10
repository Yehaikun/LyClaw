package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillProgressCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 测试 DefaultSkillExecutor 的技能执行/取消/进度逻辑
 */
@DisplayName("DefaultSkillExecutor 测试")
class DefaultSkillExecutorTest {

    private DefaultSkillExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DefaultSkillExecutor();
    }

    /** 创建模拟 Skill，execute 返回给定的 future */
    private Skill createSkill(String skillId, String name, CompletableFuture<SkillResult> executeResult) {
        Skill skill = mock(Skill.class, invocation -> {
            if ("getSkillId".equals(invocation.getMethod().getName())) return skillId;
            if ("getName".equals(invocation.getMethod().getName())) return name;
            if ("execute".equals(invocation.getMethod().getName())) {
                return executeResult;
            }
            return invocation.callRealMethod(); // never reached
        });
        return skill;
    }

    @Nested
    @DisplayName("技能执行")
    class Execution {

        @Test
        void testSuccessfulExecution() throws Exception {
            SkillResult expected = new SkillResult("s1", true, "output", null, 100, 50);
            Skill skill = createSkill("s1", "TestSkill",
                    CompletableFuture.completedFuture(expected));

            CompletableFuture<SkillResult> future = executor.execute(skill, null);
            SkillResult result = future.get(5, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals("output", result.getOutput());
            assertEquals("s1", result.getSkillId());
        }

        @Test
        void testFailedExecution() throws Exception {
            CompletableFuture<SkillResult> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("test failure"));
            Skill skill = createSkill("s1", "FailingSkill", failedFuture);

            CompletableFuture<SkillResult> future = executor.execute(skill, null);
            SkillResult result = future.get(5, TimeUnit.SECONDS);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("test failure"));
        }

        @Test
        void testSkillResultNullSkillId() throws Exception {
            SkillResult innerResult = new SkillResult("", true, "output", null, 50, 10);
            Skill skill = createSkill("s1", "TestSkill",
                    CompletableFuture.completedFuture(innerResult));

            CompletableFuture<SkillResult> future = executor.execute(skill, null);
            SkillResult result = future.get(5, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals("s1", result.getSkillId());
        }
    }

    @Nested
    @DisplayName("取消技能")
    class Cancel {

        @Test
        void testCancelRunningSkill() throws Exception {
            CompletableFuture<SkillResult> inner = new CompletableFuture<>();
            Skill skill = createSkill("s1", "TestSkill", inner);

            CompletableFuture<SkillResult> future = executor.execute(skill, null);
            Thread.sleep(50);
            assertTrue(executor.cancel("s1"));

            try {
                future.get(5, TimeUnit.SECONDS);
                fail("应抛出 CancellationException 或 ExecutionException");
            } catch (CancellationException | ExecutionException e) {
                // expected
            }
        }

        @Test
        void testCancelNonExistentSkill() {
            assertFalse(executor.cancel("non_existent"));
        }

        @Test
        void testGetRunningCount() throws Exception {
            CompletableFuture<SkillResult> inner = new CompletableFuture<>();
            Skill skill = createSkill("s1", "TestSkill", inner);

            executor.execute(skill, null);
            Thread.sleep(50);
            assertEquals(1, executor.getRunningCount());

            inner.complete(new SkillResult("s1", true, "ok", null, 0, 0));
            Thread.sleep(100);
            assertEquals(0, executor.getRunningCount());
        }
    }

    @Nested
    @DisplayName("进度跟踪")
    class Progress {

        @Test
        void testInitialProgress() throws Exception {
            CompletableFuture<SkillResult> inner = new CompletableFuture<>();
            Skill skill = createSkill("s1", "TestSkill", inner);

            executor.execute(skill, null);
            double progress = executor.getProgress("s1");
            assertTrue(progress >= 0.0 && progress <= 1.0,
                    "初始进度应在 [0, 1] 范围: " + progress);
        }

        @Test
        void testGetProgressNonExistent() {
            assertEquals(-1.0, executor.getProgress("non_existent"));
        }
    }

    @Nested
    @DisplayName("进度回调")
    class ProgressCallback {

        @Test
        void testSetAndInvokeCallback() throws Exception {
            SkillProgressCallback callback = mock(SkillProgressCallback.class);
            executor.setProgressCallback(callback);

            SkillResult expected = new SkillResult("s1", true, "ok", null, 0, 0);
            Skill skill = createSkill("s1", "TestSkill",
                    CompletableFuture.completedFuture(expected));

            CompletableFuture<SkillResult> future = executor.execute(skill, null);
            future.get(5, TimeUnit.SECONDS);

            verify(callback, atLeastOnce()).onProgress(eq("s1"), anyDouble(), anyString());
            verify(callback, atLeastOnce()).onComplete(eq("s1"), any(SkillResult.class));
        }

        @Test
        void testCallbackExceptionDoesNotCrash() throws Exception {
            SkillProgressCallback callback = mock(SkillProgressCallback.class);
            doThrow(new RuntimeException("callback error"))
                    .when(callback).onProgress(anyString(), anyDouble(), anyString());
            executor.setProgressCallback(callback);

            SkillResult expected = new SkillResult("s1", true, "ok", null, 0, 0);
            Skill skill = createSkill("s1", "TestSkill",
                    CompletableFuture.completedFuture(expected));

            CompletableFuture<SkillResult> future = executor.execute(skill, null);
            SkillResult result = future.get(5, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
        }
    }
}
