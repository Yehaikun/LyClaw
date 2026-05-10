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

@Slf4j
@Component
public class LocalFileEngine extends AbstractFileEngine implements FileRepository {

    public LocalFileEngine(@Value("${lyclaw.data-dir:/home/lyjew/Documents/Unicom/LyClaw/LyClaw}") String dataDir) {
        super(dataDir);
        ensureDir("");
    }

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

    @Override
    public void write(String relativePath, String content) {
        Path path = Paths.get(dataDir, relativePath);
        Path tempPath = Paths.get(dataDir, relativePath + ".tmp");
        log.debug("path:{}, tmpPath:{}", path, tempPath);

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(tempPath, content);
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("STORAGE_WRITE_ERROR", "Failed to write file: " + relativePath, e);
        }
    }

    @Override
    public void write(String relativePath, Object object) {
        try {
            String content = objectMapper.writeValueAsString(object);
            write(relativePath, content);
        } catch (Exception e) {
            throw new StorageException("STORAGE_WRITE_ERROR", "Failed to serialize object: " + relativePath, e);
        }
    }

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

    @Override
    public boolean exists(String relativePath) {
        Path path = Paths.get(dataDir, relativePath);
        return Files.exists(path);
    }

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

    @Override
    public String getDataDir() {
        return dataDir;
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    @Override
    public List<String> listFiles(String relativePath) {
        return listFiles(relativePath, null);
    }

    @Override
    public List<String> listFiles(String relativePath, String suffix) {
        Path dir = Paths.get(dataDir, relativePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return List.of();

        try (Stream<Path> walk = Files.walk(dir, 1)) {
            return walk
                    .filter(path -> !path.equals(dir))
                    .filter(Files::isRegularFile)
                    .filter(path -> suffix == null || path.toString().endsWith(suffix))
                    .map(path -> relativePath + "/" + path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new StorageException("STORAGE_LIST_ERROR", "Failed to list directory: " + relativePath, e);
        }
    }
}
