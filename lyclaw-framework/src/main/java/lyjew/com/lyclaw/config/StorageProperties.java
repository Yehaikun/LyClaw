package lyjew.com.lyclaw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储层完整配置 — 对应 application.yml 中 lyclaw.storage.*
 * 所有字段均有默认值，开箱即用。
 *
 * 替代原来仅有一个pageSize字段的StorageSessionProperties，
 * 将所有可配置项统一管理，通过@ConfigurationProperties注入。
 */
@ConfigurationProperties(prefix = "lyclaw.storage")
@Data
public class StorageProperties {

    /** 文件存储根目录（SQLite数据库 + JSONL文件均在此目录下） */
    private String basePath = "data/storage";

    /** 会话存储配置 */
    private SessionProperties session = new SessionProperties();

    @Data
    public static class SessionProperties {
        /** 懒加载/分页时每页消息条数 */
        private int pageSize = 50;

        /** 生成的sessionId长度（UUID截取前N位） */
        private int idLength = 8;

        /** 首条用户消息预览最大字符数 */
        private int previewMaxLength = 100;

        /** 异步写入队列容量（有界队列，超出时调用者线程阻塞写入） */
        private int writeQueueCapacity = 10000;

        /** JSONL写入失败最大重试次数 */
        private int writeMaxRetries = 3;

        /** 活跃会话缓存最大数量 */
        private int cacheMaxSize = 1000;

        /** 活跃会话缓存TTL（分钟），超时未访问自动淘汰 */
        private int cacheTtlMinutes = 30;
    }
}
