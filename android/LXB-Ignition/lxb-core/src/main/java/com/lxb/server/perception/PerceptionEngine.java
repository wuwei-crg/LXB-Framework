package com.lxb.server.perception;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import com.lxb.server.network.UdpServer;
import com.lxb.server.protocol.FrameCodec;
import com.lxb.server.protocol.StringPoolConstants;
import com.lxb.server.system.UiAutomationWrapper;

/**
 * 感知引擎 - 负责 UI 树提取和节点查找
 *
 * 处理的命令:
 * - 0x30 GET_ACTIVITY: 获取当前 Activity
 * - 0x31 DUMP_HIERARCHY: 获取 UI 树
 * - 0x32 FIND_NODE: 查找节点
 * - 0x36 GET_SCREEN_STATE: 获取屏幕状态
 * - 0x37 GET_SCREEN_SIZE: 获取屏幕尺寸
 */
public class PerceptionEngine {

    private static final String TAG = "[LXB][Perception]";

    // 系统层依赖
    private UiAutomationWrapper uiAutomation;

    // 反射缓存
    private Method getChildCountMethod;
    private Method getChildMethod;
    private Method getClassNameMethod;
    private Method getTextMethod;
    private Method getContentDescriptionMethod;
    private Method getViewIdResourceNameMethod;
    private Method getBoundsInScreenMethod;
    private Method isClickableMethod;
    private Method isVisibleToUserMethod;
    private Method isEnabledMethod;
    private Method isFocusedMethod;
    private Method isScrollableMethod;
    private Method isEditableMethod;
    private Method isCheckableMethod;
    private Method isCheckedMethod;
    private Method recycleMethod;
    private Class<?> rectClass;

    // 查找类型常量
    private static final int MATCH_EXACT_TEXT = 0;
    private static final int MATCH_CONTAINS_TEXT = 1;
    private static final int MATCH_REGEX = 2;
    private static final int MATCH_RESOURCE_ID = 3;
    private static final int MATCH_CLASS = 4;
    private static final int MATCH_DESCRIPTION = 5;

    // 返回模式常量
    private static final int RETURN_COORDS = 0;
    private static final int RETURN_BOUNDS = 1;
    private static final int RETURN_FULL = 2;

    /**
     * 设置 UiAutomation 依赖
     *
     * @param wrapper UiAutomationWrapper 实例
     */
    public void setUiAutomation(UiAutomationWrapper wrapper) {
        this.uiAutomation = wrapper;
    }

    /**
     * 初始化感知引擎
     */
    public void initialize() {
        System.out.println(TAG + " Engine initialized");
        if (uiAutomation == null) {
            System.err.println(TAG + " WARNING: UiAutomation not set!");
        }

        // 初始化反射方法缓存
        try {
            initReflectionCache();
        } catch (Exception e) {
            System.err.println(TAG + " Failed to init reflection cache: " + e.getMessage());
        }
    }

    /**
     * 初始化反射方法缓存
     */
    private void initReflectionCache() throws Exception {
        Class<?> nodeClass = Class.forName("android.view.accessibility.AccessibilityNodeInfo");
        rectClass = Class.forName("android.graphics.Rect");

        getChildCountMethod = nodeClass.getMethod("getChildCount");
        getChildMethod = nodeClass.getMethod("getChild", int.class);
        getClassNameMethod = nodeClass.getMethod("getClassName");
        getTextMethod = nodeClass.getMethod("getText");
        getContentDescriptionMethod = nodeClass.getMethod("getContentDescription");
        getViewIdResourceNameMethod = nodeClass.getMethod("getViewIdResourceName");
        getBoundsInScreenMethod = nodeClass.getMethod("getBoundsInScreen", rectClass);
        isClickableMethod = nodeClass.getMethod("isClickable");
        isVisibleToUserMethod = nodeClass.getMethod("isVisibleToUser");
        isEnabledMethod = nodeClass.getMethod("isEnabled");
        isFocusedMethod = nodeClass.getMethod("isFocused");
        isScrollableMethod = nodeClass.getMethod("isScrollable");
        isCheckableMethod = nodeClass.getMethod("isCheckable");
        isCheckedMethod = nodeClass.getMethod("isChecked");
        recycleMethod = nodeClass.getMethod("recycle");

        // isEditable 在 API 18+ 才有
        try {
            isEditableMethod = nodeClass.getMethod("isEditable");
        } catch (NoSuchMethodException e) {
            isEditableMethod = null;
        }

        System.out.println(TAG + " Reflection cache initialized");
    }

    /**
     * 处理 GET_ACTIVITY 命令 (0x30)
     *
     * 响应格式: success[1B] + pkg_len[2B] + pkg[UTF-8] + act_len[2B] + act[UTF-8]
     *
     * @return 响应数据
     */
    public byte[] handleGetActivity() {
        System.out.println(TAG + " GET_ACTIVITY");

        String packageName = "";
        String activityName = "";

        if (uiAutomation != null) {
            String[] result = uiAutomation.getCurrentActivity();
            packageName = result[0];
            activityName = result[1];
        }

        System.out.println(TAG + " Activity: " + packageName + "/" + activityName);

        ByteBuffer buffer = ByteBuffer.allocate(1024);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) 0x01);  // success

        byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
        buffer.putShort((short) pkgBytes.length);
        buffer.put(pkgBytes);

        byte[] actBytes = activityName.getBytes(StandardCharsets.UTF_8);
        buffer.putShort((short) actBytes.length);
        buffer.put(actBytes);

        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    /**
     * 处理 GET_SCREEN_STATE 命令 (0x36)
     *
     * 响应格式: status[1B] + state[1B]
     *   state: 0=off, 1=on_unlocked, 2=on_locked
     *
     * @return 响应数据 (2 字节)
     */
    public byte[] handleGetScreenState() {
        System.out.println(TAG + " GET_SCREEN_STATE");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00, 0x00};
        }

        int state = uiAutomation.getScreenState();
        System.out.println(TAG + " Screen state: " + state);

        return new byte[]{0x01, (byte) state};
    }

    /**
     * 处理 GET_SCREEN_SIZE 命令 (0x37)
     *
     * 响应格式: status[1B] + width[2B] + height[2B] + density[2B]
     *
     * @return 响应数据 (7 字节)
     */
    public byte[] handleGetScreenSize() {
        System.out.println(TAG + " GET_SCREEN_SIZE");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        }

        int[] size = uiAutomation.getScreenSize();
        int width = size[0];
        int height = size[1];
        int density = size[2];

        System.out.println(TAG + " Screen size: " + width + "x" + height + " @" + density + "dpi");

        ByteBuffer buffer = ByteBuffer.allocate(7);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 0x01);
        buffer.putShort((short) width);
        buffer.putShort((short) height);
        buffer.putShort((short) density);

        return buffer.array();
    }

    /**
     * 处理 FIND_NODE 命令 (0x32)
     *
     * 请求格式:
     *   match_type[1B] + return_mode[1B] + multi_match[1B] +
     *   timeout_ms[2B] + query_len[2B] + query[UTF-8]
     *
     * 响应格式 (RETURN_COORDS):
     *   status[1B] + count[1B] + coords[4B * count]
     *
     * @param payload 请求负载
     * @return 响应数据
     */
    public byte[] handleFindNode(byte[] payload) {
        if (payload.length < 7) {
            System.err.println(TAG + " FIND_NODE payload too short: " + payload.length);
            return new byte[]{0x00, 0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int matchType = buffer.get() & 0xFF;
        int returnMode = buffer.get() & 0xFF;
        int multiMatch = buffer.get() & 0xFF;
        int timeoutMs = buffer.getShort() & 0xFFFF;
        int queryLen = buffer.getShort() & 0xFFFF;

        if (payload.length < 7 + queryLen) {
            System.err.println(TAG + " FIND_NODE query truncated");
            return new byte[]{0x00, 0x00};
        }

        byte[] queryBytes = new byte[queryLen];
        buffer.get(queryBytes);
        String query = new String(queryBytes, StandardCharsets.UTF_8);

        System.out.println(TAG + " FIND_NODE: query=\"" + query + "\" matchType=" + matchType +
                " returnMode=" + returnMode + " multiMatch=" + (multiMatch != 0));

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00, 0x00};
        }

        // 获取根节点
        Object rootNode = uiAutomation.getRootNode();
        if (rootNode == null) {
            System.err.println(TAG + " Failed to get root node");
            return new byte[]{0x00, 0x00};  // status=0 (not found)
        }

        // BFS 查找节点
        List<int[]> results = new ArrayList<>();
        Pattern regexPattern = (matchType == MATCH_REGEX) ? Pattern.compile(query) : null;

        try {
            findNodesBFS(rootNode, query, matchType, regexPattern, multiMatch != 0, results);
        } catch (Exception e) {
            System.err.println(TAG + " FIND_NODE error: " + e.getMessage());
        }

        System.out.println(TAG + " FIND_NODE found " + results.size() + " matches");

        // 构建响应
        if (results.isEmpty()) {
            return new byte[]{0x00, 0x00};  // status=0, count=0
        }

        // 根据返回模式构建响应
        if (returnMode == RETURN_COORDS) {
            ByteBuffer respBuffer = ByteBuffer.allocate(2 + results.size() * 4);
            respBuffer.order(ByteOrder.BIG_ENDIAN);
            respBuffer.put((byte) 0x01);  // status=success
            respBuffer.put((byte) results.size());

            for (int[] bounds : results) {
                // 计算中心点
                int centerX = (bounds[0] + bounds[2]) / 2;
                int centerY = (bounds[1] + bounds[3]) / 2;
                respBuffer.putShort((short) centerX);
                respBuffer.putShort((short) centerY);
            }

            return respBuffer.array();
        } else if (returnMode == RETURN_BOUNDS) {
            ByteBuffer respBuffer = ByteBuffer.allocate(2 + results.size() * 8);
            respBuffer.order(ByteOrder.BIG_ENDIAN);
            respBuffer.put((byte) 0x01);
            respBuffer.put((byte) results.size());

            for (int[] bounds : results) {
                respBuffer.putShort((short) bounds[0]);  // left
                respBuffer.putShort((short) bounds[1]);  // top
                respBuffer.putShort((short) bounds[2]);  // right
                respBuffer.putShort((short) bounds[3]);  // bottom
            }

            return respBuffer.array();
        }

        // RETURN_FULL - 暂不实现
        return new byte[]{0x01, (byte) results.size()};
    }

    /**
     * BFS 遍历查找节点
     */
    private void findNodesBFS(Object root, String query, int matchType,
                              Pattern regexPattern, boolean multiMatch,
                              List<int[]> results) throws Exception {
        if (getChildCountMethod == null) {
            System.err.println(TAG + " Reflection cache not initialized");
            return;
        }

        Queue<Object> queue = new LinkedList<>();
        queue.offer(root);

        while (!queue.isEmpty()) {
            Object node = queue.poll();
            if (node == null) continue;

            // 检查当前节点是否匹配
            if (matchesQuery(node, query, matchType, regexPattern)) {
                int[] bounds = getNodeBounds(node);
                if (bounds != null) {
                    results.add(bounds);
                    if (!multiMatch) {
                        return;  // 只需要第一个匹配
                    }
                }
            }

            // 添加子节点到队列
            int childCount = (Integer) getChildCountMethod.invoke(node);
            for (int i = 0; i < childCount; i++) {
                Object child = getChildMethod.invoke(node, i);
                if (child != null) {
                    queue.offer(child);
                }
            }
        }
    }

    /**
     * 检查节点是否匹配查询条件
     */
    private boolean matchesQuery(Object node, String query, int matchType,
                                 Pattern regexPattern) throws Exception {
        String target = null;

        switch (matchType) {
            case MATCH_EXACT_TEXT:
            case MATCH_CONTAINS_TEXT:
            case MATCH_REGEX:
                CharSequence text = (CharSequence) getTextMethod.invoke(node);
                target = text != null ? text.toString() : "";
                break;

            case MATCH_RESOURCE_ID:
                target = (String) getViewIdResourceNameMethod.invoke(node);
                if (target == null) target = "";
                break;

            case MATCH_CLASS:
                CharSequence className = (CharSequence) getClassNameMethod.invoke(node);
                target = className != null ? className.toString() : "";
                break;

            case MATCH_DESCRIPTION:
                CharSequence desc = (CharSequence) getContentDescriptionMethod.invoke(node);
                target = desc != null ? desc.toString() : "";
                break;

            default:
                return false;
        }

        switch (matchType) {
            case MATCH_EXACT_TEXT:
                return query.equals(target);

            case MATCH_CONTAINS_TEXT:
                return target.contains(query);

            case MATCH_REGEX:
                return regexPattern != null && regexPattern.matcher(target).find();

            case MATCH_RESOURCE_ID:
            case MATCH_CLASS:
            case MATCH_DESCRIPTION:
                return target.contains(query);

            default:
                return false;
        }
    }

    /**
     * 获取节点边界
     */
    private int[] getNodeBounds(Object node) throws Exception {
        Object rect = rectClass.newInstance();
        getBoundsInScreenMethod.invoke(node, rect);

        int left = rectClass.getField("left").getInt(rect);
        int top = rectClass.getField("top").getInt(rect);
        int right = rectClass.getField("right").getInt(rect);
        int bottom = rectClass.getField("bottom").getInt(rect);

        return new int[]{left, top, right, bottom};
    }

    /**
     * 处理 DUMP_HIERARCHY 命令 (0x31)
     *
     * 请求格式: format[1B] + compress[1B] + max_depth[2B]
     *
     * 响应格式 (Binary):
     *   version[1B] + compress[1B] + original_size[4B] + compressed_size[4B] +
     *   node_count[2B] + string_pool_size[2B] + [StringPool] + [Nodes]
     *
     * @param payload 请求负载
     * @return 响应数据
     */
    public byte[] handleDumpHierarchy(byte[] payload) {
        if (payload.length < 4) {
            System.err.println(TAG + " DUMP_HIERARCHY payload too short: " + payload.length);
            return new byte[0];
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int format = buffer.get() & 0xFF;
        int compress = buffer.get() & 0xFF;
        int maxDepth = buffer.getShort() & 0xFFFF;

        System.out.println(TAG + " DUMP_HIERARCHY format=" + format +
                " compress=" + compress + " maxDepth=" + maxDepth);

        Object rootNode = null;

        // 尝试通过 UiAutomation 获取根节点
        if (uiAutomation != null) {
            rootNode = uiAutomation.getRootNode();
        }

        // 如果 UiAutomation 失败，使用 shell 后备方案
        if (rootNode == null) {
            System.out.println(TAG + " UiAutomation not available, trying shell fallback...");
            return handleDumpHierarchyViaShell(compress);
        }

        try {
            // 收集所有节点
            List<NodeInfo> nodes = new ArrayList<>();
            DynamicStringPool pool = new DynamicStringPool();
            collectNodes(rootNode, -1, 0, maxDepth, nodes, pool);

            System.out.println(TAG + " Collected " + nodes.size() + " nodes");

            // 序列化节点 (15 bytes each)
            ByteArrayOutputStream nodeData = new ByteArrayOutputStream();
            for (NodeInfo node : nodes) {
                ByteBuffer nodeBuf = ByteBuffer.allocate(15);
                nodeBuf.order(ByteOrder.BIG_ENDIAN);

                nodeBuf.put((byte) (node.parentIndex == -1 ? 0xFF : node.parentIndex));
                nodeBuf.put((byte) node.childCount);
                nodeBuf.put((byte) node.flags);
                nodeBuf.putShort((short) node.left);
                nodeBuf.putShort((short) node.top);
                nodeBuf.putShort((short) node.right);
                nodeBuf.putShort((short) node.bottom);
                nodeBuf.put((byte) node.classId);
                nodeBuf.put((byte) node.textId);
                nodeBuf.put((byte) node.resId);
                nodeBuf.put((byte) node.descId);

                nodeData.write(nodeBuf.array());
            }

            // 序列化动态字符串池
            byte[] poolData = pool.serialize();

            // 合并数据
            byte[] uncompressedData = new byte[poolData.length + nodeData.size()];
            System.arraycopy(poolData, 0, uncompressedData, 0, poolData.length);
            System.arraycopy(nodeData.toByteArray(), 0, uncompressedData, poolData.length, nodeData.size());

            int originalSize = uncompressedData.length;
            byte[] outputData;
            int compressFlag;

            // 压缩 (如果请求)
            if (compress == 1) {  // zlib
                Deflater deflater = new Deflater(6);
                deflater.setInput(uncompressedData);
                deflater.finish();

                ByteArrayOutputStream compressedStream = new ByteArrayOutputStream();
                byte[] tempBuf = new byte[1024];
                while (!deflater.finished()) {
                    int len = deflater.deflate(tempBuf);
                    compressedStream.write(tempBuf, 0, len);
                }
                deflater.end();

                outputData = compressedStream.toByteArray();
                compressFlag = 1;
            } else {
                outputData = uncompressedData;
                compressFlag = 0;
            }

            // 构建响应头
            ByteBuffer respBuffer = ByteBuffer.allocate(14 + outputData.length);
            respBuffer.order(ByteOrder.BIG_ENDIAN);

            respBuffer.put((byte) 0x01);  // version
            respBuffer.put((byte) compressFlag);
            respBuffer.putInt(originalSize);
            respBuffer.putInt(outputData.length);
            respBuffer.putShort((short) nodes.size());
            respBuffer.putShort((short) pool.getDynamicCount());
            respBuffer.put(outputData);

            return respBuffer.array();

        } catch (Exception e) {
            System.err.println(TAG + " DUMP_HIERARCHY error: " + e.getMessage());
            e.printStackTrace();
            return new byte[0];
        }
    }

    /**
     * 通过 shell 命令获取 UI 树 (后备方案)
     * 使用 uiautomator dump 命令
     */
    private byte[] handleDumpHierarchyViaShell(int compress) {
        String tmpFile = "/data/local/tmp/lxb_ui_dump.xml";

        try {
            // 执行 uiautomator dump 命令
            System.out.println(TAG + " Executing uiautomator dump...");
            Process process = Runtime.getRuntime().exec(new String[]{
                    "uiautomator", "dump", tmpFile
            });
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.err.println(TAG + " uiautomator dump failed with exit code: " + exitCode);
                return new byte[0];
            }

            // 读取 XML 文件
            java.io.FileInputStream fis = new java.io.FileInputStream(tmpFile);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            fis.close();

            // 删除临时文件
            Runtime.getRuntime().exec("rm -f " + tmpFile);

            String xmlContent = baos.toString("UTF-8");
            System.out.println(TAG + " Got XML dump: " + xmlContent.length() + " bytes");

            // 解析 XML 并转换为二进制格式
            List<NodeInfo> nodes = new ArrayList<>();
            DynamicStringPool pool = new DynamicStringPool();
            parseXmlDump(xmlContent, nodes, pool);

            System.out.println(TAG + " Parsed " + nodes.size() + " nodes from XML");

            // 序列化节点 (15 bytes each)
            ByteArrayOutputStream nodeData = new ByteArrayOutputStream();
            for (NodeInfo node : nodes) {
                ByteBuffer nodeBuf = ByteBuffer.allocate(15);
                nodeBuf.order(ByteOrder.BIG_ENDIAN);

                nodeBuf.put((byte) (node.parentIndex == -1 ? 0xFF : node.parentIndex));
                nodeBuf.put((byte) node.childCount);
                nodeBuf.put((byte) node.flags);
                nodeBuf.putShort((short) node.left);
                nodeBuf.putShort((short) node.top);
                nodeBuf.putShort((short) node.right);
                nodeBuf.putShort((short) node.bottom);
                nodeBuf.put((byte) node.classId);
                nodeBuf.put((byte) node.textId);
                nodeBuf.put((byte) node.resId);
                nodeBuf.put((byte) node.descId);

                nodeData.write(nodeBuf.array());
            }

            // 序列化动态字符串池
            byte[] poolData = pool.serialize();

            // 合并数据
            byte[] uncompressedData = new byte[poolData.length + nodeData.size()];
            System.arraycopy(poolData, 0, uncompressedData, 0, poolData.length);
            System.arraycopy(nodeData.toByteArray(), 0, uncompressedData, poolData.length, nodeData.size());

            int originalSize = uncompressedData.length;
            byte[] outputData;
            int compressFlag;

            // 压缩 (如果请求)
            if (compress == 1) {  // zlib
                Deflater deflater = new Deflater(6);
                deflater.setInput(uncompressedData);
                deflater.finish();

                ByteArrayOutputStream compressedStream = new ByteArrayOutputStream();
                byte[] tempBuf = new byte[1024];
                while (!deflater.finished()) {
                    int defLen = deflater.deflate(tempBuf);
                    compressedStream.write(tempBuf, 0, defLen);
                }
                deflater.end();

                outputData = compressedStream.toByteArray();
                compressFlag = 1;
            } else {
                outputData = uncompressedData;
                compressFlag = 0;
            }

            // 构建响应头
            ByteBuffer respBuffer = ByteBuffer.allocate(14 + outputData.length);
            respBuffer.order(ByteOrder.BIG_ENDIAN);

            respBuffer.put((byte) 0x01);  // version
            respBuffer.put((byte) compressFlag);
            respBuffer.putInt(originalSize);
            respBuffer.putInt(outputData.length);
            respBuffer.putShort((short) nodes.size());
            respBuffer.putShort((short) pool.getDynamicCount());
            respBuffer.put(outputData);

            return respBuffer.array();

        } catch (Exception e) {
            System.err.println(TAG + " Shell fallback failed: " + e.getMessage());
            e.printStackTrace();
            return new byte[0];
        }
    }

    /**
     * 解析 uiautomator dump 的 XML 输出
     */
    private void parseXmlDump(String xmlContent, List<NodeInfo> nodes, DynamicStringPool pool) {
        // 简单的 XML 解析 - 查找 <node> 元素
        // 格式: <node index="0" text="" resource-id="" class="..." bounds="[0,0][1080,1920]" ...>

        java.util.Stack<Integer> parentStack = new java.util.Stack<>();
        parentStack.push(-1);

        Pattern nodePattern = Pattern.compile("<node\\s+([^>]+?)(?:/>|>)");
        Pattern attrPattern = Pattern.compile("(\\w+(?:-\\w+)?)=\"([^\"]*)\"");

        java.util.regex.Matcher nodeMatcher = nodePattern.matcher(xmlContent);

        while (nodeMatcher.find()) {
            String attrs = nodeMatcher.group(1);

            NodeInfo info = new NodeInfo();
            info.parentIndex = parentStack.peek();

            // 解析属性
            java.util.regex.Matcher attrMatcher = attrPattern.matcher(attrs);
            while (attrMatcher.find()) {
                String name = attrMatcher.group(1);
                String value = attrMatcher.group(2);

                switch (name) {
                    case "class":
                        info.classId = pool.add(value);
                        break;
                    case "text":
                        info.textId = pool.add(value);
                        break;
                    case "resource-id":
                        info.resId = pool.add(value);
                        break;
                    case "content-desc":
                        info.descId = pool.add(value);
                        break;
                    case "bounds":
                        // 解析 "[0,0][1080,1920]" 格式
                        Pattern boundsPattern = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
                        java.util.regex.Matcher boundsMatcher = boundsPattern.matcher(value);
                        if (boundsMatcher.find()) {
                            info.left = Integer.parseInt(boundsMatcher.group(1));
                            info.top = Integer.parseInt(boundsMatcher.group(2));
                            info.right = Integer.parseInt(boundsMatcher.group(3));
                            info.bottom = Integer.parseInt(boundsMatcher.group(4));
                        }
                        break;
                    case "clickable":
                        if ("true".equals(value)) info.flags |= 0x01;
                        break;
                    case "enabled":
                        if ("true".equals(value)) info.flags |= 0x04;
                        break;
                    case "focused":
                        if ("true".equals(value)) info.flags |= 0x08;
                        break;
                    case "scrollable":
                        if ("true".equals(value)) info.flags |= 0x10;
                        break;
                    case "checkable":
                        if ("true".equals(value)) info.flags |= 0x40;
                        break;
                    case "checked":
                        if ("true".equals(value)) info.flags |= 0x80;
                        break;
                }
            }

            // 默认设置 visible 标志
            info.flags |= 0x02;

            int currentIndex = nodes.size();
            nodes.add(info);

            // 检查是否是自闭合标签
            String fullMatch = nodeMatcher.group(0);
            if (!fullMatch.endsWith("/>")) {
                // 更新父节点的 childCount
                if (info.parentIndex >= 0 && info.parentIndex < nodes.size()) {
                    nodes.get(info.parentIndex).childCount++;
                }
                parentStack.push(currentIndex);
            } else {
                // 自闭合标签，更新父节点的 childCount
                if (info.parentIndex >= 0 && info.parentIndex < nodes.size()) {
                    nodes.get(info.parentIndex).childCount++;
                }
            }
        }

        // 处理 </node> 闭合标签
        Pattern closePattern = Pattern.compile("</node>");
        java.util.regex.Matcher closeMatcher = closePattern.matcher(xmlContent);
        while (closeMatcher.find() && parentStack.size() > 1) {
            parentStack.pop();
        }
    }

    /**
     * 递归收集节点
     */
    private void collectNodes(Object node, int parentIndex, int depth, int maxDepth,
                              List<NodeInfo> nodes, DynamicStringPool pool) throws Exception {
        if (node == null) return;
        if (maxDepth > 0 && depth >= maxDepth) return;

        // 检查节点是否可见 (只收集可见节点)
        boolean isVisible = (Boolean) isVisibleToUserMethod.invoke(node);
        if (!isVisible) return;

        int currentIndex = nodes.size();
        NodeInfo info = new NodeInfo();
        info.parentIndex = parentIndex;

        // 获取属性
        CharSequence className = (CharSequence) getClassNameMethod.invoke(node);
        CharSequence text = (CharSequence) getTextMethod.invoke(node);
        CharSequence desc = (CharSequence) getContentDescriptionMethod.invoke(node);
        String resId = (String) getViewIdResourceNameMethod.invoke(node);

        info.classId = pool.add(className != null ? className.toString() : "");
        info.textId = pool.add(text != null ? text.toString() : "");
        info.descId = pool.add(desc != null ? desc.toString() : "");
        info.resId = pool.add(resId != null ? resId : "");

        // 获取边界
        int[] bounds = getNodeBounds(node);
        info.left = bounds[0];
        info.top = bounds[1];
        info.right = bounds[2];
        info.bottom = bounds[3];

        // 获取标志
        info.flags = 0;
        if ((Boolean) isClickableMethod.invoke(node)) info.flags |= 0x01;
        info.flags |= 0x02;  // visible (已确认可见)
        if ((Boolean) isEnabledMethod.invoke(node)) info.flags |= 0x04;
        if ((Boolean) isFocusedMethod.invoke(node)) info.flags |= 0x08;
        if ((Boolean) isScrollableMethod.invoke(node)) info.flags |= 0x10;
        if (isEditableMethod != null && (Boolean) isEditableMethod.invoke(node)) info.flags |= 0x20;
        if ((Boolean) isCheckableMethod.invoke(node)) info.flags |= 0x40;
        if ((Boolean) isCheckedMethod.invoke(node)) info.flags |= 0x80;

        // 获取子节点数量并递归
        int childCount = (Integer) getChildCountMethod.invoke(node);

        // 先添加节点
        nodes.add(info);

        // 递归处理子节点，统计实际添加的可见子节点数量
        int visibleChildCount = 0;
        for (int i = 0; i < childCount; i++) {
            Object child = getChildMethod.invoke(node, i);
            if (child != null) {
                int beforeSize = nodes.size();
                collectNodes(child, currentIndex, depth + 1, maxDepth, nodes, pool);
                if (nodes.size() > beforeSize) {
                    visibleChildCount++;
                }
            }
        }
        info.childCount = Math.min(visibleChildCount, 255);
    }

    /**
     * 节点信息结构
     */
    private static class NodeInfo {
        int parentIndex;
        int childCount;
        int flags;
        int left, top, right, bottom;
        int classId, textId, resId, descId;
    }

    /**
     * 动态字符串池
     */
    private static class DynamicStringPool {
        private final Map<String, Integer> pool = new HashMap<>();
        private final List<String> dynamicStrings = new ArrayList<>();

        /**
         * 添加字符串并返回 ID
         */
        public int add(String s) {
            if (s == null || s.isEmpty()) {
                return StringPoolConstants.NULL_MARKER;
            }

            // 检查预定义类
            for (int i = 0; i < StringPoolConstants.PREDEFINED_CLASSES.length; i++) {
                if (s.equals(StringPoolConstants.PREDEFINED_CLASSES[i])) {
                    return i;
                }
            }

            // 检查预定义文本
            for (int i = 0; i < StringPoolConstants.PREDEFINED_TEXTS.length; i++) {
                if (s.equals(StringPoolConstants.PREDEFINED_TEXTS[i])) {
                    return 0x40 + i;
                }
            }

            // 检查动态池
            if (pool.containsKey(s)) {
                return pool.get(s);
            }

            // 添加到动态池
            int id = StringPoolConstants.DYNAMIC_POOL_START + dynamicStrings.size();
            if (id > 0xFE) {
                // 池满，返回空标记
                return StringPoolConstants.NULL_MARKER;
            }

            pool.put(s, id);
            dynamicStrings.add(s);
            return id;
        }

        public int getDynamicCount() {
            return dynamicStrings.size();
        }

        /**
         * 序列化动态字符串池
         *
         * 格式: count[2B] + entries[...]
         *   Entry: str_id[1B] + str_len[1B] + str_data[UTF-8]
         */
        public byte[] serialize() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // 写入数量 (2 bytes, big endian)
            out.write((dynamicStrings.size() >> 8) & 0xFF);
            out.write(dynamicStrings.size() & 0xFF);

            // 写入每个动态字符串
            for (int i = 0; i < dynamicStrings.size(); i++) {
                String s = dynamicStrings.get(i);
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                int id = StringPoolConstants.DYNAMIC_POOL_START + i;

                out.write(id & 0xFF);
                out.write(Math.min(bytes.length, 255));
                out.write(bytes, 0, Math.min(bytes.length, 255));
            }

            return out.toByteArray();
        }
    }

    /**
     * 处理 HIERARCHY_REQ 命令（分片传输）- 预留接口
     *
     * @param payload 请求负载
     * @return 响应数据
     */
    public byte[] handleHierarchyReq(byte[] payload) {
        // 分片传输 - 暂不实现，先使用 DUMP_HIERARCHY 单帧传输
        System.out.println(TAG + " HIERARCHY_REQ (delegating to DUMP_HIERARCHY)");
        return handleDumpHierarchy(payload);
    }

    /**
     * 处理 DUMP_ACTIONS 命令 (0x33)
     *
     * 只返回可交互节点 (clickable/editable/scrollable) 和有文本的节点
     * 自动关联子节点文本到父节点
     *
     * 响应格式: version[1B] + count[2B] + nodes[...] + string_pool[...]
     *
     * ActionNode 格式 (20 bytes):
     *   type[1B]     - 0x01=clickable, 0x02=editable, 0x04=scrollable, 0x08=text_only
     *   bounds[8B]   - left[2B] + top[2B] + right[2B] + bottom[2B]
     *   class_id[1B] - 类名字符串ID
     *   text_id[2B]  - 文本字符串ID (2B 支持更长文本)
     *   res_id[1B]   - Resource ID 字符串ID
     *   desc_id[1B]  - Content Description 字符串ID
     *   reserved[6B] - 保留
     *
     * @param payload 请求负载 (暂无参数)
     * @return 响应数据
     */
    public byte[] handleDumpActions(byte[] payload) {
        System.out.println(TAG + " DUMP_ACTIONS");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available, trying shell fallback");
            return handleDumpActionsViaShell();
        }

        try {
            // 获取根节点
            Object rootNode = uiAutomation.getRootNode();
            if (rootNode == null) {
                System.err.println(TAG + " getRootNode returned null, trying shell fallback");
                return handleDumpActionsViaShell();
            }

            // 收集可交互节点
            List<ActionNode> actions = new ArrayList<>();
            ActionStringPool pool = new ActionStringPool();
            collectActions(rootNode, actions, pool);

            System.out.println(TAG + " Collected " + actions.size() + " action nodes");

            // 序列化
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // 版本号
            out.write(0x01);

            // 节点数量 (2 bytes, big endian)
            out.write((actions.size() >> 8) & 0xFF);
            out.write(actions.size() & 0xFF);

            // 序列化每个节点 (20 bytes each)
            for (ActionNode node : actions) {
                ByteBuffer buf = ByteBuffer.allocate(20);
                buf.order(ByteOrder.BIG_ENDIAN);

                buf.put(node.type);
                buf.putShort((short) node.left);
                buf.putShort((short) node.top);
                buf.putShort((short) node.right);
                buf.putShort((short) node.bottom);
                buf.put((byte) node.classId);
                buf.putShort((short) node.textId);  // 2 bytes for text ID
                buf.put((byte) node.resId);
                buf.put((byte) node.descId);
                buf.put(new byte[6]);  // reserved

                out.write(buf.array());
            }

            // 序列化字符串池
            byte[] poolData = pool.serialize();
            out.write(poolData);

            byte[] result = out.toByteArray();
            System.out.println(TAG + " DUMP_ACTIONS response: " + result.length + " bytes");

            return result;

        } catch (Exception e) {
            System.err.println(TAG + " DUMP_ACTIONS failed: " + e.getMessage());
            e.printStackTrace();
            return new byte[]{0x00};  // 失败
        }
    }

    /**
     * 通过 shell 命令获取可交互节点 (后备方案)
     * 解析 XML 并过滤出可交互节点
     */
    private byte[] handleDumpActionsViaShell() {
        String tmpFile = "/data/local/tmp/lxb_ui_dump.xml";

        try {
            // 执行 uiautomator dump 命令
            System.out.println(TAG + " DUMP_ACTIONS via shell...");
            Process process = Runtime.getRuntime().exec(new String[]{
                    "uiautomator", "dump", tmpFile
            });
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.err.println(TAG + " uiautomator dump failed with exit code: " + exitCode);
                return new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00};
            }

            // 读取 XML 文件
            java.io.FileInputStream fis = new java.io.FileInputStream(tmpFile);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            fis.close();

            // 删除临时文件
            Runtime.getRuntime().exec("rm -f " + tmpFile);

            String xmlContent = baos.toString("UTF-8");
            System.out.println(TAG + " Got XML dump: " + xmlContent.length() + " bytes");

            // 解析 XML 并过滤可交互节点
            List<ActionNode> actions = new ArrayList<>();
            ActionStringPool pool = new ActionStringPool();
            parseXmlDumpForActions(xmlContent, actions, pool);

            System.out.println(TAG + " Filtered " + actions.size() + " action nodes from XML");

            // 序列化
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // 版本号
            out.write(0x01);

            // 节点数量 (2 bytes, big endian)
            out.write((actions.size() >> 8) & 0xFF);
            out.write(actions.size() & 0xFF);

            // 序列化每个节点 (20 bytes each)
            for (ActionNode node : actions) {
                ByteBuffer buf = ByteBuffer.allocate(20);
                buf.order(ByteOrder.BIG_ENDIAN);

                buf.put(node.type);
                buf.putShort((short) node.left);
                buf.putShort((short) node.top);
                buf.putShort((short) node.right);
                buf.putShort((short) node.bottom);
                buf.put((byte) node.classId);
                buf.putShort((short) node.textId);
                buf.put((byte) node.resId);
                buf.put((byte) node.descId);
                buf.put(new byte[6]);  // reserved

                out.write(buf.array());
            }

            // 序列化字符串池
            byte[] poolData = pool.serialize();
            out.write(poolData);

            byte[] result = out.toByteArray();
            System.out.println(TAG + " DUMP_ACTIONS shell response: " + result.length + " bytes");

            return result;

        } catch (Exception e) {
            System.err.println(TAG + " DUMP_ACTIONS shell fallback failed: " + e.getMessage());
            e.printStackTrace();
            return new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00};
        }
    }

    /**
     * XML 节点信息 (用于构建父子关系)
     */
    private static class XmlNodeInfo {
        String className = "";
        String text = "";
        String resId = "";
        String contentDesc = "";
        int left, top, right, bottom;
        boolean isClickable;
        boolean isScrollable;
        boolean isEditable;
        int parentIndex = -1;
        List<Integer> children = new ArrayList<>();
    }

    /**
     * 解析 XML 并过滤可交互节点
     * 支持父子节点文本关联：如果交互节点没有自己的文本，使用第一个子节点的文本
     */
    private void parseXmlDumpForActions(String xmlContent, List<ActionNode> actions, ActionStringPool pool) {
        Pattern nodePattern = Pattern.compile("<node\\s+([^>]+?)(?:/>|>)");
        Pattern attrPattern = Pattern.compile("(\\w+(?:-\\w+)?)=\"([^\"]*)\"");

        // 第一遍：解析所有节点并建立父子关系
        List<XmlNodeInfo> allNodes = new ArrayList<>();
        java.util.Stack<Integer> parentStack = new java.util.Stack<>();
        parentStack.push(-1);  // 虚拟根节点

        // 记录每个 <node> 在 XML 中的位置，用于跟踪 </node> 闭合标签
        List<Integer> nodePositions = new ArrayList<>();

        java.util.regex.Matcher nodeMatcher = nodePattern.matcher(xmlContent);

        while (nodeMatcher.find()) {
            String attrs = nodeMatcher.group(1);
            String fullMatch = nodeMatcher.group(0);
            int matchEnd = nodeMatcher.end();

            XmlNodeInfo info = new XmlNodeInfo();

            // 解析属性
            java.util.regex.Matcher attrMatcher = attrPattern.matcher(attrs);
            while (attrMatcher.find()) {
                String name = attrMatcher.group(1);
                String value = attrMatcher.group(2);

                switch (name) {
                    case "class":
                        info.className = value;
                        if (value.contains("EditText") || value.contains("AutoCompleteTextView")) {
                            info.isEditable = true;
                        }
                        break;
                    case "text":
                        info.text = value;
                        break;
                    case "resource-id":
                        info.resId = value;
                        break;
                    case "content-desc":
                        info.contentDesc = value;
                        break;
                    case "bounds":
                        Pattern boundsPattern = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
                        java.util.regex.Matcher boundsMatcher = boundsPattern.matcher(value);
                        if (boundsMatcher.find()) {
                            info.left = Integer.parseInt(boundsMatcher.group(1));
                            info.top = Integer.parseInt(boundsMatcher.group(2));
                            info.right = Integer.parseInt(boundsMatcher.group(3));
                            info.bottom = Integer.parseInt(boundsMatcher.group(4));
                        }
                        break;
                    case "clickable":
                        info.isClickable = "true".equals(value);
                        break;
                    case "scrollable":
                        info.isScrollable = "true".equals(value);
                        break;
                }
            }

            // 设置父子关系
            int currentIndex = allNodes.size();
            info.parentIndex = parentStack.peek();

            // 添加到父节点的子节点列表
            if (info.parentIndex >= 0 && info.parentIndex < allNodes.size()) {
                allNodes.get(info.parentIndex).children.add(currentIndex);
            }

            allNodes.add(info);

            // 如果不是自闭合标签，压入栈
            if (!fullMatch.endsWith("/>")) {
                parentStack.push(currentIndex);
                nodePositions.add(matchEnd);
            }
        }

        // 处理 </node> 闭合标签
        Pattern closePattern = Pattern.compile("</node>");
        java.util.regex.Matcher closeMatcher = closePattern.matcher(xmlContent);
        while (closeMatcher.find() && parentStack.size() > 1) {
            parentStack.pop();
        }

        System.out.println(TAG + " parseXmlDumpForActions: parsed " + allNodes.size() + " nodes");

        // 第二遍：过滤并输出可交互节点
        for (int i = 0; i < allNodes.size(); i++) {
            XmlNodeInfo info = allNodes.get(i);

            boolean isInteractive = info.isClickable || info.isEditable || info.isScrollable;
            boolean hasText = !info.text.isEmpty() || !info.contentDesc.isEmpty();

            if (isInteractive || hasText) {
                ActionNode action = new ActionNode();

                // 设置类型
                if (info.isClickable) action.type |= 0x01;
                if (info.isEditable) action.type |= 0x02;
                if (info.isScrollable) action.type |= 0x04;
                if (!isInteractive && hasText) action.type |= 0x08;

                action.left = info.left;
                action.top = info.top;
                action.right = info.right;
                action.bottom = info.bottom;

                action.classId = pool.addShort(info.className);

                // 获取显示文本 - 关键逻辑：如果交互节点没有文本，从子节点获取
                String displayText = info.text;
                if (displayText.isEmpty() && isInteractive) {
                    // 递归查找第一个有文本的子节点
                    displayText = findFirstChildTextFromXml(allNodes, i);
                }
                if (displayText.isEmpty()) {
                    displayText = info.contentDesc;
                }
                action.textId = pool.addLong(displayText);

                action.resId = pool.addShort(info.resId);
                action.descId = pool.addShort(info.contentDesc);

                actions.add(action);
            }
        }
    }

    /**
     * 从 XML 节点树中查找第一个有文本的子节点
     */
    private String findFirstChildTextFromXml(List<XmlNodeInfo> allNodes, int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= allNodes.size()) return "";

        XmlNodeInfo node = allNodes.get(nodeIndex);

        // 遍历所有子节点
        for (int childIndex : node.children) {
            if (childIndex < 0 || childIndex >= allNodes.size()) continue;

            XmlNodeInfo child = allNodes.get(childIndex);

            // 检查子节点自己的文本
            if (!child.text.isEmpty()) {
                return child.text;
            }
            if (!child.contentDesc.isEmpty()) {
                return child.contentDesc;
            }

            // 递归检查子节点的子节点
            String childText = findFirstChildTextFromXml(allNodes, childIndex);
            if (!childText.isEmpty()) {
                return childText;
            }
        }

        return "";
    }

    /**
     * 递归收集可交互节点
     */
    private void collectActions(Object node, List<ActionNode> actions, ActionStringPool pool) throws Exception {
        if (node == null) return;

        // 检查是否可见
        boolean isVisible = (Boolean) isVisibleToUserMethod.invoke(node);
        if (!isVisible) return;

        // 获取属性
        boolean isClickable = (Boolean) isClickableMethod.invoke(node);
        boolean isScrollable = (Boolean) isScrollableMethod.invoke(node);
        boolean isEditable = isEditableMethod != null && (Boolean) isEditableMethod.invoke(node);

        CharSequence text = (CharSequence) getTextMethod.invoke(node);
        CharSequence desc = (CharSequence) getContentDescriptionMethod.invoke(node);
        String textStr = text != null ? text.toString() : "";
        String descStr = desc != null ? desc.toString() : "";

        // 判断是否是有意义的节点
        boolean isInteractive = isClickable || isEditable || isScrollable;
        boolean hasText = !textStr.isEmpty() || !descStr.isEmpty();

        if (isInteractive || hasText) {
            ActionNode action = new ActionNode();

            // 设置类型
            if (isClickable) action.type |= 0x01;
            if (isEditable) action.type |= 0x02;
            if (isScrollable) action.type |= 0x04;
            if (!isInteractive && hasText) action.type |= 0x08;  // text_only

            // 获取边界
            int[] bounds = getNodeBounds(node);
            action.left = bounds[0];
            action.top = bounds[1];
            action.right = bounds[2];
            action.bottom = bounds[3];

            // 获取类名
            CharSequence className = (CharSequence) getClassNameMethod.invoke(node);
            action.classId = pool.addShort(className != null ? className.toString() : "");

            // 获取文本 - 如果自身没有文本，尝试获取第一个有文本的子节点
            String displayText = textStr;
            if (displayText.isEmpty() && isInteractive) {
                displayText = findFirstChildText(node);
            }
            if (displayText.isEmpty()) {
                displayText = descStr;
            }
            action.textId = pool.addLong(displayText);

            // Resource ID
            String resId = (String) getViewIdResourceNameMethod.invoke(node);
            action.resId = pool.addShort(resId != null ? resId : "");

            // Content Description
            action.descId = pool.addShort(descStr);

            actions.add(action);
        }

        // 递归处理子节点
        int childCount = (Integer) getChildCountMethod.invoke(node);
        for (int i = 0; i < childCount; i++) {
            Object child = getChildMethod.invoke(node, i);
            if (child != null) {
                collectActions(child, actions, pool);
            }
        }
    }

    /**
     * 查找第一个有文本的直接子节点
     */
    private String findFirstChildText(Object node) throws Exception {
        int childCount = (Integer) getChildCountMethod.invoke(node);
        for (int i = 0; i < childCount; i++) {
            Object child = getChildMethod.invoke(node, i);
            if (child != null) {
                CharSequence text = (CharSequence) getTextMethod.invoke(child);
                if (text != null && text.length() > 0) {
                    return text.toString();
                }
                CharSequence desc = (CharSequence) getContentDescriptionMethod.invoke(child);
                if (desc != null && desc.length() > 0) {
                    return desc.toString();
                }
            }
        }
        return "";
    }

    /**
     * 可交互节点结构
     */
    private static class ActionNode {
        byte type;       // 0x01=clickable, 0x02=editable, 0x04=scrollable, 0x08=text_only
        int left, top, right, bottom;
        int classId;     // 1 byte
        int textId;      // 2 bytes (支持更长文本)
        int resId;       // 1 byte
        int descId;      // 1 byte
    }

    /**
     * DUMP_ACTIONS 专用字符串池
     * 支持短字符串 (1B ID, 最多 255 个) 和长字符串 (2B ID, 用于 text)
     */
    private static class ActionStringPool {
        private final Map<String, Integer> shortPool = new HashMap<>();
        private final List<String> shortStrings = new ArrayList<>();
        private final Map<String, Integer> longPool = new HashMap<>();
        private final List<String> longStrings = new ArrayList<>();

        /**
         * 添加短字符串 (class, resId, desc)
         */
        public int addShort(String s) {
            if (s == null || s.isEmpty()) return 0xFF;  // NULL marker
            if (shortPool.containsKey(s)) return shortPool.get(s);
            if (shortStrings.size() >= 254) return 0xFF;  // 池满

            int id = shortStrings.size();
            shortPool.put(s, id);
            shortStrings.add(s);
            return id;
        }

        /**
         * 添加长字符串 (text)
         */
        public int addLong(String s) {
            if (s == null || s.isEmpty()) return 0xFFFF;  // NULL marker
            if (longPool.containsKey(s)) return longPool.get(s);
            if (longStrings.size() >= 65534) return 0xFFFF;  // 池满

            int id = longStrings.size();
            longPool.put(s, id);
            longStrings.add(s);
            return id;
        }

        /**
         * 序列化字符串池
         * 格式: short_count[1B] + short_entries[...] + long_count[2B] + long_entries[...]
         *   Short entry: len[1B] + data[UTF-8]
         *   Long entry: len[2B] + data[UTF-8]
         */
        public byte[] serialize() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // 短字符串池
            out.write(shortStrings.size() & 0xFF);
            for (String s : shortStrings) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                int len = Math.min(bytes.length, 255);
                out.write(len);
                out.write(bytes, 0, len);
            }

            // 长字符串池
            out.write((longStrings.size() >> 8) & 0xFF);
            out.write(longStrings.size() & 0xFF);
            for (String s : longStrings) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                int len = Math.min(bytes.length, 65535);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
                out.write(bytes, 0, len);
            }

            return out.toByteArray();
        }
    }

    /**
     * 处理 SCREENSHOT 命令 (0x60)
     *
     * 响应格式: status[1B] + image_data[N]
     *   status: 0x01=成功, 0x00=失败
     *   image_data: PNG 格式的图片数据
     *
     * @return 响应数据
     */
    public byte[] handleScreenshot() {
        System.out.println(TAG + " SCREENSHOT");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        byte[] imageData = uiAutomation.takeScreenshot();
        if (imageData == null || imageData.length == 0) {
            System.err.println(TAG + " Screenshot failed");
            return new byte[]{0x00};
        }

        System.out.println(TAG + " Screenshot: " + imageData.length + " bytes");

        // 构建响应: status[1B] + image_data[N]
        byte[] response = new byte[1 + imageData.length];
        response[0] = 0x01;  // success
        System.arraycopy(imageData, 0, response, 1, imageData.length);

        return response;
    }

    // =========================================================================
    // 分片截图传输 (CMD_IMG_REQ = 0x61)
    // =========================================================================

    // 分片大小 (1KB chunks, 保证单个 UDP 包 < 1500 MTU)
    private static final int CHUNK_SIZE = 1024;

    // 命令常量
    private static final int CMD_IMG_META = 0x62;
    private static final int CMD_IMG_CHUNK = 0x63;
    private static final int CMD_IMG_MISSING = 0x64;
    private static final int CMD_IMG_FIN = 0x65;
    private static final int CMD_ACK = 0x02;

    /**
     * 处理分片截图请求 (CMD_IMG_REQ = 0x61)
     *
     * 流程:
     * 1. 截图并切分为 1KB 块
     * 2. 发送 IMG_META (img_id, total_size, num_chunks)
     * 3. 等待客户端 ACK
     * 4. 突发发送所有 IMG_CHUNK
     * 5. 等待 IMG_MISSING 或 IMG_FIN
     * 6. 如果收到 IMG_MISSING，重传缺失块
     * 7. 如果收到 IMG_FIN，发送 ACK 并结束
     *
     * @param server UDP 服务器实例
     * @param clientAddr 客户端地址
     * @param clientPort 客户端端口
     * @param reqSeq 请求序列号
     * @return true 如果传输成功
     */
    public boolean handleFragmentedScreenshot(
            UdpServer server,
            InetAddress clientAddr,
            int clientPort,
            int reqSeq
    ) {
        System.out.println(TAG + " IMG_REQ: Starting fragmented screenshot transfer");

        // Step 1: 截图
        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return false;
        }

        byte[] imageData = uiAutomation.takeScreenshot();
        if (imageData == null || imageData.length == 0) {
            System.err.println(TAG + " Screenshot failed");
            return false;
        }

        System.out.println(TAG + " Screenshot: " + imageData.length + " bytes (" +
                (imageData.length / 1024.0) + " KB)");

        // Step 2: 切分为 chunks
        int numChunks = (imageData.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        byte[][] chunks = new byte[numChunks][];

        for (int i = 0; i < numChunks; i++) {
            int offset = i * CHUNK_SIZE;
            int len = Math.min(CHUNK_SIZE, imageData.length - offset);
            chunks[i] = new byte[len];
            System.arraycopy(imageData, offset, chunks[i], 0, len);
        }

        System.out.println(TAG + " Split into " + numChunks + " chunks");

        try {
            // Step 3: 发送 IMG_META
            int imgId = (int) (System.currentTimeMillis() & 0xFFFFFFFF);
            byte[] metaPayload = packImgMeta(imgId, imageData.length, numChunks);
            byte[] metaFrame = FrameCodec.encode(reqSeq, (byte) CMD_IMG_META, metaPayload);
            server.send(clientAddr, clientPort, metaFrame);
            System.out.println(TAG + " Sent IMG_META: imgId=" + imgId +
                    ", size=" + imageData.length + ", chunks=" + numChunks);

            // Step 4: 等待 META ACK (超时 2 秒)
            if (!waitForAck(server, reqSeq, 2000)) {
                System.err.println(TAG + " Timeout waiting for META ACK");
                return false;
            }
            System.out.println(TAG + " Received ACK for IMG_META");

            // Step 5: 突发发送所有 chunks
            int chunkSeq = reqSeq + 1;
            for (int i = 0; i < numChunks; i++) {
                byte[] chunkPayload = packImgChunk(i, chunks[i]);
                byte[] chunkFrame = FrameCodec.encode(chunkSeq + i, (byte) CMD_IMG_CHUNK, chunkPayload);
                server.send(clientAddr, clientPort, chunkFrame);

                // 每 10 个 chunk 打印进度
                if ((i + 1) % 10 == 0 || i == numChunks - 1) {
                    System.out.println(TAG + " Sent chunk " + (i + 1) + "/" + numChunks);
                }

                // 短暂延迟避免网络拥塞
                if (i % 5 == 0) {
                    Thread.sleep(1);
                }
            }

            // Step 6: 等待 IMG_MISSING 或 IMG_FIN (最多重试 3 次)
            int maxRetries = 3;
            for (int retry = 0; retry < maxRetries; retry++) {
                UdpServer.ReceivedFrame frame = waitForFrame(server, 1000);
                if (frame == null) {
                    System.out.println(TAG + " Timeout waiting for client response, retry " + (retry + 1));
                    continue;
                }

                FrameCodec.DecodedFrame decoded = FrameCodec.decode(frame.data);
                int cmd = decoded.cmd & 0xFF;

                if (cmd == CMD_IMG_FIN) {
                    // 传输完成，发送 ACK
                    byte[] ackFrame = FrameCodec.encode(decoded.seq, (byte) CMD_ACK, new byte[0]);
                    server.send(clientAddr, clientPort, ackFrame);
                    System.out.println(TAG + " Received IMG_FIN, sent ACK. Transfer complete!");
                    return true;
                } else if (cmd == CMD_IMG_MISSING) {
                    // 解析缺失块列表并重传
                    List<Integer> missingIndices = unpackImgMissing(decoded.payload);
                    System.out.println(TAG + " Received IMG_MISSING: " + missingIndices.size() + " chunks");

                    for (int idx : missingIndices) {
                        if (idx >= 0 && idx < numChunks) {
                            byte[] chunkPayload = packImgChunk(idx, chunks[idx]);
                            byte[] chunkFrame = FrameCodec.encode(chunkSeq + idx, (byte) CMD_IMG_CHUNK, chunkPayload);
                            server.send(clientAddr, clientPort, chunkFrame);
                        }
                    }
                    System.out.println(TAG + " Resent " + missingIndices.size() + " missing chunks");
                } else {
                    System.out.println(TAG + " Unexpected command: 0x" + String.format("%02X", cmd));
                }
            }

            System.err.println(TAG + " Max retries exceeded");
            return false;

        } catch (Exception e) {
            System.err.println(TAG + " Fragmented transfer error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 打包 IMG_META 负载
     * 格式: img_id[uint32] + total_size[uint32] + num_chunks[uint16]
     */
    private byte[] packImgMeta(int imgId, int totalSize, int numChunks) {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(imgId);
        buf.putInt(totalSize);
        buf.putShort((short) numChunks);
        return buf.array();
    }

    /**
     * 打包 IMG_CHUNK 负载
     * 格式: chunk_index[uint16] + chunk_data
     */
    private byte[] packImgChunk(int chunkIndex, byte[] chunkData) {
        ByteBuffer buf = ByteBuffer.allocate(2 + chunkData.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) chunkIndex);
        buf.put(chunkData);
        return buf.array();
    }

    /**
     * 解包 IMG_MISSING 负载
     * 格式: count[uint16] + indices[uint16 array]
     */
    private List<Integer> unpackImgMissing(byte[] payload) {
        List<Integer> indices = new ArrayList<>();
        if (payload.length < 2) return indices;

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);

        int count = buf.getShort() & 0xFFFF;
        for (int i = 0; i < count && buf.remaining() >= 2; i++) {
            indices.add(buf.getShort() & 0xFFFF);
        }
        return indices;
    }

    /**
     * 等待指定序列号的 ACK
     */
    private boolean waitForAck(UdpServer server, int expectedSeq, int timeout) {
        long deadline = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < deadline) {
            try {
                UdpServer.ReceivedFrame frame = server.receive(
                        (int) (deadline - System.currentTimeMillis())
                );

                FrameCodec.DecodedFrame decoded = FrameCodec.decode(frame.data);
                if ((decoded.cmd & 0xFF) == CMD_ACK && decoded.seq == expectedSeq) {
                    return true;
                }
            } catch (SocketTimeoutException e) {
                break;
            } catch (Exception e) {
                System.err.println(TAG + " Error waiting for ACK: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * 等待帧（带超时）
     */
    private UdpServer.ReceivedFrame waitForFrame(UdpServer server, int timeout) {
        try {
            return server.receive(timeout);
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            System.err.println(TAG + " Error receiving frame: " + e.getMessage());
            return null;
        }
    }
}
