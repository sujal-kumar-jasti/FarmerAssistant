package com.farmerassistant.app.ui.home.fragments

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.farmerassistant.app.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*
import com.google.firebase.auth.FirebaseAuth

// 🔥 NEW IMPORTS for Glide Transformations
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.BlurTransformation

class PostAdapter(
    private var posts: List<CommunityPost>,
    private val context: Context,
    private val onModerateClicked: (CommunityPost) -> Unit, // Callback for moderation action
    private val onDeleteClicked: (CommunityPost) -> Unit // 🔥 NEW: Callback for delete action
) : RecyclerView.Adapter<PostAdapter.PostViewHolder>() {

    private val dateFormatter = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    // Define RequestOptions once to avoid re-creating on every bind
    private val imageRequestOptions: RequestOptions by lazy {
        val radiusPx = 12.dpToPx() // 12dp rounded corners
        RequestOptions().transform(CenterCrop(), RoundedCorners(radiusPx))
    }

    inner class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvPoster: TextView = itemView.findViewById(R.id.tvPoster)
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        val ivPostImage: ImageView = itemView.findViewById(R.id.ivPostImage)
        val tvModerationStatus: TextView = itemView.findViewById(R.id.tvModerationStatus)
        val btnModerate: MaterialButton = itemView.findViewById(R.id.btnModerate)
        val btnDeletePost: MaterialButton = itemView.findViewById(R.id.btnDeletePost) // 🔥 NEW: Delete button
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_community_post, parent, false)
        return PostViewHolder(view)
    }

    override fun onBindViewHolder(holder: PostViewHolder, position: Int) {
        val post = posts[position]
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        val isLocalPost = post.posterUid == currentUserId // Check if the post belongs to the current user

        holder.tvPoster.text = "${post.posterName} (${post.crop})"
        holder.tvTitle.text = post.title
        holder.tvContent.text = post.content
        holder.tvTimestamp.text = dateFormatter.format(Date(post.timestamp))

        // Image Loading (using Glide for performance)
        if (post.imagePath != null && post.imagePath.startsWith("http")) {
            holder.ivPostImage.visibility = View.VISIBLE

            Glide.with(context)
                .load(post.imagePath)
                .apply(imageRequestOptions)
                .placeholder(R.drawable.ic_image_placeholder)
                .into(holder.ivPostImage)
        } else {
            holder.ivPostImage.visibility = View.GONE
        }

        // Action Buttons (Only for the post creator)
        if (isLocalPost) {
            // Moderation Check Button
            holder.tvModerationStatus.visibility = View.VISIBLE
            holder.tvModerationStatus.text = "Status: Awaiting AI Check"
            holder.tvModerationStatus.setTextColor(ContextCompat.getColor(context, R.color.md_theme_secondary))
            holder.btnModerate.visibility = View.VISIBLE
            holder.btnModerate.text = "Run AI Check"
            holder.btnModerate.setOnClickListener { onModerateClicked(post) }

            // 🔥 NEW: Delete Post Button Visibility and Listener
            holder.btnDeletePost.visibility = View.VISIBLE
            holder.btnDeletePost.setOnClickListener { onDeleteClicked(post) }
        } else {
            // Hide moderation status and action buttons for other users' posts
            holder.tvModerationStatus.visibility = View.GONE
            holder.btnModerate.visibility = View.GONE
            holder.btnDeletePost.visibility = View.GONE
        }
    }

    override fun getItemCount() = posts.size

    fun updatePosts(newPosts: List<CommunityPost>) {
        posts = newPosts
        notifyDataSetChanged()
    }

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}