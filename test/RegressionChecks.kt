package io.github.gaboron.amllspw.verify

object RegressionChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        verifySpectrumEnvelope()
        verifyFooterProtocol()
        verifyConcurrentFooterUpdate()
        verifyHostLyricsAvailability()
        verifyPlayerExtensions()
    }
}
