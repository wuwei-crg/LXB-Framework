package com.lxb.server.daemon;

import java.util.LinkedHashSet;

/**
 * 序列号去重追踪器
 * 维护接收窗口，防止重复命令执行
 */
public class SequenceTracker {

    private final LinkedHashSet<Integer> receiveWindow;
    private static final int WINDOW_SIZE = 100;

    public SequenceTracker() {
        this.receiveWindow = new LinkedHashSet<>(WINDOW_SIZE);
    }

    /**
     * 检查序列号是否重复
     * @param seq 序列号
     * @return true=重复, false=新序列
     */
    public synchronized boolean isDuplicate(int seq) {
        if (receiveWindow.contains(seq)) {
            System.out.println("[Daemon] Duplicate seq detected: " + seq);
            return true;
        }

        // 添加到接收窗口
        receiveWindow.add(seq);

        // 超过窗口大小时移除最老的
        if (receiveWindow.size() > WINDOW_SIZE) {
            Integer oldest = receiveWindow.iterator().next();
            receiveWindow.remove(oldest);
        }

        return false;
    }

    /**
     * 清空接收窗口
     */
    public synchronized void clear() {
        receiveWindow.clear();
    }
}
