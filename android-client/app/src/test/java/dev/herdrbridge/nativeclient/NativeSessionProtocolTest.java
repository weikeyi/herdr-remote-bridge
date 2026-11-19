package dev.herdrbridge.nativeclient;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.List;
import dev.herdrbridge.nativeclient.SessionRepository.Session;
import dev.herdrbridge.nativeclient.SessionRepository.SessionTarget;

public class NativeSessionProtocolTest {
    private JSONObject target() throws Exception {
        return new JSONObject().put("server_session_id","primary").put("pane_id","pane-1")
            .put("terminal_id","term-1").put("generation",0).put("agent_session_id","session-1");
    }
    private JSONObject config(String state) throws Exception {
        return new JSONObject().put("type","push_config").put("protocol",3)
            .put("inventory",new JSONObject().put("state",state));
    }
    private JSONObject agents(JSONObject... rows) throws Exception {
        JSONArray list=new JSONArray(); for(JSONObject row:rows)list.put(row);
        return new JSONObject().put("type","agents").put("agents",list);
    }
    private NativeSessionProtocol ready() throws Exception {
        NativeSessionProtocol parser=new NativeSessionProtocol();parser.acceptConfig(config("ready"));return parser;
    }
    private SessionTarget initialize(NativeSessionProtocol parser) throws Exception {
        return parser.acceptAgents(agents(target())).get(0).target;
    }
    private JSONObject response(String id) throws Exception {
        return new JSONObject().put("type","pane_content").put("request_id",id).put("pane_id","pane-1")
            .put("target",target()).put("format","ansi").put("content","hello\n");
    }
    @Test public void exactReadRequestAndSingleUseCorrelation() throws Exception {
        NativeSessionProtocol p=ready();SessionTarget t=initialize(p);
        JSONObject q=p.readPane(t,"request-0001");
        assertEquals("read_pane",q.getString("type"));assertEquals(3,q.getInt("protocol"));
        assertEquals("primary",q.getString("server_session_id"));
        assertEquals("",q.getString("content_fingerprint"));assertEquals(80,q.getInt("lines"));
        assertEquals(t.terminalId,q.getJSONObject("target").getString("terminal_id"));
        assertEquals("hello\n",p.acceptPaneContent(response("request-0001")));
        assertNull(p.acceptPaneContent(response("request-0001")));
    }
    @Test public void rejectsWrongProtocolAndCoercions() throws Exception {
        for(Object protocol:new Object[]{2,4,"3",3.0}) {
            NativeSessionProtocol p=new NativeSessionProtocol();JSONObject c=config("ready").put("protocol",protocol);
            assertThrows(Exception.class,()->p.acceptConfig(c));assertFalse(p.ready());
        }
        for(Object generation:new Object[]{-1,1.5,"1",9007199254740992L,JSONObject.NULL}) {
            NativeSessionProtocol p=ready();JSONObject row=target().put("generation",generation);
            assertThrows(Exception.class,()->p.acceptAgents(agents(row)));
        }
    }
    @Test public void exactTargetReplacementAndCorrelation() throws Exception {
        NativeSessionProtocol p=ready();SessionTarget t=initialize(p);p.readPane(t,"request-0001");
        assertNull(p.acceptPaneContent(response("request-0002")));
        JSONObject changed=response("request-0001");changed.getJSONObject("target").put("generation",1);
        assertNull(p.acceptPaneContent(changed));
        p.acceptAgentUpdate(target().put("type","agent_update").put("generation",1));
        assertNull(p.acceptPaneContent(response("request-0001")));
        assertThrows(Exception.class,()->p.readPane(t,"request-0002"));
    }
    @Test public void allTargetDimensionsAndSupersededRequestAreRejected() throws Exception {
        NativeSessionProtocol p=ready();SessionTarget t=initialize(p);p.readPane(t,"request-0001");
        for(String field:new String[]{"server_session_id","pane_id","terminal_id","agent_session_id"}) {
            JSONObject changed=response("request-0001");changed.getJSONObject("target").put(field,"different");
            assertNull(p.acceptPaneContent(changed));
        }
        JSONObject missing=response("request-0001");missing.remove("target");
        assertNull(p.acceptPaneContent(missing));
        p.readPane(t,"request-0002");assertNull(p.acceptPaneContent(response("request-0001")));
        assertEquals("hello\n",p.acceptPaneContent(response("request-0002")));
    }
    @Test public void inventoryRecoveryNeedsFreshSnapshot() throws Exception {
        NativeSessionProtocol p=ready();SessionTarget t=initialize(p);
        p.acceptInventory(new JSONObject().put("type","inventory_status").put("state","error"));
        p.acceptInventory(new JSONObject().put("type","inventory_status").put("state","ready"));
        assertThrows(Exception.class,()->p.readPane(t,"request-0001"));
        initialize(p);assertEquals("read_pane",p.readPane(t,"request-0002").getString("type"));
    }
    @Test public void rejectsDuplicatesMissingIdentityAndUnsafeStrings() throws Exception {
        NativeSessionProtocol p=ready();
        assertThrows(Exception.class,()->p.acceptAgents(agents(target(),target())));
        JSONObject missing=target();missing.remove("server_session_id");
        assertThrows(Exception.class,()->p.acceptAgents(agents(missing)));
        assertThrows(Exception.class,()->p.acceptAgents(agents(target().put("terminal_id","evil\nterminal"))));
        assertThrows(Exception.class,()->p.acceptAgents(agents(target().put("agent_session_id",false))));
        JSONObject optional=target();optional.remove("agent_session_id");
        assertEquals("",p.acceptAgents(agents(optional)).get(0).target.agentSessionId);
    }
    @Test public void newConfigAndResetDiscardPending() throws Exception {
        NativeSessionProtocol p=ready();SessionTarget t=initialize(p);p.readPane(t,"request-0001");
        p.acceptConfig(config("ready"));assertNull(p.acceptPaneContent(response("request-0001")));
        assertThrows(Exception.class,()->p.readPane(t,"request-0002"));
        p.reset();assertFalse(p.ready());assertThrows(Exception.class,()->p.acceptAgents(agents(target())));
    }
    @Test public void inventoryAndErrorNeverBecomeSuccessfulSnapshot() throws Exception {
        NativeSessionProtocol p=new NativeSessionProtocol();p.acceptConfig(config("starting"));
        assertNull(p.acceptAgents(agents(target())));
        p.acceptInventory(new JSONObject().put("type","inventory_status").put("state","ready"));
        SessionTarget t=initialize(p);p.readPane(t,"request-0001");
        assertThrows(Exception.class,()->p.acceptPaneContent(response("request-0001").put("error","private server error")));
        assertNull(p.acceptPaneContent(response("request-0001")));
        p.readPane(t,"request-0002");
        p.acceptInventory(new JSONObject().put("type","inventory_status").put("state","error"));
        assertNull(p.acceptPaneContent(response("request-0002")));
        assertThrows(Exception.class,()->p.readPane(t,"request-0003"));
    }
    @Test public void oversizedContentAndWrongTypesFail() throws Exception {
        NativeSessionProtocol p=ready();SessionTarget t=initialize(p);p.readPane(t,"request-0001");
        assertThrows(Exception.class,()->p.acceptPaneContent(response("request-0001").put("content",42)));
        assertThrows(Exception.class,()->p.acceptPaneContent(response("request-0001").put("content",new String(new char[262145]))));
    }
    @Test public void controllerTextBoundsAndExactFreshTarget() throws Exception {
        assertTrue(NativeSessionProtocol.validInputText("hello 世界"));
        assertTrue(NativeSessionProtocol.validInputText("a".repeat(4096)));
        for(String bad:new String[]{"","a".repeat(4097),"中".repeat(1366),"a\n","a\r","\t","\u001b","\u0085","\u2028","\u2029","\ud800","\udc00"})assertFalse(NativeSessionProtocol.validInputText(bad));
        NativeSessionProtocol p=new NativeSessionProtocol();p.acceptConfig(config("ready").put("capabilities",new JSONArray().put("manual_terminal_input")));
        SessionTarget t=initialize(p);assertFalse(p.inputReady(t));p.readPane(t,"request-0001");p.acceptPaneContent(response("request-0001"));assertTrue(p.inputReady(t));
        JSONObject text=p.input(t,"mutation-0001","echo safe",false);assertEquals("send_terminal_text",text.getString("type"));assertFalse(text.has("keys"));assertEquals("echo safe",text.getString("text"));assertFalse(p.inputReady(t));
        assertThrows(Exception.class,()->p.input(t,"mutation-0002",null,true));
        p.readPane(t,"request-0002");p.acceptPaneContent(response("request-0002"));JSONObject enter=p.input(t,"mutation-0003",null,true);assertEquals("Enter",enter.getJSONArray("keys").getString(0));assertFalse(enter.has("text"));
        p.readPane(t,"request-0003");p.acceptPaneContent(response("request-0003"));p.acceptAgentUpdate(target().put("type","agent_update").put("generation",1));assertFalse(p.inputReady(t));
    }
    @Test public void missingCapabilityAndInventoryLossNeverEnableInput() throws Exception {
        NativeSessionProtocol p=ready();SessionTarget t=initialize(p);p.readPane(t,"request-0001");p.acceptPaneContent(response("request-0001"));assertFalse(p.inputReady(t));
        p.acceptConfig(config("ready").put("capabilities",new JSONArray().put("manual_terminal_input")));t=initialize(p);p.readPane(t,"request-0002");p.acceptPaneContent(response("request-0002"));assertTrue(p.inputReady(t));
        p.acceptInventory(new JSONObject().put("type","inventory_status").put("state","loading"));p.acceptInventory(new JSONObject().put("type","inventory_status").put("state","ready"));initialize(p);assertFalse(p.inputReady(t));
    }
    @Test public void delayedSnapshotAndNonPrimaryNeverEnableInput()throws Exception {
        NativeSessionProtocol p=new NativeSessionProtocol();p.acceptConfig(config("ready").put("capabilities",new JSONArray().put("manual_terminal_input")));
        SessionTarget t=initialize(p);p.readPane(t,"request-0001");
        java.lang.reflect.Field started=NativeSessionProtocol.class.getDeclaredField("readStartedAt");started.setAccessible(true);started.setLong(p,System.nanoTime()-4_000_000_000L);
        p.acceptPaneContent(response("request-0001"));assertFalse(p.inputReady(t));
        JSONObject other=target().put("server_session_id","other");Session row=p.acceptAgents(agents(other)).get(0);assertFalse(row.manualTerminalInput);assertFalse(p.inputReady(row.target));
    }
}
