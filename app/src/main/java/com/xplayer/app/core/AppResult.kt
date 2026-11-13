package com.xplayer.app.core

/** Small result type used by operations that can fail in user-visible ways. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

sealed class AppError(open val detail: String? = null) {
    /** The layout is locked and the requested rearrangement was refused. */
    data object LayoutLocked : AppError()
    data object PermissionDenied : AppError()
    data class InvalidBackup(override val detail: String?) : AppError(detail)
    data class UnsupportedBackupVersion(val found: Int, val supported: Int) : AppError("$found > $supported")
    data class Io(override val detail: String?) : AppError(detail)
    data class Unknown(override val detail: String?) : AppError(detail)
}

/** Thrown by repositories when a locked layout would be modified. */
class LayoutLockedException(scopeKey: String) : IllegalStateException("Layout '$scopeKey' is locked")

inline fun <T> runCatchingApp(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: LayoutLockedException) {
    AppResult.Failure(AppError.LayoutLocked)
} catch (e: java.io.IOException) {
    AppResult.Failure(AppError.Io(e.message))
} catch (e: SecurityException) {
    AppResult.Failure(AppError.PermissionDenied)
} catch (e: Exception) {
    AppResult.Failure(AppError.Unknown(e.message))
}
