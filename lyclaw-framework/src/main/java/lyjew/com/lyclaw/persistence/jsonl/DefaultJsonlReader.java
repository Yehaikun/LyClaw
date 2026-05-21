package lyjew.com.lyclaw.persistence.jsonl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSONL读取默认实现——基于BufferedReader逐行读取，Jackson反序列化。
 *
 * readRange支持两种模式：
 * - offset=-1：取文件末尾最新limit条（用于会话恢复时加载最近消息）
 * - offset>=0：从第offset行开始读取limit条（用于历史消息分页滚动）
 *
 * 性能优化：readRange在确定end行号后提前退出循环，不读取文件剩余部分。
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
        List<Map<String, Object>> lines = new ArrayList<>();
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return lines;
        try {
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank()) {
                    lines.add(objectMapper.readValue(line, MAP_TYPE));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL读取失败: " + filePath, e);
        }
        return lines;
    }

    @Override
    public List<Map<String, Object>> readRange(String filePath, int offset, int limit) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            int total = countLines(filePath);
            int start, end;
            if (offset == -1) {
                // 取最新limit条
                start = Math.max(0, total - limit);
                end = total;
            } else {
                // 从指定offset开始
                start = Math.max(0, offset);
                end = Math.min(total, start + limit);
            }
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                if (lineNum >= start && lineNum < end && !line.isBlank()) {
                    result.add(objectMapper.readValue(line, MAP_TYPE));
                }
                lineNum++;
                if (lineNum >= end) break;  // 提前退出，不读取文件剩余部分
            }
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL分段读取失败: " + filePath, e);
        }
        return result;
    }

    @Override
    public Map<String, Object> readFirstLine(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return null;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line = reader.readLine();
            return line != null ? objectMapper.readValue(line, MAP_TYPE) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL首行读取失败: " + filePath, e);
        }
    }

    @Override
    public int countLines(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return 0;
        try {
            return (int) Files.lines(path).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
