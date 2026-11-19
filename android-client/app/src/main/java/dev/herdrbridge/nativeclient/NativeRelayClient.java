package dev.herdrbridge.nativeclient;

import dev.herdrbridge.protocol.E2ee;
import dev.herdrbridge.protocol.ChunkCodec;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit connection attempt. Never retries an invitation or application operation. */
public final class NativeRelayClient implements SessionRepository {
    private enum Phase { NEW, GATEWAY_HELLO, GATEWAY_READY, SERVER_HELLO, SERVER_FINISH, APPLICATION, CLOSED }
    private final String origin,relayId,authId;
    private final long authVersion,invitationExpiry;
    private byte[] authSecret,rendezvous;
    private CredentialRecord saved;
    private final CredentialStore vault;
    private final java.util.concurrent.atomic.AtomicInteger queued=new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ScheduledThreadPoolExecutor serial=new ScheduledThreadPoolExecutor(1);
    private final OkHttpClient http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build();
    private final ChunkCodec.Reassembler chunks=new ChunkCodec.Reassembler();
    private final NativeSessionProtocol protocol=new NativeSessionProtocol();
    private volatile WebSocket socket;
    private Phase phase=Phase.NEW;
    private Listener listener;
    private E2ee.Handshake handshake;
    private E2ee.Session channel;
    private SessionTarget pendingTarget;
    private ScheduledFuture<?> handshakeDeadline,chunkTimer;

    public static NativeRelayClient forInvitation(String origin,String invitationUrl,DeviceVault vault)throws Exception {
        return forInvitation(origin,invitationUrl,(CredentialStore)vault);
    }
    static NativeRelayClient forInvitation(String origin,String invitationUrl,CredentialStore vault)throws Exception {
        String canonical=InvitationValidator.origin(origin);
        InvitationValidator.invitation(invitationUrl,canonical,System.currentTimeMillis());
        Map<String,String> fields=new HashMap<>();
        for(String piece:new URI(invitationUrl).getRawFragment().split("&")) {
            int p=piece.indexOf('=');fields.put(URLDecoder.decode(piece.substring(0,p),"UTF-8"),URLDecoder.decode(piece.substring(p+1),"UTF-8"));
        }
        if(vault.hasRecord())throw new IllegalStateException("Already enrolled");
        return new NativeRelayClient(canonical,fields.get("relay_id"),fields.get("invite"),Long.parseLong(fields.get("invite_version")),
            E2ee.decode(fields.get("setup"),32),E2ee.decode(fields.get("rendezvous"),32),Long.parseLong(fields.get("invite_expires")),null,vault);
    }
    public static NativeRelayClient forSaved(CredentialRecord record,DeviceVault vault) {
        return forSaved(record,(CredentialStore)vault);
    }
    static NativeRelayClient forSaved(CredentialRecord record,CredentialStore vault) {
        byte[] secret=record.secret(),rendezvous=record.rendezvousKey();
        CredentialRecord copy;
        try{copy=new CredentialRecord(record.origin,record.credentialId,record.deviceId,record.version,"reader",secret,record.relayId,rendezvous);}
        finally{Arrays.fill(secret,(byte)0);Arrays.fill(rendezvous,(byte)0);}
        return new NativeRelayClient(copy.origin,copy.relayId,copy.credentialId,copy.version,copy.secret(),copy.rendezvousKey(),0,copy,vault);
    }
    NativeRelayClient(String origin,String relayId,String authId,long version,byte[] secret,byte[] rendezvous,long expires,CredentialRecord saved,CredentialStore vault) {
        this.origin=origin;this.relayId=relayId;this.authId=authId;this.authVersion=version;this.authSecret=secret;this.rendezvous=rendezvous;
        this.invitationExpiry=expires;this.saved=saved;this.vault=vault;
        serial.setRemoveOnCancelPolicy(true);serial.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    @Override public synchronized void start(Listener listener) {
        if(this.listener!=null||closed.get())throw new IllegalStateException("Connection is single-use");
        this.listener=Objects.requireNonNull(listener);
        enqueue(()->{
            if(invitationExpiry!=0&&invitationExpiry<=System.currentTimeMillis())throw new IllegalStateException("Invitation expired");
            phase=Phase.GATEWAY_HELLO;emitState(State.CONNECTING);
            handshakeDeadline=serial.schedule(()->fail(State.DISCONNECTED),20,TimeUnit.SECONDS);
            chunkTimer=serial.scheduleWithFixedDelay(()->{try{chunks.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));}catch(Exception e){fail(State.ERROR);}},1,1,TimeUnit.SECONDS);
            String endpoint="wss"+origin.substring(5)+"/connect";
            Request request=new Request.Builder().url(endpoint).header("Origin",origin).build();
            socket=http.newWebSocket(request,new WebSocketListener(){
                @Override public void onOpen(WebSocket ws,Response response){if(closed.get())ws.cancel();}
                @Override public void onMessage(WebSocket ws,String text){if(text.length()>8192){enqueue(()->{throw new IllegalArgumentException();});return;}enqueue(()->receiveText(text));}
                @Override public void onMessage(WebSocket ws,ByteString data){if(data.size()>ChunkCodec.MAX_CHUNK_BYTES){enqueue(()->{throw new IllegalArgumentException();});return;}enqueue(()->receiveBinary(data.toByteArray()));}
                @Override public void onFailure(WebSocket ws,Throwable ignored,Response response){enqueue(()->fail(ignored instanceof javax.net.ssl.SSLException||response!=null?State.ERROR:State.DISCONNECTED));}
                @Override public void onClosed(WebSocket ws,int code,String ignored){enqueue(()->fail(closeState(code)));}
                @Override public void onClosing(WebSocket ws,int code,String ignored){ws.close(1000,null);enqueue(()->fail(closeState(code)));}
            });
        });
    }
    private static State closeState(int code){return code==1000||code==1001||code==1006||code==1011||code==1012||code==1013?State.DISCONNECTED:State.ERROR;}
    private interface Work {void run()throws Exception;}
    private void enqueue(Work work){
        if(closed.get())return;
        if(queued.incrementAndGet()>64){queued.decrementAndGet();close();notifyState(State.ERROR);return;}
        try{serial.execute(()->{try{if(!closed.get())work.run();}catch(Exception ignored){fail(State.ERROR);}finally{queued.decrementAndGet();}});}
        catch(RejectedExecutionException ignored){queued.decrementAndGet();}
    }
    private static String str(JSONObject obj,String key)throws Exception {Object v=obj.get(key);if(!(v instanceof String))throw new IllegalArgumentException();return (String)v;}
    private static long number(JSONObject obj,String key)throws Exception {Object v=obj.get(key);if(!(v instanceof Long)&&!(v instanceof Integer))throw new IllegalArgumentException();long n=((Number)v).longValue();if(n<0||n>E2ee.MAX_SEQUENCE)throw new IllegalArgumentException();return n;}
    private void receiveText(String raw)throws Exception {
        JSONObject message=StrictJson.object(raw);
        if(phase==Phase.GATEWAY_HELLO){
            if(!"gateway_hello".equals(str(message,"type"))||number(message,"proto")!=1)throw new IllegalArgumentException();
            byte[] nonce=E2ee.decode(str(message,"nonce"),32);
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(rendezvous,"HmacSHA256"));
            mac.update("herdr-gw-connect\0".getBytes(StandardCharsets.UTF_8));mac.update(relayId.getBytes(StandardCharsets.UTF_8));
            String proof=E2ee.encode(mac.doFinal(nonce));phase=Phase.GATEWAY_READY;
            if(!socket.send(new JSONObject().put("type","connect").put("proto",1).put("relay_id",relayId).put("proof",proof).toString()))throw new IllegalStateException();
        }else if(phase==Phase.GATEWAY_READY){
            if(!"ready".equals(str(message,"type")))throw new IllegalArgumentException();
            handshake=E2ee.start(saved==null?"invitation":"credential",authId,authVersion,E2ee.encode(authSecret),"en");
            Arrays.fill(authSecret,(byte)0);authSecret=null;
            E2ee.Hello h=handshake.hello;
            JSONObject hello=new JSONObject().put("type","e2ee_client_hello").put("version",2).put("auth_kind",h.kind).put("auth_id",h.id)
                .put("auth_version",h.authVersion).put("locale",h.locale).put("nonce",h.nonce).put("public_key",h.publicKey).put("proof",h.proof);
            phase=Phase.SERVER_HELLO;sendLogical(hello.toString().getBytes(StandardCharsets.UTF_8));
        }else throw new IllegalArgumentException();
    }
    private void receiveBinary(byte[] piece)throws Exception {
        if(phase!=Phase.SERVER_HELLO&&phase!=Phase.SERVER_FINISH&&phase!=Phase.APPLICATION)throw new IllegalArgumentException();
        byte[] logical=chunks.push(piece,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));if(logical==null)return;
        if(phase==Phase.SERVER_HELLO){
            if(logical.length>8192)throw new IllegalArgumentException();
            JSONObject hello=StrictJson.object(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(logical)).toString());
            long version=number(hello,"version");if(version!=2)throw new IllegalArgumentException();
            channel=handshake.acceptServerHello(str(hello,"type"),(int)version,str(hello,"nonce"),str(hello,"public_key"),str(hello,"proof"));handshake=null;
            phase=Phase.SERVER_FINISH;sendLogical(channel.encrypt(new JSONObject().put("type","e2ee_client_finish").put("version",2).toString()));
        }else{
            JSONObject data=StrictJson.object(channel.decrypt(logical));
            if(phase==Phase.SERVER_FINISH)finish(data);else application(data);
        }
    }
    private void finish(JSONObject data)throws Exception {
        boolean enrolling=saved==null;
        if(!"e2ee_server_finish".equals(str(data,"type"))||number(data,"version")!=2||!"reader".equals(str(data,"role")))throw new IllegalArgumentException();
        String locale=str(data,"locale");if(locale.length()>35||!locale.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))throw new IllegalArgumentException();
        String deviceId=str(data,"device_id"),credentialId=str(data,"credential_id");long version=number(data,"credential_version");
        if(saved==null){
            byte[] secret=E2ee.decode(str(data,"credential_secret"),32);
            try{CredentialRecord record=new CredentialRecord(origin,credentialId,deviceId,version,"reader",secret,relayId,rendezvous);try{vault.save(record);saved=record;}catch(Exception ex){record.close();throw ex;}}finally{Arrays.fill(secret,(byte)0);}
        }else if(data.has("credential_secret")||!saved.deviceId.equals(deviceId)||!saved.credentialId.equals(credentialId)||saved.version!=version)throw new IllegalArgumentException();
        phase=Phase.APPLICATION;
        if(enrolling)emitState(State.ENROLLED);
        // Wait for authenticated protocol-3 push_config before presenting READY.
    }
    private void application(JSONObject message)throws Exception {
        String type=str(message,"type");
        switch(type){
            case "push_config": protocol.acceptConfig(message);handshakeDeadline.cancel(false);if(!closed.get())emitState(State.READY);break;
            case "inventory_status": protocol.acceptInventory(message);pendingTarget=null;emitState(State.CONNECTING);break;
            case "agents": {List<Session> rows=protocol.acceptAgents(message);if(rows!=null){emitState(State.READY);emitSessions(rows);}break;}
            case "agent_update": {List<Session> rows=protocol.acceptAgentUpdate(message);if(rows!=null){emitState(State.READY);emitSessions(rows);}break;}
            case "pane_content": {String text=protocol.acceptPaneContent(message);if(text!=null&&pendingTarget!=null){emitSnapshot(pendingTarget,text);pendingTarget=null;}break;}
            case "pane_unchanged": break;
            case "error": throw new IllegalArgumentException();
            default: break; // Other authenticated pushes are not a write API.
        }
    }
    private void sendLogical(byte[] bytes){for(byte[] part:ChunkCodec.encode(bytes))if(!socket.send(ByteString.of(part)))throw new IllegalStateException();}
    @Override public void requestSnapshot(SessionTarget target){enqueue(()->{
        if(phase!=Phase.APPLICATION||!protocol.ready())return;
        JSONObject command=protocol.readPane(target,UUID.randomUUID().toString());pendingTarget=target;sendLogical(channel.encrypt(command.toString()));
    });}
    private void emitState(State state){if(!closed.get())notifyState(state);}
    private void emitSessions(List<Session> rows){if(!closed.get())listener.onSessions(rows);}
    private void emitSnapshot(SessionTarget target,String text){if(!closed.get())listener.onSnapshot(target,text);}
    private void notifyState(State state){try{if(listener!=null)listener.onState(state,"");}catch(RuntimeException ignored){}}
    private void fail(State state){if(closed.getAndSet(true))return;try{cleanup();}finally{serial.shutdown();notifyState(state);}}
    private void cleanup(){
        phase=Phase.CLOSED;
        if(authSecret!=null)Arrays.fill(authSecret,(byte)0);
        Arrays.fill(rendezvous,(byte)0);
        if(saved!=null)saved.close();
        if(handshake!=null)handshake.close();
        if(channel!=null)channel.close();
        if(handshakeDeadline!=null)handshakeDeadline.cancel(false);
        if(chunkTimer!=null)chunkTimer.cancel(false);
        chunks.close();protocol.reset();pendingTarget=null;
        try{if(socket!=null)socket.cancel();}
        finally{http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
    }
    boolean awaitClosed(long timeoutMillis)throws InterruptedException{return serial.awaitTermination(timeoutMillis,TimeUnit.MILLISECONDS);}
    @Override public void close(){
        if(closed.getAndSet(true))return;
        try{if(socket!=null)socket.cancel();}
        finally{try{serial.execute(this::cleanup);}catch(RejectedExecutionException ignored){}finally{serial.shutdown();}}
    }
}
