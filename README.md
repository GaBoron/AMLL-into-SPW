# AMLL into SPW

将 [Apple Music-like Lyrics（AMLL）](https://github.com/amll-dev/applemusic-like-lyrics) 风格的动态歌词融入 [Salt Player for Windows（SPW）](https://github.com/Moriafly/SPW) 原生播放界面。

插件通过 SPW Workshop API 接入播放页面，使用 Compose / Skia 绘制歌词。**无需独立窗口**，进入 SPW 播放界面即可使用，并保留原有播放控制。

## 功能亮点

- **动态歌词**：逐字渐变高亮、浮动、长音发光，配合平滑滚动、缩放和模糊效果。
- **多声部显示**：支持左右对唱、同时演唱及可展开的背景人声。
- **完整歌词信息**：显示翻译、音译、Ruby 注音；支持带时间戳的逐字音译，以及实验性的日语逐字罗马注音。
- **阅读与跳转**：点击歌词跳转播放，支持鼠标和键盘浏览，随后自动恢复跟随。
- **外观定制**：可调整字体、字号、动效和歌词颜色，支持减少动态效果、隐藏已唱歌词等选项。
- **纯音乐模式**：在纯音乐页面离线展示诗词，保留其他插件提交的补充信息。
- **原生界面集成**：歌词直接衔接 SPW 背景，末尾可查看歌曲元数据、歌词来源和第三方信息。

动画细节、设置说明及实验性注音的适用范围见 [歌词与界面说明](docs/歌词与界面说明.md)。

## 安装与使用

使用 **SPW 1.19.0 / Workshop API 0.1.0-dev23**。

1. 从 [Releases](https://github.com/GaBoron/AMLL-into-SPW/releases/latest) 下载 `.spmod` 文件，并通过 SPW 的插件安装功能导入。
2. 启用插件，授予“修改宿主类 / 界面”和“读取曲库”权限。
3. 正常进入 SPW 播放界面即可查看歌词；所需效果可在插件设置中调整。

插件停用后，恢复 SPW 原有的歌词界面，同时关闭插件提供的接口并清理外部信息。

## 歌词来源

优先使用原始 **TTML**，依次读取同名 `.ttml` 文件、音频内嵌 TTML、同名 `.lrc` 中的 TTML，以及其他插件通过接口提交的 TTML。没有原始 TTML 时，回退到 SPW 已解析的 SPL / LRC 歌词。

回退转换仅在内存中进行，不修改音频或歌词文件，也不会为逐行歌词编造逐字时间轴。完整优先级见 [歌词与界面说明](docs/歌词与界面说明.md#歌词来源与回退)。

## 与其他插件互通

- **原始 TTML**：[SPW Lyrics](https://github.com/GaBoron/SPW-Lyrics) 已支持直接向本插件提交 TTML，以保留完整声部、翻译和注音信息；其他歌词插件也可接入。[查看 TTML 接入指南](docs/TTML互通.md)。
- **底部信息栏**：其他插件可独立提供歌曲匹配、作者等 key/value 信息，无需提交 TTML。[查看底部信息栏接口](docs/底部信息栏接口.md)。

## 相关项目与许可

- [Salt Player for Windows](https://github.com/Moriafly/SPW)：目标播放器。
- [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)：插件及原生界面接入接口。
- [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)：歌词效果与交互设计参考。

本项目为独立社区项目，不代表上述项目的官方合作或支持。采用 **AGPL-3.0-only** 许可证；第三方组件见 [许可证说明](THIRD_PARTY_NOTICES.md)。
