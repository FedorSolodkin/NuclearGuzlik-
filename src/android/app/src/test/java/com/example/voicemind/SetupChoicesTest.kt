package com.example.voicemind

import org.junit.Assert.*
import org.junit.Test

class SetupChoicesTest {
    @Test fun optionalModelsDoNotBlockACompletedSetup() {
        assertEquals(SetupChoices.Route.APP,SetupChoices.route(false,false,true,true,false))
    }
    @Test fun missingRequiredSpeechModelStillOpensSetup() {
        assertEquals(SetupChoices.Route.INTRO,SetupChoices.route(false,false,false,true,false))
    }
    @Test fun explicitAssistantAndSpeechDownloadsOverrideASkippedSetup() {
        assertEquals(SetupChoices.Route.LLM,SetupChoices.route(false,true,true,true,true))
        assertEquals(SetupChoices.Route.TINY,SetupChoices.route(true,false,true,true,true))
    }
    @Test fun firstLaunchWithMissingModelsExplainsPreparation() {
        assertEquals(SetupChoices.Route.INTRO,SetupChoices.route(false,false,false,false,false))
    }
}
