package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import org.objectweb.asm.*
import java.io.File
import java.util.jar.JarFile

/** Replaces the page's explicit content slot; Workshop skips replacements with nonzero default masks. */
internal data class NativePlayerTarget(
    val page: Member,
    val caller: String,
    val controls: Map<String, Member>,
) {
    data class Member(val owner: String, val name: String, val descriptor: String)
    private data class Call(val target: Member, val opcode: Int, val defaultMask: Int?)
    private data class Body(val member: Member, val access: Int, val markers: List<String>, val calls: List<Call>)

    val parameters: List<String> get() = Type.getArgumentTypes(page.descriptor).take(2).map { it.className }

    companion object {
        private const val PAGE = "com.xuncorp.voxzen.ui.player.PlayerScreen (PlayerScreen.kt:"
        private const val SHEET = "com.xuncorp.voxzen.ui.main.surface.PlayerBottomSheet.<anonymous>.<anonymous> (MainSurface.kt:"
        private const val THEME = "com.moriafly.salt.ui.ChangeSaltThemeIsDark (SaltTheme.kt:"
        private const val COMPOSER = "Landroidx/compose/runtime/Composer;"
        private val controlMarkers = mapOf(
            "mode" to "com.xuncorp.voxzen.ui.player.mini.PlatformPlaybackModeControl (",
            "queue" to "com.xuncorp.voxzen.ui.player.mini.PlatformPlaybackQueueControl (",
        )

        fun discover(host: File = NativeLyricsTarget.hostArchive(WorkshopApi::class.java)): NativePlayerTarget =
            JarFile(host).use { jar ->
                val bodies = mutableListOf<Body>()
                for (entry in jar.entries()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Player discovery interrupted")
                    if (!entry.name.endsWith(".class")) continue
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    val pool = String(bytes, Charsets.ISO_8859_1)
                    if (PAGE in pool || SHEET in pool || THEME in pool || controlMarkers.values.any { it in pool }) bodies += read(bytes)
                }
                val page = bodies.filter { body -> body.markers.any { it.startsWith(PAGE) } }.singleOrNull()
                    ?: error("无法唯一确认播放页面入口")
                val args = Type.getArgumentTypes(page.member.descriptor)
                require(page.access and Opcodes.ACC_STATIC != 0 && Type.getReturnType(page.member.descriptor) == Type.VOID_TYPE &&
                    args.size == 7 && args[0].descriptor == "Lkotlin/jvm/functions/Function0;" &&
                    args[1] == Type.BOOLEAN_TYPE && args[2].sort == Type.OBJECT &&
                    args[3].descriptor == "Landroidx/compose/ui/Modifier;" && args[4].descriptor == COMPOSER &&
                    args[5] == Type.INT_TYPE && args[6] == Type.INT_TYPE) { "播放页面签名已改变" }
                val sites = bodies.filter { body -> body.member.owner != page.member.owner }.flatMap { body ->
                    body.calls.filter { it.target == page.member }.map { body to it }
                }
                require(sites.size == 1) { "播放页面调用不唯一（${sites.size}）" }
                val (caller, call) = sites.single()
                require(caller.markers.any { it.startsWith(SHEET) } && call.opcode == Opcodes.INVOKESTATIC &&
                    call.defaultMask in listOf(0, 8)) { "播放页面调用约定已改变" }
                val theme = bodies.singleOrNull { body ->
                    body.markers.any { it.startsWith(THEME) } && body.access and Opcodes.ACC_STATIC != 0 &&
                        body.member.descriptor == "(ZLkotlin/jvm/functions/Function2;${COMPOSER}I)V"
                } ?: error("无法唯一确认播放页面内容容器")
                // The outer PlayerScreen call omits Modifier (mask 8), so a registered
                // replacement there is bypassed. Its theme/content call has no default mask.
                val contentSites = bodies.filter { it.member.owner == page.member.owner }.flatMap { body ->
                    body.calls.filter { it.target == theme.member }.map { body to it }
                }
                require(contentSites.size == 1 && contentSites.single().first.member == page.member &&
                    contentSites.single().second.opcode == Opcodes.INVOKESTATIC) { "播放页面内容调用不唯一" }
                val controls = controlMarkers.mapNotNull { (role, marker) ->
                    bodies.singleOrNull { body -> body.markers.any { it.startsWith(marker) } &&
                        body.access and Opcodes.ACC_STATIC != 0 && body.member.descriptor == "(J${COMPOSER}I)V"
                    }?.let { role to it.member }
                }.toMap()
                NativePlayerTarget(theme.member, page.member.owner, controls)
            }

        private fun read(bytes: ByteArray): List<Body> {
            val reader = ClassReader(bytes)
            val bodies = mutableListOf<Body>()
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String,
                    signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    val markers = mutableListOf<String>()
                    val calls = mutableListOf<Call>()
                    bodies += Body(Member(reader.className, name, descriptor), access, markers, calls)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        var constant: Int? = null
                        override fun visitInsn(opcode: Int) { constant = if (opcode in Opcodes.ICONST_0..Opcodes.ICONST_5) opcode - Opcodes.ICONST_0 else null }
                        override fun visitIntInsn(opcode: Int, operand: Int) { constant = if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) operand else null }
                        override fun visitVarInsn(opcode: Int, variable: Int) { constant = null }
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) { constant = null }
                        override fun visitTypeInsn(opcode: Int, type: String) { constant = null }
                        override fun visitLdcInsn(value: Any?) {
                            constant = value as? Int
                            if (value is String) markers += value
                        }
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            calls += Call(Member(owner, name, descriptor), opcode, constant)
                            constant = null
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return bodies
        }
    }
}
