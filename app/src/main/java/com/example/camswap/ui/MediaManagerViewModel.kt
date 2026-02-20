package com.example.camswap.ui

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import com.example.camswap.ConfigManager
import com.example.camswap.utils.ImageToVideoConverter


enum class MediaType {
    VIDEO, IMAGE, AUDIO
}

data class MediaItem(
    val file: File,
    val name: String,
    val displayName: String,
    val isVirtual: Boolean,
    val size: Long,
    val duration: Long = 0,
    val type: MediaType
)

data class MediaManagerUiState(
    val videos: List<MediaItem> = emptyList(),
    val images: List<MediaItem> = emptyList(),
    val audios: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val totalVideoSizeMb: Double = 0.0,
    val totalImageSizeMb: Double = 0.0,
    val totalAudioSizeMb: Double = 0.0,
    val totalVideoDurationStr: String = "00:00",
    val selectedVideoName: String? = null,
    val selectedImageName: String? = null,
    val selectedAudioName: String? = null
)

class MediaManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MediaManagerUiState())
    val uiState: StateFlow<MediaManagerUiState> = _uiState.asStateFlow()

    private val mediaDir = File(android.os.Environment.getExternalStorageDirectory().absolutePath + "/DCIM/Camera1/")
    private val configManager = ConfigManager()

    init {
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        loadMedia()
    }

    fun loadMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            
            // Load Videos
            val videoFiles = mediaDir.listFiles { _, name ->
                @Suppress("DEPRECATION") val lowerName = name.toLowerCase(Locale.getDefault())
                lowerName.endsWith(".mp4") || lowerName.endsWith(".mov") || lowerName.endsWith(".avi") || lowerName.endsWith(".mkv")
            }?.toList() ?: emptyList()

            val videoItems = videoFiles.map { file ->
                // Ensure world-readable so hook process (inside target app) can read via direct path
                try { file.setReadable(true, false) } catch (_: Exception) {}
                var duration = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = time?.toLong() ?: 0L
                    retriever.release()
                } catch (e: Exception) {
                    // Ignore
                }
                
                MediaItem(file, file.name, file.name, false, file.length(), duration, MediaType.VIDEO)
            }

            // Load Images
            val imageFiles = mediaDir.listFiles { _, name ->
                @Suppress("DEPRECATION") val lowerName = name.toLowerCase(Locale.getDefault())
                lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".bmp")
            }?.toList() ?: emptyList()

            val imageItems = imageFiles.map { file ->
                MediaItem(file, file.name, file.name, false, file.length(), 0, MediaType.IMAGE)
            }

            // Load Audios
            val audioFiles = mediaDir.listFiles { _, name ->
                @Suppress("DEPRECATION") val lowerName = name.toLowerCase(Locale.getDefault())
                lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".aac")
                    || lowerName.endsWith(".m4a") || lowerName.endsWith(".ogg") || lowerName.endsWith(".flac")
            }?.toList() ?: emptyList()

            val audioItems = audioFiles.map { file ->
                var duration = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = time?.toLong() ?: 0L
                    retriever.release()
                } catch (e: Exception) {
                    // Ignore
                }
                MediaItem(file, file.name, file.name, false, file.length(), duration, MediaType.AUDIO)
            }

            // Calculate stats
            val totalVideoSize = videoItems.map { it.size }.sum()
            val totalImageSize = imageItems.map { it.size }.sum()
            val totalAudioSize = audioItems.map { it.size }.sum()
            val totalDurationMs = videoItems.map { it.duration }.sum()
            
            val durationSeconds = totalDurationMs / 1000
            val h = durationSeconds / 3600
            val m = (durationSeconds % 3600) / 60
            val s = durationSeconds % 60
            val durationStr = if (h > 0) {
                 String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
            } else {
                 String.format(Locale.getDefault(), "%02d:%02d", m, s)
            }

            configManager.reload()
            val selectedVideo = configManager.getString(ConfigManager.KEY_SELECTED_VIDEO, null)
            val selectedImage = configManager.getString(ConfigManager.KEY_SELECTED_IMAGE, null)
            val selectedAudio = configManager.getString(ConfigManager.KEY_SELECTED_AUDIO, null)

            _uiState.update {
                it.copy(
                    videos = videoItems,
                    images = imageItems,
                    audios = audioItems,
                    isLoading = false,
                    totalVideoSizeMb = totalVideoSize / (1024.0 * 1024.0),
                    totalImageSizeMb = totalImageSize / (1024.0 * 1024.0),
                    totalAudioSizeMb = totalAudioSize / (1024.0 * 1024.0),
                    totalVideoDurationStr = durationStr,
                    selectedVideoName = selectedVideo,
                    selectedImageName = selectedImage,
                    selectedAudioName = selectedAudio
                )
            }
        }
    }

    fun selectMedia(item: MediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            when (item.type) {
                MediaType.VIDEO -> {
                    configManager.reload()
                    val currentSelected = configManager.getString(ConfigManager.KEY_SELECTED_VIDEO, null)

                    if (currentSelected == item.name) {
                        configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, "")
                        _uiState.update { it.copy(selectedVideoName = null) }
                    } else {
                        configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, item.name)
                        _uiState.update { it.copy(selectedVideoName = item.name) }
                    }
                    try {
                        getApplication<Application>().contentResolver.notifyChange(
                            Uri.parse("content://com.example.camswap.provider/config"), null)
                    } catch (_: Exception) {}
                    loadMedia()
                }
                MediaType.IMAGE -> {
                    configManager.setString(ConfigManager.KEY_SELECTED_IMAGE, item.name)
                    _uiState.update { it.copy(selectedImageName = item.name) }
                }
                MediaType.AUDIO -> {
                    configManager.reload()
                    val currentSelected = configManager.getString(ConfigManager.KEY_SELECTED_AUDIO, null)

                    if (currentSelected == item.name) {
                        configManager.setString(ConfigManager.KEY_SELECTED_AUDIO, "")
                        _uiState.update { it.copy(selectedAudioName = null) }
                    } else {
                        configManager.setString(ConfigManager.KEY_SELECTED_AUDIO, item.name)
                        _uiState.update { it.copy(selectedAudioName = item.name) }
                    }
                    loadMedia()
                }
            }
        }
    }

    fun addMedia(uris: List<Uri>, type: MediaType) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            val context = getApplication<Application>()
            
            for (uri in uris) {
                try {
                    val originalName = getFileName(context, uri)
                    val mimeType = context.contentResolver.getType(uri)
                    android.util.Log.d("CamSwap", "addMedia: type=$type, uri=$uri, mimeType=$mimeType, name=$originalName")
                    
                    var effectiveType = type
                    // If adding to video list, check if it's actually an image that needs conversion
                    if (type == MediaType.VIDEO) {
                        val isImage = (mimeType?.startsWith("image/") == true) || 
                                      (originalName?.lowercase(Locale.getDefault())?.endsWith(".jpg") == true) || 
                                      (originalName?.lowercase(Locale.getDefault())?.endsWith(".jpeg") == true) || 
                                      (originalName?.lowercase(Locale.getDefault())?.endsWith(".png") == true) ||
                                      (originalName?.lowercase(Locale.getDefault())?.endsWith(".bmp") == true) ||
                                      (originalName?.lowercase(Locale.getDefault())?.endsWith(".webp") == true)
                        if (isImage) {
                            effectiveType = MediaType.IMAGE
                        }
                    }

                    if (effectiveType == MediaType.IMAGE) {
                        android.util.Log.d("CamSwap", "开始图片转视频流程...")
                        
                        // 1. Save to temp file using the original name to produce a clean output filename
                        val extension = originalName?.substringAfterLast('.', "jpg") ?: "jpg"
                        val safeBaseName = (originalName?.substringBeforeLast('.') ?: "img")
                            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                            .take(30)
                        val tempFile = File(context.cacheDir, "${safeBaseName}.$extension")
                        
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        android.util.Log.d("CamSwap", "临时文件已保存: ${tempFile.absolutePath}, 大小: ${tempFile.length()}")
                        
                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            android.util.Log.e("CamSwap", "临时文件为空或不存在!")
                            continue
                        }
                        
                        // 2. Convert to video
                        val convertedFile = ImageToVideoConverter.convert(tempFile.absolutePath, mediaDir)
                        
                        // 3. Delete temp file
                        if (tempFile.exists()) tempFile.delete()
                        
                        if (convertedFile != null && convertedFile.exists() && convertedFile.length() > 0) {
                            android.util.Log.d("CamSwap", "转换成功: ${convertedFile.name}, 大小: ${convertedFile.length()}")
                            // Auto-select the converted video
                            configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, convertedFile.name)
                            _uiState.update { it.copy(selectedVideoName = convertedFile.name) }
                            try {
                                context.contentResolver.notifyChange(
                                    Uri.parse("content://com.example.camswap.provider/config"), null)
                            } catch (_: Exception) {}
                        } else {
                            android.util.Log.e("CamSwap", "图片转视频失败! convertedFile=$convertedFile")
                        }
                        
                    } else {
                        // Handle Video / Audio
                        val extension = originalName?.substringAfterLast('.', "") ?: if (type == MediaType.VIDEO) "mp4" else "mp3"
                        val fileName = if (originalName != null) originalName else "media_${System.currentTimeMillis()}.$extension"
                        
                        val destFile = File(mediaDir, fileName)
                        
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        // Set world-readable so hook process can read via direct path
                        try {
                            destFile.setReadable(true, false)
                            Runtime.getRuntime().exec(arrayOf("chmod", "644", destFile.absolutePath))
                        } catch (_: Exception) {}
                        android.util.Log.d("CamSwap", "文件已保存: ${destFile.absolutePath}, 大小: ${destFile.length()}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CamSwap", "addMedia 异常: ${e.message}", e)
                    e.printStackTrace()
                }
            }
            loadMedia()
        }
    }

    fun deleteMedia(item: MediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (item.file.exists()) {
                item.file.delete()
            }
            loadMedia()
        }
    }

    fun clearAll(type: MediaType) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = if (type == MediaType.VIDEO) uiState.value.videos else uiState.value.images
            items.forEach { 
                if (it.file.exists()) it.file.delete() 
            }
            loadMedia()
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                         result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
