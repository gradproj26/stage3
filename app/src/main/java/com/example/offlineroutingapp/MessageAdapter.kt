package com.example.offlineroutingapp

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.app.AlertDialog
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.ImageButton
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MessageAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.messageText)
        val messageImage: ImageView = itemView.findViewById(R.id.messageImage)
        val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        val messageStatus: TextView = itemView.findViewById(R.id.messageStatus)

        // Voice message views
        val voiceMessageLayout: LinearLayout = itemView.findViewById(R.id.voiceMessageLayout)
        val playVoiceBtn: ImageButton = itemView.findViewById(R.id.playVoiceBtn)
        val voiceDurationText: TextView = itemView.findViewById(R.id.voiceDurationText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        // Reset view states
        holder.messageText.visibility = View.GONE
        holder.messageImage.visibility = View.GONE
        holder.voiceMessageLayout.visibility = View.GONE

        // Determine if sent by me for alignment and bubble color
        val gravity = if (message.isSentByMe) Gravity.END else Gravity.START
        val bubbleResource = if (message.isSentByMe) R.drawable.bg_message_sent else R.drawable.bg_message_received

        holder.messageContainer.gravity = gravity

        // Ensure text color is set correctly if text is shown later
        val textColor = if (message.isSentByMe) holder.itemView.context.getColor(android.R.color.white) else holder.itemView.context.getColor(android.R.color.black)

        if (message.isImage && !message.imageData.isNullOrEmpty()) {
            holder.messageImage.visibility = View.VISIBLE
            holder.messageImage.setBackgroundResource(bubbleResource)

            try {
                val imageBytes = Base64.decode(message.imageData, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                holder.messageImage.setImageBitmap(bitmap)

                // Make image clickable to view fullscreen
                holder.messageImage.setOnClickListener {
                    showFullscreenImage(holder.itemView.context, imageBytes)
                }
            } catch (e: Exception) {
                android.util.Log.e("MessageAdapter", "Error decoding image: ${e.message}")
                holder.messageImage.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        } else if (message.isAudio && !message.imageData.isNullOrEmpty()) {
            // Handle Voice Message
            holder.voiceMessageLayout.visibility = View.VISIBLE
            holder.voiceMessageLayout.setBackgroundResource(bubbleResource)
            holder.playVoiceBtn.setImageResource(android.R.drawable.ic_media_play)
            holder.playVoiceBtn.tag = "paused" // Initial state
            holder.playVoiceBtn.setColorFilter(textColor) // Set tint based on bubble color

            // UPDATED: عرض المدة الفعلية
            holder.voiceDurationText.text = formatDuration(message.audioDuration)
            holder.voiceDurationText.setTextColor(textColor)


            holder.playVoiceBtn.setOnClickListener {
                toggleAudioPlayback(holder.itemView.context, message.imageData, holder.playVoiceBtn)
            }
        } else {
            // Handle Text Message
            holder.messageText.visibility = View.VISIBLE
            holder.messageText.text = message.text
            holder.messageText.setBackgroundResource(bubbleResource)
            holder.messageText.setTextColor(textColor)
        }

        if (message.isSentByMe) {
            holder.messageStatus.visibility = View.VISIBLE
            holder.messageStatus.text = when {
                message.isSeen -> "✓✓ Seen"
                message.isDelivered -> "✓✓ Delivered"
                else -> "✓ Sent"
            }
        } else {
            holder.messageStatus.visibility = View.GONE
        }
    }

    private fun showFullscreenImage(context: Context, imageBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        AlertDialog.Builder(context)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .create()
            .show()
    }

    private fun toggleAudioPlayback(context: Context, audioBase64: String, playButton: ImageButton) {
        if (isPlaying && playButton.tag == "playing") {
            stopPlayback()
            playButton.setImageResource(android.R.drawable.ic_media_play)
            playButton.tag = "paused"
        } else {
            stopPlayback() // Stop any currently playing audio
            startPlayback(context, audioBase64, playButton)
        }
    }

    private fun startPlayback(context: Context, audioBase64: String, playButton: ImageButton) {
        try {
            // 1. Decode Base64 audio data
            val audioBytes = Base64.decode(audioBase64, Base64.NO_WRAP)

            // 2. Save to a temporary file for MediaPlayer
            // NOTE: Using a unique file name is better to avoid conflicts, but for simplicity
            // and immediate deletion, we reuse a name here.
            val tempFile = File(context.cacheDir, "temp_voice_msg_${System.currentTimeMillis()}.3gp")
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
            }

            // 3. Initialize and start MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(tempFile))
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                    playButton.setImageResource(android.R.drawable.ic_media_play)
                    playButton.tag = "paused"
                    tempFile.delete() // Ensure cleanup
                }
                start()
            }
            isPlaying = true
            playButton.setImageResource(android.R.drawable.ic_media_pause)
            playButton.tag = "playing"

        } catch (e: IOException) {
            android.util.Log.e("MessageAdapter", "Failed to prepare/start MediaPlayer: ${e.message}")
            stopPlayback()
            playButton.setImageResource(android.R.drawable.ic_media_play)
            playButton.tag = "paused"
            Toast.makeText(context, "Failed to play voice message.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
    }

    // UPDATED: لتحويل الملي ثانية إلى تنسيق M:SS
    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "0:00"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    // Ensure playback stops when the adapter is done (e.g. ChatActivity is destroyed)
    fun releaseMediaPlayer() {
        stopPlayback()
    }

    override fun getItemCount(): Int = messages.size
}