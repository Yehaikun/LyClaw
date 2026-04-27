package lyjew.com.lyclaw.adapter;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.storage.ConfigStorage;
import lyjew.com.lyclaw.storage.MemoryStorage;
import lyjew.com.lyclaw.storage.SessionStorage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 适配器 + 存储层 集成测试
 *
 * 模拟完整的 AI 对话场景：
 * 1. 存储层配置模型（写入 configs/ 目录）
 * 2. 创建会话（写入 sessions/ 目录）
 * 3. 进行多轮 AI 对话
 * 4. 对话结果持久化到会话文件
 * 5. 生成短期记忆（写入 memory/ 目录）
 * 6. 模拟新会话启动时读取记忆进行恢复
 */
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdapterStorageIntegrationTest {

    // ========================================================================
    // 配置常量
    // ========================================================================

    /** MiniMax API Key */
    private static final String MINIMAX_API_KEY = "sk-cp-f77oYRQUTcc0axeEVGq2KymcFp6mHEHhJD_uO1yUWEotBGhI90-zDwnJBAQIvlaoRzhL_vcrlVS_D4VqX2yFBkMNrTOcamt5_YscyumkPxJckbw1erj9vyI";

    /** DeepSeek API Key */
    private static final String DEEPSEEK_API_KEY = "sk-b1da578246114c2383616f49b5651f1d";

    /** 记忆文件日期前缀 */
    private static final String TODAY = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

    // ========================================================================
    // 依赖注入
    // ========================================================================

    @Autowired
    private ConfigStorage configStorage;

    @Autowired
    private SessionStorage sessionStorage;

    @Autowired
    private MemoryStorage memoryStorage;

    @Autowired
    private ModelAdapterFactory adapterFactory;

    // ========================================================================
    // 测试状态
    // ========================================================================

    /** 创建的会话ID */
    private static String testSessionId;

    /** 记忆文件ID列表（用于清理） */
    private static final List<String> memoryIds = new ArrayList<>();

    /** 当前使用的适配器 */
    private ModelAdapter adapter;

    // ========================================================================
    // 测试1：配置模型——写入存储层
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("1. 存储层配置模型——写入 configs/ 目录")
    void testConfigureModels() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("测试1：存储层配置模型");
        System.out.println("=".repeat(70));

        // ---- 配置 MiniMax ----
        ModelConfig minimaxConfig = ModelConfig.builder()
                .id("cfg-minimax-integration")
                .name("minimax-test")
                .provider("minimax")
                .apiKey(MINIMAX_API_KEY)
                .model("MiniMax-M2.7")
                .baseUrl("https://api.minimaxi.com")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configStorage.save(minimaxConfig);

        // 验证写入
        Optional<ModelConfig> saved = configStorage.get("minimax-test");
        assertTrue(saved.isPresent(), "MiniMax 配置应成功保存");
        assertEquals("MiniMax-M2.7", saved.get().getModel());

        System.out.println("✅ MiniMax 配置已保存: configs/minimax-test.json");

        // ---- 配置 DeepSeek ----
        ModelConfig deepseekConfig = ModelConfig.builder()
                .id("cfg-deepseek-integration")
                .name("deepseek-test")
                .provider("deepseek-openai")
                .apiKey(DEEPSEEK_API_KEY)
                .model("deepseek-chat")
                .baseUrl("https://api.deepseek.com")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configStorage.save(deepseekConfig);

        saved = configStorage.get("deepseek-test");
        assertTrue(saved.isPresent(), "DeepSeek 配置应成功保存");
        assertEquals("deepseek-chat", saved.get().getModel());

        System.out.println("✅ DeepSeek 配置已保存: configs/deepseek-test.json");
        System.out.println();
    }

    // ========================================================================
    // 测试2：创建会话——写入 sessions/ 目录
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("2. 创建会话——写入 sessions/ 目录")
    void testCreateSession() {
        System.out.println("=".repeat(70));
        System.out.println("测试2：创建会话");
        System.out.println("=".repeat(70));

        testSessionId = UUID.randomUUID().toString();

        Session session = Session.builder()
                .id(testSessionId)
                .name("集成测试会话")
                .model("minimax-test")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        sessionStorage.save(session);

        // 验证
        Optional<Session> saved = sessionStorage.get(testSessionId);
        assertTrue(saved.isPresent(), "会话应成功保存");
        assertEquals("集成测试会话", saved.get().getName());

        System.out.println("✅ 会话已创建: sessions/" + testSessionId + ".json");
        System.out.println("   会话ID: " + testSessionId);
        System.out.println();
    }

    // ========================================================================
    // 测试3：第1轮对话——简单问候并持久化
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("3. 第1轮对话——简单问候（MiniMax）并持久化到会话")
    void testFirstRoundChat() {
        System.out.println("=".repeat(70));
        System.out.println("测试3：第1轮对话（MiniMax）");
        System.out.println("=".repeat(70));

        // 获取适配器
        Optional<ModelConfig> config = configStorage.get("minimax-test");
        assertTrue(config.isPresent());
        adapter = adapterFactory.getConfiguredAdapter(config.get());

        // 获取会话
        Optional<Session> sessionOpt = sessionStorage.get(testSessionId);
        assertTrue(sessionOpt.isPresent());
        Session session = sessionOpt.get();

        // 构建用户消息
        Message userMsg = Message.builder()
                .id("msg-u1-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("你好！我叫海坤，我正在开发一个叫做 LyClaw 的 AI 网关应用。请记住这些信息。")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(userMsg);

        // 调用 AI
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(session.getMessages()))
                .temperature(0.7)
                .maxTokens(300)
                .build();

        ModelResponse response = adapter.chat(request);
        assertNotNull(response.getContent());

        // 将 AI 回复加入会话
        Message aiMsg = Message.builder()
                .id("msg-a1-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content(response.getContent())
                .model(response.getModel())
                .usage(response.getUsage())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiMsg);

        session.setUpdatedAt(LocalDateTime.now());
        sessionStorage.save(session);

        System.out.println("👤 用户: " + userMsg.getContent());
        System.out.println("🤖 AI:   " + truncate(response.getContent(), 120));
        System.out.println("   📊 Token: prompt=" + response.getUsage().getPromptTokens()
                + ", completion=" + response.getUsage().getCompletionTokens()
                + ", total=" + response.getUsage().getTotalTokens());
        System.out.println("✅ 第1轮对话已保存，会话消息数: " + session.getMessages().size());
        System.out.println();
    }

    // ========================================================================
    // 测试4：第2轮对话——追问个人信息
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("4. 第2轮对话——追问（验证模型记住了第1轮的上下文）")
    void testSecondRoundChat() {
        System.out.println("=".repeat(70));
        System.out.println("测试4：第2轮对话（上下文验证）");
        System.out.println("=".repeat(70));

        // ★ 修复：重新获取 MiniMax 适配器
        Optional<ModelConfig> config = configStorage.get("minimax-test");
        assertTrue(config.isPresent());
        adapter = adapterFactory.getConfiguredAdapter(config.get());

        Optional<Session> sessionOpt = sessionStorage.get(testSessionId);
        assertTrue(sessionOpt.isPresent());
        Session session = sessionOpt.get();

        // 追问第1轮的信息
        Message userMsg = Message.builder()
                .id("msg-u2-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("我叫什么名字？我正在开发什么应用？")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(userMsg);

        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(session.getMessages()))
                .temperature(0.7)
                .maxTokens(300)
                .build();

        ModelResponse response = adapter.chat(request);
        assertNotNull(response.getContent());

        // 验证模型记住了上下文
        String reply = response.getContent().toLowerCase();
        assertTrue(reply.contains("海坤") || reply.contains("haikun"),
                "AI 应该记住用户的名字'海坤'");
        assertTrue(reply.contains("lyclaw") || reply.contains("LyClaw"),
                "AI 应该记住应用名'LyClaw'");

        Message aiMsg = Message.builder()
                .id("msg-a2-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content(response.getContent())
                .model(response.getModel())
                .usage(response.getUsage())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiMsg);

        session.setUpdatedAt(LocalDateTime.now());
        sessionStorage.save(session);

        System.out.println("👤 用户: " + userMsg.getContent());
        System.out.println("🤖 AI:   " + truncate(response.getContent(), 120));
        System.out.println("✅ 第2轮对话已保存，会话消息数: " + session.getMessages().size());
        System.out.println();
    }

    // ========================================================================
    // 测试5：第3轮对话——换 DeepSeek 继续对话
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("5. 第3轮对话——切换 DeepSeek 模型聊天")
    void testThirdRoundChatWithDeepSeek() throws InterruptedException {
        System.out.println("=".repeat(70));
        System.out.println("测试5：第3轮对话（切换 DeepSeek）");
        System.out.println("=".repeat(70));

        Thread.sleep(2000); // 避免限流

        Optional<Session> sessionOpt = sessionStorage.get(testSessionId);
        assertTrue(sessionOpt.isPresent());
        Session session = sessionOpt.get();

        // 切换到 DeepSeek
        Optional<ModelConfig> config = configStorage.get("deepseek-test");
        assertTrue(config.isPresent());
        adapter = adapterFactory.getConfiguredAdapter(config.get());

        Message userMsg = Message.builder()
                .id("msg-u3-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("帮我分析一下，如果要开发一个 AI 网关应用，需要考虑哪些核心技术点？列出 3-5 个。")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(userMsg);

        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(session.getMessages()))
                .temperature(0.7)
                .maxTokens(500)
                .build();

        ModelResponse response = adapter.chat(request);
        assertNotNull(response.getContent());
        assertFalse(response.getContent().isEmpty());

        Message aiMsg = Message.builder()
                .id("msg-a3-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content(response.getContent())
                .model(response.getModel())
                .usage(response.getUsage())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiMsg);

        session.setModel("deepseek-test"); // 更新会话当前模型
        session.setUpdatedAt(LocalDateTime.now());
        sessionStorage.save(session);

        System.out.println("👤 用户: " + userMsg.getContent());
        System.out.println("🤖 AI:   " + truncate(response.getContent(), 200));
        System.out.println("   📊 Token: prompt=" + response.getUsage().getPromptTokens()
                + ", completion=" + response.getUsage().getCompletionTokens()
                + ", total=" + response.getUsage().getTotalTokens());
        System.out.println("✅ 第3轮对话已保存（使用 DeepSeek），会话消息数: " + session.getMessages().size());
        System.out.println();
    }

    // ========================================================================
    // 测试6：生成短期记忆——持久化到 memory/yyyy-MM-dd-{序号}.md
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("6. 生成短期记忆——持久化到 memory/ 目录")
    void testGenerateMemory() {
        System.out.println("=".repeat(70));
        System.out.println("测试6：生成短期记忆");
        System.out.println("=".repeat(70));

        Optional<Session> sessionOpt = sessionStorage.get(testSessionId);
        assertTrue(sessionOpt.isPresent());
        Session session = sessionOpt.get();

        // 生成记忆ID（带序号）
        String memoryId1 = TODAY + "-001";
        String memoryId2 = TODAY + "-002";

        // ---- 记忆1：用户个人信息 ----
        String memoryContent1 = buildMemoryFromSession(session, "用户信息");
        Memory memory1 = Memory.builder()
                .id(memoryId1)
                .title("用户个人信息 - " + TODAY)
                .content(memoryContent1)
                .enabled(true)
                .tags(List.of("用户信息", "集成测试"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        memoryStorage.save(memory1);
        memoryIds.add(memoryId1);

        Optional<Memory> saved1 = memoryStorage.get(memoryId1);
        assertTrue(saved1.isPresent(), "记忆1应成功保存");

        System.out.println("✅ 短期记忆1已保存: memory/" + memoryId1 + ".md");
        System.out.println("   标题: " + memory1.getTitle());
        System.out.println("   内容预览: " + truncate(memoryContent1, 150));
        System.out.println();

        // ---- 记忆2：技术讨论摘要 ----
        String memoryContent2 = generateTechSummary(session);
        Memory memory2 = Memory.builder()
                .id(memoryId2)
                .title("技术讨论摘要 - " + TODAY)
                .content(memoryContent2)
                .enabled(true)
                .tags(List.of("技术讨论", "AI网关", "集成测试"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        memoryStorage.save(memory2);
        memoryIds.add(memoryId2);

        Optional<Memory> saved2 = memoryStorage.get(memoryId2);
        assertTrue(saved2.isPresent(), "记忆2应成功保存");

        System.out.println("✅ 短期记忆2已保存: memory/" + memoryId2 + ".md");
        System.out.println("   标题: " + memory2.getTitle());
        System.out.println("   内容预览: " + truncate(memoryContent2, 150));
        System.out.println();

        // 验证记忆列表
        List<Memory> allMemories = memoryStorage.getAll();
        long todayMemoryCount = allMemories.stream()
                .filter(m -> m.getId().startsWith(TODAY))
                .count();
        System.out.println("📋 今天的记忆文件: " + todayMemoryCount + " 个");
        System.out.println();
    }

    // ========================================================================
    // 测试7：AI 读取记忆进行恢复
    // ========================================================================

    @Test
    @Order(7)
    @DisplayName("7. AI 读取记忆恢复——模拟新会话启动时加载记忆")
    void testMemoryRecovery() throws InterruptedException {
        System.out.println("=".repeat(70));
        System.out.println("测试7：AI 读取记忆进行恢复");
        System.out.println("=".repeat(70));

        Thread.sleep(2000);

        // ---- 模拟新会话启动 ----
        System.out.println("📖 读取所有记忆文件...");
        List<Memory> allMemories = memoryStorage.getAll();
        List<Memory> todayMemories = allMemories.stream()
                .filter(m -> m.getId().startsWith(TODAY) && m.isEnabled())
                .toList();

        // 构建记忆上下文
        StringBuilder memoryContext = new StringBuilder();
        memoryContext.append("## 以下是之前对话中记录的重要信息（摘自短期记忆）\n\n");
        for (Memory mem : todayMemories) {
            memoryContext.append("### ").append(mem.getTitle()).append("\n");
            memoryContext.append(mem.getContent()).append("\n\n");
        }

        System.out.println("   找到 " + todayMemories.size() + " 条今天的记忆");
        System.out.println("   记忆上下文长度: " + memoryContext.length() + " 字符");
        System.out.println();

        // ---- 创建新会话 ----
        String newSessionId = UUID.randomUUID().toString();
        Session newSession = Session.builder()
                .id(newSessionId)
                .name("记忆恢复测试会话")
                .model("deepseek-test")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // ---- 让 AI 基于记忆回答问题 ----
        Optional<ModelConfig> config = configStorage.get("deepseek-test");
        assertTrue(config.isPresent());
        adapter = adapterFactory.getConfiguredAdapter(config.get());

        // 用户消息（测试AI是否记得记忆中的内容）
        Message userMsg = Message.builder()
                .id("msg-r1-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("根据你的记忆记录，请回答：\n"
                        + "1. 我叫什么名字？\n"
                        + "2. 我正在开发什么应用？\n"
                        + "3. 我们之前讨论过哪些技术话题？")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        newSession.addMessage(userMsg);

        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(newSession.getMessages()))
                .systemPrompt("你是一个 AI 助手。以下是从之前的对话中提取的记忆信息，请基于这些信息回答问题：\n\n"
                        + memoryContext.toString())
                .temperature(0.5)
                .maxTokens(600)
                .build();

        ModelResponse response = adapter.chat(request);
        assertNotNull(response.getContent());
        String reply = response.getContent();

        System.out.println("📤 用户: " + userMsg.getContent());
        System.out.println("📥 AI 回复:");
        System.out.println(reply);
        System.out.println();
        System.out.println("   📊 Token: prompt=" + response.getUsage().getPromptTokens()
                + ", completion=" + response.getUsage().getCompletionTokens()
                + ", total=" + response.getUsage().getTotalTokens());

        // 验证 AI 基于记忆正确回答
        String lowerReply = reply.toLowerCase();
        assertTrue(lowerReply.contains("海坤") || lowerReply.contains("haikun"),
                "AI 应从记忆中恢复用户名'海坤'");
        assertTrue(lowerReply.contains("lyclaw") || lowerReply.contains("LyClaw"),
                "AI 应从记忆中恢复应用名'LyClaw'");

        System.out.println("✅ 记忆恢复验证通过——AI 正确记住了之前的对话信息");
        System.out.println();
    }

    // ========================================================================
    // 测试8：验证完成后数据持久化完整性
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("8. 验证——数据持久化完整性")
    void testVerifyPersistence() {
        System.out.println("=".repeat(70));
        System.out.println("测试8：数据持久化完整性验证");
        System.out.println("=".repeat(70));

        // 验证会话
        Optional<Session> sessionOpt = sessionStorage.get(testSessionId);
        assertTrue(sessionOpt.isPresent(), "会话应持久化存在");
        Session session = sessionOpt.get();

        System.out.println("📁 会话: sessions/" + testSessionId + ".json");
        System.out.println("   名称: " + session.getName());
        System.out.println("   消息数: " + session.getMessages().size());
        System.out.println("   创建时间: " + session.getCreatedAt());
        System.out.println("   更新时间: " + session.getUpdatedAt());

        // 统计角色分布
        long userCount = session.getMessages().stream().filter(m -> "user".equals(m.getRole())).count();
        long aiCount = session.getMessages().stream().filter(m -> "assistant".equals(m.getRole())).count();
        System.out.println("   用户消息: " + userCount + " 条");
        System.out.println("   AI消息: " + aiCount + " 条");

        // 验证 Token 总计
        long totalPromptTokens = session.getMessages().stream()
                .filter(m -> m.getUsage() != null)
                .mapToLong(m -> m.getUsage().getPromptTokens()).sum();
        long totalCompletionTokens = session.getMessages().stream()
                .filter(m -> m.getUsage() != null)
                .mapToLong(m -> m.getUsage().getCompletionTokens()).sum();
        System.out.println("   总Prompt Token: " + totalPromptTokens);
        System.out.println("   总Completion Token: " + totalCompletionTokens);
        System.out.println("   总Token: " + (totalPromptTokens + totalCompletionTokens));
        System.out.println();

        // 验证记忆
        System.out.println("📁 记忆文件:");
        for (String id : memoryIds) {
            Optional<Memory> memOpt = memoryStorage.get(id);
            if (memOpt.isPresent()) {
                Memory mem = memOpt.get();
                System.out.println("   memory/" + id + ".md");
                System.out.println("      标题: " + mem.getTitle());
                System.out.println("      状态: " + (mem.isEnabled() ? "启用" : "禁用"));
                System.out.println("      标签: " + String.join(", ", mem.getTags()));
                System.out.println("      内容长度: " + mem.getContent().length() + " 字符");
            }
        }
        System.out.println();

        // 验证配置
        long configCount = configStorage.getAll().size();
        System.out.println("📁 配置: 共 " + configCount + " 个模型配置");
        System.out.println();

        System.out.println("✅ 数据持久化完整性验证通过");
        System.out.println();
    }

    // ========================================================================
    // 私有辅助方法
    // ========================================================================

    /**
     * 从会话中构建短期记忆内容
     */
    private String buildMemoryFromSession(Session session, String topic) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 会话概要\n\n");
        sb.append("- 会话ID: ").append(session.getId()).append("\n");
        sb.append("- 会话时间: ").append(TODAY).append("\n");
        sb.append("- 消息总数: ").append(session.getMessages().size()).append("\n\n");

        sb.append("## 用户消息记录\n\n");
        for (Message msg : session.getMessages()) {
            if ("user".equals(msg.getRole())) {
                sb.append("- **用户**: ").append(truncate(msg.getContent(), 100)).append("\n");
            }
        }

        sb.append("\n## AI 回复摘要\n\n");
        for (Message msg : session.getMessages()) {
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                sb.append("- ").append(truncate(msg.getContent(), 150)).append("\n");
            }
        }

        sb.append("\n## 关键信息提取\n\n");
        sb.append("- 用户名: 海坤\n");
        sb.append("- 开发项目: LyClaw AI 网关应用\n");
        sb.append("- 使用模型: MiniMax-M2.7, DeepSeek\n");

        return sb.toString();
    }

    /**
     * 生成技术讨论摘要记忆
     */
    private String generateTechSummary(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 技术讨论记录 (" + TODAY + ")\n\n");
        sb.append("**项目**: LyClaw — 本地 AI 网关应用\n\n");
        sb.append("**讨论的技术要点**:\n\n");
        sb.append("1. 模型抽象层设计\n");
        sb.append("   - 使用策略模式 + 模板方法模式屏蔽不同厂商 API 差异\n");
        sb.append("   - 已接入 MiniMax 和 DeepSeek\n\n");
        sb.append("2. 存储层设计\n");
        sb.append("   - 使用 JSON 文件存储，轻量级方案\n");
        sb.append("   - 支持会话、配置、记忆、定时任务四种数据类型\n\n");
        sb.append("3. 记忆系统\n");
        sb.append("   - 短期记忆: 会话级别的消息历史\n");
        sb.append("   - 长期记忆: 跨会话的 MEMORY.md\n");
        sb.append("   - 支持新会话加载记忆进行上下文恢复\n\n");
        sb.append("4. 流式响应\n");
        sb.append("   - 使用 SSE (Server-Sent Events) 实现逐 token 输出\n");
        sb.append("   - 基于 Reactor Flux 封装异步流式处理\n\n");
        sb.append("5. 扩展性设计\n");
        sb.append("   - 新增厂商适配器只需新建类，0 行已有代码改动\n");
        sb.append("   - MCP 协议支持工具热插拔\n");

        return sb.toString();
    }

    /**
     * 截断文本用于显示
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...（截断，共" + text.length() + "字符）";
    }
}