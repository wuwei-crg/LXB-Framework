package com.lxb.server;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.daemon.SequenceTracker;
import com.lxb.server.dispatcher.CommandDispatcher;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.network.UdpServer;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.protocol.ProtocolFrame;

import java.net.SocketTimeoutException;
import java.util.Arrays;

/**
 * LXB Server 主入口
 * 通过 app_process 启动: app_process -Djava.class.path=/data/local/tmp/lxb-core.jar /system/bin com.lxb.server.Main
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("[LXB] Server starting...");

        try {
            // 1. 初始化引擎
            PerceptionEngine perceptionEngine = new PerceptionEngine();
            perceptionEngine.initialize();

            ExecutionEngine executionEngine = new ExecutionEngine();
            executionEngine.initialize();

            // 2. 初始化守护层
            SequenceTracker sequenceTracker = new SequenceTracker();
            CircuitBreaker circuitBreaker = new CircuitBreaker();

            // 3. 初始化分派器
            CommandDispatcher dispatcher = new CommandDispatcher(
                perceptionEngine,
                executionEngine,
                sequenceTracker,
                circuitBreaker
            );

            // 4. 启动 UDP 服务
            UdpServer server = new UdpServer();
            server.listen(12345);
            System.out.println("[LXB] Server listening on port 12345");

            // 5. 主循环
            while (true) {
                try {
                    // 接收帧（1 秒超时）
                    UdpServer.ReceivedFrame frame = server.receive(1000);

                    // 日志记录
                    System.out.println("[UDP] Received " + frame.data.length +
                            " bytes from " + frame.address.getHostAddress() +
                            ":" + frame.port);

                    // 解析帧头
                    ProtocolFrame.FrameInfo info = ProtocolFrame.decodeHeader(frame.data);

                    // 验证魔数
                    if (info.magic != (short) 0xAA55) {
                        System.err.println("[Frame] Invalid magic: 0x" +
                                String.format("%04X", info.magic));
                        continue;
                    }

                    // TODO: 验证 CRC32

                    // 提取 Payload
                    byte[] payload = Arrays.copyOfRange(
                        frame.data, 14, 14 + info.payloadLength
                    );

                    // 日志帧信息
                    System.out.println("[Frame] seq=" + info.seq +
                            ", cmd=0x" + String.format("%02X", info.cmd) +
                            ", len=" + info.payloadLength);

                    // 分派处理
                    byte[] response = dispatcher.dispatch(info, payload);

                    // 发送响应
                    server.send(frame.address, frame.port, response);

                    System.out.println("[UDP] Sent " + response.length +
                            " bytes response");

                } catch (SocketTimeoutException e) {
                    // 正常超时，继续循环
                } catch (Exception e) {
                    System.err.println("[LXB] Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            System.err.println("[LXB] Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
