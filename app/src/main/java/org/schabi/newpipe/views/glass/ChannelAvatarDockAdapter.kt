package org.schabi.newpipe.views.glass

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.util.image.CoilHelper

data class ChannelAvatarItem(
    val serviceId: Int,
    val url: String,
    val name: String,
    val avatarUrl: String?,
    val hasUnread: Boolean = false
)

class ChannelAvatarDockAdapter(
    private var channels: List<ChannelAvatarItem> = emptyList(),
    private val onChannelClick: (ChannelAvatarItem) -> Unit
) : RecyclerView.Adapter<ChannelAvatarDockAdapter.AvatarViewHolder>() {

    fun updateChannels(newChannels: List<ChannelAvatarItem>) {
        this.channels = newChannels
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_avatar_bubble, parent, false)
        return AvatarViewHolder(view)
    }

    override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
        val item = channels[position]
        holder.bind(item, onChannelClick)
    }

    override fun getItemCount(): Int = channels.size

    class AvatarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ringContainer: FrameLayout = itemView.findViewById(R.id.avatar_ring_container)
        private val avatarImage: ImageView = itemView.findViewById(R.id.avatar_image)
        private val avatarName: TextView = itemView.findViewById(R.id.avatar_name)

        fun bind(item: ChannelAvatarItem, onClick: (ChannelAvatarItem) -> Unit) {
            avatarName.text = item.name
            ringContainer.setBackgroundResource(
                if (item.hasUnread) R.drawable.bg_avatar_unread_ring else android.R.color.transparent
            )

            if (!item.avatarUrl.isNullOrEmpty()) {
                CoilHelper.loadAvatar(avatarImage, item.avatarUrl)
            } else {
                avatarImage.setImageResource(R.drawable.placeholder_person)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }
}
