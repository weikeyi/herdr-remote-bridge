package dev.herdrbridge.protocol;

import java.util.ArrayList;
import java.util.List;

/** Ordered gateway-phone chunk layer, not relay-link multiplexing or E2EE. */
public final class ChunkCodec {
    public static final int MAX_CHUNK_BYTES = 262144;
    public static final int MAX_LOGICAL_BYTES = 21 * 1024 * 1024;
    public static final long STALL_MILLIS = 30000;
    private ChunkCodec() {}

    public static List<byte[]> encode(byte[] logical) { return encode(logical, MAX_CHUNK_BYTES); }
    static List<byte[]> encode(byte[] logical, int ceiling) {
        if (logical == null || logical.length > MAX_LOGICAL_BYTES || ceiling <= 6 || ceiling > MAX_CHUNK_BYTES)
            throw new IllegalArgumentException("Invalid chunk input or ceiling");
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        boolean first = true;
        do {
            int header = first ? 6 : 2;
            int take = Math.min(ceiling - header, logical.length - offset);
            byte[] piece = new byte[header + take];
            piece[0] = 1;
            piece[1] = (byte)((first ? 1 : 0) | (offset + take == logical.length ? 2 : 0));
            if (first) {
                int size = logical.length;
                piece[2] = (byte)(size >>> 24); piece[3] = (byte)(size >>> 16);
                piece[4] = (byte)(size >>> 8); piece[5] = (byte)size;
            }
            System.arraycopy(logical, offset, piece, header, take);
            chunks.add(piece);
            offset += take;
            first = false;
        } while (offset < logical.length);
        return chunks;
    }

    /** Call push and tick on one serialized executor, using elapsed realtime. */
    public static final class Reassembler {
        private byte[] pending;
        private int received;
        private long lastProgress;
        private long lastClock = -1;
        private boolean closed;
        public boolean isClosed() { return closed; }
        public void close() { pending = null; received = 0; closed = true; }
        private IllegalStateException fail(String reason) { close(); return new IllegalStateException(reason); }
        public void tick(long monotonicMillis) {
            if (closed) throw new IllegalStateException("Chunk assembler is closed");
            if (monotonicMillis < 0 || monotonicMillis < lastClock) throw fail("Non-monotonic chunk clock");
            lastClock = monotonicMillis;
            if (pending != null && monotonicMillis - lastProgress >= STALL_MILLIS) throw fail("Chunk assembly stalled");
        }
        /** Returns one completed logical message, or null while incomplete. */
        public byte[] push(byte[] piece, long monotonicMillis) {
            tick(monotonicMillis);
            if (piece == null || piece.length < 2 || piece.length > MAX_CHUNK_BYTES) throw fail("Invalid chunk size");
            if (piece[0] != 1) throw fail("Unsupported chunk version");
            int flags = piece[1] & 255;
            if ((flags & ~3) != 0) throw fail("Unknown chunk flags");
            int header = 2;
            if ((flags & 1) != 0) {
                if (pending != null || piece.length < 6) throw fail("Invalid start chunk");
                long size = ((piece[2] & 255L) << 24) | ((piece[3] & 255L) << 16)
                    | ((piece[4] & 255L) << 8) | (piece[5] & 255L);
                if (size > MAX_LOGICAL_BYTES) throw fail("Logical message too large");
                pending = new byte[(int)size]; received = 0; header = 6;
            } else if (pending == null) throw fail("Chunk without start");
            int count = piece.length - header;
            if (count > pending.length - received) throw fail("Chunk exceeds declared length");
            System.arraycopy(piece, header, pending, received, count);
            received += count;
            lastProgress = monotonicMillis;
            if ((flags & 2) == 0) return null;
            if (received != pending.length) throw fail("Incomplete end chunk");
            byte[] completed = pending;
            pending = null; received = 0;
            return completed;
        }
    }
}
