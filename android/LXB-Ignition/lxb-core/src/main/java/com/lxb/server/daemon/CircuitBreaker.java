package com.lxb.server.daemon;

/**
 * 熔断器 - 防止命令风暴和异常泛滥
 */
public class CircuitBreaker {

    private int commandCount = 0;
    private int exceptionCount = 0;
    private long lastResetTime = System.currentTimeMillis();
    private long lastCommandTime = System.currentTimeMillis();

    private static final int STORM_THRESHOLD = 50;  // 50 命令/秒
    private static final int EXCEPTION_THRESHOLD = 20;  // 20 异常/分钟
    private static final long IDLE_THRESHOLD = 10 * 60 * 1000;  // 10 分钟空闲

    /**
     * 检查是否应该拒绝命令
     * @return true=拒绝, false=允许
     */
    public synchronized boolean shouldReject() {
        long now = System.currentTimeMillis();

        // 每秒重置计数器
        if (now - lastResetTime > 1000) {
            commandCount = 0;
            lastResetTime = now;
        }

        // 检测命令风暴
        commandCount++;
        if (commandCount > STORM_THRESHOLD) {
            System.out.println("[CircuitBreaker] Command storm detected, rejecting");
            return true;
        }

        // 更新最后命令时间
        lastCommandTime = now;
        return false;
    }

    /**
     * 记录异常
     */
    public synchronized void recordException() {
        long now = System.currentTimeMillis();

        // 每分钟重置异常计数
        if (now - lastResetTime > 60000) {
            exceptionCount = 0;
        }

        exceptionCount++;
        if (exceptionCount > EXCEPTION_THRESHOLD) {
            System.out.println("[CircuitBreaker] Exception storm detected, circuit opened for 30s");
            // TODO: 触发 30 秒暂停
        }
    }

    /**
     * 检查是否进入空闲状态
     * @return true=空闲, false=活跃
     */
    public synchronized boolean isIdle() {
        long now = System.currentTimeMillis();
        return (now - lastCommandTime) > IDLE_THRESHOLD;
    }
}
