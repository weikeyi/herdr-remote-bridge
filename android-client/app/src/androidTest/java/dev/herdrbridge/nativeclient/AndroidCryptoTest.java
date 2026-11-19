package dev.herdrbridge.nativeclient;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.herdrbridge.protocol.E2eeTest;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import org.junit.Test;

/** Runs the unchanged host golden/negative checks against Android's JCA provider. */
public final class AndroidCryptoTest {
    @Test public void originalGoldenAndNegativeChecksOnAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        // Instrumentation executes under the target app UID, not the test APK UID.
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture = File.createTempFile("synthetic-e2ee-", ".properties", target.getCacheDir());
        try {
            try (InputStream in = context.getAssets().open("vector.properties");
                 OutputStream out = new FileOutputStream(fixture)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            E2eeTest.main(new String[]{fixture.getAbsolutePath()});
        } finally {
            if (!fixture.delete()) fixture.deleteOnExit();
        }
    }
}
