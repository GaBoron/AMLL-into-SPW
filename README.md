# AMLL into SPW

将 Apple Music 风格的动态歌词融入 [Salt Player for Windows](https://github.com/Moriafly/SPW)（SPW）原生播放界面。

插件通过 [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api) 接入原生播放页面，以 Compose / Skia 呈现 [Apple Music-like Lyrics（AMLL）](https://github.com/amll-dev/applemusic-like-lyrics) 风格的歌词效果。正常进入 SPW 播放界面即可使用，保留宿主播放控制，无需打开独立窗口。

## 歌词显示

- 逐字渐变高亮、浮动和长音发光；行级歌词使用整行高亮。
- 按演唱者左右对唱，双方在对侧留出间距，允许多个声部同时高亮；背景人声随所属歌词组进入时展开，保留完整尾音，未展开时不预留空行。
- 弹簧滚动、错落位移、活动行缩放、非活动行模糊和视口边缘淡出。
- 密集换行时缩短过渡并平滑跟随，同步高亮与声部显示；跟随偏差过大时平滑追上，普通段落保留弹簧与错落动画。
- 显示翻译、音译和 Ruby 注音；长句参考标点、空格和词界均衡换行，并保留歌词原有的换行。
- 歌词区域透明衔接 SPW 整页背景，前奏与长间奏在独立占位中显示三点提示。
- 鼠标悬停显示歌词行框，点击歌词跳转并平滑滚动；滚轮、方向键和 Page Up / Page Down 浏览，停止操作约三秒后恢复跟随；双击、Home 或 Esc 立即恢复跟随。

在插件设置中可切换模糊、缩放、发光、弹簧动画、隐藏已唱歌词和音译。开启“彩色歌词”后，歌词、翻译和间奏提示使用歌曲封面的明亮主色；默认关闭，无封面时使用白色。隐藏已唱歌词时，整组声部唱完后渐隐；暂停或手动浏览时重新显示。减少动态效果模式保留准确高亮，关闭模糊、发光、缩放与弹簧动画。

## 歌词来源

按以下顺序读取：

1. 歌曲同名 `.ttml` 文件。
2. 音频标签中的原始 TTML；普通歌词标签不会挡住其他 TTML 标签。
3. 歌曲同名 `.lrc` 文件中的 TTML 内容。
4. 其他插件通过原始 TTML 接口提供的歌词。
5. SPW 已解析的 SPL / LRC 时间轴。

原始 TTML 直接解析，保留原文，不经过 SPL。没有 TTML 时，使用宿主提供的歌词行、逐字单元和翻译生成内存中的兼容 TTML；不改写音频、歌词文件或其他插件缓存，也不为行级歌词编造逐字时间戳。

[SPW Lyrics](https://github.com/GaBoron/SPW-Lyrics) 已接入原始 TTML 互通，同时启用即可传递完整歌词信息。其他歌词插件也可使用同一接口；协议、偏移约定和通用调用模板见 [TTML 接入指南](docs/TTML互通.md)，其中以 SPW Lyrics 为接入示例。

## 使用要求

使用 **SPW 1.19.0 / Workshop API 0.1.0-dev23**。启用时需要授权“修改宿主类 / 界面”和“读取曲库”。

从 [Releases](https://github.com/GaBoron/AMLL-into-SPW/releases/latest) 下载 `.spmod` 文件，使用 SPW 的插件安装功能安装，授权后启用。歌词获取插件可保持原有流程，已接入的插件会自动提交原始 TTML；歌曲本地 TTML 仍优先显示。

停用插件后恢复宿主歌词界面，并关闭 TTML 接收接口。

## 相关项目

- [Salt Player for Windows](https://github.com/Moriafly/SPW)：目标播放器。
- [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)：插件与原生 Hook 接口。
- [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)：歌词效果和交互设计的主要参考。

本项目是独立的社区项目，不代表上述项目的官方合作或支持。使用 AGPL-3.0-only，第三方组件见 [许可证说明](THIRD_PARTY_NOTICES.md)。
