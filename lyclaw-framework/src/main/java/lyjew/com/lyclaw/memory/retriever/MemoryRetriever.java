package lyjew.com.lyclaw.memory.retriever;

import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;

import java.util.List;

public interface MemoryRetriever {

    List<MemoryEntry> retrieve(MemoryQuery query, List<MemoryEntry> candidatePool);
    String getRetrievalMethod();
}
