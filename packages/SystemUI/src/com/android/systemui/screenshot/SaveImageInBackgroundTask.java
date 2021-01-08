/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.LogConfig.DEBUG_ACTIONS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_STORAGE;
import static com.android.systemui.screenshot.LogConfig.logTag;

import android.app.ActivityTaskManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ShareTransition;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * An AsyncTask that saves an image to the media store in the background.
 */
class SaveImageInBackgroundTask extends AsyncTask<Void, Void, Void> {
    private static final String TAG = logTag(SaveImageInBackgroundTask.class);

    private static final String SCREENSHOT_FILE_NAME_TEMPLATE = "Screenshot_%s.png";
    private static final String SCREENSHOT_ID_TEMPLATE = "Screenshot_%s";
    private static final String SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)";

    private final Context mContext;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final ScreenshotController.SaveImageInBackgroundData mParams;
    private final ScreenshotController.SavedImageData mImageData;
    private final String mImageFileName;
    private final long mImageTime;
    private final ScreenshotNotificationSmartActionsProvider mSmartActionsProvider;
    private final String mScreenshotId;
    private final boolean mSmartActionsEnabled;
    private final Random mRandom = new Random();
    private final Supplier<ShareTransition> mSharedElementTransition;
    private final ImageExporter mImageExporter;

    SaveImageInBackgroundTask(Context context, ImageExporter exporter,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotController.SaveImageInBackgroundData data,
            Supplier<ShareTransition> sharedElementTransition) {
        mContext = context;
        mScreenshotSmartActions = screenshotSmartActions;
        mImageData = new ScreenshotController.SavedImageData();
        mSharedElementTransition = sharedElementTransition;
        mImageExporter = exporter;

        // Prepare all the output metadata
        mParams = data;
        mImageTime = System.currentTimeMillis();
        String imageDate = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(mImageTime));
        mImageFileName = String.format(SCREENSHOT_FILE_NAME_TEMPLATE, imageDate);
        mScreenshotId = String.format(SCREENSHOT_ID_TEMPLATE, UUID.randomUUID());

        // Initialize screenshot notification smart actions provider.
        mSmartActionsEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS, true);
        if (mSmartActionsEnabled) {
            mSmartActionsProvider =
                    SystemUIFactory.getInstance()
                            .createScreenshotNotificationSmartActionsProvider(
                                    context, THREAD_POOL_EXECUTOR, new Handler());
        } else {
            // If smart actions is not enabled use empty implementation.
            mSmartActionsProvider = new ScreenshotNotificationSmartActionsProvider();
        }
    }

    @Override
    protected Void doInBackground(Void... paramsUnused) {
        if (isCancelled()) {
            if (DEBUG_STORAGE) {
                Log.d(TAG, "cancelled! returning null");
            }
            return null;
        }
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        Bitmap image = mParams.image;

        try {
            // Call synchronously here since already on a background thread.
            Uri uri = mImageExporter.export(Runnable::run, image).get();

            CompletableFuture<List<Notification.Action>> smartActionsFuture =
                    mScreenshotSmartActions.getSmartActionsFuture(
                            mScreenshotId, uri, image, mSmartActionsProvider,
                            mSmartActionsEnabled, getUserHandle(mContext));

            List<Notification.Action> smartActions = new ArrayList<>();
            if (mSmartActionsEnabled) {
                int timeoutMs = DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.SCREENSHOT_NOTIFICATION_SMART_ACTIONS_TIMEOUT_MS,
                        1000);
                smartActions.addAll(buildSmartActions(
                        mScreenshotSmartActions.getSmartActions(
                                mScreenshotId, smartActionsFuture, timeoutMs,
                                mSmartActionsProvider),
                        mContext));
            }

            mImageData.uri = uri;
            mImageData.smartActions = smartActions;
            mImageData.shareTransition = createShareAction(mContext, mContext.getResources(), uri);
            mImageData.editAction = createEditAction(mContext, mContext.getResources(), uri);
            mImageData.deleteAction = createDeleteAction(mContext, mContext.getResources(), uri);

            mParams.mActionsReadyListener.onActionsReady(mImageData);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "finished background processing, Calling (Consumer<Uri>) "
                        + "finisher.accept(\"" + mImageData.uri + "\"");
            }
            mParams.finisher.accept(mImageData.uri);
            mParams.image = null;
        } catch (Exception e) {
            // IOException/UnsupportedOperationException may be thrown if external storage is
            // not mounted
            if (DEBUG_STORAGE) {
                Log.d(TAG, "Failed to store screenshot", e);
            }
            mParams.clearImage();
            mImageData.reset();
            mParams.mActionsReadyListener.onActionsReady(mImageData);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "Calling (Consumer<Uri>) finisher.accept(null)");
            }
            mParams.finisher.accept(null);
        }

        return null;
    }

    /**
     * Update the listener run when the saving task completes. Used to avoid showing UI for the
     * first screenshot when a second one is taken.
     */
    void setActionsReadyListener(ScreenshotController.ActionsReadyListener listener) {
        mParams.mActionsReadyListener = listener;
    }

    @Override
    protected void onCancelled(Void params) {
        // If we are cancelled while the task is running in the background, we may get null
        // params. The finisher is expected to always be called back, so just use the baked-in
        // params from the ctor in any case.
        mImageData.reset();
        mParams.mActionsReadyListener.onActionsReady(mImageData);
        if (DEBUG_CALLBACK) {
            Log.d(TAG, "onCancelled, calling (Consumer<Uri>) finisher.accept(null)");
        }
        mParams.finisher.accept(null);
        mParams.clearImage();
    }

    /**
     * Assumes that the action intent is sent immediately after being supplied.
     */
    @VisibleForTesting
    Supplier<ShareTransition> createShareAction(Context context, Resources r, Uri uri) {
        return () -> {
            ShareTransition transition = mSharedElementTransition.get();

            // Note: Both the share and edit actions are proxied through ActionProxyReceiver in
            // order to do some common work like dismissing the keyguard and sending
            // closeSystemWindows

            // Create a share intent, this will always go through the chooser activity first
            // which should not trigger auto-enter PiP
            String subjectDate = DateFormat.getDateTimeInstance().format(new Date(mImageTime));
            String subject = String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate);
            Intent sharingIntent = new Intent(Intent.ACTION_SEND);
            sharingIntent.setType("image/png");
            sharingIntent.putExtra(Intent.EXTRA_STREAM, uri);
            // Include URI in ClipData also, so that grantPermission picks it up.
            // We don't use setData here because some apps interpret this as "to:".
            ClipData clipdata = new ClipData(new ClipDescription("content",
                    new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}),
                    new ClipData.Item(uri));
            sharingIntent.setClipData(clipdata);
            sharingIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
            sharingIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Make sure pending intents for the system user are still unique across users
            // by setting the (otherwise unused) request code to the current user id.
            int requestCode = context.getUserId();

            Intent sharingChooserIntent = Intent.createChooser(sharingIntent, null)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // cancel current pending intent (if any) since clipData isn't used for matching
            PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    context, 0, sharingChooserIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    transition.bundle, UserHandle.CURRENT);

            // Create a share action for the notification
            PendingIntent shareAction = PendingIntent.getBroadcastAsUser(context, requestCode,
                    new Intent(context, ActionProxyReceiver.class)
                            .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, pendingIntent)
                            .putExtra(ScreenshotController.EXTRA_DISALLOW_ENTER_PIP, true)
                            .putExtra(ScreenshotController.EXTRA_ID, mScreenshotId)
                            .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED,
                                    mSmartActionsEnabled)
                            .setAction(Intent.ACTION_SEND)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    UserHandle.SYSTEM);

            Notification.Action.Builder shareActionBuilder = new Notification.Action.Builder(
                    Icon.createWithResource(r, R.drawable.ic_screenshot_share),
                    r.getString(com.android.internal.R.string.share), shareAction);

            transition.shareAction = shareActionBuilder.build();
            return transition;
        };
    }

    @VisibleForTesting
    Notification.Action createEditAction(Context context, Resources r, Uri uri) {
        // Note: Both the share and edit actions are proxied through ActionProxyReceiver in
        // order to do some common work like dismissing the keyguard and sending
        // closeSystemWindows

        // Create an edit intent, if a specific package is provided as the editor, then
        // launch that directly
        String editorPackage = context.getString(R.string.config_screenshotEditor);
        Intent editIntent = new Intent(Intent.ACTION_EDIT);
        if (!TextUtils.isEmpty(editorPackage)) {
            editIntent.setComponent(ComponentName.unflattenFromString(editorPackage));
        }
        editIntent.setDataAndType(uri, "image/png");
        editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context, 0,
                editIntent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);

        // Make sure pending intents for the system user are still unique across users
        // by setting the (otherwise unused) request code to the current user id.
        int requestCode = mContext.getUserId();

        // Create a edit action
        PendingIntent editAction = PendingIntent.getBroadcastAsUser(context, requestCode,
                new Intent(context, ActionProxyReceiver.class)
                        .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, pendingIntent)
                        .putExtra(ScreenshotController.EXTRA_ID, mScreenshotId)
                        .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled)
                        .setAction(Intent.ACTION_EDIT)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                UserHandle.SYSTEM);
        Notification.Action.Builder editActionBuilder = new Notification.Action.Builder(
                Icon.createWithResource(r, R.drawable.ic_screenshot_edit),
                r.getString(com.android.internal.R.string.screenshot_edit), editAction);

        return editActionBuilder.build();
    }

    @VisibleForTesting
    Notification.Action createDeleteAction(Context context, Resources r, Uri uri) {
        // Make sure pending intents for the system user are still unique across users
        // by setting the (otherwise unused) request code to the current user id.
        int requestCode = mContext.getUserId();

        // Create a delete action for the notification
        PendingIntent deleteAction = PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, DeleteScreenshotReceiver.class)
                        .putExtra(ScreenshotController.SCREENSHOT_URI_ID, uri.toString())
                        .putExtra(ScreenshotController.EXTRA_ID, mScreenshotId)
                        .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_CANCEL_CURRENT
                        | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder deleteActionBuilder = new Notification.Action.Builder(
                Icon.createWithResource(r, R.drawable.ic_screenshot_delete),
                r.getString(com.android.internal.R.string.delete), deleteAction);

        return deleteActionBuilder.build();
    }

    private int getUserHandleOfForegroundApplication(Context context) {
        // This logic matches
        // com.android.systemui.statusbar.phone.PhoneStatusBarPolicy#updateManagedProfile
        try {
            return ActivityTaskManager.getService().getLastResumedActivityUserId();
        } catch (RemoteException e) {
            if (DEBUG_ACTIONS) {
                Log.d(TAG, "Failed to get UserHandle of foreground app: ", e);
            }
            return context.getUserId();
        }
    }

    private UserHandle getUserHandle(Context context) {
        UserManager manager = UserManager.get(context);
        return manager.getUserInfo(getUserHandleOfForegroundApplication(context)).getUserHandle();
    }

    private List<Notification.Action> buildSmartActions(
            List<Notification.Action> actions, Context context) {
        List<Notification.Action> broadcastActions = new ArrayList<>();
        for (Notification.Action action : actions) {
            // Proxy smart actions through {@link GlobalScreenshot.SmartActionsReceiver}
            // for logging smart actions.
            Bundle extras = action.getExtras();
            String actionType = extras.getString(
                    ScreenshotNotificationSmartActionsProvider.ACTION_TYPE,
                    ScreenshotNotificationSmartActionsProvider.DEFAULT_ACTION_TYPE);
            Intent intent = new Intent(context, SmartActionsReceiver.class)
                    .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, action.actionIntent)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            addIntentExtras(mScreenshotId, intent, actionType, mSmartActionsEnabled);
            PendingIntent broadcastIntent = PendingIntent.getBroadcast(context,
                    mRandom.nextInt(),
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            broadcastActions.add(new Notification.Action.Builder(action.getIcon(), action.title,
                    broadcastIntent).setContextual(true).addExtras(extras).build());
        }
        return broadcastActions;
    }

    private static void addIntentExtras(String screenshotId, Intent intent, String actionType,
            boolean smartActionsEnabled) {
        intent
                .putExtra(ScreenshotController.EXTRA_ACTION_TYPE, actionType)
                .putExtra(ScreenshotController.EXTRA_ID, screenshotId)
                .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED, smartActionsEnabled);
    }
}
