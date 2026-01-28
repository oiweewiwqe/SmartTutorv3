package com.example.smarttutor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.smarttutor.R
import com.example.smarttutor.ui.auth.AuthScreen
import com.example.smarttutor.ui.auth.RegisterScreen
import com.example.smarttutor.ui.auth.ResetPasswordScreen
import com.example.smarttutor.ui.main.MainApp
import com.example.smarttutor.ui.theme.MyApplicationTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException

@Composable
fun SmartTutorApp() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("smarttutor_prefs", android.content.Context.MODE_PRIVATE)
    }
    var darkTheme by rememberSaveable {
        mutableStateOf(preferences.getBoolean("dark_theme", false))
    }
    MyApplicationTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                AuthGate(
                    modifier = Modifier.padding(innerPadding),
                    darkTheme = darkTheme,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        preferences.edit().putBoolean("dark_theme", darkTheme).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun AuthGate(
    modifier: Modifier = Modifier,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var showRegister by remember { mutableStateOf(false) }
    var showResetPassword by remember { mutableStateOf(false) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            user = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val webClientId = stringResource(R.string.default_web_client_id)
    val hasValidWebClientId = webClientId.isNotBlank() && webClientId != "YOUR_WEB_CLIENT_ID"
    val googleSignInClient = remember(context, webClientId) {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            errorMessage = "Вход через Google отменен."
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account == null) {
                errorMessage = "Не удалось войти через Google."
                return@rememberLauncherForActivityResult
            }
            if (account.idToken.isNullOrBlank()) {
                errorMessage = "Не настроен Web Client ID для входа через Google."
                return@rememberLauncherForActivityResult
            }
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener { signInResult ->
                if (signInResult.isSuccessful) {
                    errorMessage = null
                } else {
                    errorMessage = signInResult.exception?.localizedMessage ?: "Ошибка авторизации Firebase."
                }
            }
        } catch (e: ApiException) {
            errorMessage = e.localizedMessage ?: "Вход через Google отменен."
        }
    }

    if (user == null) {
        if (showRegister) {
            RegisterScreen(
                modifier = modifier,
                onEmailRegister = { email, password ->
                    val trimmedEmail = email.trim()
                    val trimmedPassword = password.trim()
                    if (trimmedEmail.isBlank()) {
                        errorMessage = "Введите email."
                        infoMessage = null
                        return@RegisterScreen
                    }
                    if (trimmedPassword.isBlank()) {
                        errorMessage = "Введите пароль."
                        infoMessage = null
                        return@RegisterScreen
                    }
                    if (trimmedPassword.length < 6) {
                        errorMessage = "Пароль должен быть не короче 6 символов."
                        infoMessage = null
                        return@RegisterScreen
                    }
                    errorMessage = null
                    infoMessage = null
                    auth.createUserWithEmailAndPassword(trimmedEmail, trimmedPassword).addOnCompleteListener { result ->
                        if (result.isSuccessful) {
                            infoMessage = "Аккаунт создан."
                            errorMessage = null
                        } else {
                            errorMessage = mapAuthError(result.exception)
                        }
                    }
                },
                onBack = {
                    errorMessage = null
                    infoMessage = null
                    showRegister = false
                },
                errorMessage = errorMessage,
                infoMessage = infoMessage
            )
        } else if (showResetPassword) {
            ResetPasswordScreen(
                modifier = modifier,
                onSendReset = { email ->
                    val trimmedEmail = email.trim()
                    if (trimmedEmail.isBlank()) {
                        errorMessage = "Введите email для сброса пароля."
                        infoMessage = null
                        return@ResetPasswordScreen
                    }
                    errorMessage = null
                    infoMessage = null
                    auth.sendPasswordResetEmail(trimmedEmail).addOnCompleteListener { result ->
                        if (result.isSuccessful) {
                            infoMessage = "Ссылка для сброса отправлена."
                        } else {
                            errorMessage = mapAuthError(result.exception)
                        }
                    }
                },
                onBack = {
                    errorMessage = null
                    infoMessage = null
                    showResetPassword = false
                },
                errorMessage = errorMessage,
                infoMessage = infoMessage
            )
        } else {
            AuthScreen(
                modifier = modifier,
                onGoogleClick = {
                    if (!hasValidWebClientId) {
                        errorMessage = "Добавьте Web Client ID Firebase в strings.xml."
                        return@AuthScreen
                    }
                    launcher.launch(googleSignInClient.signInIntent)
                },
                onEmailSignIn = { email, password ->
                    val trimmedEmail = email.trim()
                    val trimmedPassword = password.trim()
                    if (trimmedEmail.isBlank()) {
                        errorMessage = "Введите email."
                        infoMessage = null
                        return@AuthScreen
                    }
                    if (trimmedPassword.isBlank()) {
                        errorMessage = "Введите пароль."
                        infoMessage = null
                        return@AuthScreen
                    }
                    errorMessage = null
                    infoMessage = null
                    auth.signInWithEmailAndPassword(trimmedEmail, trimmedPassword).addOnCompleteListener { result ->
                        if (result.isSuccessful) {
                            errorMessage = null
                        } else {
                            errorMessage = mapAuthError(result.exception)
                        }
                    }
                },
                onForgotPassword = {
                    errorMessage = null
                    infoMessage = null
                    showResetPassword = true
                },
                onRegisterRequested = {
                    errorMessage = null
                    infoMessage = null
                    showRegister = true
                },
                errorMessage = errorMessage,
                infoMessage = infoMessage
            )
        }
    } else {
        MainApp(
            modifier = modifier,
            userEmail = user?.email ?: "",
            userId = user?.uid,
            userDisplayName = user?.displayName,
            userLastSignInMillis = user?.metadata?.lastSignInTimestamp,
            darkTheme = darkTheme,
            onToggleTheme = onToggleTheme,
            onSignOut = {
                errorMessage = null
                infoMessage = null
                showRegister = false
                showResetPassword = false
                auth.signOut()
                googleSignInClient.signOut()
            }
        )
    }
}

private fun mapAuthError(exception: Exception?): String {
    return when (exception) {
        is FirebaseAuthUserCollisionException -> "Аккаунт с таким email уже существует."
        is FirebaseAuthInvalidCredentialsException -> "Неверный email или пароль."
        else -> exception?.localizedMessage ?: "Произошла ошибка. Попробуйте еще раз."
    }
}
