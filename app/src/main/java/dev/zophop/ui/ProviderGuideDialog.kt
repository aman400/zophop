package dev.zophop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.data.ServerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderGuideBottomSheet(
    initialServerType: String = ServerType.NTFY,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(initialServerType) }
    val isGotify = selectedType == ServerType.GOTIFY

    NoSwipeBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
        ) {
            // Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "📖 Delivery Provider Setup Guide",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Step-by-step instructions for ntfy & Gotify",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color.Gray)
                }
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Segmented Provider Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isGotify) {
                        Button(onClick = { selectedType = ServerType.NTFY }, modifier = Modifier.weight(1f)) {
                            Text("🚀 ntfy Setup", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { selectedType = ServerType.GOTIFY }, modifier = Modifier.weight(1f)) {
                            Text("🔔 Gotify Setup", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(onClick = { selectedType = ServerType.NTFY }, modifier = Modifier.weight(1f)) {
                            Text("🚀 ntfy Setup", fontSize = 12.sp)
                        }
                        Button(onClick = { selectedType = ServerType.GOTIFY }, modifier = Modifier.weight(1f)) {
                            Text("🔔 Gotify Setup", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Guide Body Content
                if (isGotify) {
                    GotifySetupGuideContent()
                } else {
                    NtfySetupGuideContent()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sticky Bottom Action Button
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Got It!", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun NtfySetupGuideContent() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "ntfy allows sending push notifications via plain HTTP/HTTPS requests.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GuideStepItem(
            stepNumber = "1",
            title = "Choose Server URL",
            description = "Use the free public server (https://ntfy.sh) or enter your self-hosted ntfy server URL.",
        )

        GuideStepItem(
            stepNumber = "2",
            title = "Set a Unique Topic Name",
            description = "Pick a unique topic string (e.g. my-private-otp-9823). Keep it long and unguessable so others cannot guess your feed!",
        )

        GuideStepItem(
            stepNumber = "3",
            title = "Subscribe to Notifications",
            description = "Open https://ntfy.sh/my-private-otp-9823 in your browser or install the official ntfy Android/iOS app and subscribe to your topic.",
        )

        GuideStepItem(
            stepNumber = "4",
            title = "Optional Access Token",
            description = "If your self-hosted ntfy server uses authentication or ACL protection, generate a Bearer Token in ntfy Web UI and paste it into the Token field.",
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "💡 Quick Verification Example:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "curl -d \"Test message\" https://ntfy.sh/my-private-otp-9823",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
fun GotifySetupGuideContent() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Gotify is a self-hosted notification server for sending and receiving messages in real time.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GuideStepItem(
            stepNumber = "1",
            title = "Deploy Gotify Server",
            description = "Run Gotify on your VPS or local home server via Docker:",
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                    .padding(8.dp),
        ) {
            Text(
                text = "docker run -p 8080:80 -v gotify_data:/app/data gotify/server",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF38BDF8),
            )
        }

        GuideStepItem(
            stepNumber = "2",
            title = "Log in to Web UI",
            description = "Open your Gotify Web UI (e.g. https://gotify.example.com) and log in using admin credentials.",
        )

        GuideStepItem(
            stepNumber = "3",
            title = "Create an Application",
            description = "Navigate to 'Apps' → Click 'Create Application' → Enter name 'ZopHop'.",
        )

        GuideStepItem(
            stepNumber = "4",
            title = "Copy Application Token",
            description = "Copy the generated Application Token (X-Gotify-Key, e.g. A1b2C3d4E5f6) and paste it into the Gotify App Token field.",
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "💡 Verification Curl Request:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "curl -X POST \"https://gotify.example.com/message?token=A1b2C3d4\" -F \"message=Test\"",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
fun GuideStepItem(
    stepNumber: String,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = stepNumber,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color.Gray,
            )
        }
    }
}
