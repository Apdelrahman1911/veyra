package dev.veyra.desktop.runtime

import kotlinx.coroutines.Job

/**
 * One admission boundary for the two asynchronously created desktop owners.
 * A host close first retires this boundary, then cancels/joins every admitted
 * job before releasing native resources. A late Compose effect cannot start a
 * second owner after that snapshot. This does not itself launch or cancel jobs.
 */
internal class DesktopStartupJobs {
    enum class Kind { PLATFORM, MEDIA_SESSION }

    private var retired = false
    private val jobs = mutableMapOf<Kind, Job>()

    @Synchronized fun admit(kind: Kind, job: Job): Boolean {
        if (retired || kind in jobs || !job.isActive) return false
        jobs[kind] = job
        return true
    }

    @Synchronized fun retire(): List<Job> {
        retired = true
        // Platform initialization can publish the app observed by the session
        // effect. Freeze both admissions first, then drain in dependency order.
        return Kind.entries.mapNotNull(jobs::get)
    }
}
