package lyjew.com.lyclaw.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lyjew.com.lyclaw.base.BaseEngine;

/**
 * 基于文件的引擎抽象基类，为需要读写文件的引擎提供 Jackson 序列化支持。
 *
 * <p>该类继承自 {@link BaseEngine}，在构造时初始化一个预配置的
 * {@link ObjectMapper}，适用于所有需要从文件系统读取和写入数据的引擎实现。
 * ObjectMapper 的配置如下：</p>
 * <ul>
 *   <li>日期序列化为 ISO-8601 字符串（而非时间戳数值）</li>
 *   <li>反序列化时忽略 JSON 中未知的属性</li>
 *   <li>注册 JavaTimeModule 以正确处理 Java 8+ 时间类型</li>
 * </ul>
 *
 * <p>子类通过继承此类可以直接复用 objectMapper 进行 JSON 文件的读写操作。</p>
 */
public abstract class AbstractFileEngine extends BaseEngine {

    /** 预配置的 Jackson ObjectMapper，支持 Java 8 时间类型和容错反序列化 */
    protected final ObjectMapper objectMapper;

    /**
     * 构造引擎实例，初始化文件系统路径和 ObjectMapper。
     *
     * @param dataDir 引擎的数据存储根目录
     */
    public AbstractFileEngine(String dataDir) {
        super(dataDir);
        this.objectMapper = new ObjectMapper();
        // 日期序列化为 ISO-8601 字符串格式，而非数字时间戳
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // 反序列化时忽略 JSON 中未定义的属性，避免因新增字段导致异常
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 注册 JavaTimeModule 以支持 LocalDate、LocalDateTime 等类型
        this.objectMapper.registerModule(new JavaTimeModule());
    }
}
