package com.lxb.server.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * 协议帧格式编解码
 * 所有多字节整数必须使用大端序（网络字节序）
 */
public class ProtocolFrame {

    // 帧头魔数
    public static final short MAGIC = (short) 0xAA55;

    /**
     * 编码帧头 (14 字节)
     * @param channel 通道号
     * @param cmd 命令号
     * @param seq 序列号
     * @param payloadLength 负载长度
     * @return 帧头字节数组
     */
    public static byte[] encodeHeader(byte channel, byte cmd, int seq, short payloadLength) {
        ByteBuffer buffer = ByteBuffer.allocate(14);
        buffer.order(ByteOrder.BIG_ENDIAN);  // 强制大端序

        buffer.putShort(MAGIC);           // 0-1: 魔数
        buffer.put(channel);              // 2: 通道
        buffer.put(cmd);                  // 3: 命令
        buffer.putInt(seq);               // 4-7: 序列号
        buffer.putShort(payloadLength);   // 8-9: 负载长度
        buffer.putInt(0);                 // 10-13: CRC32 (稍后填充)

        return buffer.array();
    }

    /**
     * 计算 CRC32 校验和
     * @param header 帧头 (14 字节)
     * @param payload 负载数据
     * @return CRC32 值
     */
    public static long calculateCRC32(byte[] header, byte[] payload) {
        CRC32 crc32 = new CRC32();

        // CRC32 覆盖前 10 字节 (魔数 + 通道 + 命令 + 序列号 + 长度)
        crc32.update(header, 0, 10);

        // CRC32 覆盖整个 payload
        if (payload != null && payload.length > 0) {
            crc32.update(payload);
        }

        return crc32.getValue();
    }

    /**
     * 填充 CRC32 到帧头
     * @param header 帧头 (14 字节)
     * @param crc32Value CRC32 值
     */
    public static void fillCRC32(byte[] header, long crc32Value) {
        ByteBuffer buffer = ByteBuffer.wrap(header, 10, 4);
        buffer.order(ByteOrder.BIG_ENDIAN);  // 强制大端序
        buffer.putInt((int) crc32Value);
    }

    /**
     * 解码帧头
     * @param data 原始数据 (至少 14 字节)
     * @return 帧信息对象
     */
    public static FrameInfo decodeHeader(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);  // 强制大端序

        FrameInfo info = new FrameInfo();
        info.magic = buffer.getShort();
        info.channel = buffer.get();
        info.cmd = buffer.get();
        info.seq = buffer.getInt();
        info.payloadLength = buffer.getShort();
        info.crc32 = buffer.getInt();

        return info;
    }

    /**
     * 打包完整帧（包括 Payload 和 CRC32）
     * @param seq 序列号
     * @param cmd 命令号
     * @param payload 负载数据
     * @return 完整帧字节数组
     */
    public static byte[] pack(int seq, byte cmd, byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }

        // 1. 编码帧头（10 字节，不含 CRC32）
        byte[] header = encodeHeader((byte) 0, cmd, seq, (short) payload.length);

        // 2. 计算 CRC32（覆盖 header[0-9] + payload）
        long crc32 = calculateCRC32(header, payload);

        // 3. 拼接完整帧: Header[10B] + Payload[N] + CRC32[4B]
        ByteBuffer buffer = ByteBuffer.allocate(14 + payload.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 写入帧头前 10 字节（Magic + Version + Seq + Cmd + Length）
        buffer.put(header, 0, 10);

        // 写入 Payload
        buffer.put(payload);

        // 写入 CRC32
        buffer.putInt((int) crc32);

        return buffer.array();
    }

    /**
     * 帧信息结构
     */
    public static class FrameInfo {
        public short magic;
        public byte channel;
        public byte cmd;
        public int seq;
        public short payloadLength;
        public int crc32;
    }
}
