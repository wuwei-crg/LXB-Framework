package com.lxb.server.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/**
 * UDP 服务器组件
 * 职责: 管理 DatagramSocket 生命周期，提供帧收发封装
 */
public class UdpServer {
    private DatagramSocket socket;
    private boolean running = false;

    /**
     * 监听指定端口
     */
    public void listen(int port) throws IOException {
        socket = new DatagramSocket(port);
        socket.setReceiveBufferSize(65536);  // 协议要求
        running = true;
        System.out.println("[UDP] Listening on port " + port);
    }

    /**
     * 接收数据帧（带超时）
     */
    public ReceivedFrame receive(int timeout) throws IOException {
        if (!running) {
            throw new IllegalStateException("Server not started");
        }

        byte[] buffer = new byte[65549];  // MAX_FRAME_SIZE
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        socket.setSoTimeout(timeout);
        socket.receive(packet);

        return new ReceivedFrame(
            Arrays.copyOf(packet.getData(), packet.getLength()),
            packet.getAddress(),
            packet.getPort()
        );
    }

    /**
     * 发送数据帧
     */
    public void send(InetAddress addr, int port, byte[] data) throws IOException {
        if (!running) {
            throw new IllegalStateException("Server not started");
        }

        DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
        socket.send(packet);
    }

    /**
     * 关闭服务器
     */
    public void close() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * 接收到的帧信息
     */
    public static class ReceivedFrame {
        public final byte[] data;
        public final InetAddress address;
        public final int port;

        public ReceivedFrame(byte[] data, InetAddress address, int port) {
            this.data = data;
            this.address = address;
            this.port = port;
        }
    }
}
