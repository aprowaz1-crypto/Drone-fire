package com.example.dronefire

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import kotlin.math.*

class AudioProcessor(
    private val context: Context,
    private val updateCallback: (status: String, spectrum: String, azimuth: Float, alarm: Boolean, log: String) -> Unit
) {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)
    private val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        audioFormat,
        bufferSize
    )
    private val energyHistory = ArrayDeque<Float>()
    private var running = false

    fun start() {
        try {
            configureAudioEffects()
            audioRecord.startRecording()
            running = true
            updateCallback("Запуск обробки", "", 0f, false, "Аудіо запис: 16 кГц, моно, 16 біт | запущено")
            val buffer = ShortArray(bufferSize)
            val fftWindow = 2048
            val fftInput = DoubleArray(fftWindow)
            val fftOutput = DoubleArray(fftWindow)
            val handler = Handler(Looper.getMainLooper())

            Thread {
                var errorCount = 0
                try {
                    while (running && errorCount < 10) {
                        try {
                            val read = audioRecord.read(buffer, 0, fftWindow)
                            if (read <= 0) continue
                            for (i in 0 until fftWindow) {
                                fftInput[i] = if (i < read) buffer[i].toDouble() else 0.0
                            }
                            val spectrum = calculateSpectrum(fftInput, fftOutput)
                            val bandEnergy = computeBandEnergy(spectrum, 40, 100)
                            val pulseDetected = updatePulseBuffer(bandEnergy)
                            val azimuth = calculateAzimuth(bandEnergy)
                            val status = if (pulseDetected && bandEnergy > 0.05f) "ТРЕВОГА" else "Моніторинг"
                            val alarm = pulseDetected && bandEnergy > 0.12f
                            val spectrumText = "40-100Hz енергія: ${"%.3f".format(bandEnergy)}"
                            val logText = "${System.currentTimeMillis()} - енергія ${"%.3f".format(bandEnergy)}, імпульс ${pulseDetected}"
                            handler.post {
                                updateCallback(status, spectrumText, azimuth, alarm, logText)
                            }
                        } catch (e: Exception) {
                            errorCount++
                            handler.post {
                                updateCallback("Помилка $errorCount", "", 0f, false, 
                                    "FFT: ${e.message?.take(50) ?: "err"} | ${e.javaClass.simpleName}")
                            }
                            if (errorCount >= 10) {
                                running = false
                                handler.post {
                                    updateCallback("ЗУПИНЕНО", "", 0f, false, "Занадто багато помилок")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    handler.post {
                        updateCallback("Крит помилка", "", 0f, false, 
                            "Потік: ${e.message} | ${e.javaClass.simpleName}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            updateCallback("Помилка запуску", "", 0f, false, 
                "AudioRecord: ${e.message} | ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        running = false
        audioRecord.stop()
        audioRecord.release()
    }

    private fun configureAudioEffects() {
        try {
            if (NoiseSuppressor.isAvailable()) {
                val noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
                noiseSuppressor.enabled = false
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                val agc = AutomaticGainControl.create(audioRecord.audioSessionId)
                agc.enabled = false
            }
        } catch (_: Exception) {
        }
    }

    private fun calculateSpectrum(input: DoubleArray, output: DoubleArray): FloatArray {
        val n = input.size
        val windowed = DoubleArray(n)
        for (i in input.indices) {
            windowed[i] = input[i] * (0.54 - 0.46 * cos(2.0 * Math.PI * i / (n - 1)))
        }
        fft(windowed, output)
        val spectrum = FloatArray(n / 2)
        for (i in spectrum.indices) {
            spectrum[i] = sqrt(windowed[i].pow(2) + output[i].pow(2)).toFloat()
        }
        return spectrum
    }

    private fun computeBandEnergy(spectrum: FloatArray, lowHz: Int, highHz: Int): Float {
        val freqResolution = sampleRate.toFloat() / spectrum.size / 2
        var sum = 0f
        var count = 0
        for (i in spectrum.indices) {
            val freq = i * freqResolution
            if (freq >= lowHz && freq <= highHz) {
                sum += spectrum[i]
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }

    private fun updatePulseBuffer(value: Float): Boolean {
        energyHistory.addLast(value)
        if (energyHistory.size > 40) energyHistory.removeFirst()
        if (energyHistory.size < 10) return false
        val average = energyHistory.average().toFloat()
        val variance = energyHistory.fold(0f) { acc: Float, v: Float -> acc + (v - average).pow(2f) } / energyHistory.size
        return variance > 0.0008f
    }

    private fun calculateAzimuth(energy: Float): Float {
        return ((energy * 360f) % 360f)
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        val m = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            imag[i] = 0.0
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j >= bit) {
                j -= bit
                bit = bit shr 1
            }
            j += bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wlenReal = cos(angle)
            val wlenImag = sin(angle)
            for (i in 0 until n step len) {
                var uReal = 1.0
                var uImag = 0.0
                for (j in 0 until len / 2) {
                    val vReal = real[i + j + len / 2] * uReal - imag[i + j + len / 2] * uImag
                    val vImag = real[i + j + len / 2] * uImag + imag[i + j + len / 2] * uReal
                    real[i + j + len / 2] = real[i + j] - vReal
                    imag[i + j + len / 2] = imag[i + j] - vImag
                    real[i + j] += vReal
                    imag[i + j] += vImag
                    val nextUReal = uReal * wlenReal - uImag * wlenImag
                    val nextUImag = uReal * wlenImag + uImag * wlenReal
                    uReal = nextUReal
                    uImag = nextUImag
                }
            }
            len = len shl 1
        }
    }
}
