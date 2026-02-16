package edu.uwm.cs595.goup11.frontend.features.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

//Home screen: initial screen users encounter on standard startup
//provides navigation to other screens such as Explore

@Composable
fun HomeScreen(onExploreClick: () -> Unit) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()




    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier.padding(15.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Top

                ) {
                    Text("Navigation", modifier = Modifier.padding(16.dp))
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text(text = "Test item") },
                        selected = false,
                        onClick = {

                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(text = "Explore")},
                        selected = false,
                        onClick = onExploreClick

                    )
                }
            }
        },


    ) {


        Scaffold() { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start
            ){
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.TopStart
                ) {

                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                drawerState.apply {
                                    if (isClosed) open() else close()
                                }
                            } },
                        ){
                            Icon(Icons.Filled.Add, "Navigation")
                        }

                }
            }
        }
    }
}