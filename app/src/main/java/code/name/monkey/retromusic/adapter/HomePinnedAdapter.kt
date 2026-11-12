package code.name.monkey.retromusic.adapter

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.db.toSong
import android.content.res.ColorStateList
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView

/**
 * "Pinned" row on the home screen. Albums are rounded squares (title + artist below), artists
 * are circles (name below, centred like in Top artists), playlists are rounded squares (name +
 * song count). The last slot is always a "+" tile that opens the pin picker.
 */
class HomePinnedAdapter(
    private val addTileColor: Int,
    private val addIconColor: Int,
    private val onClick: (Item) -> Unit,
    private val onLongClick: (Item) -> Unit
) : RecyclerView.Adapter<HomePinnedAdapter.ViewHolder>() {

    sealed class Item {
        class AlbumItem(val album: Album) : Item()
        class ArtistItem(val artist: Artist) : Item()
        class PlaylistItem(val playlist: PlaylistWithSongs) : Item()
        object Add : Item()
    }

    private var items: List<Item> = listOf(Item.Add)

    @SuppressLint("NotifyDataSetChanged")
    fun swapData(pinned: List<Item>) {
        items = pinned + Item.Add
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_pinned, parent, false)
        val d = parent.resources.displayMetrics.density
        // same size and spacing as the album cards in the "Favorites" row below
        val size = (CARD_SIZE_DP * d).toInt()
        view.layoutParams = RecyclerView.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (CARD_GAP_DP * d).toInt()
        }
        view.findViewById<View>(R.id.card).layoutParams = LinearLayout.LayoutParams(size, size)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val d = holder.itemView.resources.displayMetrics.density
        val size = holder.card.layoutParams.width.toFloat()

        holder.card.radius = if (item is Item.ArtistItem) size / 2f else CORNER_DP * d

        var noCover = item is Item.Add
        when (item) {
            is Item.AlbumItem -> {
                val first = item.album.safeGetFirstSong()
                loadCover(holder.image, first)
                setTexts(holder, item.album.title, first.artistName, centered = false)
            }
            is Item.ArtistItem -> {
                // the artist's own picture (same source as Top artists and the artist page)
                Glide.with(holder.image)
                    .asBitmap()
                    .artistImageOptions(item.artist)
                    .load(RetroGlideExtension.getArtistModel(item.artist))
                    .into(holder.image)
                setTexts(holder, item.artist.name, null, centered = true)
            }
            is Item.PlaylistItem -> {
                val first = item.playlist.songs.firstOrNull()?.toSong()
                if (first != null) loadCover(holder.image, first) else {
                    clearImage(holder.image)
                    noCover = true
                }
                val count = item.playlist.songs.size
                setTexts(
                    holder,
                    item.playlist.playlistEntity.playlistName,
                    holder.itemView.resources.getQuantityString(R.plurals.home_playlist_songs, count, count),
                    centered = false
                )
            }
            Item.Add -> {
                clearImage(holder.image)
                setTexts(holder, null, null, centered = false)
            }
        }

        holder.card.setCardBackgroundColor(if (noCover) addTileColor else 0)
        holder.addIcon.visibility = if (item is Item.Add) View.VISIBLE else View.GONE
        if (item is Item.Add) {
            holder.addIcon.imageTintList = ColorStateList.valueOf(addIconColor)
        }

        val click = View.OnClickListener { onClick(item) }
        val longClick = if (item is Item.Add) null else View.OnLongClickListener {
            onLongClick(item)
            true
        }
        for (target in listOf(holder.card, holder.title, holder.subtitle)) {
            target.setOnClickListener(click)
            target.setOnLongClickListener(longClick)
            target.isLongClickable = longClick != null
        }
    }

    private fun setTexts(holder: ViewHolder, title: String?, subtitle: String?, centered: Boolean) {
        holder.title.visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
        holder.title.text = title
        holder.title.gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        holder.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        holder.subtitle.text = subtitle
    }

    private fun loadCover(target: ImageView, song: Song) {
        Glide.with(target)
            .load(RetroGlideExtension.getSongModel(song))
            .songCoverOptions(song)
            .into(target)
    }

    private fun clearImage(target: ImageView) {
        Glide.with(target).clear(target)
        target.setImageDrawable(null)
    }

    override fun getItemCount(): Int = items.size

    private companion object {
        // measured from the Favorites row: 156dp cards with a 16dp gap
        const val CARD_SIZE_DP = 156
        const val CARD_GAP_DP = 16
        const val CORNER_DP = 18
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card)
        val image: ImageView = view.findViewById(R.id.image)
        val addIcon: ImageView = view.findViewById(R.id.addIcon)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }
}
