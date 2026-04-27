package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 存储层集成测试
 * 测试多轮对话、工具调用、长文本回复等场景
 */
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SessionStorageTest {

    @Autowired
    private SessionStorage sessionStorage;

    private static String testSessionId;

    // ========== 创建会话 ==========

    @Test
    @Order(1)
    @DisplayName("创建会话 - 初始化空会话")
    void testCreateSession() {
        Session session = Session.builder()
                .id(UUID.randomUUID().toString())
                .name("河南农业大学多轮咨询")
                .model("minimax")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        sessionStorage.save(session);
        testSessionId = session.getId();

        assertNotNull(testSessionId, "会话ID不应为空");

        System.out.println("=" .repeat(60));
        System.out.println("✅ 创建会话成功");
        System.out.println("📁 会话ID: " + testSessionId);
        System.out.println("📁 文件位置: LyClaw/sessions/" + testSessionId + ".json");
        System.out.println("=" .repeat(60));
    }

    // ========== 第1轮对话 ==========

    @Test
    @Order(2)
    @DisplayName("第1轮 - 基础问答")
    void testFirstRound() {
        Optional<Session> opt = sessionStorage.get(testSessionId);
        assertTrue(opt.isPresent(), "会话应该存在");
        Session session = opt.get();

        // 用户消息
        Message userMsg = Message.builder()
                .id("msg-u1-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("河南农业大学有哪些优势学科？")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(userMsg);

        // AI 回复
        Message aiMsg = Message.builder()
                .id("msg-a1-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content("河南农业大学是一所以农科为优势的综合性大学，主要优势学科包括：\n\n" +
                        "1. **作物学**：国家重点学科，拥有小麦玉米作物学国家重点实验室，郭天财教授团队全国知名。\n" +
                        "2. **兽医学**：一级学科博士点，动物疫病防控研究处于国内前列。\n" +
                        "3. **农业工程**：农业机械化及其自动化专业特色鲜明。\n" +
                        "4. **园艺学**：果树学、蔬菜学方向成果丰硕。\n" +
                        "5. **植物保护**：农药学、植物病理学为重点方向。\n\n" +
                        "此外，学校拥有省部共建实验室3个，国家工程技术研究中心1个，科研平台完善。")
                .model("minimax")
                .usage(Usage.of(52, 218))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiMsg);

        session.setUpdatedAt(LocalDateTime.now());
        sessionStorage.save(session);

        System.out.println("👤 用户: " + userMsg.getContent());
        System.out.println("🤖 AI: " + aiMsg.getContent().substring(0, Math.min(100, aiMsg.getContent().length())) + "...");
        System.out.println("   └─ Token用量: prompt=" + aiMsg.getUsage().getPromptTokens() +
                ", completion=" + aiMsg.getUsage().getCompletionTokens() +
                ", total=" + aiMsg.getUsage().getTotalTokens());
        System.out.println("✅ 第1轮对话已保存，当前消息数: " + session.getMessages().size());
        System.out.println();
    }

    // ========== 第2轮对话 ==========

    @Test
    @Order(3)
    @DisplayName("第2轮 - 追问细节")
    void testSecondRound() {
        Optional<Session> opt = sessionStorage.get(testSessionId);
        assertTrue(opt.isPresent());
        Session session = opt.get();

        Message userMsg = Message.builder()
                .id("msg-u2-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("能详细说说作物学这个学科吗？包括师资、科研项目、就业前景")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(userMsg);

        Message aiMsg = Message.builder()
                .id("msg-a2-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content("作物学是河南农业大学的王牌学科，详细情况如下：\n\n" +
                        "## 学科实力\n" +
                        "- 一级学科博士点，博士后流动站\n" +
                        "- 国家重点学科（2007年获批）\n" +
                        "- 第四轮学科评估：B+\n" +
                        "- ESI全球排名前1%\n\n" +
                        "## 师资力量\n" +
                        "- 教授42人，副教授56人\n" +
                        "- 中国工程院院士1人（张改平）\n" +
                        "- 国家杰出青年科学基金获得者3人\n" +
                        "- 郭天财教授：小麦栽培领域权威，国家科技进步二等奖获得者\n" +
                        "- 李潮海教授：玉米高产栽培专家\n\n" +
                        "## 科研项目\n" +
                        "- 国家重点研发计划项目5项\n" +
                        "- 国家自然科学基金重点项目8项\n" +
                        "- 年均科研经费超8000万元\n" +
                        "- 近5年发表SCI论文600余篇\n\n" +
                        "## 就业前景\n" +
                        "- 科研院所：中国农科院、各省市农科院\n" +
                        "- 种业公司：隆平高科、登海种业、荃银高科\n" +
                        "- 政府机构：农业农村局、农业技术推广中心\n" +
                        "- 高校任教：全国农林类高校\n" +
                        "- 深造率：硕士35%，博士15%")
                .model("minimax")
                .usage(Usage.of(68, 380))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiMsg);

        session.setUpdatedAt(LocalDateTime.now());
        sessionStorage.save(session);

        System.out.println("👤 用户: " + userMsg.getContent());
        System.out.println("🤖 AI: " + aiMsg.getContent().substring(0, Math.min(120, aiMsg.getContent().length())) + "...");
        System.out.println("   └─ Token用量: prompt=" + aiMsg.getUsage().getPromptTokens() +
                ", completion=" + aiMsg.getUsage().getCompletionTokens() +
                ", total=" + aiMsg.getUsage().getTotalTokens());
        System.out.println("✅ 第2轮对话已追加，当前消息数: " + session.getMessages().size());
        System.out.println();
    }

    // ========== 第3轮对话（含工具调用） ==========

    @Test
    @Order(4)
    @DisplayName("第3轮 - 触发工具调用（查询天气）")
    void testThirdRoundWithToolCall() {
        Optional<Session> opt = sessionStorage.get(testSessionId);
        assertTrue(opt.isPresent());
        Session session = opt.get();

        // 用户消息
        Message userMsg = Message.builder()
                .id("msg-u3-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("郑州今天天气怎么样？适合去河南农业大学参观吗？")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(userMsg);

        // AI 工具调用请求
        ToolCall toolCall = ToolCall.builder()
                .id("tc-" + UUID.randomUUID().toString().substring(0, 8))
                .toolCallId("call_weather_001")
                .name("get_weather")
                .arguments("{\"city\": \"郑州\", \"date\": \"today\"}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Message aiToolRequest = Message.builder()
                .id("msg-a3-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content("")
                .model("minimax")
                .toolCalls(List.of(toolCall))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiToolRequest);

        // 工具返回结果
        Message toolResult = Message.builder()
                .id("msg-t3-" + UUID.randomUUID().toString().substring(0, 8))
                .role("tool")
                .content("郑州今日天气：晴转多云，温度18-28℃，东南风2-3级，空气质量良（AQI 65）。" +
                        "适合户外活动，建议上午前往参观。")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(toolResult);

        // AI 最终回复
        Message aiFinalMsg = Message.builder()
                .id("msg-a3f-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content("根据天气查询结果，郑州今天晴转多云，18-28℃，非常适合户外活动！\n\n" +
                        "关于参观河南农业大学的建议：\n" +
                        "1. **时间**：上午9:00-11:30最佳，温度舒适，光线好\n" +
                        "2. **路线**：建议从南门进入，先参观作物学实验室，再到试验田\n" +
                        "3. **注意事项**：带瓶水，穿舒适的鞋子（校园面积大）\n" +
                        "4. **交通**：地铁2号线直达，农业大学站D口出\n\n" +
                        "要不要我帮你查一下作物学实验室的开放时间？")
                .model("minimax")
                .usage(Usage.of(245, 196))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiFinalMsg);

        session.setUpdatedAt(LocalDateTime.now());
        sessionStorage.save(session);

        System.out.println("👤 用户: " + userMsg.getContent());
        System.out.println("🔧 工具调用: " + toolCall.getName() + "(" + toolCall.getArguments() + ")");
        System.out.println("📊 工具结果: " + toolResult.getContent());
        System.out.println("🤖 AI最终回复: " + aiFinalMsg.getContent().substring(0, Math.min(100, aiFinalMsg.getContent().length())) + "...");
        System.out.println("   └─ Token用量: prompt=" + aiFinalMsg.getUsage().getPromptTokens() +
                ", completion=" + aiFinalMsg.getUsage().getCompletionTokens() +
                ", total=" + aiFinalMsg.getUsage().getTotalTokens());
        System.out.println("✅ 第3轮对话（含工具调用）已保存，当前消息数: " + session.getMessages().size());
        System.out.println();
    }

    // ========== 第4轮对话（长AI回复） ==========

    @Test
    @Order(5)
    @DisplayName("第4轮 - 长文本AI回复（详细介绍）")
    void testFourthRoundLongResponse() {
        Optional<Session> opt = sessionStorage.get(testSessionId);
        assertTrue(opt.isPresent());
        Session session = opt.get();

        Message userMsg = Message.builder()
                .id("msg-u4-" + UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content("给我详细介绍一下河南农业大学的各个校区、专业设置和录取分数线")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(userMsg);

        String longResponse = "河南农业大学现有**三个校区**，总占地面积约4000亩：\n\n" +
                "## 一、文化路校区（主校区）\n" +
                "- **地址**：郑州市金水区文化路95号\n" +
                "- **面积**：约1200亩\n" +
                "- **院系**：农学院、林学院、动物医学院、植物保护学院等\n" +
                "- **设施**：行政办公楼、图书馆（藏书200万册）、校史馆\n" +
                "- **特色**：历史最悠久，文化底蕴深厚\n\n" +
                "## 二、龙子湖校区\n" +
                "- **地址**：郑州市郑东新区龙子湖高校园区\n" +
                "- **面积**：约1800亩\n" +
                "- **院系**：信息与管理科学学院、文法学院、外国语学院等\n" +
                "- **设施**：现代化教学楼、体育馆、游泳馆\n" +
                "- **特色**：设施最新，环境优美\n\n" +
                "## 三、许昌校区\n" +
                "- **地址**：许昌市建安区\n" +
                "- **面积**：约1000亩\n" +
                "- **院系**：应用科技学院\n" +
                "- **特色**：侧重应用型人才培养\n\n" +
                "## 本科专业设置（共72个）\n" +
                "| 类别 | 代表专业 | 数量 |\n" +
                "|------|---------|------|\n" +
                "| 农学类 | 农学、植物保护、园艺 | 15 |\n" +
                "| 工学类 | 农业机械化、食品科学 | 22 |\n" +
                "| 理学类 | 生物科学、应用化学 | 10 |\n" +
                "| 经管类 | 农林经济管理、市场营销 | 12 |\n" +
                "| 文法类 | 法学、社会工作 | 8 |\n" +
                "| 其他 | 英语、设计学 | 5 |\n\n" +
                "## 2025年录取分数线（河南省）\n" +
                "| 专业 | 理科最低分 | 文科最低分 | 位次（理科）|\n" +
                "|------|-----------|-----------|------------|\n" +
                "| 农学 | 568 | — | 约42000 |\n" +
                "| 动物医学 | 575 | — | 约38000 |\n" +
                "| 食品科学 | 562 | — | 约45000 |\n" +
                "| 计算机科学 | 578 | — | 约36500 |\n" +
                "| 法学 | — | 570 | 约8500 |\n" +
                "| 英语 | — | 562 | 约9500 |\n\n" +
                "**注**：以上为2025年数据，仅供参考。实际录取以当年省招办公布为准。\n\n" +
                "## 特色培养项目\n" +
                "1. **绍骙实验班**：农学拔尖人才班，本硕博连读\n" +
                "2. **卓越农林人才计划**：校企联合培养\n" +
                "3. **国际交流项目**：与荷兰瓦赫宁根大学、美国康奈尔大学合作\n\n" +
                "总体来说，河南农业大学是一所性价比很高的农业类院校，尤其对想从事农业科研的同学来说是不错的选择。";

        Message aiMsg = Message.builder()
                .id("msg-a4-" + UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content(longResponse)
                .model("minimax")
                .usage(Usage.of(125, 892))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        session.addMessage(aiMsg);

        session.setUpdatedAt(LocalDateTime.now());
        sessionStorage.save(session);

        System.out.println("👤 用户: " + userMsg.getContent());
        System.out.println("🤖 AI回复长度: " + aiMsg.getContent().length() + " 字符");
        System.out.println("   └─ Token用量: prompt=" + aiMsg.getUsage().getPromptTokens() +
                ", completion=" + aiMsg.getUsage().getCompletionTokens() +
                ", total=" + aiMsg.getUsage().getTotalTokens());
        System.out.println("✅ 第4轮对话（长回复）已保存，当前消息数: " + session.getMessages().size());
        System.out.println();
    }

    // ========== 验证完整对话历史 ==========

    @Test
    @Order(6)
    @DisplayName("验证 - 完整对话历史回放")
    void testVerifyFullConversation() {
        Optional<Session> opt = sessionStorage.get(testSessionId);
        assertTrue(opt.isPresent());
        Session session = opt.get();
        List<Message> messages = session.getMessages();

        System.out.println("=" .repeat(60));
        System.out.println("📖 完整对话历史（共 " + messages.size() + " 条消息）");
        System.out.println("=" .repeat(60));

        int userCount = 0;
        int assistantCount = 0;
        int toolCount = 0;
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String prefix;

            switch (msg.getRole()) {
                case "user":
                    prefix = "👤 用户";
                    userCount++;
                    break;
                case "assistant":
                    prefix = "🤖 AI";
                    assistantCount++;
                    if (msg.getUsage() != null) {
                        totalPromptTokens += msg.getUsage().getPromptTokens();
                        totalCompletionTokens += msg.getUsage().getCompletionTokens();
                    }
                    break;
                case "tool":
                    prefix = "🔧 工具";
                    toolCount++;
                    break;
                default:
                    prefix = "❓ " + msg.getRole();
            }

            String content = msg.getContent();
            if (content != null && content.length() > 80) {
                content = content.substring(0, 80).replace("\n", " ") + "...";
            }

            System.out.printf("[%d] %s: %s%n", i + 1, prefix, content);

            // 显示工具调用
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    System.out.printf("    └─ 🔧 调用工具: %s(%s)%n", tc.getName(), tc.getArguments());
                }
            }

            // 显示 token 用量
            if (msg.getUsage() != null) {
                System.out.printf("    └─ 📊 Token: p=%d, c=%d, t=%d%n",
                        msg.getUsage().getPromptTokens(),
                        msg.getUsage().getCompletionTokens(),
                        msg.getUsage().getTotalTokens());
            }
        }

        // 断言验证
        assertEquals(4, userCount, "应该有4条用户消息");
        assertTrue(assistantCount >= 4, "至少有4条AI消息");
        assertEquals(1, toolCount, "应该有1条工具返回消息");
        assertTrue(messages.size() >= 9, "至少9条消息（含工具调用相关）");

        // 验证元数据
        assertNotNull(session.getId());
        assertNotNull(session.getName());
        assertEquals("minimax", session.getModel());
        assertNotNull(session.getCreatedAt());
        assertNotNull(session.getUpdatedAt());
        assertTrue(session.getUpdatedAt().compareTo(session.getCreatedAt()) >= 0,
                "更新时间 >= 创建时间");

        System.out.println("\n📊 统计汇总:");
        System.out.println("   用户消息: " + userCount + " 条");
        System.out.println("   AI消息: " + assistantCount + " 条");
        System.out.println("   工具消息: " + toolCount + " 条");
        System.out.println("   总消息数: " + messages.size() + " 条");
        System.out.println("   总Prompt Token: " + totalPromptTokens);
        System.out.println("   总Completion Token: " + totalCompletionTokens);
        System.out.println("   总Token: " + (totalPromptTokens + totalCompletionTokens));
        System.out.println("\n📁 会话文件: LyClaw/sessions/" + testSessionId + ".json");
        System.out.println("✅ 多轮对话验证通过！");
    }

    // ========== 辅助：查看所有会话 ==========

    @Test
    @Order(7)
    @DisplayName("辅助 - 查看所有会话列表")
    void testListAllSessions() {
        List<Session> all = sessionStorage.getAll();
        System.out.println("\n📁 当前所有会话 (共 " + all.size() + " 个):");
        for (Session s : all) {
            int msgCount = s.getMessages() != null ? s.getMessages().size() : 0;
            String shortId = s.getId() != null ? s.getId().substring(0, Math.min(8, s.getId().length())) : "N/A";
            System.out.printf("   - %s... | %s | %d 条消息 | 模型: %s%n",
                    shortId, s.getName(), msgCount, s.getModel());
        }
    }
}