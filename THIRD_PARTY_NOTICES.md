# 第三方组件

本项目使用 AGPL-3.0-only。原生 Compose 绘制实现参考 [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics) 的歌词交互与演出设计；未嵌入其网页运行时。

`WordAnimation.kt`、`ActivationEnvelope.kt` 和 `ViewportMotion.kt` 中的浮起、长音强调、亮度过渡与弹簧参数以 AMLL core 的对应实现为依据移植为 Kotlin；原实现由 SteveXMH 及 AMLL 贡献者维护，采用 AGPL-3.0-only。对应源码见 [float](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/animation/float/index.ts)、[emphasize](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/animation/emphasize/index.ts)、[line-brightness](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/line-brightness.ts) 和 [spring](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/base/spring.ts)。本项目的修改包括原生时间轴计算、字形裁剪绘制及独立行错峰调度。

背景声部展开、间奏占位、悬停行框和渐隐时序还参考了 AMLL core 的 [歌词组](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/dom/lyric-group.ts)、[布局](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/lyric-player/base/layout.ts) 和 [样式](https://github.com/amll-dev/applemusic-like-lyrics/blob/main/packages/core/src/styles/lyric-player.module.css)。上述原生适配于 2026-10-18 修改，使用缓存字形、显示帧时钟和原生事件实现演出。

三点等待动画的时序由 AMLL core 的 [InterludeDotsBase](https://github.com/amll-dev/applemusic-like-lyrics/blob/ca0f41d67882d936c0365ffd99c16841ea2fa39d/packages/core/src/lyric-player/base/interlude-dots.ts)（AGPL-3.0-only）移植至 `InterludeSequence.kt` 和 `InterludeAnimation.kt`，包括交错入场、整体呼吸、分段点亮与退场曲线；`InterludePainter.kt` 负责原生绘制。原实现由 SteveXMH 及 AMLL 贡献者维护，此适配修改于 2026-10-18。

插件包中的依赖：

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| [Jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) | LGPL-2.1-or-later | 只读提取音频内嵌歌词 |
| [ASM](https://asm.ow2.io/) | BSD-3-Clause | 读取宿主字节码、确认歌词调用位置 |

许可证随包置于 `licenses/`，Jaudiotagger 对应源码及本项目源码随包置于 `source/`。Jaudiotagger 为未修改的独立 JAR，可替换兼容版本。

SPW Workshop API、Kotlin、Compose、Skia 和 Skiko 由宿主提供，不随插件重复打包。Gradle Wrapper 使用 Apache-2.0 许可证。
