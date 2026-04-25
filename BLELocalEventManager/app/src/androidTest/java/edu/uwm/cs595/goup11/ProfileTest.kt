package edu.uwm.cs595.goup11

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import edu.uwm.cs595.goup11.frontend.features.profile.EditProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.UserViewModel
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test


class ProfileTest {
    var back = false
    var edit = false
    var save = false

    @get:Rule
    val testRule = createComposeRule()


    fun setup() {
        back = false
        edit = false
        testRule.setContent {
            ProfileScreen(
                viewModel = UserViewModel(),
                onBack = { back = true },
                onEdit = { edit = true }

            )
        }
    }

    fun editSetup() {
        back = false
        save = false
        testRule.setContent {
            EditProfileScreen(
                viewModel = UserViewModel(),
                onBack = { back = true },
                onSave = { save = true }
            )
        }
    }


    @Test
    fun testEditButton() {
        setup()
        testRule.onNode(hasClickAction() and hasText("Edit Personal Information")).performClick()
        assertTrue(edit)
    }

    @Test
    fun testSaveButton() {
        editSetup()
        testRule.onNode(hasClickAction() and hasText("Save Personal Information")).performClick()
        assertTrue(save)
    }

    @Test
    fun testInterestPopup() {
        editSetup()
        testRule.onNode(hasContentDescription("Add interests")).performClick()

        testRule.onNode(hasContentDescription("Add an interest")).assertExists()
    }

    @Test
    fun testAddInterest() {
        editSetup()
        //click on add interest
        testRule.onNode(hasContentDescription("Add interests")).performClick()

        testRule.onNode(hasContentDescription("Add an interest"))
            .performTextInput("Test Text")

        testRule.onNodeWithContentDescription("Add an interest")
            .assertTextContains("Test Text")

        testRule.onNode(hasClickAction() and hasText("Add")).performClick()

        testRule.onNode(hasClickAction() and hasText("Test Text"))
            .assertTextContains("Test Text")

    }

    @Test
    fun testDeleteInterest() {
        editSetup()
        //adding an interest for setup  
        testRule.onNode(hasContentDescription("Add interests")).performClick()

        testRule.onNode(hasContentDescription("Add an interest"))
            .performTextInput("Test Text")

        testRule.onNodeWithContentDescription("Add an interest")
            .assertTextContains("Test Text")

        testRule.onNode(hasClickAction() and hasText("Add")).performClick()

        testRule.onNode(hasClickAction() and hasText("Test Text"))
            .assertTextContains("Test Text")

        //deleting an interest
        testRule.onNode(hasClickAction() and hasText(("Test Text"))).performClick()
            .assertDoesNotExist()
    }

    @Test
    fun testChangeUName() {
        editSetup()

        testRule.onNode(hasText("User")).performTextReplacement("New Name")

        testRule.onNode(hasText("New Name")).assertExists()

    }
}