package io.schemat.schematioConnector.utils

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

object ProgressBarUtil {
    fun createProgressBar(player: Player, title: String): BossBar {
        val bossBar = BossBar.bossBar(
            Component.text(title),
            0f,
            BossBar.Color.BLUE,
            BossBar.Overlay.PROGRESS
        )
        (player as Audience).showBossBar(bossBar)
        return bossBar
    }

    fun updateProgressBar(bossBar: BossBar, progress: Float) {
        bossBar.progress(progress.coerceIn(0f, 1f))
    }

    fun removeProgressBar(player: Player, bossBar: BossBar) {
        (player as Audience).hideBossBar(bossBar)
    }
}