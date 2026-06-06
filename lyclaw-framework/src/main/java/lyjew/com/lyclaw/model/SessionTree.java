package lyjew.com.lyclaw.model;

import java.util.List;

/**
 * 会话树 —— 多 Agent 场景中，一个根会话及其所有子会话的层级结构。
 *
 * <p>当 Agent A 委托给子 Agent B 时，B 的整个推理过程存储在子会话中，
 * 通过 SessionTree 可以递归获取完整的调用链。</p>
 */
public class SessionTree {

    private final Session rootSession;
    private final List<SessionTreeBranch> branches;

    public SessionTree(Session rootSession, List<SessionTreeBranch> branches) {
        this.rootSession = rootSession;
        this.branches = branches;
    }

    public Session getRootSession() { return rootSession; }
    public List<SessionTreeBranch> getBranches() { return branches; }

    /** 会话树中的一条分支（子会话及其消息） */
    public static class SessionTreeBranch {
        private final Session childSession;
        private final List<Message> messages;
        private final int linkedFromMsgIndex;
        private final List<SessionTreeBranch> subBranches;

        public SessionTreeBranch(Session childSession, List<Message> messages,
                                  int linkedFromMsgIndex, List<SessionTreeBranch> subBranches) {
            this.childSession = childSession;
            this.messages = messages;
            this.linkedFromMsgIndex = linkedFromMsgIndex;
            this.subBranches = subBranches;
        }

        public Session getChildSession() { return childSession; }
        public List<Message> getMessages() { return messages; }
        public int getLinkedFromMsgIndex() { return linkedFromMsgIndex; }
        public List<SessionTreeBranch> getSubBranches() { return subBranches; }
    }
}
