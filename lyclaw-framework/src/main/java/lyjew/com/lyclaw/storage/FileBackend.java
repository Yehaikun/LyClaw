package lyjew.com.lyclaw.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.annotation.storage.EntityStore;
import lyjew.com.lyclaw.exception.StorageException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 本地文件存储后端——框架默认持久化实现。
 *
 * <p>基于 JSON 文件存储，每个命名空间对应一个子目录，每个键对应一个 .json 文件。
 * 使用 Jackson ObjectMapper 序列化/反序列化。线程安全（文件级锁）。
 *
 * <p>支持的基本能力：KEY_VALUE、BLOB。
 * 不支持向量搜索、全文搜索和图查询。
 */
@lyjew.com.lyclaw.annotation.storage.StorageBackend(name = "file", displayName = "文件存储", priority = 100)
@EntityStore(layerPriority = 0, layerDefault = true)
public class FileBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(FileBackend.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path baseDir;
    private boolean initialized;

    public FileBackend() {}

    public FileBackend(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public String backendName() {
        return "file";
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return EnumSet.of(StorageCapability.KEY_VALUE, StorageCapability.BLOB);
    }

    @Override
    public void initialize(Map<String, Object> config) {
        if (baseDir == null) {
            Object pathObj = config.get("dataDir");
            if (pathObj != null) {
                baseDir = Paths.get(pathObj.toString());
            } else {
                baseDir = Paths.get(System.getProperty("user.dir"), "data", "storage");
            }
        }
        initialized = true;
        log.info("FileBackend 初始化完成: {}", baseDir.toAbsolutePath());
    }

    @Override
    public HealthResult healthCheck() {
        boolean writable = Files.isWritable(baseDir);
        return writable
                ? HealthResult.up("FileBackend 正常，路径: " + baseDir, Map.of("path", baseDir.toString()))
                : HealthResult.down("FileBackend 目录不可写: " + baseDir);
    }

    @Override
    public <T> void put(String namespace, String key, T value) {
        ensureInitialized();
        Path file = resolveFile(namespace, key);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new StorageException("FILE_WRITE_FAILED",
                    "写入文件失败: " + file + ", key=" + key, e);
        }
    }

    @Override
    public <T> T get(String namespace, String key, Class<T> type) {
        ensureInitialized();
        Path file = resolveFile(namespace, key);
        if (!Files.exists(file)) return null;
        try {
            return objectMapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            log.warn("读取文件失败: {}, 返回 null", file, e);
            return null;
        }
    }

    @Override
    public <T> List<T> list(String namespace, Class<T> type) {
        ensureInitialized();
        Path nsDir = resolveNamespace(namespace);
        if (!Files.exists(nsDir)) return Collections.emptyList();
        try (var stream = Files.list(nsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".json"))
                    .map(f -> {
                        try {
                            return objectMapper.readValue(f.toFile(), type);
                        } catch (IOException e) {
                            log.warn("读取文件失败: {}, 跳过", f, e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new StorageException("FILE_LIST_FAILED", "列出文件失败: namespace=" + namespace, e);
        }
    }

    @Override
    public void delete(String namespace, String key) {
        ensureInitialized();
        Path file = resolveFile(namespace, key);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", file, e);
        }
    }

    @Override
    public boolean exists(String namespace, String key) {
        ensureInitialized();
        return Files.exists(resolveFile(namespace, key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void putBatch(String namespace, Map<String, T> entries) {
        ensureInitialized();
        for (Map.Entry<String, T> entry : entries.entrySet()) {
            put(namespace, entry.getKey(), entry.getValue());
        }
    }

    @Override
    public <T> Map<String, T> getBatch(String namespace, Set<String> keys, Class<T> type) {
        Map<String, T> result = new HashMap<>();
        for (String key : keys) {
            T val = get(namespace, key, type);
            if (val != null) result.put(key, val);
        }
        return result;
    }

    @Override
    public QueryResult query(QuerySpec spec) {
        // FileBackend 不支持复杂查询，回退到遍历所有文件
        List<QueryResult.QueryResultItem> items = new ArrayList<>();
        Path nsDir = resolveNamespace(spec.getNamespace());
        if (Files.exists(nsDir)) {
            try (var stream = Files.list(nsDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".json"))
                        .forEach(f -> {
                            String id = f.getFileName().toString().replace(".json", "");
                            try {
                                Object content = objectMapper.readValue(f.toFile(), Object.class);
                                String contentStr = content != null ? content.toString() : "";
                                // 简单关键词匹配
                                double score = 0.0;
                                if (spec.getFullTextKeyword() != null
                                        && contentStr.toLowerCase().contains(spec.getFullTextKeyword().toLowerCase())) {
                                    score = 1.0;
                                }
                                items.add(new QueryResult.QueryResultItem(id, score, contentStr,
                                        Map.of("file", f.toString()), QueryPath.KEYWORD));
                            } catch (IOException ignored) {
                                // skip unreadable files
                            }
                        });
            } catch (IOException e) {
                log.warn("查询文件失败: {}", nsDir, e);
            }
        }
        return new QueryResult(items, List.of(QueryPath.KEYWORD), 0);
    }

    @Override
    public void flush() {
        // 文件存储天然持久化，无需 flush
    }

    @Override
    public void compact() {
        // 文件存储无需 compact
    }

    private Path resolveNamespace(String namespace) {
        return baseDir.resolve(sanitize(namespace));
    }

    private Path resolveFile(String namespace, String key) {
        return resolveNamespace(namespace).resolve(sanitize(key) + ".json");
    }

    private String sanitize(String input) {
        return input.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new StorageException("FILE_BACKEND_NOT_INITIALIZED",
                    "FileBackend 尚未初始化，请先调用 initialize()");
        }
    }
}
