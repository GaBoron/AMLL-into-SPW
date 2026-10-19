package io.github.gaboron.amllspw.footer

/** Plain key/value data. Attribution and all presentation choices belong to AMLL. */
internal data class FooterContribution(
    val providerId: String,
    val providerName: String,
    val fields: List<Pair<String, String>>,
)
