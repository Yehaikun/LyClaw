package lyjew.com.lyclaw.base;

import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.strategy.FormatStrategy;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class BaseStorage<T> {

    protected final FileRepository fileRepository;
    protected final String subDir;
    protected final FormatStrategy<T> formatStrategy;

    public BaseStorage(FileRepository fileRepository, String subDir, FormatStrategy<T> formatStrategy) {
        this.fileRepository = fileRepository;
        this.subDir = subDir;
        this.fileRepository.ensureDir(subDir);
        this.formatStrategy = formatStrategy;
    }

    public void save(T entity) {
        String id = extractId(entity);
        String path = getFilePath(id);
        beforeSave(entity);
        String content = formatStrategy.serialize(entity);
        fileRepository.write(path, content);
        afterSave(entity);
    }

    public Optional<T> get(String id) {
        String path = getFilePath(id);
        String content = fileRepository.read(path);
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        T entity = formatStrategy.deserialize(content, getEntityClass());
        return Optional.ofNullable(entity);
    }

    public boolean exists(String id) {
        String path = getFilePath(id);
        return fileRepository.exists(path);
    }

    public boolean delete(String id) {
        String path = getFilePath(id);
        return fileRepository.delete(path);
    }

    public List<T> getAll() {
        List<String> filePaths = fileRepository.listFiles(subDir, formatStrategy.suffix());
        List<T> results = new ArrayList<>();
        for (String filePath : filePaths) {
            String content = fileRepository.read(filePath);
            if (content != null && !content.isEmpty()) {
                results.add(formatStrategy.deserialize(content, getEntityClass()));
            }
        }
        return results;
    }

    private String extractIdFromPath(String filePath) {
        String fileName = Paths.get(filePath).getFileName().toString();
        return fileName.replace(".json", "");
    }

    protected void beforeSave(T entity) {}

    protected void afterSave(T entity) {}

    protected String getFilePath(String id) {
        return subDir + "/" + id + "." + formatStrategy.suffix();
    }

    protected abstract String extractId(T entity);
    protected abstract Class<T> getEntityClass();
}
