package dev.herdrbridge.nativeclient;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** UI-side defense in depth; the transport independently enforces the same authority boundary. */
final class TerminalInputGate {
    private final LongSupplier clock;
    private String role="reader";
    private final Set<SessionRepository.SessionTarget> writable=new HashSet<>();
    private SessionRepository.SessionTarget selected,snapshot,requested;
    private long snapshotAt,requestAt;
    private boolean uncertain;
    private String pending;
    TerminalInputGate(LongSupplier clock){this.clock=clock;}
    void access(String role){this.role="controller".equals(role)?"controller":"reader";}
    void sessions(List<SessionRepository.Session> rows){writable.clear();for(SessionRepository.Session row:rows)if(row.manualTerminalInput)writable.add(row.target);if(!writable.contains(selected))invalidate();}
    void select(SessionRepository.SessionTarget target){selected=target;invalidate();}
    void requested(SessionRepository.SessionTarget target){invalidate();requested=target;requestAt=clock.getAsLong();}
    void snapshot(SessionRepository.SessionTarget target){if(target.equals(selected)&&target.equals(requested)&&clock.getAsLong()-requestAt>=0&&clock.getAsLong()-requestAt<3000){snapshot=target;snapshotAt=requestAt;}requested=null;}
    void invalidate(){snapshot=null;requested=null;}
    void resetConnection(){role="reader";writable.clear();selected=null;invalidate();if(pending!=null)uncertain=true;pending=null;}
    boolean fresh(SessionRepository.SessionTarget target){long age=clock.getAsLong()-snapshotAt;return "controller".equals(role)&&target!=null&&target.equals(selected)&&target.equals(snapshot)&&writable.contains(target)&&age>=0&&age<3000;}
    long remaining(SessionRepository.SessionTarget target){return fresh(target)?3000-(clock.getAsLong()-snapshotAt):0;}
    boolean canSend(SessionRepository.SessionTarget target){return pending==null&&!uncertain&&fresh(target);}
    boolean canReset(SessionRepository.SessionTarget target){return pending==null&&uncertain&&fresh(target);}
    void pending(String id){pending=id;uncertain=true;invalidate();}
    boolean result(String id,SessionRepository.MutationOutcome outcome){if(pending==null||!pending.equals(id))return false;pending=null;uncertain=outcome==SessionRepository.MutationOutcome.UNKNOWN;invalidate();return true;}
    void uncertainty(boolean value){uncertain=value;}
    void cleared(){uncertain=false;}
    boolean uncertain(){return uncertain;}
    String pendingId(){return pending;}
    static void validateText(String text){
        if(text==null||text.isEmpty()||text.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("Invalid single-line text");
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isISOControl(c)||Character.getType(c)==Character.LINE_SEPARATOR||Character.getType(c)==Character.PARAGRAPH_SEPARATOR)throw new IllegalArgumentException("Control characters are not allowed");
            if(Character.isHighSurrogate(c)){if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("Invalid text");}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Invalid text");
        }
    }
}
