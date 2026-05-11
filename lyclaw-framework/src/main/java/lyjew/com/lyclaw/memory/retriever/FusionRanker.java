package lyjew.com.lyclaw.memory.retriever;

import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;

import java.util.List;

public interface FusionRanker {

    List<MemoryEntry> rank(List<MemoryEntry> candidates, MemoryQuery query);
    double computeFusionScore(MemoryEntry entry, MemoryQuery query);
}
