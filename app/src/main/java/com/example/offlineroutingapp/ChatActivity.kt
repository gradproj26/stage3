package com.example.offlineroutingapp

import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.service.WifiDirectService
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

class ChatActivity : AppCompatActivity() {

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var imageBtn: ImageButton
    private lateinit var backBtn: ImageButton
    private lateinit var chatUserName: TextView
    private lateinit var chatUserProfile: ImageView
    private lateinit var voiceBtn: ImageButton // Voice/Send button

    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<Message>()

    private val database by lazy { AppDatabase.getDatabase(this) }
    private var chatId: String? = null
    private var wifiService: WifiDirectService? = null
    private var serviceBound = false

    // MediaRecorder for voice messages
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String? = null
    private var isRecording = false

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { sendImage(it) }
    }

    // Request audio recording permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Permission granted. Press and hold to record.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Recording permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WifiDirectService.LocalBinder
            wifiService = binder.getService()
            serviceBound = true

            wifiService?.isChatActivityVisible = true
            wifiService?.visibleChatId = chatId

            // Added isAudio parameter
            wifiService?.onMessageReceived = { text, isImage, isAudio, mediaData ->
                handleReceivedMessage(text, isImage, isAudio, mediaData)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiService?.isChatActivityVisible = false
            wifiService?.visibleChatId = null
            wifiService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatId = intent.getStringExtra("CHAT_ID")
        val userName = intent.getStringExtra("USER_NAME") ?: "Unknown"
        val userPhoto = intent.getStringExtra("USER_PHOTO")

        initializeViews()
        setupUI(userName, userPhoto)
        setupRecyclerView()
        loadMessages()
        setupListeners()
        bindToService()

        // Check permission on startup
        checkRecordAudioPermission()
    }

    private fun bindToService() {
        val serviceIntent = Intent(this, WifiDirectService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun initializeViews() {
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendBtn = findViewById(R.id.sendBtn)
        imageBtn = findViewById(R.id.imageBtn)
        backBtn = findViewById(R.id.backBtn)
        chatUserName = findViewById(R.id.chatUserName)
        chatUserProfile = findViewById(R.id.chatUserProfile)
        voiceBtn = findViewById(R.id.voiceBtn)
    }

    private fun setupUI(userName: String, userPhoto: String?) {
        chatUserName.text = userName

        if (!userPhoto.isNullOrEmpty() && File(userPhoto).exists()) {
            val bitmap = BitmapFactory.decodeFile(userPhoto)
            chatUserProfile.setImageBitmap(bitmap)
        } else {
            chatUserProfile.setImageResource(android.R.drawable.ic_menu_camera)
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(messages)
        messagesRecyclerView.adapter = messageAdapter
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun loadMessages() {
        chatId?.let { id ->
            lifecycleScope.launch {
                database.messageDao().getMessagesByChatId(id).collect { messageEntities ->
                    messages.clear()
                    messages.addAll(
                        messageEntities.map { entity ->
                            Message(
                                text = entity.text,
                                isSentByMe = entity.isSentByMe,
                                timestamp = entity.timestamp,
                                isImage = entity.isImage,
                                imageData = entity.imageData,
                                isDelivered = entity.isDelivered,
                                isSeen = entity.isSeen,
                                // Load audio flag
                                isAudio = entity.isAudio,
                                // UPDATED: تحميل المدة
                                audioDuration = entity.audioDuration
                            )
                        }
                    )
                    messageAdapter.notifyDataSetChanged()
                    if (messages.isNotEmpty()) {
                        messagesRecyclerView.scrollToPosition(messages.size - 1)
                    }
                }

                database.chatDao().markChatAsRead(id)
            }
        }
    }

    private fun setupListeners() {
        backBtn.setOnClickListener {
            finish()
        }

        // Text Send Button is now dynamic
        sendBtn.setOnClickListener {
            sendMessage()
        }

        imageBtn.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Dynamic input handling
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty() || s.isBlank()) {
                    sendBtn.visibility = View.GONE
                    voiceBtn.visibility = View.VISIBLE
                } else {
                    sendBtn.visibility = View.VISIBLE
                    voiceBtn.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Voice recording listener
        voiceBtn.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (checkRecordAudioPermission()) {
                    startRecording()
                }
            } else if (event.action == MotionEvent.ACTION_UP) {
                stopRecording(send = true)
            } else if (event.action == MotionEvent.ACTION_CANCEL) {
                stopRecording(send = false)
            }
            true // Consume the event
        }
    }

    // ================== Voice Recording Logic ==================

    private fun checkRecordAudioPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            false
        } else {
            true
        }
    }

    private fun getAudioCacheFile(): File {
        val storageDir = File(cacheDir, "voice_notes")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File(storageDir, "voice_msg_${System.currentTimeMillis()}.3gp")
    }

    private fun startRecording() {
        if (!checkRecordAudioPermission()) return

        audioFilePath = getAudioCacheFile().absolutePath
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFilePath)
            try {
                prepare()
                start()
                isRecording = true
                Toast.makeText(this@ChatActivity, getString(R.string.recording_in_progress), Toast.LENGTH_SHORT).show()
                Log.d("VoiceRecording", "Recording started: $audioFilePath")
            } catch (e: Exception) {
                Log.e("VoiceRecording", "MediaRecorder preparation failed: ${e.message}", e)
                Toast.makeText(this@ChatActivity, "Failed to start recording.", Toast.LENGTH_SHORT).show()
                isRecording = false
                releaseRecorder()
            }
        }
    }

    private fun stopRecording(send: Boolean) {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            isRecording = false
            Log.d("VoiceRecording", "Recording stopped. File: $audioFilePath")

            if (send && !audioFilePath.isNullOrEmpty()) {
                val audioFile = File(audioFilePath!!)
                if (audioFile.length() < 1024) { // Check if recording is too short (e.g., less than 1KB)
                    Toast.makeText(this, "Recording too short.", Toast.LENGTH_SHORT).show()
                    audioFile.delete()
                } else {
                    sendVoiceMessage(audioFilePath!!)
                }
            } else if (!audioFilePath.isNullOrEmpty()) {
                // If recording was cancelled, delete the file
                File(audioFilePath!!).delete()
                Toast.makeText(this, "Recording cancelled.", Toast.LENGTH_SHORT).show()
            }
            audioFilePath = null

        } catch (e: RuntimeException) {
            // This happens if stop is called immediately after start.
            Log.e("VoiceRecording", "Error stopping recorder: ${e.message}")
            if (!audioFilePath.isNullOrEmpty()) File(audioFilePath!!).delete()
            Toast.makeText(this, "Recording too short or failed to save.", Toast.LENGTH_SHORT).show()
        } finally {
            releaseRecorder()
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.release()
        mediaRecorder = null
    }

    // NEW FUNCTION: لحساب مدة الملف الصوتي
    private fun getAudioDuration(filePath: String): Long {
        var mediaPlayer: MediaPlayer? = null
        try {
            mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(filePath)
            mediaPlayer.prepare()
            return mediaPlayer.duration.toLong()
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error getting audio duration: ${e.message}")
            return 0L
        } finally {
            mediaPlayer?.release()
        }
    }

    private fun sendVoiceMessage(filePath: String) {
        val service = wifiService
        val id = chatId
        val audioFile = File(filePath)

        if (service == null || !service.isConnected) {
            Toast.makeText(this, "Not connected. Go to Discover tab to connect.", Toast.LENGTH_SHORT).show()
            audioFile.delete()
            return
        }
        if (id == null) {
            Toast.makeText(this, "Chat not initialized.", Toast.LENGTH_SHORT).show()
            audioFile.delete()
            return
        }

        lifecycleScope.launch {
            try {
                // UPDATED: 1. حساب المدة باستخدام MediaPlayer
                val durationMs = getAudioDuration(filePath)

                val audioBytes = FileInputStream(audioFile).readBytes()
                audioFile.delete() // Clean up local file

                // UPDATED: إرسال المدة مع البايتات
                service.sendVoice(audioBytes, durationMs)

                val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                val message = MessageEntity(
                    chatId = id,
                    text = getString(R.string.voice_message),
                    isSentByMe = true,
                    isImage = false,
                    isAudio = true,
                    imageData = base64Audio,
                    isDelivered = false,
                    // UPDATED: حفظ المدة
                    audioDuration = durationMs
                )
                database.messageDao().insertMessage(message)

                val chat = database.chatDao().getChatById(id)
                chat?.let {
                    val updatedChat = it.copy(
                        lastMessage = getString(R.string.voice_message),
                        lastMessageTime = System.currentTimeMillis()
                    )
                    database.chatDao().updateChat(updatedChat)
                }

            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Failed to send voice message", Toast.LENGTH_SHORT).show()
                Log.e("ChatActivity", "Error sending voice: ${e.message}")
            }
        }
    }

    // ================== إرسال تكست ==================

    private fun sendMessage() {
        val messageText = messageInput.text.toString().trim()
        if (messageText.isEmpty()) return

        val service = wifiService
        val id = chatId

        if (service == null || !service.isConnected) {
            Toast.makeText(
                this,
                "Not connected. Go to Discover tab to connect.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (id == null) {
            Toast.makeText(this, "Chat not initialized.", Toast.LENGTH_SHORT).show()
            return
        }

        // لو الـ Service شايف إن الاتصال مرتبط بشات تاني مختلف → ما نبعتش
        val currentConnectionChatId = service.currentChatId
        if (currentConnectionChatId != null && currentConnectionChatId != id) {
            Toast.makeText(
                this,
                "Connection is active with another chat.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // لو وصلنا هنا يبقى OK نبعِت
        service.sendMessage(messageText)

        lifecycleScope.launch {
            val message = MessageEntity(
                chatId = id,
                text = messageText,
                isSentByMe = true,
                isImage = false,
                isAudio = false,
                imageData = null,
                isDelivered = false,
                audioDuration = 0L // NEW
            )
            database.messageDao().insertMessage(message)

            val chat = database.chatDao().getChatById(id)
            chat?.let {
                val updatedChat = it.copy(
                    lastMessage = messageText,
                    lastMessageTime = System.currentTimeMillis()
                )
                database.chatDao().updateChat(updatedChat)
            }
        }

        messageInput.text.clear()
    }

    // ================== إرسال صورة ==================

    private fun sendImage(uri: Uri) {
        val service = wifiService
        val id = chatId

        if (service == null || !service.isConnected) {
            Toast.makeText(
                this,
                "Not connected. Go to Discover tab to connect.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (id == null) {
            Toast.makeText(this, "Chat not initialized.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentConnectionChatId = service.currentChatId
        if (currentConnectionChatId != null && currentConnectionChatId != id) {
            Toast.makeText(
                this,
                "Connection is active with another chat.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val compressedBitmap = compressImage(bitmap)
                val byteArrayOutputStream = ByteArrayOutputStream()
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                service.sendImage(imageBytes)

                val base64Image =
                    android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                val message = MessageEntity(
                    chatId = id,
                    text = "",
                    isSentByMe = true,
                    isImage = true,
                    isAudio = false,
                    imageData = base64Image,
                    isDelivered = false,
                    audioDuration = 0L // NEW
                )
                database.messageDao().insertMessage(message)

                val chat = database.chatDao().getChatById(id)
                chat?.let {
                    val updatedChat = it.copy(
                        lastMessage = "📷 Image",
                        lastMessageTime = System.currentTimeMillis()
                    )
                    database.chatDao().updateChat(updatedChat)
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Failed to send image", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun compressImage(bitmap: Bitmap): Bitmap {
        val maxWidth = 800
        val maxHeight = 600
        val scaleFactor =
            minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        return if (scaleFactor < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scaleFactor).toInt(),
                (bitmap.height * scaleFactor).toInt(),
                true
            )
        } else bitmap
    }

    // ================== استقبال الرسائل ==================

    // UPDATED: Added logic to extract audio duration from the text field
    private fun handleReceivedMessage(text: String, isImage: Boolean, isAudio: Boolean, mediaData: String?) {
        // استخدم الـ currentChatId الحقيقي من الـ Service لو موجود
        val effectiveChatId = wifiService?.currentChatId ?: chatId ?: return

        var durationMs = 0L
        var finalMessageText = text

        if (isAudio) {
            if (text.startsWith("DURATION:")) {
                // NEW: استخلاص المدة من الحقل text
                val durationString = text.substringAfter("DURATION:")
                durationMs = durationString.toLongOrNull() ?: 0L
            }
            finalMessageText = getString(R.string.voice_message)
        } else if (isImage) {
            finalMessageText = "📷 Image"
        }


        lifecycleScope.launch {
            val message = MessageEntity(
                chatId = effectiveChatId,
                text = finalMessageText,
                isSentByMe = false,
                isImage = isImage,
                isAudio = isAudio,
                imageData = mediaData,
                isDelivered = true,
                // UPDATED: حفظ المدة المستخلصة
                audioDuration = durationMs
            )
            database.messageDao().insertMessage(message)
        }
    }

    override fun onResume() {
        super.onResume()
        wifiService?.isChatActivityVisible = true
        wifiService?.visibleChatId = chatId
    }

    override fun onPause() {
        super.onPause()
        wifiService?.isChatActivityVisible = false
        wifiService?.visibleChatId = null
        // Ensure recorder stops if paused during recording
        if (isRecording) {
            stopRecording(send = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            wifiService?.isChatActivityVisible = false
            wifiService?.visibleChatId = null
            unbindService(serviceConnection)
            serviceBound = false
        }
        messageAdapter.releaseMediaPlayer()
        releaseRecorder()
    }
}