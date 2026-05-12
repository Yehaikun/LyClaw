package lyjew.com.lyclaw.plan.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.task.DecompositionStrategy;
import lyjew.com.lyclaw.task.TaskDecomposer;
import lyjew.com.lyclaw.task.TaskNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM 驱动的任务分解器 —— 使用关键词分析和规则将复杂任务分解为结构化子任务。
 *
 * <p>当前为混合实现：LLM_DRIVEN 策略调用 ChatFacade 进行真正的 LLM 驱动分解，
 * 其他策略使用规则引擎。规则引擎在 LLM 不可用时提供合理回退。</p>
 * 规则引擎通过以下维度分析任务描述：
 * <ul>
 *   <li><b>动作词检测</b>：识别 "创建/修改/删除/查询/部署/配置" 等操作动词</li>
 *   <li><b>目标实体检测</b>：识别 "数据库/API/文件/配置/服务" 等目标实体</li>
 *   <li><b>领域分类</b>：将任务映射到 "代码/数据/文档/网络/安全" 等知识领域</li>
 *   <li><b>约束提取</b>：识别 "必须在...之前/同时/需要...之后" 等时序约束</li>
 * </ul>
 * </p>
 *
 * <p>分解结果是一个 {@link TaskNode} 列表，包含节点之间的依赖关系，
 * 可直接用于构建 {@link lyjew.com.lyclaw.task.PlanGraph}。</p>
 *
 * <p><b>使用方式</b>：
 * <pre>{@code
 *   LLMTaskDecomposer decomposer = new LLMTaskDecomposer();
 *   List<TaskNode> subTasks = decomposer.decompose(
 *       "创建一个用户管理系统，包括数据库设计和REST API",
 *       DecompositionStrategy.BY_PHASE
 *   );
 * }</pre>
 * </p>
 *
 * <p><b>设计动机</b>：将任务分解逻辑从 Planner 中分离出来，
 * 使得分解策略可以独立测试、替换和升级。
 * 当前规则版本作为基线实现，确保在 LLM 不可用时有合理的回退方案。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see DecompositionStrategy
 * @see TaskNode
 * @see lyjew.com.lyclaw.chat.ChatFacade
 */
@Component
public class LLMTaskDecomposer implements TaskDecomposer {

    private static final Logger log = LoggerFactory.getLogger(LLMTaskDecomposer.class);

    private final ChatFacade chatFacade;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LLMTaskDecomposer(ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    /** 动作词典 —— 关键词 → 子任务类型映射 */
    private static final Map<Pattern, String> ACTION_PATTERNS = new LinkedHashMap<>();

    /** 目标实体词典 —— 关键词 → 领域 */
    private static final Map<Pattern, String> ENTITY_PATTERNS = new LinkedHashMap<>();

    /** 阶段模板 —— 用于 BY_PHASE 策略的标准阶段描述 */
    private static final List<PhaseTemplate> PHASE_TEMPLATES = List.of(
            new PhaseTemplate("ANALYZE", "Analyze requirements and constraints for: %s",
                    List.of("knowledge_search")),
            new PhaseTemplate("DESIGN", "Design solution architecture for: %s",
                    List.of()),
            new PhaseTemplate("IMPLEMENT", "Implement the solution for: %s",
                    List.of("code_executor", "file_write")),
            new PhaseTemplate("TEST", "Test and validate the implementation of: %s",
                    List.of("code_executor")),
            new PhaseTemplate("DOCUMENT", "Document the solution for: %s",
                    List.of("file_write"))
    );

    /** 领域模板 —— 用于 BY_DOMAIN 策略 */
    private static final Map<String, DomainTemplate> DOMAIN_TEMPLATES = new LinkedHashMap<>();

    static {
        // 初始化动作词典
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(创建|create|build|make|generate|new|初始化|init)"),
                "CREATE");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(修改|修改|update|modify|change|edit|refactor|重构|优化|optimize)"),
                "MODIFY");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(删除|delete|remove|clean|purge|销毁|drop)"),
                "DELETE");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(查询|query|search|find|检索|get|fetch|read|查看|list)"),
                "QUERY");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(部署|deploy|release|publish|上线|发布|launch)"),
                "DEPLOY");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(配置|configure|setup|install|设置|config)"),
                "CONFIGURE");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(分析|analyze|investigate|检查|review|审计|audit|评估|evaluate)"),
                "ANALYZE");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(迁移|migrate|move|transfer|搬迁|导入|export|import|export)"),
                "MIGRATE");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(测试|test|verify|validate|验证|检查|check)"),
                "TEST");
        ACTION_PATTERNS.put(
                Pattern.compile("(?i)(集成|integrate|connect|连接|对接|combine)"),
                "INTEGRATE");

        // 初始化目标实体词典
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(数据库|database|db|sql|mysql|postgres|mongo|redis|存储|storage)"),
                "DATA");
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(api|接口|endpoint|rest|graphql|grpc|http|服务|service|微服务|microservice)"),
                "API");
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(文件|file|文档|doc|document|readme|日志|log|配置.*文件|config.*file)"),
                "DOCUMENT");
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(代码|code|程序|program|脚本|script|函数|function|class|类|模块|module)"),
                "CODE");
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(网络|network|防火墙|firewall|dns|域名|端口|port|负载|load.balanc)"),
                "NETWORK");
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(安全|security|auth|认证|鉴权|权限|permission|加密|encrypt|token|jwt|oauth)"),
                "SECURITY");
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(用户|user|角色|role|账户|account|profile|登录|login|注册|register)"),
                "USER");
        ENTITY_PATTERNS.put(
                Pattern.compile("(?i)(监控|monitor|报警|alert|指标|metric|日志.*采集|log.*collect|观测|observ)"),
                "MONITORING");

        // 初始化领域模板
        DOMAIN_TEMPLATES.put("DATA", new DomainTemplate("DATA",
                List.of("schema-design", "migration", "query-optimization", "backup")));
        DOMAIN_TEMPLATES.put("API", new DomainTemplate("API",
                List.of("endpoint-design", "request-validation", "error-handling", "documentation")));
        DOMAIN_TEMPLATES.put("CODE", new DomainTemplate("CODE",
                List.of("architecture", "implementation", "testing", "refactoring")));
        DOMAIN_TEMPLATES.put("DOCUMENT", new DomainTemplate("DOCUMENT",
                List.of("structure", "content", "review", "publish")));
        DOMAIN_TEMPLATES.put("SECURITY", new DomainTemplate("SECURITY",
                List.of("threat-modeling", "auth-implementation", "audit", "compliance")));
        DOMAIN_TEMPLATES.put("NETWORK", new DomainTemplate("NETWORK",
                List.of("topology", "configuration", "monitoring", "troubleshooting")));
        DOMAIN_TEMPLATES.put("MONITORING", new DomainTemplate("MONITORING",
                List.of("metrics-collection", "dashboard", "alerting", "log-aggregation")));
    }

    /**
     * 使用指定策略分解任务描述为子任务列表。
     *
     * <p>这是面向外部的主要入口。根据策略选择不同的分解方法。</p>
     *
     * @param taskDescription 任务描述文本
     * @param strategy        分解策略
     * @return 子任务节点列表，包含正确的依赖关系
     */
    public List<TaskNode> decompose(String taskDescription, DecompositionStrategy strategy) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return List.of();
        }
        if (strategy == null) {
            strategy = DecompositionStrategy.BY_PHASE;
        }

        return switch (strategy) {
            case SEQUENTIAL -> decomposeSequential(taskDescription);
            case BY_DOMAIN -> decomposeByDomain(taskDescription);
            case BY_PHASE -> decomposeByPhase(taskDescription);
            case PARALLEL_INDEPENDENT -> decomposeParallel(taskDescription);
            case LLM_DRIVEN -> decomposeWithLLM(taskDescription);
            case TREE -> decomposeTree(taskDescription);
        };
    }

    /**
     * 顺序分解：分析关键词，按动作类型顺序排列子任务。
     *
     * <p>识别任务中的操作动词，每种动词类型生成一个子任务，
     * 按自然处理顺序排列（CREATE → CONFIGURE → INTEGRATE → TEST → DEPLOY）。</p>
     */
    private List<TaskNode> decomposeSequential(String taskDescription) {
        List<TaskNode> nodes = new ArrayList<>();
        String prefix = "llm-seq-" + UUID.randomUUID().toString().substring(0, 8);
        String prevId = null;

        // 按固定顺序检查每种动作类型
        List<String> actionOrder = List.of(
                "ANALYZE", "CREATE", "CONFIGURE", "MODIFY", "INTEGRATE", "TEST", "DEPLOY");

        int idx = 0;
        for (String action : actionOrder) {
            // 检查任务描述是否包含此动作类型的关键词
            boolean found = false;
            for (Map.Entry<Pattern, String> entry : ACTION_PATTERNS.entrySet()) {
                if (entry.getValue().equals(action) && entry.getKey().matcher(taskDescription).find()) {
                    found = true;
                    break;
                }
            }
            if (!found) continue;

            String nodeId = prefix + "-" + idx;
            List<String> deps = prevId != null ? List.of(prevId) : List.of();
            TaskNode node = new TaskNode(nodeId, action,
                    action + ": " + taskDescription,
                    selectToolsForAction(action), deps, 30_000L);
            nodes.add(node);
            prevId = nodeId;
            idx++;
        }

        // 如果没有匹配到任何动作，创建一个通用节点
        if (nodes.isEmpty()) {
            String nodeId = prefix + "-execute";
            nodes.add(new TaskNode(nodeId, "EXECUTE", taskDescription,
                    List.of(), List.of(), 30_000L));
        }

        return nodes;
    }

    /**
     * 按领域分解：识别任务涉及的知识领域，每个领域一个子任务。
     *
     * <p>不同领域之间是并行的（无依赖），领域内可以有子步骤。</p>
     */
    private List<TaskNode> decomposeByDomain(String taskDescription) {
        List<TaskNode> nodes = new ArrayList<>();
        String prefix = "llm-dom-" + UUID.randomUUID().toString().substring(0, 8);

        int idx = 0;
        for (Map.Entry<Pattern, String> entry : ENTITY_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(taskDescription).find()) {
                String domain = entry.getValue();
                DomainTemplate template = DOMAIN_TEMPLATES.get(domain);
                String nodeId = prefix + "-" + idx;

                String desc;
                if (template != null) {
                    desc = String.format("[%s] Handle %s aspects: %s",
                            domain, String.join(", ", template.subtasks), taskDescription);
                } else {
                    desc = "[" + domain + "] Handle " + domain.toLowerCase()
                            + " aspects: " + taskDescription;
                }

                TaskNode node = new TaskNode(nodeId, domain,
                        desc, List.of("domain_knowledge"),
                        List.of(), // 不同领域无依赖 → 并行
                        30_000L);
                nodes.add(node);
                idx++;
            }
        }

        if (nodes.isEmpty()) {
            String nodeId = prefix + "-general";
            nodes.add(new TaskNode(nodeId, "GENERAL",
                    "General processing: " + taskDescription,
                    List.of(), List.of(), 30_000L));
        }

        return nodes;
    }

    /**
     * 按阶段分解：使用标准软件工程阶段模板。
     *
     * <p>阶段顺序：ANALYZE → DESIGN → IMPLEMENT → TEST → DOCUMENT。
     * 对于简单任务，可能只包含 ANALYZE → IMPLEMENT → TEST 三个阶段。</p>
     */
    private List<TaskNode> decomposeByPhase(String taskDescription) {
        List<TaskNode> nodes = new ArrayList<>();
        String prefix = "llm-phs-" + UUID.randomUUID().toString().substring(0, 8);

        // 根据任务复杂度选择阶段
        int complexity = estimateComplexity(taskDescription);
        int endIndex = complexity <= 1 ? 4 : PHASE_TEMPLATES.size(); // 简单任务跳过 DOCUMENT

        String prevId = null;
        for (int i = 0; i < endIndex; i++) {
            PhaseTemplate pt = PHASE_TEMPLATES.get(i);
            String nodeId = prefix + "-" + i;
            List<String> deps = prevId != null ? List.of(prevId) : List.of();

            TaskNode node = new TaskNode(nodeId, pt.type,
                    String.format(pt.descFormat, taskDescription),
                    pt.tools, deps, 60_000L);
            nodes.add(node);
            prevId = nodeId;
        }

        return nodes;
    }

    /**
     * 并行分解：识别可独立执行的子任务。
     *
     * <p>检测到独立信号词（同时、分别、并行）时，将任务按逗号/分号拆分，
     * 所有子任务共享同一组依赖（都无依赖 = 完全并行）。</p>
     */
    private List<TaskNode> decomposeParallel(String taskDescription) {
        List<TaskNode> nodes = new ArrayList<>();
        String prefix = "llm-par-" + UUID.randomUUID().toString().substring(0, 8);

        // 尝试按分隔符拆分
        String[] parts = taskDescription.split("[,，;；]");

        if (parts.length >= 2) {
            int idx = 0;
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;

                String nodeId = prefix + "-" + idx;
                TaskNode node = new TaskNode(nodeId, "EXECUTE",
                        "Parallel subtask: " + trimmed,
                        List.of(), List.of(), // 无依赖 → 全并行
                        30_000L);
                nodes.add(node);
                idx++;
            }
        }

        if (nodes.size() < 2) {
            // 无法自然拆分 → 创建 2 个并行分析节点
            String nodeIdA = prefix + "-a";
            nodes.add(new TaskNode(nodeIdA, "ANALYZE",
                    "Analyze aspect A: " + taskDescription,
                    List.of(), List.of(), 30_000L));

            String nodeIdB = prefix + "-b";
            nodes.add(new TaskNode(nodeIdB, "IMPLEMENT",
                    "Implement aspect B: " + taskDescription,
                    List.of(), List.of(), 30_000L));
        }

        return nodes;
    }

    /**
     * LLM 驱动分解 —— 调用 ChatFacade 让 LLM 自行决定最优子任务分解方案。
     *
     * <p>构造结构化 system prompt 要求 LLM 输出 JSON 格式的分解结果，
     * 包含子任务描述、类型、依赖和工具需求。LLM 调用失败时回退到按阶段分解。</p>
     */
    private List<TaskNode> decomposeWithLLM(String taskDescription) {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(Message.system(DECOMPOSITION_SYSTEM_PROMPT),
                            Message.user(taskDescription)))
                    .temperature(0.3)
                    .maxTokens(2000)
                    .stream(false)
                    .build();
            ModelResponse response = chatFacade.chat(request);
            String json = response.getContent();
            if (json == null || json.isBlank()) {
                log.warn("LLM返回空内容，回退到规则分解");
                return decomposeByPhase(taskDescription);
            }
            return parseTaskNodesFromJson(json, taskDescription);
        } catch (Exception e) {
            log.warn("LLM分解失败，回退到规则分解: {}", e.getMessage());
            return decomposeByPhase(taskDescription);
        }
    }

    /**
     * 从 LLM 返回的 JSON 中解析子任务列表。
     *
     * <p>LLM 输出格式：
     * <pre>{@code
     * {
     *   "subtasks": [
     *     {"id": "1", "type": "ANALYZE", "description": "...", "tools": ["web_search"], "dependencies": []},
     *     {"id": "2", "type": "IMPLEMENT", "description": "...", "tools": ["file_write"], "dependencies": ["1"]}
     *   ]
     * }
     * }</pre>
     * 先提取 JSON 块（处理 LLM 可能包裹的 markdown 代码块），
     * 第一遍创建所有 TaskNode，第二遍连接依赖。</p>
     */
    @SuppressWarnings("unchecked")
    private List<TaskNode> parseTaskNodesFromJson(String raw, String fallbackDesc) {
        try {
            String json = extractJsonBlock(raw);
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> subtasks = (List<Map<String, Object>>) root.get("subtasks");
            if (subtasks == null || subtasks.isEmpty()) {
                log.warn("LLM返回的subtasks为空，回退到规则分解");
                return decomposeByPhase(fallbackDesc);
            }

            String prefix = "llm-ai-" + UUID.randomUUID().toString().substring(0, 8);
            List<TaskNode> nodes = new ArrayList<>();
            Map<String, TaskNode> idMap = new LinkedHashMap<>();

            for (Map<String, Object> st : subtasks) {
                String id = str(st, "id", prefix + "-" + idMap.size());
                String type = str(st, "type", "EXECUTE");
                String desc = str(st, "description", fallbackDesc);
                List<String> tools = list(st, "tools");
                long timeout = num(st, "timeoutMs", 30_000L);

                TaskNode node = new TaskNode(prefix + "-" + id, type, desc, tools,
                        new ArrayList<>(), timeout);
                nodes.add(node);
                idMap.put(id, node);
            }

            for (Map<String, Object> st : subtasks) {
                String id = str(st, "id", "");
                TaskNode node = idMap.get(id);
                if (node == null) continue;
                List<String> deps = list(st, "dependencies");
                for (String depId : deps) {
                    TaskNode dep = idMap.get(depId);
                    if (dep != null && !node.getDependencies().contains(depId)) {
                        node.getDependencies().add(prefix + "-" + depId);
                    }
                }
            }

            log.info("LLM分解成功: {} 个子任务", nodes.size());
            return nodes;
        } catch (Exception e) {
            log.warn("JSON解析失败，回退到规则分解: {}", e.getMessage());
            return decomposeByPhase(fallbackDesc);
        }
    }

    private String extractJsonBlock(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v instanceof String s ? s : def;
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> l) {
            return l.stream().map(Object::toString).collect(Collectors.toList());
        }
        return List.of();
    }

    private long num(Map<String, Object> map, String key, long def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static final String DECOMPOSITION_SYSTEM_PROMPT =
            "你是一个任务分解引擎。根据任务描述，将其拆解为子任务列表。" +
            "仅输出合法的 JSON，格式如下：\n" +
            "{\n" +
            "  \"subtasks\": [\n" +
            "    {\"id\": \"1\", \"type\": \"ANALYZE\", \"description\": \"...\", " +
            "\"tools\": [\"web_search\"], \"dependencies\": []},\n" +
            "    {\"id\": \"2\", \"type\": \"IMPLEMENT\", \"description\": \"...\", " +
            "\"tools\": [\"code_executor\"], \"dependencies\": [\"1\"]}\n" +
            "  ]\n" +
            "}\n" +
            "可用类型: ANALYZE, DESIGN, IMPLEMENT, TEST, DOCUMENT, CREATE, MODIFY, DELETE, " +
            "QUERY, DEPLOY, CONFIGURE, MIGRATE, INTEGRATE。\n" +
            "可用工具: web_search, file_read, file_write, code_executor, knowledge_search, " +
            "shell_executor, database_query。\n" +
            "dependencies 填写依赖的子任务 id 列表（无依赖填空数组）。" +
            "确保每个 id 唯一，dependencies 只引用存在的 id。";

    /**
     * 树形分解：递归构建 2 层深的子树。
     */
    private List<TaskNode> decomposeTree(String taskDescription) {
        List<TaskNode> nodes = new ArrayList<>();
        String prefix = "llm-tree-" + UUID.randomUUID().toString().substring(0, 8);
        String prevId = null;

        // 根层：按阶段创建 Level-1 节点
        for (int i = 0; i < 3; i++) {
            PhaseTemplate pt = PHASE_TEMPLATES.get(Math.min(i, PHASE_TEMPLATES.size() - 1));
            String l1Id = prefix + "-L1-" + i;
            List<String> l1Deps = prevId != null ? List.of(prevId) : List.of();

            TaskNode l1Node = new TaskNode(l1Id, pt.type,
                    String.format(pt.descFormat, taskDescription),
                    pt.tools, l1Deps, 60_000L);
            nodes.add(l1Node);

            // 每个 L1 下挂 2 个 L2 叶子节点
            for (int j = 0; j < 2; j++) {
                String l2Id = l1Id + "-L2-" + j;
                String subDesc = String.format("[%s - Sub %d] %s",
                        pt.type, j + 1, taskDescription);
                TaskNode l2Node = new TaskNode(l2Id, "ATOMIC",
                        subDesc, List.of(), List.of(l1Id), 30_000L);
                nodes.add(l2Node);
            }

            prevId = l1Id;
        }

        return nodes;
    }

    /**
     * 根据动作类型选择合适的工具。
     */
    private List<String> selectToolsForAction(String actionType) {
        return switch (actionType) {
            case "CREATE", "MODIFY" -> List.of("file_write", "code_executor");
            case "QUERY" -> List.of("web_search", "file_read", "database_query");
            case "DEPLOY" -> List.of("deployment_tool", "shell_executor");
            case "TEST" -> List.of("code_executor", "test_runner");
            case "ANALYZE" -> List.of("web_search", "knowledge_search");
            case "MIGRATE" -> List.of("file_read", "file_write", "database_query");
            default -> List.of();
        };
    }

    /**
     * 估算任务复杂度（0-3）。
     */
    private int estimateComplexity(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return 0;
        }
        long actionCount = ACTION_PATTERNS.keySet().stream()
                .filter(p -> p.matcher(taskDescription).find())
                .count();
        long entityCount = ENTITY_PATTERNS.keySet().stream()
                .filter(p -> p.matcher(taskDescription).find())
                .count();

        int score = (int) (actionCount + entityCount);
        if (score <= 2) return 0;
        if (score <= 4) return 1;
        if (score <= 6) return 2;
        return 3;
    }

    // ==================== 内部类型 ====================

    /**
     * 阶段模板 —— 定义 BY_PHASE 策略中每个阶段的结构。
     */
    private record PhaseTemplate(String type, String descFormat, List<String> tools) {}

    /**
     * 领域模板 —— 定义 BY_DOMAIN 策略中每个领域的子任务。
     */
    private record DomainTemplate(String domain, List<String> subtasks) {}
}
