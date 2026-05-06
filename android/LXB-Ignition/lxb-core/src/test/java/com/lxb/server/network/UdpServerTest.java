package com.lxb.server.network;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpServerTest {
    @Test
    public void listen_bindsToLoopbackOnly() throws Exception {
        UdpServer server = new UdpServer();
        try {
            server.listen(0);

            Field field = UdpServer.class.getDeclaredField("socket");
            field.setAccessible(true);
            DatagramSocket socket = (DatagramSocket) field.get(server);

            Assert.assertEquals(InetAddress.getByName(UdpServer.BIND_HOST), socket.getLocalAddress());
        } finally {
            server.close();
        }
    }
}
