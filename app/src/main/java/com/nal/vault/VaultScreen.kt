package com.nal.vault

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey

/**
 * The vault: a master password unlocks files stored AES-256 encrypted, with the index encrypted
 * too so even the names need the password. The key is derived on unlock and dropped when the app
 * leaves the foreground, so coming back asks for the password again.
 */
@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var configured by remember { mutableStateOf(VaultCrypto.isConfigured(context)) }
    var key by remember { mutableStateOf<SecretKey?>(null) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var contents by remember { mutableStateOf(VaultContents()) }
    var folder by remember { mutableStateOf("") }
    var showNewFolder by remember { mutableStateOf(false) }
    // True while the file picker or another app is on screen: the app goes to the background for
    // that, and locking then would drop the key the picker is about to need.
    var external by remember { mutableStateOf(false) }
    // A file picked while the process was killed is remembered and added once the vault is
    // unlocked again, instead of being lost with the key.
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    // Decrypted copies written for viewing are deleted as soon as they are no longer needed.
    val tempFiles = remember { mutableListOf<File>() }

    fun clearTemp() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
    }

    var playing by remember { mutableStateOf<File?>(null) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var category by remember { mutableStateOf("") }
    var attempts by rememberSaveable { mutableStateOf(0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    var sessionPassword by remember { mutableStateOf("") }
    // Auto-lock after a minute in the background instead of the instant lock, which made the
    // picker and a quick switch to another app lock the vault for nothing.
    var backgroundedAt by rememberSaveable { mutableStateOf(0L) }
    var folderName by remember { mutableStateOf("") }

    // No screenshots and no screen recording while the vault is on screen: the window is marked
    // secure for as long as this screen is composed, and the recents preview shows nothing.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            runCatching { exoPlayer?.release() }
            exoPlayer = null
            clearTemp()
        }
    }

    // Auto-lock: leaving the foreground drops the derived key, so the vault has to be unlocked
    // again rather than staying open in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !external) {
                backgroundedAt = System.currentTimeMillis()
            }
            if (event == Lifecycle.Event.ON_START && !external) {
                val since = backgroundedAt
                if (key != null && since > 0L && System.currentTimeMillis() - since > LOCK_AFTER_MS) {
                    key = null
                    password = ""
                    confirm = ""
                    contents = VaultContents()
                    folder = ""
                    status = null
                    error = null
                    clearTemp()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun refresh(current: SecretKey) {
        scope.launch {
            contents = withContext(Dispatchers.IO) { VaultCrypto.contents(context, current) }
        }
    }

    fun addFromUri(current: SecretKey, uri: Uri) {
        busy = true
        error = null
        status = null
        val target = folder
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(context, uri) ?: "file"
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        VaultCrypto.add(context, current, name, target, input)
                    } ?: throw IllegalStateException("The file could not be opened")
                }
            }
            busy = false
            result.onSuccess { item ->
                status = "${item.name} added to the vault"
                contents = withContext(Dispatchers.IO) { VaultCrypto.contents(context, current) }
            }.onFailure {
                error = it.message ?: it.javaClass.simpleName
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val current = key
        external = false
        if (uri == null) {
            status = "No file was chosen"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (current == null) {
            // The picker took the app out of the foreground and the process was killed, so the
            // key is gone. Keep the pick and finish the job after the next unlock.
            pendingUri = uri.toString()
            status = "Unlock the vault again to finish adding this file"
            return@rememberLauncherForActivityResult
        }
        addFromUri(current, uri)
    }

    val galleryPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val current = key
            external = false
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (current == null) {
                pendingUri = uri.toString()
                status = "Unlock the vault again to finish adding this file"
                return@rememberLauncherForActivityResult
            }
            addFromUri(current, uri)
        }

    val atRoot = folder.isEmpty() && !showAll && category.isEmpty()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            external = false
            val uri = cameraUri
            if (!ok || uri == null) {
                status = "No photo was taken"
                return@rememberLauncherForActivityResult
            }
            val current = key
            if (current == null) {
                pendingUri = uri.toString()
                status = "Unlock the vault again to finish adding the photo"
            } else {
                addFromUri(current, uri)
            }
        }

    fun launchCamera() {
        runCatching {
            val dir = File(context.cacheDir, "vault").apply { mkdirs() }
            val file = File(dir, "camera_" + System.currentTimeMillis() + ".jpg")
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            cameraUri = uri
            external = true
            cameraLauncher.launch(uri)
        }.onFailure { error = "The camera could not be opened" }
    }

    val visible = if (atRoot) {
        emptyList()
    } else {
        contents.items
            .filter { folder.isEmpty() || it.folder == folder }
            .filter { category.isEmpty() || categoryOf(it.name) == category }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Nal Vault", style = MaterialTheme.typography.headlineSmall)
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                when {
                    !configured -> SetupVault(
                        password = password,
                        confirm = confirm,
                        onPassword = { password = it },
                        onConfirm = { confirm = it },
                        onCreate = {
                            error = when {
                                password.length < 12 -> "Use at least 12 characters"
                                password != confirm -> "The two passwords do not match"
                                else -> null
                            }
                            if (error == null) {
                                VaultCrypto.createMasterPassword(context, password)
                                configured = true
                                password = ""
                                confirm = ""
                                status = "Vault created. Set folders and add files."
                            }
                        }
                    )

                    key == null -> UnlockVault(
                        password = password,
                        busy = busy,
                        biometric = BiometricUnlock.isEnabled(context),
                        onBiometric = {
                            val activity = context.findActivity() as? FragmentActivity
                            if (activity == null) {
                                error = "Fingerprint is not available here"
                            } else {
                                BiometricUnlock.unlock(context, activity) { stored ->
                                    if (stored == null) {
                                        error = "Fingerprint unlock was cancelled"
                                    } else {
                                        password = stored
                                        error = null
                                        busy = true
                                        scope.launch {
                                            val unlocked = withContext(Dispatchers.Default) {
                                                VaultCrypto.unlock(context, stored)
                                            }
                                            busy = false
                                            if (unlocked == null) {
                                                error = "The stored password no longer works"
                                            } else {
                                                key = unlocked
                                                sessionPassword = stored
                                                password = ""
                                                contents = withContext(Dispatchers.IO) {
                                                    VaultCrypto.contents(context, unlocked)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onPassword = { password = it },
                        onUnlock = {
                            busy = true
                            error = null
                            scope.launch {
                                val unlocked = withContext(Dispatchers.Default) {
                                    VaultCrypto.unlock(context, password)
                                }
                                busy = false
                                if (unlocked == null) {
                                    attempts += 1
                                    if (attempts >= MAX_ATTEMPTS) {
                                        VaultCrypto.wipe(context)
                                        key = null
                                        contents = VaultContents()
                                        configured = false
                                        attempts = 0
                                        error = null
                                        status = "Vault wiped after $MAX_ATTEMPTS wrong passwords"
                                    } else {
                                        error = "Wrong master password ($attempts/$MAX_ATTEMPTS)"
                                    }
                                } else {
                                    attempts = 0
                                    sessionPassword = password
                                    key = unlocked
                                    password = ""
                                    contents = withContext(Dispatchers.IO) {
                                        VaultCrypto.contents(context, unlocked)
                                    }
                                    val pending = pendingUri
                                    if (pending != null) {
                                        pendingUri = null
                                        runCatching { addFromUri(unlocked, Uri.parse(pending)) }
                                    }
                                }
                            }
                        }
                    )

                    else -> {
                        HomeTiles(
                            onCamera = { launchCamera() },
                            onCategory = {
                                category = it
                                folder = ""
                                showAll = false
                            },
                            onFiles = {
                                showAll = true
                                category = ""
                                folder = ""
                            },
                            onSettings = { showSettings = true },
                            onAddApp = {
                                external = true
                                picker.launch(arrayOf("application/vnd.android.package-archive"))
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        if (atRoot) {
                            FolderTiles(
                                folders = contents.folders,
                                items = contents.items,
                                onOpen = { folder = it },
                                onNewFolder = { showNewFolder = true },
                                onAll = { showAll = true }
                            )
                            Spacer(Modifier.height(10.dp))
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { folder = ""; showAll = false; category = "" }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                                Text(
                                    when {
                                        folder.isNotEmpty() -> folder
                                        category.isNotEmpty() -> category
                                        else -> "All files"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = iconPaletteColor(
                                        when {
                                            folder.isNotEmpty() -> folder
                                            category.isNotEmpty() -> category
                                            else -> "all"
                                        }
                                    ),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when {
                                    atRoot -> "Open a folder to see its files"
                                    visible.isEmpty() -> "Nothing here yet"
                                    else -> "${visible.size} file(s), AES-256 encrypted"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { showNewFolder = true },
                                enabled = !busy
                            ) { Text("+ Folder") }
                            TextButton(
                                onClick = {
                                    external = true
                                    galleryPicker.launch(arrayOf("image/*", "video/*"))
                                },
                                enabled = !busy
                            ) { Text("Gallery") }
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = { external = true; picker.launch(arrayOf("*/*")) },
                                enabled = !busy
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = Color(0xFF0A84FF)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (busy) "Adding..." else "Add file")
                            }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.id }) { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val thumb = thumbs[item.id]
                                    LaunchedEffect(item.id) {
                                        val current = key
                                        if (thumb == null && current != null &&
                                            (categoryOf(item.name) == "Photos" ||
                                                categoryOf(item.name) == "Videos")
                                        ) {
                                            val bitmap = withContext(Dispatchers.IO) {
                                                runCatching { makeThumb(context, current, item) }
                                                    .getOrNull()
                                            }
                                            if (bitmap != null) thumbs[item.id] = bitmap
                                        }
                                    }
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Icon(
                                            imageVector = iconFor(item.name),
                                            contentDescription = null,
                                            tint = colourFor(item.name),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.fillMaxWidth(0.62f)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            if (item.folder.isEmpty()) readableSize(item.size)
                                            else "${item.folder} · ${readableSize(item.size)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val current = key ?: return@Button
                                            busy = true
                                            scope.launch {
                                                if (isMedia(item.name)) {
                                                    // Clear first: the new copy may have the same
                                                    // name, and deleting it after writing it left the
                                                    // player pointing at a file that no longer existed.
                                                    clearTemp()
                                                    val file = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            decryptToCache(context, current, item)
                                                        }.getOrNull()
                                                    }
                                                    if (file == null) {
                                                        error = "This file could not be opened"
                                                    } else {
                                                        tempFiles.add(file)
                                                        playing = file
                                                    }
                                                } else if (isImage(item.name)) {
                                                    // Images are shown inside the vault: handing them
                                                    // to a gallery would leave a decrypted copy in
                                                    // another app's hands.
                                                    val bitmap = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            val temp = decryptToCache(context, current, item)
                                                            try {
                                                                BitmapFactory.decodeFile(temp.absolutePath)
                                                            } finally {
                                                                temp.delete()
                                                            }
                                                        }.getOrNull()
                                                    }
                                                    if (bitmap == null) {
                                                        error = "This image could not be shown"
                                                    } else {
                                                        preview = bitmap
                                                    }
                                                } else {
                                                    external = true
                                                    var handedOver: File? = null
                                                    val failure = withContext(Dispatchers.IO) {
                                                        runCatching { handedOver = openItem(context, current, item) }
                                                            .exceptionOrNull()
                                                    }
                                                    external = false
                                                    handedOver?.let { tempFiles.add(it) }
                                                    if (failure != null) {
                                                        error = failure.message
                                                            ?: "Could not open the file"
                                                    }
                                                }
                                                busy = false
                                            }
                                        },
                                        enabled = !busy
                                    ) { Text("Open") }
                                    IconButton(
                                        onClick = {
                                            val current = key ?: return@IconButton
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    VaultCrypto.remove(context, current, item.id)
                                                }
                                                contents = VaultCrypto.contents(context, current)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete",
                                            tint = Color(0xFFFF453A)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        playing?.let { file ->
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            val exo = ExoPlayer.Builder(ctx).build()
                            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                            exo.prepare()
                            exo.playWhenReady = true
                            player = exo
                            exoPlayer = exo
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = {
                        runCatching { exoPlayer?.release() }
                        exoPlayer = null
                        playing = null
                        clearTemp()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }

        if (showSettings) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                VaultSettings(
                    onBack = { showSettings = false },
                    fingerprintAvailable = BiometricUnlock.isAvailable(context),
                    fingerprintEnabled = BiometricUnlock.isEnabled(context),
                    onEnableFingerprint = {
                        val activity = context.findActivity() as? FragmentActivity
                        if (activity == null || sessionPassword.isEmpty()) {
                            error = "Unlock with the password first"
                        } else {
                            BiometricUnlock.enable(context, activity, sessionPassword) { ok ->
                                status = if (ok) "Fingerprint unlock enabled" else "Fingerprint was not set up"
                                showSettings = false
                            }
                        }
                    },
                    onDisableFingerprint = {
                        BiometricUnlock.disable(context)
                        status = "Fingerprint unlock disabled"
                        showSettings = false
                    }
                )
            }
        }

        preview?.let { bitmap ->
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { preview = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }

        if (key != null) {
            FloatingActionButton(
                onClick = { external = true; picker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 96.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add files")
            }
        }
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false; folderName = "" },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = key
                        val name = folderName.trim()
                        if (current != null && name.isNotEmpty()) {
                            scope.launch {
                                contents = withContext(Dispatchers.IO) {
                                    VaultCrypto.addFolder(context, current, name)
                                }
                                folder = name
                                status = "Folder \"$name\" created"
                            }
                        }
                        showNewFolder = false
                        folderName = ""
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false; folderName = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SetupVault(
    password: String,
    confirm: String,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onCreate: () -> Unit
) {
    Text(
        "Set a master password. Files are encrypted with AES-256 and the key comes from this " +
            "password, so keep it safe - without it the files cannot be opened again.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        label = { Text("Master password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = confirm,
        onValueChange = onConfirm,
        label = { Text("Repeat password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create vault") }
}

@Composable
private fun UnlockVault(
    password: String,
    busy: Boolean,
    biometric: Boolean,
    onBiometric: () -> Unit,
    onPassword: (String) -> Unit,
    onUnlock: () -> Unit
) {
    Text(
        "Unlock the vault with your master password.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        label = { Text("Master password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onUnlock,
        enabled = !busy && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (busy) "Checking..." else "Unlock") }
    if (biometric) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBiometric, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Unlock with fingerprint")
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

private fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun mimeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}

private fun isMedia(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf(
        "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac"
    )
}

private fun isImage(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
}

private fun decryptToCache(context: Context, key: SecretKey, item: VaultItem): File {
    val dir = File(context.cacheDir, "vault").apply { mkdirs() }
    val out = File(dir, item.name)
    out.outputStream().use { target ->
        VaultCrypto.decrypt(key, VaultCrypto.fileFor(context, item.id), target)
    }
    return out
}

private fun openItem(context: Context, key: SecretKey, item: VaultItem): File {
    val out = decryptToCache(context, key, item)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(item.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    return out
}


@Composable
private fun CategoryTiles(items: List<VaultItem>, selected: String, onSelect: (String) -> Unit) {
    val categories = listOf(
        Triple("Photos", Icons.Filled.Image, Color(0xFF0A84FF)),
        Triple("Videos", Icons.Filled.Videocam, Color(0xFF7C3AED)),
        Triple("Audio", Icons.Filled.MusicNote, Color(0xFFFF9F0A)),
        Triple("Documents", Icons.Filled.Description, Color(0xFF32ADE6)),
        Triple("APKs", Icons.Filled.Android, Color(0xFF30D158)),
        Triple("Archives", Icons.Filled.FolderZip, Color(0xFFAC8E68))
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { entry ->
                    val name = entry.first
                    val icon = entry.second
                    val colour = entry.third
                    val count = items.count { categoryOf(it.name) == name }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(name) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected == name) {
                                colour.copy(alpha = 0.28f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = colour,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(name, style = MaterialTheme.typography.labelMedium)
                            Text("$count", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun categoryOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> "Photos"
        "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v" -> "Videos"
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac" -> "Audio"
        "apk" -> "APKs"
        "zip", "rar", "7z", "tar", "gz" -> "Archives"
        else -> "Documents"
    }
}


@Composable
private fun FolderTiles(
    folders: List<String>,
    items: List<VaultItem>,
    onOpen: (String) -> Unit,
    onNewFolder: () -> Unit,
    onAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        folders.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { name ->
                    val count = items.count { it.folder == name }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpen(name) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = iconPaletteColor(name),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                            Text("$count", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** How long the vault may stay in the background before it locks itself. */
private const val LOCK_AFTER_MS = 60_000L


/** Wrong passwords tolerated before the vault wipes itself. */
private const val MAX_ATTEMPTS = 10


@Composable
private fun FolderTile(
    label: String,
    count: Int?,
    colour: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = colour,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (count != null) {
                Text("$count", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


@Composable
private fun VaultSettings(
    onBack: () -> Unit,
    fingerprintAvailable: Boolean,
    fingerprintEnabled: Boolean,
    onEnableFingerprint: () -> Unit,
    onDisableFingerprint: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(12.dp))
        SettingRow("Nal Vault", "Version 1.0")
        SettingRow("Contact", "krunal.m.gamitl3o8@zohomail.in") {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:krunal.m.gamitl3o8@zohomail.in")
                    }
                )
            }
        }
        if (fingerprintAvailable) {
            if (fingerprintEnabled) {
                SettingRow("Fingerprint unlock", "On - tap to disable", onDisableFingerprint)
            } else {
                SettingRow("Fingerprint unlock", "Off - tap to enable", onEnableFingerprint)
            }
        } else {
            SettingRow("Fingerprint unlock", "No fingerprint enrolled on this phone")
        }
        SettingRow("Encryption", "AES-256-GCM - PBKDF2 120,000 iterations")
        SettingRow("Auto-lock", "After 1 minute in the background")
        SettingRow("Privacy", "No network, no analytics, no cloud")
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}


private data class HomeTile(
    val label: String,
    val icon: ImageVector,
    val colour: Color,
    val onClick: () -> Unit
)

@Composable
private fun HomeTiles(
    onCamera: () -> Unit,
    onCategory: (String) -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onAddApp: () -> Unit
) {
    val tiles = listOf(
        HomeTile("Camera", Icons.Filled.PhotoCamera, Color(0xFFFF375F), onCamera),
        HomeTile("Photos", Icons.Filled.Image, Color(0xFF0A84FF)) { onCategory("Photos") },
        HomeTile("Videos", Icons.Filled.Videocam, Color(0xFF7C3AED)) { onCategory("Videos") },
        HomeTile("Music", Icons.Filled.MusicNote, Color(0xFFFF9F0A)) { onCategory("Audio") },
        HomeTile("My files", Icons.Filled.Folder, Color(0xFF32ADE6), onFiles),
        HomeTile("Settings", Icons.Filled.Settings, Color(0xFF8E8E93), onSettings),
        HomeTile("Add app", Icons.Filled.Android, Color(0xFF30D158), onAddApp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { tile.onClick() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = tile.icon,
                                contentDescription = null,
                                tint = tile.colour,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(tile.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}


/** The glyph a file gets in the list, picked from what the file is. */
private fun iconFor(name: String): ImageVector = when (categoryOf(name)) {
    "Photos" -> Icons.Filled.Image
    "Videos" -> Icons.Filled.Videocam
    "Audio" -> Icons.Filled.MusicNote
    "APKs" -> Icons.Filled.Android
    "Archives" -> Icons.Filled.FolderZip
    else -> Icons.Filled.Description
}

/** Its colour, so a long list reads at a glance. */
private fun colourFor(name: String): Color = when (categoryOf(name)) {
    "Photos" -> Color(0xFF0A84FF)
    "Videos" -> Color(0xFF7C3AED)
    "Audio" -> Color(0xFFFF375F)
    "APKs" -> Color(0xFF30D158)
    "Archives" -> Color(0xFFAC8E68)
    else -> Color(0xFF32ADE6)
}


/** A small preview of an encrypted photo or video, made from a temporary decrypted copy. */
private fun makeThumb(context: Context, key: SecretKey, item: VaultItem): Bitmap? {
    val file = decryptToCache(context, key, item)
    return try {
        if (categoryOf(item.name) == "Videos") {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }
    } finally {
        runCatching { file.delete() }
    }
}
