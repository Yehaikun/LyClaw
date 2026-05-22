package lyjew.com.lyclaw.persistence.jsonl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * JSON数组格式会话文件写入器。
 *
 * 文件格式：标准JSON数组 [\n{...},\n{...},\n{...}\n]，与OpenClaw格式兼容。
 * 每个会话文件是一个合法的JSON数组，元素按时间顺序追加。
 *
 * 写入策略：
 * - 首次写入：创建文件，写入 [\n<json>\n]
 * - 后续追加：RandomAccessFile seek到末尾-2位置，写入 ,\n<json>\n]
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
            Files.createDirectories(path.getParent());
            String json = objectMapper.writeValueAsString(fields);

            if (!Files.exists(path) || Files.size(path) == 0) {
                String content = "[\n" + json + "\n]";
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                    raf.seek(raf.length() - 2);
                    raf.write((",\n" + json + "\n]").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("JSON数组写入失败: " + filePath, e);
        }
    }

    @Override
    public void flush(String filePath) {
        // RandomAccessFile每次写入即刷盘
    }
}
