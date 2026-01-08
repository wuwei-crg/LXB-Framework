package com.lxb.server;

/**
 * 逐步测试 - 找出失败点
 */
public class DebugMain {
    public static void main(String[] args) {
        try {
            System.out.println("Step 1: Starting...");
            System.out.flush();

            System.out.println("Step 2: Java version check...");
            String version = System.getProperty("java.version");
            System.out.println("  Java version: " + version);
            System.out.flush();

            System.out.println("Step 3: Testing String operations...");
            String test = "Hello from LXB Server";
            System.out.println("  String: " + test);
            System.out.flush();

            System.out.println("Step 4: Testing Thread.sleep...");
            Thread.sleep(100);
            System.out.println("  Sleep OK");
            System.out.flush();

            System.out.println("Step 5: Testing array operations...");
            byte[] data = new byte[100];
            data[0] = (byte) 0xAA;
            System.out.println("  Array OK, first byte: 0x" + Integer.toHexString(data[0] & 0xFF));
            System.out.flush();

            System.out.println("Step 6: Testing exception handling...");
            try {
                throw new RuntimeException("Test exception");
            } catch (RuntimeException e) {
                System.out.println("  Exception caught: " + e.getMessage());
            }
            System.out.flush();

            System.out.println("Step 7: All basic tests passed!");
            System.out.flush();

            System.out.println("========================================");
            System.out.println("SUCCESS - Environment is working!");
            System.out.println("========================================");
            System.out.flush();

        } catch (Throwable t) {
            System.err.println("FATAL ERROR at some step!");
            System.err.println("Error: " + t.getClass().getName());
            System.err.println("Message: " + t.getMessage());
            t.printStackTrace();
            System.err.flush();
            System.exit(1);
        }
    }
}
