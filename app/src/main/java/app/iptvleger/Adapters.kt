package app.iptvleger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Liste de lignes de texte (catégories, saisons, épisodes) avec une ligne sélectionnée. */
class TextAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<TextAdapter.VH>() {

    private var items: List<String> = emptyList()
    private var selected = -1

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    fun current(): List<String> = items

    fun submit(list: List<String>, sel: Int = -1) {
        items = list
        selected = sel
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        val old = selected
        selected = index
        if (old >= 0 && old < items.size) notifyItemChanged(old)
        if (index >= 0 && index < items.size) notifyItemChanged(index)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_text, parent, false) as TextView
        val vh = VH(tv)
        tv.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = items[position]
        holder.tv.isActivated = position == selected
    }
}

/** Grille d'affiches (films, séries, chaînes). */
class CardAdapter(
    private val onClick: (Item) -> Unit,
    private val onLongClick: (Item) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    private var items: List<Item> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.img)
        val name: TextView = view.findViewById(R.id.name)
    }

    fun current(): List<Item> = items

    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        val vh = VH(view)
        view.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        view.setOnLongClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(items[pos])
            true
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.img.scaleType =
            if (item.kind == KIND_LIVE) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (item.icon.isBlank()) {
            holder.img.dispose()
            holder.img.setImageDrawable(null)
        } else {
            holder.img.load(item.icon)
        }
    }
}
