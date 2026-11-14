package app.banzhan.deepseekcontinuation.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.data.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local form callbacks only: these tests never create a real account or contact a model. */
@RunWith(AndroidJUnit4::class)
class AccountUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun registrationKeepsUsernameAndInviteAfterFailureButClearsThePassword() {
        val supplied = AtomicReference<Triple<String, String, String?>?>(null)
        val signedIn = AtomicInteger()
        showAccount(onRegister = { username, password, code ->
            supplied.set(Triple(username, password, code))
            error("合成：公开名额已满，请检查邀请码。")
        }, onSignedIn = { signedIn.incrementAndGet() })
        accountNode("account-register-mode").performClick()
        compose.onNodeWithTag("account-invitation").assertDoesNotExist()
        accountNode("account-username").performTextInput("合成用户")
        accountNode("account-password").performTextInput("  secret-pass  ")
        accountNode("account-password").performImeAction()
        accountNode("account-invitation-toggle").performClick()
        accountNode("account-invitation").performTextInput("SYNTHETIC-INVITE")
        accountNode("account-submit").performClick()
        compose.waitUntil(5_000) { supplied.get() != null }
        assertEquals(Triple("合成用户", "  secret-pass  ", "SYNTHETIC-INVITE"), supplied.get())
        compose.onNodeWithText("合成：公开名额已满，请检查邀请码。").performScrollTo().assertIsDisplayed()
        accountNode("account-username").assertTextContains("合成用户")
        accountNode("account-invitation").assertTextContains("SYNTHETIC-INVITE")
        accountNode("account-password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        accountNode("account-submit").assertIsNotEnabled()
        assertEquals(0, signedIn.get())
        screenshot("registration-failure.png")
    }

    @Test fun pendingLoginDoesNotSubmitTwiceAndOnlySuccessReturns() {
        val attempts = CopyOnWriteArrayList<Pair<String, String>>()
        val release = CompletableDeferred<Unit>()
        val signedIn = AtomicInteger()
        showAccount(onLogin = { username, password ->
            attempts.add(username to password)
            release.await()
        }, onSignedIn = { signedIn.incrementAndGet() })
        accountNode("account-username").performTextInput("local-user")
        accountNode("account-password").performTextInput("my-password")
        accountNode("account-password").performImeAction()
        accountNode("account-submit").performClick()
        compose.waitUntil(5_000) { attempts.size == 1 }
        accountNode("account-submit").assertIsNotEnabled().performTouchInput { click(); click() }
        accountNode("account-register-mode").assertIsNotEnabled()
        assertEquals(listOf("local-user" to "my-password"), attempts.toList())
        assertEquals(0, signedIn.get())
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(5_000) { signedIn.get() == 1 }
        assertEquals(1, attempts.size)
    }

    @Test fun passwordIsNotRestoredWithTheForm() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ContinuationTheme {
                val scope = rememberCoroutineScope()
                AccountTestFrame {
                    AccountScreenContent(AppState(), false, { action -> scope.launch { action() } },
                        { _, _ -> }, { _, _, _ -> }, {}, {})
                }
            }
        }
        accountNode("account-username").performTextInput("restore-user")
        accountNode("account-password").performTextInput("private-pass")
        accountNode("account-password").performImeAction()
        restoration.emulateSavedInstanceStateRestore()
        accountNode("account-username").assertTextContains("restore-user")
        accountNode("account-password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        accountNode("account-submit").assertIsNotEnabled()
    }

    @Test fun signedInAccountShowsOnlyPersonalQuotaAndCanLogOut() {
        val logouts = AtomicInteger()
        val state = mutableStateOf(AppState(
            account = AccountState(currentUser = AccountUser("synthetic-user", "合成账户", "public", "2026-11-14T00:00:00+08:00")),
            quota = CompanionQuota(true, null, null, "synthetic", "", dailyLimit = 987654,
                dailyRemaining = 876543, userDailyLimit = 5, userDailyRemaining = 3,
                dailyReserved = 765432, userDailyReserved = 1, accessMode = "public"),
        ))
        showAccount(state = state, onLogout = {
            logouts.incrementAndGet()
            state.value = state.value.copy(account = AccountState(), quota = null)
        })
        compose.onNodeWithText("合成账户").assertIsDisplayed()
        compose.onNodeWithText("3 / 5 次").assertIsDisplayed()
        listOf("987654", "876543", "765432", "全站").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        screenshot("account-personal-quota.png")
        accountNode("account-logout").performClick()
        compose.waitUntil(5_000) { logouts.get() == 1 }
        accountNode("account-login-mode").assertIsDisplayed()
    }

    @Test fun administratorCanRestoreTheDefaultLimitAndSetOnlyTodaysRemaining() {
        val saved = AtomicReference<Pair<Int?, Int?>?>(null)
        val user = UserUsage("synthetic-user", "public", "2026-11-14T00:00:00+08:00", null,
            9, 2, 1, 6, 3, 1, 0, 0, 0, 0, 0, username = "合成用户", registrationKind = "public", dailyLimitOverride = 9)
        compose.setContent {
            ContinuationTheme {
                AccountTestFrame {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RegisteredUserQuotaCard(user, 5, false) { limit, remaining -> saved.set(limit to remaining) }
                    }
                }
            }
        }
        compose.onNodeWithTag("admin-user-toggle:synthetic-user").performClick()
        compose.onNodeWithTag("admin-user-follow-default:synthetic-user").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("仅今天的剩余次数（选填）").performScrollTo().performTextInput("4")
        compose.onNodeWithTag("admin-user-save:synthetic-user").performScrollTo().performClick()
        assertEquals(null to 4, saved.get())
        compose.onNodeWithText("个人每日免费次数").assertDoesNotExist()
        screenshot("admin-personal-quota.png")
    }

    @Test fun unusedInvitationRemainsVisibleAndCopiesItsCompleteCode() {
        val invitation = AdminRegistrationInvite("synthetic-invite", "SYN-REGISTER-COMPLETE-1234",
            "2026-11-21T00:00:00+08:00", "2026-11-14T00:00:00+08:00")
        val generated = AtomicReference<Pair<Int, Int>?>(null)
        compose.setContent {
            ContinuationTheme {
                var expanded by remember { mutableStateOf(false) }
                AccountTestFrame {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RegistrationInvitationControls(expanded, false, { expanded = !expanded }, {},
                            { count, days -> generated.set(count to days) })
                        if (expanded) RegistrationInvitationCard(invitation)
                    }
                }
            }
        }
        compose.onNodeWithText("SYN-REGISTER-COMPLETE-1234").assertDoesNotExist()
        compose.onNodeWithTag("admin-invitations-toggle").performClick()
        compose.onNodeWithTag("admin-invitations-create").performScrollTo().performClick()
        assertEquals(1 to 7, generated.get())
        compose.onNodeWithText("SYN-REGISTER-COMPLETE-1234").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("到期时间", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("admin-invitation-copy:synthetic-invite").performScrollTo().performClick()
        compose.onNodeWithText("已复制").assertIsDisplayed()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("SYN-REGISTER-COMPLETE-1234", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
        }
        screenshot("admin-unused-invitation.png")
    }

    private fun showAccount(
        state: State<AppState> = mutableStateOf(AppState()),
        onLogin: suspend (String, String) -> Unit = { _, _ -> },
        onRegister: suspend (String, String, String?) -> Unit = { _, _, _ -> },
        onLogout: suspend () -> Unit = {},
        onSignedIn: () -> Unit = {},
    ) {
        compose.setContent {
            ContinuationTheme {
                val scope = rememberCoroutineScope()
                AccountTestFrame {
                    AccountScreenContent(state.value, false, { action -> scope.launch { action() } },
                        onLogin, onRegister, onLogout, {}, onSignedIn)
                }
            }
        }
    }

    private fun accountNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("account-page").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AccountTestFrame(content: @Composable () -> Unit) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("账户") },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Outlined.Checklist, "整理进度") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Mist))
        }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) { content() } }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.waitForIdleSync()
        instrument.uiAutomation.waitForIdle(100, 2_000)
        val bitmap = instrument.uiAutomation.takeScreenshot() ?: error("Could not capture account UI")
        val directory = File(instrument.targetContext.filesDir, "account-ui-qa")
        check(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
