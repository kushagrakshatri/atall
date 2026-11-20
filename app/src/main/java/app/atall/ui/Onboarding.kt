package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.atall.core.AgentProfile
import app.atall.core.Main
import app.atall.ui.theme.AppTheme

/** Sign-in progress as the welcome step shows it. */
sealed interface SignInUi {
    data object Idle : SignInUi
    data object Waiting : SignInUi
    data object Done : SignInUi
    data class Failed(val message: String) : SignInUi
}

@Composable
private fun StepScaffold(step: Int, content: @Composable () -> Unit, actions: @Composable () -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        Progress(step)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 40.dp)) { content() }
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun Progress(step: Int) {
    val c = AppTheme.colors
    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            Box(
                Modifier.height(6.dp).width(if (i == step) 20.dp else 6.dp).clip(CircleShape)
                    .background(if (i <= step) c.ink else c.hairlineStrong),
            )
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = AppTheme.type.display, color = AppTheme.colors.ink)

@Composable
private fun Body(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = AppTheme.type.body, color = AppTheme.colors.ink2)
}

// ------------------------------------------------------------------------------------------------ 1. Welcome

@Composable
fun WelcomeStep(signIn: SignInUi, onContinue: () -> Unit, onCancelSignIn: () -> Unit, onLater: () -> Unit) {
    val c = AppTheme.colors
    StepScaffold(0, content = {
        Box(Modifier.height(140.dp).fillMaxWidth()) {
            MainAvatar(72.dp, Modifier.align(Alignment.CenterStart))
            AddAvatar(40.dp, Modifier.align(Alignment.CenterStart).offset(x = 96.dp))
        }
        Spacer(Modifier.height(24.dp))
        Title("All your agents, one thread")
        Body("Talk to ${Main.NAME}. @mention any agent you add to ask it directly.")
        Spacer(Modifier.height(24.dp))
        for (p in listOf("@mention an agent to ask it directly.", "Text agents, chat apps and more, side by side.", "Each agent keeps its own account.")) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.Check, c.ink, size = 20.dp)
                Spacer(Modifier.width(14.dp))
                Text(p, style = AppTheme.type.body, color = c.ink)
            }
        }
    }, actions = {
        when (signIn) {
            is SignInUi.Failed -> Text(signIn.message, Modifier.fillMaxWidth(), style = AppTheme.type.caption, color = c.danger)
            else -> Text("You'll sign in on OpenAI's page. ${Main.NAME} never sees your password.", Modifier.fillMaxWidth(), style = AppTheme.type.caption, color = c.ink3)
        }
        when (signIn) {
            SignInUi.Waiting -> {
                PrimaryButton("Waiting for ChatGPT…", {}, enabled = false)
                TextButton("Cancel", onCancelSignIn)
            }
            is SignInUi.Failed -> {
                PrimaryButton("Try again", onContinue)
                TextButton("Set up later", onLater)
            }
            else -> {
                PrimaryButton("Continue with ChatGPT", onContinue)
                TextButton("Set up later", onLater)
            }
        }
    })
}

// ------------------------------------------------------------------------------------------------ 2. Notification access

@Composable
fun AccessStep(granted: Boolean, triedOnce: Boolean, restrictedApplies: Boolean, onOpenSettings: () -> Unit, onOpenAppInfo: () -> Unit, onContinue: () -> Unit, onNotNow: () -> Unit) {
    val c = AppTheme.colors
    StepScaffold(1, content = {
        Box(Modifier.size(80.dp).clip(AppTheme.radii.popover).background(c.sunken), contentAlignment = Alignment.Center) { Icon(Glyph.Bell, c.ink, size = 32.dp) }
        Spacer(Modifier.height(24.dp))
        Title("Let replies reach the thread")
        Body("Your agents answer by text or in their own apps. To show those replies here, Android needs to let Atall read your notifications.")
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().clip(AppTheme.radii.card).background(c.surface).hairlineBorder(AppTheme.radii.card, strong = false)) {
            StepRow(1, done = granted, active = true, title = "Turn on notification access", body = { Text("Find Atall in the list and switch it on.", style = AppTheme.type.body2, color = c.ink2) })
            if (restrictedApplies) {
                HorizontalDivider(color = c.hairline)
                StepRow(2, done = false, active = triedOnce && !granted, title = "Greyed out?", body = {
                    Text(buildAnnotatedString {
                        append("Open App info, tap ⋮, then ")
                        withStyle(SpanStyle(fontWeight = FontWeight(600), color = c.ink)) { append("Allow restricted settings") }
                        append(". Come back and try step 1 again.")
                    }, style = AppTheme.type.body2, color = c.ink2)
                    if (triedOnce && !granted) { Spacer(Modifier.height(10.dp)); Chip("Open App info", onOpenAppInfo) }
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Glyph.Lock, c.ink3, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Text("Atall only reads messages from agents you add. It ignores everything else.", style = AppTheme.type.caption, color = c.ink3)
        }
    }, actions = {
        if (granted) PrimaryButton("Continue", onContinue) else PrimaryButton("Open settings", onOpenSettings)
        if (!granted) TextButton("Not now", onNotNow)
    })
}

@Composable
private fun StepRow(n: Int, done: Boolean, active: Boolean, title: String, body: @Composable () -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(18.dp)) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (done) c.success else if (active) c.ink else c.surface)
                .then(if (done || active) Modifier else Modifier.hairlineBorder(CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Glyph.Check, c.onInk, size = 16.dp)
            else Text("$n", style = AppTheme.type.label, color = if (active) c.onInk else c.ink2)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.type.body, color = c.ink)
            Spacer(Modifier.height(2.dp))
            body()
        }
    }
}

// ------------------------------------------------------------------------------------------------ 3. Add an agent

@Composable
fun AddAgentStep(form: AgentFormState, existing: List<AgentProfile>, onAdd: () -> Unit, onSkip: () -> Unit) {
    StepScaffold(2, content = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MainAvatar(40.dp)
            Spacer(Modifier.width(6.dp))
            if (form.name.isNotBlank()) { PeerAvatar(form.logo(existing), hueOf(form.hue), 40.dp); Spacer(Modifier.width(6.dp)) }
            AddAvatar(40.dp)
        }
        Spacer(Modifier.height(24.dp))
        Title("Add an agent")
        Body("Any agent you text with. Use the name you'll @mention.")
        Spacer(Modifier.height(24.dp))
        AgentForm(form, existing)
    }, actions = {
        PrimaryButton(if (form.name.isBlank()) "Add agent" else "Add ${form.name.trim()}", onAdd, enabled = form.name.isNotBlank())
        TextButton("Skip for now", onSkip)
    })
}

// ------------------------------------------------------------------------------------------------ previews

@Preview(name = "Onboarding 1 · welcome", widthDp = 412, heightDp = 900)
@Composable
private fun WelcomePreview() = PreviewTheme { WelcomeStep(SignInUi.Idle, {}, {}, {}) }

@Preview(name = "Onboarding 1 · sign-in failed", widthDp = 412, heightDp = 900)
@Composable
private fun WelcomeFailedPreview() = PreviewTheme { WelcomeStep(SignInUi.Failed("Sign-in didn't finish. Try again when you're ready."), {}, {}, {}) }

@Preview(name = "Onboarding 2 · access", widthDp = 412, heightDp = 900)
@Composable
private fun AccessPreview() = PreviewTheme { AccessStep(granted = false, triedOnce = true, restrictedApplies = true, {}, {}, {}, {}) }

@Preview(name = "Onboarding 3 · add agent", widthDp = 412, heightDp = 900)
@Composable
private fun AddAgentPreview() = PreviewTheme { AddAgentStep(AgentFormState("Nova", address = "(408) 555-0142"), emptyList(), {}, {}) }
