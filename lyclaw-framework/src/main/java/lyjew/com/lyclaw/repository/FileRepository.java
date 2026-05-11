package lyjew.com.lyclaw.repository;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 文件仓库接口，抽象文件系统的读写操作。
 *
 * <p>提供文本和对象两种读写模式，支持 JSON 序列化、
 * 目录保障、文件列表查询和路径拼接等基础文件操作。</p>
 */
public interface FileRepository {

    /** 按相对路径读取文件内容为字符串。 */
    String read(String relativePath);
    /** 按相对路径读取并反序列化为指定类型。 */
    <T> T read(String relativePath, Class<T> clazz);
    /** 写入字符串内容到文件。 */
    void write(String relativePath, String content);
    /** 将对象序列化为 JSON 写入文件。 */
    void write(String relativePath, Object object);
    /** 删除指定文件。 */
    boolean delete(String relativePath);
    /** 检查文件是否存在。 */
    boolean exists(String relativePath);
    /** 确保目录存在，不存在则创建。 */
    void ensureDir(String relativePath);
    /** 列出目录下所有文件路径。 */
    List<String> listFiles(String relativePath);
    /** 列出目录下指定后缀的文件路径。 */
    List<String> listFiles(String relativePath, String suffix);
    /** @return 数据存储根目录 */
    String getDataDir();
    /** @return 用于 JSON 序列化的 ObjectMapper */
    ObjectMapper getObjectMapper();
}
