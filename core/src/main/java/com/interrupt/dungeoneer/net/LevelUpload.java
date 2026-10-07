package com.interrupt.dungeoneer.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import com.interrupt.dungeoneer.net.NetMessage.LevelBegin;
import com.interrupt.dungeoneer.net.NetMessage.LevelChunk;

/**
 * A level on its way from the host to one client. The level is cut into pieces up front, and the
 * game hands them to the transport a few at a time with pump(), once per tick. Sending them all at
 * once would overflow the transport's send queue and get the client dropped.
 *
 * A pump of 16 or so per tick moves about 7 MB a second at 60 ticks a second, which is plenty.
 */
public final class LevelUpload {
    private final List<NetMessage> messages;
    private int next = 0;

    /** @param levelBytes the whole level as saved by the game, between 1 byte and NetProtocol.MAX_LEVEL_BYTES */
    public LevelUpload(byte[] levelBytes) {
        if (levelBytes == null || levelBytes.length < 1 || levelBytes.length > NetProtocol.MAX_LEVEL_BYTES) {
            throw new IllegalArgumentException("A level must be between 1 and " + NetProtocol.MAX_LEVEL_BYTES + " bytes");
        }

        CRC32 crc = new CRC32();
        crc.update(levelBytes, 0, levelBytes.length);

        int chunkCount = (levelBytes.length + NetProtocol.LEVEL_CHUNK_BYTES - 1) / NetProtocol.LEVEL_CHUNK_BYTES;
        messages = new ArrayList<NetMessage>(chunkCount + 1);
        messages.add(new LevelBegin(levelBytes.length, chunkCount, (int) crc.getValue()));

        for (int i = 0; i < chunkCount; i++) {
            int from = i * NetProtocol.LEVEL_CHUNK_BYTES;
            int to = Math.min(levelBytes.length, from + NetProtocol.LEVEL_CHUNK_BYTES);
            messages.add(new LevelChunk(i, Arrays.copyOfRange(levelBytes, from, to)));
        }
    }

    /** Sends up to maxMessages more pieces to one client. Returns true once everything has been handed over. */
    public boolean pump(NetTransport transport, int peerId, int maxMessages) {
        int sent = 0;
        while (next < messages.size() && sent < maxMessages) {
            transport.send(peerId, messages.get(next));
            next++;
            sent++;
        }

        return isDone();
    }

    public boolean isDone() {
        return next >= messages.size();
    }

    /** How many messages the whole level needs, announcement included. */
    public int getMessageCount() {
        return messages.size();
    }
}
