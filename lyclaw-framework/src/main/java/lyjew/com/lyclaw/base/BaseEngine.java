package lyjew.com.lyclaw.base;

/**
 * 引擎抽象基类，为所有引擎实现提供数据目录管理。
 *
 * <p>子类通过继承此类获得统一的数据存储目录访问能力，
 * 通常用于文件型引擎的数据读写路径管理。</p>
 */
public abstract class BaseEngine {
    /** 引擎的数据存储根目录 */
    protected final String dataDir;

    /**
     * @param dataDir 引擎的数据存储根目录
     */
    public BaseEngine(String dataDir) {
        this.dataDir = dataDir;
    }
}
