package io.firebite.tako.ui.state

import com.google.gson.JsonObject
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.ide.context.ContextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFeedbackTest {
  private val review = ContextItem.create(ContextItem.Kind.REVIEW, "Foo.java:3: no", "Review comment on Foo.java\nComment: no")
  private val other = ContextItem.create(ContextItem.Kind.REVIEW, "Bar.kt:1: 😀", "Review comment on Bar.kt\nComment: 😀")
  private val selection = ContextItem.create(ContextItem.Kind.SELECTION, "Foo.java:1-2", "Selected in Foo.java")
  private val edit = PermissionPrompt(PermissionPrompt.Kind.TOOL, "Edit", ToolKind.EDIT,
    JsonObject().apply { this.addProperty("file_path", "/repo/Foo.java") }, "call-1", null, null, true, emptyList(),
    emptyList(), null)

  @Test
  fun typedFeedbackComesFirstThenEachReviewComment() {
    assertEquals("use a list\n\nReview comment on Foo.java\nComment: no\n\nReview comment on Bar.kt\nComment: 😀",
      withReviews("use a list", listOf(this.review, this.selection, this.other)))
  }

  @Test
  fun reviewCommentsAloneAreTheFeedback() {
    assertEquals("Review comment on Foo.java\nComment: no", withReviews("", listOf(this.review)))
  }

  @Test
  fun otherContextIsNotFeedback() {
    assertEquals("why", withReviews("why", listOf(this.selection)))
    assertEquals("", withReviews("", emptyList()))
  }

  @Test
  fun reviewCommentsGoWithADeclinedProposedEdit() {
    assertTrue(foldsReviews(this.edit, false, true, listOf(this.selection, this.review)))
  }

  @Test
  fun reviewCommentsStayWhenDecliningStopsTheTurn() {
    assertFalse(foldsReviews(this.edit, true, true, listOf(this.review)))
  }

  @Test
  fun reviewCommentsStayForCallsThatAreNotProposedEdits() {
    val shell = PermissionPrompt(PermissionPrompt.Kind.TOOL, "Bash", ToolKind.COMMAND, JsonObject(), "call-2", null,
      null, false, emptyList(), emptyList(), null)
    val plan = PermissionPrompt(PermissionPrompt.Kind.PLAN, "ExitPlanMode", ToolKind.OTHER, JsonObject(), "call-3",
      null, null, true, emptyList(), emptyList(), "the plan")

    assertFalse(foldsReviews(shell, false, true, listOf(this.review)))
    assertFalse(foldsReviews(plan, false, true, listOf(this.review)))
  }

  @Test
  fun reviewCommentsStayForAnAgentThatReadsNoReasonOrWhenThereAreNone() {
    assertFalse(foldsReviews(this.edit, false, false, listOf(this.review)))
    assertFalse(foldsReviews(this.edit, false, true, listOf(this.selection)))
  }

  @Test
  fun onlyReviewCommentsCount() {
    assertTrue(hasReviews(listOf(this.selection, this.review)))
    assertFalse(hasReviews(listOf(this.selection)))
    assertFalse(hasReviews(emptyList()))
  }
}
