package com.interrupt.dungeoneer.net;

/**
 * One end of a multiplayer game. The game only talks to this interface, so the plain TCP version
 * used for development and LAN play can later be swapped for one that goes through Steam.
 *
 * Nothing here blocks the game thread: sending queues the message and poll() hands over whatever has arrived.
 */
public interface NetTransport {
    /** Delivers everything that happened since the last call, in order. Call once per game tick. */
    void poll(NetListener listener);

    /** On a host, sends to one client. On a client, sends to the host and peerId is ignored. */
    void send(int peerId, NetMessage message);

    /** On a host, sends to every client. On a client, sends to the host. */
    void broadcast(NetMessage message);

    /** This machine's player id: 0 on a host, the id the host handed out on a client, -1 before joining finishes. */
    int getLocalPeerId();

    void close();
}
