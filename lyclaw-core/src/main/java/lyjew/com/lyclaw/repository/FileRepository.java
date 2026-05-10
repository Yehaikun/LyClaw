package lyjew.com.lyclaw.repository;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public interface FileRepository {

    String read(String relativePath);
    <T> T read(String relativePath, Class<T> clazz);
    void write(String relativePath, String content);
    void write(String relativePath, Object object);
    boolean delete(String relativePath);
    boolean exists(String relativePath);
    void ensureDir(String relativePath);
    List<String> listFiles(String relativePath);
    List<String> listFiles(String relativePath, String suffix);
    String getDataDir();
    ObjectMapper getObjectMapper();
}
