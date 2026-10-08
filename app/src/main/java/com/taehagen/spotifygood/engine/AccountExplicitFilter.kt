package com.taehagen.spotifygood.engine

import com.taehagen.spotifygood.model.User

/**
 * The account's own explicit filter (Spotify's parental setting: a Family plan child, "Allow
 * explicit content" off), as opposed to the app's "Hide explicit content" (docs §4.3, §9.7).
 *
 * Only an online session reports it ([reportedOnline]); a cold start without a network has just
 * the username ([knownUser]). The value last reported is therefore persisted
 * ([com.taehagen.spotifygood.data.settings.Settings.accountExplicitFilter], cleared with the
 * account's data) and used until a session reports it again: the offline Player, the lists and
 * the downloads then still filter for such an account.
 */
internal val User.reportedOnline: Boolean get() = product != null

/** The account's explicit filter: as [user] reported it online, else the [persisted] value. */
internal fun accountExplicitFilter(user: User?, persisted: Boolean): Boolean =
    if (user != null && user.reportedOnline) user.explicitFilter else persisted

/** The value to persist: what an online session reported for [user], when it differs from [persisted]. */
internal fun accountExplicitFilterToPersist(user: User?, persisted: Boolean): Boolean? =
    user?.takeIf { it.reportedOnline }?.explicitFilter?.takeIf { it != persisted }
