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
 * 文件读写工具类
 * 提供基本的文件操作，被其他Storage复用
 */
@Slf4j
@Component
public class LocalFileEngine extends AbstractFileEngine implements FileRepository {


    public LocalFileEngine(@Value("${lyclaw.data-dir:./LyClaw}") String dataDir){
        super(dataDir);
        ensureDir("");
    }

    /**
     * 读取文件内容
     * @param relativePath 相对于dataDir的路径，如 "configs/minimax.json"
     * @return 文件内容，如果文件不存在返回null
     */
    @Override
    public String read(String relativePath) {
        Path path = Paths.get(dataDir, relativePath);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new StorageException("STORAGE_READ_ERROR",
                    "读取文件失败: " + relativePath, e);
        }
    }

    /**
     * 读取文件并解析为对象
     * @param relativePath 相对于dataDir的路径
     * @param clazz 要解析成的类
     * @return 解析后的对象，如果文件不存在返回null
     */
    @Override
    public <T> T read(String relativePath, Class<T> clazz) {
        String content = read(relativePath);
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(content, clazz);
        } catch (IOException e) {
            throw new StorageException("STORAGE_PARSE_ERROR",
                    "解析文件失败: " + relativePath, e);
        }
    }

    /**
     * 写入文件（原子操作：先写tmp再rename）
     * @param relativePath 相对于dataDir的路径
     * @param content 文件内容
     */
    @Override
    public void write(String relativePath, String content) {
        Path path = Paths.get(dataDir, relativePath);
        Path tempPath = Paths.get(dataDir, relativePath + ".tmp");
        log.debug("path:{}, tmpPath:{}", path, tempPath);
        log.debug("FileStorage写入到：{}", relativePath);


        try {
            // 确保父目录存在
            Files.createDirectories(path.getParent());
            // 先写入临时文件
            Files.writeString(tempPath, content);
            // rename为正式文件（原子操作）
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("STORAGE_WRITE_ERROR",
                    "写入文件失败: " + relativePath, e);
        }
    }

    /**
     * 写入对象（先序列化为JSON再写入）
     * @param relativePath 相对于dataDir的路径
     * @param object 要写入的对象
     */
    @Override
    public void write(String relativePath, Object object) {
        try {
            String content = objectMapper.writeValueAsString(object);
            write(relativePath, content);
        } catch (Exception e) {
            throw new StorageException("STORAGE_WRITE_ERROR",
                    "序列化对象失败: " + relativePath, e);
        }
    }

    /**
     * 删除文件
     * @param relativePath 相对于dataDir的路径
     * @return 是否删除成功
     */
    @Override
    public boolean delete(String relativePath) {
        Path path = Paths.get(dataDir, relativePath);

        if (!Files.exists(path)) {
            return false;
        }
        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            throw new StorageException("STORAGE_DELETE_ERROR",
                    "删除文件失败: " + relativePath, e);
        }
    }

    /**
     * 检查文件是否存在
     * @param relativePath 相对于dataDir的路径
     */
    @Override
    public boolean exists(String relativePath) {
        Path path = Paths.get(dataDir, relativePath);
        return Files.exists(path);
    }

    /**
     * 确保目录存在，不存在则创建
     * @param relativePath 相对于dataDir的路径
     */
    @Override
    public void ensureDir(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return;
        }
        Path path = Paths.get(dataDir, relativePath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new StorageException("STORAGE_CREATE_DIR_ERROR",
                        "创建目录失败: " + relativePath, e);
            }
        }
    }

    /**
     * 获取数据目录路径
     */
    @Override
    public String getDataDir() {
        return dataDir;
    }

    /**
     * 获取ObjectMapper供外部使用
     * @return
     */
    @Override
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    @Override
    public List<String> listFiles(String relativePath) {
        return listFiles(relativePath, null);  // 直接调用，不需要 subList
    }

    /**
     * 输入 listFiles("cron", "json") => cron/jobs.json cron/jobs1.json ...
     * @param relativePath
     * @param suffix
     * @return
     */
    @Override
    public List<String> listFiles(String relativePath, String suffix) {
        Path dir = Paths.get(dataDir, relativePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();  // 返回空列表，不抛异常
        }

        try (Stream<Path> walk = Files.walk(dir, 1)) {
            return walk
                    .filter(path -> !path.equals(dir))
                    .filter(Files::isRegularFile)
                    .filter(path -> suffix == null || path.toString().endsWith(suffix))
                    .map(path -> relativePath + "/" + path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new StorageException("STORAGE_LIST_ERROR",
                    "列出目录失败: " + relativePath, e);
        }
    }

}
