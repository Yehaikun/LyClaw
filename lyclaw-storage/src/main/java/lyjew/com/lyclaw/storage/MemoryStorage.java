package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.base.BaseStorage;
import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.model.Memory;
import lyjew.com.lyclaw.strategy.MarkdownFormatStrategy;
import org.springframework.stereotype.Component;

/**
 * Memory持久化存储组件。
 *
 * <p>继承自{@link BaseStorage}，专门负责{@link Memory}实体的文件持久化。
 * 使用{@link MarkdownFormatStrategy}作为序列化策略，将Memory对象以Markdown
 * 格式（YAML Front Matter + 正文）存储到文件系统中。</p>
 *
 * <p>数据文件存放在"memory"子目录下，每个Memory实体对应一个Markdown文件。</p>
 *
 * @author lyjew
 */
@Component
public class MemoryStorage extends BaseStorage<Memory> {

    /**
     * @param fileRepository 文件仓库，用于底层文件读写操作
     */
    public MemoryStorage(FileRepository fileRepository) {
        super(fileRepository, "memory", new MarkdownFormatStrategy());
    }

    /**
     * 从Memory实体中提取唯一标识ID。
     *
     * @param entity Memory实体
     * @return 实体的ID字符串，用作文件名
     */
    @Override
    protected String extractId(Memory entity) {
        return entity.getId();
    }

    /**
     * 返回当前存储组件管理的实体类型。
     *
     * @return Memory.class
     */
    @Override
    protected Class<Memory> getEntityClass() {
        return Memory.class;
    }
}
