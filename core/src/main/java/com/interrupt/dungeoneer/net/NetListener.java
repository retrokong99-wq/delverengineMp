package com.interrupt.dungeoneer.net;

/**
 * What the game hears from the network. Always called from the thread that calls
 * {@link NetTransport#poll(NetListener)}, which is the game thread, so it is safe to touch game state here.
 */
public interface NetListener {
    /** A player finished joining. On a client this fires once, for the host (peer 0). */
    void onConnected(int peerId, String name);

    void onMessage(int peerId, NetMessage message);

    /** A player left or the connection failed. On a client this means the whole game is gone. */
    void onDisconnected(int peerId, String reason);
}
