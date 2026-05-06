package com.lxb.server.network;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;

public class TcpServerTest {
    @Test
    public void listen_bindsToLoopbackOnly() throws Exception {
        TcpServer server = new TcpServer();
        try {
            server.listen(0);

            Field field = TcpServer.class.getDeclaredField("serverSocket");
            field.setAccessible(true);
            ServerSocket socket = (ServerSocket) field.get(server);

            Assert.assertEquals(InetAddress.getByName(TcpServer.BIND_HOST), socket.getInetAddress());
        } finally {
            server.close();
        }
    }
}
