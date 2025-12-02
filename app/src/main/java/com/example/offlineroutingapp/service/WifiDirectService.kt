package com.example.offlineroutingapp.service

import com.example.offlineroutingapp.nativebridge.MasaarBridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.offlineroutingapp.MainActivity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import org.json.JSONObject   // ⬅⬅ إضافة دي

class WifiDirectService : Service() {
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var serverSocket: ServerSocket? = null
    var clientSocket: Socket? = null
    var isConnected = false
    var currentChatId: String? = null
    // UPDATED: Added isAudio parameter
    var onMessageReceived: ((String, Boolean, Boolean, String?) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onDeliveryStatusChanged: ((String, Boolean) -> Unit)? = null
    var onSeenStatusChanged: ((String, Boolean) -> Unit)? = null
    var onProfileReceived: ((String, String, String) -> Unit)? = null
    var isChatActivityVisible = false
    var visibleChatId: String? = null

    companion object {
        const val CHANNEL_ID = "WifiDirectServiceChannel"
        const val MESSAGE_CHANNEL_ID = "MessageNotificationChannel"
        const val NOTIFICATION_ID = 1
        const val SERVER_PORT = 8888
        private const val TAG = "WifiDirectService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): WifiDirectService = this@WifiDirectService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createNotification("WiFi Direct Service Running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Direct Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps WiFi Direct connection alive"
            }

            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New message notifications"
                enableVibration(true)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(messageChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline Chat")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showMessageNotification(messageText: String, chatId: String?) {
        // لو الشات مفتوح دلوقتي، منعملش notification
        if (isChatActivityVisible && visibleChatId == chatId) {
            android.util.Log.d(TAG, "Chat is visible, skipping notification")
            return
        }

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_CHAT_ID", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle("New Message")
            .setContentText(messageText)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun startServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                android.util.Log.d(TAG, "Server started on port $SERVER_PORT")
                updateNotification("Waiting for connection...")

                val socket = serverSocket?.accept()
                clientSocket = socket
                isConnected = true

                android.util.Log.d(TAG, "Client connected!")

                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(true)
                    updateNotification("Connected - Chat active")
                }

                listenForMessages(socket!!)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Server error: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateNotification("Connection failed")
                }
            }
        }
    }

    fun connectToServer(hostAddress: String) {
        serviceScope.launch {
            try {
                android.util.Log.d(TAG, "Attempting to connect to $hostAddress:$SERVER_PORT")
                val socket = Socket(hostAddress, SERVER_PORT)
                clientSocket = socket
                isConnected = true

                android.util.Log.d(TAG, "Connected to server!")

                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(true)
                    updateNotification("Connected - Chat active")
                }

                listenForMessages(socket)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Client connection error: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateNotification("Connection failed: ${e.message}")
                }
            }
        }
    }

    private fun listenForMessages(socket: Socket) {
        serviceScope.launch {
            try {
                val dataInputStream = DataInputStream(socket.getInputStream())
                while (isConnected && !socket.isClosed) {
                    try {
                        val messageType = dataInputStream.readUTF()
                        android.util.Log.d(TAG, "Received message type: $messageType")

                        when (messageType) {
                            "TEXT" -> {
                                val textMessageRaw = dataInputStream.readUTF()
                                android.util.Log.d(TAG, "Received raw text: $textMessageRaw")

                                // ⬇⬇ نحاول نفك JSON بتاع Masaar لو الرسالة من نوع DATA
                                var chatPayload = textMessageRaw
                                try {
                                    val obj = JSONObject(textMessageRaw)
                                    val type = obj.optString("type")
                                    if (type == "DATA") {
                                        val payload = obj.optString("payload", textMessageRaw)
                                        val src = obj.optString("src")
                                        val dst = obj.optString("dst")
                                        android.util.Log.d(
                                            "MasaarRouting",
                                            "DATA msg from $src to $dst, payload=$payload"
                                        )
                                        chatPayload = payload
                                    }
                                } catch (e: Exception) {
                                    // لو مش JSON أصلاً، نسيبه زي ما هو
                                    android.util.Log.w(
                                        "MasaarRouting",
                                        "Not a Masaar JSON, using raw text"
                                    )
                                }

                                // ممكن كمان نستدعي C++ handleIncoming بس للـ debug
                                try {
                                    val coreResult = MasaarBridge.handleIncoming(textMessageRaw)
                                    android.util.Log.d(
                                        "MasaarCore",
                                        "handleIncoming() => $coreResult"
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.w(
                                        "MasaarCore",
                                        "handleIncoming JNI failed: ${e.message}"
                                    )
                                }

                                showMessageNotification(chatPayload, currentChatId)

                                withContext(Dispatchers.Main) {
                                    // isImage=false, isAudio=false, imageData=null
                                    onMessageReceived?.invoke(chatPayload, false, false, null)
                                }

                                sendDeliveryReceipt("msg_${System.currentTimeMillis()}")
                            }

                            "IMAGE" -> {
                                val imageSize = dataInputStream.readInt()
                                val imageBytes = ByteArray(imageSize)
                                dataInputStream.readFully(imageBytes)

                                val base64Image = android.util.Base64.encodeToString(
                                    imageBytes,
                                    android.util.Base64.NO_WRAP
                                )

                                showMessageNotification("📷 Image", currentChatId)

                                withContext(Dispatchers.Main) {
                                    // isImage=true, isAudio=false
                                    onMessageReceived?.invoke("", true, false, base64Image)
                                }

                                sendDeliveryReceipt("img_${System.currentTimeMillis()}")
                            }

                            // Handle incoming Voice Message
                            "VOICE" -> {
                                val voiceSize = dataInputStream.readInt()
                                // UPDATED: قراءة المدة المرسلة
                                val voiceDuration = dataInputStream.readLong()

                                val voiceBytes = ByteArray(voiceSize)
                                dataInputStream.readFully(voiceBytes)

                                val base64Voice = android.util.Base64.encodeToString(
                                    voiceBytes,
                                    android.util.Base64.NO_WRAP
                                )

                                showMessageNotification("🎤 Voice Message", currentChatId)

                                withContext(Dispatchers.Main) {
                                    // UPDATED: تمرير المدة في حقل text بتنسيق DURATION:<duration>
                                    // isImage=false, isAudio=true, imageData=base64Voice
                                    onMessageReceived?.invoke("DURATION:$voiceDuration", false, true, base64Voice)
                                }

                                sendDeliveryReceipt("voice_${System.currentTimeMillis()}")
                            }

                            "DELIVERY_RECEIPT" -> {
                                val messageId = dataInputStream.readUTF()
                                android.util.Log.d(TAG, "Message delivered: $messageId")
                                withContext(Dispatchers.Main) {
                                    onDeliveryStatusChanged?.invoke(messageId, true)
                                }
                            }

                            "SEEN_RECEIPT" -> {
                                val messageId = dataInputStream.readUTF()
                                android.util.Log.d(TAG, "Message seen: $messageId")
                                withContext(Dispatchers.Main) {
                                    onSeenStatusChanged?.invoke(messageId, true)
                                }
                            }

                            "PROFILE_INFO" -> {
                                val userId = dataInputStream.readUTF()
                                val displayName = dataInputStream.readUTF()
                                val photoBase64 = dataInputStream.readUTF()

                                android.util.Log.d(TAG, "Received profile: $userId, $displayName")

                                withContext(Dispatchers.Main) {
                                    onProfileReceived?.invoke(userId, displayName, photoBase64)
                                }
                            }

                            "HELLO" -> {
                                val helloJson = dataInputStream.readUTF()
                                android.util.Log.d(TAG, "Received HELLO: $helloJson")
                                // بعدين هنعمل Neighbor Table هنا
                            }

                            else -> {
                                android.util.Log.w(TAG, "Unknown message type: $messageType")
                            }
                        }
                    } catch (e: Exception) {
                        if (isConnected) {
                            android.util.Log.e(TAG, "Error reading message: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error in message listener: ${e.message}")
            } finally {
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                    updateNotification("Disconnected")
                }
            }
        }
    }

    fun sendMessage(text: String) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    // ⬇⬇ هنا بنلف الرسالة بتاعة الشات في Masaar JSON
                    val dstId = currentChatId ?: "unknown"
                    val wrappedJson = try {
                        MasaarBridge.buildMessage(text, dstId)
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "MasaarRouting",
                            "Error in buildMessage, fallback to plain: ${e.message}"
                        )
                        text
                    }

                    android.util.Log.d(TAG, "Sending text message (wrapped): $wrappedJson")
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("TEXT")
                    dataOutputStream.writeUTF(wrappedJson)
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "Message sent successfully")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending message: ${e.message}")
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                }
            }
        }
    }

    fun sendImage(imageBytes: ByteArray) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    android.util.Log.d(TAG, "Sending image, size: ${imageBytes.size}")
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("IMAGE")
                    dataOutputStream.writeInt(imageBytes.size)
                    dataOutputStream.write(imageBytes)
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "Image sent successfully")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending image: ${e.message}")
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                }
            }
        }
    }

    // UPDATED: Send Voice Message (Accepts durationMs)
    fun sendVoice(voiceBytes: ByteArray, durationMs: Long) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    android.util.Log.d(TAG, "Sending voice message, size: ${voiceBytes.size}, duration: $durationMs")
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("VOICE")
                    dataOutputStream.writeInt(voiceBytes.size)
                    // UPDATED: كتابة المدة قبل البايتات
                    dataOutputStream.writeLong(durationMs)
                    dataOutputStream.write(voiceBytes)
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "Voice message sent successfully")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending voice message: ${e.message}")
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                }
            }
        }
    }

    fun sendDeliveryReceipt(messageId: String) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("DELIVERY_RECEIPT")
                    dataOutputStream.writeUTF(messageId)
                    dataOutputStream.flush()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending delivery receipt: ${e.message}")
            }
        }
    }

    fun sendSeenReceipt(messageId: String) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("SEEN_RECEIPT")
                    dataOutputStream.writeUTF(messageId)
                    dataOutputStream.flush()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending seen receipt: ${e.message}")
            }
        }
    }

    fun sendProfileInfo(userId: String, displayName: String, photoBase64: String?) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("PROFILE_INFO")
                    dataOutputStream.writeUTF(userId)
                    dataOutputStream.writeUTF(displayName)
                    dataOutputStream.writeUTF(photoBase64 ?: "")
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "Profile info sent")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending profile info: ${e.message}")
            }
        }
    }

    fun closeConnections() {
        isConnected = false
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error closing connections: ${e.message}")
        }
        clientSocket = null
        serverSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnections()
        serviceScope.cancel()
    }

    fun sendHello() {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    val helloJson = MasaarBridge.buildHelloMessage()
                    android.util.Log.d(TAG, "Sending HELLO: $helloJson")

                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("HELLO")
                    dataOutputStream.writeUTF(helloJson)
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "HELLO sent successfully")
                } else {
                    android.util.Log.w(TAG, "Cannot send HELLO: not connected")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending HELLO: ${e.message}")
            }
        }
    }
}