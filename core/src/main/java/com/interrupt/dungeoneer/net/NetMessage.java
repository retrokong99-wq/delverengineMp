package com.interrupt.dungeoneer.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Everything that can be sent between a host and its clients.
 *
 * Messages are read from strangers on the network, so reading is strict: unknown types, bad ids,
 * non-finite numbers, over-long names and leftover bytes all throw an IOException, and the
 * transports drop the peer that sent them.
 */
public abstract class NetMessage {
    public static final byte HELLO = 1;
    public static final byte WELCOME = 2;
    public static final byte REJECT = 3;
    public static final byte PLAYER_STATE = 4;
    public static final byte PLAYER_LEFT = 5;
    public static final byte LEVEL_BEGIN = 6;
    public static final byte LEVEL_CHUNK = 7;

    public abstract byte type();

    protected abstract void writeBody(DataOutputStream out) throws IOException;

    /** The bytes that go on the wire, not counting the length prefix the transports add. */
    public final byte[] toBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(type());
        writeBody(out);
        out.flush();
        return bytes.toByteArray();
    }

    public static NetMessage fromBytes(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        byte type = in.readByte();

        NetMessage message;
        switch (type) {
            case HELLO: message = Hello.read(in); break;
            case WELCOME: message = Welcome.read(in); break;
            case REJECT: message = Reject.read(in); break;
            case PLAYER_STATE: message = PlayerState.read(in); break;
            case PLAYER_LEFT: message = PlayerLeft.read(in); break;
            case LEVEL_BEGIN: message = LevelBegin.read(in); break;
            case LEVEL_CHUNK: message = LevelChunk.read(in); break;
            default: throw new IOException("Unknown message type " + type);
        }

        if (in.available() > 0) throw new IOException("Unexpected extra bytes after message type " + type);
        return message;
    }

    private static String readText(DataInputStream in) throws IOException {
        String text = in.readUTF();
        if (text.length() > NetProtocol.MAX_NAME_LENGTH * 4) throw new IOException("Text too long");
        return text;
    }

    private static float readFinite(DataInputStream in) throws IOException {
        float value = in.readFloat();
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IOException("Number is not finite");
        return value;
    }

    private static int readPeerId(DataInputStream in) throws IOException {
        int peerId = in.readUnsignedByte();
        if (!NetProtocol.isValidPeerId(peerId)) throw new IOException("Bad player id " + peerId);
        return peerId;
    }

    /** First thing a client sends. */
    public static final class Hello extends NetMessage {
        public final int protocolVersion;
        public final String name;

        public Hello(int protocolVersion, String name) {
            this.protocolVersion = protocolVersion;
            this.name = clean(name);
        }

        /** Names come from the player, so keep them short and printable. */
        private static String clean(String name) {
            if (name == null) return "Player";

            StringBuilder cleaned = new StringBuilder();
            for (int i = 0; i < name.length() && cleaned.length() < NetProtocol.MAX_NAME_LENGTH; i++) {
                char c = name.charAt(i);
                if (!Character.isISOControl(c)) cleaned.append(c);
            }

            String result = cleaned.toString().trim();
            return result.isEmpty() ? "Player" : result;
        }

        @Override public byte type() { return HELLO; }

        @Override protected void writeBody(DataOutputStream out) throws IOException {
            out.writeInt(protocolVersion);
            out.writeUTF(name);
        }

        static Hello read(DataInputStream in) throws IOException {
            int version = in.readInt();
            return new Hello(version, readText(in));
        }
    }

    /** The host's answer to a Hello it accepted: which player you are. */
    public static final class Welcome extends NetMessage {
        public final int peerId;
        public final int maxPlayers;

        public Welcome(int peerId, int maxPlayers) {
            this.peerId = peerId;
            this.maxPlayers = maxPlayers;
        }

        @Override public byte type() { return WELCOME; }

        @Override protected void writeBody(DataOutputStream out) throws IOException {
            out.writeByte(peerId);
            out.writeByte(maxPlayers);
        }

        static Welcome read(DataInputStream in) throws IOException {
            int peerId = readPeerId(in);
            int maxPlayers = in.readUnsignedByte();
            if (maxPlayers < 1 || maxPlayers > NetProtocol.MAX_PLAYERS) throw new IOException("Bad player limit " + maxPlayers);
            return new Welcome(peerId, maxPlayers);
        }
    }

    /** The host's answer when it will not let you in. The reason is shown to the player. */
    public static final class Reject extends NetMessage {
        public final String reason;

        public Reject(String reason) {
            this.reason = reason == null ? "" : reason;
        }

        @Override public byte type() { return REJECT; }

        @Override protected void writeBody(DataOutputStream out) throws IOException {
            out.writeUTF(reason);
        }

        static Reject read(DataInputStream in) throws IOException {
            return new Reject(readText(in));
        }
    }

    /** Where a player is and which way they are looking. Sent often. */
    public static final class PlayerState extends NetMessage {
        public final int peerId;
        public final float x, y, z;
        public final float rot, pitch;

        public PlayerState(int peerId, float x, float y, float z, float rot, float pitch) {
            this.peerId = peerId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rot = rot;
            this.pitch = pitch;
        }

        /** A copy that claims to come from another player. The host uses this so a client cannot pose as someone else. */
        public PlayerState withPeerId(int newPeerId) {
            return new PlayerState(newPeerId, x, y, z, rot, pitch);
        }

        @Override public byte type() { return PLAYER_STATE; }

        @Override protected void writeBody(DataOutputStream out) throws IOException {
            out.writeByte(peerId);
            out.writeFloat(x);
            out.writeFloat(y);
            out.writeFloat(z);
            out.writeFloat(rot);
            out.writeFloat(pitch);
        }

        static PlayerState read(DataInputStream in) throws IOException {
            int peerId = readPeerId(in);
            return new PlayerState(peerId, readFinite(in), readFinite(in), readFinite(in), readFinite(in), readFinite(in));
        }
    }

    /** Tells everyone a player has gone. */
    public static final class PlayerLeft extends NetMessage {
        public final int peerId;

        public PlayerLeft(int peerId) {
            this.peerId = peerId;
        }

        @Override public byte type() { return PLAYER_LEFT; }

        @Override protected void writeBody(DataOutputStream out) throws IOException {
            out.writeByte(peerId);
        }

        static PlayerLeft read(DataInputStream in) throws IOException {
            return new PlayerLeft(readPeerId(in));
        }
    }

    /** Announces a level that is about to arrive in pieces. Only a host sends this. */
    public static final class LevelBegin extends NetMessage {
        public final int totalBytes;
        public final int chunkCount;
        /** CRC32 of the whole level, to catch a level that got damaged on the way. */
        public final int crc32;

        public LevelBegin(int totalBytes, int chunkCount, int crc32) {
            this.totalBytes = totalBytes;
            this.chunkCount = chunkCount;
            this.crc32 = crc32;
        }

        @Override public byte type() { return LEVEL_BEGIN; }

        @Override protected void writeBody(DataOutputStream out) throws IOException {
            out.writeInt(totalBytes);
            out.writeInt(chunkCount);
            out.writeInt(crc32);
        }

        static LevelBegin read(DataInputStream in) throws IOException {
            int totalBytes = in.readInt();
            int chunkCount = in.readInt();
            int crc32 = in.readInt();

            if (totalBytes < 1 || totalBytes > NetProtocol.MAX_LEVEL_BYTES) throw new IOException("Bad level size " + totalBytes);

            int expectedChunks = (totalBytes + NetProtocol.LEVEL_CHUNK_BYTES - 1) / NetProtocol.LEVEL_CHUNK_BYTES;
            if (chunkCount != expectedChunks) throw new IOException("Level of " + totalBytes + " bytes cannot be " + chunkCount + " chunks");

            return new LevelBegin(totalBytes, chunkCount, crc32);
        }
    }

    /** One piece of a level. Pieces arrive in order, which TCP guarantees. */
    public static final class LevelChunk extends NetMessage {
        public final int index;
        public final byte[] data;

        public LevelChunk(int index, byte[] data) {
            this.index = index;
            this.data = data;
        }

        @Override public byte type() { return LEVEL_CHUNK; }

        @Override protected void writeBody(DataOutputStream out) throws IOException {
            out.writeInt(index);
            out.writeShort(data.length);
            out.write(data);
        }

        static LevelChunk read(DataInputStream in) throws IOException {
            int index = in.readInt();
            int length = in.readUnsignedShort();

            if (index < 0 || index >= NetProtocol.MAX_LEVEL_CHUNKS) throw new IOException("Bad chunk number " + index);
            if (length < 1 || length > NetProtocol.LEVEL_CHUNK_BYTES) throw new IOException("Bad chunk size " + length);

            byte[] data = new byte[length];
            in.readFully(data);
            return new LevelChunk(index, data);
        }
    }
}
