package com.interrupt.dungeoneer.net;

import java.io.IOException;
import java.util.zip.CRC32;

import com.interrupt.dungeoneer.net.NetMessage.LevelBegin;
import com.interrupt.dungeoneer.net.NetMessage.LevelChunk;

/**
 * Puts a level back together from the pieces a host sends. Feed it every LevelBegin and LevelChunk
 * the client receives. Any break in the rules throws an IOException, and the client should treat
 * that as the host being broken and leave the game.
 */
public final class LevelDownload {
    private byte[] data;
    private int expectedChunks;
    private int expectedCrc;
    private int nextChunk = 0;
    private int written = 0;
    private boolean complete = false;

    public void accept(NetMessage message) throws IOException {
        if (message instanceof LevelBegin) {
            acceptBegin((LevelBegin) message);
        }
        else if (message instanceof LevelChunk) {
            acceptChunk((LevelChunk) message);
        }
        else {
            throw new IOException("Not a level message");
        }
    }

    private void acceptBegin(LevelBegin begin) throws IOException {
        if (data != null) throw new IOException("A second level was announced");

        data = new byte[begin.totalBytes];
        expectedChunks = begin.chunkCount;
        expectedCrc = begin.crc32;
    }

    private void acceptChunk(LevelChunk chunk) throws IOException {
        if (data == null) throw new IOException("Level data arrived before it was announced");
        if (complete) throw new IOException("Level data arrived after the level was complete");
        if (chunk.index != nextChunk) throw new IOException("Got piece " + chunk.index + " of the level, expected " + nextChunk);

        boolean last = chunk.index == expectedChunks - 1;
        int expectedLength = last ? data.length - written : NetProtocol.LEVEL_CHUNK_BYTES;
        if (chunk.data.length != expectedLength) {
            throw new IOException("Piece " + chunk.index + " is " + chunk.data.length + " bytes, expected " + expectedLength);
        }

        System.arraycopy(chunk.data, 0, data, written, chunk.data.length);
        written += chunk.data.length;
        nextChunk++;

        if (last) {
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length);
            if ((int) crc.getValue() != expectedCrc) throw new IOException("The level was damaged on the way");

            complete = true;
        }
    }

    public boolean isComplete() {
        return complete;
    }

    /** How much of the level has arrived, from 0 to 1, for a loading bar. */
    public float getProgress() {
        if (data == null) return 0f;
        return (float) written / data.length;
    }

    /** The finished level. Only valid once isComplete() is true. */
    public byte[] getData() {
        if (!complete) throw new IllegalStateException("The level has not finished arriving");
        return data;
    }
}
