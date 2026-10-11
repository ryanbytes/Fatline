package dev.scanrelay.app.net

internal enum class NetworkHandoffTransition {
    NO_CHANGE,
    NETWORK_LOST,
    NETWORK_RESTORED,
    NETWORK_SWITCHED
}

internal enum class InternetValidationTransition {
    NO_CHANGE,
    VALIDATION_LOST,
    VALIDATION_RESTORED
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

    /** The initial capabilities snapshot must not interrupt a working LAN-only socket. */
    fun internetValidation(previous: Boolean?, validated: Boolean): InternetValidationTransition = when {
        previous == true && !validated -> InternetValidationTransition.VALIDATION_LOST
        previous == false && validated -> InternetValidationTransition.VALIDATION_RESTORED
        else -> InternetValidationTransition.NO_CHANGE
    }
}
