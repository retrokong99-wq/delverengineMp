package com.interrupt.dungeoneer.net;

/*
 * Loopback test for the multiplayer network layer. It needs nothing but a JDK: a host and several
 * clients run inside this one process, plus a few "raw" sockets that misbehave on purpose.
 *
 * From the core/src/main/java folder (about 10 seconds):
 *   javac -d out com/interrupt/dungeoneer/net/*.java ../../test/java/com/interrupt/dungeoneer/net/NetSelfTest.java
 *   java -cp out com.interrupt.dungeoneer.net.NetSelfTest
 * It prints PASS or FAIL for each check and ends with ALL CHECKS PASSED.
 */

import com.interrupt.dungeoneer.net.NetMessage.*;

import java.io.*;
import java.net.Socket;
import java.util.*;

public class NetSelfTest {
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    /** Records everything a transport reports. */
    static class Recorder implements NetListener {
        final List<String> log = new ArrayList<String>();
        final List<NetMessage> messages = new ArrayList<NetMessage>();
        final List<Integer> messagePeers = new ArrayList<Integer>();
        final List<Integer> connected = new ArrayList<Integer>();
        final List<String> connectedNames = new ArrayList<String>();
        final List<Integer> disconnected = new ArrayList<Integer>();
        final List<String> disconnectReasons = new ArrayList<String>();

        public void onConnected(int peerId, String name) { connected.add(peerId); connectedNames.add(name); }
        public void onMessage(int peerId, NetMessage m) { messages.add(m); messagePeers.add(peerId); }
        public void onDisconnected(int peerId, String reason) { disconnected.add(peerId); disconnectReasons.add(reason); }
    }

    interface Cond { boolean ok(); }

    static boolean waitFor(Cond cond, NetTransport[] transports, Recorder[] recorders, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            for (int i = 0; i < transports.length; i++) transports[i].poll(recorders[i]);
            if (cond.ok()) return true;
            Thread.sleep(10);
        }
        for (int i = 0; i < transports.length; i++) transports[i].poll(recorders[i]);
        return cond.ok();
    }

    static void writeFrame(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    static NetMessage readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return NetMessage.fromBytes(b);
    }

    /** True when the other side closes the socket (EOF or reset) within the time limit. */
    static boolean closedByPeer(Socket s, int ms) throws Exception {
        s.setSoTimeout(ms);
        try {
            return s.getInputStream().read() == -1;
        } catch (java.net.SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true; // connection reset counts as closed
        }
    }

    public static void main(String[] args) throws Exception {
        final TcpHostTransport host = new TcpHostTransport(0);
        final int port = host.getLocalPort();
        final Recorder hostRec = new Recorder();

        // ---- message encoding ----
        PlayerState ps = new PlayerState(2, 1.5f, -2f, 3f, 0.25f, 0.5f);
        PlayerState back = (PlayerState) NetMessage.fromBytes(ps.toBytes());
        check("PlayerState survives encode/decode", back.peerId == 2 && back.x == 1.5f && back.y == -2f && back.z == 3f && back.rot == 0.25f && back.pitch == 0.5f);
        Hello h = (Hello) NetMessage.fromBytes(new Hello(1, "  Al\u0007ice  ").toBytes());
        check("Hello name is cleaned (control chars removed, trimmed)", h.name.equals("Alice"));
        check("Hello name is capped at " + NetProtocol.MAX_NAME_LENGTH, new Hello(1, "x" + new String(new char[100]).replace('\0', 'y')).name.length() == NetProtocol.MAX_NAME_LENGTH);
        boolean threw = false;
        try { NetMessage.fromBytes(new byte[]{99}); } catch (IOException e) { threw = true; }
        check("unknown message type is rejected", threw);
        threw = false;
        try { byte[] b = new PlayerLeft(1).toBytes(); byte[] extra = Arrays.copyOf(b, b.length + 1); NetMessage.fromBytes(extra); } catch (IOException e) { threw = true; }
        check("trailing bytes are rejected", threw);
        threw = false;
        try { NetMessage.fromBytes(new PlayerLeft(9).toBytes()); } catch (IOException e) { threw = true; }
        check("out of range player id is rejected", threw);

        // ---- client A joins ----
        final TcpClientTransport a = new TcpClientTransport("Alice");
        final Recorder aRec = new Recorder();
        a.connect("127.0.0.1", port);
        final NetTransport[] ha = {host, a};
        final Recorder[] haRec = {hostRec, aRec};
        boolean joined = waitFor(new Cond() { public boolean ok() { return hostRec.connected.size() == 1 && aRec.connected.size() == 1; } }, ha, haRec, 3000);
        check("host and client A both see the join", joined);
        check("A is told it is player 1", a.getLocalPeerId() == 1);
        check("host sees the name Alice", hostRec.connectedNames.size() == 1 && hostRec.connectedNames.get(0).equals("Alice"));
        check("host is peer 0", host.getLocalPeerId() == 0);

        // ---- state flows both ways, and spoofed ids are overwritten ----
        a.broadcast(new PlayerState(3, 1f, 2f, 3f, 0.5f, 0.1f));
        boolean gotState = waitFor(new Cond() { public boolean ok() { return hostRec.messages.size() == 1; } }, ha, haRec, 3000);
        check("host receives A's state", gotState);
        check("host overwrites the spoofed id 3 with the real id 1", gotState && hostRec.messagePeers.get(0) == 1 && ((PlayerState) hostRec.messages.get(0)).peerId == 1 && ((PlayerState) hostRec.messages.get(0)).x == 1f);
        host.broadcast(new PlayerState(0, 9f, 8f, 7f, 0f, 0f));
        boolean gotBack = waitFor(new Cond() { public boolean ok() { return aRec.messages.size() == 1; } }, ha, haRec, 3000);
        check("A receives the host's state", gotBack && ((PlayerState) aRec.messages.get(0)).x == 9f && aRec.messagePeers.get(0) == 0);

        // ---- wrong protocol version ----
        Socket raw = new Socket("127.0.0.1", port);
        DataOutputStream rawOut = new DataOutputStream(raw.getOutputStream());
        DataInputStream rawIn = new DataInputStream(raw.getInputStream());
        raw.setSoTimeout(3000);
        writeFrame(rawOut, new Hello(99, "old").toBytes());
        NetMessage reply = readFrame(rawIn);
        check("wrong version gets a Reject mentioning the version", reply instanceof Reject && ((Reject) reply).reason.toLowerCase().contains("version"));
        check("and the host then closes the connection", closedByPeer(raw, 3000));
        raw.close();

        // ---- oversized length before the handshake ----
        raw = new Socket("127.0.0.1", port);
        rawOut = new DataOutputStream(raw.getOutputStream());
        rawOut.writeInt(1000000);
        rawOut.flush();
        check("a huge message length gets the connection dropped", closedByPeer(raw, 3000));
        raw.close();

        // ---- not-a-Hello first ----
        raw = new Socket("127.0.0.1", port);
        rawOut = new DataOutputStream(raw.getOutputStream());
        writeFrame(rawOut, new PlayerState(1, 0, 0, 0, 0, 0).toBytes());
        check("sending state before hello gets the connection dropped", closedByPeer(raw, 3000));
        raw.close();
        check("none of those bad connections counted as players", hostRec.connected.size() == 1);

        // ---- NaN position after a valid handshake ----
        raw = new Socket("127.0.0.1", port);
        rawOut = new DataOutputStream(raw.getOutputStream());
        rawIn = new DataInputStream(raw.getInputStream());
        raw.setSoTimeout(3000);
        writeFrame(rawOut, new Hello(NetProtocol.PROTOCOL_VERSION, "nan").toBytes());
        NetMessage w = readFrame(rawIn);
        check("a valid raw client is welcomed as player 2", w instanceof Welcome && ((Welcome) w).peerId == 2);
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        DataOutputStream badOut = new DataOutputStream(bad);
        badOut.writeByte(NetMessage.PLAYER_STATE);
        badOut.writeByte(2);
        badOut.writeFloat(Float.NaN);
        for (int i = 0; i < 4; i++) badOut.writeFloat(0f);
        writeFrame(rawOut, bad.toByteArray());
        check("a NaN position gets that player dropped", closedByPeer(raw, 3000));
        raw.close();
        boolean gone = waitFor(new Cond() { public boolean ok() { return hostRec.disconnected.contains(2); } }, ha, haRec, 3000);
        check("host reports player 2 as gone", gone);

        // ---- a client sending something other than its position ----
        raw = new Socket("127.0.0.1", port);
        rawOut = new DataOutputStream(raw.getOutputStream());
        rawIn = new DataInputStream(raw.getInputStream());
        raw.setSoTimeout(3000);
        writeFrame(rawOut, new Hello(NetProtocol.PROTOCOL_VERSION, "cheat").toBytes());
        readFrame(rawIn); // welcome
        writeFrame(rawOut, new PlayerLeft(1).toBytes());
        check("a client sending PlayerLeft gets dropped", closedByPeer(raw, 3000));
        raw.close();
        waitFor(new Cond() { public boolean ok() { return false; } }, ha, haRec, 300);

        // ---- fill the game: B and C join, D is turned away ----
        final TcpClientTransport b = new TcpClientTransport("Bob");
        final TcpClientTransport c = new TcpClientTransport("Cara");
        final Recorder bRec = new Recorder(), cRec = new Recorder();
        b.connect("127.0.0.1", port);
        waitFor(new Cond() { public boolean ok() { return bRec.connected.size() == 1; } }, new NetTransport[]{host, a, b}, new Recorder[]{hostRec, aRec, bRec}, 3000);
        c.connect("127.0.0.1", port);
        final NetTransport[] all = {host, a, b, c};
        final Recorder[] allRec = {hostRec, aRec, bRec, cRec};
        waitFor(new Cond() { public boolean ok() { return cRec.connected.size() == 1; } }, all, allRec, 3000);
        check("B and C get ids 2 and 3 (freed slot is reused)", b.getLocalPeerId() == 2 && c.getLocalPeerId() == 3);

        final TcpClientTransport d = new TcpClientTransport("Dan");
        final Recorder dRec = new Recorder();
        d.connect("127.0.0.1", port);
        final NetTransport[] allD = {host, a, b, c, d};
        final Recorder[] allDRec = {hostRec, aRec, bRec, cRec, dRec};
        boolean rejected = waitFor(new Cond() { public boolean ok() { return dRec.disconnected.size() == 1; } }, allD, allDRec, 3000);
        check("a fifth player is turned away with a reason", rejected && dRec.disconnectReasons.get(0).toLowerCase().contains("full"));
        check("D never counted as connected on either side", dRec.connected.isEmpty() && !hostRec.connectedNames.contains("Dan"));
        check("D's rejection is reported exactly once", dRec.disconnected.size() == 1);

        // ---- broadcast reaches every client ----
        host.broadcast(new PlayerLeft(1));
        boolean allGot = waitFor(new Cond() { public boolean ok() { return countLeft(aRec) == 1 && countLeft(bRec) == 1 && countLeft(cRec) == 1; } }, allD, allDRec, 3000);
        check("a host broadcast reaches all three clients", allGot);

        // ---- A leaves, a new client takes slot 1 ----
        a.close();
        boolean aGone = waitFor(new Cond() { public boolean ok() { return hostRec.disconnected.contains(1); } }, allD, allDRec, 3000);
        check("host reports A leaving", aGone);
        final TcpClientTransport e = new TcpClientTransport("Eve");
        final Recorder eRec = new Recorder();
        e.connect("127.0.0.1", port);
        final NetTransport[] allE = {host, b, c, e};
        final Recorder[] allERec = {hostRec, bRec, cRec, eRec};
        waitFor(new Cond() { public boolean ok() { return eRec.connected.size() == 1; } }, allE, allERec, 3000);
        check("Eve takes the free slot 1", e.getLocalPeerId() == 1);

        // ---- nothing to a closed/unknown peer should blow up ----
        host.send(1, new PlayerLeft(2));
        host.send(7, new PlayerLeft(2));
        host.send(0, new PlayerLeft(2));
        check("sending to bad peer ids is harmless", true);

        // ---- handshake timeout (takes about 5 seconds) ----
        Socket silent = new Socket("127.0.0.1", port);
        long t0 = System.currentTimeMillis();
        boolean timedOut = closedByPeer(silent, NetProtocol.HANDSHAKE_TIMEOUT_MS + 3000);
        long took = System.currentTimeMillis() - t0;
        check("a silent connection is dropped after the handshake timeout (" + took + " ms)", timedOut && took >= NetProtocol.HANDSHAKE_TIMEOUT_MS - 500);
        silent.close();

        // ---- host shuts down: clients find out ----
        host.close();
        boolean bLost = waitFor(new Cond() { public boolean ok() { return bRec.disconnected.size() == 1 && eRec.disconnected.size() == 1; } }, new NetTransport[]{b, e}, new Recorder[]{bRec, eRec}, 3000);
        check("when the host closes, every client is told once", bLost && bRec.disconnected.get(0) == 0);

        // ---- connecting to nothing ----
        final TcpClientTransport lost = new TcpClientTransport("Nobody");
        final Recorder lostRec = new Recorder();
        lost.connect("127.0.0.1", port);
        boolean noHost = waitFor(new Cond() { public boolean ok() { return lostRec.disconnected.size() == 1; } }, new NetTransport[]{lost}, new Recorder[]{lostRec}, 6000);
        check("connecting to a closed port reports a failure once", noHost && lostRec.disconnected.size() == 1);

        b.close(); c.close(); e.close(); d.close(); lost.close();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static int countLeft(Recorder r) {
        int n = 0;
        for (NetMessage m : r.messages) if (m instanceof PlayerLeft) n++;
        return n;
    }
}
