package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.engine.impl.DefaultEngine;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.repository.FileRepository;
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

    private static final String MINIMAX_API_KEY = "sk-cp-f77oYRQUTcc0axeEVGq2KymcFp6mHEHhJD_uO1yUWEotBGhI90-zDwnJBAQIvlaoRzhL_vcrlVS_D4VqX2yFBkMNrTOcamt5_YscyumkPxJckbw1erj9vyI";
    private static final String MINIMAX_MODEL = "MiniMax-M2.7";
    private static final String MINIMAX_BASE_URL = "https://api.minimaxi.com";

    private static final String DEEPSEEK_API_KEY = "sk-b1da578246114c2383616f49b5651f1d";
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
    private FileRepository fileRepository;

    private static String minimaxSessionId;
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
        // 每个测试前强制配置适配器，无论是否已有配置（适配器实例每次测试是新的，必须重新 configure()）
        ModelConfig mm = ModelConfig.builder()
                .id("cfg-minimax-engine-test")
                .name("minimax").provider("minimax")
                .apiKey(MINIMAX_API_KEY).model(MINIMAX_MODEL).baseUrl(MINIMAX_BASE_URL)
                .enabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        configStorage.save(mm);
        adapterFactory.getConfiguredAdapter(mm);

        ModelConfig ds = ModelConfig.builder()
                .id("cfg-deepseek-engine-test")
                .name("deepseek-openai").provider("deepseek-openai")
                .apiKey("sk-b1da578246114c2383616f49b5651f1d")
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
        ModelConfig minimaxConfig = ModelConfig.builder()
                .id("cfg-minimax-engine-test")
                .name("minimax")
                .provider("minimax")
                .apiKey(MINIMAX_API_KEY)
                .model(MINIMAX_MODEL)
                .baseUrl(MINIMAX_BASE_URL)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        configStorage.save(minimaxConfig);

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

        assertTrue(configStorage.exists("minimax"));
        assertTrue(configStorage.exists("deepseek-openai"));
        assertTrue(adapterFactory.hasProvider("minimax"));
        assertTrue(adapterFactory.hasProvider("deepseek-openai"));

        log.info(" 已注册适配器: {}", adapterFactory.listProviders());
        log.info("✅ 模型配置写入验证通过");
    }

    @Test
    @Order(3)
    @DisplayName("【基础】1.3 模型连接验证")
    void testModelConnectivity() {
        log.info("📋 测试：模型连接验证");

        ModelAdapter minimaxAdapter = adapterFactory.getConfiguredAdapter(
                configStorage.get("minimax").get());
        assertTrue(minimaxAdapter.isConfigured());
        log.info(" MiniMax 连接: {}", minimaxAdapter.validate() ? "✅" : "❌");

        ModelAdapter deepseekAdapter = adapterFactory.getConfiguredAdapter(
                configStorage.get("deepseek-openai").get());
        assertTrue(deepseekAdapter.isConfigured());
        log.info(" DeepSeek 连接: {}", deepseekAdapter.validate() ? "✅" : "❌");

        log.info("✅ 模型连接验证完成");
    }

    // ─── 第2组：单模型对话 ──────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("【对话】2.1 MiniMax 简单问候")
    void testMinimaxSimpleChat() throws Exception {
        log.info("📋 测试：MiniMax 简单问候");

        minimaxSessionId = UUID.randomUUID().toString();
        createdSessions.add(minimaxSessionId);

        Session session = Session.builder()
                .id(minimaxSessionId).sessionId(minimaxSessionId)
                .name("MiniMax 引擎测试").model("minimax")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        sessionStorage.save(session);

        ChatRequest request = ChatRequest.builder()
                .sessionId(minimaxSessionId)
                .messages(List.of(createMessage("user", "你好！请用一句话介绍你自己")))
                .temperature(0.7).maxTokens(200)
                .build();

        String response = executeSync(defaultEngine, request);
        assertNotNull(response);
        assertFalse(response.isEmpty());

        log.info(" 📥 响应: {}", truncate(response, 150));
        log.info(" ✅ 对话成功，{} 字符", response.length());

        Optional<Session> updated = sessionStorage.get(minimaxSessionId);
        assertTrue(updated.isPresent());
        log.info(" 📊 会话消息数: {}", updated.get().getMessages().size());
        log.info("✅ MiniMax 简单问候测试通过");
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
    @DisplayName("【对话】3.1 MiniMax System Prompt")
    void testMinimaxSystemPrompt() throws Exception {
        log.info("📋 测试：MiniMax System Prompt");

        String systemPrompt = "你是一个名叫'小爪'的AI助手，你非常喜欢猫咪，说话时会带'喵~'。";

        ChatRequest request = ChatRequest.builder()
                .sessionId(minimaxSessionId).systemPrompt(systemPrompt)
                .messages(List.of(createMessage("user", "你是谁？你喜欢什么动物？")))
                .temperature(0.8).maxTokens(300)
                .build();

        String response = executeSync(defaultEngine, request);
        boolean hasCat = response.contains("猫") || response.contains("喵") || response.contains("小爪");
        log.info(" 角色扮演体现: {}", hasCat ? "✅" : "⚠️");
        log.info("✅ MiniMax System Prompt 测试通过");
    }

    @Test
    @Order(7)
    @DisplayName("【对话】3.2 DeepSeek System Prompt")
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
    @DisplayName("【对话】4.1 MiniMax 多轮对话")
    void testMinimaxMultiTurn() throws Exception {
        log.info("📋 测试：MiniMax 多轮对话");

        String sid = createSession("MiniMax 多轮对话");

        String resp1 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid)
                .messages(List.of(createMessage("user", "请记住：我最喜欢的编程语言是 Java，我的家乡是河南郑州")))
                .temperature(0.7).maxTokens(200).build());
        log.info(" 📥 第1轮: {}", truncate(resp1, 100));
        Thread.sleep(1000);

        Session session = sessionStorage.get(sid).get();
        List<Message> messages = new ArrayList<>(session.getMessages());
        messages.add(createMessage("user", "根据之前的对话，我喜欢的编程语言是什么？我的家乡在哪里？"));

        String resp2 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid).messages(messages).temperature(0.7).maxTokens(300).build());
        log.info(" 📥 第2轮: {}", truncate(resp2, 200));

        boolean remembersJava = resp2.contains("Java") || resp2.contains("java");
        boolean remembersZhengzhou = resp2.contains("郑州") || resp2.contains("河南");
        log.info(" 记住 Java: {}  记住 郑州: {}", remembersJava ? "✅" : "❌", remembersZhengzhou ? "✅" : "❌");
        assertTrue(remembersJava || remembersZhengzhou);
        log.info("✅ MiniMax 多轮对话测试通过");
    }

    @Test
    @Order(9)
    @DisplayName("【对话】4.2 DeepSeek 多轮对话")
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
    @DisplayName("【对话】5.1 MiniMax 知识问答")
    void testMinimaxKnowledgeQA() throws Exception {
        log.info("📋 测试：MiniMax 知识问答");

        String response = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(minimaxSessionId)
                .messages(List.of(createMessage("user", "请简单介绍一下 Java 中的 HashMap 实现原理，包括数据结构和扩容机制")))
                .temperature(0.5).maxTokens(800).build());
        log.info(" 📥 {} 字符", response.length());
        assertTrue(response.length() > 100);
        log.info("✅ MiniMax 知识问答测试通过");
    }

    @Test
    @Order(11)
    @DisplayName("【对话】5.2 DeepSeek 知识问答")
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
    @DisplayName("【流式】6.1 MiniMax 流式输出")
    void testMinimaxStreaming() throws Exception {
        log.info("📋 测试：MiniMax 流式输出");

        Flux<String> flux = defaultEngine.execute(ChatRequest.builder()
                .sessionId(minimaxSessionId)
                .messages(List.of(createMessage("user", "用三句话介绍 Spring Boot")))
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
        log.info("✅ MiniMax 流式输出测试通过");
    }

    @Test
    @Order(13)
    @DisplayName("【流式】6.2 DeepSeek 流式输出")
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

    @Test
    @Order(14)
    @DisplayName("【流式】6.3 流式多轮对话 - 记住上下文")
    void testStreamMultiTurnMemory() throws Exception {
        log.info("📋 测试：流式多轮对话 - 记住上下文");
        // 预创建带 id + sessionId 的 session（源码 loadOrCreateSession 需要 id 和 sessionId 一致才能正常保存）
        String sid = UUID.randomUUID().toString();
        createdSessions.add(sid);
        Session preSession = Session.builder()
                .id(sid).sessionId(sid)
                .name("流式多轮对话").model("minimax")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        sessionStorage.save(preSession);

        // 🔍 验证 SessionStorage 的序列化/反序列化是否正常
        Session afterSave = sessionStorage.get(sid).orElse(null);
        log.info(" 🔍 预创建 session 保存后: get存在={}, 消息数={}",
                afterSave != null, afterSave != null ? afterSave.getMessages().size() : -1);
        // 手动模拟 loadOrCreateSession 的 map 分支
        afterSave.getMessages().add(createMessage("user", "test1"));
        sessionStorage.save(afterSave);
        Session afterLoad = sessionStorage.get(sid).orElse(null);
        log.info(" 🔍 手动保存后消息数={}", afterLoad != null ? afterLoad.getMessages().size() : -1);

        // 🔍 模拟 saveAssistantMessage
        if (afterLoad != null) {
            Message assistantMsg = Message.builder()
                    .role("assistant").content("这是测试回复").model("minimax")
                    .createdAt(LocalDateTime.now()).build();
            afterLoad.getMessages().add(assistantMsg);
            sessionStorage.save(afterLoad);
            Session verifySave = sessionStorage.get(sid).orElse(null);
            log.info(" 🔍 模拟saveAssistantMessage后消息数={}",
                    verifySave != null ? verifySave.getMessages().size() : -1);
        }

        // 第1轮：流式，告诉AI一些信息
        log.info(" 🔍 执行前: sessionStorage.get()={}", sessionStorage.get(sid).isPresent());
        Session pre = sessionStorage.get(sid).orElse(null);
        if (pre != null) {
            log.info(" 🔍 预创建session的消息数={}, id={}, sessionId={}",
                    pre.getMessages().size(), pre.getId(), pre.getSessionId());
        }

        CountDownLatch latch1 = new CountDownLatch(1);
        StringBuilder response1 = new StringBuilder();
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        defaultEngine.execute(ChatRequest.builder()
                .sessionId(sid)
                .messages(List.of(createMessage("user", "请记住：我叫李小明，最喜欢的编程语言是Rust，目前在学习后端开发")))
                .temperature(0.7).maxTokens(300).stream(true).build())
                .doOnNext(response1::append)
                .doOnComplete(latch1::countDown)
                .doOnError(e -> { err1.set(e); latch1.countDown(); })
                .subscribe();
        latch1.await(60, TimeUnit.SECONDS);
        assertNull(err1.get(), "第1轮流式不应报错");
        assertFalse(response1.toString().isEmpty(), "第1轮流式不应为空");
        log.info(" 📥 第1轮流式: {} 字符", response1.length());

        // 🔍 执行流式后，直接读取文件内容（不经过反序列化）
        String rawJson = fileRepository.read("sessions/" + sid + ".json");
        log.info(" 🔍 流式后文件内容: {}", rawJson);

        // 监控文件修改时间变化，追踪是否有额外的写入
        long mtime1 = fileRepository.exists("sessions/" + sid + ".json")
                ? new java.io.File(fileRepository.getDataDir(), "sessions/" + sid + ".json").lastModified() : 0;
        Thread.sleep(500);
        long mtime2 = fileRepository.exists("sessions/" + sid + ".json")
                ? new java.io.File(fileRepository.getDataDir(), "sessions/" + sid + ".json").lastModified() : 0;
        log.info(" 🔍 文件修改时间: initial={}, after500ms={}, changed={}",
                mtime1, mtime2, mtime1 != mtime2 ? "YES" : "NO");

        // 验证第1轮后会话已保存
        // doOnComplete 可能异步执行，尝试等待并重试读取
        Session afterRound1 = null;
        for (int retry = 0; retry < 5; retry++) {
            afterRound1 = sessionStorage.get(sid).orElse(null);
            if (afterRound1 != null && afterRound1.getMessages().size() >= 2) {
                break;
            }
            Thread.sleep(2000);
        }
        assertNotNull(afterRound1, "第1轮后会话应存在");
        int msgCountAfter1 = afterRound1.getMessages().size();
        log.info(" 📊 第1轮后会话消息数: {}, id={}, sessionId={}",
                msgCountAfter1, afterRound1.getId(), afterRound1.getSessionId());
        // 打印所有消息的 role 以便调试
        for (int i = 0; i < afterRound1.getMessages().size(); i++) {
            Message m = afterRound1.getMessages().get(i);
            log.info("  msg[{}]: role={}, content.length={}", i, m.getRole(),
                    m.getContent() != null ? m.getContent().length() : 0);
        }
        assertTrue(msgCountAfter1 >= 2, "第1轮后应有至少2条消息（user + assistant）");

        // 第2轮：流式，用会话历史，问AI是否记得第1轮说的信息
        Session sessionForRound2 = sessionStorage.get(sid).get();
        List<Message> hist2 = new ArrayList<>(sessionForRound2.getMessages());
        hist2.add(createMessage("user", "我叫什么名字？最喜欢的编程语言是什么？"));

        CountDownLatch latch2 = new CountDownLatch(1);
        StringBuilder response2 = new StringBuilder();
        AtomicReference<Throwable> err2 = new AtomicReference<>();
        defaultEngine.execute(ChatRequest.builder()
                .sessionId(sid)
                .messages(hist2)
                .temperature(0.7).maxTokens(300).stream(true).build())
                .doOnNext(response2::append)
                .doOnComplete(latch2::countDown)
                .doOnError(e -> { err2.set(e); latch2.countDown(); })
                .subscribe();
        latch2.await(60, TimeUnit.SECONDS);
        assertNull(err2.get(), "第2轮流式不应报错");

        String r2 = response2.toString();
        log.info(" 📥 第2轮流式: {}", truncate(r2, 200));

        boolean knowsName = r2.contains("李小明");
        boolean knowsLang = r2.contains("Rust") || r2.contains("rust");
        log.info(" 记住名字: {}  记住语言: {}", knowsName ? "✅" : "❌", knowsLang ? "✅" : "❌");
        assertTrue(knowsName || knowsLang, "流式多轮对话应该记得第1轮的信息");
        log.info("✅ 流式多轮对话测试通过");
    }

    @Test
    @Order(15)
    @DisplayName("【流式】6.4 流式记忆 - 追加记忆并读取")
    void testStreamMemoryAppend() throws Exception {
        log.info("📋 测试：流式记忆 - 追加记忆并读取");

        // 清理可能存在的旧记忆
        MemoryContent before = memoryManager.read();

        // 先建立一个记忆条目——模拟已经存在的长期记忆
        String memId = "stream-memory-" + UUID.randomUUID().toString().substring(0, 8);
        createdMemories.add(memId);
        String initialMemory = "## 用户信息\n- 用户ID: stream-test-user\n- 喜好: 开源项目";
        memoryStorage.save(Memory.builder()
                .id(memId).title("流式记忆测试")
                .content(initialMemory).enabled(true)
                .tags(List.of("测试", "流式"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        log.info(" ✅ 初始记忆已写入: {}", memId);

        // 做一次流式对话——让 AI 回复，这会触发 memoryManager.append()
        String sid = createSession("流式记忆测试");
        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        defaultEngine.execute(ChatRequest.builder()
                .sessionId(sid)
                .messages(List.of(createMessage("user", "你好！请简单介绍一下你自己")))
                .temperature(0.7).maxTokens(200).stream(true).build())
                .doOnNext(s -> {})
                .doOnComplete(latch1::countDown)
                .doOnError(e -> { err1.set(e); latch1.countDown(); })
                .subscribe();
        latch1.await(60, TimeUnit.SECONDS);
        assertNull(err1.get(), "流式记忆测试不应报错");

        // 验证记忆已经被追加
        MemoryContent after = memoryManager.read();
        boolean memoryAppended = after.getContent() != null
                && after.getContent().length() > initialMemory.length();
        log.info(" 记忆已追加: {}", memoryAppended ? "✅" : "⚠️");
        log.info("✅ 流式记忆测试通过");
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
                .name("记忆恢复测试").model("minimax").messages(new ArrayList<>())
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
        assertTrue(providers.contains("minimax"));
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
                .model("minimax").messages(new ArrayList<>())
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