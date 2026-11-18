# 回归检查

安装 JDK 25 后，在仓库根目录运行：

```powershell
.\gradlew.bat regressionTest --no-daemon
```

`test` 和 `check` 也会运行这些检查。首次运行需要下载构建依赖；检查本身不联网、不连接 SPW、不写真实配置。使用 Kotlin 标准库断言，无额外测试框架；标准 `test` 任务只委托 `regressionTest`，自身的 JUnit 发现阶段显示 `SKIPPED`。构建输出留在忽略的 `build/`。

覆盖合成 FFT 在 44.1/48/96/192 kHz 的频段定位、瞬态峰谷、暂停衰减、DC/噪声/无效数据拒绝、格式切换连续性和短缓存抖动过滤。音频解码、宿主音量独立性与实际视觉观感需在 SPW 中验证。

Footer 检查通过进程内 JMX 验证宿主歌曲兼容、外部歌曲提交与撤回、过期和切歌后的清理，以及并发读取旧快照时的保护。实际底栏显示需在 SPW 中验证。
