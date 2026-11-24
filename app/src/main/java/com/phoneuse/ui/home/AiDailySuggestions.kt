package com.phoneuse.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.i18n.tr
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Model representing an AI-driven smart contextual suggestion for daily phone tasks.
 */
data class AiDailySuggestion(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val icon: () -> ImageVector,
    val category: String,
    val accentColor: Color = Color(0xFF00E5FF),
)

/**
 * Interactive AI Daily Suggestions section displayed prominently on the Home UI.
 * Dynamically provides smart, time-aware automation suggestions across Morning,
 * Afternoon, Evening, and Night routines in Hindi and English.
 */
@Composable
fun AiDailySuggestions(
    onSelectSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
    timestamp: Long = System.currentTimeMillis(),
) {
    val c = pu
    val hour = remember(timestamp) {
        runCatching {
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalTime().hour
        }.getOrDefault(10)
    }

    var shuffleIndex by remember { mutableIntStateOf(0) }

    val (timeSlotTitle, allSuggestions) = remember(hour) {
        when (hour) {
            in 5..11 -> Pair(
                tr("Morning AI Suggestions", "आज सुबह के AI सुझाव"),
                listOf(
                    AiDailySuggestion(
                        title = "Check Schedule & Calendar",
                        subtitle = "आज का शेड्यूल और मीटिंग्स चेक करो",
                        prompt = "Check my calendar schedule for today and list any upcoming meetings or appointments",
                        icon = { PuIcons.Calendar },
                        category = "Routine",
                        accentColor = Color(0xFFFFB300),
                    ),
                    AiDailySuggestion(
                        title = "Summarize WhatsApp & Alerts",
                        subtitle = "व्हाट्सएप और नोटिफिकेशन्स की समरी बताओ",
                        prompt = "Summarize my unread notifications and any new messages from WhatsApp",
                        icon = { PuIcons.Lines },
                        category = "Updates",
                        accentColor = Color(0xFF00E5FF),
                    ),
                    AiDailySuggestion(
                        title = "Weather & Route Traffic",
                        subtitle = "आज का मौसम और रास्ते का ट्रैफिक देखो",
                        prompt = "What is the weather today and how is the traffic to office?",
                        icon = { PuIcons.Route },
                        category = "Travel",
                        accentColor = Color(0xFF00E676),
                    ),
                    AiDailySuggestion(
                        title = "Morning Motivation & News",
                        subtitle = "आज की टॉप ताज़ा खबरें बताओ",
                        prompt = "Juli, tell me today's top news headlines and an inspiring thought",
                        icon = { PuIcons.Bolt },
                        category = "Daily",
                        accentColor = Color(0xFF7C4DFF),
                    ),
                ),
            )
            in 12..16 -> Pair(
                tr("Afternoon AI Suggestions", "दोपहर के AI सुझाव"),
                listOf(
                    AiDailySuggestion(
                        title = "Product Search & Compare",
                        subtitle = "Amazon और Flipkart पर डील्स चेक करो",
                        prompt = "Search for top rated wireless earbuds on Amazon and Flipkart and compare prices",
                        icon = { PuIcons.Search },
                        category = "Shopping",
                        accentColor = Color(0xFFFF4081),
                    ),
                    AiDailySuggestion(
                        title = "Tidy Downloads & Screenshots",
                        subtitle = "फालतू फाइल्स और स्क्रीनशॉट्स साफ करो",
                        prompt = "Tidy up my Downloads folder and delete duplicate screenshots",
                        icon = { PuIcons.Folder },
                        category = "Storage",
                        accentColor = Color(0xFF00E5FF),
                    ),
                    AiDailySuggestion(
                        title = "Send Quick Follow-up Message",
                        subtitle = "पेंडिंग मैसेज का ड्राफ्ट बनाओ",
                        prompt = "Draft a polite follow-up message on WhatsApp for my pending project update",
                        icon = { PuIcons.Send },
                        category = "Work",
                        accentColor = Color(0xFF00E676),
                    ),
                    AiDailySuggestion(
                        title = "Order Lunch or Coffee",
                        subtitle = "Zomato या Swiggy खोलो",
                        prompt = "Open Zomato and show healthy lunch options near me",
                        icon = { PuIcons.Pin },
                        category = "Food",
                        accentColor = Color(0xFFFF9100),
                    ),
                ),
            )
            in 17..20 -> Pair(
                tr("Evening AI Suggestions", "शाम के AI सुझाव"),
                listOf(
                    AiDailySuggestion(
                        title = "Wrap Up Tasks & Progress",
                        subtitle = "आज का काम और प्रोग्रेस रिव्यू करो",
                        prompt = "Show my completed tasks today and summarize remaining pending items",
                        icon = { PuIcons.Check },
                        category = "Review",
                        accentColor = Color(0xFF00E5FF),
                    ),
                    AiDailySuggestion(
                        title = "Order Groceries & Dinner",
                        subtitle = "Blinkit या Instamart से ऑर्डर",
                        prompt = "Open Blinkit or Zepto to check milk and bread in cart",
                        icon = { PuIcons.Pin },
                        category = "Household",
                        accentColor = Color(0xFFFFB300),
                    ),
                    AiDailySuggestion(
                        title = "Evening Music on YouTube",
                        subtitle = "रिलैक्सिंग म्यूजिक चलाओ",
                        prompt = "Open YouTube and play relaxing acoustic Hindi songs",
                        icon = { PuIcons.Step },
                        category = "Relax",
                        accentColor = Color(0xFFFF5252),
                    ),
                ),
            )
            else -> Pair(
                tr("Night AI Suggestions", "रात के AI सुझाव"),
                listOf(
                    AiDailySuggestion(
                        title = "Sleep Routine: Alarm & DND",
                        subtitle = "डार्क मोड, DND ऑन और 6:30 AM अलार्म",
                        prompt = "Get my phone ready for bed: turn on dark mode, set an alarm for 6:30 AM, and turn on Do Not Disturb",
                        icon = { PuIcons.Lock },
                        category = "Sleep",
                        accentColor = Color(0xFF7C4DFF),
                    ),
                    AiDailySuggestion(
                        title = "Review Tomorrow's Calendar",
                        subtitle = "कल की पहली मीटिंग कब है?",
                        prompt = "What is my first meeting or appointment tomorrow morning?",
                        icon = { PuIcons.Calendar },
                        category = "Planning",
                        accentColor = Color(0xFF00E5FF),
                    ),
                    AiDailySuggestion(
                        title = "Battery Check & Power Saver",
                        subtitle = "बैटरी स्टेटस और ऑप्टिमाइज़ेशन",
                        prompt = "Check battery status and optimize settings for the night",
                        icon = { PuIcons.Bolt },
                        category = "Device",
                        accentColor = Color(0xFF00E676),
                    ),
                ),
            )
        }
    }

    // Rotated slice of suggestions
    val displayedSuggestions = remember(allSuggestions, shuffleIndex) {
        val shift = shuffleIndex % allSuggestions.size
        allSuggestions.drop(shift) + allSuggestions.take(shift)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Section Header: Title, Sparkle Badge, Shuffle Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF)),
                )
                Text(
                    text = timeSlotTitle,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                    letterSpacing = (-0.2).sp,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "AI",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = c.accentText,
                    )
                }
            }

            Text(
                text = tr("Refresh", "बदलो"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = c.accentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { shuffleIndex++ }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // Horizontal Scrollable Cards of AI Daily Suggestions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (suggestion in displayedSuggestions) {
                SuggestionCard(
                    suggestion = suggestion,
                    onClick = { onSelectSuggestion(suggestion.prompt) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: AiDailySuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .width(220.dp)
            .heightIn(min = 100.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.inputBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(suggestion.accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = suggestion.icon(),
                        contentDescription = null,
                        tint = suggestion.accentColor,
                        modifier = Modifier.size(17.dp),
                    )
                }

                Text(
                    text = suggestion.category,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.textSub,
                )
            }

            Text(
                text = suggestion.title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = suggestion.subtitle,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = c.textSub,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
