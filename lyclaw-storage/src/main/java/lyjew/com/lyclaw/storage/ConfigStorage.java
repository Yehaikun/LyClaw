package lyjew.com.lyclaw.storage;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.base.BaseStorage;
import lyjew.com.lyclaw.repository.FileRepository;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.strategy.JsonFormatStrategy;
import org.springframework.stereotype.Component;


/**
 * 模型配置存储
 * 负责读写 configs/ 目录下的配置
 */

@Slf4j
@Component
public class ConfigStorage extends BaseStorage<ModelConfig> {


    public ConfigStorage(FileRepository fileRepository, ObjectMapper objectMapper) {
        super(fileRepository, "configs", new JsonFormatStrategy<>(objectMapper));
    }

    @Override
    protected String extractId(ModelConfig entity) {
        return entity.getName();
    }

    @Override
    protected Class<ModelConfig> getEntityClass() {
        return ModelConfig.class;
    }
}
