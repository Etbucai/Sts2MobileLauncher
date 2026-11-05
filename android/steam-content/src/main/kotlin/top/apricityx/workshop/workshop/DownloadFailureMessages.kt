package top.apricityx.workshop.workshop


internal fun Throwable.userVisibleDownloadFailureMessage(): String {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { throwable ->
            throwable.message
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        .toList()

    messages.lastOrNull(::isSteamAuthorizationFailure)?.let { return it }

    val primary = messages.firstOrNull()
        ?: return this::class.simpleName ?: "Download failed"
    val detail = messages.lastOrNull()
    return if (detail != null && detail != primary) {
        "$primary: $detail"
    } else {
        primary
    }
}

fun isSteamAuthorizationFailure(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val normalized = message.lowercase()
    return normalized.contains("unauthorized") || normalized.contains("forbidden") ||
        normalized.contains("access denied") || normalized.contains("accessdenied") ||
        normalized.contains("no license") || normalized.contains("nolicense") ||
        normalized.contains("not logged") || normalized.contains("notloggedon") ||
        normalized.contains("login required") || normalized.contains("requires login") ||
        normalized.contains("does not own") || normalized.contains("insufficientprivilege") ||
        AUTHORIZATION_HTTP_STATUS.containsMatchIn(normalized)
}

private val AUTHORIZATION_HTTP_STATUS = Regex("(?:http|cdn request failed:|direct download failed:)\\s*(?:401|403)\\b")
