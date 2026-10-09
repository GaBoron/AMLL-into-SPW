# AMLL into SPW

将 Apple Music 风格的动态歌词融入 [Salt Player for Windows](https://github.com/Moriafly/SPW)（SPW）原生播放界面。

AMLL into SPW 是一个计划中的 SPW 歌词显示增强插件，目标是通过 [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api) 接管原生歌词渲染区域，呈现接近 [Apple Music-like Lyrics（AMLL）](https://github.com/amll-dev/applemusic-like-lyrics) 的歌词效果。

项目不会要求用户打开独立窗口或进入额外页面。理想情况下，启用插件后，正常进入 SPW 播放界面即可看到新的歌词显示效果，原有的播放控制和界面交互仍然保留。

## 计划功能

- **原生界面集成**：直接替换播放页面中的歌词区域，与 SPW 现有界面保持协调。
- **TTML 歌词**：支持逐字时间轴，保留歌词中的演唱者与背景人声等信息。
- **左右对唱**：根据演唱者区分歌词的左右布局。
- **多行同时高亮**：支持时间范围重叠的歌词同时演出，而非只能激活单行。
- **背景人声效果**：为背景歌词使用独立的排版、缩放、透明度及动画。
- **动态演出**：计划实现逐字渐变、平滑滚动和歌词切换动画，并支持翻译、音译等辅助内容。

以上均为开发目标，**并非已实现的功能列表**。最终效果取决于 SPW 的实际 Hook 能力及后续技术验证结果。

## 开发状态

**目前处于规划与可行性验证阶段，尚无可供安装的版本。**

SPW Workshop API 的 `0.1.0-dev22` 版本已引入方法 Hook 和 Compose UI Hook，但仍需在支持该 API 的 SPW 宿主版本中验证真实的歌词组件入口，以及播放进度同步等关键环节。

接下来的工作计划：

- [ ] 验证能否在原生播放界面中完整替换歌词组件
- [ ] 确认歌词数据获取方式与高精度播放进度同步方案
- [ ] 接入 TTML 解析和多声部歌词时间轴
- [ ] 实现对唱、重叠歌词与背景人声显示
- [ ] 完善逐字动画、滚动效果及与 SPW 原生界面的融合

在核心技术路线确定之前，项目不会提前承诺具体的发布时间。

## 相关项目

- [Salt Player for Windows](https://github.com/Moriafly/SPW)：目标播放器。
- [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)：计划使用的插件与 Hook 接口。
- [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)：歌词效果和交互设计的主要参考。

本项目是独立的社区项目，不代表上述项目的官方合作或支持。是否直接复用 AMLL 的实现尚未确定；如后续引入第三方代码，将遵守其相应开源许可证。

> [!important]
> 此项目仅为规划，不保证一定会完成，如果验证后发现无法实现将会删除此仓库；本 README 仅为计划，不保证所有内容按原计划完成，且会在完成计划后删除并重写一份新的 README。
