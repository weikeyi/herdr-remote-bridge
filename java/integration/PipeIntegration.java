package dev.herdrbridge.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Synthetic pipe integration only. This narrow JSON extraction is NOT client production parsing. */
public final class PipeIntegration {
    static String field(String json,String key) {
        Matcher m=Pattern.compile("\""+key+"\":\"([A-Za-z0-9_-]+)\"").matcher(json);
        if(!m.find())throw new AssertionError("Missing synthetic server field "+key);
        return m.group(1);
    }
    static String read(BufferedReader in)throws Exception{
        String value=in.readLine();if(value==null)throw new AssertionError("Server closed early");return value;
    }
    static byte[] frame(BufferedReader in)throws Exception{return Base64.getUrlDecoder().decode(read(in));}
    static void write(BufferedWriter out,byte[] frame)throws Exception{
        out.write(E2ee.encode(frame));out.newLine();out.flush();
    }
    static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception {
        Process server=new ProcessBuilder(args[0]).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(server.getOutputStream(),StandardCharsets.UTF_8))) {
            String secret=read(in),oldNonce="",oldPublic="";
            for(int round=0;round<2;round++) {
                E2ee.Handshake hs=E2ee.start(round==0?"invitation":"credential",
                        round==0?"synthetic-invite":"synthetic-credential",1,secret,"en");
                E2ee.Hello h=hs.hello;
                require(!oldNonce.equals(h.nonce)&&!oldPublic.equals(h.publicKey),"Ephemeral reuse");
                oldNonce=h.nonce;oldPublic=h.publicKey;
                String hello="{\"type\":\"e2ee_client_hello\",\"version\":2,\"auth_kind\":\""+h.kind+
                        "\",\"auth_id\":\""+h.id+"\",\"auth_version\":1,\"locale\":\"en\",\"nonce\":\""+h.nonce+
                        "\",\"public_key\":\""+h.publicKey+"\",\"proof\":\""+h.proof+"\"}";
                write(out,E2ee.utf8(hello));
                String reply=E2ee.strictDecode(frame(in));
                require(reply.contains("\"type\":\"e2ee_server_hello\"")&&reply.contains("\"version\":2"),"Hello identity");
                try(E2ee.Session session=hs.acceptServerHello("e2ee_server_hello",2,
                        field(reply,"nonce"),field(reply,"public_key"),field(reply,"proof"))) {
                    byte[] finish=session.encrypt("{\"type\":\"e2ee_client_finish\",\"version\":2}");
                    require(ByteBuffer.wrap(finish,2,8).getLong()==0,"Finish starts at zero");
                    write(out,finish);
                    String enrolled=session.decrypt(frame(in));
                    require(enrolled.contains("\"type\":\"e2ee_server_finish\"")&&enrolled.contains("\"version\":2")&&
                            enrolled.contains("\"credential_version\":1"),"Finish schema");
                    require(field(enrolled,"role").equals("reader")&&field(enrolled,"device_id").equals("synthetic-device")&&
                            field(enrolled,"credential_id").equals("synthetic-credential"),"Finish identity");
                    if(round==0){secret=field(enrolled,"credential_secret");E2ee.decode(secret,32);}
                    else require(!enrolled.contains("credential_secret"),"Reconnect replaced credential");
                    System.out.println("round "+round+": authenticated server, encrypted finish verified, role=reader");
                    byte[] refresh=session.encrypt("{\"type\":\"refresh_agents\",\"protocol\":3}");
                    require(ByteBuffer.wrap(refresh,2,8).getLong()==1,"Application starts at one");
                    write(out,refresh);
                    String agents=session.decrypt(frame(in));
                    require(agents.contains("\"type\":\"agents\"")&&agents.contains("synthetic-pane"),"Agents payload");
                    write(out,session.encrypt("{\"type\":\"read_pane\",\"protocol\":3,\"pane_id\":\"synthetic-pane\"}"));
                    String pane=session.decrypt(frame(in));
                    require(pane.contains("\"type\":\"pane_content\"")&&pane.contains("synthetic terminal 中文"),"Pane payload");
                    System.out.println("round "+round+": encrypted agents and UTF-8 terminal content verified");
                }
            }
            out.close();
            require(server.waitFor(5,TimeUnit.SECONDS)&&server.exitValue()==0,"Go harness failed");
            System.out.println("PASS Java ↔ original Go randomized E2EE invitation/enrollment/reconnect trace");
        } finally {server.destroyForcibly();}
    }
}
