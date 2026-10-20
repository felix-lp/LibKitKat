package io.github.felixlp.libkat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.VideoView;

/** Встроенный плеер: системный MediaPlayer (H.264 декодируется аппаратно), видео через StreamProxy. */
public class PlayerActivity extends Activity {
    private VideoView video;
    private String key;
    private boolean prepared;

    static void open(Activity from, String url, String title, String key) {
        Intent i = new Intent(from, PlayerActivity.class);
        i.putExtra("url", url);
        i.putExtra("title", title);
        i.putExtra("key", key);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        key = getIntent().getStringExtra("key");
        final String url = getIntent().getStringExtra("url");

        FrameLayout root = new FrameLayout(this);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(root);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                int pos = App.get().history.position(key);
                // Почти досмотренную серию начинаем сначала
                if (pos > 5000 && pos < mp.getDuration() - 30000) video.seekTo(pos);
                video.start();
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                App.get().history.setPosition(key, 0);
                finish();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                new AlertDialog.Builder(PlayerActivity.this)
                        .setMessage("Плеер не смог воспроизвести видео (код " + what + "/" + extra + ").\n"
                                + "Попробуйте качество пониже или внешний плеер (VLC) в настройках.")
                        .setPositiveButton("Открыть в другом плеере", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent i = new Intent(Intent.ACTION_VIEW);
                                i.setDataAndType(Uri.parse(url), "video/mp4");
                                try {
                                    startActivity(i);
                                } catch (Exception ignored) {
                                    // Плееров нет — просто закрываемся
                                }
                                finish();
                            }
                        })
                        .setNegativeButton("Закрыть", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                finish();
                            }
                        })
                        .setCancelable(false)
                        .show();
                return true;
            }
        });
        video.setVideoURI(Uri.parse(url));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prepared) App.get().history.setPosition(key, video.getCurrentPosition());
        video.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        video.stopPlayback();
    }
}
