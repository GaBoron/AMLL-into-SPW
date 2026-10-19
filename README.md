<div align="center">

# AMLL into SPW

**将 Apple Music 风格的动态歌词带入 Salt Player for Windows 原生播放界面。**

无需独立窗口 · 保留原有播放控制

[下载安装](https://github.com/GaBoron/AMLL-into-SPW/releases/latest) · [完整功能](docs/功能说明.md) · [TTML 接入指南](docs/TTML互通.md) · [插件兼容性评估](docs/插件兼容性评估.md)

</div>

## 功能

| | |
| --- | --- |
| **逐字歌词** | 渐变高亮、浮动、长音发光，兼容行级歌词 |
| **自然动效** | 弹簧滚动、错落位移、模糊、缩放与间奏提示 |
| **多声部显示** | 左右对唱、背景人声与多个声部同时高亮 |
| **完整歌词信息** | 翻译、音译、Ruby 注音、歌曲元数据及歌词来源 |
| **交互与外观** | 点击跳转、手动浏览、字号字重、封面取色及减少动态效果 |

基于 [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)，使用 Compose / Skia 绘制，视觉与交互参考 [Apple Music-like Lyrics（AMLL）](https://github.com/amll-dev/applemusic-like-lyrics)。

[查看功能与设置的详细说明 →](docs/功能说明.md)

## 安装

**要求：** [Salt Player for Windows（SPW）](https://github.com/Moriafly/SPW) 1.19.0 / Workshop API 0.1.0-dev23。

1. 在 [Releases](https://github.com/GaBoron/AMLL-into-SPW/releases/latest) 下载 `.spmod`。
2. 在 SPW 的插件管理中安装并启用。
3. 授权「修改宿主类 / 界面」和「读取曲库」。

启用后直接打开 SPW 播放界面；停用插件即可恢复原生歌词界面。

## 歌词来源

优先读取本地原始 TTML，其次接收其他插件提供的 TTML，最后回退到 SPW 已解析的歌词时间轴。**不会修改原始音频、歌词文件或其他插件缓存。**

[SPW Lyrics](https://github.com/GaBoron/SPW-Lyrics) 已支持原始 TTML 互通，两个插件同时启用即可传递完整歌词信息。

- [歌词读取顺序与回退规则](docs/功能说明.md#歌词来源与优先级)
- [面向其他歌词插件的 TTML 接口](docs/TTML互通.md)

## 关于

AMLL into SPW 是独立社区项目，与 SPW、AMLL 的原作者及团队没有官方合作或从属关系。

许可证为 [AGPL-3.0-only](LICENSE)；第三方组件及授权见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
