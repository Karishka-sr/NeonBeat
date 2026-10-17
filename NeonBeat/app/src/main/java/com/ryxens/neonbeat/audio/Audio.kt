package com.ryxens.neonbeat.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.media.SoundPool
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.core.Songs
import com.ryxens.neonbeat.core.Synth
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/** Готовый звук: моно, 16 бит. */
class Pcm(val data: ShortArray, val rate: Int) {
    val durationMs: Int get() = (data.size * 1000L / rate).toInt()
}

/** Достаёт звук из аудиофайла (mp3, aac, ogg, flac, wav — всё, что умеет телефон). */
object AudioDecoder {
    private const val MAX_MINUTES = 12

    fun decode(file: File): Pcm? {
        if (!file.exists()) return null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var out = ShortArray(rate * 30)
            var n = 0
            var inputDone = false
            var outputDone = false
            val limit = rate * 60 * MAX_MINUTES
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = sb.remaining() / Math.max(1, channels)
                        if (n + frames > out.size) {
                            out = out.copyOf(Math.max(out.size * 2, n + frames))
                        }
                        for (f in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += sb.get().toInt()
                            out[n++] = (sum / channels).toShort()
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    if (n > limit) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            if (n < rate) return null
            return Pcm(out.copyOf(n), rate)
        } catch (e: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Throwable) { }
            try { codec?.release() } catch (e: Throwable) { }
            try { extractor?.release() } catch (e: Throwable) { }
        }
    }
}

/** Загрузка музыки карты: встроенная песня синтезируется, файл — декодируется. */
object AudioLoader {
    private var key: String? = null
    private var cached: Pcm? = null

    /** Тяжёлая операция — вызывать не из главного потока. */
    @Synchronized
    fun load(spec: String): Pcm? {
        if (spec.isEmpty()) return null
        if (spec == key && cached != null) return cached
        val pcm: Pcm? = when {
            spec.startsWith("builtin:") -> {
                val def = Songs.byId(spec.substring(8))
                if (def == null) null else Pcm(Synth.render(Songs.composition(def)), Synth.RATE)
            }
            spec.startsWith("file:") -> AudioDecoder.decode(File(Env.audioDir(), spec.substring(5)))
            else -> null
        }
        key = spec
        cached = pcm
        return pcm
    }

    fun forget() { key = null; cached = null }
}

/**
 * Транспорт: общие «часы» игры и редактора. Если есть музыка — время берётся
 * от звукового трека (так ноты не расходятся со звуком), иначе от системного таймера.
 * Время песни может быть отрицательным: это «разгон» перед началом музыки.
 */
class Transport {
    private var track: AudioTrack? = null
    private var rate = 44100
    private var leadInMs = 0
    private var frames = 0
    private var volume = 1f

    var speed = 1f
        private set
    var playing = false
        private set
    var durationMs = 0
        private set

    private var pausedMs = 0.0
    private var anchorMs = 0.0
    private var anchorNano = 0L
    private var lastHead = -1
    private var needSnap = false

    val hasAudio: Boolean get() = track != null

    fun load(pcm: Pcm?, leadIn: Int): Boolean {
        release()
        leadInMs = leadIn
        pausedMs = -leadIn.toDouble()
        if (pcm == null) return true
        return try {
            rate = pcm.rate
            val lead = (rate.toLong() * leadIn / 1000L).toInt()
            val total = lead + pcm.data.size
            val buf = ShortArray(total)
            System.arraycopy(pcm.data, 0, buf, lead, pcm.data.size)
            frames = total
            durationMs = pcm.durationMs
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(total * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val w = t.write(buf, 0, total)
            if (w <= 0) {
                t.release()
                false
            } else {
                t.setVolume(volume)
                track = t
                true
            }
        } catch (e: Throwable) {
            track = null
            false
        }
    }

    fun setVolume(v: Float) {
        volume = v
        try { track?.setVolume(v) } catch (e: Throwable) { }
    }

    fun setSpeed(s: Float) { speed = s }

    private fun applySpeed() {
        val t = track ?: return
        try {
            val p = PlaybackParams()
            p.setSpeed(speed)
            p.setPitch(1f)
            t.playbackParams = p
        } catch (e: Throwable) { }
    }

    /** Начать (или продолжить) воспроизведение с позиции fromMs. */
    fun play(fromMs: Double) {
        val now = System.nanoTime()
        val t = track
        if (t != null) {
            try {
                t.pause()
                var f = ((fromMs + leadInMs) * rate / 1000.0).toLong()
                if (f < 0L) f = 0L
                if (f > (frames - 1).toLong()) f = (frames - 1).toLong()
                t.setPlaybackHeadPosition(f.toInt())
                applySpeed()
                t.play()
                lastHead = f.toInt()
                needSnap = true
            } catch (e: Throwable) { }
        }
        anchorMs = fromMs
        anchorNano = now
        playing = true
    }

    fun pause() {
        if (!playing) return
        pausedMs = nowMs()
        try { track?.pause() } catch (e: Throwable) { }
        playing = false
    }

    fun resume() { play(pausedMs) }

    fun seek(ms: Double) {
        if (playing) play(ms) else pausedMs = ms
    }

    fun stop() {
        if (playing) pausedMs = nowMs()
        try { track?.pause() } catch (e: Throwable) { }
        playing = false
    }

    /** Вызывать раз в кадр: подтягивает часы к реальной позиции звука. */
    fun update() {
        if (!playing) return
        val t = track ?: return
        val head = try { t.playbackHeadPosition } catch (e: Throwable) { return }
        if (head != lastHead) {
            lastHead = head
            val now = System.nanoTime()
            val headMs = head * 1000.0 / rate - leadInMs
            if (needSnap) {
                anchorMs = headMs
                needSnap = false
            } else {
                val predicted = anchorMs + (now - anchorNano) / 1e6 * speed
                val err = headMs - predicted
                anchorMs = if (Math.abs(err) > 100.0) headMs else predicted + err * 0.2
            }
            anchorNano = now
        }
    }

    fun nowMs(): Double = timeAtNano(System.nanoTime())

    fun timeAtNano(nano: Long): Double =
        if (!playing) pausedMs else anchorMs + (nano - anchorNano) / 1e6 * speed

    fun release() {
        playing = false
        try { track?.pause() } catch (e: Throwable) { }
        try { track?.release() } catch (e: Throwable) { }
        track = null
    }
}

/** Короткие звуки: попадание, промах, клик, метроном. */
class Sfx(ctx: Context) {
    private val pool: SoundPool
    private var hitId = 0
    private var missId = 0
    private var clickId = 0
    private var metroStrongId = 0
    private var metroWeakId = 0

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attrs).build()
        hitId = load(ctx, "hit", Synth.hitSound())
        missId = load(ctx, "miss", Synth.missSound())
        clickId = load(ctx, "click", Synth.clickSound())
        metroStrongId = load(ctx, "metro_s", Synth.metronome(true))
        metroWeakId = load(ctx, "metro_w", Synth.metronome(false))
    }

    private fun load(ctx: Context, name: String, pcm: ShortArray): Int {
        return try {
            val f = File(ctx.cacheDir, "sfx_$name.wav")
            writeWav(f, pcm, Synth.RATE)
            pool.load(f.absolutePath, 1)
        } catch (e: Throwable) { 0 }
    }

    private fun writeWav(f: File, pcm: ShortArray, rate: Int) {
        val dataLen = pcm.size * 2
        val bb = java.nio.ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        FileOutputStream(f).use { it.write(bb.array()) }
    }

    private fun play(id: Int, vol: Float) {
        if (id == 0 || vol <= 0f) return
        try { pool.play(id, vol, vol, 1, 0, 1f) } catch (e: Throwable) { }
    }

    fun hit(vol: Float) = play(hitId, vol)
    fun miss(vol: Float) = play(missId, vol)
    fun click(vol: Float) = play(clickId, vol)
    fun metronome(strong: Boolean, vol: Float) = play(if (strong) metroStrongId else metroWeakId, vol)
}
