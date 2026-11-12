package com.mrspark.cordova.plugin;

import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;

import android.app.Activity;
import android.content.Context;
import com.google.android.material.snackbar.Snackbar;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class UpdatePlugin extends CordovaPlugin {
    public int REQUEST_CODE = 7;
    private String IN_APP_UPDATE_TYPE = "FLEXIBLE";
    private Integer DAYS_FOR_FLEXIBLE_UPDATE = 0;
    private Integer DAYS_FOR_IMMEDIATE_UPDATE = 0;
    private static final Integer HIGH_PRIORITY_UPDATE = 3;
    private static final Integer MEDIUM_PRIORITY_UPDATE = 1;
    private AppUpdateManager appUpdateManager;
    private InstallStateUpdatedListener listener;
    private FrameLayout layout;
    private CallbackContext pendingCallbackContext;

    @Override
    public void initialize(final CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);
        try {
            layout = (FrameLayout) webView.getView().getParent();
        } catch (Exception e) {
            layout = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (appUpdateManager != null && listener != null) {
            try {
                appUpdateManager.unregisterListener(listener);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        listener = null;
        appUpdateManager = null;
        pendingCallbackContext = null;
    }

    public void onStateUpdate(final InstallState state) {
        if (state != null && state.installStatus() == InstallStatus.DOWNLOADED) {
            popupSnackbarForCompleteUpdate();
        }
    }

    public void checkForUpdate(final int updateType, final AppUpdateInfo appUpdateInfo) {
        if (updateType == AppUpdateType.FLEXIBLE) {
            IN_APP_UPDATE_TYPE = "FLEXIBLE";
            listener = state -> {
                onStateUpdate(state);
            };
            if (appUpdateManager != null) {
                appUpdateManager.registerListener(listener);
            }
        } else {
            IN_APP_UPDATE_TYPE = "IMMEDIATE";
        }
        try {
            final Activity activity = cordova.getActivity();
            if (activity != null && appUpdateManager != null && appUpdateInfo != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            appUpdateManager.startUpdateFlowForResult(appUpdateInfo, updateType, activity, REQUEST_CODE);
                        } catch (final Exception e) {
                            e.printStackTrace();
                            if (pendingCallbackContext != null) {
                                pendingCallbackContext.error(e.getMessage());
                                pendingCallbackContext = null;
                            }
                        }
                    }
                });
            }
        } catch (final Exception e) {
            e.printStackTrace();
            if (pendingCallbackContext != null) {
                pendingCallbackContext.error(e.getMessage());
                pendingCallbackContext = null;
            }
        }
    }

    /* Displays the snackbar notification and call to action. */
    private void popupSnackbarForCompleteUpdate() {
        if (layout == null || appUpdateManager == null) {
            return;
        }
        final Activity activity = cordova.getActivity();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final Snackbar snackbar = Snackbar.make(layout, "An update has just been downloaded.",
                        Snackbar.LENGTH_INDEFINITE);
                snackbar.setAction("RESTART", view -> appUpdateManager.completeUpdate());
                snackbar.show();
            }
        });
    }

    private JSONObject getAndroidArgs(JSONArray args) {
        try {
            if (args == null || args.length() == 0) {
                return new JSONObject();
            }
            final JSONObject argument = args.getJSONObject(0);
            if (argument.has("ANDROID")) {
                return argument.getJSONObject("ANDROID");
            }
            return new JSONObject();
        } catch (final JSONException e) {
            e.printStackTrace();
            return new JSONObject();
        }
    }

    private String getStringOrDefault(JSONObject obj, String key, String defaultValue) {
        try {
            if (obj.has(key)) {
                Object val = obj.get(key);
                if (val instanceof String) {
                    return (String) val;
                } else if (val instanceof Integer || val instanceof Long) {
                    return String.valueOf(val);
                } else if (val instanceof JSONObject || val instanceof JSONArray) {
                    return val.toString();
                }
            }
        } catch (JSONException e) {
            // fall through to default
        }
        return defaultValue;
    }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) {
        if (!action.equals("update") && !action.equals("getAvailableVersion")) {
            callbackContext.error("\"" + action + "\" is not a recognized action.");
            return false;
        }

        final Context context = this.cordova.getContext();
        if (context == null) {
            callbackContext.error("Cordova context is null.");
            return false;
        }

        // Offload all Play Core work to Cordova's thread pool to avoid blocking the main UI thread
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    appUpdateManager = AppUpdateManagerFactory.create(context);
                } catch (Exception e) {
                    e.printStackTrace();
                    cordova.getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            callbackContext.error("Failed to create AppUpdateManager: " + e.getMessage());
                        }
                    });
                    return;
                }

                final Task<AppUpdateInfo> appUpdateInfoTask = appUpdateManager.getAppUpdateInfo();
                final JSONObject androidArgs = getAndroidArgs(args);

                try {
                    final String type = getStringOrDefault(androidArgs, "type", "FLEXIBLE");

                    if (type.equals("MIXED")) {
                        DAYS_FOR_FLEXIBLE_UPDATE = Integer.parseInt(getStringOrDefault(androidArgs, "flexibleUpdateStalenessDays", "0"));
                        DAYS_FOR_IMMEDIATE_UPDATE = Integer.parseInt(getStringOrDefault(androidArgs, "immediateUpdateStalenessDays", "0"));
                    } else if (type.equals("FLEXIBLE")) {
                        DAYS_FOR_FLEXIBLE_UPDATE = Integer.parseInt(getStringOrDefault(androidArgs, "stallDays", "0"));
                        DAYS_FOR_IMMEDIATE_UPDATE = 999999999;
                    } else if (type.equals("IMMEDIATE")) {
                        DAYS_FOR_FLEXIBLE_UPDATE = 999999999;
                        DAYS_FOR_IMMEDIATE_UPDATE = Integer.parseInt(getStringOrDefault(androidArgs, "stallDays", "0"));
                    } else {
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                callbackContext.error("Unknown update type: " + type);
                            }
                        });
                        return;
                    }

                    pendingCallbackContext = callbackContext;

                    appUpdateInfoTask.addOnSuccessListener(appUpdateInfo -> {
                        try {
                            int clientVersionStalenessDays = 0;
                            try {
                                Integer staleness = appUpdateInfo.clientVersionStalenessDays();
                                if (staleness != null) {
                                    clientVersionStalenessDays = staleness;
                                }
                            } catch (Exception e) {
                                // ignore, use default 0
                            }

                            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                                    && appUpdateInfo.updatePriority() >= HIGH_PRIORITY_UPDATE
                                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                                checkForUpdate(AppUpdateType.IMMEDIATE, appUpdateInfo);
                            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                                    && appUpdateInfo.updatePriority() >= MEDIUM_PRIORITY_UPDATE
                                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                                checkForUpdate(AppUpdateType.FLEXIBLE, appUpdateInfo);
                            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                                    && clientVersionStalenessDays >= DAYS_FOR_IMMEDIATE_UPDATE
                                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                                checkForUpdate(AppUpdateType.IMMEDIATE, appUpdateInfo);
                            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                                    && clientVersionStalenessDays >= DAYS_FOR_FLEXIBLE_UPDATE
                                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                                checkForUpdate(AppUpdateType.FLEXIBLE, appUpdateInfo);
                            }

                            Integer availableVersionCode = appUpdateInfo.availableVersionCode();
                            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK,
                                    availableVersionCode != null ? availableVersionCode : 0);
                            pluginResult.setKeepCallback(true);
                            cordova.getActivity().runOnUiThread(new Runnable() {
                                public void run() {
                                    callbackContext.sendPluginResult(pluginResult);
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            cordova.getActivity().runOnUiThread(new Runnable() {
                                public void run() {
                                    callbackContext.error(e.getMessage());
                                }
                            });
                        }
                    });

                    appUpdateInfoTask.addOnFailureListener(exception -> {
                        exception.printStackTrace();
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                callbackContext.error("Failed to get app update info: " + exception.getMessage());
                            }
                        });
                    });

                } catch (final Exception e) {
                    e.printStackTrace();
                    cordova.getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            callbackContext.error(e.getMessage());
                        }
                    });
                }
            }
        });
        return true;
    }

    @Override
    public void onResume(final boolean multitasking) {
        super.onResume(multitasking);
        if (appUpdateManager == null) {
            return;
        }
        appUpdateManager
                .getAppUpdateInfo()
                .addOnSuccessListener(
                        appUpdateInfo -> {
                            if (IN_APP_UPDATE_TYPE.equals("FLEXIBLE")
                                    && appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                                popupSnackbarForCompleteUpdate();
                            }

                            if (appUpdateInfo
                                    .updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                                try {
                                    checkForUpdate(AppUpdateType.IMMEDIATE, appUpdateInfo);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
    }
}
