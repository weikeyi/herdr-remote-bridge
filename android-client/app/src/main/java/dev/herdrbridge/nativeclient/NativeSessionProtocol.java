package dev.herdrbridge.nativeclient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.herdrbridge.nativeclient.SessionRepository.Session;
import dev.herdrbridge.nativeclient.SessionRepository.SessionTarget;

/** Application v3 only. All methods run on the transport's serialized executor. */
public final class NativeSessionProtocol {
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private boolean configured, inventoryReady, authoritative;
    private String pendingId;
    private SessionTarget pendingTarget;
    private static JSONException invalid() { return new JSONException("Invalid native session protocol message"); }
    public boolean ready() { return configured; }
    public void reset() { configured = false; inventoryReady = false; authoritative = false; sessions.clear(); clearPending(); }
    private void clearPending() { pendingId = null; pendingTarget = null; }
    public void acceptConfig(JSONObject message) throws JSONException {
        reset();
        if (!"push_config".equals(string(message, "type", 32)) || integer(message, "protocol") != 3) throw invalid();
        JSONObject inventory = message.optJSONObject("inventory");
        inventoryReady = inventory != null && "ready".equals(inventory.opt("state"));
        configured = true;
    }
    /** Status transitions do not invent an empty authoritative agent snapshot. */
    public void acceptInventory(JSONObject message) throws JSONException {
        if (!configured || !"inventory_status".equals(string(message, "type", 32))) throw invalid();
        inventoryReady = "ready".equals(message.opt("state"));
        if (!inventoryReady) { authoritative = false; clearPending(); }
    }
    public List<Session> acceptAgents(JSONObject message) throws JSONException {
        if (!configured || !"agents".equals(string(message, "type", 32))) throw invalid();
        if (!inventoryReady) return null;
        JSONArray list = message.optJSONArray("agents");
        if (list == null || list.length() > 4096) throw invalid();
        Map<String, Session> next = new LinkedHashMap<>();
        for (int i = 0; i < list.length(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof JSONObject)) throw invalid();
            Session session = parseSession((JSONObject)entry);
            if (next.put(key(session.target), session) != null) throw invalid();
        }
        sessions.clear(); sessions.putAll(next); authoritative = true;
        if (pendingTarget != null && !current(pendingTarget)) clearPending();
        return snapshot();
    }
    public List<Session> acceptAgentUpdate(JSONObject message) throws JSONException {
        if (!configured || !"agent_update".equals(string(message, "type", 32))) throw invalid();
        if (!inventoryReady || !authoritative) return null;
        Session session = parseSession(message);
        if (!sessions.containsKey(key(session.target)) && sessions.size() >= 4096) throw invalid();
        sessions.put(key(session.target), session);
        if (pendingTarget != null && !current(pendingTarget)) clearPending();
        return snapshot();
    }
    private List<Session> snapshot() { return Collections.unmodifiableList(new ArrayList<>(sessions.values())); }
    private static String key(SessionTarget target) { return target.serverSessionId + "\u0000" + target.paneId; }
    private boolean current(SessionTarget target) {
        Session session = sessions.get(key(target));
        return authoritative && session != null && session.target.equals(target);
    }
    private static Session parseSession(JSONObject value) throws JSONException {
        SessionTarget target = parseTarget(value);
        return new Session(target, optionalString(value, "name", 512), optionalString(value, "status", 64));
    }
    private static SessionTarget parseTarget(JSONObject value) throws JSONException {
        String server = identifier(value, "server_session_id");
        String pane = identifier(value, "pane_id");
        String terminal = identifier(value, "terminal_id");
        long generation = integer(value, "generation");
        String agent = optionalString(value, "agent_session_id", 2048);
        if (generation < 0 || generation > 9007199254740991L || controls(agent)) throw invalid();
        return new SessionTarget(server, pane, terminal, generation, agent);
    }
    public JSONObject readPane(SessionTarget target, String requestId) throws JSONException {
        if (!configured || !inventoryReady || target == null || !current(target)
                || requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,128}")) throw invalid();
        JSONObject request = new JSONObject().put("type", "read_pane").put("protocol", 3)
            .put("request_id", requestId).put("server_session_id", target.serverSessionId)
            .put("pane_id", target.paneId).put("target", targetJson(target))
            .put("lines", 80).put("format", "ansi").put("content_fingerprint", "");
        pendingId = requestId; pendingTarget = target;
        return request;
    }
    public String acceptPaneContent(JSONObject message) throws JSONException {
        if (!configured || !"pane_content".equals(string(message, "type", 32))) throw invalid();
        if (!inventoryReady || pendingId == null || !pendingId.equals(message.opt("request_id"))) return null;
        JSONObject rawTarget = message.optJSONObject("target");
        if (rawTarget == null) return null;
        SessionTarget target = parseTarget(rawTarget);
        if (!target.equals(pendingTarget) || !current(target) || !target.paneId.equals(message.opt("pane_id"))) return null;
        if (message.has("error") && !"".equals(message.opt("error"))) { clearPending(); throw invalid(); }
        String format = string(message, "format", 16);
        if (!format.equals("ansi") && !format.equals("text") && !format.equals("plain")) throw invalid();
        String content = string(message, "content", TerminalSnapshot.MAX_INPUT_CHARS);
        clearPending();
        return content;
    }
    private static JSONObject targetJson(SessionTarget target) throws JSONException {
        return new JSONObject().put("server_session_id", target.serverSessionId).put("pane_id", target.paneId)
            .put("terminal_id", target.terminalId).put("generation", target.generation).put("agent_session_id", target.agentSessionId);
    }
    private static String identifier(JSONObject value, String key) throws JSONException {
        String result = string(value, key, 160);
        if (!result.matches("[A-Za-z0-9._:@%+-]{1,160}")) throw invalid();
        return result;
    }
    private static String optionalString(JSONObject value, String key, int limit) throws JSONException {
        return value.has(key) ? string(value, key, limit) : "";
    }
    private static String string(JSONObject value, String key, int limit) throws JSONException {
        Object field = value.opt(key);
        if (!(field instanceof String) || ((String)field).length() > limit) throw invalid();
        return (String)field;
    }
    private static long integer(JSONObject value, String key) throws JSONException {
        Object field = value.opt(key);
        if (!(field instanceof Integer) && !(field instanceof Long)) throw invalid();
        return ((Number)field).longValue();
    }
    private static boolean controls(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
}
