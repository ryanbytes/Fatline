package dev.scanrelay.app.net

internal enum class NetworkHandoffTransition {
    NO_CHANGE,
    NETWORK_LOST,
    NETWORK_RESTORED,
    NETWORK_SWITCHED
}

/** Classifies Android default-network callbacks without depending on callback ordering. */
internal object NetworkHandoffPolicy {
    fun transition(previousHandle: Long?, observedHandle: Long?): NetworkHandoffTransition = when {
        previousHandle == observedHandle -> NetworkHandoffTransition.NO_CHANGE
        previousHandle != null && observedHandle == null -> NetworkHandoffTransition.NETWORK_LOST
        previousHandle == null && observedHandle != null -> NetworkHandoffTransition.NETWORK_RESTORED
        else -> NetworkHandoffTransition.NETWORK_SWITCHED
    }

    fun isCurrentLoss(lostHandle: Long, currentHandle: Long?): Boolean =
        currentHandle != null && lostHandle == currentHandle
}
