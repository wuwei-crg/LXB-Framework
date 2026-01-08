package com.lxb.server.protocol;

/**
 * StringPool 预定义常量
 * 必须与 Python 端 constants.py 完全一致
 */
public class StringPoolConstants {

    /**
     * 预定义类名 (0x00-0x3F, 64 个)
     */
    public static final String[] PREDEFINED_CLASSES = {
        "android.view.View",                    // 0x00
        "android.view.ViewGroup",               // 0x01
        "android.widget.TextView",              // 0x02
        "android.widget.Button",                // 0x03
        "android.widget.ImageView",             // 0x04
        "android.widget.EditText",              // 0x05
        "android.widget.LinearLayout",          // 0x06
        "android.widget.RelativeLayout",        // 0x07
        "android.widget.FrameLayout",           // 0x08
        "android.widget.ScrollView",            // 0x09
        "android.widget.ListView",              // 0x0A
        "android.widget.RecyclerView",          // 0x0B
        "androidx.recyclerview.widget.RecyclerView", // 0x0C
        "android.widget.CheckBox",              // 0x0D
        "android.widget.RadioButton",           // 0x0E
        "android.widget.Switch",                // 0x0F
        "android.widget.Spinner",               // 0x10
        "android.widget.ProgressBar",           // 0x11
        "android.widget.SeekBar",               // 0x12
        "android.widget.RatingBar",             // 0x13
        "android.widget.ImageButton",           // 0x14
        "android.widget.ToggleButton",          // 0x15
        "android.widget.GridView",              // 0x16
        "android.widget.TabHost",               // 0x17
        "android.widget.TabWidget",             // 0x18
        "android.webkit.WebView",               // 0x19
        "android.inputmethodservice.Keyboard",  // 0x1A
        // 填充剩余槽位到 64 个
        "", "", "", "", "", "", "", "",         // 0x1B-0x22
        "", "", "", "", "", "", "", "",         // 0x23-0x2A
        "", "", "", "", "", "", "", "",         // 0x2B-0x32
        "", "", "", "", "", "", "", "",         // 0x33-0x3A
        "", "", "", ""                          // 0x3B-0x3E
        // 0x3F 保留
    };

    /**
     * 预定义文本 (0x40-0x7F, 64 个)
     */
    public static final String[] PREDEFINED_TEXTS = {
        "",                 // 0x40 (空字符串)
        "确定",             // 0x41
        "取消",             // 0x42
        "返回",             // 0x43
        "下一步",           // 0x44
        "完成",             // 0x45
        "保存",             // 0x46
        "删除",             // 0x47
        "编辑",             // 0x48
        "搜索",             // 0x49
        "发送",             // 0x4A
        "分享",             // 0x4B
        "更多",             // 0x4C
        "设置",             // 0x4D
        "登录",             // 0x4E
        "注册",             // 0x4F
        "退出",             // 0x50
        "刷新",             // 0x51
        "加载中",           // 0x52
        "请稍候",           // 0x53
        // 填充剩余槽位到 64 个
        "", "", "", "", "", "", "", "",         // 0x54-0x5B
        "", "", "", "", "", "", "", "",         // 0x5C-0x63
        "", "", "", "", "", "", "", "",         // 0x64-0x6B
        "", "", "", "", "", "", "", "",         // 0x6C-0x73
        "", "", "", "", "", "", "", "",         // 0x74-0x7B
        "", "", "", ""                          // 0x7C-0x7F
    };

    /**
     * 动态池起始索引 (0x80-0xFE)
     */
    public static final int DYNAMIC_POOL_START = 0x80;

    /**
     * NULL 标记 (0xFF)
     */
    public static final int NULL_MARKER = 0xFF;
}
