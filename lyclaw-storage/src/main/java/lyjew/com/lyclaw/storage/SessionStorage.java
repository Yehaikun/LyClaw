package lyjew.com.lyclaw.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.base.BaseStorage;
import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.strategy.JsonFormatStrategy;
import org.springframework.stereotype.Component;

/**
 * 会话存储
 * 负责读写 sessions/ 目录下的会话文件
 */
@Slf4j
@Component
public class SessionStorage extends BaseStorage<Session> {

    public SessionStorage(FileRepository fileRepository, ObjectMapper objectMapper) {
        super(fileRepository, "sessions", new JsonFormatStrategy<>(objectMapper));
    }

    @Override
    protected String extractId(Session entity) {
        return entity.getId();
    }

    @Override
    protected Class<Session> getEntityClass() {
        return Session.class;
    }
}
