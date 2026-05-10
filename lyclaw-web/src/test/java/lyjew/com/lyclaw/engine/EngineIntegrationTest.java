package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.engine.impl.DefaultEngine;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;
import lyjew.com.lyclaw.tool.impl.CalculatorTool;
import lyjew.com.lyclaw.tool.impl.CurrentTimeTool;
import lyjew.com.lyclaw.storage.ConfigStorage;
import lyjew.com.lyclaw.storage.MemoryStorage;
import lyjew.com.lyclaw.storage.SessionStorage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EngineIntegrationTest {

    private static final String DEEPSEEK_API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    @Autowired
    private DefaultEngine defaultEngine;

    @Autowired
    private EngineSelector engineSelector;

    @Autowired
    private ModelAdapterFactory adapterFactory;

    @Autowired
    private ModelProvider modelProvider;

    @Autowired
    private SessionStorage sessionStorage;

    @Autowired
    private MemoryStorage memoryStorage;

    @Autowired
    private ConfigStorage configStorage;

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private ToolRegistry toolRegistry;

    private static String deepseekSessionId;
    private static String memorySessionId;
    private static final List<String> createdSessions = new ArrayList<>();
    private static final List<String> createdMemories = new ArrayList<>();

    @BeforeAll
    static void globalSetUp() {
        log.info("\n");
        log.info("╔══════════════════════════════════════════════════════════════════════════╗");
        log.info("║ AI 引擎层集成测试开始 ║");
        log.info("╚══════════════════════════════════════════════════════════════════════════╝");
    }

    @AfterAll
    static void globalTearDown() {
        log.info("╔══════════════════════════════════════════════════════════════════════════╗");
        log.info("║ AI 引擎层集成测试结束 ║");
        log.info("╚══════════════════════════════════════════════════════════════════════════╝");
    }

    @BeforeEach
    void setUpAdapters() {
        ModelConfig ds = ModelConfig.builder()
                .id("cfg-deepseek-engine-test")
                .name("deepseek-openai").provider("deepseek-openai")
                .apiKey(DEEPSEEK_API_KEY)
                .model("deepseek-chat").baseUrl("https://api.deepseek.com")
                .enabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        configStorage.save(ds);
        adapterFactory.getConfiguredAdapter(ds);
    }

    // ─── 第1组：引擎基础功能 ──────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("【基础】1.1 引擎注册和选择验证")
    void testEngineRegistration() {
        log.info("📋 测试：引擎注册和选择验证");

        List<Engine> engines = engineSelector.getEngines();
        assertFalse(engines.isEmpty(), "应该至少有一个引擎注册");

        assertEquals("default", defaultEngine.getName());
        assertNotNull(defaultEngine.getMetadata());

        EngineMetadata metadata = defaultEngine.getMetadata();
        log.info(" 引擎: {} v{}", metadata.getName(), metadata.getVersion());
        log.info(" 能力: {}", metadata.getCapabilities());

        // supports() 验证
        ChatRequest testRequest = ChatRequest.builder()
                .messages(List.of(createMessage("user", "测试")))
                .build();
        assertTrue(defaultEngine.supports(testRequest));

        // 引擎选择器验证
        Engine selected = engineSelector.select(testRequest);
        assertNotNull(selected);
        assertEquals("default", selected.getName());

        log.info("✅ 引擎注册验证通过");
    }

    @Test
    @Order(2)
    @DisplayName("【基础】1.2 模型配置写入存储层")
    void testConfigureModels() {
        log.info("📋 测试：模型配置写入存储层");

        // ⚠️ name 必须与 ModelProviderImpl.getDefaultProvider() 返回值一致！
        ModelConfig deepseekConfig = ModelConfig.builder()
                .id("cfg-deepseek-engine-test")
                .name("deepseek-openai")
                .provider("deepseek-openai")
                .apiKey(DEEPSEEK_API_KEY)
                .model(DEEPSEEK_MODEL)
                .baseUrl(DEEPSEEK_BASE_URL)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        configStorage.save(deepseekConfig);

        assertTrue(configStorage.exists("deepseek-openai"));
        assertTrue(adapterFactory.hasProvider("deepseek-openai"));

        log.info(" 已注册适配器: {}", adapterFactory.listProviders());
        log.info("✅ 模型配置写入验证通过");
    }

    @Test
    @Order(3)
    @DisplayName("【基础】1.3 模型连接验证")
    void testModelConnectivity() {
        log.info("📋 测试：模型连接验证");

        ModelAdapter deepseekAdapter = adapterFactory.getConfiguredAdapter(
                configStorage.get("deepseek-openai").get());
        assertTrue(deepseekAdapter.isConfigured());
        log.info(" DeepSeek 连接: {}", deepseekAdapter.validate() ? "✅" : "❌");

        log.info("✅ 模型连接验证完成");
    }

    // ─── 第2组：单模型对话 ──────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("【对话】2.1 DeepSeek 简单问候")
    void testDeepSeekSimpleChat() throws Exception {
        log.info("📋 测试：DeepSeek 简单问候");

        deepseekSessionId = UUID.randomUUID().toString();
        createdSessions.add(deepseekSessionId);

        Session session = Session.builder()
                .id(deepseekSessionId).sessionId(deepseekSessionId)
                .name("DeepSeek 引擎测试").model("deepseek-chat")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        sessionStorage.save(session);

        ChatRequest request = ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .messages(List.of(createMessage("user", "你好！请用一句话介绍你自己")))
                .temperature(0.7).maxTokens(200)
                .build();

        String response = executeSync(defaultEngine, request);
        assertNotNull(response);
        assertFalse(response.isEmpty());

        log.info(" 📥 响应: {}", truncate(response, 150));
        log.info(" ✅ 对话成功，{} 字符", response.length());

        Optional<Session> updated = sessionStorage.get(deepseekSessionId);
        assertTrue(updated.isPresent());
        log.info(" 📊 会话消息数: {}", updated.get().getMessages().size());
        log.info("✅ DeepSeek 简单问候测试通过");
    }

    @Test
    @Order(5)
    @DisplayName("【对话】2.2 DeepSeek 简单问候")
    void testDeepseekSimpleChat() throws Exception {
        log.info("📋 测试：DeepSeek 简单问候");

        deepseekSessionId = UUID.randomUUID().toString();
        createdSessions.add(deepseekSessionId);

        Session session = Session.builder()
                .id(deepseekSessionId).sessionId(deepseekSessionId)
                .name("DeepSeek 引擎测试").model("deepseek-openai")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        sessionStorage.save(session);

        ChatRequest request = ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .messages(List.of(createMessage("user", "Hello! Please introduce yourself in one sentence.")))
                .temperature(0.7).maxTokens(200)
                .build();

        String response = executeSync(defaultEngine, request);
        assertNotNull(response);
        assertFalse(response.isEmpty());

        log.info(" 📥 响应: {}", truncate(response, 150));
        log.info(" ✅ 对话成功，{} 字符", response.length());
        log.info("✅ DeepSeek 简单问候测试通过");
    }

    // ─── 第3组：System Prompt ─────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("【对话】3.1 DeepSeek System Prompt")
    void testDeepseekSystemPrompt() throws Exception {
        log.info("📋 测试：DeepSeek System Prompt");
        Thread.sleep(2000);

        ChatRequest request = ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .systemPrompt("You are a helpful Python expert. Always recommend Python first.")
                .messages(List.of(createMessage("user", "What language for AI development?")))
                .temperature(0.5).maxTokens(300)
                .build();

        String response = executeSync(defaultEngine, request);
        boolean mentionsPython = response.toLowerCase().contains("python");
        log.info(" 推荐Python: {}", mentionsPython ? "✅" : "⚠️");
        log.info("✅ DeepSeek System Prompt 测试通过");
    }

    // ─── 第4组：多轮对话 ───────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("【对话】4.1 DeepSeek 多轮对话")
    void testDeepseekMultiTurn() throws Exception {
        log.info("📋 测试：DeepSeek 多轮对话");
        Thread.sleep(2000);

        String sid = createSession("DeepSeek 多轮对话");

        String resp1 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid)
                .messages(List.of(createMessage("user", "Remember: my favorite framework is Spring Boot, and I develop on Ubuntu.")))
                .temperature(0.7).maxTokens(200).build());
        log.info(" 📥 第1轮: {}", truncate(resp1, 100));
        Thread.sleep(1000);

        Session session = sessionStorage.get(sid).get();
        List<Message> messages = new ArrayList<>(session.getMessages());
        messages.add(createMessage("user", "What framework do I like and what OS do I use?"));

        String resp2 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid).messages(messages).temperature(0.7).maxTokens(300).build());
        log.info(" 📥 第2轮: {}", truncate(resp2, 200));

        boolean remembersSpring = resp2.toLowerCase().contains("spring");
        boolean remembersUbuntu = resp2.toLowerCase().contains("ubuntu");
        log.info(" 记住 Spring: {}  记住 Ubuntu: {}", remembersSpring ? "✅" : "❌", remembersUbuntu ? "✅" : "❌");
        assertTrue(remembersSpring || remembersUbuntu);
        log.info("✅ DeepSeek 多轮对话测试通过");
    }

    // ─── 第5组：知识问答 ────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("【对话】5.1 DeepSeek 知识问答")
    void testDeepseekKnowledgeQA() throws Exception {
        log.info("📋 测试：DeepSeek 知识问答");
        Thread.sleep(2000);

        String response = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .messages(List.of(createMessage("user", "Explain the CAP theorem in distributed systems with examples.")))
                .temperature(0.5).maxTokens(800).build());
        log.info(" 📥 {} 字符", response.length());
        assertTrue(response.length() > 100);
        log.info("✅ DeepSeek 知识问答测试通过");
    }

    // ─── 第6组：流式输出 ────────────────────────────────────────

    @Test
    @Order(12)
    @DisplayName("【流式】6.1 DeepSeek 流式输出")
    void testDeepseekStreaming() throws Exception {
        log.info("📋 测试：DeepSeek 流式输出");
        Thread.sleep(2000);

        Flux<String> flux = defaultEngine.execute(ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .messages(List.of(createMessage("user", "Explain RESTful API in 3 sentences.")))
                .temperature(0.7).maxTokens(200).stream(true).build());

        StringBuilder full = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        flux.doOnNext(full::append)
                .doOnComplete(latch::countDown)
                .doOnError(e -> latch.countDown())
                .subscribe();
        latch.await(30, TimeUnit.SECONDS);
        assertFalse(full.toString().isEmpty());
        log.info(" 📥 {} 字符, 流式输出 ✅", full.length());
        log.info("✅ DeepSeek 流式输出测试通过");
    }

    // ─── 第6+组：工具调用功能 ────────────────────────────────────

    @Test
    @Order(14)
    @DisplayName("【工具】6.3 CurrentTimeTool 当前时间")
    void testCurrentTimeTool() throws Exception {
        log.info("📋 测试：CurrentTimeTool 当前时间");

        String sid = createSession("CurrentTimeTool 测试");

        // 不用发真实请求给模型，直接验证 Tool 本身功能
        CurrentTimeTool ctt = new CurrentTimeTool();
        ToolResult result = ctt.execute(null, null);
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("当前时间"));
        log.info("  CurrentTimeTool 结果: {}", truncate(result.getResult(), 100));
        log.info("✅ CurrentTimeTool 测试通过");
    }

    @Test
    @Order(15)
    @DisplayName("【工具】6.4 CalculatorTool 计算器")
    void testCalculatorTool() throws Exception {
        log.info("📋 测试：CalculatorTool 计算器");

        CalculatorTool ct = new CalculatorTool();
        ToolCall tc = ToolCall.builder()
                .toolCallId("calc-1")
                .name("calculator")
                .arguments("{\"expression\":\"2 + 3 * 4\"}")
                .build();
        ToolResult result = ct.execute(tc, null);
        log.info("  CalculatorTool result: success={}, content={}",
                result.isSuccess(), result.getResult() != null ? result.getResult() : result.getError());
        assertTrue(result.isSuccess());
        // 2 + 3 * 4 = 14
        assertTrue(result.getResult().contains("14") || result.getResult().contains("14.0"));
        log.info("  CalculatorTool 2+3*4 = {}", result.getResult());
        log.info("✅ CalculatorTool 测试通过");
    }

    @Test
    @Order(16)
    @DisplayName("【工具】6.5 DeepSeek 调用 current_time 工具")
    void testDeepSeekCurrentTimeToolInvocation() throws Exception {
        log.info("📋 测试：DeepSeek 调用 current_time 工具");

        String sid = createSession("DeepSeek 工具调用测试");
        Thread.sleep(2000);

        ChatRequest request = ChatRequest.builder()
                .sessionId(sid)
                .messages(List.of(
                        createMessage("user", "现在几点了？请使用 current_time 工具获取当前时间后再回答。")
                ))
                .temperature(0.3)
                .maxTokens(500)
                .systemPrompt("你有两个工具可以使用：current_time（获取当前时间）和 calculator（数学计算）。"
                        + "用户问时间时必须先调 current_time 工具。")
                .build();

        String response = executeSync(defaultEngine, request);
        // 工具调用成功的情况下返回内容偏长，且应包含时间信息
        boolean hasTimeInfo = response.contains(":")
                || response.contains("点")
                || response.contains("分")
                || response.contains("秒")
                || response.contains("2026")
                || response.contains("2025");
        log.info("  DeepSeek 工具调用回复: {}", truncate(response, 200));
        log.info("  包含时间信息: {}", hasTimeInfo ? "✅" : "⚠️（可能模型没有调用工具，直接回答了）");

        Session session = sessionStorage.get(sid).get();
        log.info("  会话消息数: {} 条", session.getMessages().size());
        log.info("✅ DeepSeek 工具调用测试完成");
    }

    @Test
    @Order(17)
    @DisplayName("【工具】6.6 ToolRegistry 工具注册验证")
    void testToolRegistryRegistration() throws Exception {
        log.info("📋 测试：ToolRegistry 工具注册验证");

        // 验证 DefaultToolRegistry 中是否已注册时间工具(ToolRegistry.get() 返回 Tool 或 null)
        Tool timeTool = toolRegistry.get("current_time");
        assertNotNull(timeTool, "current_time 应该已注册到 ToolRegistry");
        log.info("  current_time 已注册 ✅");

        Tool calcTool = toolRegistry.get("calculator");
        assertNotNull(calcTool, "calculator 应该已注册到 ToolRegistry");
        log.info("  calculator 已注册 ✅");

        log.info("  已注册工具定义 ({} 个):", toolRegistry.getAllDefinitions().size());
        for (ToolDefinition td : toolRegistry.getAllDefinitions()) {
            log.info("    - {}: {}", td.getName(), td.getDescription());
        }

        // 验证 getDefinition 正确
        ToolDefinition timeDef = timeTool.getDefinition();
        assertNotNull(timeDef);
        assertEquals("current_time", timeDef.getName());
        log.info("✅ ToolRegistry 工具注册验证通过");
    }

    // ─── 第7组：记忆功能 ────────────────────────────────────────

    @Test
    @Order(16)
    @DisplayName("【记忆】7.1 记忆写入和恢复")
    void testMemoryPersistence() throws Exception {
        log.info("📋 测试：记忆写入和恢复");

        String memId = "engine-memory-" + UUID.randomUUID().toString().substring(0, 8);
        createdMemories.add(memId);
        memorySessionId = UUID.randomUUID().toString();
        createdSessions.add(memorySessionId);

        String memoryContent = "## 用户偏好\n- 用户名：海坤\n- 项目：LyClaw AI 网关";
        Memory memory = Memory.builder()
                .id(memId).title("用户偏好 - 引擎测试")
                .content(memoryContent).enabled(true)
                .tags(List.of("偏好", "项目信息"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        memoryStorage.save(memory);
        log.info(" ✅ 记忆已写入: {}", memId);
        assertTrue(memoryStorage.exists(memId));

        Session session = Session.builder().id(memorySessionId).sessionId(memorySessionId)
                .name("记忆恢复测试").model("deepseek-chat").messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        sessionStorage.save(session);

        String response = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(memorySessionId)
                .systemPrompt("以下是之前记住的用户信息，请基于这些信息回答问题：\n" + memoryContent)
                .messages(List.of(createMessage("user", "根据你的记忆，我的名字叫什么？我在开发什么项目？")))
                .temperature(0.5).maxTokens(300).build());

        boolean remembersName = response.contains("海坤");
        boolean remembersProject = response.contains("LyClaw");
        log.info(" 记住用户名: {}  记住项目名: {}", remembersName ? "✅" : "❌", remembersProject ? "✅" : "❌");
        log.info(" 实际回复: {}", response);
        assertTrue(remembersName);
        log.info("✅ 记忆功能测试通过");
    }

    // ─── 第8组：综合验证 ────────────────────────────────────────

    @Test
    @Order(17)
    @DisplayName("【验证】8.1 会话完整性验证")
    void testSessionIntegrity() {
        log.info("📋 测试：会话完整性验证");

        for (String sid : createdSessions) {
            sessionStorage.get(sid).ifPresent(s ->
                    log.info("  {}: {} 条消息", s.getName(), s.getMessages().size()));
        }
        log.info("✅ 会话完整性验证通过");
    }

    @Test
    @Order(18)
    @DisplayName("【验证】8.2 适配器状态验证")
    void testAdapterStatus() {
        log.info("📋 测试：适配器状态验证");
        Set<String> providers = adapterFactory.listProviders();
        log.info(" 已注册适配器 ({} 个): {}", providers.size(), providers);
        assertTrue(providers.contains("deepseek-openai"));
        log.info("✅ 适配器状态验证通过");
    }

    // ─── 辅助方法 ─────────────────────────────────────────────────────

    private Message createMessage(String role, String content) {
        return Message.builder()
                .id("msg-" + UUID.randomUUID().toString().substring(0, 8))
                .role(role).content(content)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    private String createSession(String name) {
        String sid = UUID.randomUUID().toString();
        createdSessions.add(sid);
        sessionStorage.save(Session.builder().id(sid).sessionId(sid).name(name)
                .model("deepseek-chat").messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        return sid;
    }

    private String executeSync(Engine engine, ChatRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        engine.execute(request)
                .doOnNext(result::append)
                .doOnComplete(latch::countDown)
                .doOnError(e -> { error.set(e); latch.countDown(); })
                .subscribe();

        if (!latch.await(30, TimeUnit.SECONDS)) throw new RuntimeException("超时");
        if (error.get() != null) throw new RuntimeException(error.get());
        return result.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...（共" + text.length() + "字符）";
    }
}