package lyjew.com.lyclaw.retrieval;

import java.util.List;
import java.util.Map;

public interface VectorStore {

    void store(String id, List<Float> vector, Map<String, Object> metadata);
    List<SearchResult> search(List<Float> queryVector, int topK);
    void delete(String id);
    String getCollectionName();
}
