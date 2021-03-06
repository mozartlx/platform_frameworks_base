/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.print;

import android.content.Context;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.printservice.PrintServiceInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.os.SomeArgs;

import libcore.io.IoUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * System level service for accessing the printing capabilities of the platform.
 * <p>
 * To obtain a handle to the print manager do the following:
 * </p>
 * 
 * <pre>
 * PrintManager printManager =
 *         (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
 * </pre>
 */
public final class PrintManager {

    private static final String LOG_TAG = "PrintManager";

    private static final boolean DEBUG = false;

    private static final int MSG_NOTIFY_PRINT_JOB_STATE_CHANGED = 1;

    /**
     * The action for launching the print dialog activity.
     *
     * @hide
     */
    public static final String ACTION_PRINT_DIALOG = "android.print.PRINT_DIALOG";

    /**
     * Extra with the intent for starting the print dialog.
     * <p>
     * <strong>Type:</strong> {@link android.content.IntentSender}
     * </p>
     *
     * @hide
     */
    public static final String EXTRA_PRINT_DIALOG_INTENT =
            "android.print.intent.extra.EXTRA_PRINT_DIALOG_INTENT";

    /**
     * Extra with a print job.
     * <p>
     * <strong>Type:</strong> {@link android.print.PrintJobInfo}
     * </p>
     *
     * @hide
     */
    public static final String EXTRA_PRINT_JOB =
            "android.print.intent.extra.EXTRA_PRINT_JOB";

    /**
     * Extra with the print document adapter to be printed.
     * <p>
     * <strong>Type:</strong> {@link android.print.IPrintDocumentAdapter}
     * </p>
     *
     * @hide
     */
    public static final String EXTRA_PRINT_DOCUMENT_ADAPTER =
            "android.print.intent.extra.EXTRA_PRINT_DOCUMENT_ADAPTER";

    /** @hide */
    public static final int APP_ID_ANY = -2;

    private final Context mContext;

    private final IPrintManager mService;

    private final int mUserId;

    private final int mAppId;

    private final Handler mHandler;

    private Map<PrintJobStateChangeListener, PrintJobStateChangeListenerWrapper> mPrintJobStateChangeListeners;

    /** @hide */
    public interface PrintJobStateChangeListener {

        /**
         * Callback notifying that a print job state changed.
         * 
         * @param printJobId The print job id.
         */
        public void onPrintJobStateChanged(PrintJobId printJobId);
    }

    /**
     * Creates a new instance.
     * 
     * @param context The current context in which to operate.
     * @param service The backing system service.
     * @hide
     */
    public PrintManager(Context context, IPrintManager service, int userId, int appId) {
        mContext = context;
        mService = service;
        mUserId = userId;
        mAppId = appId;
        mHandler = new Handler(context.getMainLooper(), null, false) {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_NOTIFY_PRINT_JOB_STATE_CHANGED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PrintJobStateChangeListenerWrapper wrapper =
                                (PrintJobStateChangeListenerWrapper) args.arg1;
                        PrintJobStateChangeListener listener = wrapper.getListener();
                        if (listener != null) {
                            PrintJobId printJobId = (PrintJobId) args.arg2;
                            listener.onPrintJobStateChanged(printJobId);
                        }
                        args.recycle();
                    } break;
                }
            }
        };
    }

    /**
     * Creates an instance that can access all print jobs.
     * 
     * @param userId The user id for which to get all print jobs.
     * @return An instance if the caller has the permission to access all print
     *         jobs, null otherwise.
     * @hide
     */
    public PrintManager getGlobalPrintManagerForUser(int userId) {
        return new PrintManager(mContext, mService, userId, APP_ID_ANY);
    }

    PrintJobInfo getPrintJobInfo(PrintJobId printJobId) {
        try {
            return mService.getPrintJobInfo(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting a print job info:" + printJobId, re);
        }
        return null;
    }

    /**
     * Adds a listener for observing the state of print jobs.
     * 
     * @param listener The listener to add.
     * @hide
     */
    public void addPrintJobStateChangeListener(PrintJobStateChangeListener listener) {
        if (mPrintJobStateChangeListeners == null) {
            mPrintJobStateChangeListeners = new ArrayMap<PrintJobStateChangeListener,
                    PrintJobStateChangeListenerWrapper>();
        }
        PrintJobStateChangeListenerWrapper wrappedListener =
                new PrintJobStateChangeListenerWrapper(listener, mHandler);
        try {
            mService.addPrintJobStateChangeListener(wrappedListener, mAppId, mUserId);
            mPrintJobStateChangeListeners.put(listener, wrappedListener);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error adding print job state change listener", re);
        }
    }

    /**
     * Removes a listener for observing the state of print jobs.
     * 
     * @param listener The listener to remove.
     * @hide
     */
    public void removePrintJobStateChangeListener(PrintJobStateChangeListener listener) {
        if (mPrintJobStateChangeListeners == null) {
            return;
        }
        PrintJobStateChangeListenerWrapper wrappedListener =
                mPrintJobStateChangeListeners.remove(listener);
        if (wrappedListener == null) {
            return;
        }
        if (mPrintJobStateChangeListeners.isEmpty()) {
            mPrintJobStateChangeListeners = null;
        }
        wrappedListener.destroy();
        try {
            mService.removePrintJobStateChangeListener(wrappedListener, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error removing print job state change listener", re);
        }
    }

    /**
     * Gets a print job given its id.
     * 
     * @return The print job list.
     * @see PrintJob
     * @hide
     */
    public PrintJob getPrintJob(PrintJobId printJobId) {
        try {
            PrintJobInfo printJob = mService.getPrintJobInfo(printJobId, mAppId, mUserId);
            if (printJob != null) {
                return new PrintJob(printJob, this);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting print job", re);
        }
        return null;
    }

    /**
     * Gets the print jobs for this application.
     * 
     * @return The print job list.
     * @see PrintJob
     */
    public List<PrintJob> getPrintJobs() {
        try {
            List<PrintJobInfo> printJobInfos = mService.getPrintJobInfos(mAppId, mUserId);
            if (printJobInfos == null) {
                return Collections.emptyList();
            }
            final int printJobCount = printJobInfos.size();
            List<PrintJob> printJobs = new ArrayList<PrintJob>(printJobCount);
            for (int i = 0; i < printJobCount; i++) {
                printJobs.add(new PrintJob(printJobInfos.get(i), this));
            }
            return printJobs;
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting print jobs", re);
        }
        return Collections.emptyList();
    }

    void cancelPrintJob(PrintJobId printJobId) {
        try {
            mService.cancelPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error cancleing a print job: " + printJobId, re);
        }
    }

    void restartPrintJob(PrintJobId printJobId) {
        try {
            mService.restartPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error restarting a print job: " + printJobId, re);
        }
    }

    /**
     * Creates a print job for printing a {@link PrintDocumentAdapter} with
     * default print attributes.
     * 
     * @param printJobName A name for the new print job.
     * @param documentAdapter An adapter that emits the document to print.
     * @param attributes The default print job attributes.
     * @return The created print job on success or null on failure.
     * @see PrintJob
     */
    public PrintJob print(String printJobName, PrintDocumentAdapter documentAdapter,
            PrintAttributes attributes) {
        if (TextUtils.isEmpty(printJobName)) {
            throw new IllegalArgumentException("priintJobName cannot be empty");
        }
        PrintDocumentAdapterDelegate delegate = new PrintDocumentAdapterDelegate(documentAdapter,
                mContext.getMainLooper());
        try {
            Bundle result = mService.print(printJobName, delegate,
                    attributes, mContext.getPackageName(), mAppId, mUserId);
            if (result != null) {
                PrintJobInfo printJob = result.getParcelable(EXTRA_PRINT_JOB);
                IntentSender intent = result.getParcelable(EXTRA_PRINT_DIALOG_INTENT);
                if (printJob == null || intent == null) {
                    return null;
                }
                try {
                    mContext.startIntentSender(intent, null, 0, 0, 0);
                    return new PrintJob(printJob, this);
                } catch (SendIntentException sie) {
                    Log.e(LOG_TAG, "Couldn't start print job config activity.", sie);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error creating a print job", re);
        }
        return null;
    }

    /**
     * Gets the list of enabled print services.
     * 
     * @return The enabled service list or an empty list.
     * @hide
     */
    public List<PrintServiceInfo> getEnabledPrintServices() {
        try {
            List<PrintServiceInfo> enabledServices = mService.getEnabledPrintServices(mUserId);
            if (enabledServices != null) {
                return enabledServices;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting the enabled print services", re);
        }
        return Collections.emptyList();
    }

    /**
     * Gets the list of installed print services.
     * 
     * @return The installed service list or an empty list.
     * @hide
     */
    public List<PrintServiceInfo> getInstalledPrintServices() {
        try {
            List<PrintServiceInfo> installedServices = mService.getInstalledPrintServices(mUserId);
            if (installedServices != null) {
                return installedServices;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting the installed print services", re);
        }
        return Collections.emptyList();
    }

    /**
     * @hide
     */
    public PrinterDiscoverySession createPrinterDiscoverySession() {
        return new PrinterDiscoverySession(mService, mContext, mUserId);
    }

    private static final class PrintDocumentAdapterDelegate extends IPrintDocumentAdapter.Stub {

        private final Object mLock = new Object();

        private CancellationSignal mLayoutOrWriteCancellation;

        private PrintDocumentAdapter mDocumentAdapter; // Strong reference OK -
                                                       // cleared in finish()

        private Handler mHandler; // Strong reference OK - cleared in finish()

        private LayoutSpec mLastLayoutSpec;

        private WriteSpec mLastWriteSpec;

        private boolean mStartReqeusted;
        private boolean mStarted;

        private boolean mFinishRequested;
        private boolean mFinished;

        public PrintDocumentAdapterDelegate(PrintDocumentAdapter documentAdapter, Looper looper) {
            mDocumentAdapter = documentAdapter;
            mHandler = new MyHandler(looper);
        }

        @Override
        public void start() {
            synchronized (mLock) {
                // Started or finished - nothing to do.
                if (mStartReqeusted || mFinishRequested) {
                    return;
                }

                mStartReqeusted = true;

                doPendingWorkLocked();
            }
        }

        @Override
        public void layout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                ILayoutResultCallback callback, Bundle metadata, int sequence) {
            synchronized (mLock) {
                // Start not called or finish called - nothing to do.
                if (!mStartReqeusted || mFinishRequested) {
                    return;
                }

                // Layout cancels write and overrides layout.
                if (mLastWriteSpec != null) {
                    IoUtils.closeQuietly(mLastWriteSpec.fd);
                    mLastWriteSpec = null;
                }

                mLastLayoutSpec = new LayoutSpec();
                mLastLayoutSpec.callback = callback;
                mLastLayoutSpec.oldAttributes = oldAttributes;
                mLastLayoutSpec.newAttributes = newAttributes;
                mLastLayoutSpec.metadata = metadata;
                mLastLayoutSpec.sequence = sequence;

                // Cancel the previous cancellable operation.When the
                // cancellation completes we will do the pending work.
                if (cancelPreviousCancellableOperationLocked()) {
                    return;
                }

                doPendingWorkLocked();
            }
        }

        @Override
        public void write(PageRange[] pages, ParcelFileDescriptor fd,
                IWriteResultCallback callback, int sequence) {
            synchronized (mLock) {
                // Start not called or finish called - nothing to do.
                if (!mStartReqeusted || mFinishRequested) {
                    return;
                }

                // Write cancels previous writes.
                if (mLastWriteSpec != null) {
                    IoUtils.closeQuietly(mLastWriteSpec.fd);
                    mLastWriteSpec = null;
                }

                mLastWriteSpec = new WriteSpec();
                mLastWriteSpec.callback = callback;
                mLastWriteSpec.pages = pages;
                mLastWriteSpec.fd = fd;
                mLastWriteSpec.sequence = sequence;

                // Cancel the previous cancellable operation.When the
                // cancellation completes we will do the pending work.
                if (cancelPreviousCancellableOperationLocked()) {
                    return;
                }

                doPendingWorkLocked();
            }
        }

        @Override
        public void finish() {
            synchronized (mLock) {
                // Start not called or finish called - nothing to do.
                if (!mStartReqeusted || mFinishRequested) {
                    return;
                }

                mFinishRequested = true;

                // When the current write or layout complete we
                // will do the pending work.
                if (mLastLayoutSpec != null || mLastWriteSpec != null) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Waiting for current operation");
                    }
                    return;
                }

                doPendingWorkLocked();
            }
        }

        private boolean isFinished() {
            return mDocumentAdapter == null;
        }

        private void doFinish() {
            mDocumentAdapter = null;
            mHandler = null;
            synchronized (mLock) {
                mLayoutOrWriteCancellation = null;
            }
        }

        private boolean cancelPreviousCancellableOperationLocked() {
            if (mLayoutOrWriteCancellation != null) {
                mLayoutOrWriteCancellation.cancel();
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cancelling previous operation");
                }
                return true;
            }
            return false;
        }

        private void doPendingWorkLocked() {
            if (mStartReqeusted && !mStarted) {
                mStarted = true;
                mHandler.sendEmptyMessage(MyHandler.MSG_START);
            } else if (mLastLayoutSpec != null) {
                mHandler.sendEmptyMessage(MyHandler.MSG_LAYOUT);
            } else if (mLastWriteSpec != null) {
                mHandler.sendEmptyMessage(MyHandler.MSG_WRITE);
            } else if (mFinishRequested && !mFinished) {
                mFinished = true;
                mHandler.sendEmptyMessage(MyHandler.MSG_FINISH);
            }
        }

        private class LayoutSpec {
            ILayoutResultCallback callback;
            PrintAttributes oldAttributes;
            PrintAttributes newAttributes;
            Bundle metadata;
            int sequence;
        }

        private class WriteSpec {
            IWriteResultCallback callback;
            PageRange[] pages;
            ParcelFileDescriptor fd;
            int sequence;
        }

        private final class MyHandler extends Handler {
            public static final int MSG_START = 1;
            public static final int MSG_LAYOUT = 2;
            public static final int MSG_WRITE = 3;
            public static final int MSG_FINISH = 4;

            public MyHandler(Looper looper) {
                super(looper, null, true);
            }

            @Override
            public void handleMessage(Message message) {
                if (isFinished()) {
                    return;
                }
                switch (message.what) {
                    case MSG_START: {
                        mDocumentAdapter.onStart();
                    }
                        break;

                    case MSG_LAYOUT: {
                        final CancellationSignal cancellation;
                        final LayoutSpec layoutSpec;

                        synchronized (mLock) {
                            layoutSpec = mLastLayoutSpec;
                            mLastLayoutSpec = null;
                            cancellation = new CancellationSignal();
                            mLayoutOrWriteCancellation = cancellation;
                        }

                        if (layoutSpec != null) {
                            if (DEBUG) {
                                Log.i(LOG_TAG, "Performing layout");
                            }
                            mDocumentAdapter.onLayout(layoutSpec.oldAttributes,
                                    layoutSpec.newAttributes, cancellation,
                                    new MyLayoutResultCallback(layoutSpec.callback,
                                            layoutSpec.sequence), layoutSpec.metadata);
                        }
                    }
                        break;

                    case MSG_WRITE: {
                        final CancellationSignal cancellation;
                        final WriteSpec writeSpec;

                        synchronized (mLock) {
                            writeSpec = mLastWriteSpec;
                            mLastWriteSpec = null;
                            cancellation = new CancellationSignal();
                            mLayoutOrWriteCancellation = cancellation;
                        }

                        if (writeSpec != null) {
                            if (DEBUG) {
                                Log.i(LOG_TAG, "Performing write");
                            }
                            mDocumentAdapter.onWrite(writeSpec.pages, writeSpec.fd,
                                    cancellation, new MyWriteResultCallback(writeSpec.callback,
                                            writeSpec.fd, writeSpec.sequence));
                        }
                    }
                        break;

                    case MSG_FINISH: {
                        if (DEBUG) {
                            Log.i(LOG_TAG, "Performing finish");
                        }
                        mDocumentAdapter.onFinish();
                        doFinish();
                    }
                        break;

                    default: {
                        throw new IllegalArgumentException("Unknown message: "
                                + message.what);
                    }
                }
            }
        }

        private final class MyLayoutResultCallback extends LayoutResultCallback {
            private ILayoutResultCallback mCallback;
            private final int mSequence;

            public MyLayoutResultCallback(ILayoutResultCallback callback,
                    int sequence) {
                mCallback = callback;
                mSequence = sequence;
            }

            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                if (info == null) {
                    throw new NullPointerException("document info cannot be null");
                }
                final ILayoutResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (callback != null) {
                    try {
                        callback.onLayoutFinished(info, changed, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onLayoutFinished", re);
                    }
                }
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                final ILayoutResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (callback != null) {
                    try {
                        callback.onLayoutFailed(error, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onLayoutFailed", re);
                    }
                }
            }

            @Override
            public void onLayoutCancelled() {
                synchronized (mLock) {
                    clearLocked();
                }
            }

            private void clearLocked() {
                mLayoutOrWriteCancellation = null;
                mCallback = null;
                doPendingWorkLocked();
            }
        }

        private final class MyWriteResultCallback extends WriteResultCallback {
            private ParcelFileDescriptor mFd;
            private int mSequence;
            private IWriteResultCallback mCallback;

            public MyWriteResultCallback(IWriteResultCallback callback,
                    ParcelFileDescriptor fd, int sequence) {
                mFd = fd;
                mSequence = sequence;
                mCallback = callback;
            }

            @Override
            public void onWriteFinished(PageRange[] pages) {
                final IWriteResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (pages == null) {
                    throw new IllegalArgumentException("pages cannot be null");
                }
                if (pages.length == 0) {
                    throw new IllegalArgumentException("pages cannot be empty");
                }
                if (callback != null) {
                    try {
                        callback.onWriteFinished(pages, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onWriteFinished", re);
                    }
                }
            }

            @Override
            public void onWriteFailed(CharSequence error) {
                final IWriteResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (callback != null) {
                    try {
                        callback.onWriteFailed(error, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onWriteFailed", re);
                    }
                }
            }

            @Override
            public void onWriteCancelled() {
                synchronized (mLock) {
                    clearLocked();
                }
            }

            private void clearLocked() {
                mLayoutOrWriteCancellation = null;
                IoUtils.closeQuietly(mFd);
                mCallback = null;
                mFd = null;
                doPendingWorkLocked();
            }
        }
    }

    private static final class PrintJobStateChangeListenerWrapper extends
            IPrintJobStateChangeListener.Stub {
        private final WeakReference<PrintJobStateChangeListener> mWeakListener;
        private final WeakReference<Handler> mWeakHandler;

        public PrintJobStateChangeListenerWrapper(PrintJobStateChangeListener listener,
                Handler handler) {
            mWeakListener = new WeakReference<PrintJobStateChangeListener>(listener);
            mWeakHandler = new WeakReference<Handler>(handler);
        }

        @Override
        public void onPrintJobStateChanged(PrintJobId printJobId) {
            Handler handler = mWeakHandler.get();
            PrintJobStateChangeListener listener = mWeakListener.get();
            if (handler != null && listener != null) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = this;
                args.arg2 = printJobId;
                handler.obtainMessage(MSG_NOTIFY_PRINT_JOB_STATE_CHANGED,
                        args).sendToTarget();
            }
        }

        public void destroy() {
            mWeakListener.clear();
        }

        public PrintJobStateChangeListener getListener() {
            return mWeakListener.get();
        }
    }
}
