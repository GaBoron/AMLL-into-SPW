# 原始 TTML 接入指南

AMLL into SPW 向歌词提供插件开放进程内 TTML 接口。发送端可在完成本地读取、缓存命中或在线获取后直接提交原始 XML，保留演唱者、背景人声、翻译、音译和注音。本文提供通用接入模板，末尾以已完成接入的 SPW Lyrics 为例。

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

`ttml` 必须是未经 SPL 转换的完整 XML。上限为 4,000,000 个字符，禁用 DTD 和外部实体。`offsetMs` 是发送端的额外单曲偏移：正数延后显示，负数提前显示，范围为 ±86,400,000 毫秒。XML 时间戳保持原值；不要先给 XML 加同一份偏移，也不要再次传入 SPW 全局歌词偏移。

`submitTtml` 会读取当前歌曲并解析 XML，必须在后台线程调用。返回 `true` 表示原始 TTML 已被接受；本地 TTML 优先时不代表外部歌词会立即显示。返回 `false` 或发生 JMX 调用异常时，应继续既有的 SPL/普通歌词流程。接收器会拒收换歌后的迟到结果、无效 XML、空时间轴和超限输入。重复提交同一首歌是允许的。`clearTtml` 返回 `false` 也可能只是没有对应的外部歌词可撤回。

优先级为：**同名 `.ttml` → 音频标签中的 TTML → 同名 `.lrc` 中的 TTML → 本接口的 TTML → SPW 已解析的 SPL/LRC**。本地 TTML 优先级不受外部提交影响。`offsetMs` 只应用于本接口提供的 TTML。

## 通用调用模板

下面的 Java 类只依赖 JDK，可用于任意 SPW 歌词插件的后台输出流程。每次调用都重新检查接收器，兼容双方插件独立启停。

```java
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;

public final class TtmlClient {
    public static boolean submit(String trackId, String path, String rawTtml, long delayMs) {
        if (path == null || path.isBlank() || rawTtml == null) return false;
        return invoke("submitTtml",
                new Object[]{trackId, path, rawTtml, delayMs},
                new String[]{"java.lang.String", "java.lang.String", "java.lang.String", "long"});
    }

    public static boolean clear(String trackId, String path) {
        if (path == null || path.isBlank()) return false;
        return invoke("clearTtml", new Object[]{trackId, path},
                new String[]{"java.lang.String", "java.lang.String"});
    }

    private static boolean invoke(String operation, Object[] arguments, String[] signature) {
        try {
            var server = ManagementFactory.getPlatformMBeanServer();
            var target = new ObjectName("io.github.gaboron.amllspw:type=TtmlReceiver,version=1");
            if (!server.isRegistered(target)
                    || !Integer.valueOf(1).equals(server.getAttribute(target, "ProtocolVersion"))
                    || !Boolean.TRUE.equals(server.getAttribute(target, "Active"))) return false;
            return Boolean.TRUE.equals(server.invoke(target, operation, arguments, signature));
        } catch (Exception unavailable) {
            return false;
        }
    }
}
```

## 输出与生命周期

建议用统一的后台输出入口覆盖同名/内嵌 TTML、原始缓存、自动获取、手动选择、后台质量升级和单曲偏移更新：

1. 保存原始 XML，并保留现有普通歌词输出或可恢复的转换路径。没有原始 TTML 时继续普通歌词流程；不要把 SPL 转回 TTML 冒充原始歌词。
2. 提交前后核对歌曲 ID、路径和选择版本，忽略切歌或撤回后的异步结果。同一首歌的新旧结果也应按版本区分。提交、撤回和重试通过同一队列串行执行，避免旧任务清除新选择。
3. 传递未改写的 XML 和独立单曲偏移；偏移改变时重新提交。即使接收成功，也可以继续向宿主输出普通歌词时间轴，供其他歌词界面、插件停用后的回退等场景使用。
4. 接收器后启用或重新注册时，重新提交当前选择；暂停时也允许提交。可监听 `MBeanServerDelegate` 的注册/注销通知并更新实例代号，或定期重新检测。仅缓存“存在”这一布尔值会漏掉两次检测之间的停用再启用。
5. 用户撤回外部选择、切换到普通歌词或发送端停用时，调用 `clear`。接收器停用、卸载或权限撤销后继续普通歌词输出，清理监听器和后台任务；再次可用后重新协商。

## 示例：SPW Lyrics

[SPW Lyrics](https://github.com/GaBoron/SPW-Lyrics) 已实现协议 1，无需额外依赖对方的 JAR。双方启用后会自动协商原始 TTML 输出。

- [JMX 接收器适配](https://github.com/GaBoron/SPW-Lyrics/blob/main/src/main/kotlin/dev/gaboron/spwlyrics/integration/JmxTtmlReceiver.kt) 负责检测版本与启用状态、提交和撤回，并监听注册变化以识别接收器重启。
- [歌词输出路由](https://github.com/GaBoron/SPW-Lyrics/blob/main/src/main/kotlin/dev/gaboron/spwlyrics/application/LyricsOutputRouter.kt) 汇总原始 TTML、缓存和单曲偏移，以歌曲身份和选择版本过滤迟到结果，并通过后台队列及周期检测处理重新接入。
- 原始 TTML 用于 AMLL 显示，同时保留普通宿主歌词时间轴；原始 XML 随歌词缓存保存。自动获取、手动选择和后续更新共用输出入口。

其他插件可以复用上述接入方式，并保留自己的歌词来源、缓存和选择逻辑。
