package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.SocialLinks
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun AboutSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val versionName = remember(context) {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
            .testTag("about_us_card"),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header title
            Text(
                text = "About Us",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // App Icon, Name, and Version
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(DarkSurfaceVariant, CircleShape)
                        .border(1.5.dp, AccentCyan, CircleShape)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "v2rayDP App Logo",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "v2rayDP",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Version $versionName",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Developed by & Name
            Text(
                text = "Developed by",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Dineth Pansilu Wickramasinghe",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = DarkSurfaceBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            // Social Rows
            SocialRow(
                icon = Icons.Default.Code,
                label = SocialLinks.GITHUB_LABEL,
                handle = SocialLinks.GITHUB_HANDLE,
                url = SocialLinks.GITHUB_URL,
                testTag = "social_github"
            )

            SocialRow(
                icon = Icons.Default.Public,
                label = SocialLinks.FACEBOOK_LABEL,
                handle = SocialLinks.FACEBOOK_HANDLE,
                url = SocialLinks.FACEBOOK_URL,
                testTag = "social_facebook"
            )

            // WhatsApp: copies handle if no phone number configured, else opens wa.me
            var whatsAppCopied by remember { mutableStateOf(false) }
            val whatsAppUrl = SocialLinks.WHATSAPP_URL
            SocialRow(
                icon = Icons.Default.Chat,
                label = SocialLinks.WHATSAPP_LABEL,
                handle = SocialLinks.WHATSAPP_HANDLE,
                url = whatsAppUrl,
                testTag = "social_whatsapp",
                customAction = if (whatsAppUrl == null) {
                    {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("WhatsApp Handle", SocialLinks.WHATSAPP_HANDLE))
                        whatsAppCopied = true
                        coroutineScope.launch {
                            delay(2000)
                            whatsAppCopied = false
                        }
                    }
                } else null,
                isCopied = whatsAppCopied
            )

            SocialRow(
                icon = Icons.Default.Send,
                label = SocialLinks.TELEGRAM_LABEL,
                handle = SocialLinks.TELEGRAM_HANDLE,
                url = SocialLinks.TELEGRAM_URL,
                testTag = "social_telegram"
            )

            SocialRow(
                icon = Icons.Default.Public,
                label = SocialLinks.INSTAGRAM_LABEL,
                handle = SocialLinks.INSTAGRAM_HANDLE,
                url = SocialLinks.INSTAGRAM_URL,
                testTag = "social_instagram"
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = DarkSurfaceBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Footer
            Text(
                text = "© $currentYear Dineth Pansilu Wickramasinghe. All rights reserved.",
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SocialRow(
    icon: ImageVector,
    label: String,
    handle: String,
    url: String?,
    testTag: String,
    modifier: Modifier = Modifier,
    customAction: (() -> Unit)? = null,
    isCopied: Boolean = false
) {
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable {
                if (customAction != null) {
                    customAction()
                } else if (!url.isNullOrBlank()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // Safe: do nothing and never crash if no app can open URL
                    } catch (_: Exception) {
                        // Safe: catch any other unexpected activity launch issues
                    }
                }
            }
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(DarkSurfaceVariant, CircleShape)
                    .border(1.dp, DarkSurfaceBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "$label icon",
                    tint = AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = handle,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        if (isCopied) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Copied",
                tint = AccentCyan,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open $label",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
