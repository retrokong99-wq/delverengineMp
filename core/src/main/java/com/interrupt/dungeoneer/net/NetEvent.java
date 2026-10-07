package com.interrupt.dungeoneer.net;

/** Something that happened on a network thread, queued until the game thread polls for it. */
final class NetEvent {
    private enum Kind { CONNECTED, MESSAGE, DISCONNECTED }

    private final Kind kind;
    private final int peerId;
    private final String text;
    private final NetMessage message;

    private NetEvent(Kind kind, int peerId, String text, NetMessage message) {
        this.kind = kind;
        this.peerId = peerId;
        this.text = text;
        this.message = message;
    }

    static NetEvent connected(int peerId, String name) {
        return new NetEvent(Kind.CONNECTED, peerId, name, null);
    }

    static NetEvent message(int peerId, NetMessage message) {
        return new NetEvent(Kind.MESSAGE, peerId, null, message);
    }

    static NetEvent disconnected(int peerId, String reason) {
        return new NetEvent(Kind.DISCONNECTED, peerId, reason, null);
    }

    void deliver(NetListener listener) {
        switch (kind) {
            case CONNECTED: listener.onConnected(peerId, text); break;
            case MESSAGE: listener.onMessage(peerId, message); break;
            case DISCONNECTED: listener.onDisconnected(peerId, text); break;
        }
    }
}
