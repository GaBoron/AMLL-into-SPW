package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.Desktop
import java.net.URI
import kotlin.concurrent.thread

/** Host settings callback, including while the plugin is disabled. */
object SponsorshipActions {
    private const val URL = "https://afdian.com/a/gaboron"

    @JvmStatic
    fun openAfdian() {
        thread(name = "amll-sponsorship", isDaemon = true) {
            try {
                check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    "系统未提供默认浏览器"
                }
                Desktop.getDesktop().browse(URI.create(URL))
            } catch (failure: Exception) {
                System.err.println("AMLL: 赞助页面打开失败：${failure.message}")
                runCatching {
                    WorkshopApi.ui.toast("无法打开赞助页面，请手动访问 $URL", WorkshopApi.Ui.ToastType.Error)
                }
            }
        }
    }
}
