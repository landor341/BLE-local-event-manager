package edu.uwm.cs595.goup11

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testExploreButtons() {
        var clicked = false
        var noClick = false

        composeTestRule.setContent {
            HomeScreen(
                onExploreClick = { clicked = true },
                onProfileClick = { noClick = true }
            )
        }

        val buttons = composeTestRule.onAllNodes(hasText("Explore") and hasClickAction())


        buttons[0].performClick()
        assertTrue (clicked)

        clicked = false
        buttons[1].performClick()
        assertTrue(clicked)

        assertFalse(noClick)
    }

    @Test
    fun testProfileButtons(){
        var clicked = false
        var noClick = false

        composeTestRule.setContent {
            HomeScreen(
                onExploreClick = {noClick =  true },
                onProfileClick = {clicked = true }
            )
        }

        val button = composeTestRule.onNode(hasText("Profile") and hasClickAction())

        button.performClick()
        assertTrue(clicked)

        clicked = false

        val otherButton = composeTestRule.onNodeWithContentDescription("Profile")

        otherButton.performClick()
        assertTrue(clicked)

        assertFalse((noClick))
    }







}
