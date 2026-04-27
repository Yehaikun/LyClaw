package lyjew.com.lyclaw.adapter;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeepSeek OpenAI 适配器集成测试
 *
 * 测试内容：
 * 1. 适配器注册——验证工厂能找到 deepseek-openai 适配器
 * 2. 配置注入——验证 configure() 方法正确注入
 * 3. API Key 验证——发送最小 token 请求验证 Key 有效性
 * 4. 同步对话——单轮简单对话
 * 5. 同步对话——带 system prompt 的对话
 * 6. 同步对话——多轮对话（含上下文）
 * 7. 同步对话——长回复（知识类问答）
 * 8. Token 估算——countTokens() 方法
 * 9. 流式对话——SSE 流式输出
 * 10. 已注册适配器列表
 *
 * 注意：测试需要有效的 API Key 和网络连接
 */
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeepSeekOpenAIAdapterTest {

    @Autowired
    private ModelAdapterFactory factory;

    private ModelAdapter adapter;

    private static final String API_KEY = "sk-b1da578246114c2383616f49b5651f1d";
    private static final String MODEL = "deepseek-chat";
    private static final String BASE_URL = "https://api.deepseek.com";

    @BeforeEach
    void setUp() {
        adapter = factory.getAdapter("deepseek-openai");
        assertNotNull(adapter, "适配器不应为 null");

        ModelConfig config = ModelConfig.builder()
                .name("deepseek-test")
                .provider("deepseek-openai")
                .apiKey(API_KEY)
                .model(MODEL)
                .baseUrl(BASE_URL)
                .enabled(true)
                .build();

        adapter.configure(config);
    }

    // ========================================================================
    // 测试1：适配器注册
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("1. 适配器注册——验证工厂能找到 deepseek-openai 适配器")
    void testAdapterRegistration() {
        System.out.println("=".repeat(60));
        System.out.println("测试1：适配器注册验证");
        System.out.println("=".repeat(60));

        assertTrue(factory.hasProvider("deepseek-openai"), "应该注册了 deepseek-openai 适配器");
        assertNotNull(adapter);
        assertEquals("deepseek-openai", adapter.getProvider());
        assertTrue(adapter.isConfigured());
        assertEquals(MODEL, adapter.getModel());
        assertEquals(BASE_URL, adapter.getBaseUrl());

        System.out.println("✅ 适配器注册验证通过");
        System.out.println("   provider: " + adapter.getProvider());
        System.out.println("   model:    " + adapter.getModel());
        System.out.println("   baseUrl:  " + adapter.getBaseUrl());
        System.out.println();
    }

    // ========================================================================
    // 测试2：API Key 验证
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("2. API Key 验证——发送最小 token 请求验证 Key 有效")
    void testValidateApiKey() {
        System.out.println("=".repeat(60));
        System.out.println("测试2：API Key 连接验证");
        System.out.println("=".repeat(60));

        boolean valid = adapter.validate();
        assertTrue(valid, "API Key 应该有效，网络应该可达");

        System.out.println("✅ API Key 验证通过（DeepSeek 服务可达）");
        System.out.println();
    }

    // ========================================================================
    // 测试3：单轮简单对话
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("3. 同步对话——单轮简单问答")
    void testSimpleChat() {
        System.out.println("=".repeat(60));
        System.out.println("测试3：单轮简单对话");
        System.out.println("=".repeat(60));

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("你好，请用一句话介绍你自己")
                                .build()
                ))
                .temperature(0.7)
                .maxTokens(200)
                .build();

        ModelResponse response = adapter.chat(request);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotNull(response.getContent());
        assertFalse(response.getContent().isEmpty());
        assertNotNull(response.getModel());
        assertNotNull(response.getFinishReason());
        assertNotNull(response.getUsage());
        assertTrue(response.getUsage().getTotalTokens() > 0);

        System.out.println("📤 请求: 你好，请用一句话介绍你自己");
        System.out.println("📥 回复: " + response.getContent());
        System.out.println("   finishReason: " + response.getFinishReason());
        System.out.println("   Token用量: prompt=" + response.getUsage().getPromptTokens()
                + ", completion=" + response.getUsage().getCompletionTokens()
                + ", total=" + response.getUsage().getTotalTokens());
        System.out.println("✅ 单轮对话测试通过");
        System.out.println();
    }

    // ========================================================================
    // 测试4：带 system prompt 的对话
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("4. 同步对话——带 system prompt（设定角色）")
    void testChatWithSystemPrompt() {
        System.out.println("=".repeat(60));
        System.out.println("测试4：带 system prompt 的对话");
        System.out.println("=".repeat(60));

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("你是谁？你的职责是什么？")
                                .build()
                ))
                .systemPrompt("你是河南农业大学的招生助手，负责解答学生关于学校专业、分数线、校园生活等问题。" +
                        "你的语气亲切友好，就像学长学姐一样。")
                .temperature(0.7)
                .maxTokens(500)
                .build();

        ModelResponse response = adapter.chat(request);

        assertNotNull(response);
        assertNotNull(response.getContent());
        assertFalse(response.getContent().isEmpty());

        System.out.println("📤 System Prompt: 你是河南农业大学的招生助手...");
        System.out.println("📤 用户: 你是谁？你的职责是什么？");
        System.out.println("📥 回复: " + response.getContent());
        System.out.println("   Token用量: prompt=" + response.getUsage().getPromptTokens()
                + ", completion=" + response.getUsage().getCompletionTokens()
                + ", total=" + response.getUsage().getTotalTokens());
        System.out.println("✅ system prompt 对话测试通过");
        System.out.println();
    }

    // ========================================================================
    // 测试5：多轮对话
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("5. 同步对话——多轮对话（含上下文）")
    void testMultiTurnChat() throws InterruptedException {
        System.out.println("=".repeat(60));
        System.out.println("测试5：多轮对话（含上下文）");
        System.out.println("=".repeat(60));

        List<Message> messages = new ArrayList<>();

        // 第1轮
        Message userMsg1 = Message.builder()
                .role("user")
                .content("请记住：我最喜欢的编程语言是 Java")
                .build();
        messages.add(userMsg1);

        ChatRequest request1 = ChatRequest.builder()
                .messages(new ArrayList<>(messages))
                .temperature(0.7)
                .maxTokens(300)
                .build();

        ModelResponse response1 = adapter.chat(request1);

        Message aiMsg1 = Message.builder()
                .role("assistant")
                .content(response1.getContent())
                .build();
        messages.add(aiMsg1);

        System.out.println("📤 第1轮 用户: 请记住：我最喜欢的编程语言是 Java");
        System.out.println("📥 第1轮 AI: " + response1.getContent());
        System.out.println();

        // 等待 1 秒避免限流
        Thread.sleep(1000);

        // 第2轮
        Message userMsg2 = Message.builder()
                .role("user")
                .content("我刚才说我喜欢的编程语言是什么？")
                .build();
        messages.add(userMsg2);

        ChatRequest request2 = ChatRequest.builder()
                .messages(new ArrayList<>(messages))
                .temperature(0.7)
                .maxTokens(300)
                .build();

        ModelResponse response2 = adapter.chat(request2);

        System.out.println("📤 第2轮 用户: 我刚才说我喜欢的编程语言是什么？");
        System.out.println("📥 第2轮 AI: " + response2.getContent());
        System.out.println("   Token用量: prompt=" + response2.getUsage().getPromptTokens() + "...");
        System.out.println();

        assertTrue(response2.getContent().toLowerCase().contains("java"),
                "第2轮回复应该包含 'Java'（因为上下文中有第1轮的信息）");

        System.out.println("✅ 多轮对话测试通过");
        System.out.println();
    }

    // ========================================================================
    // 测试6：长回复
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("6. 同步对话——长回复（知识类问答）")
    void testLongResponse() throws InterruptedException {
        System.out.println("=".repeat(60));
        System.out.println("测试6：长回复（知识类问答）");
        System.out.println("=".repeat(60));

        // 等待 2 秒避免限流
        Thread.sleep(2000);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("请简单介绍一下 Java 中 HashMap 的实现原理，包括数据结构、put流程、扩容机制")
                                .build()
                ))
                .temperature(0.5)
                .maxTokens(1000)
                .build();

        ModelResponse response = adapter.chat(request);

        assertNotNull(response.getContent());
        assertFalse(response.getContent().isEmpty());
        assertTrue(response.getContent().length() > 100);

        String displayContent = response.getContent();
        if (displayContent.length() > 300) {
            displayContent = displayContent.substring(0, 300) + "...（截断，共"
                    + response.getContent().length() + "字符）";
        }
        System.out.println("📤 用户: 请简单介绍一下 Java 中 HashMap 的实现原理...");
        System.out.println("📥 回复: " + displayContent);
        System.out.println("   回复长度: " + response.getContent().length() + " 字符");
        System.out.println("   Token用量: prompt=" + response.getUsage().getPromptTokens()
                + ", completion=" + response.getUsage().getCompletionTokens()
                + ", total=" + response.getUsage().getTotalTokens());
        System.out.println("✅ 长回复测试通过");
        System.out.println();
    }

    // ========================================================================
    // 测试7：Token 估算
    // ========================================================================

    @Test
    @Order(7)
    @DisplayName("7. Token 估算——countTokens() 方法")
    void testCountTokens() {
        System.out.println("=".repeat(60));
        System.out.println("测试7：Token 估算");
        System.out.println("=".repeat(60));

        assertEquals(0, adapter.countTokens(""));
        assertEquals(0, adapter.countTokens(null));

        int shortTokens = adapter.countTokens("Hello");
        System.out.println("   'Hello' → " + shortTokens + " tokens");

        int chineseTokens = adapter.countTokens("你好世界");
        System.out.println("   '你好世界' → " + chineseTokens + " tokens");

        String longText = "Java 是一门面向对象的编程语言，吸收了 C++ 的各种优点，" +
                "还摒弃了 C++ 里难以理解的多继承、指针等概念。";
        int longTokens = adapter.countTokens(longText);
        System.out.println("   长文本(" + longText.length() + "字符) → " + longTokens + " tokens");

        assertTrue(longTokens > shortTokens);
        assertTrue(longTokens < longText.length());

        System.out.println("✅ Token 估算测试通过");
        System.out.println();
    }

    // ========================================================================
    // 测试8：流式对话
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("8. 流式对话——SSE 流式输出")
    void testStreamChat() throws InterruptedException {
        System.out.println("=".repeat(60));
        System.out.println("测试8：流式对话（SSE 流式输出）");
        System.out.println("=".repeat(60));

        // 等待 1 秒避免限流
        Thread.sleep(1000);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("用三句话介绍 Java")
                                .build()
                ))
                .temperature(0.7)
                .maxTokens(300)
                .build();

        StringBuilder fullOutput = new StringBuilder();

        adapter.chatStream(request)
                .doOnNext(chunk -> {
                    System.out.print(chunk);
                    fullOutput.append(chunk);
                })
                .doOnComplete(() -> System.out.println("\n✅ 流式对话完成"))
                .doOnError(error -> {
                    System.err.println("❌ 流式对话失败: " + error.getMessage());
                    error.printStackTrace();
                })
                .blockLast();

        assertFalse(fullOutput.toString().isEmpty());
        System.out.println("\n📊 完整输出长度: " + fullOutput.length() + " 字符");
        System.out.println("✅ 流式对话测试通过");
        System.out.println();
    }

    // ========================================================================
    // 测试9：已注册适配器列表
    // ========================================================================

    @Test
    @Order(9)
    @DisplayName("9. 辅助——列出所有已注册的适配器")
    void testListAdapters() {
        System.out.println("=".repeat(60));
        System.out.println("测试9：已注册的适配器列表");
        System.out.println("=".repeat(60));

        java.util.Set<String> providers = factory.listProviders();
        System.out.println("📋 已注册的适配器 (共 " + providers.size() + " 个):");
        for (String provider : providers) {
            System.out.println("   - " + provider);
        }
        assertTrue(providers.contains("deepseek-openai"));
        System.out.println("✅ 适配器列表验证通过");
        System.out.println();
    }
}