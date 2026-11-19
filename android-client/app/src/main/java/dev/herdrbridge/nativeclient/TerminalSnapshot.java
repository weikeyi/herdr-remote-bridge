package dev.herdrbridge.nativeclient;

/** Lossy, bounded plain-text view of ONE full pane_content snapshot. Not a VT screen. */
public final class TerminalSnapshot {
    public static final int MAX_INPUT_CHARS=262144, MAX_VISIBLE_CHARS=65536;
    private TerminalSnapshot() {}
    public static String render(String input) {
        if(input==null)return "";
        // Never cut into a control string and accidentally display its secret payload.
        if(input.length()>MAX_INPUT_CHARS)return "[Snapshot too large; display refused]";
        StringBuilder out=new StringBuilder();
        int state=0; // normal, ESC, CSI, OSC/string, string ESC
        for(int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            if(state==1) {
                if(c==27)state=1;
                else if(c=='[')state=2;
                else if(c==']'||c=='P'||c=='_'||c=='^'||c=='X')state=3;
                else if(c>=' '&&c<='/')state=1;
                else state=0;
                continue;
            }
            if(state==2) {if(c==27)state=1;else if(c>='@'&&c<='~')state=0;continue;}
            if(state==3) {if(c==7||c==0x9c)state=0;else if(c==27)state=4;continue;}
            if(state==4) {if(c=='\\')state=0;else if(c!=27)state=3;continue;}
            if(c==27){state=1;continue;}
            if(c==0x9b){state=2;continue;}
            if(c==0x90||c==0x9d||c==0x98||c==0x9e||c==0x9f){state=3;continue;}
            // Suppress controls, bidi formatting, invisible format chars and unpaired surrogates.
            if(c=='\n'||c=='\t'){out.append(c);continue;}
            if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)continue;
            if(Character.isHighSurrogate(c)) {
                if(i+1<input.length()&&Character.isLowSurrogate(input.charAt(i+1))) {out.append(c).append(input.charAt(++i));}
            } else if(!Character.isLowSurrogate(c))out.append(c);
        }
        if(out.length()<=MAX_VISIBLE_CHARS)return out.toString();
        int start=out.length()-MAX_VISIBLE_CHARS;
        if(Character.isLowSurrogate(out.charAt(start)))start++;
        return "[Earlier snapshot text omitted]\n"+out.substring(start);
    }
}
