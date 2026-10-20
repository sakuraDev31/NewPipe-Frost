package org.schabi.newpipe.views.glass

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R

class GlassFilterAdapter(
    private val filterCategories: List<String>,
    private val onFilterSelected: (String, Int) -> Unit
) : RecyclerView.Adapter<GlassFilterAdapter.FilterViewHolder>() {

    var selectedIndex: Int = 0
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_glass_filter_chip, parent, false)
        return FilterViewHolder(view)
    }

    override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
        val category = filterCategories[position]
        holder.bind(category, position == selectedIndex) {
            val clickedIndex = holder.bindingAdapterPosition
            if (clickedIndex == RecyclerView.NO_POSITION) {
                return@bind
            }
            val previousIndex = selectedIndex
            selectedIndex = clickedIndex
            if (previousIndex != selectedIndex) {
                notifyItemChanged(previousIndex)
                notifyItemChanged(selectedIndex)
                onFilterSelected(category, selectedIndex)
            }
        }
    }

    override fun getItemCount(): Int = filterCategories.size

    fun setSelection(index: Int) {
        if (index in filterCategories.indices && index != selectedIndex) {
            val prev = selectedIndex
            selectedIndex = index
            notifyItemChanged(prev)
            notifyItemChanged(selectedIndex)
        }
    }

    class FilterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chipText: TextView = itemView.findViewById(R.id.chip_text)

        fun bind(text: String, isSelected: Boolean, onClick: () -> Unit) {
            chipText.text = text
            if (isSelected) {
                chipText.setBackgroundResource(R.drawable.bg_liquid_glass_active_pill)
                chipText.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
            } else {
                chipText.setBackgroundResource(R.drawable.bg_liquid_glass_action_pill)
                chipText.setTextColor(ContextCompat.getColor(itemView.context, R.color.liquid_glass_text_title))
            }
            itemView.setOnClickListener { onClick() }
        }
    }
}
