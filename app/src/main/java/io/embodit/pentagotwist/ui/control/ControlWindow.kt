package io.embodit.pentagotwist.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.pentagotwist.BuildConfig
import io.embodit.pentagotwist.R
import io.embodit.pentagotwist.domain.ConfirmKind
import io.embodit.pentagotwist.domain.Engine
import io.embodit.pentagotwist.domain.GameSetup
import io.embodit.pentagotwist.domain.Language
import io.embodit.pentagotwist.domain.Mode
import io.embodit.pentagotwist.domain.Outcome
import io.embodit.pentagotwist.domain.Pentago
import io.embodit.pentagotwist.domain.Phase
import io.embodit.pentagotwist.domain.PlayState
import io.embodit.pentagotwist.domain.SIZES
import io.embodit.pentagotwist.domain.SeatPick
import io.embodit.pentagotwist.domain.Settings
import io.embodit.pentagotwist.domain.TILTS
import io.embodit.pentagotwist.platform.Graph
import io.embodit.pentagotwist.platform.TAG
import io.embodit.pentagotwist.ui.Page
import io.embodit.pentagotwist.ui.Ui
import io.embodit.pentagotwist.ui.board.MarbleDot
import io.embodit.pentagotwist.ui.sideName
import io.embodit.pentagotwist.ui.statusText
import io.embodit.pentagotwist.ui.theme.PtColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer to the right of the board): all 2D UI, four pages switched by the native
 * SpatialUI TabBar (D8): 对局 / 新局 / 规则 / 设置. Every page has the same header with [静音]; its content fills the window
 * (blank <= 25 %); confirmations and the pause card are whole-page cards that lock the volume. The game page carries a
 * small copy of the board (D43: placing and the eight twists have panel keys; the same GameFlow calls as a pinch in 3D).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.SETUP -> Ui.page = Page.NEW
            Phase.PLACE, Phase.THINKING, Phase.TWIST -> if (Ui.page == Page.NEW && !Ui.newConfirm) Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.exitConfirm, Ui.newConfirm) { graph.flow.uiLocked = Ui.exitConfirm || Ui.newConfirm }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page != Page.NEW) Ui.newConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: it does not see this window's localized context, so the labels are
    // resolved here (app language) and passed in as plain strings (ui_gates tabbar-lang, build.md 2026-11-05 ②).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.NEW, R.drawable.ic_tab_new, stringResource(R.string.tab_new)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

/** Every page: header at the top, last row at the bottom, blocks share the height in between (blank <= 25 %). */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

/** Page header (56): a title, a small line under it and [静音] on every page (D15). */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph, sub: String? = null, side: Int? = null, thinking: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (side != null) { MarbleDot(side, 22, lit = true); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge)
            if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        }
        if (thinking) { CircularProgressIndicator(modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
        ToggleButton(stringResource(R.string.btn_mute), "$tag/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } })
    }
}

@Composable
private fun levelName(level: Int): String = stringResource(when (level) { Engine.EASY -> R.string.level_1; Engine.MEDIUM -> R.string.level_2; else -> R.string.level_3 })

@Composable
private fun modeLine(state: PlayState): String {
    val cfg = state.config ?: return ""
    val mode = if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.mode_2p) else stringResource(R.string.mode_ai_level, levelName(cfg.aiLevel))
    val seg = when (state.phase) {
        Phase.TWIST, Phase.TWISTING -> stringResource(R.string.seg_twist)
        Phase.RESULT -> null
        else -> stringResource(R.string.seg_place)
    }
    return listOfNotNull(mode, stringResource(R.string.plate_move, state.moveNo), seg).joinToString(" · ")
}

@Composable
private fun turnTitle(state: PlayState): String {
    val cfg = state.config ?: return ""
    val side = state.shownSide
    val name = sideName(side)
    return if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.turn_of, name)
    else stringResource(R.string.turn_of_who, name, if (side == cfg.humanSide) stringResource(R.string.who_you) else stringResource(R.string.who_ai, levelName(cfg.aiLevel)))
}

// ------------------------------------------------------------------ Game

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.config == null || state.phase in setOf(Phase.SETUP, Phase.LOADING)) {
        PageColumn {
            Header(stringResource(R.string.tab_play), "play", settings, graph)
            RulesDiagram(Modifier.fillMaxWidth().height(260.dp))
            Txt(stringResource(R.string.play_empty), "play/empty", maxLines = 2, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            PrimaryButton(stringResource(R.string.btn_start), "play/new", onClick = { Ui.page = Page.NEW }, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val confirm = state.confirm
    if (confirm != null) { ConfirmPage(confirm, settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() }); return }
    if (state.phase == Phase.RESULT) { ResultPage(state, settings, graph); return }
    if (state.paused) { PausePage(settings, graph); return }
    PageColumn {
        Header(turnTitle(state), "play", settings, graph, sub = modeLine(state), side = state.shownSide, thinking = state.phase == Phase.THINKING)
        StatusCard(state)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            MiniBoard(state, graph)
            Column(Modifier.weight(1f).height(422.dp), verticalArrangement = Arrangement.SpaceBetween) {
                if (state.canRedo) SecondaryButton(stringResource(R.string.btn_redo), "play/redo", onClick = { flow.redo() }, icon = R.drawable.ic_redo, modifier = Modifier.fillMaxWidth())
                val retract = state.phase == Phase.TWIST && state.pending != null && state.humansTurn
                SecondaryButton(stringResource(if (retract) R.string.btn_retract else R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = state.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.fillMaxWidth())
                if (!state.canUndo) Txt(undoReason(state), "play/undo-why", style = PicoTheme.typography.bodySmall, color = PtColors.InkSecondary, maxLines = 2, modifier = Modifier.fillMaxWidth())
                ToggleButton(stringResource(if (state.hintOn) R.string.hint_on else R.string.hint_off), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.btn_pause), "play/pause", onClick = { flow.pause(true) }, icon = R.drawable.ic_pause, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.btn_resign), "play/resign", onClick = { flow.requestConfirm(ConfirmKind.RESIGN) }, icon = R.drawable.ic_flag, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.btn_restart), "play/restart", onClick = { flow.requestConfirm(ConfirmKind.RESTART) }, icon = R.drawable.ic_replay, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** D43: why [悔一回合] is grey. */
@Composable
private fun undoReason(state: PlayState): String = stringResource(when {
    state.phase == Phase.THINKING || !state.humansTurn -> R.string.undo_why_ai
    state.phase == Phase.TWISTING || state.phase == Phase.PLACING -> R.string.undo_why_busy
    else -> R.string.undo_why_none
})

/** The one sentence (long form; the plate carries the short form of the same status). */
@Composable
private fun StatusCard(state: PlayState) {
    Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(18.dp)).background(PtColors.TagFace).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        Txt(statusText(state, short = false), "play/status", style = PicoTheme.typography.titleMedium, color = if (state.alert != null) PtColors.ErrorText else PtColors.Ink, maxLines = 2)
    }
}

/** A >= 48 dp tap target drawn by us (cells and twist keys of the mini board); one semantic button each (pico-cli interact). */
@Composable
private fun KeyBox(label: String, tag: String, enabled: Boolean, bg: Color, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { Log.i(TAG, "KEY $tag"); onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The board in the window (D43): four 3 x 3 tiles, each with its two twist keys under it. A cell = GameFlow.tapCell (S03 place,
 * S07 pick that block / take the marble back); a key = GameFlow.twist (S07). Keys are grey outside S07 and say why on the
 * status line when pressed ("先放一颗").
 */
@Composable
private fun MiniBoard(state: PlayState, graph: Graph) {
    val board = state.board
    val human = state.humansTurn && state.inGame
    val twistOn = state.phase == Phase.TWIST && human
    Column(Modifier.width(314.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (rowQ in listOf(listOf(0, 1), listOf(2, 3))) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (q in rowQ) {
                    val lit = twistOn && (state.picked == null || state.picked == q)
                    Column(
                        Modifier.width(152.dp).clip(RoundedCornerShape(12.dp))
                            .border(2.dp, if (lit) PtColors.Tertiary else Color.Transparent, RoundedCornerShape(12.dp)),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PtColors.Walnut), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (r in 2 downTo 0) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (k in 0..2) {
                                    val c = Pentago.at(Pentago.quadCol(q) - 1 + k, Pentago.quadRow(q) - 1 + r)
                                    MiniCell(c, board.owner(c), state, human) { graph.flow.tapCell(c) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (dir in listOf(Pentago.CCW, Pentago.CW)) {
                                val label = stringResource(if (dir == Pentago.CCW) R.string.key_ccw else R.string.key_cw)
                                val name = "${Pentago.quadName(q)} $label"
                                val hinted = state.hintOn && state.hint?.quad == q && state.hint.dir == dir && twistOn
                                KeyBox(name, "twist/${Pentago.quadName(q)}/$dir", human, if (twistOn) (if (hinted) PtColors.Teal3D else PtColors.Primary) else Color(0xFFE3DED6), Modifier.weight(1f).height(48.dp), { graph.flow.twist(q, dir) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val fg = if (!twistOn) PtColors.InkSecondary else if (hinted) PtColors.Ink else PtColors.OnPrimary
                                        Icon(painter = painterResource(if (dir == Pentago.CCW) R.drawable.ic_rotate_left else R.drawable.ic_rotate_right), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
                                        Spacer(Modifier.width(4.dp))
                                        Txt(label, "play/twist-key", style = PicoTheme.typography.labelMedium, color = fg)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniCell(c: Int, owner: Int, state: PlayState, enabled: Boolean, onClick: () -> Unit) {
    val name = Pentago.name(c)
    val hinted = state.hintOn && state.hint?.cell == c && state.phase == Phase.PLACE
    KeyBox(name, "cell/$name", enabled, Color.Transparent, Modifier.size(48.dp), onClick) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(PtColors.WalnutDeep), contentAlignment = Alignment.Center) {
            // the black marble gets a light maple rim on the deep dimple (ΔL* would be ~10 without it)
            if (owner >= 0) Box(Modifier.size(34.dp).clip(CircleShape).background(if (owner == Pentago.BLACK) PtColors.MarbleBlack else PtColors.MarbleWhite)
                .border(if (c == state.pending) 3.dp else 2.dp, if (c == state.pending) PtColors.Tertiary else PtColors.Maple, CircleShape))
            if (hinted) Box(Modifier.size(40.dp).border(3.dp, PtColors.Teal3D, CircleShape))
        }
    }
}

/** S10: a whole-page card; the AI waits and the volume is locked until [继续]. */
@Composable
private fun PausePage(settings: Settings, graph: Graph) {
    val flow = graph.flow
    PageColumn {
        Header(stringResource(R.string.pause_title), "pause", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(PtColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(56.dp), tint = PtColors.Ink)
            Txt(stringResource(R.string.pause_line), "pause/line", style = PicoTheme.typography.bodyLarge, color = PtColors.InkSecondary, maxLines = 2)
            PrimaryButton(stringResource(R.string.btn_resume), "pause/resume", onClick = { flow.pause(false) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.tab_new), "pause/new", onClick = { flow.pause(false); Ui.page = Page.NEW }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.tab_rules), "pause/rules", onClick = { Ui.page = Page.RULES }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.tab_settings), "pause/settings", onClick = { Ui.page = Page.SETTINGS }, modifier = Modifier.weight(1f))
            }
            SecondaryButton(stringResource(R.string.settings_exit), "pause/leave", onClick = { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }, icon = R.drawable.ic_exit, danger = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** S13: a whole-page confirmation card; the volume is locked while it is open (GameFlow ignores 3D taps). */
@Composable
private fun ConfirmPage(kind: ConfirmKind?, settings: Settings, graph: Graph, onYes: () -> Unit, onNo: () -> Unit, exit: Boolean = false, newGame: Boolean = false) {
    val (q, line, yes) = when {
        newGame -> Triple(R.string.confirm_new_game, R.string.confirm_new_line, R.string.confirm_yes_new)
        exit -> Triple(R.string.confirm_exit, R.string.confirm_exit_line, R.string.confirm_yes_exit)
        kind == ConfirmKind.RESIGN -> Triple(R.string.confirm_resign, R.string.confirm_resign_line, R.string.confirm_yes_resign)
        kind == ConfirmKind.UNDO_2P -> Triple(R.string.confirm_undo2p, R.string.confirm_undo2p_line, R.string.confirm_yes_undo2p)
        else -> Triple(R.string.confirm_restart, R.string.confirm_restart_line, R.string.confirm_yes_restart)
    }
    val icon = when { exit -> R.drawable.ic_exit; kind == ConfirmKind.RESIGN && !newGame -> R.drawable.ic_flag; kind == ConfirmKind.UNDO_2P -> R.drawable.ic_undo; else -> R.drawable.ic_replay }
    PageColumn {
        Header(stringResource(when { exit -> R.string.settings_title; newGame -> R.string.new_title; else -> R.string.tab_play }), "confirm", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(PtColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = PtColors.Ink)
            Txt(stringResource(q), "confirm/q", style = PicoTheme.typography.titleLarge.copy(fontSize = 30.sp), color = PtColors.Ink, maxLines = 2)
            Txt(stringResource(line), "confirm/line", style = PicoTheme.typography.bodyLarge, color = PtColors.InkSecondary, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(72.dp))
                SecondaryButton(stringResource(yes), "confirm/yes", danger = true, onClick = onYes, modifier = Modifier.weight(1f).height(72.dp))
            }
        }
        Txt(stringResource(R.string.lock_line), "confirm/lock", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** S09: the result card (whole page, [静音] still in the header). No coordinates on it (D44). */
@Composable
private fun ResultPage(state: PlayState, settings: Settings, graph: Graph) {
    val cfg = state.config ?: return
    val w = state.winner
    val title = stringResource(when {
        w == null -> R.string.result_draw
        cfg.mode == Mode.AI && w == cfg.humanSide -> R.string.result_you_win
        cfg.mode == Mode.AI -> R.string.result_you_lose
        w == Pentago.BLACK -> R.string.result_black_win
        else -> R.string.result_white_win
    })
    val reason = stringResource(when (state.outcome) {
        Outcome.WIN_PLACE -> R.string.reason_place
        Outcome.WIN_TWIST -> R.string.reason_twist
        Outcome.DRAW_DOUBLE -> R.string.reason_double
        Outcome.DRAW_FULL -> R.string.reason_full
        else -> R.string.reason_resign
    })
    val secs = state.elapsedMs / 1000
    val time = "%d:%02d".format(secs / 60, secs % 60)
    PageColumn {
        Header(stringResource(R.string.tab_play), "result", settings, graph, sub = modeLine(state))
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).clip(RoundedCornerShape(28.dp)).background(PtColors.TagFace).padding(22.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (w != null) { MarbleDot(w, 30); Spacer(Modifier.width(14.dp)) }
                Txt(title, "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), color = PtColors.Ink)
            }
            Txt(reason, "result/reason", style = PicoTheme.typography.bodyLarge, color = PtColors.InkSecondary, maxLines = 2)
            val cells = listOf(
                R.string.res_moves to state.moves.size.toString(), R.string.res_time to time, R.string.res_hints to state.hintsUsed.toString(),
                R.string.res_level to if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.mode_2p) else levelName(cfg.aiLevel),
            )
            cells.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, value) -> StatCell(stringResource(label), value, Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { graph.flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
            }
        }
        Txt(stringResource(R.string.result_footnote), "result/footnote", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).border(1.dp, PtColors.InkSecondary.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "result/stat", style = PicoTheme.typography.bodyMedium, color = PtColors.InkSecondary, modifier = Modifier.weight(1f))
        Txt(value, "result/stat-v", style = PicoTheme.typography.titleMedium, color = PtColors.Ink)
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    fun start() { Ui.newConfirm = false; graph.flow.newGame(setup); Ui.page = Page.PLAY }
    if (Ui.newConfirm) {
        // D44: starting over drops the running game, ask first (whole-page card).
        ConfirmPage(null, settings, graph, onYes = { start() }, onNo = { Ui.newConfirm = false }, newGame = true)
        return
    }
    PageColumn {
        Header(stringResource(R.string.new_title), "new", settings, graph)
        if (state.savedAvailable && state.phase == Phase.SETUP) {
            SecondaryButton(stringResource(R.string.btn_continue, state.savedMoves), "new/continue", onClick = { graph.flow.continueSaved(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth().height(56.dp))
        }
        Group(stringResource(R.string.new_who), "new/opponent") {
            // gate:allow tabs-segment same-page option of the new game (titled group: opponent / seat / level), not a page switch
            Segments(listOf(Mode.AI to stringResource(R.string.vs_ai), Mode.TWO_PLAYER to stringResource(R.string.mode_2p)), setup.mode, "new/opponent", { m -> set { it.copy(mode = m) } }, Modifier.fillMaxWidth())
        }
        if (setup.mode == Mode.AI) {
            Group(stringResource(R.string.new_seat), "new/seat") {
                // gate:allow tabs-segment same-page option of the new game (titled group: opponent / seat / level), not a page switch
                Segments(listOf(SeatPick.BLACK to stringResource(R.string.seat_black), SeatPick.WHITE to stringResource(R.string.seat_white), SeatPick.RANDOM to stringResource(R.string.seat_random)),
                    setup.seat, "new/seat", { v -> set { it.copy(seat = v) } }, Modifier.fillMaxWidth())
            }
            Group(stringResource(R.string.new_level), "new/level") {
                // gate:allow tabs-segment same-page option of the new game (titled group: opponent / seat / level), not a page switch
                Segments(listOf(Engine.EASY to stringResource(R.string.level_1), Engine.MEDIUM to stringResource(R.string.level_2), Engine.HARD to stringResource(R.string.level_3)),
                    setup.aiLevel, "new/level", { l -> set { it.copy(aiLevel = l) } }, Modifier.fillMaxWidth(),
                    subs = listOf(stringResource(R.string.level_1_sub), stringResource(R.string.level_2_sub), stringResource(R.string.level_3_sub)))
            }
        } else {
            Column(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp)).background(PtColors.TagFace).padding(18.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarbleDot(Pentago.BLACK, 22); Spacer(Modifier.width(8.dp)); Txt(stringResource(R.string.seat_black), "new/2p-b", style = PicoTheme.typography.titleMedium, color = PtColors.Ink)
                    Spacer(Modifier.width(24.dp))
                    MarbleDot(Pentago.WHITE, 22); Spacer(Modifier.width(8.dp)); Txt(stringResource(R.string.seat_white_2p), "new/2p-w", style = PicoTheme.typography.titleMedium, color = PtColors.Ink)
                }
                Txt(stringResource(R.string.new_2p_note), "new/2p-note", style = PicoTheme.typography.bodyMedium, color = PtColors.InkSecondary, maxLines = 3)
            }
        }
        PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
            if (state.hasProgress) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW_GAME S13") } else start()
        }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.new_note), "new/note", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

/** A titled option group: title 20 + a full-width segment (same-page option switch, never a page switch). */
@Composable
private fun Group(title: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, "$tag/title", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) { content() }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, settings: Settings, graph: Graph) {
    var symmetry by remember { mutableStateOf(false) }
    PageColumn {
        Header(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RulesDiagram(Modifier.size(width = 150.dp, height = 150.dp))
                Txt(stringResource(R.string.diagram_caption), "rules/caption", style = PicoTheme.typography.bodySmall, color = PtColors.InkSecondary, maxLines = 2, align = TextAlign.Center, modifier = Modifier.width(150.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(PtColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = PtColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodySmall, maxLines = 4, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Txt(stringResource(if (symmetry) R.string.symmetry_title else R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val lines = if (symmetry) listOf(R.string.symmetry_1, R.string.symmetry_2) else listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4)
            lines.forEach { id ->
                Box(Modifier.fillMaxWidth().height(if (symmetry) 108.dp else 50.dp).clip(RoundedCornerShape(12.dp)).background(PtColors.TagFace).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Txt(stringResource(id), "rules/mistake", style = if (symmetry) PicoTheme.typography.bodyMedium else PicoTheme.typography.bodySmall, color = PtColors.Ink, maxLines = if (symmetry) 4 else 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleButton(stringResource(R.string.btn_symmetry), "rules/symmetry", on = symmetry, onClick = { symmetry = !symmetry }, modifier = Modifier.weight(1f))
            // S01 [开始]: the default game is already set up; this only goes to the game page and ends the welcome.
            PrimaryButton(stringResource(if (state.welcome) R.string.btn_start else R.string.btn_back_game), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY }, modifier = Modifier.weight(1f))
        }
    }
}

/** Four blocks, the top-left one with a curved arrow, five white marbles lit along a row. */
@Composable
private fun RulesDiagram(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(PtColors.Maple)) {
        val side = minOf(size.width, size.height)
        val x0 = (size.width - side) / 2 + side * 0.06f; val y0 = (size.height - side) / 2 + side * 0.06f
        val board = side * 0.88f
        val block = board * 0.49f; val gap = board * 0.02f
        val pitch = block / 3f
        for (q in 0..3) {
            val bx = x0 + (q % 2) * (block + gap); val by = y0 + (q / 2) * (block + gap)
            drawRoundRect(PtColors.Walnut, topLeft = Offset(bx, by), size = androidx.compose.ui.geometry.Size(block, block), cornerRadius = androidx.compose.ui.geometry.CornerRadius(block * 0.08f))
            for (i in 0..2) for (j in 0..2) drawCircle(PtColors.WalnutDeep, pitch * 0.36f, Offset(bx + (i + 0.5f) * pitch, by + (j + 0.5f) * pitch))
        }
        fun at(col: Int, row: Int) = Offset(x0 + col * pitch + (if (col >= 3) gap else 0f) + pitch / 2, y0 + row * pitch + (if (row >= 3) gap else 0f) + pitch / 2)
        for (c in 0..4) drawCircle(PtColors.MarbleWhite, pitch * 0.32f, at(c, 4))
        for (c in 0..4) drawCircle(PtColors.Teal3D, pitch * 0.42f, at(c, 4), style = Stroke(pitch * 0.08f))
        listOf(1 to 1, 4 to 2, 3 to 5).forEach { (c, r) -> drawCircle(PtColors.MarbleBlack, pitch * 0.32f, at(c, r)) }
        // the clockwise arrow round block Q1
        val cx = x0 + block / 2; val cy = y0 + block / 2
        drawArc(PtColors.Teal3D, -160f, 120f, false, topLeft = Offset(cx - block * 0.62f, cy - block * 0.62f), size = androidx.compose.ui.geometry.Size(block * 1.24f, block * 1.24f), style = Stroke(pitch * 0.14f))
        val tip = Offset(cx + block * 0.62f * kotlin.math.cos(Math.toRadians(-40.0)).toFloat(), cy + block * 0.62f * kotlin.math.sin(Math.toRadians(-40.0)).toFloat())
        drawCircle(PtColors.Teal3D, pitch * 0.16f, tip)
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(null, settings, graph, onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false }, exit = true)
        return
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Header(stringResource(R.string.settings_title), "settings", settings, graph)
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 120.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 120.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", labelWidth = 120.dp) {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 120.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_tilt), "settings/tilt", labelWidth = 120.dp) {
            Segments(TILTS.map { it to "$it°" }, settings.tilt, "settings/tilt", { v -> upd { it.copy(tilt = v) } }, Modifier.weight(1f), small = true)
        }
        OptionRow(stringResource(R.string.settings_size), "settings/size", labelWidth = 120.dp) {
            Segments(SIZES.map { it to "$it%" }, settings.size, "settings/size", { v -> upd { it.copy(size = v) } }, Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; the confirmation is a whole-page card.
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

