package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.model.Memory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MemoryStorageTest {

    @Autowired
    private MemoryStorage memoryStorage;

    private static String memoryId1;
    private static String memoryId2;
    private static String memoryId3;

    // ========== 创建多条记忆 ==========

    @Test
    @Order(1)
    @DisplayName("创建记忆1 - 用户偏好")
    void testCreatePreferenceMemory() {
        memoryId1 = UUID.randomUUID().toString();

        Memory memory = Memory.builder()
                .id(memoryId1)
                .content("## 用户偏好\n"
                        + "# LyClaw 存储层架构文档\n" +
                        "\n" +
                        "## 一、模块分层\n" +
                        "\n" +
                        "```\n" +
                        "lyclaw-core/                          ← 接口和抽象层（被所有模块依赖）\n" +
                        "├── repository/\n" +
                        "│   └── FileRepository.java           ← 文件仓库接口（原 StorageEngine）\n" +
                        "├── strategy/\n" +
                        "│   └── FormatStrategy.java           ← 格式化策略接口\n" +
                        "├── base/\n" +
                        "│   ├── BaseDTO.java                  ← 实体基类（id, createdAt, updatedAt）\n" +
                        "│   ├── BaseStorage.java              ← 存储模板类（组合 FileRepository + FormatStrategy）\n" +
                        "│   └── BaseEngine.java               ← 引擎基类（dataDir）\n" +
                        "└── support/\n" +
                        "    └── AbstractFileEngine.java       ← 文件引擎支持类（ObjectMapper 配置）\n" +
                        "\n" +
                        "lyclaw-storage/                        ← 实现层\n" +
                        "├── engine/\n" +
                        "│   └── LocalFileEngine.java          ← 实现 FileRepository，继承 AbstractFileEngine\n" +
                        "├── strategy/\n" +
                        "│   ├── JsonFormatStrategy.java       ← JSON 格式化策略\n" +
                        "│   └── MarkdownFormatStrategy.java   ← Markdown 格式化策略\n" +
                        "└── storage/\n" +
                        "    ├── SessionStorage.java           ← 继承 BaseStorage<Session>\n" +
                        "    ├── ConfigStorage.java            ← 继承 BaseStorage<ModelConfig>\n" +
                        "    ├── CronStorage.java              ← 继承 BaseStorage<CronJob>\n" +
                        "    └── MemoryStorage.java            ← 继承 BaseStorage<Memory>\n" +
                        "\n" +
                        "lyclaw-common/                         ← 纯实体类\n" +
                        "└── model/\n" +
                        "    ├── Session.java\n" +
                        "    ├── Message.java\n" +
                        "    ├── ModelConfig.java\n" +
                        "    ├── CronJob.java\n" +
                        "    ├── Memory.java\n" +
                        "    ├── ToolCall.java\n" +
                        "    └── Usage.java\n" +
                        "```\n" +
                        "\n" +
                        "## 二、类继承关系\n" +
                        "\n" +
                        "```\n" +
                        "BaseDTO                          ← 所有实体的父类\n" +
                        "├── Session\n" +
                        "├── Message\n" +
                        "├── ModelConfig\n" +
                        "├── CronJob\n" +
                        "├── Memory\n" +
                        "├── ToolCall\n" +
                        "└── Usage\n" +
                        "\n" +
                        "BaseEngine                       ← 引擎抽象基类\n" +
                        "└── AbstractFileEngine            ← 文件引擎支持类（封装 ObjectMapper）\n" +
                        "    └── LocalFileEngine            ← 具体实现，implements FileRepository\n" +
                        "\n" +
                        "BaseStorage<T>                    ← 存储模板类\n" +
                        "├── SessionStorage extends BaseStorage<Session>\n" +
                        "├── ConfigStorage extends BaseStorage<ModelConfig>\n" +
                        "├── CronStorage extends BaseStorage<CronJob>\n" +
                        "└── MemoryStorage extends BaseStorage<Memory>\n" +
                        "\n" +
                        "FormatStrategy<T>                 ← 格式化策略接口\n" +
                        "├── JsonFormatStrategy<T>         ← JSON 序列化/反序列化\n" +
                        "└── MarkdownFormatStrategy        ← Markdown 文本处理\n" +
                        "\n" +
                        "FileRepository                   ← 文件仓库接口\n" +
                        "└── LocalFileEngine implements FileRepository\n" +
                        "```\n" +
                        "\n" +
                        "## 三、BaseStorage 模板方法设计\n" +
                        "\n" +
                        "```\n" +
                        "BaseStorage<T>\n" +
                        "├── 构造函数：注入 FileRepository + subDir + FormatStrategy<T>\n" +
                        "│\n" +
                        "├── 公共方法（子类直接继承）\n" +
                        "│   ├── save(T entity)          → formatStrategy.serialize() → repository.write()\n" +
                        "│   ├── get(String id)          → repository.read() → formatStrategy.deserialize()\n" +
                        "│   ├── exists(String id)       → repository.exists()\n" +
                        "│   ├── delete(String id)       → repository.delete()\n" +
                        "│   └── getAll()                → repository.listFiles(suffix) → 逐个 deserialize\n" +
                        "│\n" +
                        "├── 钩子方法（子类可选重写）\n" +
                        "│   ├── beforeSave(T entity)    ← 保存前处理（如校验、自动生成字段）\n" +
                        "│   └── afterSave(T entity)     ← 保存后处理（如日志）\n" +
                        "│\n" +
                        "└── 抽象方法（子类必须实现）\n" +
                        "    ├── extractId(T entity)     ← 从实体提取文件标识\n" +
                        "    └── getEntityClass()        ← 返回实体类 Class 对象\n" +
                        "```\n" +
                        "\n" +
                        "## 四、数据模型 JSON 范例\n" +
                        "\n" +
                        "### 4.1 会话（Session）\n" +
                        "\n" +
                        "**文件路径**：`sessions/{uuid}.json`\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"550e8400-e29b-41d4-a716-446655440000\",\n" +
                        "  \"name\": \"河南农业大学多轮咨询\",\n" +
                        "  \"model\": \"minimax\",\n" +
                        "  \"messages\": [\n" +
                        "    {\n" +
                        "      \"id\": \"msg-u1-abc12345\",\n" +
                        "      \"role\": \"user\",\n" +
                        "      \"content\": \"河南农业大学有哪些优势学科？\",\n" +
                        "      \"createdAt\": \"2026-04-26T10:00:00\",\n" +
                        "      \"updatedAt\": \"2026-04-26T10:00:00\"\n" +
                        "    },\n" +
                        "    {\n" +
                        "      \"id\": \"msg-a1-def67890\",\n" +
                        "      \"role\": \"assistant\",\n" +
                        "      \"content\": \"河南农业大学是一所以农科为优势的综合性大学...\",\n" +
                        "      \"model\": \"minimax\",\n" +
                        "      \"usage\": {\n" +
                        "        \"id\": \"usage-xxx\",\n" +
                        "        \"promptTokens\": 52,\n" +
                        "        \"completionTokens\": 218,\n" +
                        "        \"totalTokens\": 270,\n" +
                        "        \"createdAt\": \"2026-04-26T10:00:05\",\n" +
                        "        \"updatedAt\": \"2026-04-26T10:00:05\"\n" +
                        "      },\n" +
                        "      \"toolCalls\": null,\n" +
                        "      \"createdAt\": \"2026-04-26T10:00:05\",\n" +
                        "      \"updatedAt\": \"2026-04-26T10:00:05\"\n" +
                        "    }\n" +
                        "  ],\n" +
                        "  \"createdAt\": \"2026-04-26T10:00:00\",\n" +
                        "  \"updatedAt\": \"2026-04-26T10:00:05\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "### 4.2 消息（Message）\n" +
                        "\n" +
                        "**嵌入在 Session.messages[] 中，不单独存文件**\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"msg-a3f-ghi11223\",\n" +
                        "  \"role\": \"assistant\",\n" +
                        "  \"content\": \"\",\n" +
                        "  \"model\": \"minimax\",\n" +
                        "  \"usage\": null,\n" +
                        "  \"toolCalls\": [\n" +
                        "    {\n" +
                        "      \"id\": \"tc-jkl33445\",\n" +
                        "      \"toolCallId\": \"call_weather_001\",\n" +
                        "      \"name\": \"get_weather\",\n" +
                        "      \"arguments\": \"{\\\"city\\\": \\\"郑州\\\", \\\"date\\\": \\\"today\\\"}\",\n" +
                        "      \"result\": null,\n" +
                        "      \"createdAt\": \"2026-04-26T10:05:00\",\n" +
                        "      \"updatedAt\": \"2026-04-26T10:05:00\"\n" +
                        "    }\n" +
                        "  ],\n" +
                        "  \"createdAt\": \"2026-04-26T10:05:00\",\n" +
                        "  \"updatedAt\": \"2026-04-26T10:05:00\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "### 4.3 工具调用（ToolCall）\n" +
                        "\n" +
                        "**嵌入在 Message.toolCalls[] 或单独作为 tool 角色消息**\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"tc-jkl33445\",\n" +
                        "  \"toolCallId\": \"call_weather_001\",\n" +
                        "  \"name\": \"get_weather\",\n" +
                        "  \"arguments\": \"{\\\"city\\\": \\\"郑州\\\", \\\"date\\\": \\\"today\\\"}\",\n" +
                        "  \"result\": null,\n" +
                        "  \"createdAt\": \"2026-04-26T10:05:00\",\n" +
                        "  \"updatedAt\": \"2026-04-26T10:05:00\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "**工具返回消息（role: tool）**：\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"msg-t3-mno55667\",\n" +
                        "  \"role\": \"tool\",\n" +
                        "  \"content\": \"郑州今日天气：晴转多云，温度18-28℃...\",\n" +
                        "  \"createdAt\": \"2026-04-26T10:05:02\",\n" +
                        "  \"updatedAt\": \"2026-04-26T10:05:02\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "### 4.4 Token 用量（Usage）\n" +
                        "\n" +
                        "**嵌入在 Message.usage 中**\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"usage-xxx\",\n" +
                        "  \"promptTokens\": 52,\n" +
                        "  \"completionTokens\": 218,\n" +
                        "  \"totalTokens\": 270,\n" +
                        "  \"createdAt\": \"2026-04-26T10:00:05\",\n" +
                        "  \"updatedAt\": \"2026-04-26T10:00:05\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "### 4.5 模型配置（ModelConfig）\n" +
                        "\n" +
                        "**文件路径**：`configs/{name}.json`\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"cfg-openai-001\",\n" +
                        "  \"name\": \"minimax\",\n" +
                        "  \"provider\": \"minimax\",\n" +
                        "  \"apiKey\": \"sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\",\n" +
                        "  \"model\": \"abab6.5s-chat\",\n" +
                        "  \"baseUrl\": \"https://api.minimax.chat/v1\",\n" +
                        "  \"enabled\": true,\n" +
                        "  \"createdAt\": \"2026-04-24T12:00:00\",\n" +
                        "  \"updatedAt\": \"2026-04-26T09:00:00\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "### 4.6 定时任务（CronJob）\n" +
                        "\n" +
                        "**文件路径**：`cron/{uuid}.json`\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"cron-0ea0bb0a4dea43a4a786b04cd957492a\",\n" +
                        "  \"name\": \"每日天气播报\",\n" +
                        "  \"cronExpr\": \"0 0 9 * * *\",\n" +
                        "  \"prompt\": \"搜索北京今日天气，整理成简短播报\",\n" +
                        "  \"model\": \"minimax\",\n" +
                        "  \"enabled\": true,\n" +
                        "  \"lastRunTime\": \"2026-04-26T09:00:00\",\n" +
                        "  \"lastRunStatus\": \"success\",\n" +
                        "  \"lastRunResult\": \"北京今日天气：晴，25°C，适合出行\",\n" +
                        "  \"nextRunTime\": \"2026-04-27T09:00:00\",\n" +
                        "  \"createdAt\": \"2026-04-24T12:00:00\",\n" +
                        "  \"updatedAt\": \"2026-04-26T09:00:05\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "### 4.7 长期记忆（Memory）\n" +
                        "\n" +
                        "**文件路径**：`memory/global.md`\n" +
                        "\n" +
                        "**文件内容**（Markdown 格式）：\n" +
                        "\n" +
                        "```markdown\n" +
                        "## 用户偏好\n" +
                        "- 喜欢用 Java，代码风格偏好 Builder 模式\n" +
                        "- 希望所有响应都有详细注释\n" +
                        "\n" +
                        "## 项目信息\n" +
                        "- 当前项目：电商用户认证系统\n" +
                        "- 技术栈：Spring Boot 3 + MyBatis Plus\n" +
                        "- 数据库：MySQL 8.0\n" +
                        "\n" +
                        "## 其他\n" +
                        "- 记住这个\n" +
                        "```\n" +
                        "\n" +
                        "**对应实体对象**（序列化前）：\n" +
                        "\n" +
                        "```json\n" +
                        "{\n" +
                        "  \"id\": \"global\",\n" +
                        "  \"content\": \"## 用户偏好\\n- 喜欢用 Java，代码风格偏好 Builder 模式\\n- 希望所有响应都有详细注释\\n\\n## 项目信息\\n- 当前项目：电商用户认证系统\\n- 技术栈：Spring Boot 3 + MyBatis Plus\\n- 数据库：MySQL 8.0\\n\\n## 其他\\n- 记住这个\",\n" +
                        "  \"title\": \"我的偏好与项目\",\n" +
                        "  \"enabled\": true,\n" +
                        "  \"tags\": [\"偏好\", \"项目\"],\n" +
                        "  \"createdAt\": \"2026-04-24T12:00:00\",\n" +
                        "  \"updatedAt\": \"2026-04-26T14:30:00\"\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "**注意**：Memory 使用 `MarkdownFormatStrategy`，文件后缀为 `.md`，存储的是 `content` 字段的原始 Markdown 文本，不包含其他元数据字段。\n" +
                        "\n" +
                        "## 五、FormatStrategy 策略扩展\n" +
                        "\n" +
                        "| 策略 | 后缀 | 适用实体 | 序列化方式 |\n" +
                        "|------|------|----------|-----------|\n" +
                        "| `JsonFormatStrategy<T>` | `.json` | Session、CronJob、ModelConfig 等 | Jackson ObjectMapper |\n" +
                        "| `MarkdownFormatStrategy` | `.md` | Memory | 直接读写 content 字段 |\n" +
                        "\n" +
                        "### 扩展新格式步骤\n" +
                        "\n" +
                        "1. 实现 `FormatStrategy<T>` 接口\n" +
                        "2. 放入 `lyclaw-storage/strategy/` 包\n" +
                        "3. 对应的 `BaseStorage<T>` 子类构造函数传入新策略\n" +
                        "\n" +
                        "```java\n" +
                        "// 示例：未来扩展 YAML 格式\n" +
                        "@Component\n" +
                        "public class ConfigYamlStorage extends BaseStorage<ModelConfig> {\n" +
                        "    public ConfigYamlStorage(FileRepository repository) {\n" +
                        "        super(repository, \"configs\", new YamlFormatStrategy<>());\n" +
                        "    }\n" +
                        "}\n" +
                        "```\n" +
                        "\n" +
                        "## 六、文件存储目录结构\n" +
                        "\n" +
                        "```\n" +
                        "LyClaw/                            ← dataDir（可配置）\n" +
                        "├── configs/\n" +
                        "│   ├── openai.json\n" +
                        "│   ├── minimax.json\n" +
                        "│   └── deepseek.json\n" +
                        "├── sessions/\n" +
                        "│   ├── {uuid1}.json\n" +
                        "│   ├── {uuid2}.json\n" +
                        "│   └── {uuid3}.json\n" +
                        "├── cron/\n" +
                        "│   ├── {uuid1}.json\n" +
                        "│   └── {uuid2}.json\n" +
                        "└── memory/\n" +
                        "    └── global.md                  ← 单例记忆文件\n" +
                        "```")
                .title("用户偏好")
                .enabled(true)
                .tags(List.of("偏好", "编码风格"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        memoryStorage.save(memory);

        System.out.println("=".repeat(60));
        System.out.println("✅ 创建记忆1: " + memory.getTitle());
        System.out.println("📁 记忆ID: " + memoryId1);
        System.out.println("📁 文件: memory/" + memoryId1 + ".md");
        System.out.println("=".repeat(60));
    }

    @Test
    @Order(2)
    @DisplayName("创建记忆2 - 项目信息")
    void testCreateProjectMemory() {
        memoryId2 = UUID.randomUUID().toString();

        Memory memory = Memory.builder()
                .id(memoryId2)
                .content("## 项目信息\n"
                        + "- 当前项目：LyClaw - AI 网关应用\n"
                        + "- 技术栈：Spring Boot 3.5.14 + Maven 多模块\n"
                        + "- 存储方案：JSON 文件 + Markdown\n"
                        + "- 开发环境：JDK 17 + Ubuntu")
                .title("项目信息")
                .enabled(true)
                .tags(List.of("项目", "技术栈"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        memoryStorage.save(memory);

        System.out.println("✅ 创建记忆2: " + memory.getTitle());
        System.out.println("📁 记忆ID: " + memoryId2);
        System.out.println();
    }

    @Test
    @Order(3)
    @DisplayName("创建记忆3 - 架构设计原则（已禁用）")
    void testCreateArchitectureMemory() {
        memoryId3 = UUID.randomUUID().toString();

        Memory memory = Memory.builder()
                .id(memoryId3)
                .content("## 架构设计原则\n"
                        + "- 上层调用下层，下层不依赖上层\n"
                        + "- AI 引擎层是核心，其他模块围绕它转\n"
                        + "- 接口定义在 core 模块，实现在 storage 模块")
                .title("架构设计原则")
                .enabled(false)
                .tags(List.of("架构"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        memoryStorage.save(memory);

        System.out.println("✅ 创建记忆3: " + memory.getTitle() + " (已禁用)");
        System.out.println("📁 记忆ID: " + memoryId3);
        System.out.println();
    }

    // ========== 查询 ==========

    @Test
    @Order(4)
    @DisplayName("查询 - 单条记忆")
    void testGetMemoryById() {
        Optional<Memory> opt = memoryStorage.get(memoryId1);
        assertTrue(opt.isPresent());
        Memory memory = opt.get();

        assertEquals("用户偏好", memory.getTitle());
        assertTrue(memory.getContent().contains("Builder 模式"));
        assertTrue(memory.isEnabled());
        assertEquals(2, memory.getTags().size());

        System.out.println("📖 记忆1:");
        System.out.println("   标题: " + memory.getTitle());
        System.out.println("   内容长度: " + memory.getContent().length() + " 字符");
        System.out.println("   状态: " + (memory.isEnabled() ? "启用" : "禁用"));
        System.out.println("   标签: " + String.join(", ", memory.getTags()));
        System.out.println("✅ 查询成功");
        System.out.println();
    }

    // ========== 列表 ==========

    @Test
    @Order(5)
    @DisplayName("列表 - 列出所有记忆")
    void testListAllMemories() {
        List<Memory> all = memoryStorage.getAll();
        assertTrue(all.size() >= 3);

        System.out.println("📋 记忆列表（共 " + all.size() + " 条）:");
        System.out.println("-".repeat(50));

        for (Memory m : all) {
            String shortId = m.getId().substring(0, Math.min(8, m.getId().length()));
            System.out.printf("   [%s...] %-12s | %s | %d字符%n",
                    shortId,
                    m.getTitle(),
                    m.isEnabled() ? "✅启用" : "⛔禁用",
                    m.getContent().length());
        }
        System.out.println();
    }

    // ========== 更新 ==========

    @Test
    @Order(6)
    @DisplayName("更新 - 修改记忆1的内容和标题")
    void testUpdateMemory() {
        Optional<Memory> opt = memoryStorage.get(memoryId1);
        assertTrue(opt.isPresent());
        Memory memory = opt.get();

        memory.setTitle("用户偏好（已更新）");
        memory.setContent(memory.getContent() + "\n- 新增：喜欢用 Markdown 写文档");
        memory.setTags(List.of("偏好", "编码风格", "文档"));
        memory.setUpdatedAt(LocalDateTime.now());

        memoryStorage.save(memory);

        Optional<Memory> verify = memoryStorage.get(memoryId1);
        assertTrue(verify.isPresent());
        assertEquals("用户偏好（已更新）", verify.get().getTitle());
        assertTrue(verify.get().getContent().contains("Markdown"));
        assertEquals(3, verify.get().getTags().size());

        System.out.println("🔄 更新记忆1:");
        System.out.println("   标题: 用户偏好 → 用户偏好（已更新）");
        System.out.println("   新增内容: Markdown 文档偏好");
        System.out.println("   标签数: 2 → 3");
        System.out.println("✅ 更新成功");
        System.out.println();
    }

    // ========== 禁用/启用 ==========

    @Test
    @Order(7)
    @DisplayName("开关 - 禁用记忆2")
    void testDisableMemory() {
        Optional<Memory> opt = memoryStorage.get(memoryId2);
        assertTrue(opt.isPresent());
        Memory memory = opt.get();

        memory.setEnabled(false);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryStorage.save(memory);

        Optional<Memory> verify = memoryStorage.get(memoryId2);
        assertFalse(verify.get().isEnabled());

        System.out.println("⛔ 记忆2已禁用");
        System.out.println();
    }

    @Test
    @Order(8)
    @DisplayName("开关 - 启用记忆3")
    void testEnableMemory() {
        Optional<Memory> opt = memoryStorage.get(memoryId3);
        assertTrue(opt.isPresent());
        Memory memory = opt.get();

        memory.setEnabled(true);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryStorage.save(memory);

        Optional<Memory> verify = memoryStorage.get(memoryId3);
        assertTrue(verify.get().isEnabled());

        System.out.println("✅ 记忆3已启用");
        System.out.println();
    }

    // ========== 删除 ==========

    @Test
    @Order(9)
    @DisplayName("删除 - 删除记忆3")
    void testDeleteMemory() {
        assertTrue(memoryStorage.exists(memoryId3));

        boolean deleted = memoryStorage.delete(memoryId3);
        assertTrue(deleted);
        assertFalse(memoryStorage.exists(memoryId3));

        System.out.println("🗑️ 已删除记忆3");
        System.out.println("   剩余记忆数: " + memoryStorage.getAll().size());
        System.out.println();
    }

    // ========== 最终验证 ==========

    @Test
    @Order(10)
    @DisplayName("验证 - 最终记忆列表")
    void testFinalMemoryList() {
        List<Memory> all = memoryStorage.getAll();
        assertEquals(2, all.size());

        System.out.println("=".repeat(60));
        System.out.println("📋 最终记忆列表（共 " + all.size() + " 条）");
        System.out.println("=".repeat(60));

        for (Memory m : all) {
            System.out.println("   📌 " + m.getTitle());
            System.out.println("      ID: " + m.getId());
            System.out.println("      状态: " + (m.isEnabled() ? "启用" : "禁用"));
            System.out.println("      标签: " + String.join(", ", m.getTags()));
            System.out.println("      内容: " + m.getContent().substring(0, Math.min(80, m.getContent().length())).replace("\n", " ") + "...");
            System.out.println("      创建: " + m.getCreatedAt());
            System.out.println("      更新: " + m.getUpdatedAt());
            System.out.println();
        }

        System.out.println("✅ 记忆存储层测试全部通过！");
    }
}