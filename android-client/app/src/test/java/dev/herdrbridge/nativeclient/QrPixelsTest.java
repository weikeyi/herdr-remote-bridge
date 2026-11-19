package dev.herdrbridge.nativeclient;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public final class QrPixelsTest {
    static String invitation(){
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String id=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        return "https://bridge.invalid/#setup="+key+"&label=Synthetic&invite=synthetic-invite-001&invite_version=1&invite_expires=1700000600000"+
                "&gateways=wss%3A%2F%2Fbridge.invalid&relay_id="+id+"&rendezvous="+key+"&path_policy=relay-only";
    }
    static int[] qr(String text,int side)throws Exception{
        BitMatrix matrix=new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,side,side,
                Collections.singletonMap(EncodeHintType.CHARACTER_SET,"UTF-8"));
        int[] pixels=new int[side*side];
        for(int y=0;y<side;y++)for(int x=0;x<side;x++)pixels[y*side+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        return pixels;
    }
    @Test public void scopedInvitationRoundTripsAndIsValidated()throws Exception {
        String text=QrPixels.decode(qr(invitation(),640),640,640);
        assertEquals(invitation(),InvitationValidator.invitation(text,"https://bridge.invalid",1700000000000L));
    }
    @Test public void wrongOriginAndArbitraryQrNeverBecomeInvitations()throws Exception {
        String text=QrPixels.decode(qr(invitation(),640),640,640);
        assertThrows(IllegalArgumentException.class,()->InvitationValidator.invitation(text,"https://other.invalid",1700000000000L));
        String arbitrary=QrPixels.decode(qr("not an invitation",320),320,320);
        assertThrows(IllegalArgumentException.class,()->InvitationValidator.invitation(arbitrary,"https://bridge.invalid",1700000000000L));
    }
    @Test public void noCodeAndInvalidDimensionsReject() {
        int[] white=new int[320*320];Arrays.fill(white,0xffffffff);
        assertThrows(IOException.class,()->QrPixels.decode(white,320,320));
        assertThrows(IOException.class,()->QrPixels.decode(new int[1],0,1));
        assertThrows(IOException.class,()->QrPixels.decode(new int[1],Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertThrows(IOException.class,()->QrPixels.decode(new int[1],320,320));
    }
    @Test public void twoPhysicalCodesRejectEvenWhenSameText()throws Exception {
        for(String second:new String[]{"second value","first value"}){
            int side=320,width=720,height=360;int[] canvas=new int[width*height];Arrays.fill(canvas,0xffffffff);
            int[] left=qr("first value",side),right=qr(second,side);
            for(int y=0;y<side;y++){
                System.arraycopy(left,y*side,canvas,(y+20)*width+20,side);
                System.arraycopy(right,y*side,canvas,(y+20)*width+380,side);
            }
            assertThrows(IOException.class,()->QrPixels.decode(canvas,width,height));
        }
    }
    @Test public void invertedQrDecodesOffline()throws Exception {
        int[] pixels=qr("offline value",320);
        for(int i=0;i<pixels.length;i++)pixels[i]^=0x00ffffff;
        assertEquals("offline value",QrPixels.decode(pixels,320,320));
    }
    @Test public void interruptedImportCannotReturnContent()throws Exception {
        int[] pixels=qr("cancelled",320);
        Thread.currentThread().interrupt();
        try{assertThrows(IOException.class,()->QrPixels.decode(pixels,320,320));}
        finally{Thread.interrupted();}
    }
}
