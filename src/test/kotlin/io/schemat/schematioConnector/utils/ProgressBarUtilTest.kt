package io.schemat.schematioConnector.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * Tests for progress bar utility logic.
 *
 * These tests verify the progress calculation and formatting logic
 * without requiring Bukkit boss bar dependencies.
 */
class ProgressBarUtilTest {

    @Nested
    @DisplayName("Progress Normalization")
    inner class ProgressNormalizationTests {

        /**
         * Normalize progress value to 0-1 range.
         * Input can be 0-100 (percentage) or 0-1 (ratio).
         */
        private fun normalizeProgress(progress: Float): Float {
            return when {
                progress < 0 -> 0f
                progress > 1 && progress <= 100 -> progress / 100f  // Treat as percentage
                progress > 100 -> 1f
                else -> progress
            }
        }

        @Test
        fun `ratio values are unchanged`() {
            assertEquals(0f, normalizeProgress(0f))
            assertEquals(0.5f, normalizeProgress(0.5f))
            assertEquals(1f, normalizeProgress(1f))
        }

        @Test
        fun `percentage values are converted to ratio`() {
            assertEquals(0.5f, normalizeProgress(50f))
            assertEquals(1f, normalizeProgress(100f))
        }

        @Test
        fun `negative values are clamped to 0`() {
            assertEquals(0f, normalizeProgress(-10f))
            assertEquals(0f, normalizeProgress(-0.5f))
        }

        @Test
        fun `values over 100 are clamped to 1`() {
            assertEquals(1f, normalizeProgress(150f))
            assertEquals(1f, normalizeProgress(1000f))
        }
    }

    @Nested
    @DisplayName("Progress Text Formatting")
    inner class ProgressTextFormattingTests {

        /**
         * Format progress as a percentage string.
         */
        private fun formatProgressText(progress: Float): String {
            val percentage = (progress * 100).toInt().coerceIn(0, 100)
            return "$percentage%"
        }

        /**
         * Format progress with a title.
         */
        private fun formatProgressWithTitle(title: String, progress: Float): String {
            return "$title: ${formatProgressText(progress)}"
        }

        @Test
        fun `zero progress formats correctly`() {
            assertEquals("0%", formatProgressText(0f))
        }

        @Test
        fun `full progress formats correctly`() {
            assertEquals("100%", formatProgressText(1f))
        }

        @Test
        fun `partial progress formats correctly`() {
            assertEquals("50%", formatProgressText(0.5f))
            assertEquals("75%", formatProgressText(0.75f))
        }

        @Test
        fun `progress with title formats correctly`() {
            assertEquals("Downloading: 50%", formatProgressWithTitle("Downloading", 0.5f))
        }

        @Test
        fun `fractional percentages are truncated`() {
            // toInt() truncates, so 33.3 -> 33 and 66.6 -> 66
            assertEquals("33%", formatProgressText(0.333f))
            assertEquals("66%", formatProgressText(0.666f))
        }
    }

    @Nested
    @DisplayName("Progress Bar Visual")
    inner class ProgressBarVisualTests {

        /**
         * Create a text-based progress bar.
         */
        private fun createProgressBar(progress: Float, width: Int = 20): String {
            val filled = (progress * width).toInt().coerceIn(0, width)
            val empty = width - filled
            return "[" + "=".repeat(filled) + "-".repeat(empty) + "]"
        }

        @Test
        fun `empty progress bar`() {
            assertEquals("[--------------------]", createProgressBar(0f))
        }

        @Test
        fun `full progress bar`() {
            assertEquals("[====================]", createProgressBar(1f))
        }

        @Test
        fun `half progress bar`() {
            // 0.5 * 20 = 10 filled, 10 empty
            assertEquals("[==========----------]", createProgressBar(0.5f))
        }

        @Test
        fun `custom width progress bar`() {
            assertEquals("[=====-----]", createProgressBar(0.5f, width = 10))
        }

        @Test
        fun `progress bar length is consistent`() {
            val bar0 = createProgressBar(0f, 20)
            val bar50 = createProgressBar(0.5f, 20)
            val bar100 = createProgressBar(1f, 20)

            assertEquals(bar0.length, bar50.length)
            assertEquals(bar50.length, bar100.length)
            assertEquals(22, bar0.length) // 20 + 2 brackets
        }
    }

    @Nested
    @DisplayName("Progress Animation")
    inner class ProgressAnimationTests {

        /**
         * Get spinner character for animation frame.
         */
        private fun getSpinnerFrame(tick: Int): Char {
            val frames = charArrayOf('|', '/', '-', '\\')
            return frames[tick % frames.size]
        }

        /**
         * Get bouncing bar position for animation.
         */
        private fun getBouncingPosition(tick: Int, width: Int): Int {
            val cycle = width * 2
            val pos = tick % cycle
            return if (pos < width) pos else cycle - pos - 1
        }

        @Test
        fun `spinner cycles through frames`() {
            assertEquals('|', getSpinnerFrame(0))
            assertEquals('/', getSpinnerFrame(1))
            assertEquals('-', getSpinnerFrame(2))
            assertEquals('\\', getSpinnerFrame(3))
            assertEquals('|', getSpinnerFrame(4)) // Wraps
        }

        @Test
        fun `bouncing bar moves forward then backward`() {
            val width = 5
            assertEquals(0, getBouncingPosition(0, width))
            assertEquals(1, getBouncingPosition(1, width))
            assertEquals(4, getBouncingPosition(4, width))
            assertEquals(4, getBouncingPosition(5, width))
            assertEquals(3, getBouncingPosition(6, width))
            assertEquals(0, getBouncingPosition(9, width))
            assertEquals(0, getBouncingPosition(10, width)) // Cycle restarts
        }
    }

    @Nested
    @DisplayName("Time Estimation")
    inner class TimeEstimationTests {

        /**
         * Estimate remaining time based on progress rate.
         */
        private fun estimateRemainingSeconds(
            elapsedMs: Long,
            progress: Float
        ): Long? {
            if (progress <= 0 || elapsedMs <= 0) return null
            if (progress >= 1) return 0

            val msPerProgress = elapsedMs / progress
            val remainingProgress = 1 - progress
            val remainingMs = (msPerProgress * remainingProgress).toLong()

            return remainingMs / 1000
        }

        /**
         * Format seconds into human-readable time.
         */
        private fun formatRemainingTime(seconds: Long?): String {
            if (seconds == null) return "Calculating..."
            if (seconds <= 0) return "Done"
            if (seconds < 60) return "${seconds}s"

            val minutes = seconds / 60
            val secs = seconds % 60

            return if (minutes < 60) {
                "${minutes}m ${secs}s"
            } else {
                val hours = minutes / 60
                val mins = minutes % 60
                "${hours}h ${mins}m"
            }
        }

        @Test
        fun `estimate returns null for zero progress`() {
            assertNull(estimateRemainingSeconds(1000, 0f))
        }

        @Test
        fun `estimate returns 0 for complete progress`() {
            assertEquals(0, estimateRemainingSeconds(1000, 1f))
        }

        @Test
        fun `estimate is reasonable for 50 percent`() {
            // 10 seconds elapsed, 50% done = 10 more seconds
            val remaining = estimateRemainingSeconds(10000, 0.5f)
            assertEquals(10, remaining)
        }

        @Test
        fun `format seconds correctly`() {
            assertEquals("5s", formatRemainingTime(5))
            assertEquals("59s", formatRemainingTime(59))
        }

        @Test
        fun `format minutes correctly`() {
            assertEquals("1m 0s", formatRemainingTime(60))
            assertEquals("5m 30s", formatRemainingTime(330))
        }

        @Test
        fun `format hours correctly`() {
            assertEquals("1h 0m", formatRemainingTime(3600))
            assertEquals("2h 30m", formatRemainingTime(9000))
        }

        @Test
        fun `format null returns calculating`() {
            assertEquals("Calculating...", formatRemainingTime(null))
        }

        @Test
        fun `format done for zero seconds`() {
            assertEquals("Done", formatRemainingTime(0))
        }
    }

    @Nested
    @DisplayName("Download Speed Formatting")
    inner class DownloadSpeedTests {

        /**
         * Format download speed in appropriate units.
         */
        private fun formatSpeed(bytesPerSecond: Long): String {
            return when {
                bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
                bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
                else -> String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
            }
        }

        @Test
        fun `format bytes per second`() {
            assertEquals("500 B/s", formatSpeed(500))
        }

        @Test
        fun `format kilobytes per second`() {
            assertEquals("50 KB/s", formatSpeed(50 * 1024))
        }

        @Test
        fun `format megabytes per second`() {
            assertEquals("1.5 MB/s", formatSpeed((1.5 * 1024 * 1024).toLong()))
        }

        @Test
        fun `format edge cases`() {
            assertEquals("0 B/s", formatSpeed(0))
            assertEquals("1023 B/s", formatSpeed(1023))
            assertEquals("1 KB/s", formatSpeed(1024))
        }
    }

    @Nested
    @DisplayName("File Size Formatting")
    inner class FileSizeTests {

        /**
         * Format file size in appropriate units.
         */
        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }

        @Test
        fun `format bytes`() {
            assertEquals("500 B", formatFileSize(500))
        }

        @Test
        fun `format kilobytes`() {
            assertEquals("50 KB", formatFileSize(50 * 1024))
        }

        @Test
        fun `format megabytes`() {
            assertEquals("1.5 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        }

        @Test
        fun `format gigabytes`() {
            assertEquals("2.50 GB", formatFileSize((2.5 * 1024 * 1024 * 1024).toLong()))
        }
    }
}
