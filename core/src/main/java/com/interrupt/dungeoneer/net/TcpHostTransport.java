package com.interrupt.dungeoneer.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.interrupt.dungeoneer.net.NetMessage.Hello;
import com.interrupt.dungeoneer.net.NetMessage.PlayerState;
import com.interrupt.dungeoneer.net.NetMessage.Reject;
import com.interrupt.dungeoneer.net.NetMessage.Welcome;

/**
 * The host side over plain TCP, meant for development and LAN games. The host plays as peer 0 and
 * is not a connection; up to MAX_PLAYERS - 1 clients can join.
 *
 * It trusts nobody: a new connection must say hello within a few seconds with the right protocol
 * version, a client may only ever send its own position, and the player id on that position is
 * overwritten with the real one. Plain TCP does not encrypt or authenticate anything, so use this
 * on a network you trust, and use a Steam transport for games over the internet.
 */
public final class TcpHostTransport implements NetTransport, Connection.Handler {
    private static final Logger LOG = Logger.getLogger("DelverNet");

    private final ServerSocket server;
    private final Connection[] peers = new Connection[NetProtocol.MAX_PLAYERS];
    private final ConcurrentLinkedQueue<NetEvent> events = new ConcurrentLinkedQueue<NetEvent>();
    private final Object lock = new Object();
    private volatile boolean closed = false;

    /** Starts listening. Pass 0 to let the system pick a free port, then read it back with getLocalPort(). */
    public TcpHostTransport(int port) throws IOException {
        server = new ServerSocket(port);

        Thread acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "net-host-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int getLocalPort() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();

                // Whoever connects has a few seconds to finish the handshake
                socket.setSoTimeout(NetProtocol.HANDSHAKE_TIMEOUT_MS);

                Connection connection = new Connection(socket, this, "net-host-peer");
                connection.start();
            }
            catch (IOException e) {
                if (closed) return;
                LOG.log(Level.WARNING, "Could not accept a connection", e);
            }
        }
    }

    @Override
    public void onFrame(Connection connection, byte[] frame) {
        NetMessage message;
        try {
            message = NetMessage.fromBytes(frame);
        }
        catch (IOException e) {
            connection.close();
            return;
        }

        if (!connection.handshaken) {
            handleHello(connection, message);
            return;
        }

        // After joining, a client may only report where its own player is
        if (message instanceof PlayerState) {
            events.add(NetEvent.message(connection.peerId, ((PlayerState) message).withPeerId(connection.peerId)));
        }
        else {
            connection.close();
        }
    }

    private void handleHello(Connection connection, NetMessage message) {
        if (!(message instanceof Hello)) {
            connection.close();
            return;
        }

        Hello hello = (Hello) message;
        if (hello.protocolVersion != NetProtocol.PROTOCOL_VERSION) {
            reject(connection, "Different game version (host " + NetProtocol.PROTOCOL_VERSION + ", you " + hello.protocolVersion + ")");
            return;
        }

        int peerId = claimSlot(connection);
        if (peerId < 0) {
            reject(connection, "The game is full");
            return;
        }

        try {
            connection.peerId = peerId;
            connection.handshaken = true;
            connection.socket.setSoTimeout(0);
            connection.send(new Welcome(peerId, NetProtocol.MAX_PLAYERS).toBytes());
        }
        catch (IOException e) {
            connection.close();
            return;
        }

        events.add(NetEvent.connected(peerId, hello.name));
    }

    private void reject(Connection connection, String reason) {
        try {
            connection.send(new Reject(reason).toBytes());
            connection.closeAfterFlush();
        }
        catch (IOException e) {
            connection.close();
        }
    }

    private int claimSlot(Connection connection) {
        synchronized (lock) {
            if (closed) return -1;

            for (int i = NetProtocol.HOST_PEER_ID + 1; i < NetProtocol.MAX_PLAYERS; i++) {
                if (peers[i] == null) {
                    peers[i] = connection;
                    return i;
                }
            }
        }

        return -1;
    }

    @Override
    public void onClosed(Connection connection) {
        int peerId = connection.peerId;

        if (peerId > NetProtocol.HOST_PEER_ID) {
            synchronized (lock) {
                if (peers[peerId] == connection) peers[peerId] = null;
            }

            if (connection.handshaken && !closed) {
                events.add(NetEvent.disconnected(peerId, "Connection closed"));
            }
        }
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
        if (peerId <= NetProtocol.HOST_PEER_ID || peerId >= NetProtocol.MAX_PLAYERS) return;

        Connection connection;
        synchronized (lock) {
            connection = peers[peerId];
        }

        if (connection != null && connection.handshaken) {
            sendTo(connection, message);
        }
    }

    @Override
    public void broadcast(NetMessage message) {
        for (int i = NetProtocol.HOST_PEER_ID + 1; i < NetProtocol.MAX_PLAYERS; i++) {
            send(i, message);
        }
    }

    private void sendTo(Connection connection, NetMessage message) {
        try {
            connection.send(message.toBytes());
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not encode a message", e);
        }
    }

    @Override
    public int getLocalPeerId() {
        return NetProtocol.HOST_PEER_ID;
    }

    @Override
    public void close() {
        Connection[] open;
        synchronized (lock) {
            closed = true;
            open = peers.clone();
        }

        try {
            server.close();
        }
        catch (IOException ignored) {
            // nothing useful to do
        }

        for (Connection connection : open) {
            if (connection != null) connection.close();
        }
    }
}
