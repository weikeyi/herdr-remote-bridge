package dev.herdrbridge.protocol;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.Arrays;

public class ChunkCodecTest {
    private byte[] hex(String text) {
        if ((text.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex digit");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
    @Test public void matchesActualTypescriptExporterVector() {
        byte[] logical=hex("7b2274797065223a2273796e7468657469632d6672616d696e672d6f6e6c79227d");
        String[] expected={"0101000000217b2274797065223a2273","0100796e7468657469632d6672616d69","01026e672d6f6e6c79227d"};
        List<byte[]> pieces=ChunkCodec.encode(logical,16);
        assertEquals(expected.length,pieces.size());
        ChunkCodec.Reassembler r=new ChunkCodec.Reassembler();
        for(int i=0;i<pieces.size();i++) {
            assertArrayEquals(hex(expected[i]),pieces.get(i));
            byte[] output=r.push(pieces.get(i),i);
            if(i+1==pieces.size())assertArrayEquals(logical,output);else assertNull(output);
        }
        assertFalse(r.isClosed());
    }
    @Test public void zeroAndMaximumMessagesAndNewAssembly() {
        ChunkCodec.Reassembler r=new ChunkCodec.Reassembler();
        assertArrayEquals(new byte[0],r.push(ChunkCodec.encode(new byte[0]).get(0),0));
        byte[] large=new byte[ChunkCodec.MAX_LOGICAL_BYTES];Arrays.fill(large,(byte)42);
        byte[] output=null;int tick=1;
        for(byte[] p:ChunkCodec.encode(large)){assertTrue(p.length<=262144);output=r.push(p,tick++);}
        assertArrayEquals(large,output);
        assertThrows(IllegalArgumentException.class,()->ChunkCodec.encode(new byte[ChunkCodec.MAX_LOGICAL_BYTES+1]));
    }
    private void rejects(String... frames) {
        ChunkCodec.Reassembler r=new ChunkCodec.Reassembler();
        assertThrows(IllegalStateException.class,()-> {for(String frame:frames)r.push(hex(frame),0);});
        assertTrue(r.isClosed());
        assertThrows(IllegalStateException.class,()->r.push(hex("010300000000"),1));
    }
    @Test public void rejectsSequenceFlagsLengthAndVersion() {
        rejects("01");rejects("020300000000");rejects("010400000000");rejects("0102");
        rejects("0101");rejects("010101500001");rejects("010300000001");
        rejects("010300000000ff");rejects("010100000002ff","010100000001ff");
        rejects("010100000001ff","0102ff");
    }
    @Test public void timeoutAndClockFailureAreFatal() {
        ChunkCodec.Reassembler r=new ChunkCodec.Reassembler();
        r.push(hex("010100000002ff"),100);
        r.tick(30099);
        assertThrows(IllegalStateException.class,()->r.tick(30100));
        assertTrue(r.isClosed());
        ChunkCodec.Reassembler backward=new ChunkCodec.Reassembler();
        backward.tick(10);
        assertThrows(IllegalStateException.class,()->backward.tick(9));
    }
    @Test public void closeAndOversizeCarrierFail() {
        ChunkCodec.Reassembler r=new ChunkCodec.Reassembler();
        assertThrows(IllegalStateException.class,()->r.push(new byte[262145],0));
        ChunkCodec.Reassembler closed=new ChunkCodec.Reassembler();closed.close();
        assertThrows(IllegalStateException.class,()->closed.tick(0));
    }
}
