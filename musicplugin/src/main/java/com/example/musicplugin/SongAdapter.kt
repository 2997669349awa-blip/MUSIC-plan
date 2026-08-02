package com.example.musicplugin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplugin.databinding.ItemSongBinding

class SongAdapter(
    private val onClick: (Song, Int) -> Unit,
    private val onFavoriteClick: (Song, Int) -> Unit = { _, _ -> },
    private val isFavorite: (Long) -> Boolean = { false }
) : RecyclerView.Adapter<SongAdapter.VH>() {

    private val items = mutableListOf<Song>()
    private var playingPath: String? = null

    fun submit(list: List<Song>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setPlaying(path: String?) {
        playingPath = path
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val isPlaying = s.path == playingPath
        // v1.2.7：去掉 ▶ 前缀，改用卡片背景 + 封面遮罩指示播放状态
        holder.binding.tvTitle.text = s.title
        // v1.1.6：恢复显示歌手/音质副标题（v1.1.5 误隐藏，已改回）
        holder.binding.tvArtist.visibility = android.view.View.VISIBLE
        holder.binding.tvArtist.text = buildString {
            append(s.artist)
            if (s.bitrate > 0) {
                val kbps = (s.bitrate / 1000).toString()
                append(" · ").append(kbps).append("kbps")
            }
        }
        holder.binding.tvDuration.text = formatDuration(s.duration)
        // v1.2.7：播放中切换卡片背景 + 显示遮罩图标
        holder.binding.imgPlaying.visibility = if (isPlaying) android.view.View.VISIBLE else android.view.View.GONE
        holder.itemView.setBackgroundResource(
            if (isPlaying) R.drawable.bg_song_card_playing else R.drawable.bg_song_card
        )
        holder.itemView.setOnClickListener { onClick(s, position) }

        val fav = isFavorite(s.id)
        holder.binding.btnFavorite.setImageResource(
            if (fav) R.drawable.ic_star_filled
            else R.drawable.ic_star_outline
        )
        holder.binding.btnFavorite.setOnClickListener { onFavoriteClick(s, position) }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    class VH(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)
}
