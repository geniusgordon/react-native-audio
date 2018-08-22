package com.rnim.rn.audio;

import android.Manifest;
import android.content.Context;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.AudioFormat;
import android.media.AudioAttributes;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
// import java.io.DataInputStream;
// import java.io.DataOutputStream;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.IllegalAccessException;
import java.lang.NoSuchMethodException;

class AudioRecorderManager extends ReactContextBaseJavaModule {

  private static final String TAG = "ReactNativeAudio";
  private static final long DEFAULT_TIMEOUT_US = 1000 * 10;

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
  private Timer timer;
  private StopWatch stopWatch;
  private int recordBufferSize;
  private int trackBufferSize;
  private int audioTrackIndex;
  private BufferedOutputStream tempBos;
  private BufferedInputStream tempBis;
  private BufferedOutputStream aacBos;
  
  private boolean meteringEnabled = false;
  private int progressUpdateInterval = 250;
  private boolean isPauseResumeCapable = false;
  private Method pauseMethod = null;
  private Method resumeMethod = null;


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
    constants.put(DocumentDirectoryPath, this.getReactApplicationContext().getFilesDir().getAbsolutePath());
    constants.put(PicturesDirectoryPath, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath());
    constants.put(MainBundlePath, "");
    constants.put(CachesDirectoryPath, this.getReactApplicationContext().getCacheDir().getAbsolutePath());
    constants.put(LibraryDirectoryPath, "");
    constants.put(MusicDirectoryPath, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath());
    constants.put(DownloadsDirectoryPath, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
    return constants;
  }

  @Override
  public String getName() {
    return "AudioRecorderManager";
  }

  @ReactMethod
  public void checkAuthorizationStatus(Promise promise) {
    int permissionCheck = ContextCompat.checkSelfPermission(getCurrentActivity(),
            Manifest.permission.RECORD_AUDIO);
    boolean permissionGranted = permissionCheck == PackageManager.PERMISSION_GRANTED;
    promise.resolve(permissionGranted);
  }

  @ReactMethod
  public void prepareRecordingAtPath(String recordingPath, ReadableMap recordingSettings, Promise promise) {
    if (isRecording){
      logAndRejectPromise(promise, "INVALID_STATE", "Please call stopRecording before starting recording");
    }
    int sampleRate = recordingSettings.getInt("SampleRate");
    int channels = recordingSettings.getInt("Channels");
    int bitRate = recordingSettings.getInt("AudioEncodingBitRate");
    recordBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    );
    trackBufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    );

    // recorder = new MediaRecorder();
    audioRecord = new AudioRecord (
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT,
        recordBufferSize
    );
    audioTrack = new AudioTrack(
        new AudioAttributes.Builder()
             .setUsage(AudioAttributes.USAGE_MEDIA)
             .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
             .build(),
        new AudioFormat.Builder()
             .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
             .setSampleRate(sampleRate)
             .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
             .build(),
        trackBufferSize,
        AudioTrack.MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE
    );
    currentOutputFile = recordingPath;
    try {
      Log.d("RNAudio", "recordingPath: " + recordingPath);
      initEncoder(sampleRate, channels, bitRate);
      int outputFormat = getOutputFormatFromString(recordingSettings.getString("OutputFormat"));
      int audioEncoder = getAudioEncoderFromString(recordingSettings.getString("AudioEncoding"));
      // recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      // recorder.setOutputFormat(outputFormat);
      // recorder.setAudioEncoder(audioEncoder);
      // recorder.setAudioSamplingRate(sampleRate);
      // recorder.setAudioChannels(channels);
      // recorder.setAudioEncodingBitRate(bitRate);
      // recorder.setOutputFile(recordingPath);
      meteringEnabled = recordingSettings.getBoolean("MeteringEnabled");
      progressUpdateInterval = recordingSettings.getInt("ProgressUpdateInterval");
    }
    catch(final Exception e) {
      logAndRejectPromise(promise, "COULDNT_CONFIGURE_MEDIA_RECORDER" , "Make sure you've added RECORD_AUDIO permission to your AndroidManifest.xml file;\n" + e.getMessage());
      return;
    }

    try {
      // recorder.prepare();
      String tempPath = this.getReactApplicationContext().getCacheDir().getAbsolutePath() + "/temp.pcm";
      Log.d("RNAudio", "tempPath: " + tempPath);
      tempBos = new BufferedOutputStream(new FileOutputStream(tempPath));
      promise.resolve(currentOutputFile);
    } catch (final Exception e) {
      logAndRejectPromise(promise, "COULDNT_PREPARE_RECORDING_AT_PATH "+recordingPath, e.getMessage());
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
       Log.d("INVALID_AUDIO_ENCODER", "USING MediaRecorder.AudioEncoder.DEFAULT instead of "+audioEncoder+": "+MediaRecorder.AudioEncoder.DEFAULT);
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
        Log.d("INVALID_OUPUT_FORMAT", "USING MediaRecorder.OutputFormat.DEFAULT : "+MediaRecorder.OutputFormat.DEFAULT);
        return MediaRecorder.OutputFormat.DEFAULT;

    }
  }

  @ReactMethod
  public void startRecording(Promise promise){
    // if (recorder == null){
    //   logAndRejectPromise(promise, "RECORDING_NOT_PREPARED", "Please call prepareRecordingAtPath before starting recording");
    //   return;
    // }
    if (isRecording){
      logAndRejectPromise(promise, "INVALID_STATE", "Please call stopRecording before starting recording");
      return;
    }
    // recorder.start();
    encoder.start();
    audioRecord.startRecording();
    audioTrack.play();
    (new Thread() {
      @Override
      public void run() {
        recordAndEncode();
      }
    }).start();

    stopWatch.reset();
    stopWatch.start();
    isRecording = true;
    isPaused = false;
    doneEncoding = false;
    startTimer();
    promise.resolve(currentOutputFile);
  }

  @ReactMethod
  public void stopRecording(Promise promise){
    if (!isRecording){
      logAndRejectPromise(promise, "INVALID_STATE", "Please call startRecording before stopping recording");
      return;
    }

    stopTimer();
    Log.d("RNAudio", "stopRecording");
    isRecording = false;
    isPaused = false;

    try {
      // recorder.stop();
      // recorder.release();
      stopWatch.stop();
      audioRecord.stop();
      audioTrack.pause();
    }
    catch (final RuntimeException e) {
      // https://developer.android.com/reference/android/media/MediaRecorder.html#stop()
      logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "No valid audio data received. You may be using a device that can't record audio.");
      return;
    }
    finally {
      recorder = null;
    }

    promise.resolve(currentOutputFile);

    WritableMap result = Arguments.createMap();
    result.putString("status", "OK");
    result.putString("audioFileURL", "file://" + currentOutputFile);

    sendEvent("recordingFinished", result);
  }

  @ReactMethod
  public void pauseRecording(Promise promise) {
    if (!isPauseResumeCapable || pauseMethod==null) {
      logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
      return;
    }

    if (!isPaused) {
      try {
        pauseMethod.invoke(recorder);
        stopWatch.stop();
      } catch (InvocationTargetException | RuntimeException | IllegalAccessException e) {
        e.printStackTrace();
        logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
        return;
      }
    }

    isPaused = true;
    promise.resolve(null);
  }

  @ReactMethod
  public void resumeRecording(Promise promise) {
    if (!isPauseResumeCapable || resumeMethod == null) {
      logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
      return;
    }

    if (isPaused) {
      try {
        resumeMethod.invoke(recorder);
        stopWatch.start();
      } catch (InvocationTargetException | RuntimeException | IllegalAccessException e) {
        e.printStackTrace();
        logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
        return;
      }
    }
    
    isPaused = false;
    promise.resolve(null);
  }

  private void startTimer(){
    timer = new Timer();
    timer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        if (!isPaused) {
          WritableMap body = Arguments.createMap();
          body.putDouble("currentTime", stopWatch.getTimeSeconds());
          if (meteringEnabled) {
              // int amplitude = recorder.getMaxAmplitude();
              int amplitude = 0;
              if (amplitude == 0) {
                  body.putInt("currentMetering", -160);//The first call - absolutely silence
              } else {
                  //db = 20 * log10(peaks/ 32767); where 32767 - max value of amplitude in Android, peaks - current value
                  body.putInt("currentMetering", (int) (20 * Math.log(((double) amplitude) / 32767d)));
              }
          }
          sendEvent("recordingProgress", body);
        }
      }
    }, 0, progressUpdateInterval);
  }

  private void stopTimer(){
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
    while(isRecording) {
      try {
          byte[] buf = new byte[recordBufferSize];
          int num = audioRecord.read(buf, 0, recordBufferSize);
          audioTrack.write(buf, 0, num);
          tempBos.write(buf);
      } catch(final Exception e) {
          Log.d("RECORD_AND_ENCODE" , e.getMessage());
          return;
      }
    }
    try {
      tempBos.close();
      String tempPath = this.getReactApplicationContext().getCacheDir().getAbsolutePath() + "/temp.pcm";
      tempBis = new BufferedInputStream(new FileInputStream(tempPath));
      aacBos = new BufferedOutputStream(new FileOutputStream(currentOutputFile));

      while (tempBis.available() > 0) {
          byte[] buf = new byte[2048];
          int num = tempBis.read(buf);
          byte[] inputData = Arrays.copyOfRange(buf, 0, num);
          queueInputBuffer(inputData);
          Log.d("RNAudio", "queueInputBuffer");
          while (true) {
            int index = dequeueOutputBuffer();
            Log.d("RNAudio", "dequeueOutputBuffer");
            if (index < 0) {
              break;
            }
          }
      }
      queueInputBuffer(null);
      while (!doneEncoding) {
        dequeueOutputBuffer();
      }
      tempBis.close();
      aacBos.close();
      // mediaMuxer.stop();
      // mediaMuxer.release();
    } catch (Exception e) {
      String stackTrace = Log.getStackTraceString(e);
      Log.d("RNAudio", "recordAndEncode error: " + stackTrace);
    }
    Log.d("RNAudio", "recordAndEncode Done");
  }

  private void initEncoder(int sampleRate, int channels, int bitRate) throws Exception {
      Log.d("RNAudio", "initEncoder: sampleRate = " + sampleRate  + ", channels = " + channels + ", bitRate = " + bitRate);
      encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
      MediaFormat format = new MediaFormat();
      format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
      format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
      format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels);
      format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
      format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048);
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      // mediaMuxer = new MediaMuxer(currentOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
  }

  private void queueInputBuffer(byte[] data) {
      try {
          int inputBufferIndex = encoder.dequeueInputBuffer(0);
          if (inputBufferIndex >= 0) {
              if (data == null) {
                  Log.d("RNAudio", "queueInputBuffer done");
                  encoder.queueInputBuffer(
                      inputBufferIndex,
                      0 /* offset */,
                      0 /* size */,
                      0 /* timeUs */,
                      MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              } else {
                  ByteBuffer inputBuffer;
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                      inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                  } else {
                      inputBuffer = encoder.getInputBuffers()[inputBufferIndex];
                  }
                  inputBuffer.clear();
                  inputBuffer.limit(data.length);
                  inputBuffer.put(data);
                  encoder.queueInputBuffer(inputBufferIndex, 0, data.length, 0, 0);
              }
          }
      } catch (final Exception e) {
          Log.e("RNAudio", "queueInputBuffer Error " + e);
      }
  }

  private int dequeueOutputBuffer() {
      try {
          MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
          int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, DEFAULT_TIMEOUT_US);
          if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat format = encoder.getOutputFormat();
            Log.d("RNAudio", "INFO_OUTPUT_FORMAT_CHANGED: " + format);
            // audioTrackIndex = mediaMuxer.addTrack(format);
            // mediaMuxer.start();
            // Log.d("RNAudio", "mediaMuxer audioTrackIndex = " + String.valueOf(audioTrackIndex));
          } else if (outputBufferIndex >= 0) {
              ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
              MediaFormat bufferFormat = encoder.getOutputFormat(outputBufferIndex);
              if (outputBuffer != null) {
                  int outBitSize = bufferInfo.size;
                  int outPacketSize = outBitSize + 7; // ADTS header size
                  outputBuffer.position(bufferInfo.offset);
                  outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                  byte[] chunkAudio = new byte[outPacketSize];
                  int sampleRate = bufferFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                  int channels = bufferFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                  addADTStoPacket(chunkAudio, sampleRate, channels, outPacketSize);
                  outputBuffer.get(chunkAudio, 7, outBitSize);
                  outputBuffer.position(bufferInfo.offset);
                  aacBos.write(chunkAudio, 0, chunkAudio.length);

                  doneEncoding = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                  // ByteBuffer audioBuffer = ByteBuffer.allocate(outPacketSize);
                  // audioBuffer.put(chunkAudio);
                  // mediaMuxer.writeSampleData(audioTrackIndex, audioBuffer, bufferInfo);

                  outputBuffer.clear();
                  encoder.releaseOutputBuffer(outputBufferIndex, false);
              }
          }
          return outputBufferIndex;
      } catch (final Exception e) {
          String stackTrace = Log.getStackTraceString(e);
          Log.e("RNAudio", "dequeueOutputBuffer Error: " + stackTrace);
      }
      return -1;
  }

  private int getSampleRateType(int sampleRate) {
    switch (sampleRate) {
      case 44100:
        return 4;
      case 22050:
        return 4;
    }
    return 4;
  }

  // reference https://wiki.multimedia.cx/index.php/ADTS
  private void addADTStoPacket(byte[] packet, int sampleRate, int channels, int packetLen) {
      int profile = 2; // AAC LC
      int freqIdx = getSampleRateType(sampleRate);
      int chanCfg = channels;

      packet[0] = (byte) 0xFF;
      packet[1] = (byte) 0xF1;
      packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
      packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
      packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
      packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
      packet[6] = (byte) 0xFC;
  }
}
