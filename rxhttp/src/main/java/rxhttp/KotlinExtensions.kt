package rxhttp

import io.reactivex.Observable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import rxhttp.wrapper.callback.ProgressCallback
import rxhttp.wrapper.entity.Progress
import rxhttp.wrapper.parse.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * User: ljx
 * Date: 2020-02-07
 * Time: 21:04
 */

suspend fun <T : Any> Observable<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        val subscribe = subscribe({
            continuation.resume(it)
        }, {
            continuation.resumeWithException(it)
        })

        continuation.invokeOnCancellation {
            subscribe.dispose()
        }
    }
}

suspend fun <T : Any> Call.await(parser: Parser<T>): T {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()  //当前线程同关闭协程时的线程 如：A线程关闭协程，这当前就在A线程调用
        }
        enqueue(object : Callback {

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(parser.onParse(response))
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }
}

suspend fun IRxHttp.awaitBoolean() = await<Boolean>()

suspend fun IRxHttp.awaitByte() = await<Byte>()

suspend fun IRxHttp.awaitShort() = await<Short>()

suspend fun IRxHttp.awaitInt() = await<Int>()

suspend fun IRxHttp.awaitLong() = await<Long>()

suspend fun IRxHttp.awaitFloat() = await<Float>()

suspend fun IRxHttp.awaitDouble() = await<Double>()

suspend fun IRxHttp.awaitString() = await<String>()

suspend inline fun <reified T : Any> IRxHttp.await() = await(object : SimpleParser<T>() {})

suspend inline fun <reified T : Any> IRxHttp.awaitList() = await(object : ListParser<T>() {})

suspend inline fun <reified K : Any, reified V : Any> IRxHttp.awaitMap() = await(object : MapParser<K, V>() {})

suspend fun IRxHttp.awaitHeaders() = awaitOkResponse().headers()

suspend fun IRxHttp.awaitOkResponse() = await(OkResponseParser())

/**
 * 除过awaitDownload方法，所有的awaitXxx方法,最终都会调用本方法
 */
suspend fun <T : Any> IRxHttp.await(parser: Parser<T>) = newCall().await(parser)

@JvmOverloads
suspend fun IRxHttp.awaitDownload(
    destPath: String,
    coroutine: CoroutineScope? = null,
    offsetSize: Long = 0L,
    progress: (Progress<String>) -> Unit
): String {
    val clone = HttpSender.clone(ProgressCallbackImpl(coroutine, offsetSize, progress))
    return newCall(clone).await(DownloadParser(destPath))
}

private class ProgressCallbackImpl(
    private val coroutine: CoroutineScope? = null,  //协程，用于对进度回调切换线程
    private val offsetSize: Long,
    private val progress: (Progress<String>) -> Unit
) : ProgressCallback {

    private var lastProgress = 0   //上次下载进度

    override fun onProgress(progress: Int, currentSize: Long, totalSize: Long) {
        //这里最多回调100次,仅在进度有更新时,才会回调
        val p = Progress<String>(progress, currentSize, totalSize)
        if (offsetSize > 0) {
            p.addCurrentSize(offsetSize)
            p.addTotalSize(offsetSize)
            p.updateProgress()
            val currentProgress: Int = p.progress
            if (currentProgress <= lastProgress) return
            lastProgress = currentProgress
        }
        coroutine?.launch { progress(p) } ?: progress(p)
    }
}
