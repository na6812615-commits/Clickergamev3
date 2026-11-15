package com.example.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface GoogleSignInOutcome {
    data class Success(val user: FirebaseUser) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data object NoCredentialAvailable : GoogleSignInOutcome
    data class Failure(val message: String) : GoogleSignInOutcome
}

object GoogleAuthHelper {

    private const val TAG = "GoogleAuthHelper"

    fun observeAuthUser(auth: FirebaseAuth = Firebase.auth): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun attemptAutoSignIn(
        context: Context,
        auth: FirebaseAuth = Firebase.auth
    ): GoogleSignInOutcome {
        auth.currentUser?.let { return GoogleSignInOutcome.Success(it) }
        val clientId = context.getString(R.string.default_web_client_id)
        if (clientId.isBlank()) {
            return GoogleSignInOutcome.Failure("Thiếu cấu hình OAuth Web Client ID")
        }

        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = credentialManager.getCredential(context = context, request = request)
            handleCredentialResult(result.credential, auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoCredentialException) {
            GoogleSignInOutcome.NoCredentialAvailable
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Silent Google sign-in cancelled", e)
            GoogleSignInOutcome.Cancelled
        } catch (e: GetCredentialException) {
            Log.d(TAG, "Silent auto sign-in skipped: ${e.message}")
            GoogleSignInOutcome.NoCredentialAvailable
        }
    }

    suspend fun onGoogleSignInClicked(
        activityContext: Context,
        auth: FirebaseAuth = Firebase.auth
    ): GoogleSignInOutcome {
        val clientId = activityContext.getString(R.string.default_web_client_id)
        if (clientId.isBlank()) {
            return GoogleSignInOutcome.Failure("Thiếu cấu hình OAuth Web Client ID")
        }

        val credentialManager = CredentialManager.create(activityContext)
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(clientId).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        return try {
            val result = credentialManager.getCredential(
                context = activityContext,
                request = request
            )
            handleCredentialResult(result.credential, auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google sign-in cancelled by user or credential provider", e)
            GoogleSignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInOutcome.Failure("Thiết bị chưa có tài khoản Google khả dụng. Vui lòng thêm tài khoản Google trong Cài đặt thiết bị.")
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Google sign-in failed", e)
            GoogleSignInOutcome.Failure("Đăng nhập Google thất bại: ${e.localizedMessage ?: "Lỗi Credential Manager"}")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Google auth failed", e)
            GoogleSignInOutcome.Failure("Lỗi xác thực Firebase: ${e.localizedMessage ?: "Không xác định"}")
        }
    }

    suspend fun signOut(
        context: Context,
        auth: FirebaseAuth = Firebase.auth
    ) {
        withContext(NonCancellable) {
            try {
                CredentialManager.create(context)
                    .clearCredentialState(ClearCredentialStateRequest())
            } catch (e: ClearCredentialException) {
                Log.w(TAG, "Failed to clear credential state", e)
            }
            auth.signOut()
        }
    }

    private suspend fun handleCredentialResult(
        credential: androidx.credentials.Credential,
        auth: FirebaseAuth
    ): GoogleSignInOutcome {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            val authResult = auth.signInWithCredential(firebaseCredential).await()
            val user = authResult.user
            return if (user != null) {
                GoogleSignInOutcome.Success(user)
            } else {
                GoogleSignInOutcome.Failure("Không thể lấy thông tin người dùng Firebase.")
            }
        }
        return GoogleSignInOutcome.Failure("Loại chứng thực không được hỗ trợ.")
    }
}
