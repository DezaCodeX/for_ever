package com.dezacodex.ui
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dezacodex.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// -------------------------------------------------------------
// CORE NAVIGATION ENUM DEFINITION (MAPPED TO MULTI-TABS)
// -------------------------------------------------------------
enum class NavigationTab {
    CHATS, SNAPS, STORIES, CALLS, PROFILE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartSyncApp(viewModel: HeartViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()

    val profileState by viewModel.profile.collectAsStateWithLifecycle()
    val messagesList by viewModel.messages.collectAsStateWithLifecycle()
    val snapsList by viewModel.snaps.collectAsStateWithLifecycle()
    val storiesList by viewModel.stories.collectAsStateWithLifecycle()
    val activeViewingSnap by viewModel.activeViewingSnap.collectAsStateWithLifecycle()
    val secondsRemaining by viewModel.viewingSecondsRemaining.collectAsStateWithLifecycle()

    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val callType by viewModel.callType.collectAsStateWithLifecycle()
    val isPartnerSimulating by viewModel.isPartnerSimulating.collectAsStateWithLifecycle()
    val pairingCelebrationPartnerName by viewModel.pairingCelebrationPartnerName.collectAsStateWithLifecycle()

    val currentProfile = profileState ?: LoversProfile()
    val activeTheme = currentUser?.currentTheme ?: currentProfile.currentTheme

    // Dynamic color style options based on custom theme pickers
    val themeGradient = when (activeTheme) {
        "Lovely Lavender" -> Brush.sweepGradient(
            listOf(Color(0xFFE9D5FF), Color(0xFFC084FC), Color(0xFFE9D5FF))
        )
        "Cozy Crimson" -> Brush.verticalGradient(
            listOf(Color(0xFFBE123C), Color(0xFF881337))
        )
        "Cosmic Romance" -> Brush.linearGradient(
            listOf(Color(0xFF0F172A), Color(0xFF31102F), Color(0xFF0F172A))
        )
        "Romantic Pink" -> Brush.linearGradient(
            listOf(Color(0xFFFDA4AF), Color(0xFFF43F5E))
        )
        else -> Brush.linearGradient(
            listOf(Color(0xFFFF7A96), Color(0xFFFF4B72)) // Vibrant Palette (Vapor)
        )
    }

    val primaryColor = when (activeTheme) {
        "Lovely Lavender" -> Color(0xFF9333EA)
        "Cozy Crimson" -> Color(0xFFBE123C)
        "Cosmic Romance" -> Color(0xFFEC4899)
        else -> Color(0xFFFF4B6E)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF9FA))
    ) {
        when (currentScreen) {
            AppScreen.SPLASH -> SplashScreen()
            AppScreen.ONBOARDING -> OnboardingScreen(viewModel)
            AppScreen.WELCOME -> WelcomeScreen(viewModel)
            AppScreen.LOGIN -> LoginScreen(viewModel)
            AppScreen.SIGNUP -> SignupScreen(viewModel)
            AppScreen.OTP_VERIFY -> OtpVerifyScreen(viewModel)
            AppScreen.FORGOT_PWD -> ForgotPasswordScreen(viewModel)
            AppScreen.PAIRING -> PairingScreen(viewModel, currentUser)
            AppScreen.ADMIN -> AdminDashboardView(viewModel)
            AppScreen.MAIN -> MainHomeScreen(
                viewModel = viewModel,
                currentUser = currentUser ?: User("guest@heartsync.app", "Alex"),
                activeTheme = activeTheme,
                themeGradient = themeGradient,
                primaryColor = primaryColor,
                messagesList = messagesList,
                snapsList = snapsList,
                storiesList = storiesList,
                activeViewingSnap = activeViewingSnap,
                secondsRemaining = secondsRemaining,
                callState = callState,
                callType = callType,
                isPartnerSimulating = isPartnerSimulating
            )
        }

        // Global Alert overlay controller
        authError?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = message,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { viewModel.clearAuthError() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, "Dismiss", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (message.contains("Security Provider Error") || message.contains("failed") || message.contains("Permission denied") || message.contains("not configured")) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    viewModel.toggleOfflineSandboxMode(true)
                                    viewModel.clearAuthError()
                                    viewModel.navigateTo(AppScreen.WELCOME)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B6E)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudOff, contentDescription = "Offline Mode", tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bypass online & use Local Sandbox Mode 💡", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Global Celebrate Connection Overlay Dialog
        pairingCelebrationPartnerName?.let { partnerName ->
            AlertDialog(
                onDismissRequest = { viewModel.clearPairingCelebration() },
                confirmButton = {
                    Button(
                        onClick = { viewModel.clearPairingCelebration() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE)) // Purple-Lavendar
                    ) {
                        Text("Let's Go! 💕", color = Color.White)
                    }
                },
                title = {
                    Text(
                        text = "for∞ever♥︎ Bond Created! 🎉",
                        color = Color(0xFF5B21B6),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("💏✨🌸✨💖", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "You were connected with $partnerName!",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = Color(0xFF7E22CE),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your private sanctuary dashboard is fully optimized. Enjoy secure mutual messaging, camera synchronization, custom GB-Whatsapp style voice call wallpapers, and snap streak stories!",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }
                },
                shape = RoundedCornerShape(28.dp),
                containerColor = Color(0xFFFFF9FA),
                tonalElevation = 10.dp
            )
        }
    }
}

// -------------------------------------------------------------
// MODULE 1.1: SPLASH SCREEN (LOGO ANIMATION)
// -------------------------------------------------------------
@Composable
fun SplashScreen() {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFF1F2), Color(0xFFFFECEF), Color(0xFFFECDD3))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(pulseScale),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Cosmic Pulse Logo",
                    tint = Color(0xFFFF4B6E),
                    modifier = Modifier.size(120.dp)
                )
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Glow",
                    tint = Color.White,
                    modifier = Modifier
                        .size(36.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = (-16).dp, y = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "HeartSync",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF0F172A),
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Where Two Souls Beat as One ❤️",
                fontSize = 14.sp,
                color = Color(0xFFE11D48),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(48.dp))
            CircularProgressIndicator(
                color = Color(0xFFFF4B6E),
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

// -------------------------------------------------------------
// MODULE 1.2: ONBOARDING SCREENS (SWIPE TUTORIAL)
// -------------------------------------------------------------
@Composable
fun OnboardingScreen(viewModel: HeartViewModel) {
    var step by remember { mutableStateOf(0) }
    val slides = listOf(
        Triple("Realtime Love Chats", "Send lovely disappearing messages, share mood stickers, and react with custom emojis to show your deep affection. ❤️", "✨"),
        Triple("Couples Snaps", "Capture cute moments with customized camera lenses. Photos disappear after viewed, exactly like Snapchat! 📸", "🔥"),
        Triple("Voice & Video Calls", "Initiate low-latency audio sessions or glowing video chats decorated with floating falling hearts! 📞", "🥰"),
        Triple("Lover's Moments Feed", "Broadcast lovely story highlights and sweet memo cards to a private feed. Only connected couple accounts can share! 🌸", "💝")
    )

    val currentSlide = slides[step]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        // Upper Slide Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HeartSync",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFFF4B6E)
            )
            TextButton(onClick = { viewModel.skipOnboarding() }) {
                Text("Skip", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
            }
        }

        // Center Content Slider visual deck
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(Color(0xFFFFF0F3), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(currentSlide.third, fontSize = 54.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = currentSlide.first,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF0F172A),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = currentSlide.second,
                fontSize = 14.sp,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Indicator Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                slides.forEachIndexed { idx, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (idx == step) 16.dp else 8.dp, 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (idx == step) Color(0xFFFF4B6E) else Color(0xFFE2E8F0))
                    )
                }
            }
        }

        // Bottom Nav triggers
        Button(
            onClick = {
                if (step < slides.size - 1) {
                    step++
                } else {
                    viewModel.skipOnboarding()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B6E))
        ) {
            Text(
                text = if (step == slides.size - 1) "Get Started 🚀" else "Continue",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

// -------------------------------------------------------------
// MODULE 2.1: AUTH WELCOME SCREEN
// -------------------------------------------------------------
@Composable
fun WelcomeScreen(viewModel: HeartViewModel) {
    val coroutineScope = rememberCoroutineScope()
    var showGoogleChooser by remember { mutableStateOf(false) }
    var customGoogleEmail by remember { mutableStateOf("") }

    Heart3DAnimationBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Hero Icon Card
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .shadow(12.dp, CircleShape)
                        .background(Color.White.copy(alpha = 0.9f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Love logo",
                        tint = Color(0xFFA855F7), // Elegant Lavender primary
                        modifier = Modifier.size(58.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "for∞ever♥︎",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF5B21B6), // Gorgeous deep purple-lavender
                    textAlign = TextAlign.Center,
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Secure Sanctuary for Couples",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6B21A8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Synchronize live private messages, gorgeous Snapchat-style moments, and customized fullscreen calling spaces safely built for connected couples.",
                    fontSize = 13.sp,
                    color = Color(0xFF5B21B6).copy(alpha = 0.82f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    lineHeight = 19.sp
                )

                Spacer(modifier = Modifier.height(40.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.85f), contentColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFE9D5FF))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. Google OAuth (Sign-in with Google)
                        Button(
                            onClick = {
                                showGoogleChooser = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Google", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Continue with Google OAuth 🌟", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 2. Create Account Option right below Continue with Google
                        OutlinedButton(
                            onClick = {
                                viewModel.navigateTo(AppScreen.SIGNUP)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFC084FC)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7E22CE))
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Signup", tint = Color(0xFF7E22CE))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create New Account 💖", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFFE9D5FF)))
                            Text(
                                text = " or use credentials ",
                                fontSize = 11.sp,
                                color = Color(0xFF7E22CE).copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold
                            )
                            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFFE9D5FF)))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 3. Email Option
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.clearAuthError()
                                }
                                viewModel.navigateTo(AppScreen.LOGIN)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFD8B4FE)),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF6B21A8))
                        ) {
                            Icon(Icons.Default.Email, contentDescription = "Email", tint = Color(0xFF6B21A8))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Login with Email 📬", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Bottom Copyright Policy Label
            Text(
                text = "By joining, you agree to for∞ever♥︎ Secure Pairing Policy.\nAdmin: dezacodex@gmail.com",
                fontSize = 9.sp,
                color = Color(0xFF7E22CE),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                lineHeight = 13.sp
            )
        }
    }

    // Beautiful Google Sign-In Chooser Dialog
    if (showGoogleChooser) {
        AlertDialog(
            onDismissRequest = { showGoogleChooser = false },
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("o", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("o", color = Color(0xFFFBBC05), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("g", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("l", color = Color(0xFF34A853), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("e", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sign in to for∞ever♥︎",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "Choose an account to continue",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Pre-saved account 1: User metadata email (Mohan)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showGoogleChooser = false
                                viewModel.triggerGoogleSignIn("mohan641048@gmail.com", "Mohan")
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF7E22CE), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("M", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Mohan", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("mohan641048@gmail.com", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }

                    // Pre-saved account 2: Juliet
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showGoogleChooser = false
                                viewModel.updateProfileName("Juliet", "Romeo", "2212-12-12")
                                viewModel.triggerGoogleSignIn("juliet@forever.app", "Juliet")
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFEC4899), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("J", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Juliet", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("juliet@forever.app", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardColors(containerColor = Color.White, contentColor = Color.Black, disabledContainerColor = Color.White, disabledContentColor = Color.Black),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Use custom Gmail account",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = customGoogleEmail,
                                onValueChange = { customGoogleEmail = it },
                                placeholder = { Text("e.g. someone@gmail.com") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    if (customGoogleEmail.isNotEmpty()) {
                                        showGoogleChooser = false
                                        val extractedName = customGoogleEmail.substringBefore("@")
                                            .replaceFirstChar { it.uppercase() }
                                        viewModel.triggerGoogleSignIn(customGoogleEmail, extractedName)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                            ) {
                                Text("Continue with this account", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGoogleChooser = false }) {
                    Text("Cancel", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// MODULE 2.2: LOGIN SCREEN
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: HeartViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1B4B).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.9f)
    val cardBorder = if (isDark) Color(0xFF6D28D9) else Color(0xFFE9D5FF)
    val textPrimary = if (isDark) Color.White else Color(0xFF5B21B6)
    val textTitle = if (isDark) Color(0xFFC084FC) else Color(0xFF7E22CE)
    val textSecondary = if (isDark) Color(0xFFDDD6FE) else Color(0xFF6B21A8).copy(alpha = 0.75f)
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = if (isDark) Color.White else Color(0xFF1E1B4B),
        unfocusedTextColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E1B4B),
        focusedLabelColor = Color(0xFFC084FC),
        unfocusedLabelColor = if (isDark) Color(0xFFD8B4FE).copy(alpha = 0.7f) else Color(0xFF6B21A8).copy(alpha = 0.7f),
        focusedBorderColor = Color(0xFFC084FC),
        unfocusedBorderColor = if (isDark) Color(0xFF7C3AED) else Color(0xFFD8B4FE),
        focusedPlaceholderColor = if (isDark) Color(0xFF94A3B8) else Color.Gray,
        unfocusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color.Gray
    )

    Heart3DAnimationBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder) // Lavender border
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = {
                            viewModel.navigateTo(AppScreen.WELCOME)
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "for∞ever♥︎ Login",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Hello, Lover!",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = textTitle
                    )
                    Text(
                        text = "Unlock your private sanctuary with connected credentials.",
                        fontSize = 13.sp,
                        color = textSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Your Registered Email ID") },
                        placeholder = { Text("e.g. mohan641048@gmail.com") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Sanctuary Key / Password") },
                        placeholder = { Text("••••••••") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = rememberMe,
                                onCheckedChange = { rememberMe = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF7E22CE))
                            )
                            Text("Remember session", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                        }
                        TextButton(onClick = {
                            viewModel.navigateTo(AppScreen.FORGOT_PWD)
                        }) {
                            Text("Forget Password? 🔑", fontSize = 12.sp, fontWeight = FontWeight.Black, color = textTitle)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.handleNormalLogin(email, password) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = "Unlock", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect Safely 🔒♥︎", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("New to for∞ever♥︎?", fontSize = 12.sp, color = Color(0xFF6B21A8))
                        TextButton(onClick = {
                            viewModel.navigateTo(AppScreen.SIGNUP)
                        }) {
                            Text("Create Account Key", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFF7E22CE))
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 2.3: SIGNUP SCREEN
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignupScreen(viewModel: HeartViewModel) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("21") }
    var gender by remember { mutableStateOf("Female") }

    val genders = listOf("Female", "Male", "Non-binary")
    
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1B4B).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.9f)
    val cardBorder = if (isDark) Color(0xFF6D28D9) else Color(0xFFE9D5FF)
    val textPrimary = if (isDark) Color.White else Color(0xFF5B21B6)
    val textTitle = if (isDark) Color(0xFFC084FC) else Color(0xFF7E22CE)
    val textSecondary = if (isDark) Color(0xFFDDD6FE) else Color(0xFF6B21A8).copy(alpha = 0.75f)
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = if (isDark) Color.White else Color(0xFF1E1B4B),
        unfocusedTextColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E1B4B),
        focusedLabelColor = Color(0xFFC084FC),
        unfocusedLabelColor = if (isDark) Color(0xFFD8B4FE).copy(alpha = 0.7f) else Color(0xFF6B21A8).copy(alpha = 0.7f),
        focusedBorderColor = Color(0xFFC084FC),
        unfocusedBorderColor = if (isDark) Color(0xFF7C3AED) else Color(0xFFD8B4FE),
        focusedPlaceholderColor = if (isDark) Color(0xFF94A3B8) else Color.Gray,
        unfocusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color.Gray
    )

    Heart3DAnimationBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = {
                            viewModel.navigateTo(AppScreen.WELCOME)
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "for∞ever♥︎ Signup",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Black,
                            color = textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Register Account",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = textTitle
                    )
                    Text(
                        text = "Fill in details to unlock secure soulmate handshakes. Photo can be customized later in Profile Tab.",
                        fontSize = 12.sp,
                        color = textSecondary,
                        lineHeight = 17.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // 1. Name Form Input - CHANGED TO Your Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Your Name") },
                        placeholder = { Text("e.g. Juliet Capulet") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. Age Form Input
                    OutlinedTextField(
                        value = age,
                        onValueChange = { age = it.filter { char -> char.isDigit() } },
                        label = { Text("Age (Years)") },
                        placeholder = { Text("e.g. 21") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Gender Choice Row
                    Text(
                        text = "Gender Identity",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genders.forEach { item ->
                            val isSelected = (gender == item)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) Color(0xFF7E22CE) else (if (isDark) Color(0xFF130E29) else Color(0xFFF3E8FF))
                                    )
                                    .clickable { gender = item },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else textPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. Email Form Input
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email ID") },
                        placeholder = { Text("e.g. Juliet@forever.app") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 5. Phone Form Input
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        placeholder = { Text("e.g. +1 555-0199") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 6. Password Form Input
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Create Password (6+ characters)") },
                        placeholder = { Text("••••••••") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF130E29) else Color(0xFFF5F3FF)),
                        border = BorderStroke(1.dp, if (isDark) Color(0xFF4C1D95) else Color(0xFFDDD6FE))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📸", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Profile Photo (Optional): Feel free to upload your gorgeous avatar later from the profile dashboard customization center.",
                                fontSize = 10.sp,
                                color = textPrimary,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = { 
                            val parsedAge = age.toIntOrNull() ?: 21
                            viewModel.handleNormalSignUp(name, email, password, phone, parsedAge, gender) 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = "Register", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Register Sanctuary Account 🚀", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 2.4: OTP VERIFICATION SCREEN
// -------------------------------------------------------------
@Composable
fun OtpVerifyScreen(viewModel: HeartViewModel) {
    var code by remember { mutableStateOf("") }
    var timerCount by remember { mutableStateOf(30) }

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1B4B).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.9f)
    val cardBorder = if (isDark) Color(0xFF6D28D9) else Color(0xFFE9D5FF)
    val textPrimary = if (isDark) Color.White else Color(0xFF5B21B6)
    val textTitle = if (isDark) Color(0xFFC084FC) else Color(0xFF7E22CE)
    val textSecondary = if (isDark) Color(0xFFDDD6FE) else Color(0xFF6B21A8).copy(alpha = 0.75f)
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = if (isDark) Color.White else Color(0xFF1E1B4B),
        unfocusedTextColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E1B4B),
        focusedLabelColor = Color(0xFFC084FC),
        unfocusedLabelColor = if (isDark) Color(0xFFD8B4FE).copy(alpha = 0.7f) else Color(0xFF6B21A8).copy(alpha = 0.7f),
        focusedBorderColor = Color(0xFFC084FC),
        unfocusedBorderColor = if (isDark) Color(0xFF7C3AED) else Color(0xFFD8B4FE),
        focusedPlaceholderColor = if (isDark) Color(0xFF94A3B8) else Color.Gray,
        unfocusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color.Gray
    )

    LaunchedEffect(Unit) {
        while (timerCount > 0) {
            delay(1000)
            timerCount--
        }
    }

    Heart3DAnimationBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🔑", fontSize = 54.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Two-Factor security verification",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = textTitle,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "We sent a simulated security PIN code to your mobile device.\nEnter verification code to activate encryption.",
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = textSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = { Text("Enter OTP (e.g. 2212)") },
                        modifier = Modifier.width(220.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            textAlign = TextAlign.Center,
                            color = if (isDark) Color.White else Color(0xFF1E1B4B)
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = if (timerCount > 0) "Resend code in ${timerCount}s" else "Did not receive code? Tap Resend below",
                        fontSize = 12.sp,
                        color = textPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.submitOtpCode(code) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                    ) {
                        Text("Verify OTP Pin 🔒♥︎", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { timerCount = 30 },
                        enabled = timerCount == 0
                    ) {
                        Text(
                            text = "Resend Verification Code",
                            color = if (timerCount == 0) (if (isDark) Color(0xFFC084FC) else Color(0xFF9333EA)) else Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 2.5: FORGOT PASSWORD SCREEN
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(viewModel: HeartViewModel) {
    val phase by viewModel.forgotPasswordPhase.collectAsState()
    var email by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1B4B).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.9f)
    val cardBorder = if (isDark) Color(0xFF6D28D9) else Color(0xFFE9D5FF)
    val textPrimary = if (isDark) Color.White else Color(0xFF5B21B6)
    val textTitle = if (isDark) Color(0xFFC084FC) else Color(0xFF7E22CE)
    val textSecondary = if (isDark) Color(0xFFDDD6FE) else Color(0xFF6B21A8).copy(alpha = 0.75f)
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = if (isDark) Color.White else Color(0xFF1E1B4B),
        unfocusedTextColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E1B4B),
        focusedLabelColor = Color(0xFFC084FC),
        unfocusedLabelColor = if (isDark) Color(0xFFD8B4FE).copy(alpha = 0.7f) else Color(0xFF6B21A8).copy(alpha = 0.7f),
        focusedBorderColor = Color(0xFFC084FC),
        unfocusedBorderColor = if (isDark) Color(0xFF7C3AED) else Color(0xFFD8B4FE),
        focusedPlaceholderColor = if (isDark) Color(0xFF94A3B8) else Color.Gray,
        unfocusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color.Gray
    )

    Heart3DAnimationBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = {
                            viewModel.navigateTo(AppScreen.LOGIN)
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "for∞ever♥︎ Recovery",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Black,
                            color = textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (phase == 1) {
                        // PHASE 1: Enter Email ID
                        Text(
                            text = "Forgot password?",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = textTitle
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Enter your registered email address below. We'll send a secure OTP code to renew your sanctuary key.",
                            fontSize = 13.sp,
                            color = textSecondary,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Your Email ID") },
                            placeholder = { Text("e.g. juliet@forever.app") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { viewModel.triggerPasswordReset(email) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                        ) {
                            Icon(Icons.Default.VpnKey, contentDescription = "OTP", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Request Recovery OTP 🔑", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else if (phase == 2) {
                        // PHASE 2: Verify Recovery Code
                        Text(
                            text = "Verify security PIN",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = textTitle
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "We have dispatched a simulated recovery OTP to $email. Enter code to reset your account credentials.",
                            fontSize = 13.sp,
                            color = textSecondary,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = otpCode,
                            onValueChange = { otpCode = it },
                            label = { Text("Enter Verification OTP") },
                            placeholder = { Text("e.g. 2212") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { viewModel.verifyForgotOtp(otpCode) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Verify", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Validate Security PIN 🔒♥︎", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else if (phase == 3) {
                        // PHASE 3: Choose New Password
                        Text(
                            text = "Establish sanctuary pass",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = textTitle
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Your OTP has been successfully validated. Design your new secure password credential below.",
                            fontSize = 13.sp,
                            color = textSecondary,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = { Text("New Sanctuary Password") },
                            placeholder = { Text("Choose a safe key (6+ letters)") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { viewModel.submitNewPassword(newPassword) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = "Save", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save New Password & Enter ♥︎", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MAIN HOME SCREEN (MASTER SHELL WITH BOTTOM NAV AT LOWER DEPTH)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHomeScreen(
    viewModel: HeartViewModel,
    currentUser: User,
    activeTheme: String,
    themeGradient: Brush,
    primaryColor: Color,
    messagesList: List<LoveMessage>,
    snapsList: List<CoupleSnap>,
    storiesList: List<CoupleStory>,
    activeViewingSnap: CoupleSnap?,
    secondsRemaining: Int,
    callState: String,
    callType: String,
    isPartnerSimulating: Boolean
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.CHATS) }

    Scaffold(
        topBar = {
            val isPartnerConnected = currentUser.connectedPartnerEmail?.isNotEmpty() == true
            if (isPartnerConnected) {
                Column(
                    modifier = Modifier
                        .background(Color.White)
                        .statusBarsPadding()
                ) {
                    // Large Brand Head Bar with Dual Pill Gradients
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Double Border Avatar Frame
                            Box(
                                modifier = Modifier.size(46.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(width = 2.dp, color = primaryColor, shape = CircleShape)
                                        .padding(2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                            .background(themeGradient),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = (currentUser.connectedPartnerEmail?.take(1) ?: "P").uppercase(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 16.sp
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                        .border(width = 1.5.dp, color = Color.White, shape = CircleShape)
                                        .align(Alignment.BottomEnd)
                                )
                            }

                            Column {
                                Text(
                                    text = "SOULMATE SYNC",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = primaryColor,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = (currentUser.connectedPartnerEmail?.split("@")?.get(0) ?: "Sophia").replaceFirstChar { it.uppercase() },
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF0F172A)
                                )
                            }
                        }

                        // Logout / Options
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { viewModel.logout() },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFFF0F3))
                            ) {
                                Icon(Icons.Default.Logout, "Logout", tint = primaryColor, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Streaks & Snap Score stats pill header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Streaks Block
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .shadow(2.dp, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9FA)),
                            border = BorderStroke(1.dp, Color(0xFFFCE7EC)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔥", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${currentUser.streakCount} Snaps Active",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE11D48)
                                )
                            }
                        }

                        // Scores Block
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .shadow(2.dp, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9FA)),
                            border = BorderStroke(1.dp, Color(0xFFFCE7EC)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("✨", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${currentUser.snapScore} Snap Score",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE11D48)
                                )
                            }
                        }
                    }

                    // Offline / Supabase Sync Notice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (viewModel.isSupabaseConnected) Color(0xFF22C55E).copy(alpha = 0.08f)
                                else Color(0xFFFF9F0A).copy(alpha = 0.08f)
                            )
                            .padding(vertical = 4.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (viewModel.isSupabaseConnected) "SECURE MONGO ATLAS CONNECTED" else "OFFLINE SECURE SANDBOX ACTIVE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (viewModel.isSupabaseConnected) Color(0xFF16A34A) else Color(0xFFD97706)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .background(Color.White)
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("💖", fontSize = 24.sp)
                            Text(
                                text = "HeartSync",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = primaryColor
                            )
                        }
                        IconButton(
                            onClick = { viewModel.logout() },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFFF0F3))
                        ) {
                            Icon(Icons.Default.Logout, "Logout", tint = primaryColor, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        bottomBar = {
            VibrantBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                themeBackground = Color.White
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Render view dynamic based on Tab selection
            val isConnectedForTabs = currentUser?.connectedPartnerEmail?.isNotEmpty() == true
            if (!isConnectedForTabs && selectedTab != NavigationTab.PROFILE) {
                // Beautiful romantic lock gate asking to connect first
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFFFFF1F2), Color(0xFFFFECEF), Color.White)
                            )
                        )
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔒", fontSize = 56.sp)
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Couple Sanctuary Locked",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "To synchronize secure private chat logs, real-time snap camera, couple story moments, and voice/video call rooms, you must link with your partner first.\n\nGo to the Profile tab, enter their email address, and exchange 6-digit codes to sync!",
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            color = Color(0xFF64748B),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(26.dp))
                        Button(
                            onClick = { selectedTab = NavigationTab.PROFILE },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Connect Couple Profile Now 💖", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            } else {
                when (selectedTab) {
                    NavigationTab.CHATS -> ChatView(viewModel, currentUser, messagesList)
                    NavigationTab.SNAPS -> SnapsView(viewModel, currentUser, snapsList)
                    NavigationTab.STORIES -> StoriesView(viewModel, currentUser, storiesList)
                    NavigationTab.CALLS -> CallsView(viewModel, currentUser, callState, callType)
                    NavigationTab.PROFILE -> OnboardingProfileView(viewModel, currentUser)
                }
            }

            // Falling floating hearts call particles overlay
            if (callState == "CONNECTED") {
                FloatingHeartsAnimationOverlay()
            }

            // Snapchat Disappearing Viewer Screen (Expires instantly on close)
            AnimatedVisibility(
                visible = activeViewingSnap != null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier.fillMaxSize()
            ) {
                activeViewingSnap?.let { currentSnap ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.98f))
                            .clickable(enabled = false) {}
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Top controls
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFF4B6E)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = currentSnap.senderName.take(1).uppercase(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(currentSnap.senderName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("Self-Destruct Snap", color = Color.LightGray, fontSize = 10.sp)
                                    }
                                }

                                // Interactive countdown timer
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$secondsRemaining",
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            // Center display snap canvas
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(380.dp)
                                    .padding(8.dp),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1013))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = if (currentSnap.snapType == "VIDEO") "📹 Live Romantic Video" else "📸 Snap Photo",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFF4B6E),
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFFF4B6E).copy(alpha = 0.15f))
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        )

                                        Spacer(modifier = Modifier.height(18.dp))

                                        Text(
                                            text = currentSnap.description,
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Medium,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 28.sp
                                        )
                                    }
                                }
                            }

                            // Bottom actions - Option to Save in Chat or Download locally matches snapchat rules
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { viewModel.saveSnapInChat(currentSnap.id, true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = "Save", tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save in Chat", color = Color.White)
                                }

                                Button(
                                    onClick = { 
                                        viewModel.downloadSnapToLocalStorage(currentSnap.id)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B6E)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download", color = Color.White)
                                }

                                Button(
                                    onClick = { viewModel.triggerSimulatedScreenshot(currentSnap.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Camera, contentDescription = "Screenshot", tint = Color.LightGray)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Screenshot", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 3: PAIRING CONNECTION SCREEN (SOULMATE SECRET CODE)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(viewModel: HeartViewModel, user: User?) {
    val inviteCode = user?.inviteCode ?: "HS-CODM"
    var enteredCode by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFF1F2), Color(0xFFFFECEF), Color.White)
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Heart indicator
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0xFFFFE4E6), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🔒", fontSize = 36.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Connect as a Couple",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF0F172A)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter your soulmate's secret connection code, or share yours to join in instant encryption.",
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(horizontal = 16.dp),
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Share Invitation Code box
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFFFCE7EC)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("YOUR SECRET PAIRING CODE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = inviteCode,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFF4B6E),
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap and share this code with your partner.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Enter soulmate code field
            OutlinedTextField(
                value = enteredCode,
                onValueChange = { enteredCode = it },
                label = { Text("Enter Partner Secret Code") },
                placeholder = { Text("e.g. HS-1234") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFF4B6E)),
                textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { viewModel.inviteAndLinkCouple(enteredCode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B6E))
            ) {
                Text("Join and Connect 💖", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Exit Sandbox bypass
            TextButton(
                onClick = {
                    // Sandbox instant coupling simulation bypass
                    // Connects with Sophia Sim immediately
                    viewModel.inviteAndLinkCouple("L0V3P1")
                }
            ) {
                Text("💡 Instant Demo Link (Connect with Sophia Sim)", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 4: CHATS COMPOSABLE (WHATSAPP-ENGAGED LOVERS CHATS)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatView(viewModel: HeartViewModel, user: User, messages: List<LoveMessage>) {
    var textMessage by remember { mutableStateOf("") }
    var setDisappearing by remember { mutableStateOf(false) }
    var disappearingTimeSec by remember { mutableStateOf(10) }
    var activeWallpaper by remember { mutableStateOf("Cozy Pink") }
    
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Custom background wallpaper themes
    val wallpaperBrush = when (activeWallpaper) {
        "Rose Velvet" -> Brush.verticalGradient(listOf(Color(0xFFFFF1F2), Color(0xFFFFE2E6)))
        "Sunset Romance" -> Brush.linearGradient(listOf(Color(0xFFFEF2F2), Color(0xFFFEE2E2)))
        "Cozy Nights" -> Brush.verticalGradient(listOf(Color(0xFF1E1013), Color(0xFF0F0709)))
        else -> Brush.radialGradient(listOf(Color.White, Color(0xFFFFF1F3)))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(wallpaperBrush)
    ) {
        // Chat header settings - Wallpaper decorator & Disappearing messages
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { setDisappearing = !setDisappearing }
            ) {
                Icon(
                    imageVector = if (setDisappearing) Icons.Default.Timer else Icons.Default.TimerOff,
                    contentDescription = "Disappearing timer",
                    tint = if (setDisappearing) Color(0xFFFF4B6E) else Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (setDisappearing) "Disappearing Active (${disappearingTimeSec}s)" else "Ephemeral Off",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (setDisappearing) Color(0xFFFF4B6E) else Color(0xFF64748B)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Brush, "Wallpapers", tint = Color(0xFFFF4B6E), modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                listOf("Default", "Sunset", "Nights").forEach { wall ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (activeWallpaper.contains(wall) || (wall == "Default" && activeWallpaper == "Cozy Pink")) Color(0xFFFFF0F3)
                                else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (activeWallpaper.contains(wall) || (wall == "Default" && activeWallpaper == "Cozy Pink")) Color(0xFFFF4B6E) else Color(0xFFE2E8F0),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                activeWallpaper = when (wall) {
                                    "Sunset" -> "Sunset Romance"
                                    "Nights" -> "Cozy Nights"
                                    else -> "Cozy Pink"
                                }
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = wall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (activeWallpaper.contains(wall) || (wall == "Default" && activeWallpaper == "Cozy Pink")) Color(0xFFFF4B6E) else Color(0xFF475569)
                        )
                    }
                }
            }
        }

        // Messages Deck List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { msg ->
                val isMe = msg.senderEmail == user.email || msg.senderName == user.username
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {
                    Text(
                        text = if (isMe) "You" else msg.senderName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeWallpaper == "Cozy Nights") Color(0xFFFDA4AF) else Color(0xFF64748B),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isMe) {
                            // Quick emoji popup selectors decoration
                            QuickEmojiReactionRow(msg.id, viewModel)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Box(
                            modifier = Modifier
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isMe) 16.dp else 2.dp,
                                        bottomEnd = if (isMe) 2.dp else 16.dp
                                    )
                                )
                                .background(
                                    if (isMe) Color(0xFFFF4B6E)
                                    else Color.White
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isMe) Color.Transparent else Color(0xFFFFF0F3),
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isMe) 16.dp else 2.dp,
                                        bottomEnd = if (isMe) 2.dp else 16.dp
                                    )
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .widthIn(max = 240.dp)
                        ) {
                            Column {
                                Text(
                                    text = msg.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isMe) Color.White else Color(0xFF0F172A)
                                )
                                if (msg.reaction.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.25f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(msg.reaction, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        if (isMe) {
                            IconButton(
                                onClick = { viewModel.deleteMessage(msg.id) },
                                modifier = Modifier.size(24.dp).padding(start = 4.dp)
                            ) {
                                Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFDA4AF), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        // Text input dock
        Surface(
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
            color = Color.White
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textMessage,
                    onValueChange = { textMessage = it },
                    placeholder = { Text("Write lovely message... ❤️") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF4B6E),
                        unfocusedBorderColor = Color(0xFFF1F5F9),
                        focusedContainerColor = Color(0xFFFFF9FA),
                        unfocusedContainerColor = Color(0xFFFFF9FA)
                    ),
                    maxLines = 3
                )
                
                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF4B6E))
                        .clickable {
                            if (textMessage.isNotBlank()) {
                                viewModel.sendMessage(textMessage, setDisappearing, disappearingTimeSec)
                                textMessage = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Send, "Send Message", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun QuickEmojiReactionRow(messageId: Int, viewModel: HeartViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("❤️", "😍", "💖", "🔥").forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(width = 0.5.dp, color = Color(0xFFFFF0F3), shape = CircleShape)
                    .clickable { viewModel.reactToMessage(messageId, emoji) },
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 11.sp)
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 5: SNAPCHAT SNAP VIEWS (CAMERA SIM & SNAPS INBOX)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapsView(viewModel: HeartViewModel, user: User, snapsList: List<CoupleSnap>) {
    var cameraActive by remember { mutableStateOf(false) }
    var snapCaption by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf("Default Lense") }
    var snapTimer by remember { mutableStateOf(10) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!cameraActive) {
            // Camera launcher banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { cameraActive = true }
                    .shadow(4.dp, RoundedCornerShape(24.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1013)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color(0xFFFF4B6E), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PhotoCamera, "Camera Launcher", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Active Private Camera", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Shoot & send vanishing moments now 📸✨", color = Color.LightGray, fontSize = 12.sp)
                    }
                }
            }

            // Snaps Inbox Feeds
            Text("Snaps Inbox List", fontWeight = FontWeight.Black, color = Color(0xFF0F172A), fontSize = 17.sp)

            if (snapsList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .border(width = 1.dp, color = Color(0xFFFFF0F3), shape = RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No received snaps. Shoot one first! 📸", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(snapsList) { snap ->
                        val canView = !snap.viewed || snap.isSavedInChat
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(2.dp, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFFFFF0F3)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (snap.snapType == "VIDEO") "📹" else "📸",
                                        fontSize = 20.sp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Snap from ${snap.senderName}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0F172A)
                                        )
                                        Text(
                                            text = if (snap.viewed) "Viewed • Expires in 24 hr limit" else "Click to View (${snap.durationSec}s)",
                                            fontSize = 11.sp,
                                            color = if (snap.viewed) Color.Gray else Color(0xFFFF4B6E)
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (canView) {
                                        Button(
                                            onClick = { viewModel.openSnap(snap) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE4E6)),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text("OPEN", fontSize = 11.sp, color = Color(0xFFFF4B6E), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    IconButton(onClick = { viewModel.deleteSnap(snap.id) }) {
                                        Icon(Icons.Default.Close, "Dismiss", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

        } else {
            // Camera Interactive Simulator view
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .shadow(4.dp, RoundedCornerShape(32.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(32.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { cameraActive = false }) {
                            Icon(Icons.Default.Close, "Exit", tint = Color.White)
                        }

                        // Filter Indicators
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(activeFilter, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Simulation lens preview grid
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FilterFrames,
                                contentDescription = "Simulated Viewplane",
                                tint = Color(0xFFFF4B6E),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Lenses Active: $activeFilter",
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Bottom settings
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OutlinedTextField(
                            value = snapCaption,
                            onValueChange = { snapCaption = it },
                            placeholder = { Text("Write sweet drawing caption... 🎨✏️", color = Color.LightGray) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF4B6E),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Lenses controls selection
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            listOf("Love Neon", "Strawberry 🍓", "Golden Aura").forEach { filter ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (activeFilter == filter) Color(0xFFFF4B6E)
                                            else Color.White.copy(alpha = 0.15f)
                                        )
                                        .clickable { activeFilter = filter }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(filter, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Send triggers
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Timelapse, "Timer", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Limit: ${snapTimer}s", color = Color.White, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    if (snapCaption.isNotBlank()) {
                                        viewModel.sendSnap("IMAGE", "$snapCaption [$activeFilter]", snapTimer)
                                        snapCaption = ""
                                        cameraActive = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B6E))
                            ) {
                                Text("Send to Soulmate ✨", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 6: LIVE MOMENTS / INSTAGRAM STORIES SYSTEM
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesView(viewModel: HeartViewModel, user: User, stories: List<CoupleStory>) {
    var storyCaption by remember { mutableStateOf("") }
    var storyCategory by remember { mutableStateOf("MEMORIES") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // High fidelity story publisher card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(24.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFFFCE7EC)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Broadcast Moment Story",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = storyCaption,
                        onValueChange = { storyCaption = it },
                        placeholder = { Text("What sweet thing are you doing? e.g. Dreamy picnic afternoon 🧺🌸") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF4B6E),
                            unfocusedBorderColor = Color(0xFFF1F5F9),
                            focusedContainerColor = Color(0xFFFFF9FA),
                            unfocusedContainerColor = Color(0xFFFFF9FA)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Styles: ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = storyCategory == "MEMORIES",
                                onClick = { storyCategory = "MEMORIES" },
                                label = { Text("📸 Travel & Nature", fontSize = 11.sp) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = storyCategory == "ROMANCE",
                                onClick = { storyCategory = "ROMANCE" },
                                label = { Text("💖 Sweet Promises", fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (storyCaption.isNotBlank()) {
                                viewModel.shareStory(storyCaption, storyCategory)
                                storyCaption = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4B6E))
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Publish story")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share Live Moment (+3 Points)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text("Moments Social Feed Highlights", fontWeight = FontWeight.Black, color = Color(0xFF0F172A), fontSize = 17.sp)
        }

        if (stories.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .border(width = 1.dp, color = Color(0xFFFFF0F3), shape = RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No social moments active. Share some sweet story! 🌸", color = Color.Gray)
                }
            }
        } else {
            items(stories) { story ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFFFF0F3)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF85A1)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(story.senderName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(story.senderName, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), fontSize = 13.sp)
                                    Text(story.imageType, color = Color(0xFFFF4B6E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Stories highlights controls
                            Row {
                                IconButton(onClick = { viewModel.saveStoryToHighlights(story.id, true) }) {
                                    Icon(Icons.Default.Star, "Pin Highlight", tint = Color(0xFFFFD700))
                                }
                                IconButton(onClick = { viewModel.downloadStoryToLocalStorage(story.id) }) {
                                    Icon(Icons.Default.Download, "Download local", tint = Color.Gray)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Stories beautiful sweet cover gradient
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFFFFF1F2), Color(0xFFFFE4E6)))
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                story.description,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF881337),
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 7: DIGIT VOICE/VIDEO INCOMING & OUTGOING CALLS BOOTH
// -------------------------------------------------------------
// -------------------------------------------------------------
// MODULE 7: DIGIT VOICE/VIDEO INCOMING & OUTGOING CALLS BOOTH
// -------------------------------------------------------------
@Composable
fun CallsView(viewModel: HeartViewModel, user: User, state: String, type: String) {
    val cameraEnabledSelf by viewModel.cameraEnabledSelf.collectAsStateWithLifecycle()
    val cameraEnabledPartner by viewModel.cameraEnabledPartner.collectAsStateWithLifecycle()
    val isAutoCamSync by viewModel.isAutoCamSync.collectAsStateWithLifecycle()
    val profileState by viewModel.profile.collectAsStateWithLifecycle()

    val currentProfile = profileState ?: LoversProfile()
    val activeWallpaper = currentProfile.voiceCallWallpaper ?: "Default Sky"

    // Background custom brush depending on Voice wallpaper settings (copied like GB-WhatsApp style)
    val wallpaperBrush = when (activeWallpaper) {
        "Lovely Lavender" -> Brush.verticalGradient(
            listOf(Color(0xFFE9D5FF), Color(0xFFC084FC), Color(0xFF5B21B6))
        )
        "Ocean Breeze" -> Brush.verticalGradient(
            listOf(Color(0xFF0EA5E9), Color(0xFF0369A1), Color(0xFF0C4A6E))
        )
        "Sunset Rose" -> Brush.verticalGradient(
            listOf(Color(0xFFFDA4AF), Color(0xFFF43F5E), Color(0xFF1E1B4B))
        )
        "Forest Serenity" -> Brush.verticalGradient(
            listOf(Color(0xFF4ADE80), Color(0xFF15803D), Color(0xFF022C22))
        )
        else -> Brush.verticalGradient(
            listOf(Color(0xFF2E1065), Color(0xFF0F172A), Color(0xFF020617)) // Deep galaxy
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            "IDLE" -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(6.dp, RoundedCornerShape(28.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFF3E8FF)),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFFF5F3FF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (type == "VOICE") Icons.Default.Phone else Icons.Default.Videocam,
                                contentDescription = "Call icon",
                                tint = Color(0xFF7E22CE),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Couples Private Calling Booth",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF5B21B6)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Only connected couples accounts are authorized to initiate audio/video handshake calls in this system. All channels are custom end-to-end synchronized.",
                            fontSize = 12.sp,
                            color = Color(0xFF6B21A8).copy(alpha = 0.8f),
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.initiateCall("VOICE") },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                            ) {
                                Icon(Icons.Default.Phone, "Voice", tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Voice Call", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.initiateCall("VIDEO") },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
                            ) {
                                Icon(Icons.Default.Videocam, "Video", tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Video Call", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { viewModel.receiveSimulatedCall("VIDEO") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFC084FC))
                        ) {
                            Icon(Icons.Default.Star, contentDescription = "Simulate", tint = Color(0xFF7E22CE))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Simulate Lover Calling You 💑✨", color = Color(0xFF7E22CE), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            "OUTGOING", "INCOMING" -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(32.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)), // Cozy Dark Violet
                    border = BorderStroke(1.dp, Color(0xFF5B21B6)),
                    shape = RoundedCornerShape(32.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(28.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (state == "OUTGOING") "RINGING SANCTUARY HANDSHAKE..." else "INCOMING SECURE CALL REQUEST...",
                            color = Color(0xFFC084FC),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Heart visual wave
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💑", fontSize = 54.sp)
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = if (state == "OUTGOING") "Connecting to Soulmate..." else "Incoming from Connected Couple",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp
                        )

                        Text(
                            text = if (type == "VIDEO") "📹 Telegram-style Video" else "📞 GB-WhatsApp customizable Audio",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            if (state == "INCOMING") {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                        .clickable { viewModel.acceptIncomingCall() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Phone, "Answer Call", tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                                    .clickable { viewModel.hangUpCall() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CallEnd, "Reject Call", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
            "CONNECTED" -> {
                if (type == "VOICE") {
                    // 1) VOICE CALL: CUSTOM FULL-SCREEN WALLPAPER CUSTOMIZATION WINDOW (GB-WhatsApp style)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(520.dp)
                            .shadow(12.dp, RoundedCornerShape(32.dp)),
                        shape = RoundedCornerShape(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(wallpaperBrush)
                                .padding(24.dp)
                        ) {
                            // Column for content
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Title
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = "📞 GB-WhatsApp Voice Theme: Active",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Connecting Hearts Over Airwaves",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                // Pulsing Heart Emoji in custom backdrop
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("💖", fontSize = 72.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Duration: 02:44 (End-to-End Encrypted)",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }

                                // Custom Live Wallpaper picker inside the call screen!
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "🎛️ GB-WhatsApp Background Customizer",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        val wallpapersList = listOf("Lovely Lavender", "Ocean Breeze", "Sunset Rose", "Forest Serenity", "Midnight")
                                        wallpapersList.forEach { wp ->
                                            val isAct = (activeWallpaper == wp)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isAct) Color(0xFFC084FC) else Color.White.copy(alpha = 0.2f))
                                                    .clickable { viewModel.updateVoiceWallpaper(wp) }
                                                    .padding(vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = wp.substringBefore(" "),
                                                    fontSize = 9.sp,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                // Disconnect controls
                                Button(
                                    onClick = { viewModel.hangUpCall() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CallEnd, contentDescription = "End", tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Terminate Handshake Call 📞", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // 2) VIDEO CALL: TELEGRAM-STYLE DUAL VIDEO GRIDS WITH CAMERA AUTO-SYNC GESTURES
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(520.dp)
                            .shadow(12.dp, RoundedCornerShape(32.dp)),
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Header indicating camera states
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color(0xFF0284C7),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "📹 TELEGRAM-STYLE VIDEO",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (isAutoCamSync) {
                                        Surface(
                                            color = Color(0xFF22C55E),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "CAM SYNC ON",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                        .clickable { viewModel.setAutoCamSync(!isAutoCamSync) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Sync Cam",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Switch(
                                        checked = isAutoCamSync,
                                        onCheckedChange = { viewModel.setAutoCamSync(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF22C55E),
                                            checkedTrackColor = Color(0xFFDCFCE7)
                                        ),
                                        modifier = Modifier.scale(0.6f)
                                    )
                                }
                            }

                            // Splitted Video view for Self and Partner
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Grid 1: Self Video Container (Compact size like telegram pip)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (cameraEnabledSelf) Color(0xFF1E293B) else Color(0xFFFF4B6E).copy(alpha = 0.15f)
                                        )
                                        .border(BorderStroke(2.dp, if (cameraEnabledSelf) Color(0xFF38BDF8) else Color.Transparent), RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (cameraEnabledSelf) {
                                        // Self video simulator
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("🙋‍♀️ (Your Camera Mock Live)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Pulsing video stream...", color = Color.Gray, fontSize = 11.sp)
                                        }
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("🔇", fontSize = 24.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Your Camera is Off", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                // Grid 2: Partner Video Container
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (cameraEnabledPartner) Color(0xFF31102F) else Color(0xFF6B21A8).copy(alpha = 0.15f)
                                        )
                                        .border(BorderStroke(2.dp, if (cameraEnabledPartner) Color(0xFFC084FC) else Color.Transparent), RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (cameraEnabledPartner) {
                                        // Partner video simulator
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("🤵 (Lover Camera Live)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Sync active. Frame rate: 60FPS", color = Color.Gray, fontSize = 11.sp)
                                        }
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("🙈", fontSize = 24.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Partner Camera is Off", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Interactive Live triggers for camera sync, toggle self & partner cams
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.toggleCameraSelf() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (cameraEnabledSelf) Color(0xFF0284C7) else Color(0xFFEF4444)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(if (cameraEnabledSelf) "Disable My Cam" else "Enable My Cam", fontSize = 10.sp)
                                    }

                                    Button(
                                        onClick = { viewModel.toggleCameraPartner() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (cameraEnabledPartner) Color(0xFF7E22CE) else Color(0xFFEF4444)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(if (cameraEnabledPartner) "Disable Lover Cam" else "Enable Lover Cam", fontSize = 10.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Button(
                                    onClick = { viewModel.hangUpCall() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CallEnd, contentDescription = "Hang up")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Disconnect Video Handshake", fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// MODULE 8: PROFILE SANCTUARY & BREAK CONNECTION OPTIONS
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingProfileView(viewModel: HeartViewModel, user: User) {
    val activeTheme = user.currentTheme
    val isDarkTheme = activeTheme == "Cosmic Romance"
    
    // Theme colors
    val primaryColor = when (activeTheme) {
        "Lovely Lavender" -> Color(0xFF9333EA)
        "Cozy Crimson" -> Color(0xFFBE123C)
        "Cosmic Romance" -> Color(0xFFEC4899)
        else -> Color(0xFFFF4B6E)
    }
    
    val cardBg = if (isDarkTheme) Color(0xFF1E293B) else Color.White
    val cardContentColor = if (isDarkTheme) Color.White else Color(0xFF1E293B)
    val borderStrokeColor = if (isDarkTheme) Color(0xFF334155) else Color(0xFFFFF0F3)
    val textStyleColor = if (isDarkTheme) Color.White else Color.Black
    val headingColor = if (isDarkTheme) Color.White else Color(0xFF0F172A)
    val grayColor = if (isDarkTheme) Color(0xFF94A3B8) else Color.Gray

    var editMode by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf(user.username) }
    var emailInput by remember { mutableStateOf(user.email) }
    var phoneInput by remember { mutableStateOf(user.phone) }
    var ageInput by remember { mutableStateOf(user.age.toString()) }
    var avatarUrlInput by remember { mutableStateOf(user.avatarUrl) }
    var statusText by remember { mutableStateOf(user.gender.ifBlank { "Holding hands forever & always ❤️" }) }

    androidx.compose.runtime.LaunchedEffect(editMode) {
        if (editMode) {
            nicknameInput = user.username
            emailInput = user.email
            phoneInput = user.phone
            ageInput = user.age.toString()
            avatarUrlInput = user.avatarUrl
            statusText = user.gender.ifBlank { "Holding hands forever & always ❤️" }
        }
    }
    
    // Image selection state & Presets option
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            if (editMode) {
                avatarUrlInput = it.toString()
            } else {
                viewModel.updateAvatarUrl(it.toString())
            }
        }
    }

    var showPresetDialog by remember { mutableStateOf(false) }
    val presets = listOf(
        "https://images.unsplash.com/photo-1518199266791-5375a83190b7?auto=format&fit=crop&q=80&w=200", // Red Heart
        "https://images.unsplash.com/photo-1516589178581-6cd7833ae3b2?auto=format&fit=crop&q=80&w=200", // Couple
        "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&q=80&w=200", // Avatar girl
        "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&q=80&w=200", // Avatar boy
        "https://images.unsplash.com/photo-1544005313-94ddf0286df2?auto=format&fit=crop&q=80&w=200"  // Portrait
    )

    // Unconnected Coupling inputs
    var partnerEmailInput by remember { mutableStateOf("") }
    var partnerCodeInput by remember { mutableStateOf("") }

    var pairingViewMode by remember { mutableStateOf("choices") } // "choices", "generate", "enter"
    var enteredPartnerEmail by remember { mutableStateOf("") }
    var enteredOtpCode by remember { mutableStateOf("") }

    var otpSecondsRemaining by remember { mutableStateOf(300) }
    LaunchedEffect(pairingViewMode, user.pairingOtpExpiry) {
        if (pairingViewMode == "generate" && user.pairingOtpExpiry > System.currentTimeMillis()) {
            while (true) {
                val delayTime = user.pairingOtpExpiry - System.currentTimeMillis()
                if (delayTime <= 0) {
                    otpSecondsRemaining = 0
                    break
                }
                otpSecondsRemaining = (delayTime / 1000).toInt()
                delay(1000)
            }
        }
    }

    val isConnected = !user.connectedPartnerEmail.isNullOrBlank()

    Heart3DAnimationBackground(activeTheme = activeTheme) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Profile Card (Always shown)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(6.dp, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = cardBg, contentColor = cardContentColor),
                    border = BorderStroke(1.dp, borderStrokeColor),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Profile Sanctuary", fontWeight = FontWeight.Black, fontSize = 18.sp, color = headingColor)
                            IconButton(onClick = {
                                if (editMode) {
                                    val ageVal = ageInput.toIntOrNull() ?: user.age
                                    viewModel.updateUserProfileDetails(
                                        newName = nicknameInput,
                                        newEmail = emailInput,
                                        newPhone = phoneInput,
                                        newAge = ageVal,
                                        newBio = statusText,
                                        newAvatarUrl = avatarUrlInput
                                    )
                                }
                                editMode = !editMode
                            }) {
                                Icon(
                                    imageVector = if (editMode) Icons.Default.Save else Icons.Default.Edit,
                                    tint = primaryColor,
                                    contentDescription = "Edit profiles"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (editMode) {
                            OutlinedTextField(
                                value = nicknameInput,
                                onValueChange = { nicknameInput = it },
                                label = { Text("Profile Nickname", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = primaryColor) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textStyleColor,
                                    unfocusedTextColor = textStyleColor,
                                    focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    focusedLabelColor = primaryColor,
                                    unfocusedLabelColor = grayColor
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            OutlinedTextField(
                                value = statusText,
                                onValueChange = { statusText = it },
                                label = { Text("Your Love Bio / Status", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = primaryColor) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textStyleColor,
                                    unfocusedTextColor = textStyleColor,
                                    focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    focusedLabelColor = primaryColor,
                                    unfocusedLabelColor = grayColor
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("Email Address", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = primaryColor) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textStyleColor,
                                    unfocusedTextColor = textStyleColor,
                                    focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    focusedLabelColor = primaryColor,
                                    unfocusedLabelColor = grayColor
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { phoneInput = it },
                                label = { Text("Phone Number", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = primaryColor) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textStyleColor,
                                    unfocusedTextColor = textStyleColor,
                                    focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    focusedLabelColor = primaryColor,
                                    unfocusedLabelColor = grayColor
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = ageInput,
                                onValueChange = { ageInput = it },
                                label = { Text("Age", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null, tint = primaryColor) },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textStyleColor,
                                    unfocusedTextColor = textStyleColor,
                                    focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    focusedLabelColor = primaryColor,
                                    unfocusedLabelColor = grayColor
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = avatarUrlInput,
                                onValueChange = { avatarUrlInput = it },
                                label = { Text("Profile Photo URL", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = primaryColor) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                trailingIcon = {
                                    IconButton(onClick = { showPresetDialog = true }) {
                                        Icon(Icons.Default.Photo, "Presets", tint = primaryColor)
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textStyleColor,
                                    unfocusedTextColor = textStyleColor,
                                    focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                    focusedLabelColor = primaryColor,
                                    unfocusedLabelColor = grayColor
                                )
                            )
                        } else {
                            // Display visual profile details
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(listOf(Color(0xFFFDA4AF), Color(0xFFFF4B6E)))
                                        )
                                        .clickable {
                                            showPresetDialog = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (user.avatarUrl.isNotEmpty()) {
                                        coil.compose.AsyncImage(
                                            model = user.avatarUrl,
                                            contentDescription = "Profile Photo",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Text(
                                            text = user.username.take(1).uppercase(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 36.sp
                                        )
                                    }
                                    
                                    // Upload badge
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(primaryColor, CircleShape)
                                            .align(Alignment.BottomEnd)
                                            .border(2.dp, cardBg, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = "Upload profile photo",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(user.username, fontWeight = FontWeight.Black, fontSize = 20.sp, color = textStyleColor)
                                Text(user.email, color = grayColor, fontSize = 12.sp)

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "“" + (if (user.gender.isNotEmpty()) user.gender else statusText) + "”",
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    fontSize = 13.sp,
                                    color = primaryColor,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))
                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(borderStrokeColor))
                                Spacer(modifier = Modifier.height(16.dp))

                                // Dynamic Profile Detail Rows
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ProfileDetailRow(icon = Icons.Default.Email, label = "Email", value = user.email, themeColor = primaryColor, textColor = textStyleColor, grayColor = grayColor)
                                    ProfileDetailRow(icon = Icons.Default.Phone, label = "Phone", value = user.phone.ifBlank { "Not provided" }, themeColor = primaryColor, textColor = textStyleColor, grayColor = grayColor)
                                    ProfileDetailRow(icon = Icons.Default.Cake, label = "Age", value = if (user.age > 0) "${user.age} years old" else "Not set", themeColor = primaryColor, textColor = textStyleColor, grayColor = grayColor)
                                    ProfileDetailRow(icon = Icons.Default.Photo, label = "Photo URL", value = if (user.avatarUrl.isNotEmpty()) user.avatarUrl.take(25) + "..." else "None", themeColor = primaryColor, textColor = textStyleColor, grayColor = grayColor)
                                }
                            }
                        }
                    }
                }
            }

            if (!isConnected) {
                item {
                        Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(6.dp, RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = cardBg, contentColor = cardContentColor),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🔑", fontSize = 24.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Secure OTP Sync Sanctuary", fontWeight = FontWeight.Black, fontSize = 15.sp, color = headingColor)
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            when (pairingViewMode) {
                                "choices" -> {
                                    Text(
                                        text = "Pair up with your partner using automatic account-level OTP verification. Decide whether to generate a linking OTP or verify your partner's code below!",
                                        fontSize = 11.sp,
                                        color = grayColor,
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            viewModel.generatePairingOtp()
                                            pairingViewMode = "generate"
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                                    ) {
                                        Text("🔑 Generate Secure OTP", fontWeight = FontWeight.Bold, color = Color.White)
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedButton(
                                        onClick = {
                                            pairingViewMode = "enter"
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, primaryColor)
                                    ) {
                                        Text("📥 Enter Partner OTP", fontWeight = FontWeight.Bold, color = primaryColor)
                                    }
                                }
                                "generate" -> {
                                    Text(
                                        text = "Share the following secure OTP code with your partner. They must enter this code on their device along with your email to link your accounts together!",
                                        fontSize = 11.sp,
                                        color = grayColor,
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFFFF1F2)
                                        ),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("YOUR CONNECTION OTP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = user.pairingOtp ?: "------",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                color = textStyleColor,
                                                style = androidx.compose.ui.text.TextStyle(letterSpacing = 4.sp)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))

                                            val minutes = otpSecondsRemaining / 60
                                            val seconds = otpSecondsRemaining % 60
                                            val timeStr = String.format("%02d:%02d", minutes, seconds)

                                            Text(
                                                text = "Expires in $timeStr",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (otpSecondsRemaining < 60) Color.Red else primaryColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    OutlinedButton(
                                        onClick = { pairingViewMode = "choices" },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, grayColor)
                                    ) {
                                        Text("← Back to pairing options", fontWeight = FontWeight.Bold, color = grayColor)
                                    }
                                }
                                "enter" -> {
                                    Text(
                                        text = "Please enter your soulmate's email and the 6-digit verification code generated on their screen to unlock sync features!",
                                        fontSize = 11.sp,
                                        color = grayColor,
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    OutlinedTextField(
                                        value = enteredPartnerEmail,
                                        onValueChange = { enteredPartnerEmail = it },
                                        label = { Text("Partner Email Address", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = primaryColor) },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = textStyleColor,
                                            unfocusedTextColor = textStyleColor,
                                            focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                            unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                            focusedLabelColor = primaryColor,
                                            unfocusedLabelColor = grayColor
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedTextField(
                                        value = enteredOtpCode,
                                        onValueChange = { enteredOtpCode = it },
                                        label = { Text("6-Digit OTP Code", color = if (isDarkTheme) Color.LightGray else Color.DarkGray) },
                                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = primaryColor) },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = androidx.compose.ui.text.TextStyle(color = textStyleColor),
                                        singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = textStyleColor,
                                            unfocusedTextColor = textStyleColor,
                                            focusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                            unfocusedContainerColor = if (isDarkTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                            focusedLabelColor = primaryColor,
                                            unfocusedLabelColor = grayColor
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            viewModel.connectCoupleByEmailAndOtp(enteredPartnerEmail, enteredOtpCode)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                                    ) {
                                        Text("Exchange & Connect Hearts 💞", fontWeight = FontWeight.Bold, color = Color.White)
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedButton(
                                        onClick = { pairingViewMode = "choices" },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, grayColor)
                                    ) {
                                        Text("← Back to pairing options", fontWeight = FontWeight.Bold, color = grayColor)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Connected Partner Info Box
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(6.dp, RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = cardBg, contentColor = cardContentColor),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Synchronized Partnership", fontWeight = FontWeight.Black, fontSize = 14.sp, color = headingColor)
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(primaryColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("❤️", fontSize = 24.sp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Connected Soulmate:", fontSize = 11.sp, color = grayColor)
                                    Text(user.connectedPartnerEmail ?: "", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = textStyleColor)
                                }
                            }
                        }
                    }
                }
            }

            // Central wallpapers Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = cardBg, contentColor = cardContentColor),
                    border = BorderStroke(1.dp, borderStrokeColor),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Central Love Wallpaper Themes", fontWeight = FontWeight.Black, fontSize = 14.sp, color = headingColor)
                        Spacer(modifier = Modifier.height(12.dp))

                        val themes = listOf("Vibrant Palette", "Romantic Pink", "Lovely Lavender", "Cozy Crimson", "Cosmic Romance")
                        themes.forEach { t ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.updateTheme(t) }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val dotColor = when (t) {
                                        "Vibrant Palette" -> Color(0xFFFF4B6E)
                                        "Romantic Pink" -> Color(0xFFFDA4AF)
                                        "Lovely Lavender" -> Color(0xFFC084FC)
                                        "Cozy Crimson" -> Color(0xFFBE123C)
                                        else -> Color(0xFF1E1B4B)
                                    }
                                    Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(t, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textStyleColor)
                                }
                                if (user.currentTheme == t) {
                                    Icon(Icons.Default.Check, "Active", tint = primaryColor)
                                }
                            }
                        }
                    }
                }
            }

            // Connection Sever Control
            if (isConnected) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = cardBg, contentColor = cardContentColor),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Crucial Controls", fontWeight = FontWeight.Black, fontSize = 14.sp, color = headingColor)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Click below to sever connection with connected soulmate. All chat logs, streak points, and mutual video calls will reset.",
                                fontSize = 11.sp,
                                color = grayColor,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.removeCoupleLink() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Break Couple Connection 💔", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }

            // Check for Updates Card
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = cardBg, contentColor = cardContentColor),
                    border = BorderStroke(1.dp, borderStrokeColor),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔄", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("In-App Updater", fontWeight = FontWeight.Black, fontSize = 14.sp, color = headingColor)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Check if there are newer releases available for HeartSync on GitHub. You can download and install updates directly!",
                            fontSize = 11.sp,
                            color = grayColor,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val isChecking by viewModel.isCheckingForUpdates.collectAsState()
                        val isDownloading by viewModel.isDownloadingUpdate.collectAsState()
                        val downloadProgress by viewModel.updateDownloadProgress.collectAsState()
                        val updateAvailable by viewModel.updateAvailable.collectAsState()
                        val statusMessage by viewModel.updateStatusMessage.collectAsState()

                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Current version: v${viewModel.appVersion}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = grayColor)

                                Button(
                                    onClick = { viewModel.checkForUpdates() },
                                    enabled = !isChecking && !isDownloading,
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    if (isChecking) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Text("Check Updates", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }

                            statusMessage?.let { msg ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDarkTheme) Color(0xFF1E293B) else Color(0xFFF1F5F9)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(msg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textStyleColor)

                                        if (isDownloading) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { downloadProgress },
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                color = primaryColor,
                                                trackColor = primaryColor.copy(alpha = 0.2f)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("${(downloadProgress * 100).toInt()}% completed", fontSize = 10.sp, color = grayColor, modifier = Modifier.align(Alignment.End))
                                        }

                                        if (updateAvailable && !isDownloading) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = { viewModel.triggerUpdateDownloadAndInstall() },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Download & Install Update 🚀", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Logout row at the bottom of the page
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.logout() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isDarkTheme) Color(0xFF3B4F66) else Color(0xFFF1F5F9), contentColor = primaryColor),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = "Logout icon",
                        tint = primaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Logout Account 🚪", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Interactive Photo Picker Preset / Upload Select Dialog
    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text("Choose Profile Photo Style", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Button(
                        onClick = {
                            showPresetDialog = false
                            imagePickerLauncher.launch("image/*")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Upload, "Upload local image")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Upload from Photo Gallery 🖼️")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Or Select Premium Couple Presets:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = grayColor)
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        presets.forEach { url ->
                            Box(
                                modifier = Modifier
                                    .size(45.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, primaryColor, CircleShape)
                                    .clickable {
                                        viewModel.updateAvatarUrl(url)
                                        showPresetDialog = false
                                    }
                            ) {
                                coil.compose.AsyncImage(
                                    model = url,
                                    contentDescription = "Preset avatar photo selection link",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPresetDialog = false }) {
                    Text("Cancel", color = primaryColor)
                }
            },
            containerColor = cardBg,
            titleContentColor = headingColor,
            textContentColor = cardContentColor
        )
    }
}

@Composable
fun ProfileDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    themeColor: Color,
    textColor: Color,
    grayColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = themeColor, modifier = Modifier.size(18.dp))
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = grayColor)
        }
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor)
    }
}

// -------------------------------------------------------------
// MODULE 12: ADMIN SYSTEM MODERATION & ANALYTICS
// -------------------------------------------------------------
@Composable
fun AdminDashboardView(viewModel: HeartViewModel) {
    val usersList by viewModel.adminUsersList.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HeartSync Admin Panel 🚨", color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp)
            Button(
                onClick = { viewModel.logout() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5278))
            ) {
                Text("Exit Admin")
            }
        }

        // Stats Dashboard Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("LIVE ANALYTICS & MODERATION COUNTS", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("TOTAL USERS", color = Color.Gray, fontSize = 10.sp)
                        Text("${usersList.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    Column {
                        Text("ACTIVE STREAKS", color = Color.Gray, fontSize = 10.sp)
                        Text("${usersList.sumOf { it.streakCount }}", color = Color(0xFF38BDF8), fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    Column {
                        Text("MOMENTS SHARED", color = Color.Gray, fontSize = 10.sp)
                        Text("${usersList.sumOf { it.totalMomentsCount }}", color = Color(0xFF4ADE80), fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text("Registered Accounts Database Control", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Click 'REMOVE' to purge abusive profiles or unlink toxic couples immediately.", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))

        if (usersList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No user accounts loaded.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(usersList) { user ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(user.username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(user.email, color = Color.Gray, fontSize = 11.sp)
                                if (user.connectedPartnerEmail != null) {
                                    Text("Coupled with: ${user.connectedPartnerEmail}", color = Color(0xFFFF5278), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("Status: Single/Unpaired", color = Color.Gray, fontSize = 10.sp)
                                }
                            }

                            if (user.email != "dezacodex@gmail.com") {
                                Button(
                                    onClick = { viewModel.adminDeleteUser(user.email) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("REMOVE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// VIBRANT FALLING HEARTS ANIMATIONS OVERLAY FOR VIDEO CALLS
// -------------------------------------------------------------
@Composable
fun FloatingHeartsAnimationOverlay() {
    val infiniteTransition = rememberInfiniteTransition()
    val driftY by infiniteTransition.animateFloat(
        initialValue = -50f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val randomList = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        randomList.forEach { frac ->
            val driftX = size.width * frac
            drawCircle(
                color = Color(0xFFFF4B6E).copy(alpha = 0.4f),
                radius = 12f,
                center = Offset(driftX, driftY)
            )
        }
    }
}

// -------------------------------------------------------------
// NAVIGATION BAR LAYOUT IMPLEMENTATION
// -------------------------------------------------------------
@Composable
fun VibrantBottomBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    themeBackground: Color
) {
    Surface(
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color(0xFFFFF0F3),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                clip = false
            ),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 8.dp, horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chat Tab
            BottomNavItem(
                selected = selectedTab == NavigationTab.CHATS,
                onClick = { onTabSelected(NavigationTab.CHATS) },
                iconSelected = Icons.Default.ChatBubble,
                iconUnselected = Icons.Outlined.ChatBubbleOutline,
                label = "CHAT",
                modifier = Modifier.weight(1f)
            )

            // Snaps Camera Tab
            BottomNavItem(
                selected = selectedTab == NavigationTab.SNAPS,
                onClick = { onTabSelected(NavigationTab.SNAPS) },
                iconSelected = Icons.Default.PhotoCamera,
                iconUnselected = Icons.Outlined.PhotoCamera,
                label = "CAMERA",
                modifier = Modifier.weight(1f)
            )

            // Stories Moments Tab
            BottomNavItem(
                selected = selectedTab == NavigationTab.STORIES,
                onClick = { onTabSelected(NavigationTab.STORIES) },
                iconSelected = Icons.Default.AutoAwesome,
                iconUnselected = Icons.Outlined.AutoAwesome,
                label = "MOMENTS",
                modifier = Modifier.weight(1f)
            )

            // Calls Tab
            BottomNavItem(
                selected = selectedTab == NavigationTab.CALLS,
                onClick = { onTabSelected(NavigationTab.CALLS) },
                iconSelected = Icons.Default.Call,
                iconUnselected = Icons.Outlined.Call,
                label = "CALLS",
                modifier = Modifier.weight(1f)
            )

            // Sanctuary Profile Tab
            BottomNavItem(
                selected = selectedTab == NavigationTab.PROFILE,
                onClick = { onTabSelected(NavigationTab.PROFILE) },
                iconSelected = Icons.Default.Favorite,
                iconUnselected = Icons.Outlined.FavoriteBorder,
                label = "SANCTUARY",
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
fun BottomNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    iconSelected: androidx.compose.ui.graphics.vector.ImageVector,
    iconUnselected: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) iconSelected else iconUnselected,
            contentDescription = label,
            tint = if (selected) Color(0xFFFF4B6E) else Color(0xFF94A3B8),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color(0xFFFF4B6E) else Color(0xFF94A3B8),
            letterSpacing = (-0.1).sp
        )
    }
}

// -------------------------------------------------------------
// MODULE 9: 3D ANIMATING COSMIC HEARTS BACKGROUND LAYER
// -------------------------------------------------------------
@Composable
fun Heart3DAnimationBackground(
    modifier: Modifier = Modifier,
    activeTheme: String = "Vibrant Palette",
    content: @Composable () -> Unit
) {
    val transition = rememberInfiniteTransition()

    // Ambient beating pulse for elements
    val beatScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Floating vectors upwards relative
    val floatAnim1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val floatAnim2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val floatAnim3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val bgColors = when (activeTheme) {
        "Cosmic Romance" -> listOf(Color(0xFF0F172A), Color(0xFF1E112C), Color(0xFF030712))
        "Cozy Crimson" -> listOf(Color(0xFF4C0519), Color(0xFF6B0721), Color(0xFF3B0311))
        "Lovely Lavender" -> listOf(Color(0xFFF3E8FF), Color(0xFFE9D5FF), Color(0xFFEDE9FE))
        "Romantic Pink" -> listOf(Color(0xFFFFF1F2), Color(0xFFFFECEF), Color(0xFFFFE4E6))
        else -> listOf(Color(0xFFFFEEF2), Color(0xFFFFD1DC), Color(0xFFFFF1F2)) // Vibrant Palette
    }

    val bubble1Color = when (activeTheme) {
        "Cosmic Romance" -> Color(0xFFA855F7)
        "Cozy Crimson" -> Color(0xFFF43F5E)
        "Lovely Lavender" -> Color(0xFFC084FC)
        else -> Color(0xFFA855F7)
    }

    val bubble2Color = when (activeTheme) {
        "Cosmic Romance" -> Color(0xFFEC4899)
        "Cozy Crimson" -> Color(0xFF9F1239)
        "Lovely Lavender" -> Color(0xFFD8B4FE)
        else -> Color(0xFFEC4899)
    }

    val bubble3Color = when (activeTheme) {
        "Cosmic Romance" -> Color(0xFF818CF8)
        "Cozy Crimson" -> Color(0xFFE11D48)
        "Lovely Lavender" -> Color(0xFFE9D5FF)
        else -> Color(0xFFC084FC)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(bgColors))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().alpha(if (activeTheme == "Cosmic Romance" || activeTheme == "Cozy Crimson") 0.35f else 0.5f)) {
            val w = size.width
            val h = size.height

            // Render 3 distinct floating 3D bubble-shaded hearts
            draw3DHeartBubble(
                centerX = w * 0.22f + (kotlin.math.sin(floatAnim1 * kotlin.math.PI.toFloat() * 2f) * 45.dp.toPx()),
                centerY = h * (1f - floatAnim1) - 60.dp.toPx(),
                radius = 50.dp.toPx() * (0.8f + floatAnim1 * 0.4f),
                pulseFactor = beatScale,
                primaryColor = bubble1Color,
                specularColor = Color.White
            )

            draw3DHeartBubble(
                centerX = w * 0.78f - (kotlin.math.cos(floatAnim2 * kotlin.math.PI.toFloat() * 2f) * 60.dp.toPx()),
                centerY = h * (1f - floatAnim2) + 120.dp.toPx(),
                radius = 65.dp.toPx() * (0.7f + floatAnim2 * 0.5f),
                pulseFactor = beatScale * 0.96f,
                primaryColor = bubble2Color,
                specularColor = Color.White
            )

            draw3DHeartBubble(
                centerX = w * 0.5f + (kotlin.math.sin(floatAnim3 * kotlin.math.PI.toFloat() * 1.5f) * 80.dp.toPx()),
                centerY = h * (1f - floatAnim3) - 200.dp.toPx(),
                radius = 40.dp.toPx() * (0.9f + floatAnim3 * 0.3f),
                pulseFactor = beatScale * 1.04f,
                primaryColor = bubble3Color,
                specularColor = if (activeTheme == "Cosmic Romance") Color(0xFFEC4899) else Color(0xFFFFE4E6)
            )
        }

        // Contents layout overlay
        content()
    }
}

// 3D sphere gradient filling algorithm drawing standard loving custom heart
fun androidx.compose.ui.graphics.drawscope.DrawScope.draw3DHeartBubble(
    centerX: Float,
    centerY: Float,
    radius: Float,
    pulseFactor: Float,
    primaryColor: Color,
    specularColor: Color
) {
    val heartSize = radius * pulseFactor
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(centerX, centerY + heartSize * 0.65f)
        cubicTo(
            centerX - heartSize * 0.85f, centerY - heartSize * 0.15f,
            centerX - heartSize * 0.55f, centerY - heartSize * 0.85f,
            centerX, centerY - heartSize * 0.25f
        )
        cubicTo(
            centerX + heartSize * 0.55f, centerY - heartSize * 0.85f,
            centerX + heartSize * 0.85f, centerY - heartSize * 0.15f,
            centerX, centerY + heartSize * 0.65f
        )
    }

    // Shadow back layers
    drawPath(
        path = path,
        color = primaryColor.copy(alpha = 0.22f),
        style = androidx.compose.ui.graphics.drawscope.Fill
    )

    // Render radial three-dimensional illumination effects
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(specularColor, primaryColor, primaryColor.copy(alpha = 0.9f)),
            center = Offset(centerX - heartSize * 0.22f, centerY - heartSize * 0.22f),
            radius = heartSize * 0.9f
        )
    )

    // Reflection lens flare highlight capsule
    drawCircle(
        color = Color.White.copy(alpha = 0.65f),
        radius = heartSize * 0.15f,
        center = Offset(centerX - heartSize * 0.25f, centerY - heartSize * 0.25f)
    )
}
