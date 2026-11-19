package dev.herdrbridge.nativeclient;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.content.res.AssetFileDescriptor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class QrImageDecoderTest {
    @Test public void generatedPngDecodedByRealAndroidBitmapFactory()throws Exception {
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String id=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        long now=System.currentTimeMillis();
        String invite="https://bridge.invalid/#setup="+key+"&label=Synthetic&invite=synthetic-invite-001&invite_version=1&invite_expires="+(now+60000)+
                "&gateways=wss%3A%2F%2Fbridge.invalid&relay_id="+id+"&rendezvous="+key+"&path_policy=relay-only";
        // 2304 square forces sampling (source >4MP) before bounded QR decoding.
        int side=2304;
        BitMatrix matrix=new QRCodeWriter().encode(invite,BarcodeFormat.QR_CODE,side,side);
        Bitmap bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
        int[] row=new int[side];byte[] png;
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            for(int y=0;y<side;y++){for(int x=0;x<side;x++)row[x]=matrix.get(x,y)?0xff000000:0xffffffff;bitmap.setPixels(row,0,side,0,y,side,1);}
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));png=out.toByteArray();
        }finally{bitmap.recycle();Arrays.fill(row,0);}
        try{
            String decoded=QrImageDecoder.decodeBytes(png,new CancellationSignal());
            assertEquals(invite,InvitationValidator.invitation(decoded,"https://bridge.invalid",now));
            assertThrows(IllegalArgumentException.class,()->InvitationValidator.invitation(decoded,"https://other.invalid",now));
        }finally{Arrays.fill(png,(byte)0);}
    }
    @Test public void malformedOversizedAndCancelledImagesFailClosed()throws Exception {
        assertThrows(IOException.class,()->QrImageDecoder.decodeBytes(new byte[]{0,1,2},new CancellationSignal()));
        assertThrows(IOException.class,()->QrImageDecoder.decodeBytes(new byte[QrImageDecoder.MAX_BYTES+1],new CancellationSignal()));
        CancellationSignal signal=new CancellationSignal();signal.cancel();
        assertThrows(IOException.class,()->QrImageDecoder.decodeBytes(new byte[]{0,1,2},signal));
        assertThrows(IOException.class,()->QrImageDecoder.decode(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),Uri.parse("https://bridge.invalid/image.png"),new CancellationSignal()));
    }
    @Test public void cancellationClosesBlockedPipeReadWithoutRetainingDescriptor()throws Exception {
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        CancellationSignal signal=new CancellationSignal();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicBoolean returnedContent=new AtomicBoolean();
        try(AssetFileDescriptor asset=new AssetFileDescriptor(pipe[0],0,AssetFileDescriptor.UNKNOWN_LENGTH);
            ParcelFileDescriptor writer=pipe[1]){
            Future<?> task=worker.submit(()->{
                entered.countDown();
                try{QrImageDecoder.readEncoded(asset,signal);returnedContent.set(true);}
                catch(Exception expected){/* cancelled/closed descriptor is required */}
                finally{finished.countDown();}
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            // Writer remains open and silent: the reader cannot finish through EOF.
            Thread.sleep(100);
            signal.cancel();
            task.cancel(true);
            assertTrue("Cancelled pipe reader remained blocked",finished.await(3,TimeUnit.SECONDS));
            assertFalse(returnedContent.get());
        }finally{
            signal.cancel();worker.shutdownNow();
            pipe[0].close();pipe[1].close();
            assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));
        }
    }

}
