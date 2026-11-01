package io.firebite.tako.ui.state

import com.google.gson.JsonObject
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ide.context.ReviewComment
import io.firebite.tako.ide.context.ReviewTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFeedbackTest {
  private val proposed = ReviewTarget.proposed("thread-1", "/repo/Foo.java")
  private val review = ReviewComment.item(this.proposed, listOf(ReviewComment.Line(0, 3, "x()")), "no", null)!!
  private val other = ReviewComment.item(this.proposed, listOf(ReviewComment.Line(2, 0, "y()")), "😀", null)!!
  private val turnReview = ReviewComment.item(ReviewTarget.turn("thread-1", "/repo/Bar.kt"),
    listOf(ReviewComment.Line(0, 1, "z")), "later", null)!!
  private val selection = ContextItem.create(ContextItem.Kind.SELECTION, "Foo.java:1-2", "Selected in Foo.java")
  private val edit = PermissionPrompt(PermissionPrompt.Kind.TOOL, "Edit", ToolKind.EDIT,
    JsonObject().apply { this.addProperty("file_path", "/repo/Foo.java") }, "call-1", null, null, true, emptyList(),
    emptyList(), null)

  @Test
  fun typedFeedbackComesFirstThenEachCommentOnTheProposedEdit() {
    assertEquals("use a list\n\n" + this.review.text + "\n\n" + this.other.text,
      withReviews("use a list", listOf(this.review, this.selection, this.turnReview, this.other)))
  }

  @Test
  fun reviewCommentsAloneAreTheFeedback() {
    assertEquals(this.review.text, withReviews("", listOf(this.review)))
  }

  @Test
  fun otherContextAndCommentsOnOtherDiffsAreNotFeedback() {
    assertEquals("why", withReviews("why", listOf(this.selection, this.turnReview)))
    assertEquals("", withReviews("", emptyList()))
  }

  @Test
  fun reviewCommentsGoWithADeclinedProposedEdit() {
    assertTrue(foldsReviews(this.edit, false, true, listOf(this.selection, this.review)))
  }

  @Test
  fun commentsOnlyOnOtherDiffsDoNotDecline() {
    assertFalse(foldsReviews(this.edit, false, true, listOf(this.turnReview)))
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
  fun anyReviewCommentCountsForSending() {
    assertTrue(hasReviews(listOf(this.selection, this.turnReview)))
    assertFalse(hasReviews(listOf(this.selection)))
    assertFalse(hasReviews(emptyList()))
  }
}
