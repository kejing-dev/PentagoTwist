package io.embodit.pentagotwist.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.pentagotwist.domain.Language
import io.embodit.pentagotwist.platform.Graph
import java.util.Locale

/**
 * design-package theme.colorScheme: walnut blocks in a maple frame, obsidian / pearl marbles, teal for handles / hints / the
 * primary button, amber for "the other side" / the twist rim. Button fills use a deeper teal than the 3D teal #2FA69A so white
 * labels reach 4.5:1 (ui-audit contrast table).
 */
object PtColors {
    val Primary = Color(0xFF1D7268)
    val Teal3D = Color(0xFF2FA69A)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFC4EDE6)
    val OnPrimaryContainer = Color(0xFF063B36)
    val Secondary = Color(0xFF6E4A2B)
    val SecondaryContainer = Color(0xFFEBD6B8)
    val OnSecondaryContainer = Color(0xFF3A2410)
    val Tertiary = Color(0xFFD99A2B)
    val OnTertiary = Color(0xFF2B1D05)
    val TertiaryContainer = Color(0xFFFFE2A8)
    val OnTertiaryContainer = Color(0xFF3E2A00)
    val Error = Color(0xFFB8322F)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass / light cards. */
    val ErrorText = Color(0xFF7A1A18)
    val Amber = Color(0xFFD99A2B)

    // 3D / plate / mini board
    val MarbleBlack = Color(0xFF141418)
    val MarbleWhite = Color(0xFFF2EEE6)
    val Maple = Color(0xFFD9B88A)
    val Walnut = Color(0xFF8A5E3B)
    val WalnutDeep = Color(0xFF3E2816)
    val TagFace = Color(0xFFFBF6EC)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun PentagoTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = PtColors.OnPrimary,
        labelPrimary = PtColors.Ink,
        labelSecondary = PtColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = PtColors.Error,
        alert = PtColors.Amber,
        passable = PtColors.Secondary,
        interaction = PtColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
