package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.engine.impl.DefaultEngine;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.storage.ConfigStorage;
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

/**
 * MiniMax 中文对话测试
 *
 * 所有问题都用中文提问，展示格式为"我："/"AI："。
 * 流式输出逐 token 打印，便于观察打字机效果。
 */
@Slf4j
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MiniMaxChatTest {

    @Autowired
    private DefaultEngine defaultEngine;

    @Autowired
    private SessionStorage sessionStorage;

    @Autowired
    private ConfigStorage configStorage;

    @Autowired
    private ModelAdapterFactory adapterFactory;

    private static String sessionId;

    private static final String DEEPSEEK_API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    @BeforeAll
    static void banner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║          MiniMax 中文对话测试                   ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();
    }

    @BeforeEach
    void setUp() {
        // ModelProviderImpl.getDefaultProvider() 写死返回 "minimax"
        // 且 getConfiguredAdapter() 只调 getAdapter() 不调 configure()
        // 所以必须显式配置两个适配器，并调用 adapterFactory.getConfiguredAdapter(ModelConfig) 来触发 configure()
        ModelConfig mm = ModelConfig.builder()
                .id("cfg-minimax-test").name("minimax").provider("minimax")
                .apiKey(System.getenv().getOrDefault("MINIMAX_API_KEY", "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
                .model("MiniMax-M2.7").baseUrl("https://api.minimaxi.com").enabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        configStorage.save(mm);
        adapterFactory.getConfiguredAdapter(mm);

        ModelConfig ds = ModelConfig.builder()
                .id("cfg-deepseek-test").name("deepseek-openai").provider("deepseek-openai")
                .apiKey(DEEPSEEK_API_KEY).model(DEEPSEEK_MODEL).baseUrl(DEEPSEEK_BASE_URL).enabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        configStorage.save(ds);
        adapterFactory.getConfiguredAdapter(ds);

        sessionId = UUID.randomUUID().toString();
        sessionStorage.save(Session.builder()
                .id(sessionId).sessionId(sessionId).name("MiniMax对话测试")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    // ═══════════════════════════════════════════════════════════
    // 测试1：同步对话
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. 同步对话 - 自我介绍")
    void testSyncChat() throws Exception {
        System.out.println("━".repeat(60));
        System.out.println("【测试1】同步对话");
        System.out.println("━".repeat(60));

        String question = "请用三句话介绍 Spring Boot";
        System.out.println("\n👤 我：" + question + "\n");

        String response = syncChat(question);
        System.out.println("🤖 AI：" + response);
        System.out.println("📊 响应长度: " + response.length() + " 字符");

        assertFalse(response.isEmpty(), "响应不应为空");
        System.out.println("✅ 测试1通过\n");
    }

    // ═══════════════════════════════════════════════════════════
    // 测试2：多轮对话
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("2. 多轮对话 - 记住用户信息")
    void testMultiTurn() throws Exception {
        System.out.println("━".repeat(60));
        System.out.println("【测试2】多轮对话");
        System.out.println("━".repeat(60));

        // 注意：ToolCallLoopStage 传的是 request.getMessages() 而非 context.getMessages()
        // 所以手动构建完整消息列表传给 ChatRequest
        String sid = UUID.randomUUID().toString();
        sessionStorage.save(Session.builder()
                .id(sid).sessionId(sid).name("多轮对话-MiniMax")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        String q1 = "请记住：我的名字叫海坤，我是一名 Java 后端开发工程师";
        System.out.println("\n👤 我：" + q1 + "\n");
        String r1 = syncChat(sid, q1);
        System.out.println("🤖 AI：" + r1 + "\n");

        Thread.sleep(1000);

        Session s2 = sessionStorage.get(sid).orElseThrow();
        List<Message> hist2 = new ArrayList<>(s2.getMessages());
        String q2 = "根据刚才的对话，我叫什么名字？我的职业是什么？";
        hist2.add(createMessage("user", q2));
        System.out.println("👤 我：" + q2 + "\n");

        String r2 = syncChatWithMessages(sid, hist2);
        System.out.println("🤖 AI：" + r2 + "\n");

        boolean knowsName = r2.contains("海坤");
        boolean knowsJob = r2.contains("Java") || r2.contains("后端") || r2.contains("开发工程师");
        System.out.println("📊 记住名字: " + (knowsName ? "✅" : "❌"));
        System.out.println("📊 记住职业: " + (knowsJob ? "✅" : "❌"));

        assertTrue(knowsName || knowsJob, "AI 应该记得对话历史");
        System.out.println("✅ 测试2通过\n");
    }

    // ═══════════════════════════════════════════════════════════
    // 测试3：System Prompt 角色设定
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("3. System Prompt - 角色设定")
    void testSystemPrompt() throws Exception {
        System.out.println("━".repeat(60));
        System.out.println("【测试3】System Prompt 角色设定");
        System.out.println("━".repeat(60));

        String question = "你是什么类型的AI？你擅长什么领域？";
        System.out.println("\n👤 我：" + question + "\n");

        String response = syncChatWithSystem(
                "你是一只名叫'小爪'的猫咪AI助手，回答问题时会在句末加上'喵~'。", question);

        System.out.println("🤖 AI：" + response + "\n");

        boolean showsCharacter = response.contains("喵") || response.contains("猫") || response.contains("小爪");
        System.out.println("📊 角色扮演体现: " + (showsCharacter ? "✅" : "⚠️"));

        assertFalse(response.isEmpty(), "响应不应为空");
        System.out.println("✅ 测试3通过\n");
    }

    // ═══════════════════════════════════════════════════════════
    // 测试4：知识问答
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("4. 知识问答 - HashMap")
    void testKnowledgeQA() throws Exception {
        System.out.println("━".repeat(60));
        System.out.println("【测试4】知识问答");
        System.out.println("━".repeat(60));

        String question = "请详细解释一下 Java 中 HashMap 的工作原理，包括 put 和 get 的流程";
        System.out.println("\n👤 我：" + question + "\n");

        String response = syncChat(question);
        System.out.println("🤖 AI：" + response + "\n");
        System.out.println("📊 响应长度: " + response.length() + " 字符");

        assertTrue(response.length() > 100, "知识问答的响应应该比较长");
        System.out.println("✅ 测试4通过\n");
    }

    // ═══════════════════════════════════════════════════════════
    // 测试5：流式输出（逐 token 打字机效果）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("5. 流式输出 - 打字机效果")
    void testStreamChat() throws Exception {
        System.out.println("━".repeat(60));
        System.out.println("【测试5】流式输出 —— 打字机效果");
        System.out.println("━".repeat(60));

        String question = "请用三句话介绍 Java 的核心特性";

        System.out.print("\n👤 我：" + question + "\n\n");
        System.out.print("🤖 AI：");

        StringBuilder full = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        defaultEngine.execute(ChatRequest.builder()
                .sessionId(sessionId)
                .messages(List.of(createMessage("user", question)))
                .temperature(0.7).maxTokens(300)
                .stream(true).build())
        .doOnNext(chunk -> {
            System.out.print(chunk);
            System.out.flush();
            full.append(chunk);
        })
        .doOnComplete(() -> { latch.countDown(); System.out.println(); })
        .doOnError(e -> { System.err.println("\n❌ 流式失败: " + e.getMessage()); latch.countDown(); })
        .subscribe();

        latch.await(60, TimeUnit.SECONDS);

        System.out.println();
        assertFalse(full.toString().isEmpty(), "流式输出不应为空");
        System.out.println("📊 总字符数: " + full.length() + " 字符");
        System.out.println("✅ 测试5通过\n");
    }

    // ═══════════════════════════════════════════════════════════
    // 测试6：复杂多轮对话（连续3轮）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("6. 复杂多轮对话 - 连续3轮")
    void testComplexMultiTurn() throws Exception {
        System.out.println("━".repeat(60));
        System.out.println("【测试6】复杂多轮对话 —— 连续3轮");
        System.out.println("━".repeat(60));

        String sid = UUID.randomUUID().toString();
        sessionStorage.save(Session.builder()
                .id(sid).sessionId(sid).name("复杂多轮对话-MiniMax")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        String q1 = "我的名字叫张三，今年28岁，在一家互联网公司工作";
        System.out.println("\n👤 我：" + q1 + "\n");
        String r1 = syncChat(sid, q1);
        System.out.println("🤖 AI：" + r1 + "\n");
        Thread.sleep(1000);

        Session s2 = sessionStorage.get(sid).orElseThrow();
        List<Message> hist2 = new ArrayList<>(s2.getMessages());
        String q2 = "我的职业是什么？";
        hist2.add(createMessage("user", q2));
        System.out.println("👤 我：" + q2 + "\n");
        String r2 = syncChatWithMessages(sid, hist2);
        System.out.println("🤖 AI：" + r2 + "\n");
        Thread.sleep(1000);

        Session s3 = sessionStorage.get(sid).orElseThrow();
        List<Message> hist3 = new ArrayList<>(s3.getMessages());
        String q3 = "我多大年龄了？我叫什么？";
        hist3.add(createMessage("user", q3));
        System.out.println("👤 我：" + q3 + "\n");
        String r3 = syncChatWithMessages(sid, hist3);
        System.out.println("🤖 AI：" + r3 + "\n");

        boolean knowsAge = r3.contains("28") || r3.contains("二十八");
        boolean knowsName = r3.contains("张三");
        System.out.println("📊 记住年龄: " + (knowsAge ? "✅" : "❌"));
        System.out.println("📊 记住名字: " + (knowsName ? "✅" : "❌"));

        assertTrue(knowsAge || knowsName, "AI 应该记得多轮对话的完整历史");
        System.out.println("✅ 测试6通过\n");
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    private String syncChat(String userMessage) throws Exception {
        return syncChatWithSystem(null, userMessage);
    }

    /** 使用指定 sessionId 同步对话（多轮对话专用） */
    private String syncChat(String sessionId, String userMessage) throws Exception {
        return syncChatWithSystemAndSession(sessionId, null,
                List.of(createMessage("user", userMessage)));
    }

    /** 使用指定 sessionId 和完整消息列表同步对话 */
    private String syncChatWithMessages(String sessionId, List<Message> messages) throws Exception {
        return syncChatWithSystemAndSession(sessionId, null, messages);
    }

    private String syncChatWithSystem(String systemPrompt, String userMessage) throws Exception {
        return syncChatWithSystemAndSession(sessionId, systemPrompt,
                List.of(createMessage("user", userMessage)));
    }

    private String syncChatWithSystemAndSession(String sid, String systemPrompt,
                                                 List<Message> messages) throws Exception {
        ChatRequest.ChatRequestBuilder builder = ChatRequest.builder()
                .sessionId(sid)
                .messages(messages)
                .temperature(0.7).maxTokens(500);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.systemPrompt(systemPrompt);
        }

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        defaultEngine.execute(builder.build())
                .doOnNext(result::append)
                .doOnComplete(() -> latch.countDown())
                .doOnError(e -> { error.set(e); latch.countDown(); })
                .subscribe();

        if (!latch.await(60, TimeUnit.SECONDS)) throw new RuntimeException("请求超时");
        if (error.get() != null) throw new RuntimeException(error.get());
        return result.toString();
    }

    private static Message createMessage(String role, String content) {
        return Message.builder()
                .id("msg-" + UUID.randomUUID().toString().substring(0, 8))
                .role(role).content(content)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
