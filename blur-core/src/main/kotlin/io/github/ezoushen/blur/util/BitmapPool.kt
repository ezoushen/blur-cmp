package io.github.ezoushen.blur.util

import android.graphics.Bitmap
import androidx.annotation.IntRange
import java.util.LinkedList

/**
 * A simple bitmap pool for reusing bitmap allocations.
 *
 * This reduces GC pressure by recycling bitmaps instead of creating new ones
 * for each blur operation. Bitmaps are matched by dimensions and config.
 *
 * Thread-safety: All public methods are synchronized.
 *
 * @param maxPoolSize Maximum number of bitmaps to keep in the pool
 */
class BitmapPool(
    @IntRange(from = 1, to = 16)
    private val maxPoolSize: Int = 4
) {
    private val pool = LinkedList<Bitmap>()
    private val lock = Any()

    /**
     * Acquires a bitmap from the pool or creates a new one if none available.
     *
     * @param width Required bitmap width
     * @param height Required bitmap height
     * @param config Bitmap config (default ARGB_8888)
     * @return A bitmap ready for use (may contain previous data, call eraseColor if needed)
     */
    fun acquire(
        width: Int,
        height: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap {
        require(width > 0 && height > 0) { "Dimensions must be positive" }

        synchronized(lock) {
            // Try to find a matching bitmap in the pool
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val bitmap = iterator.next()
                if (bitmap.width == width &&
                    bitmap.height == height &&
                    bitmap.config == config &&
                    !bitmap.isRecycled
                ) {
                    iterator.remove()
                    return bitmap
                }
            }
        }

        // No matching bitmap found, create a new one
        return Bitmap.createBitmap(width, height, config)
    }

    /**
     * Returns a bitmap to the pool for future reuse.
     *
     * The bitmap should not be used after calling this method.
     *
     * @param bitmap The bitmap to return to the pool
     */
    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return

        synchronized(lock) {
            // Don't add if pool is full
            if (pool.size >= maxPoolSize) {
                // Remove oldest bitmap to make room
                pool.pollFirst()?.recycle()
            }
            pool.addLast(bitmap)
        }
    }

    /**
     * Clears all bitmaps from the pool and recycles them.
     *
     * Call this when the blur view is detached or destroyed.
     */
    fun clear() {
        synchronized(lock) {
            for (bitmap in pool) {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            pool.clear()
        }
    }

    /**
     * Returns the current number of bitmaps in the pool.
     */
    val size: Int
        get() = synchronized(lock) { pool.size }

    /**
     * Trims the pool to the specified size, recycling excess bitmaps.
     *
     * @param targetSize The desired pool size
     */
    fun trimToSize(targetSize: Int) {
        synchronized(lock) {
            while (pool.size > targetSize) {
                pool.pollFirst()?.recycle()
            }
        }
    }
}
