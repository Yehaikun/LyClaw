package lyjew.com.lyclaw.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.base.BaseStorage;
import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.model.CronJob;
import lyjew.com.lyclaw.strategy.JsonFormatStrategy;
import org.springframework.stereotype.Component;

/**
 * 定时任务存储
 * 负责读写 cron/jobs.json 文件
 */
@Slf4j
@Component
public class CronStorage extends BaseStorage<CronJob> {


    public CronStorage(FileRepository fileRepository, ObjectMapper objectMapper) {
        super(fileRepository,"cron", new JsonFormatStrategy<>(objectMapper));
    }

    @Override
    protected String extractId(CronJob entity) {
        return entity.getId();
    }

    @Override
    protected Class<CronJob> getEntityClass() {
        return CronJob.class;
    }
}
