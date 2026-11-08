package code.name.monkey.retromusic.fragments.home

import code.name.monkey.retromusic.databinding.FragmentHomeBinding

class HomeBinding(
    homeBinding: FragmentHomeBinding
) {
    val root = homeBinding.root
    val container = homeBinding.container
    val contentContainer = homeBinding.contentContainer
    val appBarLayout = homeBinding.appBarLayout
    val searchBar = homeBinding.searchBar
    val menuButton = homeBinding.menuButton

    private val content = homeBinding.homeContent

    // the six square tiles
    val tileHistory = content.tileHistory
    val tileMostPlayed = content.tileMostPlayed
    val tileFavorite = content.tileFavorite
    val tileForYouNow = content.tileForYouNow
    val tileRediscover = content.tileRediscover
    val tileStats = content.tileStats

    // Recently added
    val recentSection = content.recentSection
    val recentHeader = content.recentHeader
    val recentRecyclerView = content.recentRecyclerView

    // Pinned
    val pinnedSection = content.pinnedSection
    val pinnedRecyclerView = content.pinnedRecyclerView

    // existing home sections
    val recyclerView = content.recyclerView
}
