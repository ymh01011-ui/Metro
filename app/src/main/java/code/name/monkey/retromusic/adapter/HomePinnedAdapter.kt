package code.name.monkey.retromusic.adapter

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView

/**
 * "Pinned" row on the home screen: albums are rounded squares, artists are circles and the last
 * slot is always a "+" tile that opens the pin picker.
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
        val width = if (parent.width > 0) parent.width else parent.resources.displayMetrics.widthPixels
        // three per screen: screen - side paddings (2 x 16dp) - two gaps (2 x 10dp)
        val size = ((width - 32 * d - 20 * d) / 3f).toInt()
        view.layoutParams = RecyclerView.LayoutParams(size, size).apply {
            marginEnd = (10 * d).toInt()
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val d = holder.itemView.resources.displayMetrics.density
        val size = holder.itemView.layoutParams.width.toFloat()

        holder.card.radius = if (item is Item.ArtistItem) size / 2f else 20 * d
        holder.card.setCardBackgroundColor(if (item is Item.Add) addTileColor else 0)
        holder.addIcon.visibility = if (item is Item.Add) View.VISIBLE else View.GONE
        holder.addIcon.imageTintList = ColorStateList.valueOf(addIconColor)

        when (item) {
            is Item.AlbumItem -> loadCover(holder.image, item.album.safeGetFirstSong())
            is Item.ArtistItem ->
                loadCover(holder.image, item.artist.safeGetFirstAlbum().safeGetFirstSong())
            Item.Add -> {
                Glide.with(holder.image).clear(holder.image)
                holder.image.setImageDrawable(null)
            }
        }

        holder.itemView.setOnClickListener { onClick(item) }
        if (item is Item.Add) {
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
        } else {
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    private fun loadCover(target: ImageView, song: Song) {
        Glide.with(target)
            .load(RetroGlideExtension.getSongModel(song))
            .songCoverOptions(song)
            .into(target)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val image: ImageView = view.findViewById(R.id.image)
        val addIcon: ImageView = view.findViewById(R.id.addIcon)
    }
}
