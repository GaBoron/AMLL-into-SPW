# 第三方组件

本项目使用 AGPL-3.0-only。原生 Compose 绘制实现参考 [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics) 的歌词交互与演出设计；未嵌入其网页运行时。

`WordAnimation.kt`、`ActivationEnvelope.kt` 和 `ViewportMotion.kt` 中的浮起、长音强调、亮度过渡与弹簧参数以 AMLL core 的对应实现为依据移植为 Kotlin；原实现由 SteveXMH 及 AMLL 贡献者维护，采用 AGPL-3.0-only。对应源码见 [float](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/animation/float/index.ts)、[emphasize](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/animation/emphasize/index.ts)、[line-brightness](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/line-brightness.ts) 和 [spring](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/base/spring.ts)。本项目的修改包括原生时间轴计算、字形裁剪绘制及独立行错峰调度。

背景声部展开、间奏占位、悬停行框和渐隐时序还参考了 AMLL core 的 [歌词组](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/lyric-group.ts)、[布局](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/base/layout.ts) 和 [样式](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/styles/lyric-player.module.css)。上述原生适配于 2026-10-18 修改，使用缓存字形、显示帧时钟和原生事件实现演出。

三点等待动画的时序由 AMLL core 的 [InterludeDotsBase](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/lyric-player/base/interlude-dots.ts)（AGPL-3.0-only）移植至 `InterludeSequence.kt` 和 `InterludeAnimation.kt`，包括交错入场、整体呼吸、分段点亮与退场曲线；`InterludePainter.kt` 负责原生绘制。原实现由 SteveXMH 及 AMLL 贡献者维护，此适配修改于 2026-10-18。

`BalancedLineBreaks.kt` 和 `LyricLineWrapping.kt` 根据 AMLL core 的 [均衡断点算法](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/utils/lyric-line-break.ts)、[行平衡器](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/utils/line-balancer.ts)及[词分组](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/utils/lyric-split-words.ts)（AGPL-3.0-only）移植，保留行宽代价及标点、空格和 CJK 词界的权重，改用原生文字测量和文字索引映射；对唱留白沿用同一版本样式中的 15% 比例。原实现由 SteveXMH 及 AMLL 贡献者维护，此适配修改于 2026-10-18。

`CoverLyricColor.kt` 的主要色系选择和明亮歌词色参考 [SPW Island 的封面取色](https://github.com/GaBoron/SPW-island/blob/8ad331daaf1fb6217cdbe0337e34fa3e8db8dd61/src/main/kotlin/io/github/gaboron/spwisland/platform/CoverColorExtractor.kt)及[调色](https://github.com/GaBoron/SPW-island/blob/8ad331daaf1fb6217cdbe0337e34fa3e8db8dd61/src/main/kotlin/io/github/gaboron/spwisland/ui/IslandPalette.kt)（GaBoron，GPL-3.0-only），限制封面采样数量并适配原生歌词绘制。修改于 2026-10-18；许可证见 `licenses/GPL-3.0.txt`。

`LyricFooterMotion.kt`、`LyricFooterLayout.kt`、`LyricFooterPainter.kt` 及 `ViewportMotion.kt` 的末尾底栏行为依据 AMLL core 的 [BottomLine](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/lyric-player/dom/bottom-line.ts)、[焦点状态机](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/lyric-player/base/focus.ts)及[样式](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/styles/lyric-player.module.css)（AGPL-3.0-only）适配，包括独立占位、曲末聚焦、弹簧入场及透明度过渡。底栏模糊的过渡与绘制使用本项目的 `LyricBlurMotion.kt`、`LyricTextBlur.kt` 等原生实现。原实现由 SteveXMH 及 AMLL 贡献者维护；本项目于 2026-10-18 改为原生文字布局与绘制，并接入元数据和来源内容。

`poetry/poems.tsv` 包含 [chinese-poetry/chinese-poetry](https://github.com/chinese-poetry/chinese-poetry/tree/b8594f81a89752241442f2ce267d6f66f96704ee)（MIT，Copyright (c) 2016 JackeyGao）的 1,524 首完整短篇选集，保留来源文字及段落，转换为离线 TSV。选篇来自 `诗经/shijing.json`、`楚辞/chuci.json`、`曹操诗集/caocao.json`、`全唐诗/唐诗三百首.json`、`五代诗词/huajianji/huajianji-{1..9,x}-juan.json`、`五代诗词/nantang/poetrys.json`、`宋词/宋词三百首.json`、`元曲/yuanqu.json` 和 `纳兰性德/纳兰性德诗集.json`。原许可证随包置于 `licenses/chinese-poetry-MIT.txt`。

插件包中的依赖：

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| [Jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) | LGPL-2.1-or-later | 只读提取音频内嵌歌词 |
| [ASM](https://asm.ow2.io/) | BSD-3-Clause | 读取宿主字节码、确认歌词调用位置 |
| [Kuromoji IPADIC 0.9.0](https://github.com/atilika/kuromoji) | Apache-2.0；词典另含 NAIST / ICOT 条款 | 本地日语分词与读音分析 |

许可证随包置于 `licenses/`，Jaudiotagger 对应源码及本项目源码随包置于 `source/`。Jaudiotagger 为未修改的独立 JAR，可替换兼容版本。

Kuromoji 及其未修改的 IPADIC 词典随包提供；Apache-2.0 许可证见 `licenses/Apache-2.0.txt`，版权及词典声明见 `licenses/Kuromoji-IPADIC-NOTICE.txt`。`LyricRubyLayout.kt` 的注音居中、半号字体及独立占宽设计参考 AMLL core 的 [歌词行](https://github.com/amll-dev/applemusic-like-lyrics/blob/5fee356362353dbb24ce9bea3a62536478040de7/packages/core/src/lyric-player/dom/lyric-line.ts)和[样式](https://github.com/amll-dev/applemusic-like-lyrics/blob/5fee356362353dbb24ce9bea3a62536478040de7/packages/core/src/styles/lyric-player.module.css)（AGPL-3.0-only）；`AnnotationMotion.kt` 和 `LyricRubyPainter.kt` 参考其整词容器的[上浮](https://github.com/amll-dev/applemusic-like-lyrics/blob/5fee356362353dbb24ce9bea3a62536478040de7/packages/core/src/lyric-player/dom/animation/float/index.ts)及[渐变遮罩](https://github.com/amll-dev/applemusic-like-lyrics/blob/5fee356362353dbb24ce9bea3a62536478040de7/packages/core/src/lyric-player/dom/animation/mask/animator-web.ts)，注音共用原词播放进度，逐字符强调保留在正文。上述实现于 2026-10-22 适配为 Compose 原生测量和绘制，保留原始音译时间，不嵌入网页运行时。

SPW Workshop API、Kotlin、Compose、Skia 和 Skiko 由宿主提供，不随插件重复打包。Gradle Wrapper 使用 Apache-2.0 许可证。

`romanization/jyutping.tsv` 由 Cantonese Computational Linguistics Infrastructure Development Workgroup（CanCLID）的 [ToJyutping 3.2.0](https://github.com/CanCLID/ToJyutping/tree/a692787e9af65b27310580604b1d69b96b2165be) 中的 `src/ToJyutping/trie.txt` 转换，采用 BSD-2-Clause 许可证，Copyright (c) 2024 Cantonese Computational Linguistics Infrastructure Development Workgroup。转换将压缩读音编号解码为文本、展开为排序 TSV，并仅保留可逐字符对应的词条；保留原词条的读音及候选顺序，共 48,262 条（含 30,206 个单字）。`CantoneseReadings.kt` 使用该数据离线进行最长词组匹配，未嵌入 Python 运行时。原许可证随包置于 `licenses/ToJyutping-BSD-2-Clause.txt`；词典不适用本项目的 AGPL-3.0-only 许可证。

内置字体为 Adobe [思源黑体 SC（Source Han Sans SC）2.005](https://github.com/adobe-fonts/source-han-sans/releases/tag/2.005R)，使用官方未修改的 `SourceHanSansSC-VF.ttf` 可变字体（字重轴 250–900）。Copyright 2014–2025 Adobe，采用 SIL Open Font License 1.1，保留字体内嵌版权信息与保留字体名称；原许可证见 `licenses/OFL-1.1-Source-Han-Sans.txt`。该字体不适用本项目的 AGPL-3.0-only 许可证。
