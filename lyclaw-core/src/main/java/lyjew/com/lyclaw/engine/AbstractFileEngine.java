package lyjew.com.lyclaw.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lyjew.com.lyclaw.base.BaseEngine;

public abstract class AbstractFileEngine extends BaseEngine {

    protected final ObjectMapper objectMapper;

    public AbstractFileEngine(String dataDir) {
        super(dataDir);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.registerModule(new JavaTimeModule());
    }
}