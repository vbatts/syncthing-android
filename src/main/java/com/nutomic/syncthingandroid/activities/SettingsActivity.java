package com.nutomic.syncthingandroid.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.widget.Toast;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.model.Config;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Options;
import com.nutomic.syncthingandroid.service.RestApi;
import com.nutomic.syncthingandroid.service.SyncthingService;
import com.nutomic.syncthingandroid.views.WifiSsidPreference;

import java.security.InvalidParameterException;
import java.util.List;

import eu.chainfire.libsuperuser.Shell;

public class SettingsActivity extends SyncthingActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragment
            implements SyncthingActivity.OnServiceConnectedListener,
            SyncthingService.OnApiChangeListener, Preference.OnPreferenceChangeListener,
            Preference.OnPreferenceClickListener {

        private static final String TAG = "SettingsFragment";
        private static final String KEY_STTRACE = "sttrace";
        private static final String KEY_EXPORT_CONFIG = "export_config";
        private static final String KEY_IMPORT_CONFIG = "import_config";
        private static final String KEY_STRESET = "streset";

        private CheckBoxPreference mAlwaysRunInBackground;
        private CheckBoxPreference mSyncOnlyCharging;
        private CheckBoxPreference mSyncOnlyWifi;
        private WifiSsidPreference mSyncOnlyOnSSIDs;

        private EditTextPreference mDeviceName;
        private EditTextPreference mListenAddresses;
        private EditTextPreference mMaxRecvKbps;
        private EditTextPreference mMaxSendKbps;
        private CheckBoxPreference mNatEnabled;
        private CheckBoxPreference mLocalAnnounceEnabled;
        private CheckBoxPreference mGlobalAnnounceEnabled;
        private CheckBoxPreference mRelaysEnabled;
        private EditTextPreference mGlobalAnnounceServers;
        private EditTextPreference mAddress;
        private EditTextPreference mUser;
        private EditTextPreference mPassword;
        private CheckBoxPreference mUrAccepted;

        private CheckBoxPreference mUseRoot;

        private Preference mSyncthingVersion;

        private SyncthingService mSyncthingService;
        private RestApi mApi;

        private Options mOptions;
        private Config.Gui mGui;

        /**
         * Loads layout, sets version from Rest API.
         *
         * Manual target API as we manually check if ActionBar is available (for ActionBar back button).
         */
        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);

            ((SyncthingActivity) getActivity()).registerOnServiceConnectedListener(this);

            addPreferencesFromResource(R.xml.app_settings);
            PreferenceScreen screen = getPreferenceScreen();
            mAlwaysRunInBackground =
                    (CheckBoxPreference) findPreference(SyncthingService.PREF_ALWAYS_RUN_IN_BACKGROUND);
            mSyncOnlyCharging =
                    (CheckBoxPreference) findPreference(SyncthingService.PREF_SYNC_ONLY_CHARGING);
            mSyncOnlyWifi =
                    (CheckBoxPreference) findPreference(SyncthingService.PREF_SYNC_ONLY_WIFI);
            mSyncOnlyOnSSIDs =
                    (WifiSsidPreference) findPreference(SyncthingService.PREF_SYNC_ONLY_WIFI_SSIDS);

            mSyncOnlyCharging.setEnabled(mAlwaysRunInBackground.isChecked());
            mSyncOnlyWifi.setEnabled(mAlwaysRunInBackground.isChecked());
            mSyncOnlyOnSSIDs.setEnabled(mSyncOnlyWifi.isChecked());

            mDeviceName             = (EditTextPreference) findPreference("deviceName");
            mListenAddresses        = (EditTextPreference) findPreference("listenAddresses");
            mMaxRecvKbps            = (EditTextPreference) findPreference("maxRecvKbps");
            mMaxSendKbps            = (EditTextPreference) findPreference("maxSendKbps");
            mNatEnabled             = (CheckBoxPreference) findPreference("natEnabled");
            mLocalAnnounceEnabled   = (CheckBoxPreference) findPreference("localAnnounceEnabled");
            mGlobalAnnounceEnabled  = (CheckBoxPreference) findPreference("globalAnnounceEnabled");
            mRelaysEnabled          = (CheckBoxPreference) findPreference("relaysEnabled");
            mGlobalAnnounceServers  = (EditTextPreference) findPreference("globalAnnounceServers");
            mAddress                = (EditTextPreference) findPreference("address");
            mUser                   = (EditTextPreference) findPreference("user");
            mPassword               = (EditTextPreference) findPreference("password");
            mUrAccepted             = (CheckBoxPreference) findPreference("urAccepted");

            Preference exportConfig = findPreference("export_config");
            Preference importConfig = findPreference("import_config");

            Preference stTrace      = findPreference("sttrace");
            Preference stReset      = findPreference("streset");

            mUseRoot              = (CheckBoxPreference) findPreference(SyncthingService.PREF_USE_ROOT);
            Preference useWakelock       = findPreference(SyncthingService.PREF_USE_WAKE_LOCK);
            Preference foregroundService = findPreference("run_as_foreground_service");
            Preference useTor            = findPreference("use_tor");

            mSyncthingVersion       = findPreference("syncthing_version");
            Preference appVersion   = screen.findPreference("app_version");

            mSyncOnlyOnSSIDs.setEnabled(mSyncOnlyWifi.isChecked());
            setPreferenceCategoryChangeListener(findPreference("category_run_conditions"), this);

            setPreferenceCategoryChangeListener(
                    findPreference("category_syncthing_options"), this::onSyncthingPreferenceChange);

            exportConfig.setOnPreferenceClickListener(this);
            importConfig.setOnPreferenceClickListener(this);

            stTrace.setOnPreferenceChangeListener(this);
            stReset.setOnPreferenceClickListener(this);

            mUseRoot.setOnPreferenceChangeListener(this);
            useWakelock.setOnPreferenceChangeListener(this::onRequireRestart);
            foregroundService.setOnPreferenceChangeListener(this::onRequireRestart);
            useTor.setOnPreferenceChangeListener(this::onRequireRestart);

            try {
                appVersion.setSummary(getActivity().getPackageManager()
                        .getPackageInfo(getActivity().getPackageName(), 0).versionName);
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "Failed to get app version name");
            }
        }

        @Override
        public void onServiceConnected() {
            if (getActivity() == null)
                return;

            mSyncthingService = ((SyncthingActivity) getActivity()).getService();
            mSyncthingService.registerOnApiChangeListener(this);
            if (mSyncthingService.getApi().isConfigLoaded()) {
                mGui = mSyncthingService.getApi().getGui();
                mOptions = mSyncthingService.getApi().getOptions();
            }
        }

        @Override
        public void onApiChange(SyncthingService.State currentState) {
            boolean syncthingActive = currentState == SyncthingService.State.ACTIVE;
            boolean enableAllPrefs = syncthingActive && mSyncthingService.getApi().isConfigLoaded();
            PreferenceScreen ps = getPreferenceScreen();
            for (int i = 0; i < ps.getPreferenceCount(); i++) {
                Preference p = ps.getPreference(i);
                p.setEnabled(enableAllPrefs || "category_run_conditions".equals(p.getKey()));
            }

            if (!enableAllPrefs)
                return;

            mApi = mSyncthingService.getApi();
            mSyncthingVersion.setSummary(mApi.getVersion());
            mOptions = mApi.getOptions();
            mGui = mApi.getGui();

            Joiner joiner = Joiner.on(", ");
            mDeviceName.setText(mApi.getLocalDevice().name);
            mListenAddresses.setText(joiner.join(mOptions.listenAddresses));
            mMaxRecvKbps.setText(Integer.toString(mOptions.maxRecvKbps));
            mMaxSendKbps.setText(Integer.toString(mOptions.maxSendKbps));
            mNatEnabled.setChecked(mOptions.natEnabled);
            mLocalAnnounceEnabled.setChecked(mOptions.localAnnounceEnabled);
            mGlobalAnnounceEnabled.setChecked(mOptions.globalAnnounceEnabled);
            mRelaysEnabled.setChecked(mOptions.relaysEnabled);
            mGlobalAnnounceServers.setText(joiner.join(mOptions.globalAnnounceServers));
            mAddress.setText(mGui.address);
            mUser.setText(mGui.user);
            mPassword.setText(mGui.password);
            mUrAccepted.setChecked(mOptions.getUsageReportValue() == Options.USAGE_REPORTING_ACCEPTED);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mSyncthingService != null)
                mSyncthingService.unregisterOnApiChangeListener(this);
        }

        private void setPreferenceCategoryChangeListener(
                Preference category, Preference.OnPreferenceChangeListener listener) {
            PreferenceScreen ps = (PreferenceScreen) category;
            for (int i = 0; i < ps.getPreferenceCount(); i++) {
                Preference p = ps.getPreference(i);
                p.setOnPreferenceChangeListener(listener);
            }
        }

        public boolean onSyncthingPreferenceChange(Preference preference, Object o) {
            Splitter splitter = Splitter.on(",").trimResults().omitEmptyStrings();
            switch (preference.getKey()) {
                case "deviceName":
                    Device localDevice = mApi.getLocalDevice();
                    localDevice.name = (String) o;
                    mApi.editDevice(localDevice);
                    break;
                case "listenAddresses":
                    mOptions.listenAddresses = Iterables.toArray(splitter.split((String) o), String.class);
                    break;
                case "maxRecvKbps":           mOptions.maxRecvKbps = (int) o;               break;
                case "maxSendKbps":           mOptions.maxRecvKbps = (int) o;               break;
                case "natEnabled":            mOptions.natEnabled = (boolean) o;            break;
                case "localAnnounceEnabled":  mOptions.localAnnounceEnabled = (boolean) o;  break;
                case "globalAnnounceEnabled": mOptions.globalAnnounceEnabled = (boolean) o; break;
                case "relaysEnabled":         mOptions.relaysEnabled = (boolean) o;         break;
                case "globalAnnounceServers":
                    mOptions.globalAnnounceServers = Iterables.toArray(splitter.split((String) o), String.class);
                    break;
                case "address":               mGui.address = (String) o;                    break;
                case "user":                  mGui.user = (String) o;                       break;
                case "password":              mGui.password = (String) o;                   break;
                case "urAccepted":
                    mOptions.urAccepted = ((boolean) o)
                            ? Options.USAGE_REPORTING_ACCEPTED
                            : Options.USAGE_REPORTING_DENIED;
                    break;
                default: throw new InvalidParameterException();
            }

            mApi.editSettings(mGui, mOptions, getActivity());
            return true;
        }

        public boolean onRequireRestart(Preference preference, Object o) {
            mSyncthingService.getApi().showRestartDialog(getActivity());
            return true;
        }

        /**
         * Sends the updated value to {@link }RestApi}, and sets it as the summary
         * for EditTextPreference.
         */
        @Override
        public boolean onPreferenceChange(Preference preference, Object o) {
            switch (preference.getKey()) {
                case SyncthingService.PREF_ALWAYS_RUN_IN_BACKGROUND:
                    boolean value = (Boolean) o;
                    mAlwaysRunInBackground.setSummary((value)
                            ? R.string.always_run_in_background_enabled
                            : R.string.always_run_in_background_disabled);
                    mSyncOnlyCharging.setEnabled(value);
                    mSyncOnlyWifi.setEnabled(value);
                    mSyncOnlyOnSSIDs.setEnabled(mSyncOnlyWifi.isChecked());
                    // Uncheck items when disabled, so it is clear they have no effect.
                    if (!value) {
                        mSyncOnlyCharging.setChecked(false);
                        mSyncOnlyWifi.setChecked(false);
                    }
                    break;
                case SyncthingService.PREF_SYNC_ONLY_WIFI:
                    mSyncOnlyOnSSIDs.setEnabled((Boolean) o);
                    break;
                case KEY_STTRACE:
                    if (((String) o).matches("[0-9a-z, ]*"))
                        mSyncthingService.getApi().showRestartDialog(getActivity());
                    else {
                        Toast.makeText(getActivity(), R.string.toast_invalid_sttrace, Toast.LENGTH_SHORT)
                                .show();
                        return false;
                    }
                    break;
            }

            return true;
        }

        @Override
        public boolean onPreferenceClick(Preference preference) {
            switch (preference.getKey()) {
                case SyncthingService.PREF_USE_ROOT:
                    if (mUseRoot.isChecked()) {
                        // Only check preference after root was granted.
                        mUseRoot.setChecked(false);
                        new TestRootTask().execute();
                    } else {
                        new Thread(new ChownFilesRunnable()).start();
                        mSyncthingService.getApi().showRestartDialog(getActivity());
                    }
                    return true;
                case KEY_EXPORT_CONFIG:
                    new AlertDialog.Builder(getActivity())
                            .setMessage(R.string.dialog_confirm_export)
                            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                                        mSyncthingService.exportConfig();
                                        Toast.makeText(getActivity(),
                                                getString(R.string.config_export_successful,
                                                SyncthingService.EXPORT_PATH), Toast.LENGTH_LONG).show();
                                    })
                            .setNegativeButton(android.R.string.no, null)
                            .show();
                    return true;
                case KEY_IMPORT_CONFIG:
                    new AlertDialog.Builder(getActivity())
                            .setMessage(R.string.dialog_confirm_import)
                            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                                        if (mSyncthingService.importConfig()) {
                                            Toast.makeText(getActivity(),
                                                    getString(R.string.config_imported_successful),
                                                    Toast.LENGTH_SHORT).show();
                                            // No need to restart, as we shutdown to import the config, and
                                            // then have to start Syncthing again.
                                        } else {
                                            Toast.makeText(getActivity(),
                                                    getString(R.string.config_import_failed,
                                                    SyncthingService.EXPORT_PATH), Toast.LENGTH_LONG).show();
                                        }
                                    })
                            .setNegativeButton(android.R.string.no, null)
                            .show();
                    return true;
                case KEY_STRESET:
                    final Intent intent = new Intent(getActivity(), SyncthingService.class)
                            .setAction(SyncthingService.ACTION_RESET);

                    new AlertDialog.Builder(getActivity())
                            .setTitle(R.string.streset_title)
                            .setMessage(R.string.streset_question)
                            .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                                getActivity().startService(intent);
                                Toast.makeText(getActivity(), R.string.streset_done, Toast.LENGTH_LONG).show();
                            })
                            .setNegativeButton(android.R.string.no, (dialogInterface, i) -> {
                            })
                            .show();
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Enables or disables {@link #mUseRoot} preference depending whether root is available.
         */
        private class TestRootTask extends AsyncTask<Void, Void, Boolean> {
            @Override
            protected Boolean doInBackground(Void... params) {
                return Shell.SU.available();
            }

            @Override
            protected void onPostExecute(Boolean haveRoot) {
                if (haveRoot) {
                    mSyncthingService.getApi().showRestartDialog(getActivity());
                    mUseRoot.setChecked(true);
                } else {
                    Toast.makeText(getActivity(), R.string.toast_root_denied, Toast.LENGTH_SHORT)
                            .show();
                }
            }
        }

        /**
         * Changes the owner of syncthing files so they can be accessed without root.
         */
        private class ChownFilesRunnable implements Runnable {
            @Override
            public void run() {
                String f = getActivity().getExternalFilesDir("config").getAbsolutePath();
                List<String> out = Shell.SU.run("chown -R --reference=" + f + " " + f);
                Log.i(TAG, "Changed owner of syncthing files, output: " + out);
            }
        }

    }
}
