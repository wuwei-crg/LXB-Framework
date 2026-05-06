package com.lxb.server.network;

import com.lxb.server.protocol.FrameCodec;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * TCP server wrapper for framed LXB-Link traffic.
 *
 * Transport responsibilities:
 * - Accept client sockets
 * - Read exactly one protocol frame from stream
 * - Write encoded response frame back to client
 */
public class TcpServer {
    public static final String BIND_HOST = "127.0.0.1";

    private ServerSocket serverSocket;
    private boolean running = false;

    public void listen(int port) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(BIND_HOST), port));
        running = true;
        System.out.println("[TCP] Listening on " + BIND_HOST + ":" + serverSocket.getLocalPort());
    }

    public ClientConnection accept(int timeoutMs) throws IOException {
        if (!running || serverSocket == null) {
            throw new IllegalStateException("Server not started");
        }
        serverSocket.setSoTimeout(timeoutMs);
        Socket socket = serverSocket.accept();
        socket.setTcpNoDelay(true);
        return new ClientConnection(socket);
    }

    public void close() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static final class ClientConnection implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        public ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        public String peerTag() {
            return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        /**
         * Read one full frame from TCP stream.
         *
         * @return Complete frame bytes, or null when peer closed cleanly.
         */
        public byte[] readFrame(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs);

            byte[] prefix = new byte[3];
            int first = in.read();
            if (first < 0) {
                return null;
            }
            prefix[0] = (byte) first;
            readFully(in, prefix, 1, 2); // magic[1] + version[1]

            int headerSize;
            try {
                headerSize = FrameCodec.headerSizeForVersion(prefix[2]);
            } catch (FrameCodec.ProtocolException e) {
                throw new IOException("Invalid frame version in stream header", e);
            }

            byte[] header = new byte[headerSize];
            System.arraycopy(prefix, 0, header, 0, 3);
            readFully(in, header, 3, headerSize - 3);

            int payloadLen;
            try {
                payloadLen = FrameCodec.parsePayloadLengthFromHeader(header);
            } catch (FrameCodec.ProtocolException e) {
                throw new IOException("Invalid stream frame header", e);
            }

            int totalLen = headerSize + payloadLen + FrameCodec.CRC_SIZE;
            byte[] frame = new byte[totalLen];
            System.arraycopy(header, 0, frame, 0, headerSize);
            readFully(in, frame, headerSize, payloadLen + FrameCodec.CRC_SIZE);
            return frame;
        }

        public void writeFrame(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while reading frame");
            }
            read += n;
        }
    }
}
