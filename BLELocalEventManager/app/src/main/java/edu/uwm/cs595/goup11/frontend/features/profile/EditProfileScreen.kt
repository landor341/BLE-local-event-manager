package edu.uwm.cs595.goup11.frontend.features.profile

// EditProfileScreen.kt

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.R
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    username: String,    onBack: () -> Unit,
    onSave: () -> Unit,
    onAdd: () -> Unit
) {
    // TODO: Implement profile UI
    Scaffold(
        topBar = {
            TopAppBar(
                title = { "" },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
                    }
                }
            )
        }
    ){ innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
        ){
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ){
                Image(
                    painter = painterResource(id = R.drawable.profile_picture),
                    contentDescription = null,
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = username,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )


            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Interests",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 4
            ){
                InterestCard("Music")
                InterestCard("Travel")
                InterestCard("Coding")
                InterestCard("Cooking")
                InterestCard("Photography")

            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Save Personal Information")
            }
        }
    }

}

@Composable
private fun InterestCard(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 8.dp
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview
@Composable
fun PreviewEditProfileScreen() {
    BLELocalEventManagerTheme {
        EditProfileScreen(
            username = "Luca",
            onBack = {},
            onSave = {},
            onAdd = {}
        )
    }
}

