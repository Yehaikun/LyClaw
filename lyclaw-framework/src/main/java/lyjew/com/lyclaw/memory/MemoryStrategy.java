package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.context.ChatContext;

public interface MemoryStrategy {

    String formatForContext(MemoryContent memory);
    boolean shouldIncludeInContext(MemoryContent memory, ChatContext context);
    int getPriority();
}
