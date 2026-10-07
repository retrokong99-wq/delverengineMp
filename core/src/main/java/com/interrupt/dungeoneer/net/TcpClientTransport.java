package com.interrupt.dungeoneer.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.interrupt.dungeoneer.net.NetMessage.Hello;
import com.interrupt.dungeoneer.net.NetMessage.LevelBegin;
import com.interrupt.dungeoneer.net.NetMessage.LevelChunk;
import com.interrupt.dungeoneer.net.NetMessage.PlayerLeft;
import com.interrupt.dungeoneer.net.NetMessage.PlayerState;
import com.interrupt.dungeoneer.net.NetMessage.Reject;
import com.interrupt.dungeoneer.net.NetMessage.Welcome;

/**
 * The client side over plain TCP. Call connect(), then keep calling poll(): onConnected fires for
 * the host once the host has accepted us, and onDisconnected fires once if that never happens or the
 * connection is lost later.
 */
public final class TcpClientTransport implements NetTransport, Connection.Handler {
    private final String name;
    private final ConcurrentLinkedQueue<NetEvent> events = new ConcurrentLinkedQueue<NetEvent>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private volatile Connection connection;
    private volatile boolean closed = false;
    private volatile int localPeerId = -1;

    public TcpClientTransport(String name) {
        this.name = name;
    }

    /** Connects in the background, so the game keeps running. Progress arrives through poll(). */
    public void connect(final String host, final int port) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("connect() was already called");
        }

        Thread connectThread = new Thread(new Runnable() {
            @Override public void run() { doConnect(host, port); }
        }, "net-client-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void doConnect(String host, int port) {
        Socket socket = new Socket();

        try {
            socket.connect(new InetSocketAddress(host, port), NetProtocol.CONNECT_TIMEOUT_MS);

            // The host has a few seconds to answer our hello
            socket.setSoTimeout(NetProtocol.HANDSHAKE_TIMEOUT_MS);

            Connection c = new Connection(socket, this, "net-client");
            connection = c;

            if (closed) {
                c.close();
                return;
            }

            c.start();
            c.send(new Hello(NetProtocol.PROTOCOL_VERSION, name).toBytes());
        }
        catch (IOException e) {
            try {
                socket.close();
            }
            catch (IOException ignored) {
                // already closed
            }

            end("Could not connect: " + e.getMessage());
        }
    }

    private void end(String reason) {
        if (ended.compareAndSet(false, true)) {
            events.add(NetEvent.disconnected(NetProtocol.HOST_PEER_ID, reason));
        }
    }

    @Override
    public void onFrame(Connection c, byte[] frame) {
        NetMessage message;
        try {
            message = NetMessage.fromBytes(frame);
        }
        catch (IOException e) {
            end("The host sent something we could not understand");
            c.close();
            return;
        }

        if (!c.handshaken) {
            if (message instanceof Welcome) {
                try {
                    c.socket.setSoTimeout(0);
                }
                catch (IOException e) {
                    c.close();
                    return;
                }

                localPeerId = ((Welcome) message).peerId;
                c.peerId = NetProtocol.HOST_PEER_ID;
                c.handshaken = true;
                events.add(NetEvent.connected(NetProtocol.HOST_PEER_ID, "Host"));
            }
            else if (message instanceof Reject) {
                end(((Reject) message).reason);
                c.close();
            }
            else {
                end("The host did not answer properly");
                c.close();
            }
            return;
        }

        // Once joined, the host may tell us where players are, who has left, and send us the level
        if (message instanceof PlayerState || message instanceof PlayerLeft
                || message instanceof LevelBegin || message instanceof LevelChunk) {
            events.add(NetEvent.message(NetProtocol.HOST_PEER_ID, message));
        }
        else {
            end("The host sent something unexpected");
            c.close();
        }
    }

    @Override
    public void onClosed(Connection c) {
        if (!closed) end("Connection lost");
    }

    @Override
    public void poll(NetListener listener) {
        NetEvent event;
        while ((event = events.poll()) != null) {
            event.deliver(listener);
        }
    }

    @Override
    public void send(int peerId, NetMessage message) {
        broadcast(message);
    }

    @Override
    public void broadcast(NetMessage message) {
        Connection c = connection;
        if (c == null || !c.handshaken) return;

        try {
            c.send(message.toBytes());
        }
        catch (IOException e) {
            end("Could not send: " + e.getMessage());
            c.close();
        }
    }

    @Override
    public int getLocalPeerId() {
        return localPeerId;
    }

    @Override
    public void close() {
        closed = true;

        Connection c = connection;
        if (c != null) c.close();
    }
}
