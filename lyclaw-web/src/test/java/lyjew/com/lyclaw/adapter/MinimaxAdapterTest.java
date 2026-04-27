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
 * MiniMax 适配器集成测试
 *
 * 测试内容：
 * 1. 适配器注册——验证工厂能找到 minimax 适配器
 * 2. 配置注入——验证 configure() 方法正确注入 apiKey/baseUrl/model
 * 3. API Key 验证——发送最小 token 请求验证 Key 有效性
 * 4. 同步对话——单轮简单对话
 * 5. 同步对话——带 system prompt 的对话
 * 6. 同步对话——多轮对话（含上下文）
 * 7. 流式对话——SSE 流式输出
 * 8. Token 估算——countTokens() 方法
 *
 * 注意：测试需要有效的 API Key 和网络连接
 */
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MinimaxAdapterTest {

    @Autowired
    private ModelAdapterFactory factory;

    private ModelAdapter adapter;

    /** MiniMax API Key——测试用 */
    private static final String API_KEY = "sk-cp-f77oYRQUTcc0axeEVGq2KymcFp6mHEHhJD_uO1yUWEotBGhI90-zDwnJBAQIvlaoRzhL_vcrlVS_D4VqX2yFBkMNrTOcamt5_YscyumkPxJckbw1erj9vyI";

    /** MiniMax 模型 */
    private static final String MODEL = "MiniMax-M2.7";

    /** MiniMax API 端点 */
    private static final String BASE_URL = "https://api.minimaxi.com";

    // ========================================================================
    // 初始化与清理
    // ========================================================================

    @BeforeEach
    void setUp() {
        // 从工厂获取 minimax 适配器
        adapter = factory.getAdapter("minimax");
        assertNotNull(adapter, "适配器不应为 null");

        // 注入配置
        ModelConfig config = ModelConfig.builder()
                .name("minimax-test")
                .provider("minimax")
                .apiKey(API_KEY)
                .model(MODEL)
                .baseUrl(BASE_URL)
                .enabled(true)
                .build();

        adapter.configure(config);
    }

    // ========================================================================
    // 测试1：适配器注册验证
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("1. 适配器注册——验证工厂能找到 minimax 适配器")
    void testAdapterRegistration() {
        System.out.println("=".repeat(60));
        System.out.println("测试1：适配器注册验证");
        System.out.println("=".repeat(60));

        // 验证工厂中已注册
        assertTrue(factory.hasProvider("minimax"), "应该注册了 minimax 适配器");

        // 验证适配器信息
        assertNotNull(adapter, "适配器不应为 null");
        assertEquals("minimax", adapter.getProvider(), "provider 应为 minimax");
        assertTrue(adapter.isConfigured(), "适配器应该已完成配置");
        assertEquals(MODEL, adapter.getModel(), "模型名应为 " + MODEL);
        assertEquals(BASE_URL, adapter.getBaseUrl(), "Base URL 应为 " + BASE_URL);

        System.out.println("✅ 适配器注册验证通过");
        System.out.println("   provider: " + adapter.getProvider());
        System.out.println("   model:    " + adapter.getModel());
        System.out.println("   baseUrl:  " + adapter.getBaseUrl());
        System.out.println();
    }

    // ========================================================================
    // 测试2：API Key 连接验证
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

        System.out.println("✅ API Key 验证通过（MiniMax 服务可达）");
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

        // 构建请求——只需一条用户消息
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

        // 发送请求
        ModelResponse response = adapter.chat(request);

        // 验证响应
        assertNotNull(response, "响应不应为 null");
        assertNotNull(response.getId(), "响应ID不应为 null");
        assertNotNull(response.getContent(), "回复内容不应为 null");
        assertFalse(response.getContent().isEmpty(), "回复内容不应为空");
        assertNotNull(response.getModel(), "模型名不应为 null");
        assertNotNull(response.getFinishReason(), "停止原因不应为 null");
        assertNotNull(response.getUsage(), "Token用量不应为 null");
        assertTrue(response.getUsage().getTotalTokens() > 0, "Token总量应 > 0");

        // 打印结果
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

        // 构建请求——包含 system prompt
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

        // 发送请求
        ModelResponse response = adapter.chat(request);

        // 验证
        assertNotNull(response, "响应不应为 null");
        assertNotNull(response.getContent(), "回复内容不应为 null");
        assertFalse(response.getContent().isEmpty(), "回复内容不应为空");
        assertTrue(response.getContent().contains("河南农业大学")
                        || response.getContent().contains("招生")
                        || response.getContent().contains("助手"),
                "回复应该包含 system prompt 中设定的角色信息");

        // 打印
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
    // 测试5：多轮对话（含上下文）
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("5. 同步对话——多轮对话（含上下文）")
    void testMultiTurnChat() {
        System.out.println("=".repeat(60));
        System.out.println("测试5：多轮对话（含上下文）");
        System.out.println("=".repeat(60));

        List<Message> messages = new ArrayList<>();

        // ===== 第1轮 =====
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

        // 将第1轮 AI 回复加入上下文
        Message aiMsg1 = Message.builder()
                .role("assistant")
                .content(response1.getContent())
                .build();
        messages.add(aiMsg1);

        System.out.println("📤 第1轮 用户: 请记住：我最喜欢的编程语言是 Java");
        System.out.println("📥 第1轮 AI: " + response1.getContent());
        System.out.println();

        // ===== 第2轮 =====
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

        // 验证第2轮回复中包含 "Java"（上下文被正确传递）
        assertTrue(response2.getContent().toLowerCase().contains("java"),
                "第2轮回复应该包含 'Java'（因为上下文中有第1轮的信息）");

        System.out.println("✅ 多轮对话测试通过（模型正确记住了上下文中的信息）");
        System.out.println();
    }

    // ========================================================================
    // 测试6：长回复（知识类问答）
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("6. 同步对话——长回复（知识类问答）")
    void testLongResponse() throws InterruptedException {
        System.out.println("=".repeat(60));
        System.out.println("测试6：长回复（知识类问答）");
        System.out.println("=".repeat(60));

        // ★ 加这一行：等待 2 秒，避免触发 MiniMax 频率限制
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

        // 验证
        assertNotNull(response.getContent(), "回复内容不应为 null");
        assertFalse(response.getContent().isEmpty(), "回复不应为空");
        assertTrue(response.getContent().length() > 100, "长回复应该超过 100 字符");
        assertTrue(response.getUsage().getCompletionTokens() > 50,
                "长回复的 completion Token 应 > 50");

        // 打印（截断）
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

        // 测试空字符串
        assertEquals(0, adapter.countTokens(""), "空字符串应为 0 token");
        assertEquals(0, adapter.countTokens(null), "null 应为 0 token");

        // 测试短文本
        int shortTokens = adapter.countTokens("Hello");
        System.out.println("   'Hello' → " + shortTokens + " tokens");

        // 测试中文
        int chineseTokens = adapter.countTokens("你好世界");
        System.out.println("   '你好世界' → " + chineseTokens + " tokens");

        // 测试长文本
        String longText = "Java 是一门面向对象的编程语言，吸收了 C++ 的各种优点，" +
                "还摒弃了 C++ 里难以理解的多继承、指针等概念。";
        int longTokens = adapter.countTokens(longText);
        System.out.println("   长文本(" + longText.length() + "字符) → " + longTokens + " tokens");

        // Token 数应该和字符数正相关
        assertTrue(longTokens > shortTokens, "长文本 Token 数应该 > 短文本");
        assertTrue(longTokens < longText.length(), "中文大约 1.5 字符/token，所以 token 数应 < 字符数");

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

        // 收集流式输出
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
                .blockLast(); // 阻塞等待流式完成

        // 验证
        assertFalse(fullOutput.toString().isEmpty(), "流式输出不应为空");
        System.out.println("\n📊 完整输出长度: " + fullOutput.length() + " 字符");
        System.out.println("✅ 流式对话测试通过");
        System.out.println();
    }

    // ========================================================================
    // 测试9：列表已注册的适配器
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
        assertTrue(providers.contains("minimax"), "应该包含 minimax");
        System.out.println("✅ 适配器列表验证通过");
        System.out.println();
    }
}