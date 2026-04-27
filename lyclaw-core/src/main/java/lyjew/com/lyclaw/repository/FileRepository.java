package lyjew.com.lyclaw.repository;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public interface FileRepository {


    // ========== 基础读写 ==========

    String read(String relativePath);

    <T> T read(String relativePath, Class<T> clazz);

    void write(String relativePath, String content);

    void write(String relativePath, Object object);

    // ========== 文件操作 ==========

    boolean delete(String relativePath);

    boolean exists(String relativePath);

    void ensureDir(String relativePath);

    // ========== 目录扫描（用于 getAll） ==========

    List<String> listFiles(String relativePath);

    List<String> listFiles(String relativePath, String suffix);


    // ========== 辅助方法 ==========

    String getDataDir();

    ObjectMapper getObjectMapper();  // 特殊场景复用

}