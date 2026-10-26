package dev.scanrelay.app.ui

/** Page prefetch when the user approaches the end. I/O is independently
 * serialized in the repository to avoid duplicates and filter races. */
internal object InfiniteScrollPolicy {
    fun shouldLoad(
        lastVisibleIndex: Int?,
        totalItems: Int,
        loadedRows: Int,
        hasMore: Boolean,
        loading: Boolean,
        connected: Boolean,
        prefetchItems: Int = 6
    ): Boolean = connected && hasMore && !loading && loadedRows > 0 &&
        totalItems > 0 && lastVisibleIndex != null &&
        lastVisibleIndex >= totalItems - 1 - prefetchItems.coerceAtLeast(0)
}
