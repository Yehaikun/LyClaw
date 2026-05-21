package lyjew.com.lyclaw.persistence.jsonl;

import java.util.List;
import java.util.Map;

/**
 * JSONL行读取器——支持全量读取、分页读取、首行读取和行数统计。
 * 用于会话恢复时懒加载消息历史和分页查询。
 */
public interface JsonlReader {
    /** 读取文件全部行，每行反序列化为Map。大文件慎用。 */
    List<Map<String, Object>> readAll(String filePath);

    /**
     * 分页读取指定范围的行。
     * @param offset -1表示取最新limit条；>=0表示从第offset行开始取limit条
     * @param limit 最大返回行数
     */
    List<Map<String, Object>> readRange(String filePath, int offset, int limit);

    /** 读取文件首行（通常是session_created事件），文件不存在时返回null */
    Map<String, Object> readFirstLine(String filePath);

    /** 统计文件总行数，文件不存在返回0 */
    int countLines(String filePath);
}
