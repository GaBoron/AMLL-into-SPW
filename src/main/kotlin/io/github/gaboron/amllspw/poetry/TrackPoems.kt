package io.github.gaboron.amllspw.poetry

import kotlin.random.Random

/** Owned by the lyric IO worker. Reopening the player and lyric/palette updates retain the poem. */
internal class TrackPoems(private val catalog: List<Poem>, private val random: Random = Random.Default) {
    private val selections = LinkedHashMap<String, Poem>(64, .75f, true)
    private var previous: Poem? = null

    init { require(catalog.isNotEmpty()) }

    fun forTrack(trackKey: String): Poem = selections[trackKey] ?: choose(previous).also {
        selections[trackKey] = it
        previous = it
        if (selections.size > 64) selections.remove(selections.keys.first())
    }

    fun next(trackKey: String): Poem = choose(selections[trackKey]).also {
        selections[trackKey] = it
        previous = it
    }

    private fun choose(excluded: Poem?): Poem {
        val excludedIndex = catalog.indexOf(excluded)
        if (catalog.size == 1) return catalog.single()
        val index = random.nextInt(catalog.size - if (excludedIndex >= 0) 1 else 0)
        return catalog[if (excludedIndex >= 0 && index >= excludedIndex) index + 1 else index]
    }
}
