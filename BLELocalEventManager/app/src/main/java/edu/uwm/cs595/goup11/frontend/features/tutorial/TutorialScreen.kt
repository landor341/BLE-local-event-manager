package edu.uwm.cs595.goup11.frontend.features.tutorial

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    onNoClick: () -> Unit,
    onBack: () -> Unit,
) {
    //string array to hold the various tutorial sections
    val tutorialText = arrayOf(
        "This app utilizes Bluetooth for communication at large gatherings such as " +
                "professional conferences and conventions.",
        "We use event will refer to the entire gathering, presentation will refer to " +
                "specific activities such as keynotes, signings, and lectures.",
        "From the home screen, you will be able to see nearby events to join.",
        "Users can also communicate with other event attendees. It is recommended " +
                "users to fill out their personal profile so that other attendees can see " +
                "their name, profile picture, and interests/skills."
    )

    var i by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tutorial") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(100.dp))

            Text(
                text = tutorialText[i],
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (i < tutorialText.size - 1) {
                        i += 1
                    } else {
                        onNoClick()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Next")
            }
        }
    }
}

// initial tutorial page, users will be asked if they wish to
// view a short tutorial or skip it and go to the home screen
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun introTutorialScreen(
    onYesClick: () -> Unit,
    onNoClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tutorial") },

                )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {


            Text(
                text = "Would you like a quick overview of how to use this app?",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onYesClick
            ) {
                Text("Yes")
            }
            Button(
                onClick = onNoClick
            ) {
                Text("No")
            }
        }
    }
}


