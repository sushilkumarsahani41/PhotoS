package app.photos

/** A historical observation, never proof that an offline drive still contains a file. */
data class SyncRecord(val hash: String, val target: String, val state: String, val checked: Long, val verified: Long, val size: Long, val modified: Long) {
    fun label(currentSize: Long, currentModified: Long): String = when {
        currentSize != size || currentModified != modified -> if (verified > 0) "Changed" else "Not checked"
        state == "present" -> "Backed up ✓"
        state == "changed" -> "Changed"
        state == "missing" -> "Not backed up"
        else -> "Not checked"
    }
}

object SyncRules {
    fun absentState(previousHash: String?, currentHash: String): String =
        if (!previousHash.isNullOrEmpty() && previousHash != currentHash) "changed" else "missing"
}
