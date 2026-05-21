package lyjew.com.lyclaw.persistence.jsonl;

import java.util.Map;

/**
 * JSONL行写入器——每行一个JSON对象，行间\n分隔。
 * 实现类负责线程安全和文件追加。
 */
public interface JsonlWriter {
    /**
     * 追加一行JSON到文件末尾。
     * @param filePath 文件绝对路径
     * @param fields 要序列化为JSON的字段Map，键为字段名，值为任意可序列化对象
     */
    void appendLine(String filePath, Map<String, Object> fields);

    /** 强制flush缓冲区到磁盘，确保数据持久化 */
    void flush(String filePath);
}
