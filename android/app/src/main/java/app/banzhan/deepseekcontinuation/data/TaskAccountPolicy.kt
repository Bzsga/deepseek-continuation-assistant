package app.banzhan.deepseekcontinuation.data

/** Ownership is metadata, independent of frozen provider settings and historical request/cache identities. */
internal fun taskBelongsToAccount(task: TaskRecord, session: AccountSession?): Boolean =
    task.settings.mode == "companion" && task.ownerUserId != null && session != null &&
        !accountSessionExpired(session) && task.ownerUserId == session.user.id &&
        task.settings.companionUrl == session.serviceUrl
