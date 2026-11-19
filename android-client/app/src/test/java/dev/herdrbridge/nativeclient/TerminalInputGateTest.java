package dev.herdrbridge.nativeclient;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class TerminalInputGateTest {
 private final long[] now={100};
 private final SessionRepository.SessionTarget a=new SessionRepository.SessionTarget("primary","a","terminal",1,"");
 private TerminalInputGate gate(){TerminalInputGate g=new TerminalInputGate(()->now[0]);g.access("controller");g.sessions(Collections.singletonList(new SessionRepository.Session(a,"","",true)));g.select(a);g.requested(a);g.snapshot(a);return g;}
 @Test public void readerAndMissingCapabilityNeverEnable(){TerminalInputGate g=gate();assertTrue(g.canSend(a));g.access("reader");assertFalse(g.canSend(a));g.access("controller");g.sessions(Collections.singletonList(new SessionRepository.Session(a,"","",false)));assertFalse(g.canSend(a));}
 @Test public void freshnessUsesRequestTimeAndExactGeneration(){TerminalInputGate g=gate();now[0]+=2999;assertTrue(g.canSend(a));now[0]++;assertFalse(g.canSend(a));g.requested(a);now[0]+=3000;g.snapshot(a);assertFalse(g.canSend(a));g.select(new SessionRepository.SessionTarget("primary","a","terminal",2,""));g.snapshot(a);assertFalse(g.canSend(a));}
 @Test public void onePendingAndUnknownRequireFreshExplicitInspection(){TerminalInputGate g=gate();g.pending("id");assertFalse(g.canSend(a));assertFalse(g.result("old",SessionRepository.MutationOutcome.COMPLETED));assertTrue(g.result("id",SessionRepository.MutationOutcome.UNKNOWN));assertFalse(g.canReset(a));g.requested(a);g.snapshot(a);assertTrue(g.canReset(a));assertFalse(g.canSend(a));g.cleared();assertTrue(g.canSend(a));}
 @Test public void connectionAndTargetChangesDropFreshnessNotUnknown(){TerminalInputGate g=gate();g.pending("id");g.resetConnection();assertTrue(g.uncertain());assertFalse(g.canSend(a));g.access("controller");g.sessions(Collections.singletonList(new SessionRepository.Session(a,"","",true)));g.select(a);g.requested(a);g.snapshot(a);assertTrue(g.canReset(a));g.select(a);assertFalse(g.canReset(a));}
 @Test public void singleLineUtf8ValidationRejectsControlsAndOversize(){TerminalInputGate.validateText("你好 world");TerminalInputGate.validateText(new String(new char[1365]).replace('\0','中'));for(String bad:new String[]{"","a\nb","a\rb","\t","\u001b","a\u2028b","\ud800",new String(new char[1366]).replace('\0','中')}){try{TerminalInputGate.validateText(bad);fail("accepted invalid text");}catch(IllegalArgumentException expected){}}}
}
