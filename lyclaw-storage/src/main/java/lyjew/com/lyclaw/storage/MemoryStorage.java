package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.base.BaseStorage;
import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.model.Memory;
import lyjew.com.lyclaw.strategy.MarkdownFormatStrategy;
import org.springframework.stereotype.Component;

@Component
public class MemoryStorage extends BaseStorage<Memory> {

    public MemoryStorage(FileRepository fileRepository) {
        super(fileRepository, "memory", new MarkdownFormatStrategy());
    }

    @Override
    protected String extractId(Memory entity) {
        return entity.getId();
    }

    @Override
    protected Class<Memory> getEntityClass() {
        return Memory.class;
    }
}
