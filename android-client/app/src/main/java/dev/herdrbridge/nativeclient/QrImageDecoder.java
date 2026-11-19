package dev.herdrbridge.nativeclient;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** User-picked local content only; no network, credentials, storage or intents. */
public final class QrImageDecoder {
    public static final int MAX_BYTES=8*1024*1024;
    private static final long MAX_SOURCE_PIXELS=32L*1024*1024;
    private QrImageDecoder(){}
    public static String decode(Context context,Uri uri)throws IOException {
        return decode(context.getContentResolver(),uri,new CancellationSignal());
    }
    public static String decode(Context context,Uri uri,CancellationSignal cancellation)throws IOException {
        return decode(context.getContentResolver(),uri,cancellation);
    }
    public static String decode(ContentResolver resolver,Uri uri,CancellationSignal cancellation)throws IOException {
        if(resolver==null||uri==null||!"content".equals(uri.getScheme()))throw new IOException("Choose a local image");
        CancellationSignal signal=cancellation==null?new CancellationSignal():cancellation;
        byte[] encoded=null;
        try{
            cancelled(signal);
            try(AssetFileDescriptor asset=resolver.openAssetFileDescriptor(uri,"r",signal)){
                if(asset==null||asset.getLength()>MAX_BYTES)throw new IOException("Image is unavailable or too large");
                encoded=readEncoded(asset,signal);
            }
            return decodeBytes(encoded,signal);
        }catch(OperationCanceledException ex){throw new IOException("QR import cancelled");}
        catch(SecurityException ex){throw new IOException("Selected image permission is unavailable");}
        catch(IllegalArgumentException ex){throw new IOException("Selected image is not supported");}
        catch(IOException ex){throw new IOException("Unable to decode selected QR image");}
        finally{if(encoded!=null)Arrays.fill(encoded,(byte)0);}
    }
    // Shared production read path, exercised with a pipe-backed descriptor on device.
    static byte[] readEncoded(AssetFileDescriptor asset,CancellationSignal signal)throws IOException {
        AtomicReference<InputStream> ownedInput=new AtomicReference<>();
        // Cancellation closes our descriptor as well as interrupting the worker:
        // provider-backed reads must not retain the selected image after dismissal.
        signal.setOnCancelListener(()->{closeQuietly(ownedInput.get());closeQuietly(asset);});
        try{
            cancelled(signal);
            try(InputStream input=asset.createInputStream();WipingBuffer out=new WipingBuffer()){
                ownedInput.set(input);
                byte[] block=new byte[8192];
                try{
                    for(;;){
                        cancelled(signal);
                        int count=input.read(block);
                        if(count==-1)break;
                        if(count==0)throw new IOException("Image provider made no progress");
                        cancelled(signal);
                        if(count>MAX_BYTES-out.size())throw new IOException("Image is too large");
                        out.write(block,0,count);
                    }
                    cancelled(signal);
                    return out.toByteArray();
                }finally{Arrays.fill(block,(byte)0);ownedInput.set(null);}
            }
        }finally{signal.setOnCancelListener(null);}
    }
    // Package-only device test seam; production reads through the scoped content URI.
    static String decodeBytes(byte[] encoded,CancellationSignal signal)throws IOException {
        if(encoded==null||encoded.length==0||encoded.length>MAX_BYTES)throw new IOException("Image is empty or too large");
        Bitmap bitmap=null;int[] pixels=null;
        try{
            cancelled(signal);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(encoded,0,encoded.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16384||bounds.outHeight>16384||
                    (long)bounds.outWidth*bounds.outHeight>MAX_SOURCE_PIXELS||
                    !("image/png".equals(bounds.outMimeType)||"image/jpeg".equals(bounds.outMimeType)||"image/webp".equals(bounds.outMimeType)))
                throw new IOException("Image format or dimensions are not supported");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            options.inSampleSize=1;
            while(((long)bounds.outWidth+options.inSampleSize-1)/options.inSampleSize*
                    (((long)bounds.outHeight+options.inSampleSize-1)/options.inSampleSize)>QrPixels.MAX_PIXELS)
                options.inSampleSize*=2;
            cancelled(signal);
            bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
            if(bitmap==null||(long)bitmap.getWidth()*bitmap.getHeight()>QrPixels.MAX_PIXELS)throw new IOException("Image cannot be decoded");
            cancelled(signal);
            pixels=new int[bitmap.getWidth()*bitmap.getHeight()];
            bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
            String result=QrPixels.decode(pixels,bitmap.getWidth(),bitmap.getHeight());
            cancelled(signal);
            return result; // Caller MUST apply InvitationValidator before populating UI.
        }catch(OperationCanceledException ex){throw new IOException("QR import cancelled");}
        finally{if(pixels!=null)Arrays.fill(pixels,0);if(bitmap!=null)bitmap.recycle();}
    }
    private static void cancelled(CancellationSignal signal)throws IOException {
        QrPixels.checkCancelled();signal.throwIfCanceled();
    }
    private static void closeQuietly(Closeable value){
        if(value!=null)try{value.close();}catch(IOException ignored){/* cancellation is fail-closed */}
    }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        @Override public void close(){Arrays.fill(buf,(byte)0);reset();}
    }
}
