package lyjew.com.lyclaw.persistence.jsonl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话文件读取器——支持JSON数组格式 [{...},{...}] 和旧版JSONL格式（每行一个JSON）。
 *
 * 自动检测格式：读文件首非空字符，若为'['则按JSON数组解析，否则按JSONL逐行解析。
 * JSON数组模式使用Jackson JsonNode树解析，正确处理嵌套对象和字符串中的特殊字符。
 */
public class DefaultJsonlReader implements JsonlReader {

    private final ObjectMapper objectMapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    public DefaultJsonlReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> readAll(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return List.of();
        try {
            String content = Files.readString(path).trim();
            if (content.isEmpty()) return List.of();
            if (content.startsWith("[")) {
                return readJsonArray(content);
            }
            return readJsonlLines(content);
        } catch (IOException e) {
            throw new UncheckedIOException("读取会话文件失败: " + filePath, e);
        }
    }

    @Override
    public List<Map<String, Object>> readRange(String filePath, int offset, int limit) {
        List<Map<String, Object>> all = readAll(filePath);
        int total = all.size();
        int start, end;
        if (offset == -1) {
            start = Math.max(0, total - limit);
            end = total;
        } else {
            start = Math.max(0, offset);
            end = Math.min(total, start + limit);
        }
        if (start >= total) return List.of();
        return all.subList(start, end);
    }

    @Override
    public Map<String, Object> readFirstLine(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return null;
        try {
            String content = Files.readString(path).trim();
            if (content.isEmpty()) return null;
            if (content.startsWith("[")) {
                List<Map<String, Object>> all = readJsonArray(content);
                return all.isEmpty() ? null : all.get(0);
            }
            String firstLine = content.split("\n", 2)[0].trim();
            return firstLine.isEmpty() ? null : objectMapper.readValue(firstLine, MAP_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException("读取会话文件首行失败: " + filePath, e);
        }
    }

    @Override
    public int countLines(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return 0;
        try {
            String content = Files.readString(path).trim();
            if (content.isEmpty()) return 0;
            if (content.startsWith("[")) {
                return readJsonArray(content).size();
            }
            return (int) content.lines().filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 使用Jackson树模型解析JSON数组，正确处理嵌套对象和字符串中的特殊字符 */
    private List<Map<String, Object>> readJsonArray(String content) throws IOException {
        JsonNode root = objectMapper.readTree(content);
        if (!root.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode node : root) {
            result.add(objectMapper.convertValue(node, MAP_TYPE));
        }
        return result;
    }

    /** 旧版JSONL格式：逐行解析 */
    private List<Map<String, Object>> readJsonlLines(String content) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(objectMapper.readValue(trimmed, MAP_TYPE));
            }
        }
        return result;
    }
}
