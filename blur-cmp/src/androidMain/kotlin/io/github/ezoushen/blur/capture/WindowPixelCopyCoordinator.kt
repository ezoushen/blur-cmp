package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import java.util.ArrayDeque
import java.util.IdentityHashMap

internal fun interface WindowPixelCopier {
    fun request(
        window: Window,
        sourceRect: Rect,
        destination: Bitmap,
        onResult: (Int) -> Unit,
    )
}

internal fun interface WindowPixelCopyRequest {
    fun cancel()
}

internal class WindowPixelCopyPlane private constructor(
    val window: Window,
    val sourceRect: Rect,
    internal val sourceView: View? = null,
    @Suppress("UNUSED_PARAMETER") owned: Unit,
) {
    internal constructor(
        window: Window,
        sourceRect: Rect,
        sourceView: View? = null,
    ) : this(window, Rect(sourceRect), sourceView, Unit)

    internal constructor(
        window: Window,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        sourceView: View? = null,
    ) : this(window, Rect(left, top, right, bottom), sourceView, Unit)

    override fun equals(other: Any?): Boolean =
        other is WindowPixelCopyPlane &&
            window === other.window &&
            sourceRect == other.sourceRect &&
            sourceView === other.sourceView

    override fun hashCode(): Int =
        31 * (31 * System.identityHashCode(window) + sourceRect.hashCode()) +
            System.identityHashCode(sourceView)
}

internal fun interface WindowPrefixComposer {
    fun compose(
        destination: Bitmap,
        prefix: Bitmap?,
        additions: List<Bitmap>,
    )
}

internal interface WindowCapturedBitmap : AutoCloseable {
    val bitmap: Bitmap
}

internal class WindowPixelCopyLease internal constructor(
    override val bitmap: Bitmap,
    private var release: (() -> Unit)?,
) : WindowCapturedBitmap {
    override fun close() {
        release?.invoke()
        release = null
    }
}

internal class BackdropCapturePrefix(
    val parent: BackdropCapturePrefix?,
    val source: BackdropCaptureSource,
) {
    val size: Int = (parent?.size ?: 0) + 1
}

internal class BackdropCaptureViewport(
    screenRect: Rect,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    val screenRect = Rect(screenRect)
}

internal class WindowPrefixFrame internal constructor(
    private val lease: WindowPixelCopyLease,
) : WindowCapturedBitmap {
    override val bitmap: Bitmap
        get() = lease.bitmap

    override fun close() = lease.close()
}

internal class WindowPixelCopyCoordinator(
    private val pixelCopier: WindowPixelCopier = AndroidWindowPixelCopier,
    private val surfacePixelCopier: SurfacePixelCopier = AndroidSurfacePixelCopier,
    private val epochProvider: () -> Long = AndroidBlurFrameDispatcher::currentEpoch,
    private val bitmapFactory: (Int, Int) -> Bitmap = { width, height ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    },
    private val prefixComposer: WindowPrefixComposer = AndroidWindowPrefixComposer,
    private val preparedPrefixBatchPoster: ((Runnable) -> Unit)? = null,
) : AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<CopyKey, CopyEntry>()
    private val entriesByEpoch = LinkedHashMap<Long, MutableSet<CopyEntry>>()
    private val surfaceEntries = LinkedHashMap<SurfaceCopyKey, SurfaceCopyEntry>()
    private val surfaceEntriesByEpoch = LinkedHashMap<Long, MutableSet<SurfaceCopyEntry>>()
    private val prefixRoots = LinkedHashMap<PrefixRootKey, PrefixRoot>()
    private val prefixRootsByEpoch = LinkedHashMap<Long, MutableSet<PrefixRoot>>()
    private val pendingPreparedBatches = LinkedHashMap<PreparedBatchKey, PreparedBatch>()
    private val surfaceViewTrackers = IdentityHashMap<View, SharedSurfaceViewTracker>()
    private val deferredSurfaceViewTrackerReleases =
        IdentityHashMap<View, SharedSurfaceViewTracker>()
    private val deferredSurfaceViewTrackerReleaseRunner =
        Runnable(::drainDeferredSurfaceViewTrackerReleases)
    private val surfacePlaneRenderer = WindowSurfacePlaneRenderer()
    private val surfaceLayerCompositionComparator =
        Comparator<PrefixSurfaceLayer> { left, right ->
            left.compositionOrder.compareTo(right.compositionOrder)
        }
    private val surfaceTransparentRegion = Region()
    private val surfaceLocation = IntArray(2)
    private val surfaceCompositionOrderMethod by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { SurfaceView::class.java.getMethod("getCompositionOrder") }.getOrNull()
    }
    private val reusableBitmaps = LinkedHashMap<Long, ArrayDeque<Bitmap>>()
    private val reservedReusableBitmaps = LinkedHashMap<Long, ArrayDeque<Bitmap>>()
    private val poolRequirements = LinkedHashMap<Long, PoolRequirement>()
    private val leaseCounts = mutableMapOf<Long, Int>()
    private val leasedOutputCounts = mutableMapOf<Long, Int>()
    private val prefixAdditions = ArrayList<Bitmap>()
    private val noSurfaceLayers = PreparedSurfaceLayers(emptyList(), ready = true)
    private var latestEpoch = Long.MIN_VALUE
    private var prefixBitmapCount = 0
    private var extraBitmapCount = 0
    private var reusableBitmapLimit = 0
    private var reusableBitmapCount = 0
    private var reusableBitmapTrimPosted = false
    private var nextPreparedWaveId = 1L
    private var cachedPreparedTopology: PreparedTopology? = null
    private var deferredSurfaceViewTrackerReleasePosted = false
    private var closed = false

    fun beginEpoch(): Epoch {
        checkMainThread()
        check(!closed) { "WindowPixelCopyCoordinator is closed" }
        val epoch = epochProvider()
        val epochChanged = latestEpoch != epoch
        val previousEpoch = latestEpoch
        latestEpoch = epoch
        leaseCounts[epoch] = (leaseCounts[epoch] ?: 0) + 1
        if (epochChanged && previousEpoch != Long.MIN_VALUE) pruneEpoch(previousEpoch)
        return Epoch(this, epoch)
    }

    fun invalidatePreparedTopology() {
        checkMainThread()
        cachedPreparedTopology = null
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        cachedPreparedTopology = null
        pendingPreparedBatches.values.forEach { batch ->
            batch.requests.forEach { request ->
                request.active = false
                request.delegate = null
            }
        }
        pendingPreparedBatches.clear()
        mainHandler.removeCallbacks(deferredSurfaceViewTrackerReleaseRunner)
        deferredSurfaceViewTrackerReleasePosted = false
        deferredSurfaceViewTrackerReleases.clear()
        prefixRoots.values.forEach { root ->
            root.children.values.toList().forEach { entry ->
                closePrefixSubtree(entry)
            }
            closePrefixRootResources(root)
        }
        prefixRoots.clear()
        prefixRootsByEpoch.clear()
        surfaceViewTrackers.values.forEach { it.tracker.release() }
        surfaceViewTrackers.clear()
        prefixBitmapCount = 0
        entries.values.forEach { entry ->
            entry.subscribers.forEach { it.active = false }
            entry.subscribers.clear()
            if (entry.pending || entry.delivering) {
                entry.orphaned = true
            } else {
                entry.bitmap.recycle()
            }
        }
        entries.clear()
        entriesByEpoch.clear()
        surfaceEntries.values.forEach { entry ->
            entry.subscribers.forEach { it.active = false }
            entry.subscribers.clear()
            if (entry.pending || entry.delivering) {
                entry.orphaned = true
            } else {
                recycleSurfaceBitmap(entry)
            }
        }
        surfaceEntries.clear()
        surfaceEntriesByEpoch.clear()
        reusableBitmaps.values.forEach { bucket -> bucket.forEach(Bitmap::recycle) }
        reusableBitmaps.clear()
        reservedReusableBitmaps.values.forEach { bucket -> bucket.forEach(Bitmap::recycle) }
        reservedReusableBitmaps.clear()
        reusableBitmapCount = 0
        reusableBitmapTrimPosted = false
        poolRequirements.clear()
        leasedOutputCounts.clear()
        reusableBitmapLimit = 0
        leaseCounts.clear()
    }

    private fun request(
        epoch: Long,
        window: Window,
        sourceRect: Rect,
        width: Int,
        height: Int,
        onResult: (Int, Bitmap?) -> Unit,
    ): WindowPixelCopyRequest {
        checkMainThread()
        check(!closed) { "WindowPixelCopyCoordinator is closed" }
        require(width > 0 && height > 0) { "PixelCopy output must be non-empty" }

        val key = CopyKey(epoch, window, sourceRect, width, height)
        val existing = entries[key]
        if (existing != null) {
            val subscriber = Subscriber(onResult)
            existing.subscribers += subscriber
            if (!existing.pending) {
                mainHandler.post {
                    deliverCached(existing, subscriber)
                }
            }
            return WindowPixelCopyRequest { cancel(existing, subscriber) }
        }

        val bitmap = obtainBitmap(width, height)
        val subscriber = Subscriber(onResult)
        val entry = CopyEntry(key, bitmap).also {
            it.subscribers += subscriber
            entries[key] = it
            entriesByEpoch.getOrPut(epoch, ::linkedSetOf) += it
        }
        updateReusableBitmapLimit()
        try {
            pixelCopier.request(window, Rect(sourceRect), bitmap) { result ->
                complete(entry, result)
            }
        } catch (_: Exception) {
            mainHandler.post { complete(entry, PixelCopy.ERROR_UNKNOWN) }
        }
        return WindowPixelCopyRequest { cancel(entry, subscriber) }
    }

    private fun requestPrefix(
        epoch: Long,
        planes: List<WindowPixelCopyPlane>,
        width: Int,
        height: Int,
        onResult: (Int, WindowPixelCopyLease?) -> Unit,
    ): WindowPixelCopyRequest {
        checkMainThread()
        check(!closed) { "WindowPixelCopyCoordinator is closed" }
        require(planes.isNotEmpty()) { "Window prefix must contain at least one plane" }
        require(width > 0 && height > 0) { "PixelCopy output must be non-empty" }

        val rootKey = PrefixRootKey(epoch, width, height)
        val root = prefixRoot(rootKey)
        val newEntries = mutableListOf<PrefixEntry>()
        var parent: PrefixEntry? = null
        planes.forEach { requestedPlane ->
            val children = parent?.children ?: root.children
            val entry = children[requestedPlane] ?: PrefixEntry(
                root = root,
                parent = parent,
                plane = WindowPixelCopyPlane(
                    requestedPlane.window,
                    requestedPlane.sourceRect,
                    requestedPlane.sourceView,
                ),
            ).also { created ->
                children[created.plane] = created
                newEntries += created
            }
            parent = entry
        }
        return subscribeToPrefix(
            entry = requireNotNull(parent),
            newEntries = newEntries,
            onResult = onResult,
        )
    }

    private fun prefixRoot(key: PrefixRootKey): PrefixRoot =
        prefixRoots[key] ?: PrefixRoot(key).also { root ->
            prefixRoots[key] = root
            prefixRootsByEpoch.getOrPut(key.epoch, ::linkedSetOf) += root
        }

    private fun removePrefixRoot(root: PrefixRoot) {
        if (!prefixRoots.remove(root.key, root)) return
        prefixRootsByEpoch[root.key.epoch]?.let { roots ->
            roots.remove(root)
            if (roots.isEmpty()) prefixRootsByEpoch.remove(root.key.epoch)
        }
    }

    private fun requestPrefix(
        epoch: Long,
        prefix: BackdropCapturePrefix,
        viewport: BackdropCaptureViewport,
        onResult: (Int, WindowPrefixFrame?) -> Unit,
    ): WindowPixelCopyRequest {
        checkMainThread()
        check(!closed) { "WindowPixelCopyCoordinator is closed" }
        require(viewport.screenRect.width() > 0 && viewport.screenRect.height() > 0) {
            "Window prefix viewport must be non-empty"
        }
        require(viewport.outputWidth > 0 && viewport.outputHeight > 0) {
            "PixelCopy output must be non-empty"
        }

        val screenRect = viewport.screenRect
        val key = PreparedBatchKey(
            epoch = epoch,
            screenLeft = screenRect.left,
            screenTop = screenRect.top,
            screenRight = screenRect.right,
            screenBottom = screenRect.bottom,
        )
        var created = false
        val batch = pendingPreparedBatches[key] ?: PreparedBatch(key).also {
            pendingPreparedBatches[key] = it
            retainEpoch(epoch)
            created = true
        }
        val request = PreparedRequest(prefix, viewport, onResult)
        batch.requests += request
        if (created) postPreparedBatch(Runnable { flushPreparedBatch(key, batch) })
        return WindowPixelCopyRequest { cancelPreparedRequest(batch, request) }
    }

    private fun postPreparedBatch(block: Runnable) {
        val poster = preparedPrefixBatchPoster
        if (poster == null) mainHandler.post(block) else poster(block)
    }

    private fun cancelPreparedRequest(batch: PreparedBatch, request: PreparedRequest) {
        checkMainThread()
        if (!request.active) return
        request.active = false
        request.delegate?.cancel()
        request.delegate = null
        if (pendingPreparedBatches[batch.key] === batch && batch.requests.none { it.active }) {
            pendingPreparedBatches.remove(batch.key)
            releaseEpoch(batch.key.epoch)
            scheduleDeferredSurfaceViewTrackerRelease()
        }
    }

    private fun flushPreparedBatch(key: PreparedBatchKey, batch: PreparedBatch) {
        checkMainThread()
        if (pendingPreparedBatches.remove(key) !== batch || closed) return
        try {
            val requests = if (batch.requests.all(PreparedRequest::active)) {
                batch.requests
            } else {
                batch.requests.filter(PreparedRequest::active)
            }
            if (requests.isNotEmpty()) materializePreparedBatch(key, requests)
        } catch (_: Exception) {
            batch.requests.forEach { request ->
                if (request.active) {
                    request.delegate?.cancel()
                    request.delegate = null
                    request.active = false
                    request.onResult(PixelCopy.ERROR_UNKNOWN, null)
                }
            }
        } finally {
            releaseEpoch(key.epoch)
            scheduleDeferredSurfaceViewTrackerRelease()
        }
    }

    private fun materializePreparedBatch(
        key: PreparedBatchKey,
        requests: List<PreparedRequest>,
    ) {
        val topology = preparedTopology(requests)
        val nodes = topology.nodes
        val orderedNodes = topology.orderedNodes
        val rootNodes = topology.rootNodes
        orderedNodes.forEach { node ->
            node.requestedWidth = 0
            node.requestedHeight = 0
            node.entry = null
        }
        requests.forEach { request ->
            requireNotNull(nodes[request.prefix]).apply {
                requestedWidth = maxOf(requestedWidth, request.viewport.outputWidth)
                requestedHeight = maxOf(requestedHeight, request.viewport.outputHeight)
            }
        }
        rootNodes.forEach(::resolvePreparedDemand)

        try {
            materializePreparedBatch(key, requests, topology)
        } finally {
            orderedNodes.forEach { it.entry = null }
        }
    }

    private fun preparedTopology(requests: List<PreparedRequest>): PreparedTopology {
        cachedPreparedTopology?.takeIf { it.matches(requests) }?.let { return it }

        val nodes = IdentityHashMap<BackdropCapturePrefix, PreparedNode>()
        val orderedNodes = mutableListOf<PreparedNode>()
        val rootNodes = mutableListOf<PreparedNode>()
        val rootNodesBySource = LinkedHashMap<BackdropCaptureSource, PreparedNode>()
        requests.forEach { request ->
            val missing = mutableListOf<BackdropCapturePrefix>()
            var cursor: BackdropCapturePrefix? = request.prefix
            while (cursor != null && nodes[cursor] == null) {
                missing += cursor
                cursor = cursor.parent
            }
            var parent = cursor?.let(nodes::get)
            missing.asReversed().forEach { preparedPrefix ->
                val nodeParent = parent
                val siblings = nodeParent?.childrenBySource ?: rootNodesBySource
                val node = siblings[preparedPrefix.source] ?: PreparedNode(
                    prefix = preparedPrefix,
                    parent = nodeParent,
                ).also { created ->
                    siblings[preparedPrefix.source] = created
                    orderedNodes += created
                    if (nodeParent == null) rootNodes += created else nodeParent.children += created
                }
                nodes[preparedPrefix] = node
                node.prefixes[preparedPrefix] = true
                parent = node
            }
        }

        return PreparedTopology(
            prefixes = Array(requests.size) { requests[it].prefix },
            nodes = nodes,
            orderedNodes = orderedNodes,
            rootNodes = rootNodes,
        ).also { cachedPreparedTopology = it }
    }

    private fun materializePreparedBatch(
        key: PreparedBatchKey,
        requests: List<PreparedRequest>,
        topology: PreparedTopology,
    ) {
        val nodes = topology.nodes
        val orderedNodes = topology.orderedNodes

        val screenRect = Rect(key.screenLeft, key.screenTop, key.screenRight, key.screenBottom)
        val sourceScreenLocation = IntArray(2)
        val sourceWindowLocation = IntArray(2)
        val planes = IdentityHashMap<PreparedNode, WindowPixelCopyPlane>()
        orderedNodes.forEach { node ->
            val source = node.prefix.source
            val window = requireNotNull(source.window) {
                "Prepared window prefix sources must have a Window"
            }
            source.view.getLocationOnScreen(sourceScreenLocation)
            source.view.getLocationInWindow(sourceWindowLocation)
            val left = screenRect.left - sourceScreenLocation[0] + sourceWindowLocation[0]
            val top = screenRect.top - sourceScreenLocation[1] + sourceWindowLocation[1]
            planes[node] = WindowPixelCopyPlane(
                window = window,
                left = left,
                top = top,
                right = left + screenRect.width(),
                bottom = top + screenRect.height(),
                sourceView = source.view,
            )
        }

        val rootMatch = findCompatiblePreparedRoot(key, orderedNodes, planes)
        val rootKey = rootMatch?.root?.key ?: PrefixRootKey(
            epoch = key.epoch,
            width = 0,
            height = 0,
            screenLeft = key.screenLeft,
            screenTop = key.screenTop,
            screenRight = key.screenRight,
            screenBottom = key.screenBottom,
            preparedWaveId = nextPreparedWaveId++,
        )
        val preparedSurfaceLayers = IdentityHashMap<PreparedNode, PreparedSurfaceLayers>()
        val preparedSourceViews = IdentityHashMap<View, Boolean>()
        try {
            orderedNodes.forEach { node ->
                if (rootMatch?.entries?.get(node) != null) return@forEach
                val sourceView = node.prefix.source.view
                preparedSourceViews[sourceView] = true
                preparedSurfaceLayers[node] = prepareSurfaceLayers(
                    rootKey = rootKey,
                    sourceView = sourceView,
                    outputWidth = node.width,
                    outputHeight = node.height,
                )
            }
        } catch (exception: Exception) {
            preparedSourceViews.keys.forEach(::releaseUnusedSurfaceViewTracker)
            throw exception
        }

        val root = rootMatch?.root ?: prefixRoot(rootKey)
        val newEntries = mutableListOf<PrefixEntry>()
        orderedNodes.forEach { node ->
            val existing = rootMatch?.entries?.get(node)
            if (existing != null) {
                if (existing.bitmap == null) {
                    existing.width = maxOf(existing.width, node.width)
                    existing.height = maxOf(existing.height, node.height)
                }
                bindPreparedPrefixes(root, existing, node)
                node.entry = existing
                return@forEach
            }
            val source = node.prefix.source
            val parent = node.parent?.entry
            val surfaceLayers = requireNotNull(preparedSurfaceLayers[node])
            val entry = PrefixEntry(
                root = root,
                parent = parent,
                plane = requireNotNull(planes[node]),
                width = node.width,
                height = node.height,
                rawWidth = node.width,
                rawHeight = node.height,
                surfaceLayers = surfaceLayers.layers,
                surfacesReady = surfaceLayers.ready,
            )
            val children = parent?.children ?: root.children
            children[entry.plane] = entry
            retainSurfaceViewTracker(root, source.view)
            bindPreparedPrefixes(root, entry, node)
            node.entry = entry
            newEntries += entry
        }

        reservePreparedBatchBitmaps(key.epoch, requests, nodes, newEntries)
        try {
            requests.forEach { request ->
                val entry = requireNotNull(nodes[request.prefix]?.entry)
                val delegate = subscribeToPrefix(
                    entry = entry,
                    newEntries = emptyList(),
                    requestedWidth = request.viewport.outputWidth,
                    requestedHeight = request.viewport.outputHeight,
                    usePreparedOutputReservation = true,
                ) { result, lease ->
                    if (request.active) {
                        request.active = false
                        request.delegate = null
                        request.onResult(result, lease?.let(::WindowPrefixFrame))
                    } else {
                        lease?.close()
                    }
                }
                if (request.active) request.delegate = delegate else delegate.cancel()
            }
            newEntries.forEach { entry ->
                if (isPrefixAttached(entry)) startRawPrefixRequest(entry)
            }
        } finally {
            releasePreparedBatchBitmapReservations()
        }
    }

    private fun reservePreparedBatchBitmaps(
        epoch: Long,
        requests: List<PreparedRequest>,
        nodes: IdentityHashMap<BackdropCapturePrefix, PreparedNode>,
        newEntries: List<PrefixEntry>,
    ) {
        if (reusableBitmaps.isEmpty() ||
            !hasMixedPreparedBitmapSizes(requests, nodes, newEntries)
        ) return

        val requirements = LinkedHashMap<Long, Int>()
        fun requireBitmap(width: Int, height: Int) {
            val key = bitmapSizeKey(width, height)
            requirements[key] = (requirements[key] ?: 0) + 1
        }

        val activatedEntries = IdentityHashMap<PrefixEntry, Boolean>()
        requests.forEach { request ->
            val entry = requireNotNull(nodes[request.prefix]?.entry)
            if (entry.bitmap == null && activatedEntries.put(entry, true) == null) {
                requireBitmap(entry.width, entry.height)
            }
            if (entry.width != request.viewport.outputWidth ||
                entry.height != request.viewport.outputHeight
            ) {
                requireBitmap(request.viewport.outputWidth, request.viewport.outputHeight)
            }
        }

        val copyKeys = linkedSetOf<CopyKey>()
        val surfaceKeys = linkedSetOf<SurfaceCopyKey>()
        newEntries.forEach { entry ->
            if (!entry.surfacesReady || !isPrefixAttached(entry)) return@forEach
            val copyKey = CopyKey(
                epoch,
                entry.plane.window,
                entry.plane.sourceRect,
                entry.rawWidth,
                entry.rawHeight,
            )
            if (entries[copyKey] == null && copyKeys.add(copyKey)) {
                requireBitmap(entry.rawWidth, entry.rawHeight)
            }
            entry.surfaceLayers.forEach { layer ->
                val surfaceKey = SurfaceCopyKey(epoch, layer.surface, layer.width, layer.height)
                if (surfaceEntries[surfaceKey] == null && surfaceKeys.add(surfaceKey)) {
                    requireBitmap(layer.width, layer.height)
                }
            }
        }

        requirements.forEach { (key, required) ->
            val bucket = reusableBitmaps[key] ?: return@forEach
            val reserved = reservedReusableBitmaps.getOrPut(key, ::ArrayDeque)
            repeat(minOf(required, bucket.size)) {
                reserved.addLast(bucket.removeLast())
            }
            if (bucket.isEmpty()) reusableBitmaps.remove(key)
        }
    }

    private fun hasMixedPreparedBitmapSizes(
        requests: List<PreparedRequest>,
        nodes: IdentityHashMap<BackdropCapturePrefix, PreparedNode>,
        newEntries: List<PrefixEntry>,
    ): Boolean {
        var firstSize = 0L
        var hasSize = false
        for (index in requests.indices) {
            val request = requests[index]
            val entry = requireNotNull(nodes[request.prefix]?.entry)
            if (entry.bitmap == null) {
                val size = bitmapSizeKey(entry.width, entry.height)
                if (hasSize && size != firstSize) return true
                firstSize = size
                hasSize = true
            }
            if (entry.width != request.viewport.outputWidth ||
                entry.height != request.viewport.outputHeight
            ) {
                val outputSize = bitmapSizeKey(
                    request.viewport.outputWidth,
                    request.viewport.outputHeight,
                )
                if (hasSize && outputSize != firstSize) return true
                firstSize = outputSize
                hasSize = true
            }
        }
        for (entryIndex in newEntries.indices) {
            val entry = newEntries[entryIndex]
            if (!entry.surfacesReady || !isPrefixAttached(entry)) continue
            val rawSize = bitmapSizeKey(entry.rawWidth, entry.rawHeight)
            if (hasSize && rawSize != firstSize) return true
            firstSize = rawSize
            hasSize = true
            for (layerIndex in entry.surfaceLayers.indices) {
                val layer = entry.surfaceLayers[layerIndex]
                val surfaceSize = bitmapSizeKey(layer.width, layer.height)
                if (surfaceSize != firstSize) return true
            }
        }
        return false
    }

    private fun releasePreparedBatchBitmapReservations() {
        reservedReusableBitmaps.forEach { (key, reserved) ->
            reusableBitmaps.getOrPut(key, ::ArrayDeque).addAll(reserved)
        }
        reservedReusableBitmaps.clear()
    }

    private fun takeReservedPreparedOutput(width: Int, height: Int): Bitmap? {
        val key = bitmapSizeKey(width, height)
        val bucket = reservedReusableBitmaps[key] ?: return null
        val bitmap = bucket.removeLast()
        reusableBitmapCount--
        if (bucket.isEmpty()) reservedReusableBitmaps.remove(key)
        extraBitmapCount++
        updateReusableBitmapLimit()
        return bitmap
    }

    private fun resolvePreparedDemand(node: PreparedNode) {
        node.width = node.requestedWidth
        node.height = node.requestedHeight
        node.children.forEach { child ->
            resolvePreparedDemand(child)
            node.width = maxOf(node.width, child.width)
            node.height = maxOf(node.height, child.height)
        }
    }

    private fun findCompatiblePreparedRoot(
        key: PreparedBatchKey,
        nodes: List<PreparedNode>,
        planes: IdentityHashMap<PreparedNode, WindowPixelCopyPlane>,
    ): PreparedRootMatch? = prefixRootsByEpoch[key.epoch]?.firstNotNullOfOrNull { root ->
        val rootKey = root.key
        if (rootKey.preparedWaveId == null ||
            rootKey.screenLeft != key.screenLeft ||
            rootKey.screenTop != key.screenTop ||
            rootKey.screenRight != key.screenRight ||
            rootKey.screenBottom != key.screenBottom
        ) {
            return@firstNotNullOfOrNull null
        }
        matchPreparedRoot(root, nodes, planes)
    }

    private fun matchPreparedRoot(
        root: PrefixRoot,
        nodes: List<PreparedNode>,
        planes: IdentityHashMap<PreparedNode, WindowPixelCopyPlane>,
    ): PreparedRootMatch? {
        val entries = IdentityHashMap<PreparedNode, PrefixEntry>()
        nodes.forEach { node ->
            val parent = node.parent?.let(entries::get)
            val entryByIdentity = node.prefixes.keys.firstNotNullOfOrNull { prefix ->
                root.preparedEntries[prefix]
            }
            val siblings = when {
                node.parent == null -> root.children
                parent != null -> parent.children
                else -> null
            }
            val entry = entryByIdentity ?: siblings?.get(requireNotNull(planes[node]))
            if (entry == null) return@forEach
            if (entry.parent !== parent || entry.plane != planes[node] ||
                entry.rawWidth < node.width || entry.rawHeight < node.height
            ) {
                return null
            }
            entries[node] = entry
        }
        return PreparedRootMatch(root, entries)
    }

    private fun bindPreparedPrefixes(
        root: PrefixRoot,
        entry: PrefixEntry,
        node: PreparedNode,
    ) {
        node.prefixes.keys.forEach { prefix ->
            root.preparedEntries[prefix] = entry
            entry.preparedPrefixes[prefix] = true
        }
    }

    private fun prepareSurfaceLayers(
        rootKey: PrefixRootKey,
        sourceView: View,
        outputWidth: Int,
        outputHeight: Int,
    ): PreparedSurfaceLayers {
        val sharedTracker = surfaceViewTrackers.getOrPut(sourceView) {
            SharedSurfaceViewTracker(
                SurfaceViewPresenceTracker().also { it.setSource(sourceView) },
            )
        }
        val surfaceViews = sharedTracker.tracker.surfaceViews()
        if (surfaceViews.isEmpty()) return noSurfaceLayers

        val viewportWidth = requireNotNull(rootKey.screenRight) -
            requireNotNull(rootKey.screenLeft)
        val viewportHeight = requireNotNull(rootKey.screenBottom) -
            requireNotNull(rootKey.screenTop)
        val scaleX = outputWidth.toFloat() / viewportWidth
        val scaleY = outputHeight.toFloat() / viewportHeight
        var ready = true
        val layers = ArrayList<PrefixSurfaceLayer>(surfaceViews.size)
        surfaceViews.forEach { candidate ->
            val surfaceView = candidate as? SurfaceView ?: return@forEach
            val alpha = surfaceView.effectiveAlpha()
            val surface = surfaceView.holder.surface
            if (alpha == 0f) {
                return@forEach
            }
            if (surfaceView.width == 0 || surfaceView.height == 0 || !surface.isValid) {
                ready = false
                return@forEach
            }
            val aboveWindow = SurfaceCapture.isAboveWindow(
                surfaceView,
                surfaceTransparentRegion,
                surfaceLocation,
            )
            layers += PrefixSurfaceLayer(
                target = surfaceView,
                surface = surface,
                width = (surfaceView.width * scaleX).toInt().coerceAtLeast(1),
                height = (surfaceView.height * scaleY).toInt().coerceAtLeast(1),
                alpha = alpha,
                aboveWindow = aboveWindow,
                compositionOrder = surfaceView.compositionOrder(aboveWindow),
            )
        }
        layers.sortWith(surfaceLayerCompositionComparator)
        return PreparedSurfaceLayers(layers, ready)
    }

    private fun retainSurfaceViewTracker(root: PrefixRoot, sourceView: View) {
        val sharedTracker = requireNotNull(surfaceViewTrackers[sourceView])
        if (root.trackedSurfaceViews.put(sourceView, true) == null) {
            sharedTracker.rootCount++
        }
    }

    private fun releaseUnusedSurfaceViewTracker(sourceView: View) {
        val sharedTracker = surfaceViewTrackers[sourceView] ?: return
        if (sharedTracker.rootCount == 0) releaseSurfaceViewTracker(sourceView, sharedTracker)
    }

    private fun View.effectiveAlpha(): Float {
        var alphaProduct = 1f
        var current: View? = this
        while (current != null) {
            if (current.visibility != View.VISIBLE) return 0f
            alphaProduct *= current.alpha
            current = current.parent as? View
        }
        return alphaProduct
    }

    private fun SurfaceView.compositionOrder(aboveWindow: Boolean): Int {
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching { return surfaceCompositionOrderMethod?.invoke(this) as Int }
        }
        SurfaceCapture.registeredCompositionOrder(this)?.let { return it }
        return if (aboveWindow) 1 else -2
    }

    private fun subscribeToPrefix(
        entry: PrefixEntry,
        newEntries: List<PrefixEntry>,
        requestedWidth: Int = entry.width,
        requestedHeight: Int = entry.height,
        usePreparedOutputReservation: Boolean = false,
        onResult: (Int, WindowPixelCopyLease?) -> Unit,
    ): WindowPixelCopyRequest {
        val subscriber = PrefixSubscriber(requestedWidth, requestedHeight, onResult)
        entry.subscribers += subscriber
        if (entry.bitmap == null) {
            activatePrefix(entry)
        } else if (!entry.prefixPending) {
            mainHandler.post {
                deliverCachedPrefix(entry, subscriber)
            }
        }
        if (usePreparedOutputReservation &&
            (entry.width != requestedWidth || entry.height != requestedHeight)
        ) {
            subscriber.preparedOutput = takeReservedPreparedOutput(
                requestedWidth,
                requestedHeight,
            )
        }
        newEntries.forEach { created ->
            if (isPrefixAttached(created)) startRawPrefixRequest(created)
        }
        if (entry.rawChainReady) attemptPrefixComposition(entry)
        return WindowPixelCopyRequest { cancelPrefix(entry, subscriber) }
    }

    private fun activatePrefix(entry: PrefixEntry) {
        entry.bitmap = obtainBitmap(entry.width, entry.height)
        entry.bitmapCounted = true
        entry.root.bitmapCount++
        prefixBitmapCount++
        entry.prefixPending = true
        entry.result = PixelCopy.ERROR_UNKNOWN
        retainEpoch(entry.root.key.epoch)
        entry.holdsEpoch = true
        updateReusableBitmapLimit()
    }

    private fun startRawPrefixRequest(entry: PrefixEntry) {
        if (!entry.surfacesReady) {
            failPrefixSubtree(entry, PixelCopy.ERROR_SOURCE_INVALID)
            return
        }
        entry.rawPartsRemaining = 1 + entry.surfaceLayers.size
        val windowRequest = request(
            epoch = entry.root.key.epoch,
            window = entry.plane.window,
            sourceRect = entry.plane.sourceRect,
            width = entry.rawWidth,
            height = entry.rawHeight,
        ) { result, bitmap ->
            receiveRawWindowPrefix(entry, result, bitmap)
        }
        if (entry.rawPending && isPrefixAttached(entry)) {
            entry.rawRequest = windowRequest
        } else {
            windowRequest.cancel()
            return
        }
        entry.surfaceLayers.forEach { layer ->
            if (!entry.rawPending || !isPrefixAttached(entry)) return@forEach
            val surfaceRequest = requestSurface(entry.root.key.epoch, layer) { result, bitmap ->
                receiveRawSurfacePrefix(entry, layer, result, bitmap)
            }
            if (entry.rawPending && isPrefixAttached(entry)) {
                val surfaceRequests = entry.surfaceRequests ?: mutableListOf<WindowPixelCopyRequest>()
                    .also { entry.surfaceRequests = it }
                surfaceRequests += surfaceRequest
            } else {
                surfaceRequest.cancel()
            }
        }
    }

    private fun receiveRawWindowPrefix(
        entry: PrefixEntry,
        result: Int,
        bitmap: Bitmap?,
    ) {
        checkMainThread()
        if (!entry.rawPending || closed || !isPrefixAttached(entry)) return
        entry.rawRequest?.cancel()
        entry.rawRequest = null
        if (result != PixelCopy.SUCCESS || bitmap == null) {
            failPrefixSubtree(
                entry,
                result.takeIf { it != PixelCopy.SUCCESS } ?: PixelCopy.ERROR_UNKNOWN,
            )
            return
        }
        entry.windowBitmap = bitmap
        completeRawPrefixPart(entry)
    }

    private fun receiveRawSurfacePrefix(
        entry: PrefixEntry,
        layer: PrefixSurfaceLayer,
        result: Int,
        bitmap: Bitmap?,
    ) {
        checkMainThread()
        if (!entry.rawPending || closed || !isPrefixAttached(entry)) return
        if (result != PixelCopy.SUCCESS || bitmap == null) {
            failPrefixSubtree(
                entry,
                result.takeIf { it != PixelCopy.SUCCESS } ?: PixelCopy.ERROR_UNKNOWN,
            )
            return
        }
        layer.bitmap = bitmap
        completeRawPrefixPart(entry)
    }

    private fun completeRawPrefixPart(entry: PrefixEntry) {
        entry.rawPartsRemaining--
        if (entry.rawPartsRemaining != 0) return
        val windowBitmap = entry.windowBitmap ?: return
        if (entry.surfaceLayers.isEmpty()) {
            entry.rawBitmap = windowBitmap
        } else {
            val destination = obtainBitmap(entry.rawWidth, entry.rawHeight)
            entry.ownedRawBitmap = true
            extraBitmapCount++
            updateReusableBitmapLimit()
            val composed = runCatching {
                surfacePlaneRenderer.compose(
                    destination = destination,
                    window = windowBitmap,
                    layers = entry.surfaceLayers,
                    sourceView = requireNotNull(entry.plane.sourceView),
                    rootKey = entry.root.key,
                    outputWidth = entry.rawWidth,
                    outputHeight = entry.rawHeight,
                )
            }.isSuccess
            if (!composed) {
                entry.ownedRawBitmap = false
                extraBitmapCount--
                retireBitmap(destination)
                failPrefixSubtree(entry, PixelCopy.ERROR_UNKNOWN)
                return
            }
            entry.rawBitmap = destination
            entry.windowBitmap = null
            entry.surfaceLayers.forEach { it.bitmap = null }
        }
        entry.surfaceRequests?.forEach(WindowPixelCopyRequest::cancel)
        entry.surfaceRequests?.clear()
        entry.rawPending = false
        promoteRawPrefixChain(entry)
    }

    private fun requestSurface(
        epoch: Long,
        layer: PrefixSurfaceLayer,
        onResult: (Int, Bitmap?) -> Unit,
    ): WindowPixelCopyRequest {
        val key = SurfaceCopyKey(epoch, layer.surface, layer.width, layer.height)
        val existing = surfaceEntries[key]
        if (existing != null) {
            val subscriber = SurfaceSubscriber(onResult)
            existing.subscribers += subscriber
            if (!existing.pending) {
                mainHandler.post { deliverCachedSurface(existing, subscriber) }
            }
            return WindowPixelCopyRequest { cancelSurface(existing, subscriber) }
        }

        val bitmap = obtainBitmap(layer.width, layer.height)
        val subscriber = SurfaceSubscriber(onResult)
        val entry = SurfaceCopyEntry(key, bitmap).also {
            it.subscribers += subscriber
            surfaceEntries[key] = it
            surfaceEntriesByEpoch.getOrPut(epoch, ::linkedSetOf) += it
        }
        extraBitmapCount++
        updateReusableBitmapLimit()
        try {
            surfacePixelCopier.request(
                layer.surface,
                bitmap,
                { result -> completeSurface(entry, result) },
                mainHandler,
            )
        } catch (_: Exception) {
            mainHandler.post { completeSurface(entry, PixelCopy.ERROR_UNKNOWN) }
        }
        return WindowPixelCopyRequest { cancelSurface(entry, subscriber) }
    }

    private fun completeSurface(entry: SurfaceCopyEntry, result: Int) {
        checkMainThread()
        if (!entry.pending) return
        entry.pending = false
        entry.result = result
        if (entry.orphaned || closed) {
            recycleSurfaceBitmap(entry)
            return
        }
        val failed = result != PixelCopy.SUCCESS
        if (failed) removeSurfaceEntry(entry)
        entry.delivering = true
        val subscribers = entry.subscribers.toList()
        entry.subscribers.clear()
        subscribers.forEach { subscriber ->
            if (!closed && subscriber.active) {
                subscriber.active = false
                subscriber.onResult(
                    result,
                    entry.bitmap.takeIf { result == PixelCopy.SUCCESS },
                )
            }
            subscriber.active = false
        }
        entry.delivering = false
        if (entry.orphaned || closed) {
            recycleSurfaceBitmap(entry)
            return
        }
        if (failed) {
            retireSurfaceBitmap(entry)
        } else {
            maybeRetireSurfaceEntry(entry)
        }
    }

    private fun deliverCachedSurface(
        entry: SurfaceCopyEntry,
        subscriber: SurfaceSubscriber,
    ) {
        checkMainThread()
        if (!subscriber.active) return
        if (closed || entry.orphaned || surfaceEntries[entry.key] !== entry ||
            entry.result != PixelCopy.SUCCESS
        ) {
            subscriber.active = false
            return
        }
        entry.subscribers.remove(subscriber)
        subscriber.active = false
        entry.delivering = true
        subscriber.onResult(entry.result, entry.bitmap)
        entry.delivering = false
        if (entry.orphaned || closed) recycleSurfaceBitmap(entry)
        else maybeRetireSurfaceEntry(entry)
    }

    private fun cancelSurface(entry: SurfaceCopyEntry, subscriber: SurfaceSubscriber) {
        checkMainThread()
        if (!subscriber.active) return
        subscriber.active = false
        entry.subscribers.remove(subscriber)
        maybeRetireSurfaceEntry(entry)
    }

    private fun promoteRawPrefixChain(entry: PrefixEntry) {
        if (entry.rawChainReady || entry.rawPending || entry.rawBitmap == null ||
            entry.parent?.rawChainReady == false || !isPrefixAttached(entry)
        ) {
            return
        }
        entry.rawChainReady = true
        attemptPrefixComposition(entry)
        entry.children.values.toList().forEach(::promoteRawPrefixChain)
    }

    private fun attemptPrefixComposition(entry: PrefixEntry) {
        val destination = entry.bitmap ?: return
        if (!entry.prefixPending || !entry.rawChainReady || entry.composing ||
            entry.orphaned || closed || !isPrefixAttached(entry)
        ) {
            return
        }
        var base = entry.parent
        while (base != null && base.bitmap == null) base = base.parent
        if (base != null && (base.prefixPending || base.result != PixelCopy.SUCCESS)) {
            return
        }

        entry.composing = true
        val result = runCatching {
            composePrefix(
                destination = destination,
                prefix = base?.bitmap,
                entry = entry,
                base = base,
            )
            PixelCopy.SUCCESS
        }.getOrDefault(PixelCopy.ERROR_UNKNOWN)
        entry.composing = false
        if (!entry.prefixPending || entry.orphaned || closed || !isPrefixAttached(entry)) {
            recycleOrphanedPrefixBitmap(entry)
            return
        }
        if (result == PixelCopy.SUCCESS) {
            completePrefixSuccess(entry)
        } else {
            failPrefixSubtree(entry, result)
        }
    }

    private fun composePrefix(
        destination: Bitmap,
        prefix: Bitmap?,
        entry: PrefixEntry,
        base: PrefixEntry?,
    ) {
        prefixAdditions.clear()
        var cursor: PrefixEntry? = entry
        while (cursor !== base) {
            val current = requireNotNull(cursor)
            prefixAdditions += requireNotNull(current.rawBitmap)
            cursor = current.parent
        }
        prefixAdditions.reverse()
        try {
            prefixComposer.compose(destination, prefix, prefixAdditions)
        } finally {
            prefixAdditions.clear()
        }
    }

    private fun resizePrefix(destination: Bitmap, prefix: Bitmap) {
        prefixComposer.compose(destination, prefix, emptyList())
    }

    private fun completePrefixSuccess(entry: PrefixEntry) {
        checkMainThread()
        if (!entry.prefixPending) return
        entry.prefixPending = false
        entry.result = PixelCopy.SUCCESS
        deliverPrefixSubscribers(entry, PixelCopy.SUCCESS)
        releasePrefixEpoch(entry)
        recycleOrphanedPrefixBitmap(entry)
        attemptPendingPrefixDescendants(entry)
    }

    private fun attemptPendingPrefixDescendants(entry: PrefixEntry) {
        entry.children.values.toList().forEach { child ->
            if (child.bitmap == null) {
                attemptPendingPrefixDescendants(child)
            } else {
                attemptPrefixComposition(child)
            }
        }
    }

    private fun deliverCachedPrefix(entry: PrefixEntry, subscriber: PrefixSubscriber) {
        checkMainThread()
        if (!subscriber.active) {
            releasePreparedOutput(subscriber)
            return
        }
        if (closed || !isPrefixAttached(entry) || entry.result != PixelCopy.SUCCESS ||
            entry.bitmap == null
        ) {
            subscriber.active = false
            releasePreparedOutput(subscriber)
            return
        }
        entry.subscribers.remove(subscriber)
        subscriber.active = false
        entry.delivering = true
        val lease = createDeliveredPrefixLease(entry, subscriber)
        subscriber.onResult(
            if (lease == null) PixelCopy.ERROR_UNKNOWN else entry.result,
            lease,
        )
        entry.delivering = false
        recycleOrphanedPrefixBitmap(entry)
    }

    private fun cancelPrefix(entry: PrefixEntry, subscriber: PrefixSubscriber) {
        checkMainThread()
        if (!subscriber.active) return
        subscriber.active = false
        entry.subscribers.remove(subscriber)
        releasePreparedOutput(subscriber)
        if (entry.prefixPending && entry.subscribers.isEmpty()) {
            entry.prefixPending = false
            releasePrefixEpoch(entry)
            retirePrefixBitmap(entry)
            pruneUnusedPrefixPath(entry)
            attemptPendingPrefixDescendants(entry)
        }
    }

    private fun failPrefixSubtree(entry: PrefixEntry, result: Int) {
        if (!isPrefixAttached(entry)) return
        detachPrefixSubtree(entry)
        visitPrefixSubtree(entry) { failed ->
            if (failed.prefixPending) {
                failed.prefixPending = false
                releasePrefixEpoch(failed)
                retirePrefixBitmap(failed)
                deliverPrefixSubscribers(failed, result)
            } else {
                recycleOrphanedPrefixBitmap(failed)
            }
        }
    }

    private fun createPrefixLease(entry: PrefixEntry): WindowPixelCopyLease {
        val bitmap = requireNotNull(entry.bitmap)
        if (entry.leaseCount == 0) entry.root.leasedBitmapCount++
        entry.leaseCount++
        return WindowPixelCopyLease(bitmap) {
            releasePrefixLease(entry)
        }
    }

    private fun createDeliveredPrefixLease(
        entry: PrefixEntry,
        subscriber: PrefixSubscriber,
    ): WindowPixelCopyLease? {
        val source = requireNotNull(entry.bitmap)
        if (source.width == subscriber.width && source.height == subscriber.height) {
            releasePreparedOutput(subscriber)
            return createPrefixLease(entry)
        }
        val preparedOutput = subscriber.preparedOutput
        subscriber.preparedOutput = null
        val output = preparedOutput ?: obtainBitmap(subscriber.width, subscriber.height)
        if (preparedOutput == null) extraBitmapCount++
        val sizeKey = bitmapSizeKey(subscriber.width, subscriber.height)
        leasedOutputCounts[sizeKey] = (leasedOutputCounts[sizeKey] ?: 0) + 1
        updateReusableBitmapLimit()
        try {
            resizePrefix(output, source)
        } catch (_: Exception) {
            retireDeliveredOutput(output, sizeKey)
            return null
        }
        var active = true
        return WindowPixelCopyLease(output) {
            checkMainThread()
            if (!active) return@WindowPixelCopyLease
            active = false
            retireDeliveredOutput(output, sizeKey)
        }
    }

    private fun releasePreparedOutput(subscriber: PrefixSubscriber) {
        val output = subscriber.preparedOutput ?: return
        subscriber.preparedOutput = null
        extraBitmapCount--
        retireBitmap(output)
    }

    private fun retireDeliveredOutput(output: Bitmap, sizeKey: Long) {
        extraBitmapCount--
        if (!closed) {
            val count = requireNotNull(leasedOutputCounts[sizeKey])
            if (count == 1) {
                leasedOutputCounts.remove(sizeKey)
            } else {
                leasedOutputCounts[sizeKey] = count - 1
            }
        }
        retireBitmap(output)
    }

    private fun releasePrefixLease(entry: PrefixEntry) {
        checkMainThread()
        check(entry.leaseCount > 0) { "Window prefix lease released too many times" }
        entry.leaseCount--
        if (entry.leaseCount == 0) entry.root.leasedBitmapCount--
        recycleOrphanedPrefixBitmap(entry)
    }

    private fun deliverPrefixSubscribers(entry: PrefixEntry, result: Int) {
        entry.delivering = true
        val subscribers = entry.subscribers.toList()
        entry.subscribers.clear()
        try {
            subscribers.forEach { subscriber ->
                if (!closed && subscriber.active) {
                    subscriber.active = false
                    val lease = if (result == PixelCopy.SUCCESS) {
                        createDeliveredPrefixLease(entry, subscriber)
                    } else {
                        null
                    }
                    subscriber.onResult(
                        if (result == PixelCopy.SUCCESS && lease == null) {
                            PixelCopy.ERROR_UNKNOWN
                        } else {
                            result
                        },
                        lease,
                    )
                }
                subscriber.active = false
            }
        } finally {
            subscribers.forEach(::releasePreparedOutput)
            entry.delivering = false
        }
    }

    private fun detachPrefixSubtree(entry: PrefixEntry) {
        val siblings = entry.parent?.children ?: entry.root.children
        if (siblings[entry.plane] === entry) siblings.remove(entry.plane)
        visitPrefixSubtree(entry) { detached ->
            detached.orphaned = true
            forgetPreparedPrefixes(detached)
            cancelRawDependencies(detached)
            retireOwnedRawBitmap(detached)
            uncountPrefixBitmap(detached)
        }
        if (entry.root.children.isEmpty()) {
            removePrefixRoot(entry.root)
            closePrefixRootResources(entry.root)
        }
    }

    private fun pruneUnusedPrefixPath(start: PrefixEntry?) {
        var entry = start
        while (entry != null && entry.bitmap == null && entry.children.isEmpty() &&
            entry.subscribers.isEmpty() && entry.leaseCount == 0
        ) {
            val parent = entry.parent
            val siblings = parent?.children ?: entry.root.children
            if (siblings[entry.plane] === entry) siblings.remove(entry.plane)
            entry.orphaned = true
            forgetPreparedPrefixes(entry)
            cancelRawDependencies(entry)
            retireOwnedRawBitmap(entry)
            if (entry.root.children.isEmpty()) {
                removePrefixRoot(entry.root)
                closePrefixRootResources(entry.root)
            }
            entry = parent
        }
    }

    private fun forgetPreparedPrefixes(entry: PrefixEntry) {
        entry.preparedPrefixes.keys.forEach { prefix ->
            if (entry.root.preparedEntries[prefix] === entry) {
                entry.root.preparedEntries.remove(prefix)
            }
        }
        entry.preparedPrefixes.clear()
    }

    private fun cancelRawDependencies(entry: PrefixEntry) {
        entry.rawRequest?.cancel()
        entry.rawRequest = null
        entry.surfaceRequests?.forEach(WindowPixelCopyRequest::cancel)
        entry.surfaceRequests?.clear()
        entry.windowBitmap = null
        entry.surfaceLayers.forEach { it.bitmap = null }
        entry.rawPending = false
    }

    private fun retireOwnedRawBitmap(entry: PrefixEntry, recycle: Boolean = false) {
        if (!entry.ownedRawBitmap) {
            entry.rawBitmap = null
            return
        }
        val bitmap = entry.rawBitmap
        entry.rawBitmap = null
        entry.ownedRawBitmap = false
        extraBitmapCount--
        if (bitmap != null) {
            if (recycle || closed) bitmap.recycle() else retireBitmap(bitmap)
        }
    }

    private fun visitPrefixSubtree(entry: PrefixEntry, visit: (PrefixEntry) -> Unit) {
        visit(entry)
        entry.children.values.toList().forEach { child -> visitPrefixSubtree(child, visit) }
    }

    private fun isPrefixAttached(entry: PrefixEntry): Boolean {
        if (closed || entry.orphaned || prefixRoots[entry.root.key] !== entry.root) return false
        val siblings = entry.parent?.children ?: entry.root.children
        return siblings[entry.plane] === entry
    }

    private fun recycleOrphanedPrefixBitmap(entry: PrefixEntry) {
        if ((entry.orphaned || closed) && entry.leaseCount == 0 &&
            !entry.prefixPending && !entry.composing && !entry.delivering
        ) {
            if (closed) recyclePrefixBitmap(entry) else retirePrefixBitmap(entry)
        }
    }

    private fun complete(entry: CopyEntry, result: Int) {
        checkMainThread()
        if (!entry.pending) return
        entry.pending = false
        entry.result = result
        if (entry.orphaned || closed) {
            entry.bitmap.recycle()
            return
        }
        val failed = result != PixelCopy.SUCCESS
        if (failed) removeCopyEntry(entry)

        entry.delivering = true
        val subscribers = entry.subscribers.toList()
        entry.subscribers.clear()
        subscribers.forEach { subscriber ->
            if (!closed && subscriber.active) {
                subscriber.active = false
                subscriber.onResult(result, entry.bitmap.takeIf { result == PixelCopy.SUCCESS })
            }
            subscriber.active = false
        }
        entry.delivering = false
        if (entry.orphaned || closed) {
            entry.bitmap.recycle()
            return
        }
        if (failed) {
            retireBitmap(entry.bitmap)
        } else {
            maybeRetireCopyEntry(entry)
        }
    }

    private fun deliverCached(entry: CopyEntry, subscriber: Subscriber) {
        checkMainThread()
        if (!subscriber.active) return
        if (closed || entries[entry.key] !== entry) {
            subscriber.active = false
            return
        }
        entry.subscribers.remove(subscriber)
        subscriber.active = false
        entry.delivering = true
        subscriber.onResult(entry.result, entry.bitmap)
        entry.delivering = false
        maybeRetireCopyEntry(entry)
    }

    private fun cancel(entry: CopyEntry, subscriber: Subscriber) {
        checkMainThread()
        if (!subscriber.active) return
        subscriber.active = false
        entry.subscribers.remove(subscriber)
        maybeRetireCopyEntry(entry)
    }

    private fun retainEpoch(epoch: Long) {
        leaseCounts[epoch] = (leaseCounts[epoch] ?: 0) + 1
    }

    private fun releasePrefixEpoch(entry: PrefixEntry) {
        if (!entry.holdsEpoch) return
        entry.holdsEpoch = false
        releaseEpoch(entry.root.key.epoch)
    }

    private fun releaseEpoch(epoch: Long) {
        checkMainThread()
        val count = leaseCounts[epoch] ?: return
        if (count == 1) {
            leaseCounts.remove(epoch)
            if (epoch == latestEpoch) {
                trimReusableBitmapsIfEpochIdle()
            } else {
                pruneEpoch(epoch)
            }
        } else {
            leaseCounts[epoch] = count - 1
        }
    }

    private fun trimReusableBitmapsIfEpochIdle() {
        if (latestEpoch == Long.MIN_VALUE || leaseCounts[latestEpoch] != null) return
        poolRequirements.values.forEach { requirement ->
            requirement.required = 0
            requirement.kept = 0
        }
        prefixRoots.values.forEach { root ->
            if (root.key.epoch == latestEpoch && root.leasedBitmapCount > 0) {
                root.children.values.forEach { entry ->
                    visitPrefixSubtree(entry) { leased ->
                        if (leased.leaseCount == 0) return@visitPrefixSubtree
                        val key = bitmapSizeKey(leased.width, leased.height)
                        val requirement = poolRequirements.getOrPut(key) {
                            PoolRequirement(leased.width, leased.height)
                        }
                        requirement.required++
                    }
                }
            }
        }
        leasedOutputCounts.forEach { (key, count) ->
            val width = (key ushr 32).toInt()
            val height = key.toInt()
            val requirement = poolRequirements.getOrPut(key) {
                PoolRequirement(width, height)
            }
            requirement.required += count
        }
        val bucketIterator = reusableBitmaps.entries.iterator()
        while (bucketIterator.hasNext()) {
            val (key, bucket) = bucketIterator.next()
            val requirement = poolRequirements[key]
            val required = requirement?.required ?: 0
            while (bucket.size > required) {
                bucket.removeFirst().recycle()
                reusableBitmapCount--
            }
            requirement?.kept = bucket.size
            if (bucket.isEmpty()) bucketIterator.remove()
        }
        poolRequirements.forEach { (key, requirement) ->
            repeat(requirement.required - requirement.kept) {
                reusableBitmaps.getOrPut(key, ::ArrayDeque)
                    .addLast(bitmapFactory(requirement.width, requirement.height))
                reusableBitmapCount++
            }
        }
        poolRequirements.entries.removeAll { (_, requirement) -> requirement.required == 0 }
        reusableBitmapLimit = entries.count { it.key.epoch == latestEpoch } +
            prefixRoots.values.sumOf { root ->
                root.bitmapCount.takeIf { root.key.epoch == latestEpoch } ?: 0
            } + extraBitmapCount + poolRequirements.values.sumOf { it.required }
    }

    private fun pruneEpoch(epoch: Long) {
        if (epoch == latestEpoch || leaseCounts[epoch] != null) return
        prefixRootsByEpoch[epoch]?.toList()?.forEach(::maybeRetirePrefixRoot)
        entriesByEpoch[epoch]?.toList()?.forEach(::maybeRetireCopyEntry)
        surfaceEntriesByEpoch[epoch]?.toList()?.forEach(::maybeRetireSurfaceEntry)
    }

    private fun maybeRetireCopyEntry(entry: CopyEntry) {
        val epoch = entry.key.epoch
        if (entry.pending || entry.delivering || entry.subscribers.isNotEmpty() ||
            epoch == latestEpoch || leaseCounts[epoch] != null || entries[entry.key] !== entry
        ) return
        if (removeCopyEntry(entry)) retireBitmap(entry.bitmap)
    }

    private fun removeCopyEntry(entry: CopyEntry): Boolean {
        if (!entries.remove(entry.key, entry)) return false
        entriesByEpoch[entry.key.epoch]?.let { epochEntries ->
            epochEntries.remove(entry)
            if (epochEntries.isEmpty()) entriesByEpoch.remove(entry.key.epoch)
        }
        return true
    }

    private fun maybeRetireSurfaceEntry(entry: SurfaceCopyEntry) {
        val epoch = entry.key.epoch
        if (entry.pending || entry.delivering || entry.subscribers.isNotEmpty() ||
            epoch == latestEpoch || leaseCounts[epoch] != null ||
            surfaceEntries[entry.key] !== entry
        ) return
        if (removeSurfaceEntry(entry)) retireSurfaceBitmap(entry)
    }

    private fun removeSurfaceEntry(entry: SurfaceCopyEntry): Boolean {
        if (!surfaceEntries.remove(entry.key, entry)) return false
        surfaceEntriesByEpoch[entry.key.epoch]?.let { epochEntries ->
            epochEntries.remove(entry)
            if (epochEntries.isEmpty()) surfaceEntriesByEpoch.remove(entry.key.epoch)
        }
        return true
    }

    private fun maybeRetirePrefixRoot(root: PrefixRoot) {
        val epoch = root.key.epoch
        if (epoch == latestEpoch || leaseCounts[epoch] != null ||
            prefixRoots[root.key] !== root ||
            root.children.values.any { !canPrunePrefixSubtree(it) }
        ) return
        removePrefixRoot(root)
        root.children.values.forEach(::retirePrefixSubtree)
        root.children.clear()
        root.preparedEntries.clear()
        closePrefixRootResources(root, deferTrackerRelease = true)
    }

    private fun canPrunePrefixSubtree(entry: PrefixEntry): Boolean =
        !entry.rawPending && !entry.prefixPending && !entry.composing &&
            !entry.delivering && !entry.holdsEpoch && entry.subscribers.isEmpty() &&
            entry.children.values.all(::canPrunePrefixSubtree)

    private fun retirePrefixSubtree(entry: PrefixEntry) {
        entry.orphaned = true
        forgetPreparedPrefixes(entry)
        cancelRawDependencies(entry)
        retireOwnedRawBitmap(entry)
        uncountPrefixBitmap(entry)
        if (entry.leaseCount == 0) retirePrefixBitmap(entry)
        entry.children.values.forEach(::retirePrefixSubtree)
        entry.children.clear()
    }

    private fun updateReusableBitmapLimit() {
        reusableBitmapLimit = maxOf(
            reusableBitmapLimit,
            entries.size + prefixBitmapCount + extraBitmapCount,
        )
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val key = bitmapSizeKey(width, height)
        reservedReusableBitmaps[key]?.let { bucket ->
            val bitmap = bucket.removeLast()
            reusableBitmapCount--
            if (bucket.isEmpty()) reservedReusableBitmaps.remove(key)
            return bitmap
        }
        reusableBitmaps[key]?.let { bucket ->
            val bitmap = bucket.removeLast()
            reusableBitmapCount--
            if (bucket.isEmpty()) reusableBitmaps.remove(key)
            return bitmap
        }
        evictReusableBitmap()
        return bitmapFactory(width, height)
    }

    private fun retireBitmap(bitmap: Bitmap) {
        if (closed) {
            bitmap.recycle()
            return
        }
        if (reusableBitmapLimit == 0) {
            bitmap.recycle()
            return
        }
        val key = bitmapSizeKey(bitmap.width, bitmap.height)
        if (reusableBitmapCount >= reusableBitmapLimit) evictReusableBitmap()
        reusableBitmaps.getOrPut(key, ::ArrayDeque).addLast(bitmap)
        reusableBitmapCount++
    }

    private fun evictReusableBitmap() {
        val iterator = reusableBitmaps.entries.iterator()
        if (!iterator.hasNext()) return
        val bucket = iterator.next().value
        bucket.removeFirst().recycle()
        reusableBitmapCount--
        if (bucket.isEmpty()) iterator.remove()
    }

    private fun retirePrefixBitmap(entry: PrefixEntry) {
        val bitmap = entry.bitmap ?: return
        check(entry.leaseCount == 0)
        entry.bitmap = null
        uncountPrefixBitmap(entry)
        retireBitmap(bitmap)
    }

    private fun recyclePrefixBitmap(entry: PrefixEntry) {
        val bitmap = entry.bitmap ?: return
        check(entry.leaseCount == 0)
        entry.bitmap = null
        uncountPrefixBitmap(entry)
        bitmap.recycle()
    }

    private fun uncountPrefixBitmap(entry: PrefixEntry) {
        if (!entry.bitmapCounted) return
        entry.bitmapCounted = false
        entry.root.bitmapCount--
        prefixBitmapCount--
    }

    private fun closePrefixSubtree(entry: PrefixEntry) {
        entry.children.values.forEach(::closePrefixSubtree)
        entry.children.clear()
        entry.orphaned = true
        forgetPreparedPrefixes(entry)
        cancelRawDependencies(entry)
        retireOwnedRawBitmap(entry, recycle = true)
        entry.subscribers.forEach { subscriber ->
            subscriber.active = false
            releasePreparedOutput(subscriber)
        }
        entry.subscribers.clear()
        entry.prefixPending = false
        entry.holdsEpoch = false
        uncountPrefixBitmap(entry)
        if (entry.leaseCount == 0 && !entry.composing && !entry.delivering) {
            recyclePrefixBitmap(entry)
        }
    }

    private fun closePrefixRootResources(
        root: PrefixRoot,
        deferTrackerRelease: Boolean = false,
    ) {
        root.trackedSurfaceViews.keys.forEach { sourceView ->
            val sharedTracker = surfaceViewTrackers[sourceView] ?: return@forEach
            sharedTracker.rootCount--
            if (sharedTracker.rootCount == 0) {
                if (deferTrackerRelease) {
                    postSurfaceViewTrackerRelease(sourceView, sharedTracker)
                } else {
                    releaseSurfaceViewTracker(sourceView, sharedTracker)
                }
            }
        }
        root.trackedSurfaceViews.clear()
    }

    private fun postSurfaceViewTrackerRelease(
        sourceView: View,
        sharedTracker: SharedSurfaceViewTracker,
    ) {
        deferredSurfaceViewTrackerReleases[sourceView] = sharedTracker
        scheduleDeferredSurfaceViewTrackerRelease()
    }

    private fun scheduleDeferredSurfaceViewTrackerRelease() {
        if (closed || deferredSurfaceViewTrackerReleases.isEmpty() ||
            deferredSurfaceViewTrackerReleasePosted
        ) {
            return
        }
        deferredSurfaceViewTrackerReleasePosted = true
        mainHandler.post(deferredSurfaceViewTrackerReleaseRunner)
    }

    private fun drainDeferredSurfaceViewTrackerReleases() {
        deferredSurfaceViewTrackerReleasePosted = false
        if (closed) {
            deferredSurfaceViewTrackerReleases.clear()
            return
        }
        if (pendingPreparedBatches.isNotEmpty()) return

        val iterator = deferredSurfaceViewTrackerReleases.entries.iterator()
        while (iterator.hasNext()) {
            val (sourceView, sharedTracker) = iterator.next()
            iterator.remove()
            if (sharedTracker.rootCount == 0) {
                releaseSurfaceViewTracker(sourceView, sharedTracker)
            }
        }
    }

    private fun releaseSurfaceViewTracker(
        sourceView: View,
        sharedTracker: SharedSurfaceViewTracker,
    ) {
        if (surfaceViewTrackers[sourceView] !== sharedTracker) return
        surfaceViewTrackers.remove(sourceView)
        sharedTracker.tracker.release()
    }

    private fun retireSurfaceBitmap(entry: SurfaceCopyEntry) {
        if (!entry.bitmapCounted) return
        entry.bitmapCounted = false
        extraBitmapCount--
        retireBitmap(entry.bitmap)
        scheduleReusableBitmapTrimIfEpochIdle()
    }

    private fun recycleSurfaceBitmap(entry: SurfaceCopyEntry) {
        if (!entry.bitmapCounted) return
        entry.bitmapCounted = false
        extraBitmapCount--
        entry.bitmap.recycle()
        scheduleReusableBitmapTrimIfEpochIdle()
    }

    private fun scheduleReusableBitmapTrimIfEpochIdle() {
        if (closed || reusableBitmapTrimPosted || latestEpoch == Long.MIN_VALUE ||
            leaseCounts[latestEpoch] != null
        ) return
        reusableBitmapTrimPosted = true
        mainHandler.post {
            reusableBitmapTrimPosted = false
            if (!closed) trimReusableBitmapsIfEpochIdle()
        }
    }

    internal class Epoch internal constructor(
        private val coordinator: WindowPixelCopyCoordinator,
        private val epoch: Long,
    ) : AutoCloseable {
        private var closed = false

        fun request(
            window: Window,
            sourceRect: Rect,
            width: Int,
            height: Int,
            onResult: (Int, Bitmap?) -> Unit,
        ): WindowPixelCopyRequest {
            check(!closed) { "Window PixelCopy epoch is closed" }
            return coordinator.request(epoch, window, sourceRect, width, height, onResult)
        }

        fun requestPrefix(
            planes: List<WindowPixelCopyPlane>,
            width: Int,
            height: Int,
            onResult: (Int, WindowPixelCopyLease?) -> Unit,
        ): WindowPixelCopyRequest {
            check(!closed) { "Window PixelCopy epoch is closed" }
            return coordinator.requestPrefix(epoch, planes, width, height, onResult)
        }

        fun requestPrefix(
            prefix: BackdropCapturePrefix,
            viewport: BackdropCaptureViewport,
            onResult: (Int, WindowPrefixFrame?) -> Unit,
        ): WindowPixelCopyRequest {
            check(!closed) { "Window PixelCopy epoch is closed" }
            return coordinator.requestPrefix(epoch, prefix, viewport, onResult)
        }

        override fun close() {
            if (closed) return
            closed = true
            coordinator.releaseEpoch(epoch)
        }
    }

    private class CopyKey(
        val epoch: Long,
        val window: Window,
        sourceRect: Rect,
        val width: Int,
        val height: Int,
    ) {
        val sourceRect = Rect(sourceRect)

        override fun equals(other: Any?): Boolean =
            other is CopyKey &&
                epoch == other.epoch &&
                window === other.window &&
                sourceRect == other.sourceRect &&
                width == other.width &&
                height == other.height

        override fun hashCode(): Int {
            var result = epoch.hashCode()
            result = 31 * result + System.identityHashCode(window)
            result = 31 * result + sourceRect.hashCode()
            result = 31 * result + width
            return 31 * result + height
        }
    }

    private data class PrefixRootKey(
        val epoch: Long,
        val width: Int,
        val height: Int,
        val screenLeft: Int? = null,
        val screenTop: Int? = null,
        val screenRight: Int? = null,
        val screenBottom: Int? = null,
        val preparedWaveId: Long? = null,
    )

    private data class PreparedBatchKey(
        val epoch: Long,
        val screenLeft: Int,
        val screenTop: Int,
        val screenRight: Int,
        val screenBottom: Int,
    )

    private class PreparedBatch(val key: PreparedBatchKey) {
        val requests = mutableListOf<PreparedRequest>()
    }

    private class PreparedRequest(
        val prefix: BackdropCapturePrefix,
        val viewport: BackdropCaptureViewport,
        val onResult: (Int, WindowPrefixFrame?) -> Unit,
    ) {
        var active = true
        var delegate: WindowPixelCopyRequest? = null
    }

    private class PreparedTopology(
        val prefixes: Array<BackdropCapturePrefix>,
        val nodes: IdentityHashMap<BackdropCapturePrefix, PreparedNode>,
        val orderedNodes: List<PreparedNode>,
        val rootNodes: List<PreparedNode>,
    ) {
        fun matches(requests: List<PreparedRequest>): Boolean {
            if (prefixes.size != requests.size) return false
            prefixes.indices.forEach { index ->
                if (prefixes[index] !== requests[index].prefix) return false
            }
            return true
        }
    }

    private class PreparedNode(
        val prefix: BackdropCapturePrefix,
        val parent: PreparedNode?,
    ) {
        val prefixes = IdentityHashMap<BackdropCapturePrefix, Boolean>().apply {
            put(prefix, true)
        }
        val children = mutableListOf<PreparedNode>()
        val childrenBySource = LinkedHashMap<BackdropCaptureSource, PreparedNode>()
        var requestedWidth = 0
        var requestedHeight = 0
        var width = 0
        var height = 0
        var entry: PrefixEntry? = null
    }

    private class PreparedRootMatch(
        val root: PrefixRoot,
        val entries: IdentityHashMap<PreparedNode, PrefixEntry>,
    )

    private class PrefixRoot(val key: PrefixRootKey) {
        val children = LinkedHashMap<WindowPixelCopyPlane, PrefixEntry>()
        val preparedEntries = IdentityHashMap<BackdropCapturePrefix, PrefixEntry>()
        val trackedSurfaceViews = IdentityHashMap<View, Boolean>()
        var bitmapCount = 0
        var leasedBitmapCount = 0
    }

    private class SharedSurfaceViewTracker(
        val tracker: SurfaceViewPresenceTracker,
    ) {
        var rootCount = 0
    }

    private class PoolRequirement(
        val width: Int,
        val height: Int,
    ) {
        var required = 0
        var kept = 0
    }

    private class CopyEntry(
        val key: CopyKey,
        val bitmap: Bitmap,
    ) {
        val subscribers = mutableListOf<Subscriber>()
        var pending = true
        var delivering = false
        var orphaned = false
        var result = PixelCopy.ERROR_UNKNOWN
    }

    private class Subscriber(
        val onResult: (Int, Bitmap?) -> Unit,
    ) {
        var active = true
    }

    private class SurfaceCopyKey(
        val epoch: Long,
        val surface: Surface,
        val width: Int,
        val height: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is SurfaceCopyKey &&
                epoch == other.epoch &&
                surface === other.surface &&
                width == other.width &&
                height == other.height

        override fun hashCode(): Int {
            var result = epoch.hashCode()
            result = 31 * result + System.identityHashCode(surface)
            result = 31 * result + width
            return 31 * result + height
        }
    }

    private class SurfaceCopyEntry(
        val key: SurfaceCopyKey,
        val bitmap: Bitmap,
    ) {
        val subscribers = mutableListOf<SurfaceSubscriber>()
        var pending = true
        var delivering = false
        var orphaned = false
        var bitmapCounted = true
        var result = PixelCopy.ERROR_UNKNOWN
    }

    private class SurfaceSubscriber(
        val onResult: (Int, Bitmap?) -> Unit,
    ) {
        var active = true
    }

    private class PrefixSurfaceLayer(
        val target: SurfaceView,
        val surface: Surface,
        val width: Int,
        val height: Int,
        val alpha: Float,
        val aboveWindow: Boolean,
        val compositionOrder: Int,
    ) {
        var bitmap: Bitmap? = null
    }

    private data class PreparedSurfaceLayers(
        val layers: List<PrefixSurfaceLayer>,
        val ready: Boolean,
    )

    private class PrefixEntry(
        val root: PrefixRoot,
        val parent: PrefixEntry?,
        val plane: WindowPixelCopyPlane,
        var width: Int = root.key.width,
        var height: Int = root.key.height,
        val rawWidth: Int = width,
        val rawHeight: Int = height,
        val surfaceLayers: List<PrefixSurfaceLayer> = emptyList(),
        val surfacesReady: Boolean = true,
    ) {
        val children = LinkedHashMap<WindowPixelCopyPlane, PrefixEntry>()
        val preparedPrefixes = IdentityHashMap<BackdropCapturePrefix, Boolean>()
        val subscribers = mutableListOf<PrefixSubscriber>()
        var rawRequest: WindowPixelCopyRequest? = null
        var surfaceRequests: MutableList<WindowPixelCopyRequest>? = null
        var windowBitmap: Bitmap? = null
        var rawBitmap: Bitmap? = null
        var rawPending = true
        var rawPartsRemaining = 0
        var rawChainReady = false
        var ownedRawBitmap = false
        var bitmap: Bitmap? = null
        var bitmapCounted = false
        var prefixPending = false
        var composing = false
        var delivering = false
        var orphaned = false
        var holdsEpoch = false
        var leaseCount = 0
        var result = PixelCopy.ERROR_UNKNOWN
    }

    private class PrefixSubscriber(
        val width: Int,
        val height: Int,
        val onResult: (Int, WindowPixelCopyLease?) -> Unit,
    ) {
        var active = true
        var preparedOutput: Bitmap? = null
    }

    private class WindowSurfacePlaneRenderer {
        private val sourceLocation = IntArray(2)
        private val surfaceLocation = IntArray(2)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val clipPath = Path()
        private val canvas = Canvas()
        private val destinationBounds = RectF()
        private val ancestorMatrix = Matrix()
        private val ancestorStepMatrix = Matrix()

        fun compose(
            destination: Bitmap,
            window: Bitmap,
            layers: List<PrefixSurfaceLayer>,
            sourceView: View,
            rootKey: PrefixRootKey,
            outputWidth: Int,
            outputHeight: Int,
        ) {
            val screenLeft = requireNotNull(rootKey.screenLeft)
            val screenTop = requireNotNull(rootKey.screenTop)
            val viewportWidth = requireNotNull(rootKey.screenRight) - screenLeft
            val viewportHeight = requireNotNull(rootKey.screenBottom) - screenTop
            val scaleX = outputWidth.toFloat() / viewportWidth
            val scaleY = outputHeight.toFloat() / viewportHeight
            canvas.setBitmap(destination)
            val composeSaveCount = canvas.save()
            try {
                destination.eraseColor(android.graphics.Color.TRANSPARENT)
                layers.forEach { layer ->
                    if (!layer.aboveWindow) {
                        drawSurfaceLayer(
                            canvas,
                            layer,
                            sourceView,
                            screenLeft,
                            screenTop,
                            scaleX,
                            scaleY,
                        )
                    }
                }
                canvas.drawBitmap(window, 0f, 0f, null)
                layers.forEach { layer ->
                    if (layer.aboveWindow) {
                        drawSurfaceLayer(
                            canvas,
                            layer,
                            sourceView,
                            screenLeft,
                            screenTop,
                            scaleX,
                            scaleY,
                        )
                    }
                }
            } finally {
                try {
                    canvas.restoreToCount(composeSaveCount)
                } finally {
                    canvas.setBitmap(null)
                }
            }
        }

        private fun drawSurfaceLayer(
            canvas: Canvas,
            layer: PrefixSurfaceLayer,
            sourceView: View,
            screenLeft: Int,
            screenTop: Int,
            scaleX: Float,
            scaleY: Float,
        ) {
            val bitmap = requireNotNull(layer.bitmap)
            sourceView.getLocationOnScreen(sourceLocation)
            val offsetX = screenLeft - sourceLocation[0]
            val offsetY = screenTop - sourceLocation[1]
            paint.alpha = (layer.alpha * 255).toInt().coerceIn(0, 255)
            val saveCount = canvas.save()
            canvas.scale(scaleX, scaleY)
            canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
            if (layer.aboveWindow) {
                layer.target.getLocationOnScreen(surfaceLocation)
                val left = (surfaceLocation[0] - sourceLocation[0]).toFloat()
                val top = (surfaceLocation[1] - sourceLocation[1]).toFloat()
                destinationBounds.set(
                    left,
                    top,
                    left + layer.target.width,
                    top + layer.target.height,
                )
            } else {
                clipToAncestors(canvas, layer.target, sourceView)
                canvas.concat(layer.target.matrixToAncestor(sourceView))
                layer.target.clipBounds?.let(canvas::clipRect)
                destinationBounds.set(
                    0f,
                    0f,
                    layer.target.width.toFloat(),
                    layer.target.height.toFloat(),
                )
            }
            canvas.drawBitmap(bitmap, null, destinationBounds, paint)
            canvas.restoreToCount(saveCount)
        }

        private fun clipToAncestors(canvas: Canvas, target: View, ancestor: View) {
            var parent = target.parent as? View
            while (parent != null) {
                if (parent is ViewGroup && (parent.clipChildren || parent.clipToPadding)) {
                    val left = if (parent.clipToPadding) parent.paddingLeft.toFloat() else 0f
                    val top = if (parent.clipToPadding) parent.paddingTop.toFloat() else 0f
                    val right = if (parent.clipToPadding) {
                        (parent.width - parent.paddingRight).toFloat()
                    } else {
                        parent.width.toFloat()
                    }
                    val bottom = if (parent.clipToPadding) {
                        (parent.height - parent.paddingBottom).toFloat()
                    } else {
                        parent.height.toFloat()
                    }
                    clipPath.reset()
                    clipPath.addRect(left, top, right, bottom, Path.Direction.CW)
                    clipPath.transform(parent.matrixToAncestor(ancestor))
                    canvas.clipPath(clipPath)
                }
                parent.clipBounds?.let { bounds ->
                    clipPath.reset()
                    clipPath.addRect(
                        bounds.left.toFloat(),
                        bounds.top.toFloat(),
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat(),
                        Path.Direction.CW,
                    )
                    clipPath.transform(parent.matrixToAncestor(ancestor))
                    canvas.clipPath(clipPath)
                }
                if (parent === ancestor) return
                parent = parent.parent as? View
            }
        }

        private fun View.matrixToAncestor(ancestor: View): Matrix {
            ancestorMatrix.reset()
            var current: View = this
            while (current !== ancestor) {
                val parent = current.parent as? View ?: return ancestorMatrix.apply(Matrix::reset)
                ancestorStepMatrix.reset()
                ancestorStepMatrix.setTranslate(
                    (current.left - parent.scrollX).toFloat(),
                    (current.top - parent.scrollY).toFloat(),
                )
                if (!current.matrix.isIdentity) ancestorStepMatrix.preConcat(current.matrix)
                ancestorMatrix.setConcat(ancestorStepMatrix, ancestorMatrix)
                current = parent
            }
            return ancestorMatrix
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "WindowPixelCopyCoordinator must run on the main thread"
        }
    }

    private fun bitmapSizeKey(width: Int, height: Int): Long =
        width.toLong() shl 32 or (height.toLong() and 0xffffffffL)

}

private object AndroidWindowPrefixComposer : WindowPrefixComposer {
    private val canvas = Canvas()
    private val sourcePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val additionPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destinationBounds = Rect()

    override fun compose(
        destination: Bitmap,
        prefix: Bitmap?,
        additions: List<Bitmap>,
    ) {
        require(prefix != null || additions.isNotEmpty()) {
            "Window prefix composition requires at least one plane"
        }
        canvas.setBitmap(destination)
        try {
            destinationBounds.set(0, 0, destination.width, destination.height)
            val first = prefix ?: additions.first()
            if (first.width == destination.width && first.height == destination.height) {
                canvas.drawBitmap(first, 0f, 0f, sourcePaint)
            } else {
                canvas.drawBitmap(first, null, destinationBounds, sourcePaint)
            }
            var additionIndex = if (prefix == null) 1 else 0
            while (additionIndex < additions.size) {
                val bitmap = additions[additionIndex++]
                if (bitmap.width == destination.width && bitmap.height == destination.height) {
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                } else {
                    canvas.drawBitmap(bitmap, null, destinationBounds, additionPaint)
                }
            }
        } finally {
            canvas.setBitmap(null)
        }
    }
}

private object AndroidWindowPixelCopier : WindowPixelCopier {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun request(
        window: Window,
        sourceRect: Rect,
        destination: Bitmap,
        onResult: (Int) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            mainHandler.post { onResult(PixelCopy.ERROR_UNKNOWN) }
            return
        }
        PixelCopy.request(window, sourceRect, destination, onResult, mainHandler)
    }
}
