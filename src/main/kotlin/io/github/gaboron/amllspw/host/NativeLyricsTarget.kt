package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import org.objectweb.asm.*
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.jar.JarFile

/** Resolves original source trace markers after obfuscation; refuses missing/ambiguous call sites. */
internal data class NativeLyricsTarget(val owner: String, val method: String, val caller: String, val parameters: List<String>) {
    companion object {
        private const val PLAYER_MARKER = "com.xuncorp.voxzen.ui.player.component.lyrics.PlayerLyrics (PlayerLyrics.kt:"
        private const val CONTAINER_MARKER = "com.xuncorp.spc.lyrics.component.LyricsContainer (LyricsContrainer.kt:"
        private val indexCache = NativeLyricsIndexCache()

        @Synchronized
        fun discover(host: File = hostArchive(WorkshopApi::class.java), cache: NativeLyricsIndexCache = indexCache): NativeLyricsTarget {
            require(host.isFile) { "宿主代码不是可读取的 JAR，无法确认原生歌词入口" }
            val path = host.toPath()
            val before = revision(path)
            val fingerprint = cache.fingerprint(path)
            return JarFile(host).use { jar ->
                val cached = cache.read(fingerprint)?.let { index -> runCatching { resolve(jar, index) }.getOrNull() }
                if (cached != null) {
                    check(revision(path) == before) { "宿主代码在定位期间发生变化，请重新启用插件" }
                    return@use cached
                }
                val index = scan(jar)
                val target = resolve(jar, index)
                check(revision(path) == before) { "宿主代码在定位期间发生变化，请重新启用插件" }
                cache.write(fingerprint, index)
                target
            }
        }

        private fun scan(jar: JarFile): NativeLyricsIndex {
            val players = mutableListOf<String>()
            val containers = mutableListOf<String>()
            for (entry in jar.entries()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Host archive scan interrupted")
                if (!entry.name.endsWith(".class")) continue
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                // Search the complete archive for stable source markers, never guessed class names.
                val strings = String(bytes, Charsets.ISO_8859_1)
                if (PLAYER_MARKER in strings) players += entry.name
                if (CONTAINER_MARKER in strings) containers += entry.name
            }
            return NativeLyricsIndex(players, containers)
        }

        private fun resolve(jar: JarFile, index: NativeLyricsIndex): NativeLyricsTarget {
            fun reader(entryName: String, marker: String): ClassReader {
                val entry = requireNotNull(jar.getJarEntry(entryName)) { "歌词源码定位已失效" }
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                require(marker in String(bytes, Charsets.ISO_8859_1)) { "歌词源码标记已失效" }
                return ClassReader(bytes).also { require(it.className + ".class" == entryName) }
            }
            val playerClasses = index.players.map { reader(it, PLAYER_MARKER) }
            val containerClasses = index.containers.map { reader(it, CONTAINER_MARKER).className }.toSet()
            val candidates = mutableListOf<Pair<NativeLyricsTarget, String>>()
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
                                if (owner !in containerClasses) {
                                    lastOpcode = opcode
                                    return
                                }
                                val args = Type.getArgumentTypes(desc).toList()
                                val composer = args.indexOfFirst { it.className == "androidx.compose.runtime.Composer" }
                                if (composer == 10 &&
                                    args.getOrNull(1)?.className == "kotlin.jvm.functions.Function0" &&
                                    args.getOrNull(2)?.className == "kotlin.jvm.functions.Function0" &&
                                    args.getOrNull(4)?.className == "androidx.compose.ui.Modifier" &&
                                    Type.getReturnType(desc) == Type.VOID_TYPE) {
                                    require(opcode == Opcodes.INVOKESTATIC) { "歌词组件调用方式发生变化，无法安全替换" }
                                    require(lastOpcode == Opcodes.ICONST_0) { "歌词组件使用了省略参数，无法安全替换" }
                                    candidates += NativeLyricsTarget(owner.replace('/', '.'), method, reader.className.replace('/', '.'),
                                        args.take(composer).map { it.className }) to desc
                                }
                                lastOpcode = opcode
                            }
                        }
                }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            require(candidates.size == 1) { "无法唯一确认 PlayerLyrics → LyricsContainer 调用（找到 ${candidates.size} 个）" }
            val (target, descriptor) = candidates.single()
            return target.also {
                val metadata = mutableListOf<String>()
                var declarations = 0
                val owner = reader(target.owner.replace('.', '/') + ".class", CONTAINER_MARKER)
                owner.accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                        if (name == target.method && desc == descriptor) {
                            require(access and Opcodes.ACC_STATIC != 0) { "歌词组件不是静态入口，无法安全替换" }
                            declarations++
                        }
                        return null
                    }
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                        if (descriptor != "Lkotlin/Metadata;") null else object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visitArray(name: String): AnnotationVisitor? = if (name != "d2") null else object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(name: String?, value: Any?) { if (value is String) metadata += value }
                            }
                        }
                }, ClassReader.SKIP_CODE)
                require(declarations == 1) { "无法唯一确认歌词组件方法签名" }
                val lineTime = metadata.indexOf("onLineTime")
                val time = metadata.indexOf("onTime")
                require(lineTime >= 0 && time > lineTime && time < metadata.indexOf("onClick")) {
                    "宿主歌词时钟参数顺序发生变化，无法安全绑定播放进度"
                }
            }
        }

        private data class ArchiveRevision(val size: Long, val modified: FileTime, val fileKey: Any?)
        private fun revision(path: Path): ArchiveRevision = Files.readAttributes(path, BasicFileAttributes::class.java).let {
            ArchiveRevision(it.size(), it.lastModifiedTime(), it.fileKey())
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
