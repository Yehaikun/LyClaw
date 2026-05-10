package lyjew.com.lyclaw.memory.extractor;

import lyjew.com.lyclaw.memory.MemoryEntry;

import java.util.List;

public interface MemoryExtractor {

    List<MemoryEntry> extract(String conversation, List<MemoryEntry> existingMemories);
    boolean supportsRealtime();
    String getExtractorName();
}
