package com.interrupt.dungeoneer.net;

/**
 * Numbers shared by every part of the multiplayer code.
 *
 * Nothing in the net package depends on libGDX or on the rest of the game, so it can be built and
 * tested on its own with a plain JDK.
 */
public final class NetProtocol {
    private NetProtocol() {}

    /** Bumped whenever the messages change, so a host and a client on different builds refuse each other. */
    public static final int PROTOCOL_VERSION = 1;

    /** Players in one game, the host included. */
    public static final int MAX_PLAYERS = 4;

    /** The host always plays as peer 0. Clients get 1 to MAX_PLAYERS - 1. */
    public static final int HOST_PEER_ID = 0;

    /** Largest message accepted from the network. A bigger length is treated as garbage and the peer is dropped. */
    public static final int MAX_FRAME_BYTES = 16 * 1024;

    public static final int MAX_NAME_LENGTH = 24;

    /** How long a new connection gets to finish the handshake before it is dropped. */
    public static final int HANDSHAKE_TIMEOUT_MS = 5000;

    public static final int CONNECT_TIMEOUT_MS = 5000;

    public static final int DEFAULT_PORT = 7777;

    /** True for a peer id that can belong to a player. */
    public static boolean isValidPeerId(int peerId) {
        return peerId >= 0 && peerId < MAX_PLAYERS;
    }
}
