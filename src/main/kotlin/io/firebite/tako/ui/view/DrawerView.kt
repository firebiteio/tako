package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.ui.state.Approval
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.advanceQuestion
import io.firebite.tako.ui.state.answer
import io.firebite.tako.ui.state.approvalDetail
import io.firebite.tako.ui.state.approvalLabel
import io.firebite.tako.ui.state.chooseOption
import io.firebite.tako.ui.state.deny
import io.firebite.tako.ui.state.questionFor
import io.firebite.tako.ui.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import kotlin.math.max

/** How long a picked single-choice answer shows before the next question. */
private const val ANSWER_DELAY_MILLIS = 200L

/** The drawer over the composer where the agent waits for the person: tool approvals, plans and questions. */
@Composable
fun Drawer(thread: ThreadState, approval: Approval) {
  val warning = approval.prompt.kind == PermissionPrompt.Kind.TOOL
  val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
  val background = if (warning) lerpColor(colors.card, colors.warn, 0.07f) else colors.card
  val border = if (warning) colors.warn.copy(alpha = 0.3f) else colors.softBorder
  Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).tuckUnder(16.dp)) {
    Box(Modifier.fillMaxWidth().clip(shape).background(background).border(1.dp, border, shape)
      .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 26.dp)) {
      when (approval.prompt.kind) {
        PermissionPrompt.Kind.QUESTION -> QuestionDrawer(thread, approval)
        PermissionPrompt.Kind.PLAN -> PlanDrawer(thread, approval)
        else -> ApprovalDrawer(thread, approval)
      }
    }
  }
}

/** Takes [overlap] less room than it draws, so what follows covers its bottom. */
private fun Modifier.tuckUnder(overlap: Dp): Modifier = this.layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  this.layout(placeable.width, max(0, placeable.height - overlap.roundToPx())) {
    placeable.place(0, 0)
  }
}

@Composable
private fun DrawerFrame(thread: ThreadState, icon: IconKey, label: String, labelColor: Color,
  extra: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.width(22.dp).padding(top = 1.dp)) { Ico(icon, tint = colors.warn) }
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f), color = labelColor, style = smallStyle, fontWeight = FontWeight.SemiBold)
        if (thread.approvals.size > 1) {
          Text("1/" + thread.approvals.size, color = colors.muted, style = smallStyle)
        }
        extra()
      }
      content()
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(content: @Composable () -> Unit) {
  FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    verticalArrangement = Arrangement.spacedBy(6.dp)) {
    content()
  }
}

@Composable
private fun MoreButton(thread: ThreadState, approval: Approval) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconBtn(Icons.more, t("approvals.more"), iconSize = 14.dp) { open = true }
    if (open) {
      val entries = approval.prompt.suggestions.mapIndexed { index, suggestion ->
        MenuEntry(suggestion.label, icon = Icons.check) { thread.answer(approval, true, suggestion = index) }
      } + MenuEntry(t("approvals.decline.and.stop"), icon = Icons.closeSmall) {
        thread.deny(approval, "The user declined and stopped.", true)
      }
      MenuPopup(entries, alignment = Alignment.End) { open = false }
    }
  }
}

@Composable
private fun ApprovalDrawer(thread: ThreadState, approval: Approval) {
  val prompt = approval.prompt
  DrawerFrame(thread, Icons.approval, approvalLabel(prompt), colors.warn) {
    val detail = thread.approvalDetail(prompt)
    SelectionContainer {
      Text(detail, Modifier.padding(top = 4.dp).heightIn(max = 80.dp).verticalScroll(rememberScrollState()),
        color = colors.fg, style = monoStyle)
    }
    val description = prompt.description
    if (description != null && description != detail) {
      Text(description, Modifier.padding(top = 2.dp), color = colors.muted, style = smallStyle)
    }
    Actions {
      if (prompt.canDiff) {
        OutlineButton(t("approvals.diff"), icon = Icons.diff) { thread.controller?.showProposedEdit(approval.requestId) }
      }
      MoreButton(thread, approval)
      OutlineButton(t("approvals.decline")) { thread.deny(approval, "The user declined this tool call.") }
      PrimaryButton(t("approvals.approve")) { thread.answer(approval, true) }
    }
  }
}

@Composable
private fun PlanDrawer(thread: ThreadState, approval: Approval) {
  DrawerFrame(thread, Icons.plan, t("approvals.plan"), colors.warn) {
    Text(t("approvals.plan.description"), Modifier.padding(top = 2.dp), color = colors.muted, style = smallStyle)
    Actions {
      MoreButton(thread, approval)
      OutlineButton(t("approvals.refine")) { thread.deny(approval, "Keep planning.") }
      PrimaryButton(t("approvals.implement")) { thread.answer(approval, true) }
    }
  }
}

@Composable
private fun QuestionDrawer(thread: ThreadState, approval: Approval) {
  val questions = approval.prompt.questions
  val q = thread.questionFor(approval)
  val question = questions.getOrNull(q.index) ?: return
  val scope = rememberCoroutineScope()
  DrawerFrame(thread, Icons.question, question.header ?: t("approvals.question"), colors.fg, extra = {
    if (questions.size > 1) {
      Text((q.index + 1).toString() + "/" + questions.size, color = colors.muted, style = smallStyle)
    }
    IconBtn(Icons.closeSmall, t("approvals.skip"), iconSize = 14.dp) {
      thread.answer(approval, false, "The user declined to answer.")
    }
  }) {
    Text(question.question, Modifier.padding(top = 6.dp, bottom = 4.dp), color = colors.fg)
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      question.options.forEachIndexed { index, option ->
        val selected = option.label in q.selected
        Row(Modifier.fillMaxWidth().hoverClick(alpha = 0.6f, selected = selected) {
          if (thread.chooseOption(index)) {
            scope.launch {
              delay(ANSWER_DELAY_MILLIS)
              thread.advanceQuestion()
            }
          }
        }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.weight(1f)) {
            Text(option.label, color = if (selected) colors.fg else colors.body, fontWeight = FontWeight.Medium)
            option.description?.let { Text(it, color = colors.muted, style = tinyStyle) }
          }
          val keyColor = if (selected) colors.primary else colors.muted
          Box(Modifier.size(20.dp).border(1.dp, if (selected) colors.primary else colors.softBorder,
            RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text((index + 1).toString(), color = keyColor, style = tinyStyle)
          }
        }
      }
    }
    Actions {
      PrimaryButton(if (q.index == questions.size - 1) t("approvals.submit") else t("approvals.next")) {
        thread.advanceQuestion()
      }
    }
  }
}
