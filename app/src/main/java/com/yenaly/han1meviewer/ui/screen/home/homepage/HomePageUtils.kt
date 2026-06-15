package com.yenaly.han1meviewer.ui.screen.home.homepage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.yenaly.han1meviewer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException

/**
 * 将首页分类转换为高级搜索请求参数。
 *
 * 仅写入分类中存在的参数，避免向搜索页传递空值。
 *
 * @receiver 首页分类数据
 * @return 可直接用于高级搜索的参数映射
 */
internal fun HomeCategory.toAdvancedSearchParams(): Map<String, String> = buildMap {
    genre?.let { put("genre", it) }
    sort?.let { put("sort", it) }
    tags?.let { put("tags", it) }
}

/**
 * 将首页加载异常映射为对应的错误提示字符串资源。
 *
 * 优先根据异常类型判断常见网络问题，必要时回退到异常信息中的关键字匹配。
 *
 * @receiver 首页加载过程中抛出的异常
 * @return 错误提示的字符串资源 ID
 */
internal fun Throwable.toHomePageErrorMessageRes(): Int {
    val rawMessage = message.orEmpty().lowercase()
    return when {
        this is UnknownHostException ||
                rawMessage.contains("unable to resolve host") ||
                rawMessage.contains("no address associated with hostname") -> {
            R.string.home_error_dns
        }

        this is SocketTimeoutException || rawMessage.contains("timeout") -> {
            R.string.home_error_timeout
        }

        this is SSLHandshakeException ||
                rawMessage.contains("ssl") ||
                rawMessage.contains("certificate") -> {
            R.string.home_error_ssl
        }

        this is ConnectException || rawMessage.contains("failed to connect") -> {
            R.string.home_error_connect
        }

        this is SocketException && rawMessage.contains("connection reset") -> {
            R.string.home_error_connection_interrupted
        }

        rawMessage.contains("connection reset") -> {
            R.string.home_error_connection_reset
        }

        rawMessage.contains("403") -> {
            R.string.home_error_forbidden
        }

        rawMessage.contains("404") -> {
            R.string.home_error_not_found
        }

        rawMessage.contains("500") || rawMessage.contains("502") ||
                rawMessage.contains("503") || rawMessage.contains("504") -> {
            R.string.home_error_server_unavailable
        }

        else -> {
            R.string.home_error_generic
        }
    }
}

/**
 * 下载远程图片并保存到系统相册。
 *
 * Android 10 及以上通过 [MediaStore] 写入公共图片目录，低版本直接写入 Pictures 目录。
 * 保存成功后会在主线程显示完成提示。
 *
 * @param context 用于加载图片、访问 ContentResolver 和显示 Toast 的上下文
 * @param imageUrl 需要保存的图片地址
 */
internal suspend fun saveImageToGallery(context: Context, imageUrl: String) {
    val loader = SingletonImageLoader.get(context)
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .build()
    val result = (loader.execute(request) as? SuccessResult)?.image
    val bitmap = result?.toBitmap() ?: return
    val filename = "IMG_${System.currentTimeMillis()}.jpg"
    val fos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }
        val uri =
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { context.contentResolver.openOutputStream(it) }
    } else {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            filename
        )
        withContext(Dispatchers.IO) {
            FileOutputStream(file)
        }
    }
    fos?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
    withContext(Dispatchers.Main) {
        Toast.makeText(context, context.getString(R.string.saved), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 将公告秒级时间戳格式化为本地时间字符串。
 *
 * @param timestamp 秒级 Unix 时间戳。
 * @return 本地日期时间字符串。
 */
fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.ofEpochSecond(timestamp)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}
