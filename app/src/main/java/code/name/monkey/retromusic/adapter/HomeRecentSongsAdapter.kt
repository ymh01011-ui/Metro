package code.name.monkey.retromusic.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Song
import com.bumptech.glide.Glide

/**
 * "Recently added" on the home screen. Used inside a horizontal 3-row grid, so every item is
 * about two thirds of the screen wide and the next column peeks in from the side.
 */
class HomeRecentSongsAdapter(
    @Suppress("unused") private val activity: AppCompatActivity
) : RecyclerView.Adapter<HomeRecentSongsAdapter.ViewHolder>() {

    private var songs = listOf<Song>()

    @SuppressLint("NotifyDataSetChanged")
    fun swapData(data: List<Song>) {
        if (data == songs) return
        songs = data
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_recent_song, parent, false)
        val width = if (parent.width > 0) parent.width else parent.resources.displayMetrics.widthPixels
        view.layoutParams = RecyclerView.LayoutParams(
            (width * 0.67f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.artist.text = song.artistName
        Glide.with(holder.image)
            .load(RetroGlideExtension.getSongModel(song))
            .songCoverOptions(song)
            .into(holder.image)
        holder.itemView.setOnClickListener {
            MusicPlayerRemote.openQueue(songs, position, true)
        }
    }

    override fun getItemCount(): Int = songs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val title: TextView = view.findViewById(R.id.title)
        val artist: TextView = view.findViewById(R.id.artist)
    }
}
