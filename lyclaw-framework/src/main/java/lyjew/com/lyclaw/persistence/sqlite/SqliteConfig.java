package lyjew.com.lyclaw.persistence.sqlite;

import lombok.Builder;
import lombok.Value;

/**
 * SQLite配置持有类——封装数据库路径、WAL模式、连接池参数。
 * 由lyclaw-web层的StorageAutoConfiguration根据application.yml构建并注入。
 */
@Value
@Builder
public class SqliteConfig {
    /** SQLite数据库文件完整路径，如 /home/lyjew/.../LyClaw/index/lyclaw.db */
    String dbPath;
    /** WAL模式，默认true（Write-Ahead Logging，提升并发读写性能） */
    @Builder.Default
    boolean walMode = true;
    /** 连接池大小（SQLite单写，此值实际控制同时读的连接数） */
    @Builder.Default
    int poolSize = 5;
    /** 连接空闲超时（毫秒），超时未使用的连接将被回收 */
    @Builder.Default
    long idleTimeoutMs = 300_000;
}
