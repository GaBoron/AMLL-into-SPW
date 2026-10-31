# 第三方组件与许可证

本项目采用 AGPL-3.0-only。以下列出参考或移植的上游代码，以及使用和分发的第三方依赖、数据与字体。

## Apple Music-like Lyrics

[Apple Music-like Lyrics（AMLL）](https://github.com/amll-dev/applemusic-like-lyrics) 由 SteveXMH 及 AMLL 贡献者维护，采用 AGPL-3.0-only。歌词交互、动效与部分布局算法参考或移植自 AMLL core：

| 范围 | 上游源码 |
| --- | --- |
| 浮起、长音强调、亮度过渡与弹簧 | [Float](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/animation/float/index.ts)、[Emphasize](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/animation/emphasize/index.ts)、[LineBrightness](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/line-brightness.ts)、[Spring](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/base/spring.ts) |
| 背景声部、歌词布局及交互 | [LyricGroup](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/lyric-group.ts)、[Layout](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/base/layout.ts)、[样式](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/styles/lyric-player.module.css) |
| 间奏三点动画 | [InterludeDots](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/lyric-player/base/interlude-dots.ts) |
| 歌词断行与词分组 | [LyricLineBreak](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/utils/lyric-line-break.ts)、[LineBalancer](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/utils/line-balancer.ts)、[LyricSplitWords](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/utils/lyric-split-words.ts) |
| 底栏布局与交互 | [BottomLine](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/lyric-player/dom/bottom-line.ts)、[Focus](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/lyric-player/base/focus.ts) |
| Ruby 注音布局与动画 | [LyricLine](https://github.com/amll-dev/applemusic-like-lyrics/blob/5fee356362353dbb24ce9bea3a62536478040de7/packages/core/src/lyric-player/dom/lyric-line.ts)、[MaskAnimator](https://github.com/amll-dev/applemusic-like-lyrics/blob/5fee356362353dbb24ce9bea3a62536478040de7/packages/core/src/lyric-player/dom/animation/mask/animator-web.ts) |

## 第三方依赖

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| [Jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) | LGPL-2.1-or-later | 音频标签读取 |
| [ASM](https://asm.ow2.io/) | BSD-3-Clause | 字节码读取 |
| [Kuromoji IPADIC 0.9.0](https://github.com/atilika/kuromoji) | Apache-2.0；词典另有 NAIST / ICOT 条款 | 日语分词与读音 |
| Gradle Wrapper | Apache-2.0 | 构建工具 |

相关许可证见 `licenses/`。Kuromoji 的词典版权与许可说明见 `licenses/Kuromoji-IPADIC-NOTICE.txt`。Jaudiotagger 作为独立 JAR 分发，其对应源码随包置于 `source/`，可替换为兼容版本。

## 数据与字体

- **[chinese-poetry](https://github.com/chinese-poetry/chinese-poetry/tree/b8594f81a89752241442f2ce267d6f66f96704ee)**：内置诗词数据的来源，MIT，Copyright (c) 2016 JackeyGao。许可证见 `licenses/chinese-poetry-MIT.txt`。
- **[ToJyutping 3.2.0](https://github.com/CanCLID/ToJyutping/tree/a692787e9af65b27310580604b1d69b96b2165be)**：内置粤拼词典 `romanization/jyutping.tsv` 的来源，由 Cantonese Computational Linguistics Infrastructure Development Workgroup（CanCLID）维护，BSD-2-Clause，Copyright (c) 2024 Cantonese Computational Linguistics Infrastructure Development Workgroup。许可证见 `licenses/ToJyutping-BSD-2-Clause.txt`。
- **[思源黑体 SC 2.005](https://github.com/adobe-fonts/source-han-sans/releases/tag/2.005R)**：内置字体 `SourceHanSansSC-VF.ttf`，Copyright 2014–2025 Adobe，SIL Open Font License 1.1。许可证见 `licenses/OFL-1.1-Source-Han-Sans.txt`。

上述第三方数据与字体遵循各自的许可证，不适用本项目代码的 AGPL-3.0-only 许可证。
