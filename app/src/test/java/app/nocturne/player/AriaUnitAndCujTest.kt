package app.nocturne.player

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import app.nocturne.player.data.model.PlaybackMode
import app.nocturne.player.data.prefs.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AriaUnitAndCujTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val shadowSetupRule = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            runBlocking {
                SettingsRepository(app).updateSettings { it.copy(activePlaybackMode = PlaybackMode.OFFLINE) }
            }
            val shadowApp = Shadows.shadowOf(app)
            shadowApp.setBindServiceCallsOnServiceConnectedDirectly(false)
            shadowApp.declareActionUnbindable("androidx.media3.session.MediaLibraryService")
            shadowApp.declareActionUnbindable("androidx.media3.session.MediaSessionService")
            if (Build.VERSION.SDK_INT >= 33) {
                shadowApp.grantPermissions(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                shadowApp.grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(shadowSetupRule).around(composeRule)

    @Test
    fun navigationHomeCustomizeAndPaperThemeSwitching_cujWorks() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_tab_HOME").assertIsDisplayed()
        composeRule.onNodeWithTag("home_customize_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Customize Journal").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_LIBRARY").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("MUSIC LIBRARY").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_SEARCH").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("global_search_input").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_PLAYLISTS").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_playlist_btn").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_SETTINGS").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen_list").performScrollToIndex(2)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Charcoal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AMOLED").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Warm").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_screen_list").performScrollToIndex(1)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open_eq_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Equalizer & Processing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close Audio FX").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen_list").assertIsDisplayed()
    }

    @Test
    fun onlineModeSwitcher_navigationAndBackHandling_cujWorks() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_tab_HOME").assertIsDisplayed()
        composeRule.onNodeWithTag("mode_switch_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("mode_switch_online").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("online_home_screen_list").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_LIBRARY").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("online_lib_sec_FAVORITES").assertIsDisplayed()
        composeRule.onNodeWithTag("online_lib_sec_HISTORY").assertIsDisplayed()
        composeRule.onNodeWithTag("online_lib_sec_QUEUE").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_SEARCH").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("online_search_input").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_PLAYLISTS").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_online_playlist_btn").assertIsDisplayed()

        // Pressing system Back from secondary online tab returns to Online Home
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("online_home_screen_list").assertIsDisplayed()

        // Switch back to Offline Music smoothly
        composeRule.onNodeWithTag("mode_switch_offline").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_customize_btn").assertIsDisplayed()
    }
}
