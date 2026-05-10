package lyjew.com.lyclaw.memory;

import java.util.List;

@Deprecated(since = "2.0", forRemoval = true)
public interface MemoryManager {

    MemoryContent read();
    void append(String content);
    void rewrite(String content);
    List<MemoryContent> search(String query);
    void flush();
    MemoryStrategy getStrategy();
    void setStrategy(MemoryStrategy strategy);
}
