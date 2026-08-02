package com.example.filemanager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.filemanager.databinding.ItemFileGridBinding

class FileAdapter(
    private var items: List<FileItem> = emptyList(),
    private val selected: MutableSet<String> = mutableSetOf(),
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Boolean,
    private val onMoreClick: (FileItem) -> Unit = {}
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    var selectionMode: Boolean = false
        private set

    fun submit(list: List<FileItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun getItems(): List<FileItem> = items

    fun selectAll(allItems: List<FileItem>) {
        selectionMode = true
        allItems.forEach { selected.add(it.file.absolutePath) }
        notifyDataSetChanged()
    }

    fun isSelected(item: FileItem): Boolean = selected.contains(item.file.absolutePath)

    fun toggleSelection(item: FileItem): Int {
        val path = item.file.absolutePath
        if (selected.contains(path)) selected.remove(path) else selected.add(path)
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        return selected.size
    }

    fun enterSelectionMode(item: FileItem): Int {
        selectionMode = true
        selected.add(item.file.absolutePath)
        notifyDataSetChanged()
        return selected.size
    }

    fun clearSelection() {
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun getSelected(): List<FileItem> = items.filter { selected.contains(it.file.absolutePath) }

    fun selectedCount(): Int = selected.size

    inner class FileViewHolder(val binding: ItemFileGridBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = items[position]
        holder.binding.fileName.text = item.name
        holder.binding.fileInfo.text = if (item.isDirectory) "" else item.formattedSize()
        holder.binding.fileIcon.setImageResource(iconFor(item))

        val isSel = isSelected(item)
        holder.binding.checkbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.binding.checkbox.isChecked = isSel
        holder.binding.root.isActivated = isSel

        holder.binding.root.setOnClickListener {
            if (selectionMode) {
                toggleSelection(item)
            } else {
                onClick(item)
            }
        }
        holder.binding.root.setOnLongClickListener {
            if (!selectionMode) {
                enterSelectionMode(item)
            } else {
                toggleSelection(item)
            }
            onLongClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun iconFor(item: FileItem): Int {
        if (item.isDirectory) return R.drawable.ic_folder
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "gif", "bmp", "webp" -> R.drawable.ic_image
            "mp3", "wav", "flac", "aac", "ogg", "m4a" -> R.drawable.ic_audio
            "mp4", "avi", "mkv", "mov", "3gp", "flv" -> R.drawable.ic_video
            "txt", "log", "md", "json", "xml", "csv" -> R.drawable.ic_text
            "pdf" -> R.drawable.ic_pdf
            "doc", "docx", "rtf" -> R.drawable.ic_doc
            "xls", "xlsx" -> R.drawable.ic_sheet
            "ppt", "pptx" -> R.drawable.ic_ppt
            "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
            "apk" -> R.drawable.ic_apk
            else -> R.drawable.ic_file
        }
    }
}
