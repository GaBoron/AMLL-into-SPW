<h1 align="center">AMLL into SPW</h1>

<p align="center">
  将 Apple Music 风格的动态歌词融入 <a href="https://github.com/Moriafly/SPW">Salt Player for Windows</a> 原生播放界面。
</p>

<p align="center">
  <a href="https://github.com/GaBoron/AMLL-into-SPW/releases/latest"><img alt="最新版本" src="https://img.shields.io/github/v/release/GaBoron/AMLL-into-SPW?label=Release&color=6750A4"></a>
  <img alt="适配 SPW 1.19.0" src="https://img.shields.io/badge/SPW-1.19.0-3A7AFE">
  <img alt="Workshop API dev23" src="https://img.shields.io/badge/Workshop_API-dev23-64748B">
  <a href="LICENSE"><img alt="许可证" src="https://img.shields.io/badge/License-AGPL--3.0--only-2E8B57"></a>
</p>

<p align="center">
  <a href="https://github.com/GaBoron/AMLL-into-SPW/releases/latest">下载插件</a> ·
  <a href="docs/功能说明.md">功能详解</a> ·
  <a href="https://github.com/GaBoron/AMLL-into-SPW/issues">问题反馈</a>
</p>

通过 SPW Workshop API 接入原生播放页面，使用 Compose / Skia 绘制歌词。**无需独立窗口**，进入播放界面即可体验，并保留原有播放控制。

## ✨ 功能亮点

| 功能 | 说明 |
| --- | --- |
| 🎵 动态歌词 | 逐字渐变高亮、浮动、长音发光、平滑滚动和过渡效果 |
| 🎤 多声部 | 左右对唱、同时演唱、可展开的背景人声 |
| 🌐 多语言信息 | 翻译、音译、Ruby 注音、带时间戳的逐字音译，以及实验性日语逐字罗马注音 |
| 🖱️ 交互浏览 | 点击歌词跳转；鼠标与键盘浏览后自动恢复跟随 |
| 🎨 外观设置 | 字重、字号、歌词颜色和动效调节；支持减少动态效果、隐藏已唱歌词 |
| 📖 纯音乐模式 | 在纯音乐页面离线展示诗词，支持手动阅读 |
| 🧩 原生集成 | 透明衔接 SPW 背景；歌词末尾显示元数据、歌词来源与插件补充信息 |

更多动画细节、设置选项及实验性注音的适用范围，见 [功能说明](docs/功能说明.md)。

## 📦 安装与使用

**要求：SPW 1.19.0 / Workshop API 0.1.0-dev23**

1. 从 [Releases](https://github.com/GaBoron/AMLL-into-SPW/releases/latest) 下载 `.spmod` 文件，通过 SPW 的插件安装功能导入。
2. 授予“修改宿主类 / 界面”和“读取曲库”权限后启用插件。
3. 进入 SPW 播放界面即可查看歌词，并可在插件设置中调整显示效果。

停用插件后会恢复 SPW 原有歌词界面，并关闭相关接口、清理外部信息。

## 🎼 歌词来源

优先读取本地原始 **TTML**，其次使用其他插件提交的原始 TTML，最后回退到 SPW 已解析的 SPL / LRC 歌词。完整顺序见 [歌词来源与优先级](docs/功能说明.md#歌词来源与优先级)。

**不修改音频和歌词文件，也不为逐行歌词编造逐字时间轴。**

## 🔌 与其他插件互通

- **[原始 TTML 接口](docs/TTML互通.md)**：已与 [SPW Lyrics](https://github.com/GaBoron/SPW-Lyrics) 互通，可接收完整歌词及声部、翻译、注音等信息；其他歌词插件也能接入。
- **[底部信息栏接口](docs/底部信息栏接口.md)**：其他插件可提供歌曲匹配、作者等 key/value 信息，无需提交 TTML。

## 📚 相关项目与许可

- [Salt Player for Windows](https://github.com/Moriafly/SPW)：目标播放器。
- [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)：插件与原生界面接入接口。
- [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)：歌词效果与交互设计参考。

本项目是独立的社区项目，不代表上述项目的官方合作或支持。采用 **AGPL-3.0-only** 许可证；第三方组件见 [许可证说明](THIRD_PARTY_NOTICES.md)。
