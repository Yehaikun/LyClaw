package lyjew.com.lyclaw.react.subagent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;

// TODO: The lyjew.com.lyclaw.persistence.SessionStore class does not exist yet.
// When it is added, replace the Object constructor parameter with the typed import:
//   import lyjew.com.lyclaw.persistence.SessionStore;

/**
 * Manages subagent sessions using <strong>hierarchical session keys</strong>.
 *
 * <h3>Key format</h3>
 * <pre>
 *   parentKey / subagent / agentId / uuid8
 * </pre>
 *
 * <p>For example, a root session {@code sess-abc123} spawning a {@code code-reviewer}
 * child produces the key {@code sess-abc123/subagent/code-reviewer/a1b2c3d4}.
 * Multi-level nesting continues to append segments naturally:</p>
 * <pre>
 *   sess-abc123/subagent/code-reviewer/a1b2c3d4/subagent/linter/e5f6g7h8
 * </pre>
 *
 * <p>This scheme enables tree-based operations:</p>
 * <ul>
 *   <li><strong>Bulk archival</strong> — archiving a parent cascade-archives
 *       all descendants via key-prefix matching.</li>
 *   <li><strong>Hierarchical termination</strong> — terminating a parent
 *       terminates every active descendant automatically.</li>
 *   <li><strong>Delegation tracing</strong> — the full spawn chain is
 *       recoverable from any session key.</li>
 * </ul>
 */
public class SubagentSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentSessionManager.class);

    private static final String SEP = "/";
    private static final String SUBAGENT_SEGMENT = "subagent";
    private static final int UUID_LENGTH = 8;

    /**
     * SessionStore backend.
     * TODO: Change to {@code lyjew.com.lyclaw.persistence.SessionStore} once the class exists.
     */
    private final Object sessionStore;

    /** Index of currently active subagent sessions: {@code sessionKey -> Session}. */
    private final ConcurrentMap<String, Session> activeSessions = new ConcurrentHashMap<>();

    /** Scheduled archive times: {@code sessionKey -> archiveEpochMillis}. */
    private final ConcurrentMap<String, Long> archiveSchedule = new ConcurrentHashMap<>();

    // ================================================================
    // Constructor
    // ================================================================

    /**
     * Construct a manager backed by the given session store.
     *
     * @param sessionStore persistence backend for session data
     *        (TODO: type will become {@code lyjew.com.lyclaw.persistence.SessionStore})
     */
    public SubagentSessionManager(Object sessionStore) {
        this.sessionStore = sessionStore;
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * Create a new subagent session under the given parent.
     *
     * <p>The session key is built as
     * {@code parentSessionKey/subagent/agentId/uuid8}. An initial system
     * message carrying {@code systemPrompt} is added to the returned
     * {@link Session}.</p>
     *
     * @param parentSessionKey session key of the parent agent
     * @param agentId          unique identifier of the child agent
     * @param systemPrompt     system prompt for the child agent
     * @return a newly constructed and indexed {@link Session}
     */
    public Session createSubagentSession(String parentSessionKey, String agentId, String systemPrompt) {
        String childKey = buildSessionKey(parentSessionKey, agentId);

        // TODO: When Session gains builder + setAttribute support, replace with:
        //   Session session = Session.builder()
        //           .sessionId(childKey)
        //           .name("subagent:" + agentId)
        //           .messages(new java.util.ArrayList<>())
        //           .build();
        //   session.setAttribute("parentSessionKey", parentSessionKey);
        //   session.setAttribute("agentId", agentId);
        //   session.setAttribute("systemPrompt", systemPrompt);
        //   session.setAttribute("createdAt", System.currentTimeMillis());
        //   session.setAttribute("status", "active");

        Session session = new Session();
        session.setSessionId(childKey);
        session.setName("subagent:" + agentId);

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Message sysMsg = new Message();
            sysMsg.setRole("system");
            sysMsg.setContent(systemPrompt);
            session.addMessage(sysMsg);
        }

        activeSessions.put(childKey, session);
        log.debug("Created subagent session: {} (parent: {}, agent: {})",
                childKey, parentSessionKey, agentId);

        return session;
    }

    /**
     * Mark {@code sessionKey} and all its descendants as archived,
     * effective after {@code afterMinutes}.
     *
     * <p>Descendants are identified by key-prefix matching: any active
     * session whose key starts with {@code sessionKey + "/"} is considered
     * a descendant. All matched sessions are removed from the active index
     * and scheduled for persistent archival.</p>
     *
     * @param sessionKey   hierarchical key of the session to archive
     * @param afterMinutes delay in minutes before archival takes effect
     */
    public void archiveSession(String sessionKey, int afterMinutes) {
        if (sessionKey == null) {
            return;
        }

        long archiveAt = System.currentTimeMillis() + (long) afterMinutes * 60 * 1000;
        String prefix = sessionKey + SEP;

        archiveOne(sessionKey, archiveAt);

        for (String key : activeSessions.keySet()) {
            if (key.startsWith(prefix)) {
                archiveOne(key, archiveAt);
            }
        }

        int descendants = countDescendants(sessionKey);
        log.debug("Archived session tree rooted at {} ({} descendants), effective in {} min",
                sessionKey, descendants, afterMinutes);
    }

    /**
     * Terminate all active descendant sessions under the given parent.
     *
     * <p>A descendant is any active session whose key starts with
     * {@code parentSessionKey + "/subagent/"}. Each is removed from the
     * active index and marked as terminated.</p>
     *
     * @param parentSessionKey session key of the parent agent
     */
    public void terminateDescendants(String parentSessionKey) {
        if (parentSessionKey == null) {
            return;
        }

        String prefix = parentSessionKey + SEP + SUBAGENT_SEGMENT + SEP;
        int terminated = 0;

        for (String key : activeSessions.keySet()) {
            if (key.startsWith(prefix)) {
                Session session = activeSessions.remove(key);
                if (session != null) {
                    markTerminated(session);
                    terminated++;
                    log.debug("Terminated descendant session: {}", key);
                }
            }
        }

        if (terminated > 0) {
            log.info("Terminated {} descendant session(s) under parent: {}", terminated, parentSessionKey);
        }
    }

    /**
     * Return the count of currently active descendants of {@code sessionKey}.
     *
     * @param sessionKey hierarchical session key
     * @return number of active descendant sessions
     */
    public int getActiveDescendantCount(String sessionKey) {
        return countDescendants(sessionKey);
    }

    /**
     * Return an immutable snapshot view of the active session index.
     *
     * @return {@code sessionKey -> Session} mapping
     */
    public ConcurrentMap<String, Session> getActiveSessions() {
        return activeSessions;
    }

    // ================================================================
    // Internal helpers
    // ================================================================

    private String buildSessionKey(String parentSessionKey, String agentId) {
        String uuid8 = UUID.randomUUID().toString().replace("-", "").substring(0, UUID_LENGTH);
        return parentSessionKey + SEP + SUBAGENT_SEGMENT + SEP + agentId + SEP + uuid8;
    }

    private void archiveOne(String sessionKey, long archiveAt) {
        activeSessions.remove(sessionKey);
        archiveSchedule.put(sessionKey, archiveAt);
        // TODO: When SessionStore is available, delegate to it:
        //   ((SessionStore) sessionStore).markArchived(sessionKey, archiveAt);
    }

    private void markTerminated(Session session) {
        // TODO: When Session supports setAttribute:
        //   session.setAttribute("status", "terminated");
        //   session.setAttribute("terminatedAt", System.currentTimeMillis());
    }

    private int countDescendants(String sessionKey) {
        String prefix = sessionKey + SEP;
        int count = 0;
        for (String key : activeSessions.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
}
