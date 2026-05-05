package io.github.ezoushen.blur.algorithm

import android.os.Build
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * On-disk cache of compiled GLSL program binaries (`glGetProgramBinary` /
 * `glProgramBinary`, exposed by the `GL_OES_get_program_binary` extension
 * and the ES3 core API).
 *
 * Purpose: skip the per-launch `glCompileShader` + `glLinkProgram` cost on
 * devices whose driver does not advertise the implicit
 * `EGL_ANDROID_blob_cache` extension (Pixel 4a / Adreno 618 is one such
 * device). Compilation only runs on the very first process launch after
 * install or driver upgrade; subsequent launches load the cached binary
 * via `glProgramBinary` (~1–3 ms vs ~30–40 ms cold).
 *
 * Storage layout: `<cacheDir>/blur-cmp/<key>.bin` where `<key>` is a hex
 * SHA-256 digest of (Build.FINGERPRINT, GL_VENDOR, GL_RENDERER, GL_VERSION,
 * cache schema version, vertex source, fragment source). Any change in any
 * input invalidates the cache key — security-patch driver swaps,
 * shader-source edits, schema bumps all generate fresh keys.
 *
 * File format:
 * ```
 * offset  size  contents
 * 0       8     magic "BLURCMP1"
 * 8       2     schema version (big-endian uint16)
 * 10      4     GL program binary format (big-endian int32)
 * 14      4     binary length N (big-endian int32)
 * 18      N     binary blob bytes
 * ```
 *
 * Atomic writes via [AtomicFile] (writes to backup file, fsyncs, renames
 * — survives mid-write process death). Reads validate the magic + schema
 * before trusting the format/length fields.
 */
internal class OpenGLBlurProgramBinaryCache(rootCacheDir: File) {

    /** Result of a successful binary load. */
    data class Entry(val format: Int, val bytes: ByteArray) {
        // Equality not used; stub so the data class compiles cleanly.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = format xor bytes.contentHashCode()
    }

    private val dir: File = File(rootCacheDir, DIR_NAME).apply {
        // Best-effort. mkdir failure is fine — write() will catch it and
        // surface the IOException to the fail-open caller.
        if (!exists()) mkdirs()
    }

    /**
     * Look up a cached binary by [key]. Returns null on miss / corruption /
     * I/O error so the caller falls back to a fresh compile.
     */
    fun read(key: String): Entry? {
        val file = AtomicFile(File(dir, "$key$FILE_EXT"))
        return try {
            val raw = file.readFully()
            decode(raw)
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "read failed for $key", e)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "read corrupt entry $key", t)
            // Best-effort cleanup of the corrupt file so future writes
            // can replace it.
            try { file.delete() } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Write a freshly-linked program binary to disk. [bytes] is the blob
     * returned by `glGetProgramBinary`; [format] is the corresponding
     * GLenum format ID. Failures are logged and swallowed — caching is
     * always best-effort, runtime path is unaffected.
     */
    fun write(key: String, format: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val file = AtomicFile(File(dir, "$key$FILE_EXT"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(encode(format, bytes))
            file.finishWrite(stream)
        } catch (e: IOException) {
            Log.w(TAG, "write failed for $key", e)
            if (stream != null) {
                try { file.failWrite(stream) } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write threw for $key", t)
            if (stream != null) {
                try { file.failWrite(stream) } catch (_: Throwable) {}
            }
        }
    }

    private fun encode(format: Int, bytes: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(HEADER_SIZE + bytes.size).order(ByteOrder.BIG_ENDIAN)
        out.put(MAGIC)
        out.putShort(SCHEMA_VERSION.toShort())
        out.putInt(format)
        out.putInt(bytes.size)
        out.put(bytes)
        return out.array()
    }

    private fun decode(raw: ByteArray): Entry? {
        if (raw.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val schema = buf.short.toInt() and 0xFFFF
        if (schema != SCHEMA_VERSION) return null
        val format = buf.int
        val length = buf.int
        if (length <= 0) return null
        if (raw.size != HEADER_SIZE + length) return null
        val payload = ByteArray(length)
        buf.get(payload)
        return Entry(format = format, bytes = payload)
    }

    companion object {
        private const val TAG = "BlurBinaryCache"
        private const val DIR_NAME = "blur-cmp"
        private const val FILE_EXT = ".bin"
        private const val SCHEMA_VERSION = 1

        // "BLURCMP1" — bumped if file format changes incompatibly.
        private val MAGIC = byteArrayOf(
            'B'.code.toByte(),
            'L'.code.toByte(),
            'U'.code.toByte(),
            'R'.code.toByte(),
            'C'.code.toByte(),
            'M'.code.toByte(),
            'P'.code.toByte(),
            '1'.code.toByte(),
        )

        private const val HEADER_SIZE = 8 /* magic */ +
            2 /* schema */ +
            4 /* format */ +
            4 /* length */

        /**
         * Derive the cache key for a (driver, schema, sources) tuple.
         *
         * Inputs that must contribute to the key:
         * - Build.FINGERPRINT: catches OS / security-patch driver swaps
         *   that don't bump GL_VERSION.
         * - GL_VENDOR / GL_RENDERER / GL_VERSION: per-device driver
         *   identity. A binary produced by one driver is meaningless to
         *   another.
         * - SCHEMA_VERSION (this constant): bumps invalidate everything
         *   on next launch.
         * - vertex source + fragment source: the actual program identity.
         */
        fun deriveKey(
            glVendor: String,
            glRenderer: String,
            glVersion: String,
            vertexSource: String,
            fragmentSource: String,
        ): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(Build.FINGERPRINT.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(glVendor.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(glRenderer.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(glVersion.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(SCHEMA_VERSION.toString().toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(vertexSource.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(fragmentSource.toByteArray(Charsets.UTF_8))
            val digest = md.digest()
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private val HEX = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        )
    }
}
