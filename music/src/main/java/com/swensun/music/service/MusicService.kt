package com.swensun.music.service

import MusicLibrary
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import com.swensun.music.MusicHelper
import putDuration


class MusicService : MediaBrowserServiceCompat() {

    private var mRepeatMode: Int = PlaybackStateCompat.REPEAT_MODE_NONE
    /**
     * 播放状态，通过 MediaSession 回传给 UI 端。
     */
    private var mState = PlaybackStateCompat.Builder().build()
    /**
     * UI 可能被销毁，Service 需要保存播放列表，并处理循环模式
     */
    private var mPlayList = arrayListOf<MediaSessionCompat.QueueItem>()
    /**
     * 当前播放音乐的相关信息
     */
    private var mMusicIndex = -1
    private var mCurrentMedia: MediaSessionCompat.QueueItem? = null
    /**
     * 播放会话，将播放状态信息回传给 UI 端。
     */
    private lateinit var mSession: MediaSessionCompat
    /**
     * 真正的音乐播放器
     */
    private var mMediaPlayer: MediaPlayer = MediaPlayer()
    /**
     * 前台通知的相关内容
     */
    private lateinit var mNotificationManager: MediaNotificationManager
    /**
     * 音频焦点处理
     */
    private lateinit var mAudioFocusHelper: AudioFocusHelper


    /**
     * 记录耳机按下的次数
     */
    private var mHeadSetClickCount = 0

    private var handler = HeadSetHandler()

    inner class HeadSetHandler: Handler() {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            // 根据耳机按下的次数决定执行什么操作
            when(mHeadSetClickCount) {
                1 -> {
                    if (mMediaPlayer.isPlaying) {
                        mSessionCallback.onPause()
                    } else {
                        mSessionCallback.onPlay()
                    }
                }
                2 -> {
                    mSessionCallback.onSkipToNext()
                }
                3 -> {
                    mSessionCallback.onSkipToPrevious()
                }
                4 -> {
                    mSessionCallback.onSkipToPrevious()
                    mSessionCallback.onSkipToPrevious()
                }
            }
            mHeadSetClickCount = 0
        }
    }

    /**
     * 播放控制器的事件回调，UI 端通过播放控制器发出的指令会在这里接收到，交给真正的音乐播放器处理。
     */
    private var mSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            val action = mediaButtonEvent?.action
            val keyevent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            MusicHelper.log("action: $action, keyEvent: $keyevent")

            return if (keyevent?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ) {
                if (keyevent.action == KeyEvent.ACTION_UP) {
                    //耳机单机操作
                    mHeadSetClickCount += 1
                    if (mHeadSetClickCount == 1) {
                        handler.sendEmptyMessageDelayed(1, 800)
                    }
                }
                true
            } else {
                super.onMediaButtonEvent(mediaButtonEvent)
            }

        }

        override fun onRewind() {
            super.onRewind()
        }

        override fun onSeekTo(pos: Long) {
            super.onSeekTo(pos)
            mMediaPlayer.seekTo(pos.toInt())
            setNewState(mState.state)
        }

        override fun onAddQueueItem(description: MediaDescriptionCompat) {
            super.onAddQueueItem(description)
            // 客户端添加歌曲
            if (mPlayList.find { it.description.mediaId == description.mediaId } == null) {
                mPlayList.add(
                    MediaSessionCompat.QueueItem(description, description.hashCode().toLong())
                )
            }
            mMusicIndex = if (mMusicIndex == -1) 0 else mMusicIndex
            mSession.setQueue(mPlayList)
        }

        override fun onRemoveQueueItem(description: MediaDescriptionCompat?) {
            super.onRemoveQueueItem(description)
            mPlayList.remove(MediaSessionCompat.QueueItem(description, description.hashCode().toLong()))
            mMusicIndex = if (mPlayList.isEmpty()) -1 else mMusicIndex
            mSession.setQueue(mPlayList)

        }

        override fun onSkipToPrevious() {
            super.onSkipToPrevious()
            if (mPlayList.isEmpty()) {
                MusicHelper.log("not playlist")
                return
            }
            mMusicIndex = if (mMusicIndex > 0) mMusicIndex - 1 else mPlayList.size - 1
            mCurrentMedia = null
            onPlay()
        }

        override fun onSkipToNext() {
            super.onSkipToNext()
            if (mPlayList.isEmpty()) {
                MusicHelper.log("not playlist")
                return
            }
            mMusicIndex = (++mMusicIndex % mPlayList.size)
            mCurrentMedia = null
            onPlay()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            super.onCustomAction(action, extras)
        }

        override fun onPrepare() {
            super.onPrepare()
            if (mPlayList.isEmpty()) {
                MusicHelper.log("not playlist")
                return
            }
            if (mMusicIndex < 0 || mMusicIndex >= mPlayList.size) {
                MusicHelper.log("media index error")
                return
            }
            mCurrentMedia = mPlayList[mMusicIndex]
            val uri = mCurrentMedia?.description?.mediaUri
            MusicHelper.log("uri, $uri")
            if (uri == null) {
                return
            }
            // 加载资源要重置
            mMediaPlayer.reset()
            try {
                if (uri.toString().startsWith("http")) {
                    mMediaPlayer.setDataSource(applicationContext, uri)
                } else {
                    //  assets 资源
                    val assetFileDescriptor = applicationContext.assets.openFd(uri.toString())
                    mMediaPlayer.setDataSource(
                        assetFileDescriptor.fileDescriptor,
                        assetFileDescriptor.startOffset,
                        assetFileDescriptor.length
                    )
                }
                mMediaPlayer.prepare()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onFastForward() {
            super.onFastForward()
        }

        override fun onPlay() {
            super.onPlay()
            if (mCurrentMedia == null) {
                onPrepare()
            }
            if (mCurrentMedia == null) {
                return
            }
            if (mAudioFocusHelper.requestAudioFocus()) {
                mMediaPlayer.start()
                setNewState(PlaybackStateCompat.STATE_PLAYING)
            }
        }

        override fun onPause() {
            super.onPause()
            if (!mAudioFocusHelper.mPlayOnAudioFocus) {
                mAudioFocusHelper.abandonAudioFocus()
            }
            mMediaPlayer.pause()
            setNewState(PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onStop() {
            super.onStop()
            mAudioFocusHelper.abandonAudioFocus()
            mMediaPlayer.stop()
            setNewState(PlaybackStateCompat.STATE_STOPPED)
        }

        override fun onSkipToQueueItem(id: Long) {
            super.onSkipToQueueItem(id)
        }


        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
            super.onPrepareFromMediaId(mediaId, extras)
        }

        override fun onSetRepeatMode(repeatMode: Int) {
            super.onSetRepeatMode(repeatMode)
            mRepeatMode = repeatMode
        }

        override fun onCommand(command: String?, extras: Bundle?, cb: ResultReceiver?) {
            super.onCommand(command, extras, cb)
        }

        override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
            super.onPrepareFromSearch(query, extras)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            super.onPlayFromMediaId(mediaId, extras)
            MusicHelper.log("cur mp3: ${mCurrentMedia?.description?.mediaUri}")
            if (mediaId == mCurrentMedia?.description?.mediaId) {
                // 同一首歌曲
                if (!mMediaPlayer.isPlaying) {
                    onPlay()
                    return
                }
            }
            mMusicIndex = mPlayList.indexOfFirst { it.description.mediaId == mediaId}
            mCurrentMedia = null
            onPlay()
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            super.onSetShuffleMode(shuffleMode)
        }

        override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
            super.onPrepareFromUri(uri, extras)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            super.onPlayFromSearch(query, extras)
        }

        override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
            super.onPlayFromUri(uri, extras)
        }

        override fun onSetRating(rating: RatingCompat?) {
            super.onSetRating(rating)
        }

        override fun onSetRating(rating: RatingCompat?, extras: Bundle?) {
            super.onSetRating(rating, extras)
        }

        override fun onSetCaptioningEnabled(enabled: Boolean) {
            super.onSetCaptioningEnabled(enabled)
        }
    }

    // 播放器的回调
    private var mCompletionListener: MediaPlayer.OnCompletionListener =
        MediaPlayer.OnCompletionListener {
            MusicHelper.log("OnCompletionListener")
            setNewState(PlaybackStateCompat.STATE_PAUSED)
            when (mRepeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> {
                    mSessionCallback.onPlay()
                }
                PlaybackStateCompat.REPEAT_MODE_ALL -> {
                    mSessionCallback.onSkipToNext()
                }
                PlaybackStateCompat.REPEAT_MODE_NONE -> {
                    if (mMusicIndex != mPlayList.size - 1) {
                        mSessionCallback.onSkipToNext()
                    }
                }
            }
        }
    private var mPreparedListener: MediaPlayer.OnPreparedListener =
        MediaPlayer.OnPreparedListener {
            val mediaId = mCurrentMedia?.description?.mediaId ?: ""
            val metadata = MusicLibrary.getMeteDataFromId(mediaId)
            mSession.setMetadata(metadata.putDuration(mMediaPlayer.duration.toLong()))
            mSessionCallback.onPlay()
        }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        MusicHelper.log("onLoadChildren, $parentId")
        result.detach()
        val list = mPlayList.map { MediaBrowserCompat.MediaItem(it.description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE) }
        result.sendResult(list as MutableList<MediaBrowserCompat.MediaItem>?)
        mCurrentMedia?.let {
            val mediaId = it?.description?.mediaId ?: ""
            val metadata = MusicLibrary.getMeteDataFromId(mediaId)
            mSession.setMetadata(metadata.putDuration(mMediaPlayer.duration.toLong()))
            setNewState(mState.state)
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot("MusicService", null)
    }

    override fun onCreate() {
        super.onCreate()
        mSession = MediaSessionCompat(applicationContext, "MusicService")
        mSession.setCallback(mSessionCallback)
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS)
        sessionToken = mSession.sessionToken
        mMediaPlayer.setOnCompletionListener(mCompletionListener)
        mMediaPlayer.setOnPreparedListener(mPreparedListener)
        mMediaPlayer.setOnErrorListener { mp, what, extra -> true }
        mNotificationManager = MediaNotificationManager(this)
        mAudioFocusHelper = AudioFocusHelper(this)
    }

    override fun onDestroy() {
        handler.removeMessages(1)
        super.onDestroy()
    }

    /**
     * 根据当前播放状态，设置 MediaSession 支持的相关操作
     */
    @PlaybackStateCompat.Actions
    private fun getAvailableActions(state: Int): Long {
        var actions = (PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        actions = when (state) {
            PlaybackStateCompat.STATE_STOPPED -> actions or (PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
            PlaybackStateCompat.STATE_PLAYING -> actions or (PlaybackStateCompat.ACTION_STOP
                    or PlaybackStateCompat.ACTION_PAUSE
                    or PlaybackStateCompat.ACTION_SEEK_TO)
            PlaybackStateCompat.STATE_PAUSED -> actions or (PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP)
            else -> actions or (PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PLAY_PAUSE
                    or PlaybackStateCompat.ACTION_STOP
                    or PlaybackStateCompat.ACTION_PAUSE)
        }
        return actions
    }

    private fun setNewState(state: Int) {
        val stateBuilder = PlaybackStateCompat.Builder()
        stateBuilder.setActions(getAvailableActions(state))
        stateBuilder.setState(
            state,
            mMediaPlayer.currentPosition.toLong(),
            1.0f,
            SystemClock.elapsedRealtime()
        )
        mState = stateBuilder.build()
        mSession.setPlaybackState(mState)

        sessionToken?.let {
            val description = mCurrentMedia?.description ?: MediaDescriptionCompat.Builder().build()
            when(state) {
                PlaybackStateCompat.STATE_PLAYING -> {
                    val notification = mNotificationManager.getNotification(description, mState, it)
                    ContextCompat.startForegroundService(
                        this@MusicService,
                        Intent(this@MusicService, MusicService::class.java)
                    )
                    startForeground(MediaNotificationManager.NOTIFICATION_ID, notification)
                }
                PlaybackStateCompat.STATE_PAUSED -> {
                    val notification = mNotificationManager.getNotification(
                        description, mState, it
                    )
                    mNotificationManager.notificationManager
                        .notify(MediaNotificationManager.NOTIFICATION_ID, notification)
                }
                PlaybackStateCompat.STATE_STOPPED ->  {
                    stopSelf()
                }
            }
        }
    }

    /**
     * Helper class for managing audio focus related tasks.
     */
    private inner class AudioFocusHelper(val context: Context) : AudioManager.OnAudioFocusChangeListener {

        private val MEDIA_VOLUME_DUCK: Float = 0.2f
        private val MEDIA_VOLUME_DEFAULT: Float = 1.0f
        public var mPlayOnAudioFocus: Boolean = false
        private var mAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private var mAudioNoisyReceiverRegistered = false

        /**
         * 耳机拨出的相关广播
         */
        private var AUDIO_NOISY_INTENT_FILTER =
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

        private var mAudioNoisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                    if (mMediaPlayer.isPlaying) {
                        mSessionCallback.onPause()
                    }
                }
            }
        }

        fun requestAudioFocus(): Boolean {
            registerAudioNoisyReceiver()
            val result = mAudioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

         fun abandonAudioFocus() {
             unregisterAudioNoisyReceiver()
            mAudioManager.abandonAudioFocus(this)
        }

        override fun onAudioFocusChange(focusChange: Int) {
            when (focusChange) {
                /**
                 * 获取音频焦点
                 */
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (mPlayOnAudioFocus && !mMediaPlayer.isPlaying) {
                        mSessionCallback.onPlay()
                    } else if (mMediaPlayer.isPlaying) {
                        setVolume(MEDIA_VOLUME_DEFAULT)
                    }
                    mPlayOnAudioFocus = false
                }
                /**
                 * 暂时失去音频焦点，但可降低音量播放音乐，类似导航模式
                 */
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(MEDIA_VOLUME_DUCK)
                /**
                 * 暂时失去音频焦点，一段时间后会重新获取焦点，比如闹钟
                 */
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (mMediaPlayer.isPlaying) {
                    mPlayOnAudioFocus = true
                    mSessionCallback.onPause()
                }
                /**
                 * 失去焦点
                 */
                AudioManager.AUDIOFOCUS_LOSS -> {
                    mAudioManager.abandonAudioFocus(this)
                    mPlayOnAudioFocus = false
                    // 这里暂停播放
                    mSessionCallback.onPause()
                }
            }
        }

        private fun setVolume(volume: Float) {
            mMediaPlayer.setVolume(volume, volume)
        }

        fun registerAudioNoisyReceiver() {
            if (!mAudioNoisyReceiverRegistered) {
                context.registerReceiver(mAudioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER)
                mAudioNoisyReceiverRegistered = true
            }
        }

        fun unregisterAudioNoisyReceiver() {
            if (mAudioNoisyReceiverRegistered) {
                context.unregisterReceiver(mAudioNoisyReceiver)
                mAudioNoisyReceiverRegistered = false
            }
        }
    }
}