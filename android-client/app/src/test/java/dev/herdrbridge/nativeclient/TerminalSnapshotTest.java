package dev.herdrbridge.nativeclient;
import org.junit.Test;
import static org.junit.Assert.*;
public class TerminalSnapshotTest {
 @Test public void removesAnsiAndOscSecrets() {
  assertEquals("redsafe",TerminalSnapshot.render("\u001b[31mred\u001b[0m\u001b]52;c;secret\u0007safe"));
  assertEquals("ok",TerminalSnapshot.render("\u001bPprivate\u001b\\ok"));
  assertEquals("",TerminalSnapshot.render("\u001b]unterminated secret"));
  assertEquals("safe",TerminalSnapshot.render("\u001b[\u001b]52;c;secret\u0007safe"));
  assertEquals("safe",TerminalSnapshot.render("\u001b\u001b]52;c;secret\u0007safe"));
  assertEquals("left\nright",TerminalSnapshot.render("left\u202e\u0000\nright"));
 }
 @Test public void boundsAndReplacesSnapshots() {
  assertTrue(TerminalSnapshot.render("x".repeat(100000)).length()<66000);
  assertTrue(TerminalSnapshot.render("x".repeat(300000)).contains("refused"));
  assertEquals("second",TerminalSnapshot.render("second"));
  assertEquals("😀",TerminalSnapshot.render("😀"));
 }
}
