package dev.hotword.android.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantSelectionTest {
    @Test fun parsesSystemSelectedComponent() {
        assertEquals("com.example.assistant",
            AssistantSelection.fromSetting("com.example.assistant/.VoiceService"))
        assertEquals("com.example.assistant",
            AssistantSelection.fromSetting("com.example.assistant"))
    }

    @Test fun rejectsEmptyAndMalformedSelections() {
        assertNull(AssistantSelection.fromSetting(null))
        assertNull(AssistantSelection.fromSetting(""))
        assertNull(AssistantSelection.fromSetting("not a package"))
    }

    @Test fun rejectsResolverActivities() {
        assertTrue(AssistantSelection.isResolver("com.android.internal.app.ResolverActivity"))
        assertTrue(AssistantSelection.isResolver("android.app.ChooserActivity"))
        assertFalse(AssistantSelection.isResolver("com.example.voice.AssistActivity"))
    }
}
