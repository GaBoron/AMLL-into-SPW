package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import org.objectweb.asm.*
import java.io.File
import java.net.JarURLConnection
import java.util.jar.JarFile

/** Resolves original source trace markers after obfuscation; refuses missing/ambiguous call sites. */
internal data class NativeLyricsTarget(val owner: String, val method: String, val caller: String, val parameters: List<String>) {
    companion object {
        private const val PLAYER_MARKER = "com.xuncorp.voxzen.ui.player.component.lyrics.PlayerLyrics (PlayerLyrics.kt:"
        private const val CONTAINER_MARKER = "com.xuncorp.spc.lyrics.component.LyricsContainer (LyricsContrainer.kt:"

        fun discover(host: File = hostArchive(WorkshopApi::class.java)): NativeLyricsTarget {
            require(host.isFile) { "宿主代码不是可读取的 JAR，无法确认原生歌词入口" }
            return JarFile(host).use { jar ->
                val playerClasses = mutableListOf<ClassReader>()
                val containerClasses = mutableSetOf<String>()
                for (entry in jar.entries()) {
                    if (!entry.name.endsWith(".class")) continue
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    // Search only the two stable compiler source markers, never guessed class names.
                    val strings = String(bytes, Charsets.ISO_8859_1)
                    if (PLAYER_MARKER in strings) playerClasses += ClassReader(bytes)
                    if (CONTAINER_MARKER in strings) containerClasses += ClassReader(bytes).className
                }
                val candidates = mutableListOf<NativeLyricsTarget>()
                playerClasses.forEach { reader ->
                    reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                            object : MethodVisitor(Opcodes.ASM9) {
                                var lastOpcode = -1
                                override fun visitInsn(opcode: Int) { lastOpcode = opcode }
                                override fun visitVarInsn(opcode: Int, variable: Int) { lastOpcode = opcode }
                                override fun visitIntInsn(opcode: Int, operand: Int) { lastOpcode = opcode }
                                override fun visitLdcInsn(value: Any?) { lastOpcode = Opcodes.LDC }
                                override fun visitMethodInsn(opcode: Int, owner: String, method: String, desc: String, isInterface: Boolean) {
                                    val args = Type.getArgumentTypes(desc).toList()
                                    val composer = args.indexOfFirst { it.className == "androidx.compose.runtime.Composer" }
                                    if (owner in containerClasses && composer == 10 &&
                                        args.getOrNull(1)?.className == "kotlin.jvm.functions.Function0" &&
                                        args.getOrNull(2)?.className == "kotlin.jvm.functions.Function0" &&
                                        args.getOrNull(4)?.className == "androidx.compose.ui.Modifier" &&
                                        Type.getReturnType(desc) == Type.VOID_TYPE) {
                                        require(lastOpcode == Opcodes.ICONST_0) { "歌词组件使用了省略参数，无法安全替换" }
                                        candidates += NativeLyricsTarget(owner.replace('/', '.'), method, reader.className.replace('/', '.'), args.take(composer).map { it.className })
                                    }
                                    lastOpcode = opcode
                                }
                            }
                    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
                require(candidates.size == 1) { "无法唯一确认 PlayerLyrics → LyricsContainer 调用（找到 ${candidates.size} 个）" }
                candidates.single().also { target ->
                    val metadata = mutableListOf<String>()
                    ClassReader(jar.getInputStream(jar.getJarEntry(target.owner.replace('.', '/') + ".class"))).accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                            if (descriptor != "Lkotlin/Metadata;") null else object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visitArray(name: String): AnnotationVisitor? = if (name != "d2") null else object : AnnotationVisitor(Opcodes.ASM9) {
                                    override fun visit(name: String?, value: Any?) { if (value is String) metadata += value }
                                }
                            }
                    }, ClassReader.SKIP_CODE)
                    val lineTime = metadata.indexOf("onLineTime")
                    val time = metadata.indexOf("onTime")
                    require(lineTime >= 0 && time > lineTime && time < metadata.indexOf("onClick")) {
                        "宿主歌词时钟参数顺序发生变化，无法安全绑定播放进度"
                    }
                }
            }
        }

        private fun hostArchive(type: Class<*>): File {
            val location = type.protectionDomain?.codeSource?.location
            if (location?.protocol == "file") return File(location.toURI())

            // The host can omit CodeSource locations while retaining a local JAR resource URL.
            val name = type.name.replace('.', '/') + ".class"
            val resource = type.getResource("/$name")
            if (resource?.protocol == "jar") {
                val archive = (resource.openConnection() as JarURLConnection).jarFileURL
                if (archive.protocol == "file") return File(archive.toURI())
            }
            error("无法定位宿主代码归档：${type.name} " +
                "(source=${location?.protocol ?: "none"}, resource=${resource?.protocol ?: "none"})")
        }
    }
}
