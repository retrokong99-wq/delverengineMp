package com.interrupt.dungeoneer.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One TCP socket carrying length-prefixed messages. It has its own reader and writer threads, so
 * a slow or stuck peer can never freeze the game thread.
 */
final class Connection {
    interface Handler {
        /** Called on this connection's reader thread. */
        void onFrame(Connection connection, byte[] frame);

        /** Called exactly once, on whichever thread closed the connection. */
        void onClosed(Connection connection);
    }

    private static final Logger LOG = Logger.getLogger("DelverNet");

    /** A peer that falls this far behind is dropped rather than letting memory grow without limit. */
    private static final int MAX_QUEUED_FRAMES = 256;

    /** Queued after a final message to close the socket once that message has actually been written. */
    private static final byte[] CLOSE_AFTER_FLUSH = new byte[0];

    final Socket socket;

    /** Filled in by the transports once they know who is on the other end. */
    volatile int peerId = -1;
    volatile boolean handshaken = false;

    private final Handler handler;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final BlockingQueue<byte[]> outbound = new LinkedBlockingQueue<byte[]>(MAX_QUEUED_FRAMES);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread reader;
    private final Thread writer;

    Connection(Socket socket, Handler handler, String name) throws IOException {
        this.socket = socket;
        this.handler = handler;

        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        this.reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, name + "-reader");
        this.writer = new Thread(new Runnable() {
            @Override public void run() { writeLoop(); }
        }, name + "-writer");
        reader.setDaemon(true);
        writer.setDaemon(true);
    }

    void start() {
        reader.start();
        writer.start();
    }

    /** Queues a message. Returns false if the connection is already closed or has fallen too far behind. */
    boolean send(byte[] frame) {
        if (frame.length < 1 || frame.length > NetProtocol.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Message is " + frame.length + " bytes, the limit is " + NetProtocol.MAX_FRAME_BYTES);
        }

        if (closed.get()) return false;

        if (!outbound.offer(frame)) {
            close();
            return false;
        }

        return true;
    }

    /** Closes once everything already queued has been written, for a last message such as a rejection. */
    void closeAfterFlush() {
        if (!outbound.offer(CLOSE_AFTER_FLUSH)) close();
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;

        try {
            socket.close();
        }
        catch (IOException ignored) {
            // already gone
        }

        writer.interrupt();
        handler.onClosed(this);
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                int length = in.readInt();
                if (length < 1 || length > NetProtocol.MAX_FRAME_BYTES) {
                    throw new IOException("Bad message length " + length);
                }

                byte[] frame = new byte[length];
                in.readFully(frame);
                handler.onFrame(this, frame);
            }
        }
        catch (IOException ignored) {
            // the peer left, timed out or sent garbage; either way this connection is over
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Network handler failed, dropping the connection", e);
        }
        finally {
            close();
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                byte[] frame = outbound.take();
                if (frame == CLOSE_AFTER_FLUSH) break;

                out.writeInt(frame.length);
                out.write(frame);
                out.flush();
            }
        }
        catch (IOException ignored) {
            // the peer is gone
        }
        catch (InterruptedException ignored) {
            // close() woke us up
        }
        finally {
            close();
        }
    }
}
