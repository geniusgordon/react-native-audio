package com.rnim.rn.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.media.MediaRecorder;
import android.media.AudioManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Base64;
import android.util.Log;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.IllegalAccessException;
import java.lang.NoSuchMethodException;

class AudioRecorderManager extends ReactContextBaseJavaModule {

  private static final String TAG = "ReactNativeAudio";
  private static final long DEFAULT_TIMEOUT_US = 1000 * 10;
  private static final int BUFFER_SIZE = 48000;
  private static final int FINISH_ENCODING = 1;

  private static final String DocumentDirectoryPath = "DocumentDirectoryPath";
  private static final String PicturesDirectoryPath = "PicturesDirectoryPath";
  private static final String MainBundlePath = "MainBundlePath";
  private static final String CachesDirectoryPath = "CachesDirectoryPath";
  private static final String LibraryDirectoryPath = "LibraryDirectoryPath";
  private static final String MusicDirectoryPath = "MusicDirectoryPath";
  private static final String DownloadsDirectoryPath = "DownloadsDirectoryPath";

  private Context context;
  private MediaRecorder recorder;
  private AudioRecord audioRecord;
  private AudioTrack audioTrack;
  private MediaCodec encoder;
  private MediaMuxer mediaMuxer;
  private String currentOutputFile;
  private boolean isRecording = false;
  private boolean isPaused = false;

  private boolean doneEncoding = false;
  private boolean finishRecording = false;
  private boolean shouldMonitor = false;
  private boolean includeBase64 = false;
  private Timer timer;
  private StopWatch stopWatch;
  private int sampleRate;
  private int channels;
  private int recordBufferSize;
  private int trackBufferSize;
  private int audioTrackIndex;
  private BufferedOutputStream tempBos;
  private BufferedInputStream tempBis;
  private int amplitude;

  private boolean meteringEnabled = false;
  private int progressUpdateInterval = 250;
  private boolean isPauseResumeCapable = false;
  private Method pauseMethod = null;
  private Method resumeMethod = null;
  private Handler threadHandler = null;

  public AudioRecorderManager(ReactApplicationContext reactContext) {
    super(reactContext);
    this.context = reactContext;
    stopWatch = new StopWatch();

    isPauseResumeCapable = Build.VERSION.SDK_INT > Build.VERSION_CODES.M;
    if (isPauseResumeCapable) {
      try {
        pauseMethod = MediaRecorder.class.getMethod("pause");
        resumeMethod = MediaRecorder.class.getMethod("resume");
      } catch (NoSuchMethodException e) {
        Log.d("ERROR", "Failed to get a reference to pause and/or resume method");
      }
    }
  }

  @Override
  public Map<String, Object> getConstants() {
    Map<String, Object> constants = new HashMap<>();
    constants.put(
        DocumentDirectoryPath, this.getReactApplicationContext().getFilesDir().getAbsolutePath());
    constants.put(
        PicturesDirectoryPath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .getAbsolutePath());
    constants.put(MainBundlePath, "");
    constants.put(
        CachesDirectoryPath, this.getReactApplicationContext().getCacheDir().getAbsolutePath());
    constants.put(LibraryDirectoryPath, "");
    constants.put(
        MusicDirectoryPath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            .getAbsolutePath());
    constants.put(
        DownloadsDirectoryPath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .getAbsolutePath());
    return constants;
  }

  @Override
  public String getName() {
    return "AudioRecorderManager";
  }

  @ReactMethod
  public void checkAuthorizationStatus(Promise promise) {
    int permissionCheck =
        ContextCompat.checkSelfPermission(getCurrentActivity(), Manifest.permission.RECORD_AUDIO);
    boolean permissionGranted = permissionCheck == PackageManager.PERMISSION_GRANTED;
    promise.resolve(permissionGranted);
  }

  @ReactMethod
  public void prepareRecordingAtPath(
      String recordingPath, ReadableMap recordingSettings, Promise promise) {
    if (isRecording) {
      logAndRejectPromise(
          promise, "INVALID_STATE", "Please call stopRecording before starting recording");
    }
    sampleRate = recordingSettings.getInt("SampleRate");
    channels = 1; // recordingSettings.getInt("Channels");
    int bitRate = recordingSettings.getInt("AudioEncodingBitRate");
    recordBufferSize =
        AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    trackBufferSize =
        AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

    audioRecord =
        new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferSize);
    audioTrack =
        new AudioTrack(
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            trackBufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE);
    currentOutputFile = recordingPath;
    try {
      Log.d("RNAudio", "recordingPath: " + recordingPath);
      initEncoder(sampleRate, channels, bitRate);
    File destFile = new File(recordingPath);
    if (destFile.getParentFile() != null) {
      destFile.getParentFile().mkdirs();
    }
    recorder = new MediaRecorder();
    try {
      recorder.setAudioSource(recordingSettings.getInt("AudioSource"));
      int outputFormat = getOutputFormatFromString(recordingSettings.getString("OutputFormat"));
      int audioEncoder = getAudioEncoderFromString(recordingSettings.getString("AudioEncoding"));
      meteringEnabled = recordingSettings.getBoolean("MeteringEnabled");
      progressUpdateInterval = recordingSettings.getInt("ProgressUpdateInterval");
    } catch (final Exception e) {
      logAndRejectPromise(
          promise,
          "COULDNT_CONFIGURE_MEDIA_RECORDER",
          "Make sure you've added RECORD_AUDIO permission to your AndroidManifest.xml file;\n"
              + e.getMessage());
      recorder.setAudioEncoder(audioEncoder);
      recorder.setAudioSamplingRate(recordingSettings.getInt("SampleRate"));
      recorder.setAudioChannels(recordingSettings.getInt("Channels"));
      recorder.setAudioEncodingBitRate(recordingSettings.getInt("AudioEncodingBitRate"));
      recorder.setOutputFile(destFile.getPath());
      includeBase64 = recordingSettings.getBoolean("IncludeBase64");
    }
    catch(final Exception e) {
      logAndRejectPromise(promise, "COULDNT_CONFIGURE_MEDIA_RECORDER" , "Make sure you've added RECORD_AUDIO permission to your AndroidManifest.xml file "+e.getMessage());
      return;
    }

    try {
      String tempPath =
          this.getReactApplicationContext().getCacheDir().getAbsolutePath() + "/temp.pcm";
      Log.d("RNAudio", "tempPath: " + tempPath);
      tempBos = new BufferedOutputStream(new FileOutputStream(tempPath));
      promise.resolve(currentOutputFile);
    } catch (final Exception e) {
      logAndRejectPromise(
          promise, "COULDNT_PREPARE_RECORDING_AT_PATH " + recordingPath, e.getMessage());
    }
  }

  private int getAudioEncoderFromString(String audioEncoder) {
    switch (audioEncoder) {
      case "aac":
        return MediaRecorder.AudioEncoder.AAC;
      case "aac_eld":
        return MediaRecorder.AudioEncoder.AAC_ELD;
      case "amr_nb":
        return MediaRecorder.AudioEncoder.AMR_NB;
      case "amr_wb":
        return MediaRecorder.AudioEncoder.AMR_WB;
      case "he_aac":
        return MediaRecorder.AudioEncoder.HE_AAC;
      case "vorbis":
        return MediaRecorder.AudioEncoder.VORBIS;
      default:
        Log.d(
            "INVALID_AUDIO_ENCODER",
            "USING MediaRecorder.AudioEncoder.DEFAULT instead of "
                + audioEncoder
                + ": "
                + MediaRecorder.AudioEncoder.DEFAULT);
        return MediaRecorder.AudioEncoder.DEFAULT;
    }
  }

  private int getOutputFormatFromString(String outputFormat) {
    switch (outputFormat) {
      case "mpeg_4":
        return MediaRecorder.OutputFormat.MPEG_4;
      case "aac_adts":
        return MediaRecorder.OutputFormat.AAC_ADTS;
      case "amr_nb":
        return MediaRecorder.OutputFormat.AMR_NB;
      case "amr_wb":
        return MediaRecorder.OutputFormat.AMR_WB;
      case "three_gpp":
        return MediaRecorder.OutputFormat.THREE_GPP;
      case "webm":
        return MediaRecorder.OutputFormat.WEBM;
      default:
        Log.d(
            "INVALID_OUPUT_FORMAT",
            "USING MediaRecorder.OutputFormat.DEFAULT : " + MediaRecorder.OutputFormat.DEFAULT);
        return MediaRecorder.OutputFormat.DEFAULT;
    }
  }

  @ReactMethod
  public void startRecording(Promise promise) {
    if (isRecording) {
      logAndRejectPromise(
          promise, "INVALID_STATE", "Please call stopRecording before starting recording");
      return;
    }
    encoder.start();
    audioRecord.startRecording();
    audioTrack.play();
    threadHandler =
        new Handler() {
          @Override
          public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == FINISH_ENCODING) {
              WritableMap result = Arguments.createMap();
              result.putString("status", "OK");
              result.putString("audioFileURL", currentOutputFile);
              sendEvent("recordingFinished", result);
            }
          }
        };
    (new Thread() {
          @Override
          public void run() {
            recordAndEncode();
            threadHandler.sendEmptyMessage(FINISH_ENCODING);
          }
        })
        .start();

    stopWatch.reset();
    stopWatch.start();
    isRecording = true;
    isPaused = false;
    doneEncoding = false;
    finishRecording = false;
    startTimer();
    promise.resolve(currentOutputFile);
  }

  @ReactMethod
  public void stopRecording(Promise promise) {
    Log.d("RNAudio", "stopRecording, isRecording: " + isRecording);
    if (!isRecording) {
      logAndRejectPromise(
          promise, "INVALID_STATE", "Please call startRecording before stopping recording");
      return;
    }

    stopTimer();
    isRecording = false;
    isPaused = false;

    try {
      stopWatch.stop();
      audioRecord.stop();
      audioTrack.pause();
    } catch (final RuntimeException e) {
      // https://developer.android.com/reference/android/media/MediaRecorder.html#stop()
      logAndRejectPromise(
          promise,
          "RUNTIME_EXCEPTION",
          "No valid audio data received. You may be using a device that can't record audio.");
      return;
    }

    promise.resolve(currentOutputFile);

    WritableMap result = Arguments.createMap();
    result.putString("status", "OK");
    result.putString("audioFileURL", "file://" + currentOutputFile);

    String base64 = "";
    if (includeBase64) {
      try {
        InputStream inputStream = new FileInputStream(currentOutputFile);
        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
          while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
          }
        } catch (IOException e) {
          Log.e(TAG, "FAILED TO PARSE FILE");
        }
        bytes = output.toByteArray();
        base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
      } catch(FileNotFoundException e) {
        Log.e(TAG, "FAILED TO FIND FILE");
      }
    }
    result.putString("base64", base64);

    sendEvent("recordingFinished", result);
  }

  @ReactMethod
  public void pauseRecording(Promise promise) {
    if (!isPauseResumeCapable || pauseMethod == null) {
      logAndRejectPromise(
          promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
      return;
    }

    if (!isPaused) {
      try {
        pauseMethod.invoke(recorder);
        stopWatch.stop();
      } catch (InvocationTargetException | RuntimeException | IllegalAccessException e) {
        e.printStackTrace();
        logAndRejectPromise(
            promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
        return;
      }
    }

    isPaused = true;
    promise.resolve(null);
  }

  @ReactMethod
  public void resumeRecording(Promise promise) {
    if (!isPauseResumeCapable || resumeMethod == null) {
      logAndRejectPromise(
          promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
      return;
    }

    if (isPaused) {
      try {
        resumeMethod.invoke(recorder);
        stopWatch.start();
      } catch (InvocationTargetException | RuntimeException | IllegalAccessException e) {
        e.printStackTrace();
        logAndRejectPromise(
            promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
        return;
      }
    }

    isPaused = false;
    promise.resolve(null);
  }

  @ReactMethod
  public void startMonitoring() {
    shouldMonitor = true;
  }

  @ReactMethod
  public void stopMonitoring() {
    shouldMonitor = false;
  }

  private void startTimer() {
    timer = new Timer();
    timer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            if (!isPaused) {
              WritableMap body = Arguments.createMap();
              body.putDouble("currentTime", stopWatch.getTimeSeconds());
              if (meteringEnabled) {
                // db = 20 * log10(peaks/ 32767); where 32767 - max value of amplitude in Android,
                // peaks - current value
                double db = calculateDbFromAmplitude(amplitude, 32767d);
                body.putDouble("currentMetering", db);
              }
              sendEvent("recordingProgress", body);
            }
          }
        },
        0,
        progressUpdateInterval);
  }

  private void stopTimer() {
    if (timer != null) {
      timer.cancel();
      timer.purge();
      timer = null;
    }
  }

  private void sendEvent(String eventName, Object params) {
    getReactApplicationContext()
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit(eventName, params);
  }

  private void logAndRejectPromise(Promise promise, String errorCode, String errorMessage) {
    Log.e(TAG, errorMessage);
    promise.reject(errorCode, errorMessage);
  }

  private void recordAndEncode() {
    finishRecording = false;
    try {
      while (isRecording) {
        byte[] buf = new byte[recordBufferSize];
        int num = audioRecord.read(buf, 0, recordBufferSize);
        int curAmp = 0;
        if (num > 0) {
          for (int i = 0; i < num / 2; i++) {
            int x = buf[i * 2] | (buf[i * 2 + 1] << 8);
            curAmp = Math.max(curAmp, x);
          }
          amplitude = curAmp;
        }
        if (shouldMonitor) {
          audioTrack.write(buf, 0, num);
        }
        tempBos.write(buf);
      }
      tempBos.close();
      String tempPath =
          this.getReactApplicationContext().getCacheDir().getAbsolutePath() + "/temp.pcm";
      tempBis = new BufferedInputStream(new FileInputStream(tempPath));

      int inputBufferIndex = 0;
      boolean hasMoreData = true;
      double presentationTimeUs = 0;
      int totalBytesRead = 0;
      while (tempBis.available() > 0) {
        byte[] buf = new byte[BUFFER_SIZE];
        inputBufferIndex = encoder.dequeueInputBuffer(0);
        if (inputBufferIndex >= 0) {
          ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
          inputBuffer.clear();

          int bytesRead = tempBis.read(buf, 0, inputBuffer.limit());
          Log.d("RNAudio", "tempBis read: " + bytesRead);
          if (bytesRead > 0) {
            totalBytesRead += bytesRead;
            inputBuffer.put(buf, 0, bytesRead);
            encoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, (long) presentationTimeUs, 0);
            presentationTimeUs = 1000000l * (totalBytesRead / 2) / sampleRate;
          }
          while (true) {
            int index = dequeueOutputBuffer();
            if (index < 0) {
              break;
            }
          }
        }
      }
      while (true) {
        inputBufferIndex = encoder.dequeueInputBuffer(0);
        if (inputBufferIndex >= 0) {
          Log.d("RNAudio", "BUFFER_FLAG_END_OF_STREAM");
          encoder.queueInputBuffer(
              inputBufferIndex,
              0,
              0,
              (long) presentationTimeUs,
              MediaCodec.BUFFER_FLAG_END_OF_STREAM);
          break;
        }
      }
      Log.d("RNAudio", "drain buffer");
      while (!doneEncoding) {
        dequeueOutputBuffer();
      }
      tempBis.close();
      encoder.stop();
      encoder.release();
      mediaMuxer.stop();
      mediaMuxer.release();
    } catch (Exception e) {
      String stackTrace = Log.getStackTraceString(e);
      Log.d("RNAudio", "recordAndEncode error: " + stackTrace);
    }
    Log.d("RNAudio", "recordAndEncode Done, " + currentOutputFile);
    finishRecording = true;
  }

  private void initEncoder(int sampleRate, int channels, int bitRate) throws Exception {
    Log.d(
        "RNAudio",
        "initEncoder: sampleRate = "
            + sampleRate
            + ", channels = "
            + channels
            + ", bitRate = "
            + bitRate);
    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
    MediaFormat format = new MediaFormat();
    format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
    format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
    format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels);
    format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE);
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    mediaMuxer = new MediaMuxer(currentOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
  }

  private int dequeueOutputBuffer() {
    try {
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, DEFAULT_TIMEOUT_US);
      if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        MediaFormat format = encoder.getOutputFormat();
        Log.d("RNAudio", "INFO_OUTPUT_FORMAT_CHANGED: " + format);
        audioTrackIndex = mediaMuxer.addTrack(format);
        mediaMuxer.start();
        Log.d("RNAudio", "mediaMuxer audioTrackIndex = " + String.valueOf(audioTrackIndex));
      } else if (outputBufferIndex >= 0) {
        ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
        outputBuffer.position(bufferInfo.offset);
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
          byte[] buf = new byte[bufferInfo.size];
          outputBuffer.get(buf);
          String bufStr = "buf[0]: " + buf[0] + ", buf[1]: " + buf[1];
          Log.d("RNAudio", "BUFFER_FLAG_CODEC_CONFIG size: " + bufferInfo.size + "\n" + bufStr);
        } else {
          doneEncoding = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

          if (!doneEncoding) {
            mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo);
          }
        }
        outputBuffer.clear();
        encoder.releaseOutputBuffer(outputBufferIndex, false);
      }
      return outputBufferIndex;
    } catch (final Exception e) {
      String stackTrace = Log.getStackTraceString(e);
      Log.e("RNAudio", "dequeueOutputBuffer Error: " + stackTrace);
    }
    return -1;
  }

  private double calculateDbFromAmplitude(double amp, double max) {
    return amp == 0 ? -160 : (int) (20 * Math.log(amp / max));
  }
}
