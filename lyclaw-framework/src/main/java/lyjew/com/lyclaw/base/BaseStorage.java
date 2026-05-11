package lyjew.com.lyclaw.base;

import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.strategy.FormatStrategy;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文件存储抽象基类，提供基于文件系统的实体 CRUD 模板方法。
 *
 * <p>将实体序列化为文件持久化到磁盘，通过 {@link FileRepository} 读写文件，
 * 通过 {@link FormatStrategy} 进行序列化/反序列化。
 * 子类只需实现 {@link #extractId} 和 {@link #getEntityClass} 即可获得完整的存储能力。</p>
 *
 * <p>保存流程：提取 ID → 构建路径 → beforeSave 钩子 → 序列化 → 写入文件 → afterSave 钩子。</p>
 *
 * @param <T> 实体类型
 */
public abstract class BaseStorage<T> {

    /** 底层文件仓库 */
    protected final FileRepository fileRepository;
    /** 实体存储子目录 */
    protected final String subDir;
    /** 序列化/反序列化策略 */
    protected final FormatStrategy<T> formatStrategy;

    /**
     * @param fileRepository 文件仓库
     * @param subDir         存储子目录
     * @param formatStrategy 序列化策略
     */
    public BaseStorage(FileRepository fileRepository, String subDir, FormatStrategy<T> formatStrategy) {
        this.fileRepository = fileRepository;
        this.subDir = subDir;
        this.fileRepository.ensureDir(subDir); // 确保子目录存在
        this.formatStrategy = formatStrategy;
    }

    /**
     * 保存实体到文件。
     *
     * @param entity 待保存的实体
     */
    public void save(T entity) {
        String id = extractId(entity);
        String path = getFilePath(id);
        beforeSave(entity);                             // 保存前钩子
        String content = formatStrategy.serialize(entity); // 序列化为字符串
        fileRepository.write(path, content);
        afterSave(entity);                              // 保存后钩子
    }

    /**
     * 按 ID 读取实体。
     *
     * @param id 实体唯一标识
     * @return 包含实体的 Optional，不存在时为空
     */
    public Optional<T> get(String id) {
        String path = getFilePath(id);
        String content = fileRepository.read(path);
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        T entity = formatStrategy.deserialize(content, getEntityClass());
        return Optional.ofNullable(entity);
    }

    /** @return 指定 ID 的实体是否存在 */
    public boolean exists(String id) {
        String path = getFilePath(id);
        return fileRepository.exists(path);
    }

    /** @return 删除是否成功 */
    public boolean delete(String id) {
        String path = getFilePath(id);
        return fileRepository.delete(path);
    }

    /**
     * 获取子目录下所有实体。
     *
     * <p>遍历子目录中所有匹配后缀的文件，逐个反序列化。读取失败的文件静默跳过。</p>
     *
     * @return 实体列表
     */
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

    /** 从文件路径中提取实体 ID（去除 .json 后缀）。 */
    private String extractIdFromPath(String filePath) {
        String fileName = Paths.get(filePath).getFileName().toString();
        return fileName.replace(".json", "");
    }

    /** 保存前回调钩子，子类可覆盖。 */
    protected void beforeSave(T entity) {}

    /** 保存后回调钩子，子类可覆盖。 */
    protected void afterSave(T entity) {}

    /** 构建实体文件的完整路径：subDir/id.suffix */
    protected String getFilePath(String id) {
        return subDir + "/" + id + "." + formatStrategy.suffix();
    }

    /** 从实体中提取唯一标识 */
    protected abstract String extractId(T entity);
    /** @return 实体 Class */
    protected abstract Class<T> getEntityClass();
}
