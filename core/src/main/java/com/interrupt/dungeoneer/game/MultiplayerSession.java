package com.interrupt.dungeoneer.game;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.net.NetListener;
import com.interrupt.dungeoneer.net.NetMessage;
import com.interrupt.dungeoneer.net.NetMessage.PlayerLeft;
import com.interrupt.dungeoneer.net.NetMessage.PlayerState;
import com.interrupt.dungeoneer.net.NetTransport;
import com.interrupt.dungeoneer.net.TcpClientTransport;
import com.interrupt.dungeoneer.net.TcpHostTransport;

/**
 * The game's side of a multiplayer session: it owns the network transport, sends this player's
 * position and keeps track of where everyone else is.
 *
 * There is at most one session, in {@link #current}. It stays null in a normal single player game,
 * and every hook into the game checks for that, so single player is untouched.
 *
 * Everything here runs on the game thread: pollCurrent() from the render loop and tick() from the
 * game tick. The network threads only queue events for poll() to hand over.
 */
public class MultiplayerSession implements NetListener {
    /** The running session, or null when playing alone. */
    public static MultiplayerSession current = null;

    /** Game ticks between position updates. A tick is 1/60 of a second, so 3 is 20 updates a second. */
    private static final float SEND_INTERVAL_TICKS = 3f;

    /** How often the console says where the other players are, in game ticks (every 3 seconds). */
    private static final float REPORT_INTERVAL_TICKS = 180f;

    private final boolean host;
    private final NetTransport transport;

    /** Ids of the players currently connected to this host. Not used on a client. */
    private final Set<Integer> connectedPeers = new HashSet<Integer>();

    /** The latest known position of every other player, by player id. */
    private final Map<Integer, PlayerState> otherPlayers = new HashMap<Integer, PlayerState>();

    private float sendTimer = 0f;
    private float reportTimer = 0f;

    private MultiplayerSession(boolean host, NetTransport transport) {
        this.host = host;
        this.transport = transport;
    }

    /** Starts hosting. Other players join with the address of this computer and the same port. */
    public static void startHost(int port) throws IOException {
        shutdown();

        TcpHostTransport transport = new TcpHostTransport(port);
        current = new MultiplayerSession(true, transport);
        log("Hosting a game on port " + transport.getLocalPort() + " (up to 4 players, you included).");
    }

    /** Starts joining a host. The connection is made in the background, so the game keeps running. */
    public static void startClient(String address, int port, String name) {
        shutdown();

        TcpClientTransport transport = new TcpClientTransport(name);
        current = new MultiplayerSession(false, transport);
        transport.connect(address, port);
        log("Joining " + address + ":" + port + " as " + name + "...");
    }

    /** Hands over whatever has arrived from the network. Called every frame by the application. */
    public static void pollCurrent() {
        MultiplayerSession session = current;
        if (session != null) session.transport.poll(session);
    }

    /** Leaves the session, if there is one. */
    public static void shutdown() {
        MultiplayerSession session = current;
        current = null;

        if (session != null) session.transport.close();
    }

    public boolean isHost() {
        return host;
    }

    /** This machine's player id: 0 for the host, 1 to 3 for a client, -1 while a client is still joining. */
    public int getLocalPeerId() {
        return transport.getLocalPeerId();
    }

    /** The latest known position of every other player, by player id. Do not modify. */
    public Map<Integer, PlayerState> getOtherPlayers() {
        return otherPlayers;
    }

    /** Called from the game tick: sends this player's position a few times a second. */
    public void tick(Game game, float delta) {
        int localId = transport.getLocalPeerId();
        if (localId < 0) return; // a client that has not been let in yet

        sendTimer += delta;
        if (sendTimer < SEND_INTERVAL_TICKS) return;
        sendTimer = 0f;

        Player player = game.player;
        if (player != null && isFinite(player.x) && isFinite(player.y) && isFinite(player.z)
                && isFinite(player.rot) && isFinite(player.yrot)) {
            transport.broadcast(new PlayerState(localId, player.x, player.y, player.z, player.rot, player.yrot));
        }

        reportTimer += SEND_INTERVAL_TICKS;
        if (reportTimer >= REPORT_INTERVAL_TICKS) {
            reportTimer = 0f;
            for (PlayerState state : otherPlayers.values()) {
                log("Player " + state.peerId + " is at " + format(state.x) + ", " + format(state.y));
            }
        }
    }

    @Override
    public void onConnected(int peerId, String name) {
        if (host) {
            connectedPeers.add(peerId);
            log(name + " joined as player " + peerId + ".");
        }
        else {
            log("Connected to the host. You are player " + transport.getLocalPeerId() + ".");
        }
    }

    @Override
    public void onMessage(int peerId, NetMessage message) {
        if (message instanceof PlayerState) {
            PlayerState state = (PlayerState) message;
            if (state.peerId == transport.getLocalPeerId()) return; // never track ourselves

            otherPlayers.put(state.peerId, state);

            // The host passes each player's position on to everyone else
            if (host) {
                for (Integer other : connectedPeers) {
                    if (other != peerId) transport.send(other, state);
                }
            }
        }
        else if (message instanceof PlayerLeft) {
            PlayerLeft left = (PlayerLeft) message;
            otherPlayers.remove(left.peerId);
            log("Player " + left.peerId + " left the game.");
        }
        // Level data is not handled yet
    }

    @Override
    public void onDisconnected(int peerId, String reason) {
        if (host) {
            connectedPeers.remove(peerId);
            otherPlayers.remove(peerId);
            transport.broadcast(new PlayerLeft(peerId));
            log("Player " + peerId + " left (" + reason + ").");
        }
        else {
            log("Left the game: " + reason);
            otherPlayers.clear();
            shutdown();
        }
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static String format(float value) {
        return String.format("%.1f", value);
    }

    /** Writes to the game's log, or to the console if the game has not started its log yet. */
    static void log(String text) {
        if (Gdx.app != null) Gdx.app.log("DelverNet", text);
        else System.out.println("[DelverNet] " + text);
    }
}
