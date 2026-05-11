package lyjew.com.lyclaw.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.exception.StorageException;
import lyjew.com.lyclaw.repository.FileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地文件系统存储引擎，基于Java NIO实现文件的原子读写操作。
 *
 * <p>
 * 使用先写临时文件(.tmp)再原子移动的策略保证写入的原子性，
 * 避免在写入过程中因异常导致文件损坏。
 * 数据目录通过{@code lyclaw.data-dir}配置项指定。
 * </p>
 *
 * <p>
 * 核心能力：文件CRUD、JSON序列化存储、目录遍历（支持后缀过滤）、
 * 目录自动创建。继承自{@link AbstractFileEngine}，实现{@link FileRepository}接口。
 * </p>
 *
 * @author lyjew
 */
@Slf4j
@Component
public class LocalFileEngine extends AbstractFileEngine implements FileRepository {

    /**
     * @param dataDir 数据存储根目录，通过{@code lyclaw.data-dir}配置，
     *                默认值为/ home/lyjew/Documents/Unicom/LyClaw/LyClaw
     */
    public LocalFileEngine(@Value("${lyclaw.data-dir:/home/lyjew/Documents/Unicom/LyClaw/LyClaw}") String dataDir) {
        super(dataDir);
        ensureDir("");
    }

    /**
     * 读取文件内容为字符串。
     *
     * @param relativePath 相对于dataDir的文件路径
     * @return 文件内容，文件不存在时返回null
     * @throws StorageException 读取IO异常时抛出
     */
    @Override
    public String read(String relativePath) {
        Path path = Paths.get(dataDir, relativePath);
        if (!Files.exists(path)) return null;
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new StorageException("STORAGE_READ_ERROR", "Failed to read file: " + relativePath, e);
        }
    }

    /**
     * 读取JSON文件并反序列化为指定类型的Java对象。
     *
     * @param <T>          目标类型
     * @param relativePath 相对于dataDir的文件路径（JSON文件）
     * @param clazz        目标类型的Class对象
     * @return 反序列化后的对象，文件不存在或为空时返回null
     * @throws StorageException 读取或JSON解析失败时抛出
     */
    @Override
    public <T> T read(String relativePath, Class<T> clazz) {
        String content = read(relativePath);
        if (content == null || content.isEmpty()) return null;
        try {
            return objectMapper.readValue(content, clazz);
        } catch (IOException e) {
            throw new StorageException("STORAGE_PARSE_ERROR", "Failed to parse file: " + relativePath, e);
        }
    }

    /**
     * 将字符串内容原子写入文件。
     *
     * <p>原子写入策略：先将内容写入临时文件(.tmp)，
     * 然后通过{@link StandardCopyOption#REPLACE_EXISTING}原子移动至目标路径，
     * 确保写入过程不会产生文件损坏的中间状态。</p>
     *
     * @param relativePath 相对于dataDir的目标文件路径
     * @param content      要写入的字符串内容
     * @throws StorageException 写入IO异常时抛出
     */
    @Override
    public void write(String relativePath, String content) {
        Path path = Paths.get(dataDir, relativePath);
        // 临时文件路径，用于原子写入
        Path tempPath = Paths.get(dataDir, relativePath + ".tmp");
        log.debug("path:{}, tmpPath:{}", path, tempPath);

        try {
            // 自动创建父目录
            Files.createDirectories(path.getParent());
            // 先写入临时文件
            Files.writeString(tempPath, content);
            // 原子移动到目标路径
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("STORAGE_WRITE_ERROR", "Failed to write file: " + relativePath, e);
        }
    }

    /**
     * 将Java对象序列化为JSON后原子写入文件。
     *
     * @param relativePath 相对于dataDir的目标文件路径
     * @param object       要写入的Java对象，会被Jackson序列化为JSON
     * @throws StorageException 序列化或写入失败时抛出
     */
    @Override
    public void write(String relativePath, Object object) {
        try {
            String content = objectMapper.writeValueAsString(object);
            write(relativePath, content);
        } catch (Exception e) {
            throw new StorageException("STORAGE_WRITE_ERROR", "Failed to serialize object: " + relativePath, e);
        }
    }

    /**
     * 删除指定文件。
     *
     * @param relativePath 相对于dataDir的文件路径
     * @return true表示文件存在并已删除，false表示文件不存在
     * @throws StorageException 删除IO异常时抛出
     */
    @Override
    public boolean delete(String relativePath) {
        Path path = Paths.get(dataDir, relativePath);
        if (!Files.exists(path)) return false;
        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            throw new StorageException("STORAGE_DELETE_ERROR", "Failed to delete file: " + relativePath, e);
        }
    }

    /**
     * 检查文件是否存在。
     *
     * @param relativePath 相对于dataDir的文件路径
     * @return true表示文件存在
     */
    @Override
    public boolean exists(String relativePath) {
        Path path = Paths.get(dataDir, relativePath);
        return Files.exists(path);
    }

    /**
     * 确保目录存在，不存在则递归创建。
     *
     * @param relativePath 相对于dataDir的目录路径
     * @throws StorageException 创建目录IO异常时抛出
     */
    @Override
    public void ensureDir(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return;
        Path path = Paths.get(dataDir, relativePath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new StorageException("STORAGE_CREATE_DIR_ERROR", "Failed to create directory: " + relativePath, e);
            }
        }
    }

    /**
     * 获取数据存储根目录路径。
     *
     * @return 配置的dataDir绝对路径
     */
    @Override
    public String getDataDir() {
        return dataDir;
    }

    /**
     * 获取Jackson ObjectMapper实例。
     *
     * @return 共享的ObjectMapper，用于JSON序列化与反序列化
     */
    @Override
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * 列出目录下所有文件（不限后缀）。
     *
     * @param relativePath 相对于dataDir的目录路径
     * @return 文件相对路径列表
     */
    @Override
    public List<String> listFiles(String relativePath) {
        return listFiles(relativePath, null);
    }

    /**
     * 列出目录下符合后缀条件的文件。
     *
     * <p>使用{@link Files#walk(Path, int)}遍历目录（深度1，仅直接子文件），
     * 通过Stream过滤出符合条件的常规文件。</p>
     *
     * @param relativePath 相对于dataDir的目录路径
     * @param suffix       文件后缀过滤条件（如"md"），为null时不过滤
     * @return 文件相对路径列表（格式：目录/文件名）
     * @throws StorageException 目录遍历IO异常时抛出
     */
    @Override
    public List<String> listFiles(String relativePath, String suffix) {
        Path dir = Paths.get(dataDir, relativePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return List.of();

        // 遍历目录深度为1（仅直接子文件）
        try (Stream<Path> walk = Files.walk(dir, 1)) {
            return walk
                    .filter(path -> !path.equals(dir))          // 排除目录本身
                    .filter(Files::isRegularFile)               // 仅常规文件
                    .filter(path -> suffix == null || path.toString().endsWith(suffix)) // 后缀过滤
                    .map(path -> relativePath + "/" + path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new StorageException("STORAGE_LIST_ERROR", "Failed to list directory: " + relativePath, e);
        }
    }
}
