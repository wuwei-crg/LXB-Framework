package com.lxb.server;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.dispatcher.CommandDispatcher;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.network.TcpServer;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.protocol.FrameCodec;
import com.lxb.server.system.HiddenApiBypass;
import com.lxb.server.system.UiAutomationWrapper;

import java.net.SocketTimeoutException;

/**
 * LXB server main entrypoint.
 *
 * Runtime layers:
 * - Layer 5: System (UiAutomationWrapper, HiddenApiBypass)
 * - Layer 4: Business (ExecutionEngine, PerceptionEngine)
 * - Layer 3: Daemon (CircuitBreaker)
 * - Layer 2: Protocol (FrameCodec)
 * - Layer 1: Network (TcpServer)
 */
public class Main {

    private static final String TAG = "[LXB]";
    private static final int CLIENT_READ_TIMEOUT_MS = 30000;

    public static void main(String[] args) {
        System.out.println(TAG + " =============================================");
        System.out.println(TAG + " LXB Server v1.0 - TCP Control Channel");
        System.out.println(TAG + " =============================================");

        try {
            System.out.println(TAG + " [1/5] Bypassing Hidden API restrictions...");
            boolean bypassSuccess = HiddenApiBypass.bypass();
            if (!bypassSuccess) {
                System.err.println(TAG + " WARNING: Hidden API bypass failed");
            }

            System.out.println(TAG + " [2/5] Initializing UiAutomation...");
            UiAutomationWrapper uiAutomation = new UiAutomationWrapper();
            try {
                uiAutomation.initialize();
            } catch (Exception e) {
                System.err.println(TAG + " WARNING: UiAutomation init failed: " + e.getMessage());
            }

            System.out.println(TAG + " [3/5] Initializing engines...");
            PerceptionEngine perceptionEngine = new PerceptionEngine();
            perceptionEngine.setUiAutomation(uiAutomation);
            perceptionEngine.initialize();

            ExecutionEngine executionEngine = new ExecutionEngine();
            executionEngine.setUiAutomation(uiAutomation);
            executionEngine.initialize();

            System.out.println(TAG + " [4/5] Initializing daemon services...");
            CircuitBreaker circuitBreaker = new CircuitBreaker();
            CommandDispatcher dispatcher = new CommandDispatcher(
                    perceptionEngine,
                    executionEngine,
                    circuitBreaker
            );

            int port = 12345;
            if (args.length > 0) {
                try {
                    port = Integer.parseInt(args[0]);
                } catch (NumberFormatException ignored) {
                }
            }

            System.out.println(TAG + " [5/5] Starting TCP server...");
            TcpServer server = new TcpServer();
            server.listen(port);
            System.out.println(TAG + " Server listening on " + TcpServer.BIND_HOST + ":" + port);
            System.out.println(TAG + " Ready to accept connections.");
            System.out.println(TAG + " =============================================");

            while (true) {
                try {
                    TcpServer.ClientConnection conn = server.accept(1000);
                    String peer = conn.peerTag();
                    System.out.println(TAG + " [TCP] Accepted " + peer);

                    Thread t = new Thread(() -> handleClient(conn, dispatcher), "LxbTcpClient");
                    t.setDaemon(true);
                    t.start();
                } catch (SocketTimeoutException ignore) {
                    // Accept timeout is expected.
                } catch (Exception e) {
                    System.err.println(TAG + " [Loop] Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            System.err.println(TAG + " FATAL: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void handleClient(TcpServer.ClientConnection conn, CommandDispatcher dispatcher) {
        try (TcpServer.ClientConnection c = conn) {
            String peer = c.peerTag();
            while (true) {
                byte[] rawFrame;
                try {
                    rawFrame = c.readFrame(CLIENT_READ_TIMEOUT_MS);
                } catch (SocketTimeoutException timeout) {
                    // Keep waiting on this connection.
                    continue;
                }

                if (rawFrame == null) {
                    System.out.println(TAG + " [TCP] Client closed: " + peer);
                    return;
                }

                FrameCodec.DecodedFrame decoded;
                try {
                    decoded = FrameCodec.decode(rawFrame);
                } catch (FrameCodec.ProtocolException e) {
                    System.err.println(TAG + " [Frame] Protocol error: " + e.getMessage());
                    continue;
                } catch (FrameCodec.CRCException e) {
                    System.err.println(TAG + " [Frame] CRC error: " + e.getMessage());
                    continue;
                }

                FrameCodec.FrameInfo info = new FrameCodec.FrameInfo();
                info.magic = FrameCodec.MAGIC;
                info.version = decoded.version;
                info.seq = decoded.seq;
                info.cmd = decoded.cmd;
                info.payloadLength = decoded.payload.length;

                byte[] response = dispatcher.dispatch(info, decoded.payload, peer);
                c.writeFrame(response);
            }
        } catch (Exception e) {
            System.err.println(TAG + " [TCP] Client handler error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
