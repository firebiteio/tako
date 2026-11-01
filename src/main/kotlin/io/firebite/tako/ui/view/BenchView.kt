package io.firebite.tako.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.ui.MessageDialogBuilder
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.duration
import io.firebite.tako.ui.elapsed
import io.firebite.tako.ui.money
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.state.runName
import io.firebite.tako.ui.t
import io.firebite.tako.ui.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A bench's runs side by side: what each changed, cost and took. One run's changes can be applied. */
@Composable
fun BenchView(workspace: Workspace, modifier: Modifier) {
  val bench = workspace.benches.firstOrNull { it.id == workspace.shownBench } ?: return
  OverlayView(Icons.workflow, bench.title, { workspace.shownBench = null }, modifier, actions = {
    IconBtn(Icons.refresh, t("bench.refresh"), iconSize = 14.dp) { workspace.openBench(bench.id) }
  }) {
    ViewNote(t("bench.note", bench.baseCommit))
    val runs = produceState<List<ChatMessage.RunChanges>?>(null, bench.id, workspace.benchLoads) {
      this.value = null
      this.value = withContext(Dispatchers.IO) { workspace.service.compareBench(bench.id) }
    }.value
    if (runs == null) {
      ViewNote(t("bench.reading"))
      return@OverlayView
    }
    Table(listOf(t("bench.run"), t("bench.status"), t("bench.files"), t("bench.lines"), t("usage.cost"),
      t("usage.tokens"), t("bench.time")), runs.mapIndexed { index, run -> runRow(workspace, bench, run, index + 1) })
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
      OutlineButton(t("bench.discard"), color = colors.del) { confirmDiscard(workspace, bench) }
    }
  }
}

/** @param index the run's number, from 1 */
private fun runRow(workspace: Workspace, bench: ChatMessage.BenchItem, run: ChatMessage.RunChanges,
  index: Int): TableRow {
  val thread = workspace.threads[bench.threads.getOrNull(index - 1)]
  val name = thread?.runName(index) ?: "#$index"
  val error = run.error
  if (error != null) {
    return TableRow(listOf(name, error, "", "", "", "", ""), muted = true)
  }
  val cells = listOf(name, runStatus(thread), run.files.toString(), "+" + run.added + " −" + run.removed,
    thread?.let { money(it.costUsd) } ?: "", thread?.let { tokens(it.tokens) } ?: "", runTime(thread, workspace.now))
  val actions: (@Composable () -> Unit)? = if (run.files > 0) {
    {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlineButton(t("bench.diff")) { workspace.service.showRunDiff(bench.id, index) }
        PrimaryButton(t("bench.pick")) {
          if (MessageDialogBuilder.yesNo(t("bench.pick"), t("bench.pick.confirm", index)).guessWindowAndAsk()) {
            workspace.service.pickWinner(bench.id, index)
            workspace.shownBench = null
          }
        }
      }
    }
  } else {
    null
  }
  val open = thread?.let {
    {
      workspace.shownBench = null
      workspace.open(it)
    }
  }
  return TableRow(cells, actions = actions, onClick = open)
}

private fun runStatus(thread: ThreadState?): String = when {
  thread == null -> t("bench.status.closed")
  thread.approvals.isNotEmpty() -> t("bench.status.waiting")
  thread.setupFailed -> t("bench.status.setup.failed")
  thread.busy -> t("bench.status.working")
  thread.failed -> t("bench.status.failed")
  else -> t("bench.status.done")
}

private fun runTime(thread: ThreadState?, now: Long): String = when {
  thread == null -> ""
  thread.firstTurnMillis > 0 -> duration(thread.firstTurnMillis)
  thread.busy -> elapsed(thread.startedAt, now)
  else -> ""
}
