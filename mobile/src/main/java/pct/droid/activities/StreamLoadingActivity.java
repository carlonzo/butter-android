package pct.droid.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.FileObserver;
import android.provider.MediaStore;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.util.Map;

import butterknife.InjectView;
import pct.droid.R;
import pct.droid.base.providers.media.types.Media;
import pct.droid.base.providers.media.types.Movie;
import pct.droid.base.providers.media.types.Show;
import pct.droid.base.providers.subs.OpenSubsProvider;
import pct.droid.base.providers.subs.SubsProvider;
import pct.droid.base.providers.subs.YSubsProvider;
import pct.droid.base.streamer.Ready;
import pct.droid.base.streamer.Status;
import pct.droid.base.utils.FileUtils;
import pct.droid.base.utils.LogUtils;

public class StreamLoadingActivity extends BaseActivity {

    public final static String STREAM_URL = "stream_url";
    public final static String DATA = "video_data";
    public final static String SHOW = "show";
    public final static String QUALITY = "quality";
    public final static String SUBTITLES = "subtitles";

    private FileObserver mFileObserver;
    private SubsProvider mSubsProvider;
    private Boolean mIntentStarted = false, mHasSubs = false;

    private enum SubsStatus {SUCCESS, FAILURE, DOWNLOADING};
    private SubsStatus mSubsStatus = SubsStatus.DOWNLOADING;

    @InjectView(R.id.progressIndicator)
    ProgressBar progressIndicator;
    @InjectView(R.id.progressText)
    TextView progressText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState, R.layout.activity_streamloading);

        while (!getApp().isServiceBound()) {
            getApp().startService();
        }

        if (!getIntent().hasExtra(STREAM_URL) && !getIntent().hasExtra(DATA)) {
            finish();
        }

        String streamUrl = getIntent().getStringExtra(STREAM_URL);
        final Media data = getIntent().getParcelableExtra(DATA);

        if (getIntent().hasExtra(SUBTITLES)) {
            mHasSubs = true;
            String subtitleLanguage = getIntent().getStringExtra(SUBTITLES);
            if (!subtitleLanguage.equals("no-subs")) {
                SubsProvider.download(this, data, subtitleLanguage, new Callback() {
                    @Override
                    public void onFailure(Request request, IOException e) {
                        mSubsStatus = SubsStatus.FAILURE;
                    }

                    @Override
                    public void onResponse(Response response) throws IOException {
                        mSubsStatus = SubsStatus.SUCCESS;
                    }
                });
            }
        } else {
            // TODO: make more generic
            if(data instanceof Movie) {
                mSubsProvider = new YSubsProvider();
                mSubsProvider.getList((Movie) data, new SubsProvider.Callback() {
                    @Override
                    public void onSuccess(Map<String, String> items) {
                        data.subtitles = items;
                        mSubsStatus = SubsStatus.SUCCESS;
                    }

                    @Override
                    public void onFailure(Exception e) {
                        mSubsStatus = SubsStatus.FAILURE;
                    }
                });
            } else {
                mSubsProvider = new OpenSubsProvider();
                Show.Episode episode = (Show.Episode) data;
                Show show = getIntent().getParcelableExtra(SHOW);
                mSubsProvider.getList(show, episode, new SubsProvider.Callback() {
                    @Override
                    public void onSuccess(Map<String, String> items) {
                        data.subtitles = items;
                        mSubsStatus = SubsStatus.SUCCESS;
                    }

                    @Override
                    public void onFailure(Exception e) {
                        mSubsStatus = SubsStatus.FAILURE;
                    }
                });
            }
        }

        getApp().startStreamer(streamUrl);

        String directory = getApp().getStreamDir();
        mFileObserver = new FileObserver(directory) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null) return;
                if (path.contains("streamer.json")) {
                    switch (event) {
                        case CREATE:
                            break;
                        case MODIFY:
                            startPlayer();
                            break;
                    }
                } else if (path.contains("status.json")) {
                    switch (event) {
                        case CREATE:
                        case MODIFY:
                            updateStatus();
                            break;
                    }
                }
            }
        };

        mFileObserver.startWatching();
    }

    private void startPlayer() {
        if (mHasSubs && mSubsStatus == SubsStatus.DOWNLOADING) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    progressText.setText("Waiting for subtitles"); // TODO: translation (by sv244)
                }
            });
            return;
        }

        if (!mIntentStarted && progressIndicator.getProgress() == progressIndicator.getMax()) {
            try {
                Ready ready = Ready.parseJSON(FileUtils.getContentsAsString(getApp().getStreamDir() + "/streamer.json"));
                mIntentStarted = true;
                Intent i = new Intent(StreamLoadingActivity.this, VideoPlayerActivity.class);
                if (getIntent().hasExtra(DATA)) {
                    i.putExtra(VideoPlayerActivity.DATA, getIntent().getParcelableExtra(DATA));
                }
                if (getIntent().hasExtra(QUALITY)) {
                    i.putExtra(VideoPlayerActivity.QUALITY, getIntent().getStringExtra(QUALITY));
                }
                if (mSubsStatus == SubsStatus.SUCCESS) {
                    i.putExtra(VideoPlayerActivity.SUBTITLES, getIntent().getStringExtra(SUBTITLES));
                }
                i.putExtra(VideoPlayerActivity.LOCATION, "file://" + ready.filePath);
                startActivity(i);
                finish();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void updateStatus() {
        try {
            final Status status = Status.parseJSON(FileUtils.getContentsAsString(getApp().getStreamDir() + "/status.json"));
            if (status == null) return;
            LogUtils.d(status.toString());
            int calculateProgress = (int) Math.floor(status.progress * 25);
            if (calculateProgress > 100) calculateProgress = 100;
            final int progress = calculateProgress;
            if (progressIndicator.getProgress() < 100) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        progressIndicator.setIndeterminate(false);
                        progressIndicator.setProgress(progress);
                        progressText.setText(progress + "%");
                    }
                });
            } else {
                startPlayer();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (progressText.getText().toString().equals("Streaming"))
                            progressText.setText("Streaming"); // TODO: translation (by sv244)
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mFileObserver.startWatching();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mFileObserver.stopWatching();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        getApp().stopStreamer();
    }
}