package lyjew.com.lyclaw.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite连接管理器。
 *
 * 使用SQLiteDataSource连接池（SQLite JDBC内置），WAL模式启用。
 * 单例生命周期由Spring管理（@Bean在lyclaw-web层创建）。
 * 连接池大小由{@link SqliteConfig#poolSize}控制。
 */
public class SqliteConnectionManager implements AutoCloseable {

    private final SQLiteDataSource dataSource;
    private final SqliteConfig config;

    /**
     * 根据配置初始化SQLite数据源。
     * 自动创建数据库文件父目录，启用WAL模式和5秒忙等待超时。
     */
    public SqliteConnectionManager(SqliteConfig config) {
        this.config = config;
        java.io.File dbFile = new java.io.File(config.getDbPath());
        dbFile.getParentFile().mkdirs();

        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        if (config.isWalMode()) {
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        }
        sqLiteConfig.setBusyTimeout(5000);

        this.dataSource = new SQLiteDataSource(sqLiteConfig);
        this.dataSource.setUrl("jdbc:sqlite:" + config.getDbPath());
    }

    /** 从连接池获取一个JDBC连接。调用者负责关闭（try-with-resources）。 */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 返回当前配置的数据库文件路径 */
    public String getDbPath() {
        return config.getDbPath();
    }

    @Override
    public void close() {
        // SQLiteDataSource没有显式close，连接池由GC处理
    }
}
