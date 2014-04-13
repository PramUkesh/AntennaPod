package de.danoeh.antennapod.fragment;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.mobeta.android.dslv.DragSortListView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.adapter.QueueListAdapter;
import de.danoeh.antennapod.asynctask.DownloadObserver;
import de.danoeh.antennapod.feed.EventDistributor;
import de.danoeh.antennapod.feed.FeedItem;
import de.danoeh.antennapod.feed.FeedMedia;
import de.danoeh.antennapod.service.download.Downloader;
import de.danoeh.antennapod.storage.DBReader;
import de.danoeh.antennapod.storage.DBWriter;
import de.danoeh.antennapod.util.UndoBarController;
import de.danoeh.antennapod.util.gui.FeedItemUndoToken;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shows all items in the queue
 */
public class QueueFragment extends Fragment {
    private static final String TAG = "QueueFragment";
    private static final int EVENTS = EventDistributor.DOWNLOAD_HANDLED |
            EventDistributor.DOWNLOAD_QUEUED |
            EventDistributor.QUEUE_UPDATE;

    private DragSortListView listView;
    private QueueListAdapter listAdapter;
    private TextView txtvEmpty;
    private ProgressBar progLoading;
    private UndoBarController undoBarController;

    private List<FeedItem> queue;
    private List<Downloader> downloaderList;

    private boolean itemsLoaded = false;
    private boolean viewsCreated = false;

    private AtomicReference<Activity> activity = new AtomicReference<Activity>();

    private DownloadObserver downloadObserver = null;

    /**
     * Download observer updates won't result in an upate of the list adapter if this is true.
     */
    private boolean blockDownloadObserverUpdate = false;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        startItemLoader();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventDistributor.getInstance().register(contentUpdate);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventDistributor.getInstance().unregister(contentUpdate);
        stopItemLoader();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        this.activity.set((MainActivity) activity);
        if (downloadObserver != null) {
            downloadObserver.setActivity(activity);
            downloadObserver.onResume();
        }
        if (viewsCreated && itemsLoaded) {
            onFragmentLoaded();
        }


    }

    @Override
    public void onDetach() {
        super.onDetach();
        listAdapter = null;
        undoBarController = null;
        activity.set(null);
        viewsCreated = false;
        blockDownloadObserverUpdate = false;
        if (downloadObserver != null) {
            downloadObserver.onPause();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View root = inflater.inflate(R.layout.queue_fragment, container, false);
        listView = (DragSortListView) root.findViewById(android.R.id.list);
        txtvEmpty = (TextView) root.findViewById(android.R.id.empty);
        progLoading = (ProgressBar) root.findViewById(R.id.progLoading);
        listView.setEmptyView(txtvEmpty);


        listView.setRemoveListener(new DragSortListView.RemoveListener() {
            @Override
            public void remove(int which) {
                stopItemLoader();
                FeedItem item = (FeedItem) listView.getAdapter().getItem(which);
                DBWriter.removeQueueItem(getActivity(), item.getId(), true);
                undoBarController.showUndoBar(false,
                        getString(R.string.removed_from_queue), new FeedItemUndoToken(item,
                                which)
                );
            }
        });

        undoBarController = new UndoBarController(root.findViewById(R.id.undobar), new UndoBarController.UndoListener() {
            @Override
            public void onUndo(Parcelable token) {
                // Perform the undo
                FeedItemUndoToken undoToken = (FeedItemUndoToken) token;
                if (token != null) {
                    long itemId = undoToken.getFeedItemId();
                    int position = undoToken.getPosition();
                    DBWriter.addQueueItemAt(getActivity(), itemId, position, false);
                }
            }
        });

        listView.setDragSortListener(new DragSortListView.DragSortListener() {
            @Override
            public void drag(int from, int to) {
                Log.d(TAG, "drag");
                blockDownloadObserverUpdate = true;
            }

            @Override
            public void drop(int from, int to) {
                Log.d(TAG, "drop");
                blockDownloadObserverUpdate = false;
                stopItemLoader();
                final FeedItem item = queue.remove(from);
                queue.add(to, item);
                listAdapter.notifyDataSetChanged();
                DBWriter.moveQueueItem(getActivity(), from, to, true);
            }

            @Override
            public void remove(int which) {

            }
        });

        if (!itemsLoaded) {
            progLoading.setVisibility(View.VISIBLE);
            txtvEmpty.setVisibility(View.GONE);
        }

        viewsCreated = true;

        if (itemsLoaded && activity.get() != null) {
            onFragmentLoaded();
        }

        return root;
    }

    private void onFragmentLoaded() {
        if (listAdapter == null) {
            listAdapter = new QueueListAdapter(activity.get(), itemAccess);
            listView.setAdapter(listAdapter);
            downloadObserver = new DownloadObserver(activity.get(), new Handler(), downloadObserverCallback);
            downloadObserver.onResume();
        }
        listAdapter.notifyDataSetChanged();
    }

    private DownloadObserver.Callback downloadObserverCallback = new DownloadObserver.Callback() {
        @Override
        public void onContentChanged() {
            if (listAdapter != null && !blockDownloadObserverUpdate) {
                listAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onDownloadDataAvailable(List<Downloader> downloaderList) {
            QueueFragment.this.downloaderList = downloaderList;
            if (listAdapter != null && !blockDownloadObserverUpdate) {
                listAdapter.notifyDataSetChanged();
            }
        }
    };

    private QueueListAdapter.ItemAccess itemAccess = new QueueListAdapter.ItemAccess() {
        @Override
        public int getCount() {
            return (itemsLoaded) ? queue.size() : 0;
        }

        @Override
        public FeedItem getItem(int position) {
            return (itemsLoaded) ? queue.get(position) : null;
        }

        @Override
        public int getItemDownloadProgressPercent(FeedItem item) {
            if (downloaderList != null) {
                for (Downloader downloader : downloaderList) {
                    if (downloader.getDownloadRequest().getFeedfileType() == FeedMedia.FEEDFILETYPE_FEEDMEDIA
                            && downloader.getDownloadRequest().getFeedfileId() == item.getMedia().getId()) {
                        return downloader.getDownloadRequest().getProgressPercent();
                    }
                }
            }
            return 0;
        }

        @Override
        public void onFeedItemSecondaryAction(FeedItem item) {

        }
    };

    private EventDistributor.EventListener contentUpdate = new EventDistributor.EventListener() {
        @Override
        public void update(EventDistributor eventDistributor, Integer arg) {
            if ((arg & EVENTS) != 0) {
                startItemLoader();
            }
        }
    };

    private ItemLoader itemLoader;

    private void startItemLoader() {
        if (itemLoader != null) {
            itemLoader.cancel(true);
        }
        itemLoader = new ItemLoader();
        itemLoader.execute();
    }

    private void stopItemLoader() {
        if (itemLoader != null) {
            itemLoader.cancel(true);
        }
    }

    private class ItemLoader extends AsyncTask<Void, Void, List<FeedItem>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (viewsCreated && !itemsLoaded) {
                listView.setVisibility(View.GONE);
                txtvEmpty.setVisibility(View.GONE);
                progLoading.setVisibility(View.VISIBLE);
            }
        }

        @Override
        protected void onPostExecute(List<FeedItem> feedItems) {
            super.onPostExecute(feedItems);
            listView.setVisibility(View.VISIBLE);
            progLoading.setVisibility(View.GONE);

            if (feedItems != null) {
                queue = feedItems;
                itemsLoaded = true;
                if (viewsCreated && activity.get() != null) {
                    onFragmentLoaded();
                }
            }
        }

        @Override
        protected List<FeedItem> doInBackground(Void... params) {
            Context context = activity.get();
            if (context != null) {
                return DBReader.getQueue(context);
            }
            return null;
        }
    }
}