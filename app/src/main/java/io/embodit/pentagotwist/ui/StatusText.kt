package io.embodit.pentagotwist.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.embodit.pentagotwist.R
import io.embodit.pentagotwist.domain.Mode
import io.embodit.pentagotwist.domain.Outcome
import io.embodit.pentagotwist.domain.Pentago
import io.embodit.pentagotwist.domain.PlayState
import io.embodit.pentagotwist.domain.Reason
import io.embodit.pentagotwist.domain.StatusKey

@Composable
fun sideName(side: Int): String = stringResource(if (side == Pentago.BLACK) R.string.side_black else R.string.side_white)

@Composable
fun quadName(q: Int): String = stringResource(when (q) { 0 -> R.string.quad_1; 1 -> R.string.quad_2; 2 -> R.string.quad_3; else -> R.string.quad_4 })

@Composable
fun reasonText(r: Reason, side: Int): String = when (r) {
    Reason.OCCUPIED -> stringResource(R.string.al_occupied)
    Reason.PLACE_FIRST -> stringResource(R.string.al_place_first)
    Reason.THINKING -> stringResource(R.string.st_thinking, sideName(side))
    Reason.BUSY -> stringResource(R.string.st_twisting, sideName(side))
    Reason.PAUSED -> stringResource(R.string.al_paused)
}

/**
 * The one status sentence (D38 / D45): the move plate shows the short form ([short]), the window the long form. Both read
 * [PlayState.status]; nothing here is fixed text per state.
 */
@Composable
fun statusText(st: PlayState, short: Boolean): String {
    val s = st.status()
    val cfg = st.config
    val who = sideName(s.side)
    return when (s.key) {
        StatusKey.NONE -> ""
        StatusKey.PLACE -> stringResource(if (short) R.string.st_place else R.string.st_place_long, who)
        StatusKey.COACH_1 -> stringResource(if (short) R.string.st_coach1 else R.string.st_coach1_long)
        StatusKey.COACH_2 -> stringResource(if (short) R.string.st_coach2 else R.string.st_coach2_long)
        StatusKey.COACH_3 -> stringResource(if (short) R.string.st_coach3 else R.string.st_coach3_long)
        StatusKey.TWIST -> stringResource(if (short) R.string.st_twist else R.string.st_twist_long, who)
        StatusKey.TWIST_PICKED -> stringResource(if (short) R.string.st_twist_picked else R.string.st_twist_picked_long, who, quadName(s.quad))
        StatusKey.PLACING -> stringResource(R.string.st_placing, who)
        StatusKey.THINKING -> stringResource(R.string.st_thinking, who)
        StatusKey.TWISTING -> stringResource(R.string.st_twisting, who)
        StatusKey.SKIPPED -> stringResource(if (short) R.string.st_skipped else R.string.st_skipped_long)
        StatusKey.ALERT -> s.reason?.let { reasonText(it, s.side) }.orEmpty()
        StatusKey.RESTORED -> stringResource(R.string.st_restored, who)
        StatusKey.PAUSED -> stringResource(R.string.st_paused)
        StatusKey.OVER -> when {
            s.winner == null -> stringResource(R.string.st_draw)
            cfg != null && cfg.mode == Mode.AI && s.winner == cfg.humanSide -> stringResource(if (st.outcome == Outcome.WIN_TWIST && !short) R.string.st_you_win_twist else R.string.st_you_win)
            else -> stringResource(R.string.st_side_wins, sideName(s.winner))
        }
    }
}
