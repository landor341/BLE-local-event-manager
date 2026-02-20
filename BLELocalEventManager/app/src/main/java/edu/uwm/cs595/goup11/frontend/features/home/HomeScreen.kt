package edu.uwm.cs595.goup11.frontend.features.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


//Home screen: initial screen users encounter on standard startup
//provides navigation to other screens such as Explore
//nearby presentation will also be listed here

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(onExploreClick: () -> Unit) {

    var allPres = remember {HomeMockData.presentations() }

    val cornerSize = 10.dp
    val padSize = 16.dp
    val header = 24.sp
    val mini = 12.sp
    val myFormat = DateTimeFormatter.ofPattern("HH:mm")
    Column(modifier = Modifier
        .padding(padSize)
        .offset(y = padSize),
        verticalArrangement = Arrangement.spacedBy(padSize/2),

    ){
        Text("Welcome back User!", modifier = Modifier
            .align(Alignment.CenterHorizontally)
            , fontSize = header)
        Text("Recommended presentations", modifier = Modifier
            .align(Alignment.CenterHorizontally))

        allPres.forEach {Presentation ->
            Column(modifier = Modifier
                .background(Color.LightGray, RoundedCornerShape(topStart = cornerSize,
                    topEnd = cornerSize, bottomEnd = cornerSize,
                    bottomStart = cornerSize)),

            ){
                Text(Presentation.name)
                Text(Presentation.speaker, fontSize = mini)
                Row(modifier = Modifier.fillMaxWidth()
                    ){
                    var postScript = ""
                    if(Presentation.time.hour < 12){
                        postScript = "am"
                    }else{
                        postScript = "pm"
                    }

                    Text(Presentation.location, fontSize = mini)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(Presentation.time.format(myFormat) + " " + postScript, fontSize = mini)
                }
            }
        }
    }

}