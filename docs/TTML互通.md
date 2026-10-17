# 与 SPW Lyrics 传递原始 TTML

AMLL into SPW 提供进程内 TTML 接收接口。SPW Lyrics 可在完成本地读取、缓存命中或在线获取后直接提交原始 XML，避免先转换成 SPL 再丢失演唱者、背景人声、翻译、音译和注音。

接口通过 JVM 平台 MBeanServer 发现，跨 PF4J 类加载器工作，不需要依赖对方的 JAR，也不启动网络服务。仅在 AMLL into SPW 启用期间注册。

## 协议 1

ObjectName：`io.github.gaboron.amllspw:type=TtmlReceiver,version=1`

| 属性或操作 | 类型与含义 |
| --- | --- |
| `ProtocolVersion` | 只读 `int`，当前为 `1` |
| `Active` | 只读 `boolean`，接收器是否启用 |
| `submitTtml(String trackId, String audioPath, String ttml, long offsetMs)` | 返回 `boolean`，只接受当前歌曲的有效原始 TTML |
| `clearTtml(String trackId, String audioPath)` | 返回 `boolean`，撤回当前歌曲的外部 TTML，回退到本地 TTML 或宿主歌词 |

`trackId` 使用 Workshop API 的 `MediaItem.id`，未提供时允许空字符串。`audioPath` 必须填写 `MediaItem.path`；两端 ID 均非空时，ID 和规范化路径必须同时匹配。Windows 路径不区分大小写。

`ttml` 必须是未经 SPL 转换的完整 XML。上限为 4,000,000 个字符，禁用 DTD 和外部实体。`offsetMs` 是 SPW Lyrics 的额外单曲偏移：正数延后显示，负数提前显示，范围为 ±86,400,000 毫秒。XML 时间戳保持原值；不要先给 XML 加同一份偏移，也不要再次传入 SPW 全局歌词偏移。

`submitTtml` 会读取当前歌曲并解析 XML，必须在后台线程调用。返回 `true` 表示原始 TTML 已被接受；返回 `false` 或任何 JMX 调用异常时，应继续既有的 SPL/普通歌词流程。接收器会拒收换歌后的迟到结果、无效 XML、空时间轴和超限输入。重复提交同一首歌是允许的。

优先级为：**同名 `.ttml` → 音频标签中的 TTML → 同名 `.lrc` 中的 TTML → 本接口的 TTML → SPW 已解析的 SPL/LRC**。本地 TTML 优先级不受外部提交影响。`offsetMs` 只应用于本接口提供的 TTML。

## 调用示例

下面的 Java 代码可直接在 SPW Lyrics 的后台获取流程中使用，只依赖 JDK。

```java
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;

boolean deliverTtml(String trackId, String path, String rawTtml, long delayMs) {
    try {
        var server = ManagementFactory.getPlatformMBeanServer();
        var target = new ObjectName("io.github.gaboron.amllspw:type=TtmlReceiver,version=1");
        if (!server.isRegistered(target)
                || !Integer.valueOf(1).equals(server.getAttribute(target, "ProtocolVersion"))
                || !Boolean.TRUE.equals(server.getAttribute(target, "Active"))) return false;
        return Boolean.TRUE.equals(server.invoke(target, "submitTtml",
                new Object[]{trackId, path, rawTtml, delayMs},
                new String[]{"java.lang.String", "java.lang.String", "java.lang.String", "long"}));
    } catch (Exception unavailable) {
        return false;
    }
}
```

建议在 SPW Lyrics 的转换入口统一分流，覆盖同名/内嵌 TTML、原始缓存、自动获取、手动选择、后台质量升级和单曲偏移更新。只有接收返回 `true` 才跳过本次 SPL 转换；缓存仍保留原始 TTML 和普通播放器可恢复的路径。普通歌词没有原始 TTML 时继续原流程。

启用顺序不固定：对方后启用时，应重新提交当前歌曲的原始 TTML；暂停时也允许提交。禁用、卸载或权限撤销后应恢复原流程，避免继续使用缓存的“接收器存在”判断。用户明确切回本地歌词或撤回外部选择时，调用 `clearTtml`，防止同一首歌的旧外部结果残留。
