package dev.herdrbridge.nativeclient;

import java.util.List;
import java.util.Objects;

/** Authenticated exact-target boundary. Callbacks may arrive off the UI thread. */
public interface SessionRepository extends AutoCloseable {
    enum State { DISCONNECTED, CONNECTING, ENROLLED, READY, REVOKED, ERROR }
    enum MutationOutcome { COMPLETED, NOT_STARTED, UNKNOWN }
    interface Listener {
        default void onAccess(String role) {}
        default void onInputUncertaintyCleared(SessionTarget target) {}
        default void onMutationResult(String id, SessionTarget target, MutationOutcome outcome) {}
        void onSessions(List<Session> sessions);
        void onSnapshot(SessionTarget target, String content);
        void onState(State state, String safeMessage);
    }
    void start(Listener listener);
    void requestSnapshot(SessionTarget target);
    default String sendTerminalText(SessionTarget target,String text) { throw new UnsupportedOperationException(); }
    default String sendEnter(SessionTarget target) { throw new UnsupportedOperationException(); }
    default void resetInputUncertainty(SessionTarget target) {}
    @Override void close();

    final class SessionTarget {
        public final String serverSessionId, paneId, terminalId, agentSessionId;
        public final long generation;
        public SessionTarget(String serverSessionId, String paneId, String terminalId, long generation, String agentSessionId) {
            this.serverSessionId = required(serverSessionId);
            this.paneId = required(paneId); this.terminalId = required(terminalId);
            if (generation < 0 || generation > 9007199254740991L) throw new IllegalArgumentException("Invalid target generation");
            this.generation = generation;
            this.agentSessionId = agentSessionId == null ? "" : agentSessionId;
            if(this.agentSessionId.length()>2048 || hasControls(this.agentSessionId)) throw new IllegalArgumentException("Invalid agent session");
        }
        private static boolean hasControls(String value) {
            for (int i=0;i<value.length();i++) if(Character.isISOControl(value.charAt(i))) return true;
            return false;
        }
        private static String required(String v) {
            if (v == null || v.isEmpty() || v.length() > 2048 || hasControls(v)) throw new IllegalArgumentException("Invalid session target");
            return v;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof SessionTarget)) return false;
            SessionTarget t = (SessionTarget) other;
            return serverSessionId.equals(t.serverSessionId) && paneId.equals(t.paneId)
                && terminalId.equals(t.terminalId) && generation == t.generation && agentSessionId.equals(t.agentSessionId);
        }
        @Override public int hashCode() { return Objects.hash(serverSessionId, paneId, terminalId, generation, agentSessionId); }
    }
    final class Session {
        public final SessionTarget target;
        public final String title, status;
        public final boolean manualTerminalInput;
        public Session(SessionTarget target, String title, String status) {
            this(target,title,status,false);
        }
        public Session(SessionTarget target,String title,String status,boolean manualTerminalInput) {
            this.manualTerminalInput=manualTerminalInput;
            this.target = Objects.requireNonNull(target);
            this.title = title == null ? "" : title;
            this.status = status == null ? "" : status;
        }
    }
}
