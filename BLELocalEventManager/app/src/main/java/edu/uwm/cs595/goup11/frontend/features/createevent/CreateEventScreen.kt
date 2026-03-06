package edu.uwm.cs595.goup11.frontend.features.createevent

import android.R.attr.onClick
import android.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.InputTransformation.Companion.keyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import edu.uwm.cs595.goup11.frontend.domain.models.Event


/*
Create Event screen

This screen should
  - allow users to create a new event
  - provide options to customize the new event
  -
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEventScreen(
    onBack: () -> Unit,
){
    var eventName        by rememberSaveable{ mutableStateOf("")}
    var eventDesc        by rememberSaveable{ mutableStateOf("")}
    var hostName         by rememberSaveable{ mutableStateOf("")}
    var participantCount by rememberSaveable{ mutableStateOf(0) }
    var time             by rememberSaveable{ mutableStateOf("")}
    var location         by rememberSaveable{ mutableStateOf("")}

    //temporary pop-up window variable
    var showPopup by remember{mutableStateOf(false)}

    Scaffold(
        topBar = {
            TopAppBar(
                title = {Text("Create an Event") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ){ innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ){

            TextField(
                value = eventName,
                onValueChange = {eventName = it},
                label = {Text (text = "Event Name")},
                modifier = Modifier.fillMaxWidth(),
            )

            TextField(
                value = eventDesc,
                onValueChange = {eventDesc = it},
                minLines = 3,
                label = {Text("Event Description")},
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = hostName,
                onValueChange = {hostName = it},
                label = {Text("Host Name")},
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = participantCount.toString(),
                onValueChange = {try{
                    participantCount = it.toInt()
                }catch(x: NumberFormatException){
                    false
                }},
                label = {Text("Participant count")},
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),

            )

            //I am assuming we will change this parameter from String to LocalDateTime (or something similar)
            //is there a reason this needs to be a sting?
            TextField(
                value = time,
                onValueChange = {time = it},
                label = {Text("Time of Event")},
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = location,
                onValueChange = {location = it},
                label = {Text("Location of Event")},
                modifier = Modifier.fillMaxWidth()
            )

            var myEvent = Event("TEST",eventName,
                eventDesc, hostName,
                participantCount, time, location)


            //temporary popup box
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                onClick = {
                    if(!showPopup){
                        showPopup = true
                    }
                }
            ) {Text("Submit")}
            if (showPopup){
                Popup(
                    alignment = Alignment.Center,
                    onDismissRequest = {showPopup = false}
                ){
                    Column(
                        modifier = Modifier
                            .background(Color.Gray)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        Text(
                            "Event name: " + myEvent.name +
                                    "\nEvent description: " + myEvent.description +
                                    "\nHost name: " + myEvent.hostName +
                                    "\nParticipant count: " + myEvent.participantCount.toString() +
                                    "\nLocation of event: " + myEvent.location
                        )
                        Button(onClick = {showPopup = false},
                            modifier = Modifier.fillMaxWidth()){
                            Text("Dismiss")
                        }
                    }
                }
            }

        }

    }


}


