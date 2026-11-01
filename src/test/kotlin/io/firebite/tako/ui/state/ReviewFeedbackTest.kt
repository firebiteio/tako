package io.firebite.tako.ui.state

import io.firebite.tako.ide.context.ContextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFeedbackTest {
  private val review = ContextItem.create(ContextItem.Kind.REVIEW, "Foo.java:3: no", "Review comment on Foo.java\nComment: no")
  private val other = ContextItem.create(ContextItem.Kind.REVIEW, "Bar.kt:1: 😀", "Review comment on Bar.kt\nComment: 😀")
  private val selection = ContextItem.create(ContextItem.Kind.SELECTION, "Foo.java:1-2", "Selected in Foo.java")

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
  fun onlyReviewCommentsCount() {
    assertTrue(hasReviews(listOf(this.selection, this.review)))
    assertFalse(hasReviews(listOf(this.selection)))
    assertFalse(hasReviews(emptyList()))
  }
}
