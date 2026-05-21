package lyjew.com.lyclaw.persistence.jsonl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * JSONL写入默认实现——使用Jackson序列化，Files.writeString逐行追加。
 *
 * 每次appendLine调用自动创建父目录（如果不存在），以CREATE+APPEND模式打开文件。
 * Files.writeString内部已确保原子性和磁盘同步，因此flush为空操作。
 */
public class DefaultJsonlWriter implements JsonlWriter {

    private final ObjectMapper objectMapper;

    public DefaultJsonlWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void appendLine(String filePath, Map<String, Object> fields) {
        try {
            Path path = Paths.get(filePath);
            // 确保父目录存在（首次创建会话时目录可能不存在）
            Files.createDirectories(path.getParent());
            String line = objectMapper.writeValueAsString(fields) + "\n";
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL写入失败: " + filePath, e);
        }
    }

    @Override
    public void flush(String filePath) {
        // Files.writeString 每次调用即刷盘，无需额外flush
    }
}
