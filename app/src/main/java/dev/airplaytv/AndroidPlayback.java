package dev.airplaytv;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

/**
 * Bridges java-airplay callbacks (Netty threads) to the Android renderers and UI.
 */
final class AndroidPlayback implements Playback {

    /** UI hooks; each is invoked off the main thread. */
    interface Ui {
        void onMirroringStarted();

        void onMirroringStopped();

        void onAudioOnlyStarted();

        void onAudioStopped();
    }

    private final VideoRenderer video;
    private final AudioRenderer audio;
    private final MediaPlayerController media;
    private final Ui ui;

    private volatile boolean mirroring;
    private volatile double volume = 1.0;

    AndroidPlayback(VideoRenderer video, AudioRenderer audio, MediaPlayerController media, Ui ui) {
        this.video = video;
        this.audio = audio;
        this.media = media;
        this.ui = ui;
    }

    // --- Screen mirroring -------------------------------------------------------------

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        mirroring = true;
        ui.onMirroringStarted();
    }

    @Override
    public void onVideo(byte[] bytes) {
        video.feed(bytes);
    }

    @Override
    public void onVideoSrcDisconnect() {
        mirroring = false;
        video.stop();
        ui.onMirroringStopped();
    }

    // --- Audio (mirroring audio or music-only AirPlay) ------------------------------------

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        audio.start(audioStreamInfo);
        audio.setVolume((float) volume);
        if (!mirroring) {
            ui.onAudioOnlyStarted();
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        audio.feed(bytes);
    }

    @Override
    public void onAudioSrcDisconnect() {
        audio.stop();
        ui.onAudioStopped();
    }

    @Override
    public void onVolume(double volumeLinear) {
        volume = volumeLinear;
        audio.setVolume((float) volumeLinear);
        media.setVolume((float) volumeLinear);
    }

    @Override
    public double volume() {
        return volume;
    }

    // --- URL / HLS video (YouTube, Safari, etc.) ----------------------------------------

    @Override
    public void onPlaylist(String playlistUri) {
        media.play(playlistUri, (float) volume);
    }

    @Override
    public void onPlaylistRemoved() {
        media.stop();
    }

    @Override
    public void onPause() {
        media.pause();
    }

    @Override
    public void onResume() {
        media.resume();
    }

    @Override
    public void onSeek(double positionSeconds) {
        media.seek(positionSeconds);
    }

    @Override
    public Info info() {
        return media.info();
    }

    @Override
    public void setObserver(Observer observer) {
        media.setOnEnded(observer == null ? () -> { } : observer::onEnded);
    }
}
