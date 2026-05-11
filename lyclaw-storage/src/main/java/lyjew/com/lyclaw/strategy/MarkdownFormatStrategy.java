package lyjew.com.lyclaw.strategy;

import lyjew.com.lyclaw.model.Memory;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Markdown格式的序列化策略，专门用于Memory实体的持久化。
 *
 * <p>
 * 采用YAML Front Matter + Markdown正文的格式：
 * 文件头部以"---"分隔的YAML元数据区记录id、title、tags等信息，
 * 正文部分为纯Markdown内容。文件后缀为".md"。
 * </p>
 *
 * <p>
 * 序列化流程：Memory对象 → YAML Front Matter(元数据) → 正文 → Markdown字符串<br>
 * 反序列化流程：Markdown字符串 → 解析Front Matter → 提取正文 → Memory对象<br>
 * 内部类ParsedFrontMatter负责解析Front Matter区域。
 * </p>
 *
 * @author lyjew
 */
public class MarkdownFormatStrategy implements FormatStrategy<Memory> {

    /** YAML Front Matter分隔符，用于标记元数据区域的起止 */
    private static final String DELIMITER = "---";

    /**
     * 将Memory实体序列化为Markdown格式字符串（Front Matter + 正文）。
     *
     * @param entity Memory实体
     * @return Markdown格式字符串，包含元数据和正文
     */
    @Override
    public String serialize(Memory entity) {
        StringBuilder sb = new StringBuilder();

        // 元数据
        sb.append(DELIMITER).append("\n");
        sb.append("id: ").append(nullToDefault(entity.getId(), "")).append("\n");
        sb.append("title: ").append(nullToDefault(entity.getTitle(), "记忆")).append("\n");
        sb.append("enabled: ").append(entity.isEnabled()).append("\n");
        sb.append("tags: [").append(String.join(", ", nullToEmpty(entity.getTags()))).append("]\n");
        sb.append("createdAt: ").append(entity.getCreatedAt() != null ? entity.getCreatedAt() : "").append("\n");
        sb.append("updatedAt: ").append(entity.getUpdatedAt() != null ? entity.getUpdatedAt() : "").append("\n");
        sb.append(DELIMITER).append("\n");

        // 正文
        sb.append(nullToEmpty(entity.getContent()));
        return sb.toString();
    }

    /**
     * 将Markdown字符串反序列化为Memory对象。
     *
     * <p>解析Front Matter区域提取元数据，剩余部分作为正文。
     * 若内容为null或空白，返回带有随机UUID的默认空白Memory。</p>
     *
     * @param raw   Markdown格式原始字符串
     * @param clazz 目标类型（Memory.class）
     * @return 包含元数据和正文的Memory对象
     */
    @Override
    public Memory deserialize(String raw, Class<Memory> clazz) {
        if (raw == null || raw.isBlank()) {
            return emptyMemory();
        }

        ParsedFrontMatter parsed = parseFrontMatter(raw);

        return Memory.builder()
                .id(parsed.id)
                .content(parsed.body)
                .title(parsed.title)
                .enabled(parsed.enabled)
                .tags(parsed.tags)
                .createdAt(parsed.createdAt)
                .updatedAt(parsed.updatedAt)
                .build();
    }

    /**
     * 返回该策略对应的文件后缀。
     *
     * @return "md"
     */
    @Override
    public String suffix() {
        return "md";
    }

    // ========== 私有方法 ==========

    /**
     * 解析Markdown文档中的Front Matter区域。
     *
     * <p>Front Matter以"---"开头和结束，位于文档最前面。
     * 若文档不以分隔符开头，则将整个内容视为正文。</p>
     *
     * @param raw 完整的Markdown原始字符串
     * @return 解析后的Front Matter元数据和正文
     */
    private ParsedFrontMatter parseFrontMatter(String raw) {
        if (!raw.startsWith(DELIMITER)) {
            return ParsedFrontMatter.bodyOnly(raw);
        }

        int end = raw.indexOf(DELIMITER, 3);
        if (end < 0) {
            return ParsedFrontMatter.bodyOnly(raw);
        }

        String frontMatter = raw.substring(3, end).trim();
        String body = raw.substring(end + 3).trim();

        return ParsedFrontMatter.from(frontMatter, body);
    }

    /**
     * 创建默认空白Memory对象，用于反序列化空内容时的兜底处理。
     *
     * @return 带随机UUID的默认Memory
     */
    private Memory emptyMemory() {
        return Memory.builder()
                .id(UUID.randomUUID().toString())
                .content("")
                .title("记忆")
                .enabled(true)
                .tags(new ArrayList<>())
                .build();
    }

    private String nullToDefault(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private List<String> nullToEmpty(List<String> value) {
        return value != null ? value : new ArrayList<>();
    }

    // ========== 内部类 ==========

    /**
     * Front Matter解析结果数据载体。
     *
     * <p>封装从Markdown Front Matter区域解析出的所有元数据字段，
     * 包括id、title、enabled、tags、时间戳和正文。
     * 提供静态工厂方法用于不同场景的解析。</p>
     */
    private static class ParsedFrontMatter {
        /** Memory唯一标识，默认为空字符串 */
        String id = "";
        /** Memory标题，默认为"记忆" */
        String title = "记忆";
        /** 是否启用，默认true */
        boolean enabled = true;
        /** 标签列表，默认为空列表 */
        List<String> tags = new ArrayList<>();
        /** 创建时间 */
        LocalDateTime createdAt;
        /** 更新时间 */
        LocalDateTime updatedAt;
        /** 正文内容 */
        String body = "";

        /**
         * 当无Front Matter时，将整个内容视为正文。
         *
         * @param body 完整文本作为正文
         * @return 仅包含正文的解析结果
         */
        static ParsedFrontMatter bodyOnly(String body) {
            ParsedFrontMatter result = new ParsedFrontMatter();
            result.body = body;
            return result;
        }

        /**
         * 从Front Matter文本和正文构建完整解析结果。
         *
         * <p>逐行解析YAML格式的键值对：id、title、enabled、tags、createdAt、updatedAt。</p>
         *
         * @param frontMatter YAML Front Matter文本（去除分隔符）
         * @param body        正文内容
         * @return 完整解析结果
         */
        static ParsedFrontMatter from(String frontMatter, String body) {
            ParsedFrontMatter result = new ParsedFrontMatter();
            result.body = body;

            for (String line : frontMatter.split("\n")) {
                line = line.trim();
                if (line.startsWith("id:")) {
                    result.id = line.substring(3).trim();
                } else if (line.startsWith("title:")) {
                    result.title = line.substring(6).trim();
                } else if (line.startsWith("enabled:")) {
                    result.enabled = Boolean.parseBoolean(line.substring(8).trim());
                } else if (line.startsWith("tags:")) {
                    result.tags = parseTags(line.substring(5).trim());
                } else if (line.startsWith("createdAt:")) {
                    String val = line.substring(10).trim();
                    if (!val.isEmpty() && !"null".equalsIgnoreCase(val)) {
                        result.createdAt = LocalDateTime.parse(val);
                    }
                } else if (line.startsWith("updatedAt:")) {
                    String val = line.substring(10).trim();
                    if (!val.isEmpty() && !"null".equalsIgnoreCase(val)) {
                        result.updatedAt = LocalDateTime.parse(val);
                    }
                }
            }
            return result;
        }

        /**
         * 解析YAML格式的标签数组字符串。
         *
         * <p>去除方括号后按逗号分割，支持"tag1, tag2, tag3"格式。</p>
         *
         * @param raw 原始标签数组字符串（含方括号）
         * @return 标签字符串列表
         */
        static List<String> parseTags(String raw) {
            String cleaned = raw.replace("[", "").replace("]", "").trim();
            if (cleaned.isEmpty()) return new ArrayList<>();
            return Arrays.asList(cleaned.split("\\s*,\\s*"));
        }
    }
}