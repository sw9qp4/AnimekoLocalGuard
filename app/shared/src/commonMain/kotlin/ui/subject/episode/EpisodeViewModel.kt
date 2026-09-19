/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.annotation.UiThread
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.comment.CommentReportTargetType
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.models.episode.renderEpisodeEp
import me.him188.ani.app.data.models.preference.VideoEnhancementDefaultMode
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.data.models.preference.parseMpvOptions
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectProgressInfo
import me.him188.ani.app.data.models.subject.nameCnOrName
import me.him188.ani.app.data.models.player.playProgressByEpisodeId
import me.him188.ani.app.data.network.AniCommentReportService
import me.him188.ani.app.data.network.AutoSkipRepository
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.app.data.repository.episode.EpisodeCommentRepository
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.comment.PostCommentUseCase
import me.him188.ani.app.domain.danmaku.DanmakuRepository
import me.him188.ani.app.domain.danmaku.SetDanmakuEnabledUseCase
import me.him188.ani.app.domain.episode.EpisodeCompletionContext.isKnownCompleted
import me.him188.ani.app.domain.episode.EpisodeDanmakuLoader
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.GetSubjectRecommendationUseCase
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeUseCase
import me.him188.ani.app.domain.episode.SubjectEpisodeInfoBundle
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.episodeIdFlow
import me.him188.ani.app.domain.episode.getCurrentEpisodeId
import me.him188.ani.app.domain.episode.infoBundleFlow
import me.him188.ani.app.domain.episode.infoLoadErrorFlow
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.domain.media.cache.EpisodeCacheStatus
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.fetch.MediaSourceResultsFilterer
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.mediasource.GetPreferredWebMediaSourceUseCase
import me.him188.ani.app.domain.mediasource.instance.GetMediaSourceInstancesUseCase
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.player.CacheProgressProvider
import me.him188.ani.app.domain.player.extension.AnalyticsExtension
import me.him188.ani.app.domain.player.extension.AutoSelectExtension
import me.him188.ani.app.domain.player.extension.CacheOnBtPlayExtension
import me.him188.ani.app.domain.player.extension.MarkAsWatchedExtension
import me.him188.ani.app.domain.player.extension.ObserveWebMediaSourcePreferenceExtension
import me.him188.ani.app.domain.player.extension.PlaybackSpeedExtension
import me.him188.ani.app.domain.player.extension.RememberPlayProgressExtension
import me.him188.ani.app.domain.player.extension.SaveMediaPreferenceExtension
import me.him188.ani.app.domain.player.extension.SwitchMediaOnPlayerErrorExtension
import me.him188.ani.app.domain.player.extension.SwitchNextEpisodeExtension
import me.him188.ani.app.domain.player.extension.WatchTogetherPlayerExtension
import me.him188.ani.app.domain.settings.GetDanmakuRegexFilterListFlowUseCase
import me.him188.ani.app.domain.settings.GetMediaSelectorSettingsUseCase
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.domain.watchtogether.PlaybackAutomationGate
import me.him188.ani.app.navigation.EpisodeNavigationGuardRegistry
import me.him188.ani.app.platform.Context
import me.him188.ani.app.ui.comment.BangumiCommentSticker
import me.him188.ani.app.ui.comment.CommentEditorState
import me.him188.ani.app.ui.comment.CommentMapperContext
import me.him188.ani.app.ui.comment.CommentMapperContext.parseToUIComment
import me.him188.ani.app.ui.comment.CommentMapperContext.toCommentVoteValue
import me.him188.ani.app.ui.comment.CommentReportState
import me.him188.ani.app.ui.comment.CommentState
import me.him188.ani.app.ui.comment.EditCommentSticker
import me.him188.ani.app.ui.comment.UICommentSource
import me.him188.ani.app.ui.comment.reportSnapshotText
import me.him188.ani.app.ui.comment.toDataReason
import me.him188.ani.app.ui.danmaku.DanmakuSendStyle
import me.him188.ani.app.ui.danmaku.UIDanmakuEvent
import me.him188.ani.app.ui.danmaku.toDanmakuSendStyle
import me.him188.ani.app.ui.episode.PlayingEpisodeSummary
import me.him188.ani.app.ui.episode.danmaku.MatchingDanmakuPresenter
import me.him188.ani.app.ui.episode.danmaku.MatchingDanmakuUiState
import me.him188.ani.app.ui.episode.share.MediaShareData
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.foundation.HasBackgroundScope
import me.him188.ani.app.ui.foundation.launchInBackground
import me.him188.ani.app.ui.foundation.lists.PaginatedGroup
import me.him188.ani.app.ui.foundation.stateOf
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceInfoProvider
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresenter
import me.him188.ani.app.ui.mediafetch.ViewKind
import me.him188.ani.app.ui.mediafetch.createTestMediaSelectorState
import me.him188.ani.app.ui.mediaselect.summary.MediaSelectorSummary
import me.him188.ani.app.ui.mediaselect.summary.MediaSelectorSummaryStateProducer
import me.him188.ani.app.ui.mediaselect.summary.selectedMaybeExcludedMediaFlow
import me.him188.ani.app.ui.settings.danmaku.DanmakuRegexFilterState
import me.him188.ani.app.ui.subject.AiringLabelState
import me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeState
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsStateFactory
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsStateLoader
import me.him188.ani.app.ui.subject.episode.details.DanmakuListState
import me.him188.ani.app.ui.subject.episode.details.DanmakuListStateProducer
import me.him188.ani.app.ui.subject.episode.details.EpisodeCarouselState
import me.him188.ani.app.ui.subject.episode.details.EpisodeDetailsState
import me.him188.ani.app.ui.subject.episode.statistics.DanmakuStatistics
import me.him188.ani.app.ui.subject.episode.statistics.VideoStatistics
import me.him188.ani.app.ui.subject.episode.statistics.VideoStatisticsCollector
import me.him188.ani.app.ui.subject.episode.video.PlayerSkipOpEdState
import me.him188.ani.app.ui.subject.episode.video.sidesheet.EpisodeSelectorState
import me.him188.ani.app.ui.user.SelfInfoStateProducer
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.app.videoplayer.player.applyMpvOptions
import me.him188.ani.app.videoplayer.player.isMpv
import me.him188.ani.app.videoplayer.ui.ControllerVisibility
import me.him188.ani.app.videoplayer.ui.PlayerControllerState
import me.him188.ani.app.videoplayer.videoenhancement.VideoEnhancementMode
import me.him188.ani.app.videoplayer.videoenhancement.createVideoEnhancementController
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuEvent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
// AnimekoLocalGuard: local danmaku guard (unofficial added module)
import me.him188.ani.app.data.repository.danmaku.GuardConfigRepository
import me.him188.ani.app.data.repository.danmaku.LocalGuardEpisodeOrder
import me.him188.ani.danmaku.localguard.cache.AnalyzerVersion
import me.him188.ani.danmaku.localguard.cache.BoundedSemanticsCache
import me.him188.ani.danmaku.localguard.knowledge.KnowledgeLoadResult
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgeSource
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.policy.DanmakuGuardSession
import me.him188.ani.danmaku.localguard.policy.GuardRequest
import me.him188.ani.danmaku.localguard.policy.GuardStatus
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.GuardUserConfig
import me.him188.ani.danmaku.localguard.policy.InMemoryGuardConfigSource
import me.him188.ani.danmaku.ui.DanmakuConfig
import me.him188.ani.danmaku.ui.DanmakuHostState
import me.him188.ani.danmaku.ui.DanmakuPresentation
import me.him188.ani.danmaku.ui.DanmakuTrackProperties
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.isDoneOrDropped
import me.him188.ani.utils.coroutines.SingleTaskExecutor
import me.him188.ani.utils.coroutines.flows.FlowRestarter
import me.him188.ani.utils.coroutines.flows.flowOfEmptyList
import me.him188.ani.utils.coroutines.flows.flowOfNull
import me.him188.ani.utils.coroutines.flows.restartable
import me.him188.ani.utils.coroutines.sampleWithInitial
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.annotations.TestOnly
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.chapters
import org.openani.mediamp.metadata.Chapter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


private const val OP_ED_AUTO_SKIP_BASE_SAMPLE_INTERVAL_MILLIS = 1_000L

private fun opEdAutoSkipSampleIntervalMillis(playbackSpeed: Float): Long {
    val effectiveSpeed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (OP_ED_AUTO_SKIP_BASE_SAMPLE_INTERVAL_MILLIS / effectiveSpeed).toLong().coerceAtLeast(1L)
}


@Stable
data class EpisodePageState(
    val selfInfo: SelfInfoUiState,
    val mediaSelectorState: MediaSelectorState,
    val mediaSourceResultListPresentation: MediaSourceResultListPresentation,
    val danmakuStatistics: DanmakuStatistics,
    val subjectPresentation: SubjectPresentation,
    val episodePresentation: EpisodePresentation,
    val danmakuEnabled: Boolean,
    val danmakuConfig: DanmakuConfig,
    val isLoading: Boolean = false,
    val loadError: EpisodePageLoadError? = null,
    val isPlaceholder: Boolean = false,
    val playingEpisodeSummary: PlayingEpisodeSummary?, // null means placeholder TODO: should distinguish placeholder
    val mediaSelectorSummary: MediaSelectorSummary,
    val initialMediaSelectorViewKind: ViewKind,
    val matchingDanmakuPresenter: MatchingDanmakuPresenter?,
    val matchingDanmakuUiState: MatchingDanmakuUiState?,
    val fetchRequest: MediaFetchRequest?,
    val shareData: MediaShareData,
)

/**
 * 播放页的加载错误
 */
sealed class EpisodePageLoadError {
    /**
     * 关键的条目和剧集信息加载错误.
     *
     * 这只包含 [SubjectEpisodeInfoBundle.subjectInfo] 和 [SubjectEpisodeInfoBundle.episodeInfo].
     *
     * 这两个信息是极其关键的信息, 如果加载错误就无法显示整个页面.
     */
    data class SubjectError(
        val loadError: LoadError,
    ) : EpisodePageLoadError()

    /**
     * [SubjectEpisodeInfoBundle.seriesInfo] 或者 [SubjectEpisodeInfoBundle.subjectCompleted] 等用来让查询更准确的信息加载错误.
     *
     * 缺少这些信息仍然可以继续查询和播放, 只是不太准确.
     * 注意, 这可能会在离线播放时发生.
     */
    data class SeriesError(
        val loadError: LoadError,
    ) : EpisodePageLoadError()
}

/**
 * AnimekoLocalGuard: version of the danmaku semantics analysis.
 *
 * Participates in the semantics cache key, so any change to the model, the text normalisation or
 * the tokenizer **must** be reflected here — otherwise cached verdicts from the previous pipeline
 * would be reused silently.
 *
 * The current values describe the prototype honestly: there is no model yet, so nothing is
 * analysed by meaning and the provider returns null. It is not a claim that a model exists.
 */
private val LocalGuardAnalyzerVersion = AnalyzerVersion(
    modelId = "none",
    modelVersion = "0",
    normalizationVersion = "prototype-1",
    tokenizerVersion = "none",
)

/**
 * 要查看有关剧集播放页的详细信息，请参阅 PR 文档 [#1439](https://github.com/open-ani/animeko/pull/1439).
 *
 * @see EpisodeFetchSelectPlayState
 */
@Stable
class EpisodeViewModel(
    val subjectId: Int,
    initialEpisodeId: Int,
    initialIsFullscreen: Boolean = false,
    context: Context,
    val getCurrentDate: () -> PackedDate = { PackedDate.now() },
    private val koin: Koin = GlobalKoin,
) : KoinComponent, AbstractViewModel(), HasBackgroundScope {
    // region dependencies
    private val playerStateFactory: MediampPlayerFactory<*> by inject()
    private val episodeCollectionRepository: EpisodeCollectionRepository by inject()
    private val downloadManager: MediaDownloadManager by inject()
    private val danmakuRepository: DanmakuRepository by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val danmakuRegexFilterRepository: DanmakuRegexFilterRepository by inject()
    private val episodePlayHistoryRepository: EpisodePlayHistoryRepository by inject()
    private val mediaSourceManager: MediaSourceManager by inject()
    private val episodeCommentRepository: EpisodeCommentRepository by inject()
    private val commentReportService: AniCommentReportService by inject()
    private val subjectDetailsStateFactory: SubjectDetailsStateFactory by inject()
    private val setDanmakuEnabledUseCase: SetDanmakuEnabledUseCase by inject()
    private val postCommentUseCase: PostCommentUseCase by inject()
    private val autoSkipRepository: AutoSkipRepository by inject()
    private val getMediaSelectorSettings: GetMediaSelectorSettingsUseCase by inject()
    private val getMediaSourceInstances: GetMediaSourceInstancesUseCase by inject()
    private val selectorEpisodeCacheRepository: SelectorMediaSourceEpisodeCacheRepository by inject()
    val setEpisodeCollectionType: SetEpisodeCollectionTypeUseCase by inject()
    private val getSubjectRecommendations: GetSubjectRecommendationUseCase by inject()
    private val getDanmakuRegexFilterListFlowUseCase: GetDanmakuRegexFilterListFlowUseCase by inject()
    private val setSubjectCollectionTypeOrDeleteUseCase: SetSubjectCollectionTypeOrDeleteUseCase by inject()
    private val getPreferredWebMediaSource: GetPreferredWebMediaSourceUseCase by inject()
    private val webSessionManager: WebSessionManager by inject()
    private val playbackAutomationGate: PlaybackAutomationGate by inject()
    val playbackAutomationSuppressed get() = playbackAutomationGate.suppressed
    // endregion

    private val tasker = SingleTaskExecutor(backgroundScope.coroutineContext)

    val player: MediampPlayer = playerStateFactory
        .create(context, backgroundScope.coroutineContext)
        .apply {
            if (!isMpv()) return@apply
            // datastore 读取很快, 可以接受这里的 blocking coroutine
            runBlocking { applyCustomOptions() }
        }

    val videoEnhancement = createVideoEnhancementController(
        player,
        settingsRepository.playerKernelConfig.flow,
        backgroundScope.coroutineContext,
    )

    /** `null` 表示本次播放尚未调整过倍速, 此时跟随配置. */
    private val playbackSpeedOverride = MutableStateFlow<Float?>(null)

    /**
     * 当前生效的倍速. 作用域为一次播放 (本 ViewModel 的生命周期), 播放页内切集保持.
     */
    private val playbackSpeedFlow: Flow<Float> = combine(
        settingsRepository.videoScaffoldConfig.flow,
        playbackSpeedOverride,
    ) { config, override ->
        override ?: config.playbackSpeed
    }.distinctUntilChanged()

    @OptIn(UnsafeEpisodeSessionApi::class)
    private val fetchPlayState = EpisodeFetchSelectPlayState(
        subjectId, initialEpisodeId, player, backgroundScope,
        extensions = listOf(
            AnalyticsExtension,
            PlaybackSpeedExtension.Factory(playbackSpeedFlow),
            RememberPlayProgressExtension,
            WatchTogetherPlayerExtension,
            MarkAsWatchedExtension,
            CacheOnBtPlayExtension,
            SwitchNextEpisodeExtension.Factory(
                getNextEpisode = { currentEpisodeId ->
                    val list = episodeCollectionsFlow.first()
                    val subject = subjectCollectionFlow.first()
                    val currentIndex = list.indexOfFirst { it.episodeId == currentEpisodeId }
                    if (currentIndex == -1) {
                        null
                    } else {
                        val nextEpisode = list.getOrNull(currentIndex + 1) ?: return@Factory null

                        if (!nextEpisode.episodeInfo.isKnownCompleted(subject.recurrence)) {
                            null
                        } else {
                            nextEpisode.episodeId
                        }
                    }
                },
            ),
            SwitchMediaOnPlayerErrorExtension,
            AutoSelectExtension,
            SaveMediaPreferenceExtension,
            ObserveWebMediaSourcePreferenceExtension,
        ),
        koin,
        sharingStarted = SharingStarted.WhileSubscribed(5_000),
        analyticsContext = object : EpisodeFetchSelectPlayState.AnalyticsContext {
            override suspend fun isFullscreen(): Boolean? {
                return withContext(Dispatchers.Main) { this@EpisodeViewModel.isFullscreen }
            }
        },
    )

    val mediaResolver: MediaResolver get() = fetchPlayState.playerSession.mediaResolver

    // region Subject and episode data info flows
    @UnsafeEpisodeSessionApi
    private val episodeIdFlow get() = fetchPlayState.episodeIdFlow

    @UnsafeEpisodeSessionApi
    private val subjectEpisodeInfoBundleFlow: Flow<SubjectEpisodeInfoBundle?> get() = fetchPlayState.infoBundleFlow

    @UnsafeEpisodeSessionApi
    private val subjectEpisodeInfoBundleLoadErrorFlow = fetchPlayState.infoLoadErrorFlow
        .filterNotNull()
        .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), null)

    @UnsafeEpisodeSessionApi
    private val subjectCollectionFlow =
        subjectEpisodeInfoBundleFlow.filterNotNull().map { it.subjectCollectionInfo }
            .distinctUntilChanged()

    @UnsafeEpisodeSessionApi
    private val subjectInfoFlow = subjectCollectionFlow.map { it.subjectInfo }.distinctUntilChanged()

    @UnsafeEpisodeSessionApi
    private val episodeCollectionFlow = subjectEpisodeInfoBundleFlow.map { it?.episodeCollectionInfo }
        .distinctUntilChanged()

    private val episodeCollectionsFlow = episodeCollectionRepository.subjectEpisodeCollectionInfosFlow(subjectId)
        .shareInBackground()

    @UnsafeEpisodeSessionApi
    private val episodeInfoFlow = episodeCollectionFlow.map { it?.episodeInfo }.distinctUntilChanged()
    // endregion


    val playerControllerState = PlayerControllerState(ControllerVisibility.Invisible)
    private val mediaSourceInfoProvider: MediaSourceInfoProvider = MediaSourceInfoProvider(
        getSourceInfoFlow = { mediaSourceManager.infoFlowByMediaSourceId(it) },
    )

    val cacheProgressInfoFlow = CacheProgressProvider(
        player, backgroundScope,
    ).cacheProgressInfoFlow

    /**
     * "视频统计" bottom sheet 显示内容
     */
    @OptIn(UnsafeEpisodeSessionApi::class)
    val videoStatisticsFlow: Flow<VideoStatistics> = VideoStatisticsCollector(
        fetchPlayState.mediaSelectorFlow
            .filterNotNull(), // // TODO: 2025/1/3 check filterNotNull
        fetchPlayState.playerSession.videoLoadingState,
        player,
        mediaSourceInfoProvider,
        mediaSourceLoading = fetchPlayState.episodeSessionFlow.flatMapLatest { it.mediaSourceLoadingFlow },
        backgroundScope,
    ).videoStatisticsFlow

    val videoScaffoldConfig: VideoScaffoldConfig by settingsRepository.videoScaffoldConfig
        .flow.produceState(VideoScaffoldConfig.Default)

    /** 当前生效的用户倍速范围. */
    val playbackSpeedRange: ClosedFloatingPointRange<Float>
        get() = videoScaffoldConfig.minPlaybackSpeed..videoScaffoldConfig.maxPlaybackSpeed

    /** 总是对本次播放生效; 仅在开启「记住播放倍速」时才另外写回配置. */
    fun setPlaybackSpeed(speed: Float) {
        playbackSpeedOverride.value = speed
        launchInBackground {
            if (settingsRepository.videoScaffoldConfig.flow.first().rememberPlaybackSpeed) {
                settingsRepository.videoScaffoldConfig.update {
                    copy(playbackSpeed = speed)
                }
            }
        }
    }

    /**
     * 桌面端: 用户是否通过播放器内按钮开启了窗口置顶. 退出播放页时需要自动取消置顶.
     */
    var desktopAlwaysOnTopSetByPlayer: Boolean = false

    val playerVolumeFlow: Flow<VideoScaffoldConfig.PlayerVolume> =
        settingsRepository.videoScaffoldConfig.flow.map { it.playerVolume }

    val danmakuRegexFilterState = DanmakuRegexFilterState(
        list = danmakuRegexFilterRepository.flow.produceState(emptyList()),
        add = {
            launchInBackground { danmakuRegexFilterRepository.add(it) }
        },
        edit = { regex, filter ->
            launchInBackground {
                danmakuRegexFilterRepository.update(filter.id, filter.copy(regex = regex))
            }
        },
        remove = {
            launchInBackground { danmakuRegexFilterRepository.remove(it) }
        },
        switch = {
            launchInBackground {
                danmakuRegexFilterRepository.update(it.id, it.copy(enabled = !it.enabled))
            }
        },
        onExport = { danmakuRegexFilterRepository.export() },
        onImport = { danmakuRegexFilterRepository.import(it) },
    )


    private val selfInfoFlow = SelfInfoStateProducer(koin = getKoin()).flow

    private fun initialMediaSelectorViewKindFlow(): Flow<ViewKind> =
        settingsRepository.mediaSelectorSettings.flow.map { settings ->
            when (settings.preferKind) {
                MediaSourceKind.WEB -> ViewKind.WEB
                MediaSourceKind.BitTorrent -> ViewKind.BT
                MediaSourceKind.LocalCache -> ViewKind.WEB
                null -> ViewKind.WEB
            }
        }


    @OptIn(UnsafeEpisodeSessionApi::class)
    val episodeDetailsState: EpisodeDetailsState = run {
        EpisodeDetailsState(
            subjectInfo = subjectInfoFlow.produceState(SubjectInfo.Empty),
            airingLabelState = AiringLabelState(
                subjectCollectionFlow.map { it.airingInfo }.produceState(null),
                subjectCollectionFlow.map {
                    SubjectProgressInfo.compute(it.subjectInfo, it.episodes, getCurrentDate(), it.recurrence)
                }
                    .produceState(null),
            ),
            recommendations = subjectInfoFlow.map { getSubjectRecommendations(it.subjectId) }.produceState(emptyList()),
            subjectDetailsStateLoader = SubjectDetailsStateLoader(subjectDetailsStateFactory, backgroundScope),
        )
    }

    /**
     * 剧集列表分页分组
     */
    @OptIn(UnsafeEpisodeSessionApi::class)
    val episodeGroups = episodeCollectionsFlow.map { episodes ->
        episodes.chunked(100).mapIndexed { groupIndex, chunk ->
            val startItemIndex = groupIndex * 100
            val startEp = groupIndex * 100 + 1
            val endEp = startEp + chunk.size - 1
            PaginatedGroup(
                title = "第 $startEp-$endEp 话",
                items = chunk,
                startIndex = startItemIndex,
                groupIndex = groupIndex,
            )
        }
    }.produceState(emptyList())

    /**
     * 剧集列表
     */
    @OptIn(UnsafeEpisodeSessionApi::class)
    val episodeCarouselState: EpisodeCarouselState = run {
        val episodeCacheStatusListState by episodeCollectionsFlow.flatMapLatest { list ->
            if (list.isEmpty()) {
                return@flatMapLatest flowOfEmptyList()
            }
            combine(
                list.map { collection ->
                    downloadManager.downloadStatusForEpisode(subjectId, collection.episodeId).map {
                        collection.episodeId to it
                    }
                },
            ) {
                it.toList()
            }
        }.produceState(emptyList())

        // 只订阅本条目剧集的播放记录, 换算成按剧集 id 索引的进度
        val playProgressByEpisodeId by episodeCollectionsFlow
            .map { list -> list.map { it.episodeId } }
            .distinctUntilChanged()
            .flatMapLatest { episodeIds -> episodePlayHistoryRepository.flowByEpisodeIds(episodeIds) }
            .map { it.playProgressByEpisodeId() }
            .produceState(emptyMap())

        val collectionButtonEnabled = MutableStateFlow(false)
        EpisodeCarouselState(
            episodes = episodeCollectionsFlow.produceState(emptyList()),
            playProgress = { playProgressByEpisodeId[it.episodeId] },
            playingEpisode = episodeIdFlow.combine(episodeCollectionsFlow) { id, collections ->
                collections.firstOrNull { it.episodeId == id }
            }.produceState(null),
            cacheStatus = {
                episodeCacheStatusListState.firstOrNull { status ->
                    status.first == it.episodeInfo.episodeId
                }?.second ?: EpisodeCacheStatus.NotCached
            },
            onSelect = {
                launchInBackground {
                    switchEpisode(it.episodeInfo.episodeId)
                }
            },
            onChangeCollectionType = { episode, it ->
                collectionButtonEnabled.value = false
                launchInBackground {
                    try {
                        episodeCollectionRepository.setEpisodeCollectionType(
                            subjectId,
                            episodeId = episode.episodeInfo.episodeId,
                            collectionType = it,
                        )
                    } finally {
                        collectionButtonEnabled.value = true
                    }
                }
            },
            backgroundScope = backgroundScope,
            groupsState = episodeGroups,
        )
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    val editableSubjectCollectionTypeState: EditableSubjectCollectionTypeState =
        EditableSubjectCollectionTypeState(
            selfCollectionTypeFlow = subjectCollectionFlow
                .map { it.collectionType },
            hasAnyUnwatched = {
                val collections =
                    episodeCollectionsFlow.firstOrNull() ?: return@EditableSubjectCollectionTypeState true
                collections.any { !it.collectionType.isDoneOrDropped() }
            },
            onSetSelfCollectionType = { setSubjectCollectionTypeOrDeleteUseCase(subjectId, it) },
            onSetAllEpisodesWatched = {
                episodeCollectionRepository.setAllEpisodesWatched(subjectId)
            },
            backgroundScope,
        )

    var isFullscreen: Boolean by mutableStateOf(initialIsFullscreen)
    var sidebarVisible: Boolean by mutableStateOf(true)
    val commentLazyGirdState: LazyGridState = LazyGridState()

    /**
     * 播放器内切换剧集
     */
    @OptIn(UnsafeEpisodeSessionApi::class)
    val episodeSelectorState: EpisodeSelectorState = EpisodeSelectorState(
        itemsFlow = episodeCollectionsFlow.combine(subjectCollectionFlow) { list, subject ->
            list.map {
                it.toPresentation(subject.recurrence)
            }
        },
        onSelect = {
            launchInBackground {
                switchEpisode(it.episodeId)
            }
        },
        currentEpisodeId = episodeIdFlow,
        parentCoroutineContext = backgroundScope.coroutineContext,
    )


    @OptIn(UnsafeEpisodeSessionApi::class)
    private val episodeDanmakuLoader = EpisodeDanmakuLoader(
        player = player,
        // TODO: 2025/1/6 this is not very good. May see old data. 
        selectedMedia = fetchPlayState.mediaSelectorFlow.transformLatest {
            if (it == null) {
                emit(null)
            } else {
                emitAll(it.selected)
            }
        },
        bundleFlow = fetchPlayState.infoBundleFlow.filterNotNull().distinctUntilChanged(),
        danmakuRepository = danmakuRepository,
        getDanmakuRegexFilterListFlowUseCase = getDanmakuRegexFilterListFlowUseCase,
        backgroundScope,
        sharingStarted = SharingStarted.WhileSubscribed(5_000),
    )

    // region AnimekoLocalGuard: local danmaku guard (unofficial added module)

    /**
     * Config source for the local danmaku guard.
     *
     * Persisted (a dedicated key in Animeko's preferences store), so the switch and tier survive
     * restarts. Off by default, so default behaviour is identical to upstream.
     *
     * Resolved as the concrete type rather than the `GuardConfigSource` interface: Koin's type
     * parameter has to be resolvable from this module, and `app-data` does not re-export the
     * localguard module's types here.
     */
    val localGuardConfigSource: GuardConfigRepository by inject()

    /**
     * Semantics cache for the guard, alive for the whole playback session.
     *
     * Deliberately session-scoped rather than app-scoped: the isolation guarantees (fact
     * relations never cross works/episodes) are enforced by the keys, but keeping the cache
     * short-lived also bounds memory and makes "switch episode -> re-derive" trivially correct.
     */
    private val localGuardSemanticsCache = BoundedSemanticsCache()

    /**
     * Story knowledge for the guard.
     *
     * Injected rather than constructed so the data source (bundled assets, downloaded packs,
     * tests) can be swapped without touching playback code.
     */
    private val localGuardKnowledgeSource: StoryKnowledgeSource by inject()

    /**
     * Story knowledge currently bound to the guard session.
     *
     * Loaded per work (keyed by the upstream `subjectId`, which is the stable work identity) and
     * re-used across episodes of the same subject. Held in a field rather than re-read per danmaku
     * because the decision path must not do file IO.
     */
    @Volatile
    private var localGuardKnowledge: StoryKnowledgePack? = null

    /** Alignment currently bound to the guard session. Unaligned until a source provides one. */
    @Volatile
    private var localGuardAlignment: TimeAlignment = TimeAlignment.Unaligned

    private var localGuardKnowledgeLoadStarted = false

    /**
     * Loads knowledge for this subject once, off the main thread.
     *
     * One-shot: the subject does not change within one playback page, so there is nothing to
     * re-load on episode switches. The result is applied to the session on the next `startEpisode`
     * call, which happens before any danmaku are judged.
     *
     * Called from the view model's existing `init` block rather than a separate one — a view model
     * may only have a single `init` block.
     */
    private fun ensureLocalGuardKnowledgeLoaded() {
        if (localGuardKnowledgeLoadStarted) return
        localGuardKnowledgeLoadStarted = true
        backgroundScope.launch {
            try {
                loadLocalGuardKnowledge()
            } catch (e: Throwable) {
                // Failure to load must never take playback down; it is capability degradation.
                logger.warn(e) { "Failed to load local guard knowledge; continuing without it" }
            }
        }
    }

    /**
     * Loads knowledge + alignment for this subject and binds them to the guard.
     *
     * Work identity is the upstream `subjectId`: it is stable and already available here, so a
     * separate mapping table is not needed. Passing a real work id also enables the fact-relation
     * cache layer, which is keyed by work id and must not be a placeholder.
     *
     * Failures are surfaced as capability degradation, never as "no data so anything goes":
     * an unusable pack leaves knowledge null, and the session then reports KNOWLEDGE_MISSING.
     */
    private suspend fun loadLocalGuardKnowledge() {
        val workId = subjectId.toString()
        localGuardKnowledge = when (val result = localGuardKnowledgeSource.load(workId)) {
            is KnowledgeLoadResult.Loaded -> result.pack
            is KnowledgeLoadResult.Unusable -> {
                logger.warn { "Local guard knowledge pack unusable for subject $workId: ${result.reason}" }
                null
            }

            KnowledgeLoadResult.Absent -> null
        }
        // Null alignment stays Unaligned: never treat "not configured" as zero offset.
        localGuardAlignment = localGuardKnowledgeSource.loadAlignment(workId) ?: TimeAlignment.Unaligned
    }

    /**
     * Filter session for the current playback session.
     *
     * In this prototype stage `semanticsProvider` is not set (returns null = no semantic
     * information) and no knowledge pack is bundled yet. Therefore even with the switch ON
     * the module will not guess from text, and the capability state must honestly report the
     * degraded state. These are real capability limits, not a sign of completion.
     *
     * The semantics cache is real and active: repeated danmaku text is only analysed once per
     * session, and entries are invalidated on episode change / model version change. See
     * `GuardSessionCacheTest` for the isolation guarantees.
     */
    val localGuardSession = DanmakuGuardSession(
        configSource = localGuardConfigSource,
        scope = backgroundScope,
        semanticsCache = localGuardSemanticsCache,
        analyzerVersion = LocalGuardAnalyzerVersion,
        // Work id participates in the fact-relation cache key; a placeholder here would let one
        // subject's fact verdicts be reused for another, which is exactly what must not happen.
        workId = subjectId.toString(),
    )

    /** Last episode number synchronised to the guard session. */
    private var localGuardSyncedEpisodeNumber: Double? = null

    /**
     * Tracks the current episode number and synchronises it to the guard session.
     *
     * `episodeInfoFlow` is a COLD flow (no `.value`), so it cannot be read synchronously on the
     * decision path. This turns it into a hot StateFlow: it keeps following episode changes in
     * the background and still allows a synchronous read when deciding.
     */
    @OptIn(UnsafeEpisodeSessionApi::class)
    private val localGuardEpisodeNumberFlow: StateFlow<Double?> = episodeInfoFlow
        .map { info: EpisodeInfo? -> localGuardEpisodeNumberOf(info) }
        .distinctUntilChanged()
        .onEach { episodeNumber: Double? -> syncLocalGuardEpisode(episodeNumber) }
        .stateIn(backgroundScope, SharingStarted.Eagerly, null)

    /**
     * Extracts the knowledge-pack episode number for the current episode.
     *
     * Delegates to [LocalGuardEpisodeOrder], which owns the "which upstream field is the
     * knowledge-pack episode number" decision. Reading `ep`/`sort` inline used to be done here,
     * and it got two things wrong: it ignored that `ep` (within-season) and `sort` (series-wide)
     * are different numbering systems, and it treated a special episode's number as an ordinary
     * episode number. Both directions matter — either one can mark a fact as already revealed when
     * it is not, or block danmaku for an episode that has nothing to do with it.
     *
     * Returns null when the episode cannot be mapped (a special without an explicit position in
     * the knowledge pack, or an unparsable sort). The session treats "episode unknown" as a
     * capability degradation rather than reusing the previous episode, so null is the safe answer.
     */
    private fun localGuardEpisodeNumberOf(info: EpisodeInfo?): Double? {
        if (info == null) return null
        return LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(ep = info.ep, sort = info.sort)
    }

    /**
     * Everything the settings UI needs to describe the guard: on/off, tier, capability state and
     * counters.
     *
     * Built by combining the three independent signals the session reads, rather than by pushing
     * snapshots from the playback path. A pushed refresh would be easy to forget and would leave
     * the UI showing a stale state; deriving the snapshot from these flows cannot drift.
     *
     * The snapshot contains no danmaku text and no story facts, so it is safe to display.
     */
    val localGuardStatusFlow: StateFlow<GuardStatus> = combine(
        localGuardConfigSource.config,
        localGuardSession.counters,
        localGuardEpisodeNumberFlow,
    ) { _, _, _ -> localGuardSession.captureStatus() }
        .stateIn(backgroundScope, SharingStarted.Eagerly, localGuardSession.captureStatus())

    /** Master switch. Off by default; turning it on cannot make the guard claim more than it can do. */
    fun setLocalGuardEnabled(enabled: Boolean) {
        backgroundScope.launch { localGuardConfigSource.setEnabled(enabled) }
    }

    /** Selects the strictness tier. */
    fun setLocalGuardTier(tier: GuardTier) {
        backgroundScope.launch { localGuardConfigSource.setTier(tier) }
    }

    /**
     * Synchronises the current episode to the guard session.
     *
     * A null episode is ignored: an unknown episode is a capability degradation, and the session
     * treats "episode unknown" as "do not guess" rather than reusing the previous episode.
     *
     * `startEpisode` is only called when the episode number actually CHANGES, because it bumps
     * the generation and resets counters; calling it repeatedly would pollute the statistics.
     */
    private fun syncLocalGuardEpisode(episodeNumber: Double?) {
        if (episodeNumber == null) return
        if (localGuardSyncedEpisodeNumber == episodeNumber) return
        localGuardSyncedEpisodeNumber = episodeNumber
        localGuardSession.startEpisode(
            episodeNumber = episodeNumber,
            knowledge = localGuardKnowledge,
            alignment = localGuardAlignment,
        )
    }

    /**
     * Decides whether one danmaku is allowed to be displayed.
     *
     * This is the module's entry point on upstream's danmaku pipelines, and it runs before
     * display. Failures and timeouts are always treated as "do not display"; the detailed
     * accounting lives in [DanmakuGuardSession].
     *
     * @param data the danmaku under decision.
     * @param positionMillis playback position used for the timeline decision.
     *   For the overlay: for `Add` events this is the position at decision time; for `Repopulate`
     *   it must be the position carried by the event itself. For the danmaku list it is the
     *   current position when the list is rendered.
     */
    private fun localGuardShouldDisplay(
        data: DanmakuInfo,
        positionMillis: Long,
    ): Boolean {
        // Cheap early-out: no work at all while the feature is off.
        if (!localGuardConfigSource.current.enabled) return true

        return try {
            localGuardSession.shouldDisplay(
                GuardRequest(
                    id = data.id,
                    text = data.text,
                    decisionPositionMillis = positionMillis,
                ),
            )
        } catch (_: Throwable) {
            // The guard itself failed while the user has it ON. Every remaining danmaku is
            // unreviewed, and unreviewed content must not be displayed (master prompt §12):
            // silently showing the original danmaku while claiming protection is exactly what
            // that rule forbids. So fail closed here, and keep the client itself working.
            false
        }
    }
    // endregion

    /**
     * Danmaku event flow to be processed by UI DanmakuHost.
     *
     * AnimekoLocalGuard (unofficial added module) hooks local filtering in here. The hook point
     * satisfies three necessary conditions:
     * 1. It runs AFTER upstream's existing filtering (regex / blank text / provider switches),
     *    so this module can only ever subtract.
     * 2. It runs BEFORE danmaku reach `DanmakuHostState`, so nothing is "shown then hidden".
     * 3. Both `Add` and `Repopulate` funnel through here, so seeking cannot bypass filtering.
     *
     * The master switch is OFF by default, in which case behaviour is exactly upstream's.
     */
    val uiDanmakuEventFlow = danmakuRepository.selfId.flatMapLatest { selfId ->
        fun createDanmakuPresentation(
            data: DanmakuInfo,
            selfId: String?,
        ) = DanmakuPresentation(data, isSelf = selfId == data.senderId)

        episodeDanmakuLoader.danmakuEventFlow.mapNotNull { event ->
            when (event) {
                is DanmakuEvent.Add -> {
                    val data = event.danmaku
                    if (data.text.isBlank()) {
                        null
                    } else if (
                        !localGuardShouldDisplay(
                            data,
                            // Add: use the player position at decision time.
                            player.currentPositionMillis.value,
                        )
                    ) {
                        // Blocked: never becomes a UIDanmakuEvent, so it never reaches display.
                        null
                    } else {
                        UIDanmakuEvent.Add(createDanmakuPresentation(data, selfId))
                    }
                }

                is DanmakuEvent.Repopulate -> {
                    // Position semantics: use the event's OWN playTimeMillis (the player time when
                    // the event was produced) instead of re-reading the current position, so that
                    // "decision time" and "event time" cannot disagree.
                    val position = event.playTimeMillis
                    UIDanmakuEvent.Repopulate(
                        event.list
                            .filter { it.text.any { c -> !c.isWhitespace() } }
                            .filter { localGuardShouldDisplay(it, position) }
                            .map { createDanmakuPresentation(it, selfId) },
                        withContext(Dispatchers.Main) {
                            player.currentPositionMillis.value
                        },
                    )
                }
            }
        }
    }.shareInBackground(
        started = SharingStarted.WhileSubscribed(5000), // Must be some time, because when switching full-screen (i.e. configuration change), UI may stop collect for some milliseconds.
        replay = 1,
    ) // This is lazy. If user puts app into background, queries will abort.

    /**
     * Danmaku list (`DanmakuListItem`) content source.
     *
     * This is the **second** real text entrance: the list renders every danmaku's original text
     * (`DanmakuListItem.content = danmaku.text`), sorted by time. Scrolling it to the end would
     * otherwise expose exactly the danmaku the overlay is hiding, so it must go through the same
     * guard. Master prompt §5 requires covering *all* real text entrances, not just the overlay.
     *
     * Position semantics: the danmaku's own `playTimeMillis` is deliberately **not** used as the
     * decision position. A list entry whose play time is in the future would then be judged as if
     * that future moment had already happened, i.e. the list would unlock content the viewer has
     * not reached — a leak. The current playback position is used instead, which is strictly more
     * conservative: the list shows exactly what the overlay is allowed to show right now.
     *
     * When the guard is off this returns the upstream list unchanged (off = upstream behaviour).
     */
    val allDanmakuListFlow = combine(
        episodeDanmakuLoader.allDanmakuFlow,
        danmakuRepository.selfId,
    ) { danmakuList, selfId ->
        if (!localGuardConfigSource.current.enabled) {
            danmakuList.map {
                DanmakuPresentation(it, isSelf = selfId == it.senderId)
            }
        } else {
            // Reading the position here is a cheap StateFlow read on the producing coroutine,
            // never on the UI thread.
            val position = player.currentPositionMillis.value
            danmakuList
                .filter { localGuardShouldDisplay(it, position) }
                .map { DanmakuPresentation(it, isSelf = selfId == it.senderId) }
        }
    }.shareInBackground(
        started = SharingStarted.WhileSubscribed(5000),
        replay = 1,
    )

    val danmakuListStateProducer = DanmakuListStateProducer(
        danmakuFlow = allDanmakuListFlow,
        fetchResultsFlow = episodeDanmakuLoader.fetchResults,
    )

    val danmakuListState = danmakuListStateProducer.stateFlow
        .stateIn(
            backgroundScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DanmakuListState.Loading,
        )


    private val commentStateRestarter = FlowRestarter()
    private val commentLoadFailureChannel = Channel<Throwable>(Channel.BUFFERED)

    @OptIn(UnsafeEpisodeSessionApi::class)
    val episodeCommentState: CommentState = CommentState(
        list = episodeIdFlow
            .restartable(commentStateRestarter)
            .flatMapLatest { episodeId ->
                episodeCommentRepository.subjectEpisodeCommentsPager(
                    episodeId.toLong(),
                    // Ani 评论正常但服务端没取到 Bangumi 评论: 列表照常显示, 额外提示一次, 免得看起来像"没有评论"
                    onBangumiUnavailable = {
                        commentLoadFailureChannel.trySend(
                            RepositoryServiceUnavailableException("Bangumi episode comments unavailable"),
                        )
                    },
                )
                    .map { page -> page.map { it.parseToUIComment() } }
            }.cachedIn(backgroundScope),
        countState = stateOf(null),
        onSubmitCommentReaction = { comment, value, selected ->
            // Bangumi 评论只读, 不支持提交表情回应
            if (comment.source == UICommentSource.ANI) {
                episodeCommentRepository.submitReaction(
                    // 用评论所属集而非当前播放集: 自动连播/页内切集后两者可能不一致
                    episodeId = comment.episodeId ?: episodeIdFlow.first().toLong(),
                    commentId = comment.sourceCommentId,
                    value = value,
                    selected = selected,
                )
            }
        },
        backgroundScope = backgroundScope,
        commentLoadFailures = commentLoadFailureChannel.receiveAsFlow(),
        onSubmitCommentVote = { comment, vote ->
            // Bangumi 评论只读, 不支持点赞
            if (comment.source == UICommentSource.ANI) {
                episodeCommentRepository.submitVote(
                    episodeId = comment.episodeId ?: episodeIdFlow.first().toLong(),
                    commentId = comment.sourceCommentId,
                    vote = vote?.toCommentVoteValue(),
                )
            }
        },
    )

    @OptIn(UnsafeEpisodeSessionApi::class)
    val commentReportState: CommentReportState = CommentReportState(
        onSubmitReport = { comment, reason, detail ->
            commentReportService.createReport(
                targetType = CommentReportTargetType.EPISODE_COMMENT,
                targetId = comment.sourceCommentId,
                reason = reason.toDataReason(),
                commentAuthorId = comment.author?.id,
                detail = detail.takeIf { it.isNotEmpty() },
                contentSnapshot = comment.reportSnapshotText(),
                subjectId = subjectId.toLong(),
                // 举报里的 episodeId 必须是评论所属集
                episodeId = comment.episodeId ?: episodeIdFlow.first().toLong(),
            )
        },
        backgroundScope = backgroundScope,
    )

    @OptIn(UnsafeEpisodeSessionApi::class)
    val commentEditorState: CommentEditorState = CommentEditorState(
        showExpandEditCommentButton = true,
        initialEditExpanded = false,
        panelTitle = subjectInfoFlow
            .combine(episodeInfoFlow) { sub, epi -> "${sub.displayName} ${epi?.renderEpisodeEp()}" }
            .produceState(null),
        stickers = flowOf(BangumiCommentSticker.map { EditCommentSticker(it.first, it.second) })
            .produceState(emptyList()),
        richTextRenderer = { text ->
            withContext(Dispatchers.Default) {
                with(CommentMapperContext) { parseBBCode(text) }
            }
        },
        onSend = { context, content -> postCommentUseCase(context, content) },
        backgroundScope = backgroundScope,
    )

    // Combine original chapters with AutoSkip rules fetched from server
    @OptIn(UnsafeEpisodeSessionApi::class, InternalMediampApi::class)
    private val autoSkipChaptersFlow: Flow<List<Chapter>> = combine(
        fetchPlayState.episodeSessionFlow.flatMapLatest { session ->
            autoSkipRepository.rulesFlow(session.episodeId)
        },
        player.mediaProperties.mapNotNull { it?.durationMillis?.milliseconds },
        settingsRepository.videoScaffoldConfig.flow
            .map { it.opEdSkipDuration }
            .distinctUntilChanged(),
    ) { millisecondTimes, videoLength, opEdSkipDuration ->
        val durationMillis = when {
            videoLength > 20.minutes -> opEdSkipDuration.inWholeMilliseconds
            videoLength > 10.minutes -> 55_000L
            else -> 0L
        }
        if (durationMillis == 0L) {
            emptyList()
        } else {
            millisecondTimes.mapIndexed { index, t ->
                val name = if (millisecondTimes.size == 2) {
                    val anotherIndex = if (index == 0) 1 else 0
                    if (t <= millisecondTimes[anotherIndex]) {
                        "OP"
                    } else {
                        "ED"
                    }
                } else {
                    "Ch ${index + 1}"
                }
                Chapter(
                    name,
                    durationMillis,
                    t,
                )
            }
        }
    }.catch {
        logger.warn(it) { "Failed to fetch AutoSkip chapters" }
    }


    private val combinedChaptersFlow: Flow<List<Chapter>> =
        combine(
            (player.chapters ?: flowOf(emptyList())),
            flow {
                emit(emptyList()) // 先给个空列表, 避免刚开始时因为等待网络而没有进度
                emitAll(autoSkipChaptersFlow)
            },
        ) { a, b -> if (b.isEmpty()) a else (a + b) }

    // Chapters to be displayed on progress slider (merged with AutoSkip rules)
    val progressChaptersFlow: Flow<List<Chapter>> = combinedChaptersFlow

    val playerSkipOpEdState: PlayerSkipOpEdState = PlayerSkipOpEdState(
        chapters = combinedChaptersFlow.produceState(emptyList()),
        onSkip = {
            launchInBackground(Dispatchers.Main) {
                player.seekTo(it)
            }
        },
        videoLength = player.mediaProperties.mapNotNull { it?.durationMillis?.milliseconds }
            .produceState(0.milliseconds),
    )

    private val matchingDanmakuProviderId = MutableStateFlow<DanmakuProviderId?>(null)

    val pageState = fetchPlayState.episodeSessionFlow.transformLatest { episodeSession ->
        logger.info { "Switching to new episodeSession ${episodeSession.episodeId}" }
        coroutineScope {
            emitAll(createPageStateFlow(episodeSession))
            awaitCancellation()
        }
    }.stateIn(backgroundScope, started = SharingStarted.WhileSubscribed(5_000), null)

    private val danmakuConfigState = mutableStateOf(DanmakuConfig.Default)
    val danmakuHostState = DanmakuHostState(danmakuConfigState, DanmakuTrackProperties.Default)

    private fun CoroutineScope.createPageStateFlow(episodeSession: EpisodeSession): Flow<EpisodePageState> {
        // 保证数据源会一直查询, 否则会显示许多 CANCELLED 日志
        episodeSession.fetchSelectFlow.flatMapLatest {
            it?.mediaFetchSession?.cumulativeResults ?: flowOfEmptyList()
        }.launchIn(this)

        val filteredSourceResults = MediaSourceResultsFilterer(
            results = episodeSession.fetchSelectFlow.map {
                it?.mediaFetchSession?.mediaSourceResults ?: emptyList()
            },
            settings = settingsRepository.mediaSelectorSettings.flow,
            flowScope = this,
        ).filteredSourceResults
            .shareIn(this, started = SharingStarted.Lazily, replay = 1)

        val mediaSourceResultsFlow = MediaSourceResultListPresenter(
            filteredSourceResults,
            getPreferredWebMediaSource(subjectId),
        ).presentationFlow
            .shareIn(this, SharingStarted.Lazily, replay = 1)

        val matchingDanmakuPresenter = matchingDanmakuProviderId.map { providerId ->
            episodeDanmakuLoader
                .getInteractiveDanmakuFetcherOrNull(providerId)
                ?.startInteractiveMatch()
                ?.let { MatchingDanmakuPresenter(it, this) }
        }.shareIn(this, started = SharingStarted.Lazily, replay = 1)

        val mediaSelectorSummaryStateProducer = MediaSelectorSummaryStateProducer(
            episodeSession.fetchSelectFlow.mapNotNull { it?.mediaSelector }
                .flatMapLatest { it.selectedMaybeExcludedMediaFlow }
                .onStart { emit(null) },
            filteredSourceResults,
            getMediaSelectorSettings(),
            getMediaSourceInstances.getAsMediaSourceInfoWithId(),
        ).flow.stateIn(
            this,
            started = SharingStarted.Lazily,
            initialValue = MediaSelectorSummary.AutoSelecting(listOf(), estimate = 10.seconds),
        )

        val selectedMediaFlow =
            episodeSession.fetchSelectFlow.flatMapLatest { it?.mediaSelector?.selected ?: flowOfNull() }
        return me.him188.ani.utils.coroutines.flows.combine(
            selfInfoFlow,
            episodeSession.infoBundleFlow.distinctUntilChanged().onStart { emit(null) },
            episodeSession.infoLoadErrorStateFlow,
            episodeSession.fetchSelectFlow,
            combine(
                episodeDanmakuLoader.danmakuLoadingStateFlow,
                episodeDanmakuLoader.fetchResults,
                settingsRepository.danmakuEnabled.flow,
                ::DanmakuStatistics,
            ).distinctUntilChanged(),
            settingsRepository.danmakuEnabled.flow,
            settingsRepository.danmakuConfig.flow,
            episodeSession.fetchSelectFlow.map { fetchSelect ->
                if (fetchSelect != null) {
                    MediaSelectorState(
                        fetchSelect.mediaSelector,
                        filteredSourceResults,
                        mediaSourceInfoProvider,
                        getPreferredWebMediaSource(subjectId),
                        backgroundScope,
                        webSessionManager,
                    )
                } else {
                    // TODO: 2025/1/22 We should not use createTestMediaSelectorState
                    @OptIn(TestOnly::class)
                    createTestMediaSelectorState(backgroundScope)
                }
            },
            mediaSourceResultsFlow.map { MediaSourceResultListPresentation(it) },
            mediaSelectorSummaryStateProducer,
            initialMediaSelectorViewKindFlow(),
            matchingDanmakuPresenter,
            matchingDanmakuPresenter.flatMapLatest { it?.uiState ?: flowOfNull() },
            combine(selectedMediaFlow, player.mediaData) { selectedMedia, mediaData ->
                MediaShareData.from(selectedMedia, mediaData)
            },
        ) { authState, subjectEpisodeBundle, subjectLoadError, fetchSelect, danmakuStatistics, danmakuEnabled, danmakuConfig, mediaSelectorState, mediaSourceResultsPresentation, mediaSelectorSummary, initialMediaSelectorViewKind, matchingDanmakuPresenter, matchingDanmaku, shareData ->

            val (subject, episode) = if (subjectEpisodeBundle == null) {
                SubjectPresentation.Placeholder to EpisodePresentation.Placeholder
            } else { // modern JVM will optimize out the Pair creation
                Pair(
                    subjectEpisodeBundle.subjectInfo.toPresentation(),
                    subjectEpisodeBundle.episodeCollectionInfo.toPresentation(subjectEpisodeBundle.subjectCollectionInfo.recurrence),
                )
            }

            if (subjectLoadError != null) { // TODO: 2025/1/6 display load error in UI 
                logger.warn { "InfoBundle load error: $subjectLoadError" }
            }

            fun getLoadError(): EpisodePageLoadError? {
                // 注意, 这是有显示优先级的. 优先显示重大错误.
                subjectLoadError?.let {
                    return EpisodePageLoadError.SubjectError(subjectLoadError)
                }
                return null
            }

            EpisodePageState(
                selfInfo = authState,
                mediaSelectorState = mediaSelectorState,
                mediaSourceResultListPresentation = mediaSourceResultsPresentation,
                danmakuStatistics = danmakuStatistics,
                subjectPresentation = subject,
                episodePresentation = episode,
                danmakuEnabled = danmakuEnabled,
                danmakuConfig = danmakuConfig,
                isLoading = subjectEpisodeBundle == null,
                loadError = getLoadError(),
                playingEpisodeSummary = if (subjectEpisodeBundle == null) {
                    null
                } else {
                    PlayingEpisodeSummary(
                        episodeSort = subjectEpisodeBundle.episodeInfo.sort,
                        episodeName = subjectEpisodeBundle.episodeInfo.displayName,
                        subjectName = subjectEpisodeBundle.subjectInfo.displayName,
                        subjectTags = listOf(), // todo: tags, see figma
                        subjectCoverUrl = subjectEpisodeBundle.subjectInfo.imageLarge,
                        rating = subjectEpisodeBundle.subjectInfo.ratingInfo,
                        selfRatingInfo = subjectEpisodeBundle.subjectCollectionInfo.selfRatingInfo,
                    )
                },
                mediaSelectorSummary = mediaSelectorSummary,
                initialMediaSelectorViewKind = initialMediaSelectorViewKind,
                matchingDanmakuPresenter = matchingDanmakuPresenter,
                matchingDanmakuUiState = matchingDanmaku?.copy(
                    initialQuery = subjectEpisodeBundle?.subjectInfo?.nameCnOrName ?: "",
                ),
                fetchRequest = fetchSelect?.mediaFetchSession?.request?.first(),
                shareData = shareData,
            )
        }
    }

    suspend fun switchEpisode(episodeId: Int) {
        // 页内切集不经过 AniNavigator, 需在此单独过导航守卫 (如一起看跟随中只能去 host 所在集);
        // 引导性的切集走 extension 的 context.switchEpisode, 不经过这里, 不受影响.
        if (!EpisodeNavigationGuardRegistry.checkOrNotifyDenied(subjectId, episodeId)) return
        // 在后台 dispatchers 中操作
        backgroundScope.launch {
            fetchPlayState.switchEpisode(episodeId)
        }.join()
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    suspend fun postDanmaku(danmaku: DanmakuContent): DanmakuInfo {
        return withContext(Dispatchers.Default) {
            danmakuRepository.post(fetchPlayState.getCurrentEpisodeId(), danmaku)
        }
    }

    /**
     * 发送弹幕时使用的样式 (颜色, 位置), 持久化在 [SettingsRepository.danmakuSettings].
     */
    val danmakuSendStyleFlow: Flow<DanmakuSendStyle> =
        settingsRepository.danmakuSettings.flow.map { it.toDanmakuSendStyle() }

    fun setDanmakuSendStyle(style: DanmakuSendStyle) {
        launchInBackground {
            settingsRepository.danmakuSettings.update {
                copy(sendColor = style.color, sendLocation = style.location)
            }
        }
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        launchInBackground {
            setDanmakuEnabledUseCase(enabled)
        }
    }

    fun savePlayerVolume(volume: Float, mute: Boolean) {
        launchInBackground {
            tasker.invoke {
                delay(200)
                settingsRepository.videoScaffoldConfig
                    .update { copy(playerVolume = VideoScaffoldConfig.PlayerVolume(volume, mute)) }
            }
        }
    }

    fun refreshFetch() {
        launchInBackground {
            // 手动重新查询: 清除本条目的 web 源搜索缓存, 让所有数据源真正重新搜索
            selectorEpisodeCacheRepository.clearByRequestedSubject(subjectId)
            // Although it's flow, it should be ready.
            fetchPlayState.episodeSessionFlow.flatMapLatest { it.fetchSelectFlow }
                .mapNotNull { it?.mediaFetchSession }
                .firstOrNull()
                ?.restartAll()
        }
    }

    /**
     * UI handler for the "skip OP/ED" button.
     * Reports the action to server with throttling and then performs the seek.
     */
    @OptIn(UnsafeEpisodeSessionApi::class)
    fun onClickSkipOpEd(currentPositionMillis: Long) {
        val skipDuration = videoScaffoldConfig.opEdSkipDuration
        // Seek immediately for UX
        player.skip(skipDuration.inWholeMilliseconds)
        // Report in background
        launchInBackground {
            logger.info {
                "Reporting skip ${skipDuration.inWholeSeconds} at ${currentPositionMillis / 1000}s"
            }
            val episodeId = fetchPlayState.getCurrentEpisodeId()
            val selected = fetchPlayState.episodeSessionFlow.firstOrNull()
                ?.fetchSelectFlow
                ?.firstOrNull()
                ?.mediaSelector
                ?.selected
                ?.firstOrNull()
            // 拖入的本地文件不对应任何数据源, 其时间轴不应计入该剧集的跳过统计
            if (selected == null || DroppedFileMedia.isDroppedFile(selected)) return@launchInBackground
            val mediaSourceId = selected.mediaSourceId
            val timeSeconds = (currentPositionMillis / 1000).toInt()
            if (timeSeconds < 0 || timeSeconds > 200 * 60) {
                logger.warn {
                    "Refusing to report skip ${skipDuration.inWholeSeconds} at invalid time ${timeSeconds}s"
                }
                return@launchInBackground
            }
            autoSkipRepository.reportSkip(episodeId, mediaSourceId, timeSeconds, currentPositionMillis)
        }
    }

    fun restartSource(instanceId: String) {
        launchInBackground {
            val result = fetchPlayState.episodeSessionFlow.flatMapLatest { it.fetchSelectFlow }
                .mapNotNull { it?.mediaFetchSession }
                .firstOrNull()
                ?.mediaSourceResults
                ?.find { it.instanceId == instanceId }
                ?: return@launchInBackground
            // 手动刷新单个源: 只清除该源的搜索缓存, 让它真正重新搜索, 不影响其他源的缓存
            selectorEpisodeCacheRepository.clearByRequestedSubjectAndSource(subjectId, result.mediaSourceId)
            result.restart()
        }
    }

    /**
     * 在当前剧集播放用户拖入的本地视频文件 [file], 不经过数据源选择.
     *
     * 只对当前剧集的本次播放有效: 不更新数据源偏好, 之后仍可在数据源选择器中换回其他资源;
     * 切换剧集或重新进入播放页后照常自动选择数据源. 若剧集信息加载完成前切换了剧集, 则放弃播放.
     */
    fun playDroppedFile(file: SystemPath) {
        launchInBackground {
            val session = fetchPlayState.episodeSessionFlow.value
            val mediaSelector = fetchPlayState.episodeSessionFlow
                .mapLatest { current ->
                    if (current !== session) return@mapLatest null
                    current.fetchSelectFlow.filterNotNull().first().mediaSelector
                }
                .first()
                ?: return@launchInBackground
            logger.info { "Playing dropped file: $file" }
            mediaSelector.selectTemporarily(DroppedFileMedia.create(file))
        }
    }

    fun onUIReady() {
        fetchPlayState.onUIReady()
    }

    @UiThread
    suspend fun collectDanmakuConfig() {
        pageState
            .filterNotNull()
            .collect { state ->
                danmakuConfigState.value = state.danmakuConfig
            }
    }

    init {
        // AnimekoLocalGuard. Runs here (not via an `onRemembered` hook) because view models
        // obtained through `viewModel {}` are never remembered, so such a hook would silently
        // never run. Loading the persisted switch early also lets the synchronous `current`
        // answer without waiting on the danmaku decision path.
        backgroundScope.launch {
            localGuardConfigSource.start()
        }
        ensureLocalGuardKnowledgeLoaded()

        launchInBackground {
            val defaultMode = settingsRepository.videoScaffoldConfig.flow
                .first()
                .videoEnhancementDefaultMode
            videoEnhancement?.setMode(
                when (defaultMode) {
                    VideoEnhancementDefaultMode.OFF -> VideoEnhancementMode.OFF
                    VideoEnhancementDefaultMode.PERFORMANCE -> VideoEnhancementMode.PERFORMANCE
                    VideoEnhancementDefaultMode.QUALITY -> VideoEnhancementMode.QUALITY
                },
            )
        }

        // 跳过 OP 和 ED
        launchInBackground {
            settingsRepository.videoScaffoldConfig.flow
                .map { it.autoSkipOpEd }
                .distinctUntilChanged()
                .debounce(1000)
                .collectLatest { enabled ->
                    if (!enabled) return@collectLatest

                    // 根据当前倍速调整采样间隔, 使其在媒体时间线上对应一秒.
                    val positionSamples = player.features[PlaybackSpeed]?.let { playbackSpeed ->
                        playbackSpeed.valueFlow
                            .onStart { emit(playbackSpeed.value) }
                            .distinctUntilChanged()
                            .flatMapLatest { speed ->
                                player.currentPositionMillis.sampleWithInitial(
                                    opEdAutoSkipSampleIntervalMillis(speed),
                                )
                            }
                    } ?: player.currentPositionMillis.sampleWithInitial(
                        OP_ED_AUTO_SKIP_BASE_SAMPLE_INTERVAL_MILLIS,
                    )
                    @OptIn(UnsafeEpisodeSessionApi::class)
                    combine(
                        positionSamples,
                        episodeIdFlow,
                        episodeCollectionsFlow,
                    ) { pos, id, collections ->
                        // 不止一集并且当前是第一集时不跳过
                        if (collections.size > 1 && collections.getOrNull(0)?.episodeId == id) return@combine
                        if (!playbackAutomationGate.suppressed.value) playerSkipOpEdState.update(pos)
                    }.collect()
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        videoEnhancement?.close()
        webSessionManager.cancelAutoSolves()
        backgroundScope.launch(NonCancellable + CoroutineName("EpisodeViewModel#onCleared")) {
            fetchPlayState.onClose()
        }
    }

    override fun getKoin(): Koin = koin

    fun setDanmakuSourceEnabled(serviceId: DanmakuServiceId, enabled: Boolean) {
        episodeDanmakuLoader.setEnabled(serviceId, enabled)
    }

    fun setDanmakuSourceShiftMillis(serviceId: DanmakuServiceId, shiftMillis: Long) {
        episodeDanmakuLoader.setShiftMillis(serviceId, shiftMillis)
    }

    fun startMatchingDanmaku(id: DanmakuProviderId) {
        matchingDanmakuProviderId.value = id
    }

    fun cancelMatchingDanmaku() {
        matchingDanmakuProviderId.value = null
    }

    fun onMatchingDanmakuComplete(provider: DanmakuProviderId, result: List<DanmakuFetchResult>) {
        episodeDanmakuLoader.overrideResults(provider, result)
        cancelMatchingDanmaku()
    }

    fun updateFetchRequest(request: MediaFetchRequest) {
        launchInBackground {
            // 编辑查询条件后会重启所有源的搜索, 同样清除本条目的 web 源搜索缓存
            selectorEpisodeCacheRepository.clearByRequestedSubject(subjectId)
            fetchPlayState.episodeSessionFlow
                .firstOrNull()
                ?.fetchSelectFlow
                ?.firstOrNull()
                ?.mediaFetchSession
                ?.setFetchRequest(request)
        }
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    fun retryLoad(error: EpisodePageLoadError) {
        launchInBackground {
            when (error) {
                is EpisodePageLoadError.SeriesError -> {
                    fetchPlayState.restartLoad()
                }

                is EpisodePageLoadError.SubjectError -> {
                    fetchPlayState.restartLoad()
                }
            }
        }
    }

    private suspend fun MediampPlayer.applyCustomOptions() {
        val config = try {
            settingsRepository.playerKernelConfig.flow.map { it.mpvOptions }.first()
        } catch (e: Exception) {
            if (e !is CancellationException) logger.warn(e) { "Failed to get custom mpv options." }
            return
        }

        applyMpvOptions(parseMpvOptions(config))
    }
}
