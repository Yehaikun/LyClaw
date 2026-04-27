package lyjew.com.lyclaw.strategy;

import lyjew.com.lyclaw.model.Memory;

import java.time.LocalDateTime;
import java.util.*;

public class MarkdownFormatStrategy implements FormatStrategy<Memory> {

    private static final String DELIMITER = "---";

    @Override
    public String serialize(Memory entity) {
        StringBuilder sb = new StringBuilder();

        // 元数据
        sb.append(DELIMITER).append("\n");
        sb.append("id: ").append(nullToDefault(entity.getId(), "")).append("\n");
        sb.append("title: ").append(nullToDefault(entity.getTitle(), "记忆")).append("\n");
        sb.append("enabled: ").append(entity.isEnabled()).append("\n");
        sb.append("tags: [").append(String.join(", ", nullToEmpty(entity.getTags()))).append("]\n");
        sb.append("createdAt: ").append(entity.getCreatedAt()).append("\n");
        sb.append("updatedAt: ").append(entity.getUpdatedAt()).append("\n");
        sb.append(DELIMITER).append("\n");

        // 正文
        sb.append(nullToEmpty(entity.getContent()));
        return sb.toString();
    }

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

    @Override
    public String suffix() {
        return "md";
    }

    // ========== 私有方法 ==========

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

    private static class ParsedFrontMatter {
        String id = "";
        String title = "记忆";
        boolean enabled = true;
        List<String> tags = new ArrayList<>();
        LocalDateTime createdAt;
        LocalDateTime updatedAt;
        String body = "";

        static ParsedFrontMatter bodyOnly(String body) {
            ParsedFrontMatter result = new ParsedFrontMatter();
            result.body = body;
            return result;
        }

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
                    result.createdAt = LocalDateTime.parse(line.substring(10).trim());
                } else if (line.startsWith("updatedAt:")) {
                    result.updatedAt = LocalDateTime.parse(line.substring(10).trim());
                }
            }
            return result;
        }

        static List<String> parseTags(String raw) {
            String cleaned = raw.replace("[", "").replace("]", "").trim();
            if (cleaned.isEmpty()) return new ArrayList<>();
            return Arrays.asList(cleaned.split("\\s*,\\s*"));
        }
    }
}