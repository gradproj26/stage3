package com.example.offlineroutingapp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.offlineroutingapp.adapters.ViewPagerAdapter
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.fragments.DiscoverFragment
import com.example.offlineroutingapp.nativebridge.MasaarBridge
import com.example.offlineroutingapp.service.WifiDirectService
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import android.net.wifi.WpsInfo
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var intentFilter: IntentFilter

    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var peersAdapter: ArrayAdapter<String>

    private var wifiService: WifiDirectService? = null
    private var serviceBound = false
    private var currentChatId: String? = null
    private var isGroupOwner = false

    private val database by lazy { AppDatabase.getDatabase(this) }

    private val reqNearbyPermissionCode = 1001
    private val reqStoragePermissionCode = 1002

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { sendImage(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WifiDirectService.LocalBinder
            wifiService = binder.getService()
            serviceBound = true

            Log.d(TAG, "Service connected")

            // UPDATED: تصحيح التوقيع ليتضمن isAudio
            wifiService?.onMessageReceived = { text, isImage, isAudio, imageData ->
                handleReceivedMessage(text, isImage, isAudio, imageData)
            }

            wifiService?.onConnectionStatusChanged = { connected ->
                Log.d(TAG, "Connection status changed: $connected")
                if (!connected) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Connection lost",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    currentChatId = null

                    // لما الاتصال يقع، بعد ثانية ارجع اعمل Discover تاني
                    lifecycleScope.launch {
                        delay(1000)
                        startDiscovery()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Connected successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            wifiService?.onProfileReceived = { userId, displayName, photoBase64 ->
                handleReceivedProfile(userId, displayName, photoBase64)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiService = null
            serviceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_tabbed)

        Log.d(TAG, "MainActivity created")

        // ============ Masaar NodeId + native init ============
        val nodeId = getOrCreateNodeId()
        MasaarBridge.setNodeId(nodeId)
        Log.d("MasaarTest", "NodeId = $nodeId")

        // Test Masaar bridge (لسه بنخليه عشان الـ log)
        val testMsg = MasaarBridge.buildMessage("Hello from app!", "user_b1234567")
        Log.d("MasaarTest", "Generated message: $testMsg")
        // =====================================================

        initializeViews()
        setupWifiP2p()
        setupViewPager()
        startAndBindService()
        handleNotificationIntent(intent)

        // Check WiFi state
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(
                this,
                "Please enable WiFi for device discovery",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent) {
        val chatId = intent.getStringExtra("OPEN_CHAT_ID")
        if (chatId != null) {
            Log.d(TAG, "Opening chat from notification: $chatId")
            lifecycleScope.launch {
                val chat = database.chatDao().getChatById(chatId)
                chat?.let {
                    val chatIntent = Intent(this@MainActivity, ChatActivity::class.java).apply {
                        putExtra("CHAT_ID", it.chatId)
                        putExtra("USER_NAME", it.userName)
                        putExtra("USER_PHOTO", it.userProfilePhoto)
                    }
                    startActivity(chatIntent)
                }
            }
        }
    }

    private fun initializeViews() {
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
    }

    private fun setupViewPager() {
        viewPagerAdapter = ViewPagerAdapter(this)
        viewPager.adapter = viewPagerAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Chats"
                1 -> "Discover"
                2 -> "Profile"
                else -> ""
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position == 1) {
                    setupDiscoverFragment()
                }
            }
        })
    }

    private fun setupDiscoverFragment() {
        val fragment = supportFragmentManager.fragments
            .filterIsInstance<DiscoverFragment>()
            .firstOrNull()

        fragment?.let {
            val discoverBtn = it.getDiscoverButton()
            val peersList = it.getPeersList()

            if (!::peersAdapter.isInitialized) {
                peersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
                peersList.adapter = peersAdapter
            }

            discoverBtn.setOnClickListener {
                android.util.Log.d("MainActivity", "Discover button clicked")
                startDiscovery()
            }

            peersList.setOnItemClickListener { _, _, position, _ ->
                if (position < peers.size) {
                    connectToPeer(peers[position])
                }
            }
        } ?: run {
            android.util.Log.w("MainActivity", "DiscoverFragment not ready yet")
        }
    }

    private fun setupWifiP2p() {
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        Log.d(TAG, "WiFi P2P initialized")

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, WifiDirectService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "Service started and binding...")
    }

    // =================== Wi-Fi Direct Callbacks ===================

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers.clear()
        peers.addAll(peerList.deviceList)

        Log.d(TAG, "Peers found: ${peers.size}")

        val names = if (peers.isEmpty()) {
            listOf(getString(R.string.no_devices_found))
        } else {
            peers.map { device ->
                "${device.deviceName} (${device.deviceAddress})"
            }
        }

        if (::peersAdapter.isInitialized) {
            peersAdapter.clear()
            peersAdapter.addAll(names)
        } else {
            Log.w(TAG, "peersAdapter not initialized yet")
        }
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        val groupOwnerAddress = info.groupOwnerAddress
        isGroupOwner = info.isGroupOwner

        Log.d(TAG, "=== CONNECTION ESTABLISHED ===")
        Log.d(TAG, "IsGroupOwner: $isGroupOwner")
        Log.d(TAG, "GroupOwner IP: ${groupOwnerAddress?.hostAddress}")
        Log.d(TAG, "Current Chat ID: $currentChatId")

        if (isGroupOwner) {
            Log.d(TAG, "Starting server as Group Owner...")
            wifiService?.startServer()

            lifecycleScope.launch {
                delay(2000)
                exchangeProfileInfo()

                // بعد ما السيرفر يكون جاهز، ابعت HELLO كـ JSON
                val helloJson = MasaarBridge.buildHelloMessage()
                Log.d("MasaarTest", "Sending HELLO (GO): $helloJson")
                wifiService?.sendMessage(helloJson)
            }
        } else {
            Log.d(TAG, "Connecting to server at ${groupOwnerAddress.hostAddress}")
            wifiService?.connectToServer(groupOwnerAddress.hostAddress)

            lifecycleScope.launch {
                delay(3000)
                exchangeProfileInfo()

                // بعد ما الكلاينت يتصل، ابعت HELLO كـ JSON
                val helloJson = MasaarBridge.buildHelloMessage()
                Log.d("MasaarTest", "Sending HELLO (Client): $helloJson")
                wifiService?.sendMessage(helloJson)
            }
        }

        wifiService?.currentChatId = currentChatId

        viewPager.currentItem = 0
        Toast.makeText(this, "Connection established!", Toast.LENGTH_SHORT).show()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WiFi P2P state changed. Enabled: $isEnabled")

                    if (!isEnabled) {
                        Toast.makeText(
                            this@MainActivity,
                            "WiFi Direct is disabled. Please enable WiFi.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "Peers changed, requesting peer list...")
                    if (hasNearbyPermission()) {
                        try {
                            manager.requestPeers(channel, peerListListener)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException on requestPeers", e)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo =
                        intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    Log.d(TAG, "Connection changed. Connected: ${networkInfo?.isConnected}")

                    if (networkInfo?.isConnected == true) {
                        if (hasNearbyPermission()) {
                            try {
                                manager.requestConnectionInfo(channel, connectionInfoListener)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "SecurityException on requestConnectionInfo", e)
                            }
                        }
                    } else {
                        Log.d(TAG, "Disconnected from WiFi P2P")
                        wifiService?.closeConnections()
                        currentChatId = null

                        // بعد ما يفصل نرجع نعمل Discover (لو انتي في تب Discover)
                        lifecycleScope.launch {
                            delay(1500)
                            startDiscovery()
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device =
                        intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Log.d(TAG, "This device changed: ${device?.deviceName}")
                }
            }
        }
    }

    // =================== اتصال بالجهاز التاني ===================

    private fun connectToPeer(device: WifiP2pDevice) {
        Log.d(TAG, "Attempting to connect to: ${device.deviceName} (${device.deviceAddress})")

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        currentChatId = device.deviceAddress

        if (hasNearbyPermission()) {
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Connection initiated successfully")
                        Toast.makeText(
                            this@MainActivity,
                            "Connecting to ${device.deviceName}...",
                            Toast.LENGTH_SHORT
                        ).show()

                        lifecycleScope.launch {
                            val existingChat =
                                database.chatDao().getChatById(device.deviceAddress)
                            if (existingChat == null) {
                                val newChat = ChatEntity(
                                    chatId = device.deviceAddress,
                                    userName = "Connecting...",
                                    userProfilePhoto = null
                                )
                                database.chatDao().insertChat(newChat)
                                Log.d(TAG, "Created placeholder chat")
                            } else {
                                Log.d(TAG, "Chat already exists")
                            }
                        }
                    }

                    override fun onFailure(reason: Int) {
                        val reasonText = when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
                            WifiP2pManager.ERROR -> "Error"
                            WifiP2pManager.BUSY -> "Busy"
                            else -> "Unknown ($reason)"
                        }
                        Log.e(TAG, "Connection failed: $reasonText")
                        Toast.makeText(
                            this@MainActivity,
                            "Connection failed: $reasonText",
                            Toast.LENGTH_LONG
                        ).show()
                        currentChatId = null
                    }
                })
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on connect", e)
                Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w(TAG, "Missing nearby device permission")
            requestNearbyPermission()
        }
    }

    fun reconnectToDevice(deviceAddress: String) {
        Log.d(TAG, "Reconnect requested for: $deviceAddress")

        if (wifiService?.isConnected == true && currentChatId == deviceAddress) {
            Toast.makeText(this, "Already connected!", Toast.LENGTH_SHORT).show()
            return
        }

        val device = peers.find { it.deviceAddress == deviceAddress }

        if (device != null) {
            Log.d(TAG, "Device found in peer list, connecting...")
            connectToPeer(device)
        } else {
            Log.w(TAG, "Device not in peer list. Starting discovery...")
            Toast.makeText(this, "Searching for device...", Toast.LENGTH_SHORT).show()

            viewPager.currentItem = 1

            lifecycleScope.launch {
                delay(500)
                startDiscovery()
            }
        }
    }

    // =================== Stage 2 Helper: Discover + Reset ===================

    private fun startDiscovery() {
        Log.d(TAG, "startDiscovery() called")

        if (!hasNearbyPermission()) {
            Log.w(TAG, "Missing permission for discovery")
            requestNearbyPermission()
            return
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Please enable WiFi first", Toast.LENGTH_LONG).show()
            Log.e(TAG, "WiFi is disabled")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 1) حاول توقف أي Discover قديم أو Group قديم
            safeResetP2P()

            // 2) استنى شوية صغيرين بعد الـ Reset
            delay(400)

            // 3) ابدا Discover جديد
            withContext(Dispatchers.Main) {
                // تم إضافة try-catch هنا لحل خطأ SecurityException
                try {
                    manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "Discovery started successfully")
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.search_started),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        override fun onFailure(reason: Int) {
                            val errorMsg = when (reason) {
                                WifiP2pManager.P2P_UNSUPPORTED ->
                                    "WiFi Direct not supported on this device"
                                WifiP2pManager.BUSY ->
                                    "System busy, please try again"
                                WifiP2pManager.ERROR ->
                                    "Internal error occurred"
                                else -> "Failed with code: $reason"
                            }
                            Log.e(TAG, "Discovery failed: $errorMsg")
                            Toast.makeText(
                                this@MainActivity,
                                "Discovery failed: $errorMsg",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException in startDiscovery: ${e.message}", e)
                    Toast.makeText(
                        this@MainActivity,
                        "Permission error: Please grant nearby devices/location permission.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in startDiscovery: ${e.message}", e)
                    Toast.makeText(
                        this@MainActivity,
                        "Error starting discovery: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // دالة reset لبروتوكول Wi-Fi Direct قبل أي Discover جديد
    private suspend fun safeResetP2P() {
        if (!hasNearbyPermission()) return

        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "safeResetP2P(): stopping peer discovery")
                manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "stopPeerDiscovery success")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "stopPeerDiscovery failed: $reason")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Error calling stopPeerDiscovery: ${e.message}")
            }

            try {
                Log.d(TAG, "safeResetP2P(): removing group (if any)")
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "removeGroup success")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "removeGroup failed: $reason")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Error calling removeGroup: ${e.message}")
            }
        }
    }

    // =================== Profile Exchange ===================

    private fun exchangeProfileInfo() {
        lifecycleScope.launch {
            val user = database.userDao().getUser()
            user?.let {
                var photoBase64: String? = null
                if (!it.profilePhotoPath.isNullOrEmpty()) {
                    try {
                        val file = File(it.profilePhotoPath)
                        if (file.exists()) {
                            val bytes = file.readBytes()
                            photoBase64 = android.util.Base64.encodeToString(
                                bytes,
                                android.util.Base64.NO_WRAP
                            )
                            Log.d(TAG, "Profile photo encoded, size: ${bytes.size}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error encoding profile photo: ${e.message}")
                    }
                }

                wifiService?.sendProfileInfo(it.userId, it.displayName, photoBase64)
                Log.d(TAG, "Sent profile info: ${it.userId}, ${it.displayName}")
            }
        }
    }

    private fun handleReceivedProfile(
        userId: String,
        displayName: String,
        photoBase64: String
    ) {
        lifecycleScope.launch {
            Log.d(
                TAG,
                "Handling received profile: $userId, $displayName, hasPhoto: ${photoBase64.isNotEmpty()}"
            )

            // 👈 لو currentChatId = null هنستخدم userId كـ chatId
            val chatId = currentChatId ?: userId
            currentChatId = chatId  // نثبّت الـ chatId في الميموري

            var photoPath: String? = null
            if (photoBase64.isNotEmpty()) {
                try {
                    val photoBytes =
                        android.util.Base64.decode(photoBase64, android.util.Base64.NO_WRAP)
                    val profileDir = File(filesDir, "received_profiles")
                    if (!profileDir.exists()) {
                        profileDir.mkdirs()
                    }

                    val fileName = "profile_${userId}_${System.currentTimeMillis()}.jpg"
                    val file = File(profileDir, fileName)
                    file.writeBytes(photoBytes)
                    photoPath = file.absolutePath

                    Log.d(TAG, "Saved received profile photo to: $photoPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving received photo: ${e.message}", e)
                }
            }

            // 👈 هنشوف لو فيه chat بنفس الـ chatId ولا لأ
            val chat = database.chatDao().getChatById(chatId)
            if (chat != null) {
                val updatedChat = chat.copy(
                    userName = displayName,
                    userProfilePhoto = photoPath
                )
                database.chatDao().updateChat(updatedChat)
                Log.d(TAG, "Updated existing chat with profile: $displayName (chatId=$chatId)")
            } else {
                val newChat = ChatEntity(
                    chatId = chatId,
                    userName = displayName,
                    userProfilePhoto = photoPath
                )
                database.chatDao().insertChat(newChat)
                Log.d(TAG, "Created new chat with profile: $displayName (chatId=$chatId)")
            }
        }
    }

    // =================== Messages & Images ===================

    // تم تحديث توقيع الدالة لإضافة isAudio
    private fun handleReceivedMessage(text: String, isImage: Boolean, isAudio: Boolean, imageData: String?) {
        Log.d(TAG, "Message received. Type: ${if (isImage) "Image" else if (isAudio) "Audio" else "Text"}")
        currentChatId?.let { chatId ->
            lifecycleScope.launch {

                var durationMs = 0L
                var lastMsgText = text

                if (isAudio) {
                    // NEW: استخلاص المدة إذا كانت رسالة صوتية
                    if (text.startsWith("DURATION:")) {
                        val durationString = text.substringAfter("DURATION:")
                        durationMs = durationString.toLongOrNull() ?: 0L
                    }
                    lastMsgText = getString(R.string.voice_message)
                } else if (isImage) {
                    lastMsgText = "📷 Image"
                }

                val message = MessageEntity(
                    chatId = chatId,
                    // نستخدم الـ text المعروض (أو النص الفعلي لو تكست)
                    text = lastMsgText,
                    isSentByMe = false,
                    isImage = isImage,
                    isAudio = isAudio, // حفظ نوع الرسالة الصوتية
                    imageData = imageData,
                    isDelivered = true,
                    audioDuration = durationMs // حفظ المدة
                )
                database.messageDao().insertMessage(message)

                val chat = database.chatDao().getChatById(chatId)
                chat?.let {
                    val updatedChat = it.copy(
                        lastMessage = lastMsgText,
                        lastMessageTime = System.currentTimeMillis(),
                        unreadCount = it.unreadCount + 1
                    )
                    database.chatDao().updateChat(updatedChat)
                }
            }
        }
    }

    private fun sendImage(imageUri: Uri) {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val compressedBitmap = compressImage(bitmap)
                val byteArrayOutputStream = ByteArrayOutputStream()
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                wifiService?.sendImage(imageBytes)

                currentChatId?.let { chatId ->
                    val base64Image =
                        android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    val message = MessageEntity(
                        chatId = chatId,
                        text = "",
                        isSentByMe = true,
                        isImage = true,
                        isAudio = false,
                        imageData = base64Image,
                        isDelivered = false,
                        audioDuration = 0L
                    )
                    database.messageDao().insertMessage(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending image: ${e.message}")
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

    // =================== Permissions ===================

    private fun hasNearbyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNearbyPermission() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissionsToRequest, reqNearbyPermissionCode)
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), reqStoragePermissionCode)
    }

    // =================== Lifecycle ===================

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
        Log.d(TAG, "Receiver registered")

        // نحاول نضبط الـ Discover fragment لو موجود
        setupDiscoverFragment()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
        Log.d(TAG, "Receiver unregistered")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        Log.d(TAG, "MainActivity destroyed")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            reqNearbyPermissionCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Nearby permission granted")
                    // بعد ما البرميشن يتوافق، نجرب Discover
                    startDiscovery()
                } else {
                    Log.w(TAG, "Nearby permission denied")
                    Toast.makeText(
                        this,
                        getString(R.string.permission_denied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            reqStoragePermissionCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Storage permission granted")
                    Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // =================== Masaar helper ===================

    private fun getOrCreateNodeId(): String {
        val prefs = getSharedPreferences("masaar_prefs", MODE_PRIVATE)
        var id = prefs.getString("node_id", null)
        if (id == null) {
            id = "user_" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString("node_id", id).apply()
        }
        return id
    }
}