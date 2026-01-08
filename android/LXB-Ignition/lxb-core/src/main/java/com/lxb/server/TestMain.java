package com.lxb.server;

/**
 * 最小测试版本 - 用于验证 app_process 环境
 */
public class TestMain {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("LXB Server Test - Minimal Version");
        System.out.println("========================================");

        System.out.println("[Test] Java version: " + System.getProperty("java.version"));
        System.out.println("[Test] OS name: " + System.getProperty("os.name"));
        System.out.println("[Test] OS arch: " + System.getProperty("os.arch"));

        System.out.println("[Test] Args count: " + args.length);
        for (int i = 0; i < args.length; i++) {
            System.out.println("[Test] Arg[" + i + "]: " + args[i]);
        }

        System.out.println("[Test] Starting countdown...");
        for (int i = 5; i > 0; i--) {
            System.out.println("[Test] " + i + "...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        System.out.println("[Test] SUCCESS! Java environment is working correctly.");
        System.out.println("========================================");
    }
}
