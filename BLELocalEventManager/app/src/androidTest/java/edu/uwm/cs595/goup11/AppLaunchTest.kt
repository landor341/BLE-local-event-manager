package edu.uwm.cs595.goup11

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.uwm.cs595.goup11.frontend.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @Test
    fun appLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // if it crashes on launch, the test fails
        }
    }
}