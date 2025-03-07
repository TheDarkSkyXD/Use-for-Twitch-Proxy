package com.perflyst.twire.model

import android.os.Parcelable
import com.github.twitch4j.helix.domain.Stream
import kotlinx.parcelize.Parcelize

@Parcelize
class StreamInfo(
    @JvmField val userInfo: UserInfo,
    @JvmField var game: String? = null,
    @JvmField var currentViewers: Int = 0,
    @JvmField val previewTemplate: String?,
    @JvmField val startedAt: Long,
    @JvmField var title: String? = null
) : Comparable<StreamInfo>, Parcelable {
    constructor(stream: Stream) : this(
        UserInfo(stream.userId, stream.userLogin, stream.userName),
        stream.gameName,
        stream.viewerCount,
        stream.thumbnailUrlTemplate,
        stream.startedAtInstant.toEpochMilli(),
        stream.title
    )

    override fun equals(other: Any?): Boolean = when (other) {
        is StreamInfo -> this.userInfo.userId == other.userInfo.userId
        else -> false
    }

    override fun hashCode(): Int = userInfo.userId.hashCode()

    /**
     * For a Comparator that also takes priority into account, check out the private comparator in the OnlineStreamsCardAdapter
     */
    override fun compareTo(other: StreamInfo): Int = currentViewers - other.currentViewers

    override fun toString(): String = userInfo.displayName
}
