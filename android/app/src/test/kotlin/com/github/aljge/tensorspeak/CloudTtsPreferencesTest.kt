package com.github.aljge.tensorspeak

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTtsPreferencesTest {

    @Test
    fun `parses label id pairs`() {
        val slots = CloudTtsPreferences.parseVoiceSlots("Rachel|21m00Tcm4TlvDq8ikWAM\nAdam|pNInz6obpgDQGcFmaJgB")
        assertEquals(2, slots.size)
        assertEquals("Rachel", slots[0].label)
        assertEquals("21m00Tcm4TlvDq8ikWAM", slots[0].id)
        assertEquals("rachel", slots[0].slug)
        assertEquals("adam", slots[1].slug)
    }

    @Test
    fun `skips blank lines and lines without a delimiter`() {
        val slots = CloudTtsPreferences.parseVoiceSlots("\n  \nno-delimiter-here\nRachel|abc\n")
        assertEquals(1, slots.size)
        assertEquals("Rachel", slots[0].label)
    }

    @Test
    fun `skips lines with an empty label or id`() {
        val slots = CloudTtsPreferences.parseVoiceSlots("|abc\nRachel|\nRachel|abc")
        assertEquals(1, slots.size)
        assertEquals("abc", slots[0].id)
    }

    @Test
    fun `disambiguates slug collisions with a numeric suffix`() {
        val slots = CloudTtsPreferences.parseVoiceSlots("Rachel|abc\nRachel|def\nRachel!|ghi")
        assertEquals(listOf("rachel", "rachel-2", "rachel-3"), slots.map { it.slug })
    }

    @Test
    fun `slugifies non-alphanumeric characters`() {
        val slots = CloudTtsPreferences.parseVoiceSlots("Deep Voice (EN)|xyz")
        assertEquals("deep-voice-en", slots[0].slug)
    }
}
