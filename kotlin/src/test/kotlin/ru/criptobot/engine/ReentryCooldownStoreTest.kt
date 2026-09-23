package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class ReentryCooldownStoreTest {
    @Test
    fun `cooldown survives restart and expires`() {
        val dir = Files.createTempDirectory("reentry-cooldown-test")
        val file = dir.resolve("cooldowns.tsv")
        val now = Instant.now()

        ReentryCooldownStore(file).block("brzl", Duration.ofHours(4), "Stop-loss", now)

        val restored = ReentryCooldownStore(file)
        assertEquals("BRZL", restored.active("BRZL", now.plusSeconds(60))?.symbol)
        assertEquals("Stop-loss", restored.active("BRZL", now.plusSeconds(60))?.reason)
        assertNull(restored.active("BRZL", now.plus(Duration.ofHours(4))))
    }

    @Test
    fun `zero duration does not block`() {
        val file = Files.createTempDirectory("reentry-cooldown-zero").resolve("cooldowns.tsv")
        val store = ReentryCooldownStore(file)

        store.block("EUTR", Duration.ZERO, "Stop-loss")

        assertNull(store.active("EUTR"))
    }
}
