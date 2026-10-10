package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProbeTest {
    private fun reading(
        elapsed: Long, cpu: Long, pss: Long? = 20_480L, javaHeap: Long = 8_192L,
        native: Long = 4_096L, rx: Long? = 10_240L, tx: Long? = 4_096L,
        pct: Int? = 80, charge: Long? = 3_000_000L, plugged: Boolean = false
    ) = PerformanceReading(
        elapsedMs = elapsed, cpuMs = cpu, pssKb = pss,
        javaHeapKb = javaHeap, nativeHeapKb = native,
        rxBytes = rx, txBytes = tx,
        batteryPercent = pct, chargeMicroAh = charge, plugged = plugged
    )

    @Test
    fun reportSeparatesCpuPssNetworkAndWholePhoneBattery() {
        val probe = PerformanceProbe(
            "Live playback", reading(elapsed = 1_000L, cpu = 200L)
        )
        probe.sample(reading(
            elapsed = 61_000L, cpu = 12_200L, pss = 30_720L, javaHeap = 12_288L,
            native = 5_120L, rx = 20_480L, tx = 6_144L, pct = 79,
            charge = 2_995_000L
        ))
        probe.sample(reading(
            elapsed = 121_000L, cpu = 24_200L, pss = 24_576L,
            javaHeap = 9_216L, native = 6_144L, rx = 30_720L,
            tx = 8_192L, pct = 79, charge = 2_990_000L
        ))
        val report = probe.report()
        assertTrue(report.contains("Scenario: Live playback"))
        assertTrue(report.contains("Duration: 2.0 min; samples: 3"))
        assertTrue(report.contains("App CPU time: 24000 ms"))
        assertTrue(report.contains("Avg app CPU: 20.0% of one core"))
        assertTrue(report.contains("PSS memory start / end / peak: 20.0 MiB / 24.0 MiB / 30.0 MiB"))
        assertTrue(report.contains("UID network received: 20.0 KiB"))
        assertTrue(report.contains("UID network sent: 4.0 KiB"))
        assertTrue(report.contains("Phone battery level: 80% → 79%"))
        assertTrue(report.contains("Phone battery charge used: 10.00 mAh"))
        assertTrue(report.contains("whole-phone measurements, not FatLine-only"))
    }

    @Test
    fun reportsDelayedBackgroundSamplingWithoutAffectingCpuMath() {
        val probe = PerformanceProbe("Idle monitoring", reading(elapsed = 0L, cpu = 0L))
        probe.sample(reading(elapsed = 10_000L, cpu = 300L))
        probe.sample(reading(elapsed = 5_000L, cpu = 999L)) // stale reading ignored
        probe.sample(reading(elapsed = 35_001L, cpu = 1_000L))
        probe.sample(reading(elapsed = 45_001L, cpu = 1_350L))
        val report = probe.report()
        assertTrue(report.contains("Duration: 0.8 min; samples: 4"))
        assertTrue(report.contains("Sampling interval mean / longest: 15.0 s / 25.0 s; gaps >20s: 1"))
        assertTrue(report.contains("App CPU time: 1350 ms"))
        assertTrue(report.contains("Avg app CPU: 3.0% of one core"))
    }

    @Test
    fun samplingGapReportHandlesOnlyInitialReading() {
        val probe = PerformanceProbe("Brief", reading(elapsed = 0L, cpu = 0L))
        val report = probe.report()
        assertTrue(report.contains("Sampling interval mean / longest: unavailable s / 0.0 s; gaps >20s: 0"))
        assertTrue(report.contains("Avg app CPU: unavailable"))
    }

    @Test
    fun unsupportedCountersAndResetNetworkCountersStayUnavailable() {
        val probe = PerformanceProbe(
            "Idle monitoring", reading(
                elapsed = 100L, cpu = 500L, pss = null, rx = 5_000L,
                tx = null, pct = null, charge = null
            )
        )
        probe.sample(reading(
            elapsed = 10_100L, cpu = 700L, pss = null, rx = 2L,
            tx = null, pct = null, charge = null
        ))
        val report = probe.report()
        assertTrue(report.contains("PSS memory start / end / peak: unavailable / unavailable / unavailable"))
        assertTrue(report.contains("UID network received: unavailable"))
        assertTrue(report.contains("UID network sent: unavailable"))
        assertTrue(report.contains("Phone battery charge used: unavailable"))
    }

    @Test
    fun pluggingInDisablesBatteryDischargeClaims() {
        val probe = PerformanceProbe(
            "Transcript monitoring", reading(elapsed = 0, cpu = 0, charge = 3_000_000L)
        )
        probe.sample(reading(
            elapsed = 60_000, cpu = 600, charge = 2_990_000L, plugged = true
        ))
        probe.sample(reading(
            elapsed = 120_000, cpu = 1_200, charge = 2_980_000L, plugged = false
        ))
        val report = probe.report()
        assertTrue(report.contains("Charging observed: true"))
        assertTrue(report.contains("Phone battery level: unavailable while charging"))
        assertTrue(report.contains("Phone battery charge used: unavailable"))
    }

    @Test
    fun invalidOutOfOrderSamplesAreIgnoredAndCpuPercentMayExceed100() {
        val probe = PerformanceProbe("Multicore test", reading(elapsed = 5_000, cpu = 1_000))
        probe.sample(reading(elapsed = 4_000, cpu = 2_000))
        probe.sample(reading(elapsed = 6_000, cpu = 3_500))
        val report = probe.report()
        assertTrue(report.contains("Duration: 0.0 min; samples: 2"))
        assertTrue(report.contains("Avg app CPU: 250.0% of one core"))
        assertFalse(report.contains("samples: 3"))
    }

    @Test
    fun zeroDurationDoesNotDivideByZero() {
        val probe = PerformanceProbe("Quick", reading(elapsed = 0L, cpu = 0L))
        probe.sample(reading(elapsed = 0L, cpu = 0L))
        assertTrue(probe.report().contains("Avg app CPU: unavailable"))
    }
}
