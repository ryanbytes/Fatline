package dev.scanrelay.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerAudioStopPolicyTest {
    @Test fun playRequiresNoConfirmation() {
        assertTrue(ScannerAudioStopPolicy.permit(enabled = true, confirmedStop = false, fromScannerCard = true))
        assertTrue(ScannerAudioStopPolicy.permit(enabled = true, confirmedStop = false, fromScannerCard = false))
    }

    @Test fun straySingleTapAndUnconfirmedServiceCommandsCannotStopAudio() {
        assertFalse(ScannerAudioStopPolicy.permit(enabled = false, confirmedStop = false, fromScannerCard = true))
        assertFalse(ScannerAudioStopPolicy.permit(enabled = false, confirmedStop = false, fromScannerCard = false))
        assertFalse(ScannerAudioStopPolicy.permit(enabled = false, confirmedStop = true, fromScannerCard = false))
    }

    @Test fun onlyConfirmedScannerCardStopCanDisableAudio() {
        assertTrue(ScannerAudioStopPolicy.permit(enabled = false, confirmedStop = true, fromScannerCard = true))
    }
}
