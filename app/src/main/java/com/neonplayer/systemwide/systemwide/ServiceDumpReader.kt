package com.neonplayer.systemwide.systemwide

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.charset.Charset

/**
 * DUMP-permission-backed binder dump reader. Same approach as reference:
 * ask the system service to dump into a pipe, then parse text in-process.
 */
class ServiceDumpReader(context: Context) {
    private val appContext = context.applicationContext

    fun read(serviceName: String): String? {
        if (!SystemWidePermissions.hasDump(appContext)) return null
        var pipe: Array<ParcelFileDescriptor>? = null
        var input: FileInputStream? = null
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, serviceName) as? IBinder ?: return null
            pipe = ParcelFileDescriptor.createPipe()
            binder.dumpAsync(pipe[1].fileDescriptor, emptyArray())
            pipe[1].close()
            pipe[1] = null

            input = FileInputStream(pipe[0].fileDescriptor)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (output.size() + read > MAX_DUMP_BYTES) {
                    output.write(buffer, 0, (MAX_DUMP_BYTES - output.size()).coerceAtLeast(0))
                    break
                }
                output.write(buffer, 0, read)
            }
            String(output.toByteArray(), Charset.forName("UTF-8"))
        } catch (_: Throwable) {
            null
        } finally {
            try { input?.close() } catch (_: Exception) {}
            if (pipe != null) {
                try { pipe[0].close() } catch (_: Exception) {}
                try { pipe[1]?.close() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val MAX_DUMP_BYTES = 4 * 1024 * 1024
    }
}
