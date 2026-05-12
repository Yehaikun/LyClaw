package lyjew.com.lyclaw.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * 存储层配置属性，对应 application.yml 中 lyclaw.storage.* 前缀。
 *
 * <p>支持按后端名称配置详细参数（url、用户名、密码、连接池、向量配置、TTL），
 * 以及各层默认后端绑定。不配任何配置时行为与当前完全一致。
 *
 * <p>Spring Boot 的 @ConfigurationProperties 绑定由 autoconfigure 模块的
 * StorageAutoConfiguration 通过 @EnableConfigurationProperties 完成。
 */
public class StorageProperties {

    /** 全局默认后端 */
    private String defaultBackend = "file";

    /** 各后端详细配置，key 为后端名称（如 sqlite、postgresql、redis） */
    private Map<String, BackendConfig> backends = new HashMap<>();

    /** 各层后端绑定，key 为层名（session/entity/memory） */
    private Map<String, String> stores = new HashMap<>();

    /** 默认值——不配任何配置时行为与当前完全一致 */
    public StorageProperties() {
        stores.put("session", "file");
        stores.put("entity", "file");
        stores.put("memory", "inmemory");
    }

    public String getDefaultBackend() { return defaultBackend; }
    public void setDefaultBackend(String defaultBackend) { this.defaultBackend = defaultBackend; }
    public Map<String, BackendConfig> getBackends() { return backends; }
    public void setBackends(Map<String, BackendConfig> backends) { this.backends = backends; }
    public Map<String, String> getStores() { return stores; }
    public void setStores(Map<String, String> stores) { this.stores = stores; }

    /** 获取某层的后端名称 */
    public String getLayerBackend(StoreLayer layer) {
        return stores.getOrDefault(layer.name().toLowerCase(), defaultBackend);
    }

    /**
     * 单个后端的详细配置。
     */
    public static class BackendConfig {
        /** 后端类型：file、sqlite、postgresql、redis、milvus、neo4j */
        private String type;
        /** 连接 URL */
        private String url;
        /** 用户名 */
        private String username;
        /** 密码 */
        private String password;
        /** 是否启用 */
        private boolean enabled = true;
        /** 连接池配置 */
        private PoolConfig pool = new PoolConfig();
        /** 向量配置 */
        private VectorConfig vector = new VectorConfig();
        /** 键生存时间（秒） */
        private int ttl = 3600;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public PoolConfig getPool() { return pool; }
        public void setPool(PoolConfig pool) { this.pool = pool; }
        public VectorConfig getVector() { return vector; }
        public void setVector(VectorConfig vector) { this.vector = vector; }
        public int getTtl() { return ttl; }
        public void setTtl(int ttl) { this.ttl = ttl; }
    }

    /** 连接池配置 */
    public static class PoolConfig {
        private int maxSize = 10;
        private int minIdle = 2;
        private long idleTimeoutMs = 300000;
        private long connectionTimeoutMs = 10000;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        public long getIdleTimeoutMs() { return idleTimeoutMs; }
        public void setIdleTimeoutMs(long idleTimeoutMs) { this.idleTimeoutMs = idleTimeoutMs; }
        public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(long connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
    }

    /** 向量索引配置 */
    public static class VectorConfig {
        private boolean enabled;
        private int dimension = 1536;
        private String indexType = "hnsw";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public String getIndexType() { return indexType; }
        public void setIndexType(String indexType) { this.indexType = indexType; }
    }
}
