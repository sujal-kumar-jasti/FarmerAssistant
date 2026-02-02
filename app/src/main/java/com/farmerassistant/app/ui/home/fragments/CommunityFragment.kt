package com.farmerassistant.app.ui.home.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farmerassistant.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.delay

data class CommunityPost(
    val id: String = "",
    val posterUid: String = "",
    val posterName: String = "Anonymous Farmer",
    val crop: String = "N/A",
    val district: String = "",
    val title: String = "",
    val content: String = "",
    val imagePath: String? = null,
    val isModerated: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class CommunityFragment : Fragment() {

    private lateinit var tvCommunityStatus: TextView
    private lateinit var rvPosts: RecyclerView
    private lateinit var btnNewPost: MaterialButton
    private lateinit var postAdapter: PostAdapter

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val COMMUNITY_COLLECTION = "regional_community_posts"
    private val TAG = "CommunityFragment"

    // We revert to the original preference key assumption
    private val PREF_KEY_DISTRICT = "user_district"

    private var currentUserName: String = "Anonymous"
    private var currentUserDistrict: String = "Unknown"
    private var currentBaseDistrict: String = "Unknown"

    private var selectedImageUri: Uri? = null

    // Activity Result Launcher for Gallery/Camera
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedImageUri = result.data?.data
            showNewPostDialog(isImageSelected = true)
        }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_community, container, false)

        tvCommunityStatus = v.findViewById(R.id.tvCommunityStatus)
        rvPosts = v.findViewById(R.id.rvPosts)
        btnNewPost = v.findViewById(R.id.btnNewPost)

        // 1. Setup RecyclerView
        postAdapter = PostAdapter(
            emptyList(),
            requireContext(),
            onModerateClicked = { postToModerate -> runAiModeration(postToModerate) },
            onDeleteClicked = { postToDelete -> confirmAndDeletePost(postToDelete) }
        )
        rvPosts.layoutManager = LinearLayoutManager(context)
        rvPosts.adapter = postAdapter

        loadUserProfileAndLocation()

        btnNewPost.setOnClickListener {
            showNewPostDialog()
        }

        return v
    }

    private fun loadUserProfileAndLocation() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        // Load the full location string directly
        val fullLocation = prefs.getString(PREF_KEY_DISTRICT, null)
        currentUserDistrict = fullLocation ?: "Unknown"

        // 🔥 CRITICAL: currentBaseDistrict is set to the full string for consistent querying/posting
        currentBaseDistrict = currentUserDistrict

        val uid = auth.currentUser?.uid
        if (uid != null) {
            db.collection("users").document(uid).get().addOnSuccessListener { doc ->
                currentUserName = doc.getString("name") ?: "Farmer ${uid.take(4)}"
                loadRegionalPosts()
            }.addOnFailureListener {
                loadRegionalPosts()
            }
        } else {
            loadRegionalPosts()
        }
    }


    // --- AI MODERATION SIMULATION ---
    private fun runAiModeration(post: CommunityPost) {
        Toast.makeText(context, "Running AI Moderation check on post...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            delay(1500)

            val isSafe = Random.nextBoolean()

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (isSafe) {
                    Toast.makeText(context, "AI Check: Post is SAFE.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "AI Check: Post is UNSAFE (Hate Speech/Spam). Post will be hidden.", Toast.LENGTH_LONG).show()
                }
                loadRegionalPosts()
            }
        }
    }
    // --- END AI MODERATION ---


    // --- POSTING & IMAGE SHARING ---
    private fun showNewPostDialog(isImageSelected: Boolean = false) {
        if (!isAdded) return

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_new_post, null)
        val etTitle = dialogView.findViewById<TextInputEditText>(R.id.etPostTitle)
        val etContent = dialogView.findViewById<TextInputEditText>(R.id.etPostContent)
        val btnAddImage = dialogView.findViewById<MaterialButton>(R.id.btnAddImage)
        val tvImageStatus = dialogView.findViewById<TextView>(R.id.tvImageStatus)

        if (selectedImageUri != null) {
            tvImageStatus.text = "Image Ready: ${selectedImageUri!!.lastPathSegment}"
            btnAddImage.text = "Change Image"
        } else if (isImageSelected) {
            tvImageStatus.text = "Image Ready (Pending upload)"
        }

        btnAddImage.setOnClickListener {
            val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            imagePickerLauncher.launch(galleryIntent)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Share Regional Insight")
            .setView(dialogView)
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.dismiss()
                selectedImageUri = null
            }
            .setPositiveButton("Post") { dialog, _ ->
                val title = etTitle.text.toString().trim()
                val content = etContent.text.toString().trim()

                if (title.isEmpty() || content.isEmpty()) {
                    Toast.makeText(context, "Title and content are required.", Toast.LENGTH_SHORT).show()
                } else {
                    submitPost(title, content)
                    dialog.dismiss()
                }
            }
            .show()
    }

    private fun submitPost(title: String, content: String) {
        val uid = auth.currentUser?.uid ?: return
        // 🔥 Use the full location string for the post's district field
        val districtForPost = currentBaseDistrict

        if (districtForPost == "Unknown") {
            Toast.makeText(context, "Error: District location is unknown. Cannot post regionally.", Toast.LENGTH_LONG).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            var imageUrl: String? = null

            if (selectedImageUri != null) {
                Toast.makeText(context, "Uploading image...", Toast.LENGTH_SHORT).show()
                imageUrl = uploadImageAndGetUrl(uid)
                if (imageUrl == null) {
                    Toast.makeText(context, "Image upload failed. Post cancelled.", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }

            val post = CommunityPost(
                posterUid = uid,
                posterName = currentUserName,
                crop = "Paddy",
                district = districtForPost, // Post saved with full location string
                title = title,
                content = content,
                imagePath = imageUrl
            )

            db.collection(COMMUNITY_COLLECTION).add(post).await()

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Post submitted to ${districtForPost} regional board!", Toast.LENGTH_LONG).show()
                selectedImageUri = null
                loadRegionalPosts()
            }
        }
    }

    private suspend fun uploadImageAndGetUrl(uid: String): String? {
        val storageRef = storage.reference.child("community_images/${uid}/${UUID.randomUUID()}.jpg")
        return try {
            storageRef.putFile(selectedImageUri!!).await()
            storageRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Image upload failed: ${e.message}")
            null
        }
    }

    private fun confirmAndDeletePost(post: CommunityPost) {
        if (!isAdded) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Deletion")
            .setMessage("Are you sure you want to delete this post? This action cannot be undone and will also delete any associated image.")
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Delete") { _, _ ->
                deletePost(post)
            }
            .show()
    }


    private fun deletePost(post: CommunityPost) {
        if (!isAdded) return

        Toast.makeText(context, "Deleting post...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Delete Image from Storage (if exists)
                if (post.imagePath != null) {
                    val imageRef = storage.getReferenceFromUrl(post.imagePath)
                    imageRef.delete().await()
                    Log.d(TAG, "Image deleted successfully from storage.")
                }

                // 2. Delete Document from Firestore
                db.collection(COMMUNITY_COLLECTION).document(post.id).delete().await()
                Log.d(TAG, "Post document deleted successfully.")


                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Post deleted successfully!", Toast.LENGTH_LONG).show()
                    loadRegionalPosts() // Refresh the list
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete post or image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(context, "Error deleting post: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // --- END DELETION LOGIC ---


    private fun loadRegionalPosts() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        // Re-load the full location string
        currentBaseDistrict = prefs.getString(PREF_KEY_DISTRICT, null) ?: "Unknown"

        if (currentBaseDistrict == "Unknown") {
            tvCommunityStatus.text = "Error: Your farm location/district is unknown. Cannot filter posts."
            postAdapter.updatePosts(emptyList())
            return
        }

        // Display the full location string being queried
        tvCommunityStatus.text = "Loading posts for: ${currentBaseDistrict} "

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 🔥 CRITICAL CHANGE: Query uses the full, consistent location string
                val querySnapshot = db.collection(COMMUNITY_COLLECTION)
                    .whereEqualTo("district", currentBaseDistrict)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(20)
                    .get().await()

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext

                    val posts = querySnapshot.documents.mapNotNull { doc ->
                        doc.toObject(CommunityPost::class.java)?.copy(id = doc.id)
                    }

                    if (posts.isNotEmpty()) {
                        postAdapter.updatePosts(posts)
                    } else {
                        tvCommunityStatus.text = "No recent posts in ${currentBaseDistrict}. Be the first to share!"
                        postAdapter.updatePosts(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load community posts: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        tvCommunityStatus.text = "Network error: Failed to load community posts."
                        postAdapter.updatePosts(emptyList())
                    }
                }
            }
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density + 0.5f).toInt()
    }
}