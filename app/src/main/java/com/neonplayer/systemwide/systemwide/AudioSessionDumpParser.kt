package com.neonplayer.systemwide.systemwide

import java.util.Locale
import java.util.regex.Pattern

/**
 * Parses audio service / AudioFlinger / audio policy dumps to discover active
 * playback sessions and capture-allowed packages.
 *
 * Adapted from the RootlessJamesDSP approach (GPL-3.0). Kept Android-free for testability.
 */
object AudioSessionDumpParser {

    interface PackageResolver {
        fun packageForUid(uid: Int): String
    }

    interface ReversePackageResolver : PackageResolver {
        fun uidForPackage(packageName: String): Int
    }

    data class Session(
        val sessionId: Int,
        val uid: Int,
        val pid: Int,
        val usage: String = "USAGE_UNKNOWN",
        val content: String = "CONTENT_TYPE_UNKNOWN",
        val packageName: String = uid.toString()
    ) {
        fun isRecordableUsage(): Boolean = Companion.isRecordableUsage(usage)
    }

    class PolicyDump {
        val sessions: LinkedHashMap<Int, Session> = linkedMapOf()
        val captureAllowedByPackage: LinkedHashMap<String, Boolean> = linkedMapOf()
    }

    private val AUDIO_SERVICE_31 = Pattern.compile(
        "AudioPlaybackConfiguration.*u/pid:(\\d+)/(\\d+).*usage=(\\w+).*content=(\\w+).*sessionId:(\\d+)"
    )
    private val AUDIO_SERVICE_30 = Pattern.compile(
        "AudioPlaybackConfiguration.*u/pid:(\\d+)/(\\d+).*usage=(\\w+).*content=(\\w+)"
    )
    private val AUDIO_SERVICE_29 = Pattern.compile(
        "ID:\\d+.*u/pid:(\\d+)/(\\d+).*usage=(\\w+).*content=(\\w+)"
    )

    private val POLICY_SESSION_29 = Pattern.compile(
        "Session Id:\\s*(\\d+)\\s+UID:\\s*(\\d+)[\\s\\S]*?Attributes:[\\s\\S]*?Content type:\\s*(\\w+)\\s*Usage:\\s*(\\w+)"
    )
    private val POLICY_SESSION_33 = Pattern.compile(
        "Session ID:\\s*(\\d+);\\s*uid\\s*(\\d+);[\\s\\S]*?Attributes:[\\s\\S]*?Content type:\\s*(\\w+)\\s*Usage:\\s*(\\w+)"
    )
    private val CAPTURE_ALLOWED = Pattern.compile(
        "allowPlaybackCapture=(\\S+)(?:\\s*,).+packageName=(\\S+)"
    )

    fun isRecordableUsage(usage: String?): Boolean {
        if (usage == null) return false
        val v = usage.trim().uppercase(Locale.US)
        return "USAGE_UNKNOWN" in v || "USAGE_MEDIA" in v || "USAGE_GAME" in v
    }

    fun parseAudioService(
        audioDump: String?,
        flingerDump: String?,
        resolver: PackageResolver?
    ): Map<Int, Session> {
        val sessions = linkedMapOf<Int, Session>()
        if (audioDump.isNullOrEmpty()) return sessions
        val sidByPid = parseFlingerSessionRefs(flingerDump)

        var mode = 31
        var matcher = AUDIO_SERVICE_31.matcher(audioDump)
        if (!matcher.find()) {
            matcher = AUDIO_SERVICE_30.matcher(audioDump)
            mode = 30
            if (!matcher.find()) {
                matcher = AUDIO_SERVICE_29.matcher(audioDump)
                mode = 29
                if (!matcher.find()) return sessions
            }
        }

        do {
            try {
                val uid = matcher.group(1)!!.toInt()
                val pid = matcher.group(2)!!.toInt()
                val usage = matcher.group(3) ?: "USAGE_UNKNOWN"
                val content = matcher.group(4) ?: "CONTENT_TYPE_UNKNOWN"
                val sid = if (mode == 31) matcher.group(5)?.toIntOrNull() else sidByPid[pid]
                val resolvedSid = sid ?: sidByPid[pid] ?: continue
                if (resolvedSid <= 0) continue
                val pkg = resolver?.packageForUid(uid) ?: uid.toString()
                val session = Session(resolvedSid, uid, pid, usage, content, pkg)
                if (session.isRecordableUsage() && !sessions.containsKey(resolvedSid)) {
                    sessions[resolvedSid] = session
                }
            } catch (_: RuntimeException) {
                // Ignore malformed dump lines
            }
        } while (matcher.find())

        return sessions
    }

    fun parseFlingerSessionRefs(flingerDump: String?): Map<Int, Int> {
        val sidByPid = linkedMapOf<Int, Int>()
        if (flingerDump == null) return sidByPid
        val lines = flingerDump.split(Regex("\\r?\\n"))
        var tableMode = 0
        var inTable = false
        for (line in lines) {
            if (line.contains("Global session refs")) {
                inTable = true
                tableMode = 0
                continue
            }
            if (!inTable) continue
            val normalized = line.lowercase(Locale.US)
            if ("session" in normalized && "cnt" in normalized && "pid" in normalized && "uid" in normalized) {
                tableMode = 30
                continue
            }
            if ("session" in normalized && "pid" in normalized && "count" in normalized) {
                tableMode = 29
                continue
            }
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) {
                if (tableMode != 0) inTable = false
                continue
            }
            try {
                when {
                    tableMode == 30 && parts.size >= 4 -> {
                        val sid = parts[0].toInt()
                        val pid = parts[2].toInt()
                        if (sid > 0 && pid > 0) sidByPid[pid] = sid
                    }
                    tableMode == 29 -> {
                        val sid = parts[0].toInt()
                        val pid = parts[1].toInt()
                        if (sid > 0 && pid > 0) sidByPid[pid] = sid
                    }
                    tableMode != 0 -> inTable = false
                }
            } catch (_: NumberFormatException) {
                if (tableMode != 0) inTable = false
            }
        }
        return sidByPid
    }

    fun parseAudioPolicy(policyDump: String?, resolver: PackageResolver?): PolicyDump {
        val result = PolicyDump()
        if (policyDump.isNullOrEmpty()) return result

        var matcher = POLICY_SESSION_29.matcher(policyDump)
        if (!matcher.find()) {
            matcher = POLICY_SESSION_33.matcher(policyDump)
            if (!matcher.find()) matcher = null
        }
        if (matcher != null) {
            do {
                try {
                    val sid = matcher!!.group(1)!!.toInt()
                    val uid = matcher.group(2)!!.toInt()
                    val content = matcher.group(3) ?: "CONTENT_TYPE_UNKNOWN"
                    val usage = matcher.group(4) ?: "USAGE_UNKNOWN"
                    val pkg = resolver?.packageForUid(uid) ?: uid.toString()
                    val session = Session(sid, uid, -1, usage, content, pkg)
                    if (sid > 0 && session.isRecordableUsage() && !result.sessions.containsKey(sid)) {
                        result.sessions[sid] = session
                    }
                } catch (_: RuntimeException) {
                }
            } while (matcher!!.find())
        }

        val allowed = CAPTURE_ALLOWED.matcher(policyDump)
        while (allowed.find()) {
            var pkg = allowed.group(2) ?: continue
            pkg = pkg.replace("shared:", "").trim()
            while (pkg.endsWith(",") || pkg.endsWith(";")) pkg = pkg.dropLast(1)
            val isAllowed = "true".equals(allowed.group(1), ignoreCase = true)
            if (pkg.isNotEmpty()) result.captureAllowedByPackage[pkg] = isAllowed
        }
        return result
    }

    fun restrictedUids(policy: PolicyDump?, resolver: PackageResolver?): List<Int> {
        val uids = mutableListOf<Int>()
        if (policy == null || resolver !is ReversePackageResolver) return uids
        for ((pkg, isAllowed) in policy.captureAllowedByPackage) {
            if (!isAllowed) {
                val uid = resolver.uidForPackage(pkg)
                if (uid > 0 && uid !in uids) uids.add(uid)
            }
        }
        return uids
    }
}
