// IShizukuService.aidl
package com.example.lxb_ignition;

interface IShizukuService {

    /**
     * 接收 JAR 字节并写入目标路径（shell 进程有写权限）。
     * @return true 表示写入成功
     */
    boolean deployJar(in byte[] jarBytes, String destPath);

    /**
     * 启动 app_process 服务。
     * @return "OK" 开头表示成功，"ERROR" 开头表示失败（含原因）
     */
    String startServer(String jarPath, String serverClass, int port);
    String startServerWithJvmOpts(String jarPath, String serverClass, int port, String jvmOpts);

    void stopServer(String serverClass);

    boolean isRunning(String serverClass);

    String readLogPart(long fromByte, int maxBytes);

    oneway void destroy();
}
