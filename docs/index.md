# AutoLXB 使用手册

AutoLXB 是一个面向 Android 端的自动化框架。它的目标是让用户用自然语言描述任务，再由系统完成应用启动、页面跳转、视觉识别、点击、输入、滑动等操作。

它适合处理这类场景：

- 临时执行一次手机操作，例如打开某个 App 完成签到或查询信息。
- 按固定时间自动执行任务，例如每天早上运行一次固定流程。
- 根据手机通知触发任务，例如收到特定消息后自动进入对应 App 处理。
- 保存一次成功执行产生的任务路线，后续复用路线提高稳定性和速度。
- 导入、导出任务与路线，在另一台设备上进行本机适配后继续使用。

## 演示视频

下面的视频演示了 AutoLXB 的基础使用流程。视频中没有展示任务路线复用功能，路线相关能力可以继续阅读后面的“任务教程”。

<div style="position: relative; padding: 30% 45%; height: 0; overflow: hidden; max-width: 100%;">
  <iframe
    src="https://player.bilibili.com/player.html?bvid=BV1pDSSBKEvn&page=1&autoplay=0"
    scrolling="no"
    border="0"
    frameborder="no"
    framespacing="0"
    allowfullscreen="true"
    style="position: absolute; top: 0; left: 0; width: 100%; height: 100%;">
  </iframe>
</div>

如果页面无法直接播放，也可以打开：<https://www.bilibili.com/video/BV1pDSSBKEvn>

## 项目架构

AutoLXB 由 Android 前端、端侧 core、感知与执行能力、模型服务和任务路线系统共同组成。用户在 App 里创建任务；core 负责调度状态机、读取屏幕信息、调用模型、执行动作，并在任务成功后沉淀可复用路线。

![AutoLXB 架构](assets/images/architecture.png)

## 手册内容

这份手册主要介绍如何使用 AutoLXB：

- **快速开始**：安装、启动 core、配置模型。
- **任务教程**：创建快速任务、定时任务、通知触发任务，理解和编辑任务路线。
- **配置说明**：查看各类配置项的用途。
- **如何查看 Trace**：遇到问题时查看运行日志，并在反馈问题时提供 trace 文件。