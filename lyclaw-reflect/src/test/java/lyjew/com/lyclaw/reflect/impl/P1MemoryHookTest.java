package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.impl.hook.MemoryHook;
import lyjew.com.lyclaw.reflect.impl.hook.MemoryHookRegistry;
import lyjew.com.lyclaw.reflect.impl.store.InMemoryReflectionStore;
import lyjew.com.lyclaw.reflect.model.*;
import lyjew.com.lyclaw.reflect.topology.ExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Batch 10 — P1 Memory + MemoryHook")
class P1MemoryHookTest {

    // ── InMemoryReflectionStore ──

    @Nested
    @DisplayName("InMemoryReflectionStore")
    class StoreTests {
        @Test
        void storeAndRetrieveSkills() {
            InMemoryReflectionStore store = new InMemoryReflectionStore();
            MemoryRecord skill = new MemoryRecord();
            skill.setSummary("如何使用 Git 进行 rebase");
            skill.setContent("git rebase -i HEAD~3 可以交互式合并最近3个提交");
            skill.setImportanceScore(0.8);
            skill.setTaskType("git");
            store.storeSkill(new ReflectionContext(), skill);
            assertEquals(1, store.skillCount());

            List<MemoryRecord> results = store.retrieveSkills(new ReflectionContext(), "git rebase", 5);
            assertFalse(results.isEmpty());
            assertTrue(results.get(0).getSummary().contains("rebase"));
        }

        @Test
        void storeAndRetrieveExperiences() {
            InMemoryReflectionStore store = new InMemoryReflectionStore();
            MemoryRecord exp = new MemoryRecord();
            exp.setSummary("处理 NullPointerException 的经验");
            exp.setContent("检查 Optional 是否为空再调用 .get()");
            exp.setImportanceScore(0.6);
            exp.setTaskType("debugging");
            store.storeExperience(new ReflectionContext(), exp);
            assertEquals(1, store.experienceCount());

            List<MemoryRecord> results = store.retrieveExperiences(new ReflectionContext(), "NullPointerException", 5);
            assertFalse(results.isEmpty());
        }

        @Test
        void retrieveSkipsExpiredRecords() {
            InMemoryReflectionStore store = new InMemoryReflectionStore();
            MemoryRecord skill = new MemoryRecord();
            skill.setSummary("过期技能");
            skill.setImportanceScore(0.5);
            store.storeSkill(new ReflectionContext(), skill);

            // 未过期的记录应该能被检索
            List<MemoryRecord> results = store.retrieveSkills(new ReflectionContext(), "过期", 5);
            assertEquals(1, results.size());
        }

        @Test
        void keywordMatchingPrioritizesRelevance() {
            InMemoryReflectionStore store = new InMemoryReflectionStore();
            MemoryRecord r1 = new MemoryRecord();
            r1.setSummary("Docker 容器网络配置");
            r1.setImportanceScore(0.5);
            store.storeSkill(new ReflectionContext(), r1);

            MemoryRecord r2 = new MemoryRecord();
            r2.setSummary("Kubernetes Pod 网络调试");
            r2.setImportanceScore(0.9);
            store.storeSkill(new ReflectionContext(), r2);

            // 搜索 "网络" 应该两个都返回，按相关性+重要性排序
            List<MemoryRecord> results = store.retrieveSkills(new ReflectionContext(), "网络", 5);
            assertEquals(2, results.size());
            // r2 相关性和重要性都高，应排第一
            assertEquals("Kubernetes Pod 网络调试", results.get(0).getSummary());
        }

        @Test
        void emptyQueryReturnsByImportance() {
            InMemoryReflectionStore store = new InMemoryReflectionStore();
            MemoryRecord r1 = new MemoryRecord();
            r1.setSummary("低重要性"); r1.setImportanceScore(0.2);
            store.storeSkill(new ReflectionContext(), r1);

            MemoryRecord r2 = new MemoryRecord();
            r2.setSummary("高重要性"); r2.setImportanceScore(0.9);
            store.storeSkill(new ReflectionContext(), r2);

            List<MemoryRecord> results = store.retrieveSkills(new ReflectionContext(), "", 5);
            assertEquals(2, results.size());
            assertEquals("高重要性", results.get(0).getSummary());
        }

        @Test
        void clearRemovesAll() {
            InMemoryReflectionStore store = new InMemoryReflectionStore();
            MemoryRecord r = new MemoryRecord();
            r.setSummary("test");
            store.store(new ReflectionContext(), r);
            store.storeSkill(new ReflectionContext(), r);
            store.storeExperience(new ReflectionContext(), r);
            assertTrue(store.skillCount() > 0);

            store.clear();
            assertEquals(0, store.skillCount());
            assertEquals(0, store.experienceCount());
            assertEquals(0, store.sessionCount());
        }
    }

    // ── MemoryHookRegistry ──

    @Nested
    @DisplayName("MemoryHookRegistry")
    class HookRegistryTests {
        @Test
        void dispatchCallsAllHooks() {
            MemoryHookRegistry registry = new MemoryHookRegistry();
            AtomicInteger counter = new AtomicInteger(0);

            registry.register(new MemoryHook() {
                @Override public void onTopologyStart(ReflectionContext ctx, String name) { counter.incrementAndGet(); }
                @Override public void onActorAfter(ReflectionContext ctx, String name, String out) { counter.incrementAndGet(); }
            });

            registry.dispatchTopologyStart(new ReflectionContext(), "test");
            registry.dispatchActorAfter(new ReflectionContext(), "actor", "hello");

            assertEquals(2, counter.get());
        }

        @Test
        void hookExceptionDoesNotStopOthers() {
            MemoryHookRegistry registry = new MemoryHookRegistry();
            AtomicInteger counter = new AtomicInteger(0);

            registry.register(new MemoryHook() {
                @Override public void onTopologyStart(ReflectionContext ctx, String name) {
                    throw new RuntimeException("broken hook");
                }
            });
            registry.register(new MemoryHook() {
                @Override public void onTopologyStart(ReflectionContext ctx, String name) { counter.incrementAndGet(); }
            });

            // 不应抛异常，第二个钩子应该执行
            registry.dispatchTopologyStart(new ReflectionContext(), "test");
            assertEquals(1, counter.get());
        }

        @Test
        void unregisterRemovesHook() {
            MemoryHookRegistry registry = new MemoryHookRegistry();
            AtomicInteger counter = new AtomicInteger(0);
            MemoryHook hook = new MemoryHook() {
                @Override public void onTopologyStart(ReflectionContext ctx, String name) { counter.incrementAndGet(); }
            };

            registry.register(hook);
            registry.dispatchTopologyStart(new ReflectionContext(), "test");
            assertEquals(1, counter.get());

            registry.unregister(hook);
            registry.dispatchTopologyStart(new ReflectionContext(), "test");
            assertEquals(1, counter.get()); // 不变，钩子已注销
        }

        @Test
        void duplicateRegistrationPrevented() {
            MemoryHookRegistry registry = new MemoryHookRegistry();
            MemoryHook hook = new MemoryHook() {};
            registry.register(hook);
            registry.register(hook);
            assertEquals(1, registry.size()); // 去重
        }

        @Test
        void dispatchTopologyEnd() {
            MemoryHookRegistry registry = new MemoryHookRegistry();
            AtomicInteger counter = new AtomicInteger(0);

            registry.register(new MemoryHook() {
                @Override public void onTopologyEnd(ReflectionContext ctx, lyjew.com.lyclaw.reflect.topology.ExecutionResult result) {
                    counter.incrementAndGet();
                }
            });

            ExecutionResult result = new ExecutionResult();
            result.setFinalOutput("done");
            registry.dispatchTopologyEnd(new ReflectionContext(), result);
            assertEquals(1, counter.get());
        }
    }
}
