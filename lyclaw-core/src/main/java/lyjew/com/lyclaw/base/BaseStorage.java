package lyjew.com.lyclaw.base;

import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.strategy.FormatStrategy;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * json持久化类基类
 * @param <T> 持久化对象
 */

public abstract class BaseStorage<T> {

    protected final FileRepository fileRepository;
    protected final String subDir;
    protected final FormatStrategy<T> formatStrategy;  // ← 新增


    public BaseStorage(FileRepository fileRepository, String subDir, FormatStrategy<T> formatStrategy){
        this.fileRepository = fileRepository;
        this.subDir=subDir;
        this.fileRepository.ensureDir(subDir);
        this.formatStrategy = formatStrategy;

    }

    // ========== 公共方法（子类直接继承） ==========

    public void save(T entity) {
        String id = extractId(entity);
        String path = getFilePath(id);
        beforeSave(entity);
        String content = formatStrategy.serialize(entity);  // ← 由策略决定格式
        fileRepository.write(path, content);
        afterSave(entity);
    }

    public Optional<T> get(String id) {
        String path = getFilePath(id);
        String content = fileRepository.read(path);
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        T entity = formatStrategy.deserialize(content, getEntityClass());  // ← 由策略决定格式
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
        List<String> filePaths = fileRepository.listFiles(subDir, formatStrategy.suffix());  // ← 由策略决定后缀
        List<T> results = new java.util.ArrayList<>();
        for (String filePath : filePaths) {
            String content = fileRepository.read(filePath);
            if (content != null && !content.isEmpty()) {
                results.add(formatStrategy.deserialize(content, getEntityClass()));
            }
        }
        return results;
    }

    private String extractIdFromPath(String filePath) {
        // 输入: "sessions/abc-123.json" → 返回 "abc-123"
        String fileName = Paths.get(filePath).getFileName().toString();
        return fileName.replace(".json", "");
    }

    // ========== 钩子方法（子类可选重写） ==========

    protected void beforeSave(T entity) {
        // 默认空实现，子类可重写做预处理（如生成ID）
    }

    protected void afterSave(T entity) {
        // 默认空实现，子类可重写做后处理（如日志）
    }

    protected String getFilePath(String id) {
        return subDir + "/" + id + "." + formatStrategy.suffix();  // ← 由策略决定后缀
    }

    //抽象方法
    protected abstract String extractId(T entity);
    protected abstract Class<T> getEntityClass();

}
