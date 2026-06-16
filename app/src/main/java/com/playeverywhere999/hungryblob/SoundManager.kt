package com.playeverywhere999.hungryblob

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

class SoundManager(context: Context) {
    private val soundPool: SoundPool
    private var eatSoundId: Int = 0
    private var splitSoundId: Int = 0
    private var chaseSoundId: Int = 0
    private var shockSoundId: Int = 0
    private var lastChasePlayTime: Long = 0
    private var lastEatPlayTime: Long = 0
    private var lastSplitPlayTime: Long = 0
    private var lastShockPlayTime: Long = 0
    
    private var bgmSoundId: Int = 0
    private var bgmStreamId: Int = 0
    private var isSoundEnabled: Boolean = true

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
            
        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(audioAttributes)
            .build()
            
        eatSoundId = soundPool.load(context, R.raw.eat, 1)
        splitSoundId = soundPool.load(context, R.raw.split, 1)
        chaseSoundId = soundPool.load(context, R.raw.chase, 1)
        shockSoundId = soundPool.load(context, R.raw.shock, 1)
        bgmSoundId = soundPool.load(context, R.raw.bgm, 1)
        
        soundPool.setOnLoadCompleteListener { pool, sampleId, status ->
            if (sampleId == bgmSoundId && status == 0 && isSoundEnabled && bgmStreamId == 0) {
                bgmStreamId = pool.play(bgmSoundId, 1.0f, 1.0f, 1, -1, 1f)
            }
        }
    }
    
    fun playEat() {
        val now = System.currentTimeMillis()
        if (isSoundEnabled && now - lastEatPlayTime > 100) {
            soundPool.play(eatSoundId, 0.5f, 0.5f, 1, 0, 1f)
            lastEatPlayTime = now
        }
    }
    
    fun playSplit() {
        val now = System.currentTimeMillis()
        if (isSoundEnabled && now - lastSplitPlayTime > 500) {
            soundPool.play(splitSoundId, 0.8f, 0.8f, 1, 0, 1f)
            lastSplitPlayTime = now
        }
    }
    
    fun playChase() {
        val now = System.currentTimeMillis()
        if (isSoundEnabled && now - lastChasePlayTime > 2000) {
            soundPool.play(chaseSoundId, 0.4f, 0.4f, 1, 0, 1f)
            lastChasePlayTime = now
        }
    }
    
    fun playShock() {
        val now = System.currentTimeMillis()
        if (isSoundEnabled && now - lastShockPlayTime > 500) {
            soundPool.play(shockSoundId, 0.7f, 0.7f, 1, 0, 1f)
            lastShockPlayTime = now
        }
    }
    
    fun setSoundEnabled(enabled: Boolean) {
        isSoundEnabled = enabled
        if (enabled) {
            if (bgmStreamId != 0) {
                soundPool.resume(bgmStreamId)
            } else {
                bgmStreamId = soundPool.play(bgmSoundId, 1.0f, 1.0f, 1, -1, 1f)
            }
        } else {
            if (bgmStreamId != 0) {
                soundPool.pause(bgmStreamId)
            }
        }
    }
    
    fun onPause() {
        if (bgmStreamId != 0) {
            soundPool.pause(bgmStreamId)
        }
    }
    
    fun onResume() {
        if (isSoundEnabled && bgmStreamId != 0) {
            soundPool.resume(bgmStreamId)
        } else if (isSoundEnabled && bgmStreamId == 0) {
            bgmStreamId = soundPool.play(bgmSoundId, 1.0f, 1.0f, 1, -1, 1f)
        }
    }
    
    fun release() {
        if (bgmStreamId != 0) {
            soundPool.stop(bgmStreamId)
        }
        soundPool.release()
    }
}
