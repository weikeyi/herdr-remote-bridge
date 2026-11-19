package dev.herdrbridge.nativeclient;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import java.io.IOException;
import java.util.*;

/** Offline QR-only pixel decoder. Returned text is NOT a validated invitation. */
public final class QrPixels {
    public static final int MAX_PIXELS = 4 * 1024 * 1024;
    private QrPixels() {}
    public static String decode(int[] pixels,int width,int height)throws IOException {
        if(width<=0||height<=0||(long)width*height>MAX_PIXELS||pixels==null||pixels.length!=(long)width*height)
            throw new IOException("Image dimensions are not supported");
        checkCancelled();
        RGBLuminanceSource luminance=new RGBLuminanceSource(width,height,pixels);
        List<Result> found=new ArrayList<>();
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.CHARACTER_SET,"UTF-8");
        scan(luminance,hints,found);
        checkCancelled();
        scan(luminance.invert(),hints,found);
        checkCancelled();
        if(found.size()!=1)throw new IOException(found.isEmpty()?"No QR code found":"Choose an image with exactly one QR code");
        String text=found.get(0).getText();
        if(text==null||text.isEmpty()||text.length()>8192)throw new IOException("QR content is not supported");
        return text;
    }
    private static void scan(LuminanceSource source,Map<DecodeHintType,Object> hints,List<Result> found)throws IOException {
        try{
            Result[] results=new QRCodeMultiReader().decodeMultiple(new BinaryBitmap(new HybridBinarizer(source)),hints);
            for(Result result:results){
                // Structured-append composites have no physical QR points. Do not
                // silently combine several codes into a credential-bearing string.
                if(result.getResultPoints()==null||result.getResultPoints().length<3)
                    throw new IOException("Composite QR codes are not supported");
                boolean duplicate=false;
                for(Result existing:found)if(sameLocation(existing,result)&&existing.getText().equals(result.getText()))duplicate=true;
                if(!duplicate)found.add(result);
                if(found.size()>1)throw new IOException("Choose an image with exactly one QR code");
            }
        }catch(NotFoundException ignored){/* no readable QR in this polarity */}
    }
    private static boolean sameLocation(Result a,Result b) {
        ResultPoint[] x=a.getResultPoints(),y=b.getResultPoints();
        float ax=0,ay=0,bx=0,by=0;
        for(int i=0;i<3;i++){ax+=x[i].getX();ay+=x[i].getY();bx+=y[i].getX();by+=y[i].getY();}
        return Math.abs(ax-bx)<6&&Math.abs(ay-by)<6;
    }
    static void checkCancelled()throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("QR import cancelled");
    }
}
