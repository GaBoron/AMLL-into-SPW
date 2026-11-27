package io.github.gaboron.amllspw.host

import io.github.gaboron.amllspw.ui.LyricsPresentation

/** Uses the same route keys and artist names as the native library's track menu. */
internal class NativeTrackNavigation(private val loader: ClassLoader, artistNames: NativePlayerTarget.Member?) {
    private fun hostClass(name: String) = Class.forName(name, false, loader)

    private val route = "com.xuncorp.voxzen.ui.navigation.ScreenRoute"
    private val configType = hostClass("com.xuncorp.voxzen.util.AppConfig")
    private val config by lazy { configType.getField("INSTANCE").get(null) }
    private val collapse = configType.getMethod("collapsePlaybackScreen")
    private val navigate = configType.getMethod("navigateTo", hostClass(route))
    private val artistRoute = hostClass("$route\$ArtistContent").getConstructor(String::class.java)
    private val albumRoute = hostClass("$route\$AlbumContent").getConstructor(String::class.java, String::class.java)
    private val parseArtists = artistNames?.let { hostClass(it.owner.replace('/', '.')).getMethod(it.name, String::class.java) }

    fun entries(presentation: LyricsPresentation, dispatch: (() -> Unit) -> Unit): List<NativeGlassMenu.Entry> {
        if (presentation.externalPlayback || presentation.trackKey.isBlank()) return emptyList()
        val artists = runCatching { parseArtists?.invoke(null, presentation.artist) as? List<*> }
            .onFailure { System.err.println("AMLL: 歌手名单读取失败：${it.message}") }
            .getOrNull().orEmpty().filterIsInstance<String>().filter(String::isNotBlank).distinct()
        val albums = if (presentation.album.isBlank()) emptyList() else listOf(presentation.album to {
            dispatch { open(albumRoute.newInstance(presentation.album, presentation.albumArtist)) }
        })
        return listOfNotNull(
            NativeGlassMenu.Entry.destinations("转到歌手页", artists.map { name ->
                name to { dispatch { open(artistRoute.newInstance(name)) } }
            }),
            NativeGlassMenu.Entry.destinations("转到专辑", albums),
        )
    }

    private fun open(destination: Any) {
        // The host processes both events on its navigation collector, in order.
        collapse.invoke(config)
        navigate.invoke(config, destination)
    }
}
