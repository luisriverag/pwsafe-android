/*
 * Copyright (©) 2016 Jeff Harris <jefftharris@gmail.com>
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package com.jefftharris.passwdsafe.sync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.jefftharris.passwdsafe.lib.AboutUtils;
import com.jefftharris.passwdsafe.lib.DynamicPermissionMgr;
import com.jefftharris.passwdsafe.lib.PasswdSafeContract;
import com.jefftharris.passwdsafe.lib.PasswdSafeUtil;
import com.jefftharris.passwdsafe.lib.ProviderType;
import com.jefftharris.passwdsafe.lib.view.GuiUtils;
import com.jefftharris.passwdsafe.sync.dropbox.DropboxFilesActivity;
import com.jefftharris.passwdsafe.sync.lib.AccountUpdateTask;
import com.jefftharris.passwdsafe.sync.lib.NewAccountTask;
import com.jefftharris.passwdsafe.sync.lib.Provider;
import com.jefftharris.passwdsafe.sync.onedrive.OnedriveFilesActivity;
import com.jefftharris.passwdsafe.sync.owncloud.OwncloudEditDialog;
import com.jefftharris.passwdsafe.sync.owncloud.OwncloudFilesActivity;
import com.jefftharris.passwdsafe.sync.owncloud.OwncloudProvider;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements LoaderCallbacks<Cursor>, SyncUpdateHandler,
                   AccountUpdateTask.Listener,
                   OwncloudEditDialog.Listener
{
    private static final String TAG = "MainActivity";

    private static final int DROPBOX_LINK_RC = 1;
    private static final int BOX_AUTH_RC = 2;
    private static final int ONEDRIVE_LINK_RC = 3;
    private static final int OWNCLOUD_LINK_RC = 4;
    private static final int PERMISSIONS_RC = 5;
    private static final int APP_SETTINGS_RC = 6;
    private static final int GDRIVE_PLAY_LINK_RC = 7;
    private static final int GDRIVE_PLAY_SERVICES_ERROR_RC = 8;

    private static final int LOADER_PROVIDERS = 0;

    private DynamicPermissionMgr itsPermissionMgr;
    private Uri itsGDrivePlayUri = null;
    private Uri itsDropboxUri = null;
    private boolean itsDropboxPendingAcctLink = false;
    private Uri itsBoxUri = null;
    private Uri itsOnedriveUri = null;
    private boolean itsOnedrivePendingAcctLink = false;
    private Uri itsOwncloudUri = null;
    private int itsOwncloudSyncFreq = 0;

    private NewAccountTask itsNewAccountTask = null;
    private final List<AccountUpdateTask> itsUpdateTasks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.i(TAG, "onCreate");

        OnItemSelectedListener freqSelListener = new OnItemSelectedListener()
        {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id)
            {
                switch (parent.getId()) {
                case R.id.gdrive_interval: {
                    onGdriveFreqChanged(position);
                    break;
                }
                case R.id.dropbox_interval: {
                    onDropboxFreqChanged(position);
                    break;
                }
                case R.id.box_interval: {
                    onBoxFreqChanged(position);
                    break;
                }
                case R.id.onedrive_interval: {
                    onOnedriveFreqChanged(position);
                    break;
                }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent)
            {
            }
        };

        for (int id: new int[]{R.id.box_interval, R.id.dropbox_interval,
                               R.id.gdrive_interval, R.id.onedrive_interval}) {
            Spinner freqSpin = (Spinner)findViewById(id);
            assert freqSpin != null;
            freqSpin.setOnItemSelectedListener(freqSelListener);
        }

        itsPermissionMgr = new DynamicPermissionMgr(
                Manifest.permission.GET_ACCOUNTS, this,
                PERMISSIONS_RC, APP_SETTINGS_RC,
                "com.jefftharris.passwdsafe.sync",
                R.id.reload, R.id.app_settings);
        View noPermGroup = findViewById(R.id.no_permission_group);
        GuiUtils.setVisible(noPermGroup, !itsPermissionMgr.checkPerms());

        // Check the state of Google Play services
        GoogleApiAvailability googleApi = GoogleApiAvailability.getInstance();
        int rc = googleApi.isGooglePlayServicesAvailable(this);
        if (rc != ConnectionResult.SUCCESS) {
            googleApi.showErrorDialogFragment(this, rc,
                                              GDRIVE_PLAY_SERVICES_ERROR_RC);
        }

        updateGdriveAccount(null);
        updateDropboxAccount(null);
        updateBoxAccount(null);
        updateOnedriveAccount(null);
        updateOwncloudAccount(null);
        LoaderManager lm = getSupportLoaderManager();
        lm.initLoader(LOADER_PROVIDERS, null, this);
    }

    /**
     * Destroy all fragments and loaders.
     */
    @Override
    protected void onDestroy()
    {
        for (AccountUpdateTask task: itsUpdateTasks) {
            task.cancel(true);
        }
        itsUpdateTasks.clear();
        super.onDestroy();
    }

    /* (non-Javadoc)
     * @see android.support.v4.app.FragmentActivity#onStart()
     */
    @Override
    protected void onStart()
    {
        super.onStart();
        if (AboutUtils.checkShowNotes(this)) {
            showAbout();
        }
    }

    /* (non-Javadoc)
     * @see android.support.v4.app.FragmentActivity#onPause()
     */
    @Override
    protected void onPause()
    {
        super.onPause();
        SyncApp.get(this).setSyncUpdateHandler(null);
    }

    /* (non-Javadoc)
     * @see android.support.v4.app.FragmentActivity#onResumeFragments()
     */
    @Override
    protected void onResumeFragments()
    {
        super.onResumeFragments();
        if (itsDropboxPendingAcctLink) {
            itsDropboxPendingAcctLink = false;
            itsNewAccountTask = getDbxProvider().finishAccountLink(
                    Activity.RESULT_OK, null, itsDropboxUri);
        } else if (itsOnedrivePendingAcctLink) {
            itsOnedrivePendingAcctLink = false;
            itsNewAccountTask = getOnedriveProvider().finishAccountLink(
                    Activity.RESULT_OK, null, itsOnedriveUri);
        }

        if (itsNewAccountTask != null) {
            itsNewAccountTask.startTask(this, this);
            itsNewAccountTask = null;
        }
        LoaderManager lm = getSupportLoaderManager();
        lm.restartLoader(LOADER_PROVIDERS, null, this);
        SyncApp.get(this).setSyncUpdateHandler(this);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        switch (requestCode) {
        case BOX_AUTH_RC: {
            itsNewAccountTask =
                    getBoxProvider().finishAccountLink(resultCode, data,
                                                       itsBoxUri);
            break;
        }
        case OWNCLOUD_LINK_RC: {
            itsNewAccountTask = getOwncloudProvider().finishAccountLink(
                    resultCode, data, itsOwncloudUri);
            break;
        }
        case GDRIVE_PLAY_LINK_RC: {
            itsNewAccountTask = getGDrivePlayProvider().finishAccountLink(
                    resultCode, data, itsGDrivePlayUri);
            break;
        }
        case GDRIVE_PLAY_SERVICES_ERROR_RC: {
            break;
        }
        default: {
            if (!itsPermissionMgr.handleActivityResult(requestCode)) {
                super.onActivityResult(requestCode, resultCode, data);
            }
            break;
        }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        if (!itsPermissionMgr.handlePermissionsResult(requestCode,
                                                      grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions,
                                             grantResults);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.activity_main, menu);

        for (int id:
                new int[] {R.id.menu_add, R.id.menu_about, R.id.menu_logs}) {
            MenuItem item = menu.findItem(id);
            MenuItemCompat.setShowAsAction(
                    item, MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);
        }
        return true;
    }

    /** Prepare the Screen's standard options menu to be displayed. */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        MenuItem item = menu.findItem(R.id.menu_add);
        item.setEnabled(itsPermissionMgr.hasPerms());

        setProviderMenuEnabled(menu, R.id.menu_add_box, itsBoxUri);
        setProviderMenuEnabled(menu, R.id.menu_add_dropbox, itsDropboxUri);
        setProviderMenuEnabled(menu, R.id.menu_add_google_drive,
                               itsGDrivePlayUri);
        setProviderMenuEnabled(menu, R.id.menu_add_onedrive, itsOnedriveUri);
        setProviderMenuEnabled(menu, R.id.menu_add_owncloud, itsOwncloudUri);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId()) {
        case R.id.menu_about: {
            showAbout();
            return true;
        }
        case R.id.menu_logs: {
            Intent intent = new Intent();
            intent.setClass(this, SyncLogsActivity.class);
            startActivity(intent);
            return true;
        }
        case R.id.menu_add_box: {
            onBoxChoose();
            return true;
        }
        case R.id.menu_add_dropbox: {
            onDropboxChoose();
            return true;
        }
        case R.id.menu_add_google_drive: {
            onGdriveChoose();
            return true;
        }
        case R.id.menu_add_onedrive: {
            onOnedriveChoose();
            return true;
        }
        case R.id.menu_add_owncloud: {
            onOwncloudChoose();
            return true;
        }
        default: {
            return super.onOptionsItemSelected(item);
        }
        }
    }


    /** Button onClick handler to launch PasswdSafe */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onLaunchPasswdSafeClick(View view)
    {
        PasswdSafeUtil.startMainActivity("com.jefftharris.passwdsafe", this);
    }


    /** Handler to choose a Google Drive account */
    private void onGdriveChoose()
    {
        Provider driveProvider = getGDrivePlayProvider();
        try {
            driveProvider.startAccountLink(this, GDRIVE_PLAY_LINK_RC);
        } catch (Exception e) {
            Log.e(TAG, "onGDrivePlayChoose failed", e);
            driveProvider.unlinkAccount();
        }
    }


    /** Button onClick handler to sync a Google Drive account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onGdriveSync(View view)
    {
        Provider driveProvider = getGDrivePlayProvider();
        if (driveProvider.isAccountAuthorized()) {
            driveProvider.requestSync(true);
        } else {
            onGdriveChoose();
        }
    }


    /** Button onClick handler to clear a Google Drive account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onGdriveClear(View view)
    {
        DialogFragment prompt = ClearPromptDlg.newInstance(itsGDrivePlayUri);
        prompt.show(getSupportFragmentManager(), null);
    }


    /** Google Drive sync frequency spinner changed */
    private void onGdriveFreqChanged(int pos)
    {
        ProviderSyncFreqPref freq = ProviderSyncFreqPref.displayValueOf(pos);
        updateSyncFreq(freq, itsGDrivePlayUri);
    }

    /** Handler to choose a Dropbox account */
    private void onDropboxChoose()
    {
        Provider dbxProvider = getDbxProvider();
        try {
            dbxProvider.startAccountLink(this, DROPBOX_LINK_RC);
            itsDropboxPendingAcctLink = true;
        } catch (Exception e) {
            Log.e(TAG, "startDropboxLink failed", e);
            dbxProvider.unlinkAccount();
        }
    }


    /** Button onClick handler to sync a Dropbox account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onDropboxSync(View view)
    {
        Provider dbxProvider = getDbxProvider();
        if (dbxProvider.isAccountAuthorized()) {
            dbxProvider.requestSync(true);
        } else {
            onDropboxChoose();
        }
    }


    /** Button onClick handler to clear a Dropbox account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onDropboxClear(View view)
    {
        DialogFragment prompt = ClearPromptDlg.newInstance(itsDropboxUri);
        prompt.show(getSupportFragmentManager(), null);
    }


    /** Dropbox sync frequency spinner changed */
    private void onDropboxFreqChanged(int pos)
    {
        ProviderSyncFreqPref freq = ProviderSyncFreqPref.displayValueOf(pos);
        updateSyncFreq(freq, itsDropboxUri);
    }


    /** Button onClick handler to select Dropbox files */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onDropboxChooseFiles(View view)
    {
        Intent intent = new Intent();
        intent.putExtra(DropboxFilesActivity.INTENT_PROVIDER_URI,
                        itsDropboxUri);
        intent.setClass(this, DropboxFilesActivity.class);
        startActivity(intent);
    }

    /** Handler to choose a Box account */
    private void onBoxChoose()
    {
        Provider boxProvider = getBoxProvider();
        try {
            boxProvider.startAccountLink(this, BOX_AUTH_RC);
        } catch (Exception e) {
            Log.e(TAG, "Box startAccountLink failed", e);
            boxProvider.unlinkAccount();
        }
    }


    /** Button onClick handler to sync a Box account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onBoxSync(View view)
    {
        Provider boxProvider = getBoxProvider();
        if (boxProvider.isAccountAuthorized()) {
            boxProvider.requestSync(true);
        } else {
            onBoxChoose();
        }
    }


    /** Button onClick handler to clear a Box account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onBoxClear(View view)
    {
        DialogFragment prompt = ClearPromptDlg.newInstance(itsBoxUri);
        prompt.show(getSupportFragmentManager(), null);
    }


    /** Box sync frequency spinner changed */
    private void onBoxFreqChanged(int pos)
    {
        ProviderSyncFreqPref freq = ProviderSyncFreqPref.displayValueOf(pos);
        updateSyncFreq(freq, itsBoxUri);
    }


    /** Handler to choose an OneDrive account */
    private void onOnedriveChoose()
    {
        Provider onedriveProvider = getOnedriveProvider();
        try {
            onedriveProvider.startAccountLink(this, ONEDRIVE_LINK_RC);
            itsOnedrivePendingAcctLink = true;
        } catch (Exception e) {
            Log.e(TAG, "OneDrive startAccountLink failed", e);
            onedriveProvider.unlinkAccount();
        }
    }


    /** Button onClick handler to sync an OneDrive account */
    @SuppressWarnings("UnusedParameters")
    public void onOnedriveSync(View view)
    {
        Provider onedriveProvider = getOnedriveProvider();
        if (onedriveProvider.isAccountAuthorized()) {
            onedriveProvider.requestSync(true);
        } else {
            onOnedriveChoose();
        }
    }


    /** Button onClick handler to clear an OneDrive account */
    @SuppressWarnings("UnusedParameters")
    public void onOnedriveClear(View view)
    {
        DialogFragment prompt = ClearPromptDlg.newInstance(itsOnedriveUri);
        prompt.show(getSupportFragmentManager(), null);
    }


    /** OneDrive sync frequency spinner changed */
    private void onOnedriveFreqChanged(int pos)
    {
        ProviderSyncFreqPref freq = ProviderSyncFreqPref.displayValueOf(pos);
        updateSyncFreq(freq, itsOnedriveUri);
    }


   /** Button onClick handler to select OneDrive files */
    @SuppressWarnings("UnusedParameters")
    public void onOnedriveChooseFiles(View view)
    {
        Intent intent = new Intent();
        intent.putExtra(OnedriveFilesActivity.INTENT_PROVIDER_URI,
                        itsOnedriveUri);
        intent.setClass(this, OnedriveFilesActivity.class);
        startActivity(intent);
    }


    /** Handler to choose an ownCloud account */
    private void onOwncloudChoose()
    {
        Provider owncloudProvider = getOwncloudProvider();
        try {
            owncloudProvider.startAccountLink(this, OWNCLOUD_LINK_RC);
        } catch (Exception e) {
            Log.e(TAG, "ownCloud startAccountLink failed", e);
            owncloudProvider.unlinkAccount();
        }
    }


    /** Button onClick handler to sync an ownCloud account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onOwncloudSync(View view)
    {
        getOwncloudProvider().requestSync(true);
    }


    /** Button onClick handler to edit an ownCloud account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onOwncloudEdit(View view)
    {
        String url = getOwncloudProvider().getUrl().toString();
        DialogFragment dialog =
                OwncloudEditDialog.newInstance(url, itsOwncloudSyncFreq);
        dialog.show(getSupportFragmentManager(), null);
    }


    /** Handle changed settings for ownCloud */
    @Override
    public void handleOwncloudSettingsChanged(String url,
                                              ProviderSyncFreqPref freq)
    {
        getOwncloudProvider().setSettings(url);
        int freqVal = freq.getFreq();
        if (freqVal != itsOwncloudSyncFreq) {
            itsOwncloudSyncFreq = freqVal;
            updateSyncFreq(freq, itsOwncloudUri);
        }
    }


    /** Button onClick handler to clear an ownCloud account */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onOwncloudClear(View view)
    {
        DialogFragment prompt = ClearPromptDlg.newInstance(itsOwncloudUri);
        prompt.show(getSupportFragmentManager(), null);
    }


    /** Button onClick handler to select ownCloud files */
    @SuppressWarnings({"UnusedParameters", "unused"})
    public void onOwncloudChooseFiles(View view)
    {
        Intent intent = new Intent();
        intent.putExtra(OwncloudFilesActivity.INTENT_PROVIDER_URI,
                        itsOwncloudUri);
        intent.setClass(this, OwncloudFilesActivity.class);
        startActivity(intent);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args)
    {
        return new CursorLoader(this, PasswdSafeContract.Providers.CONTENT_URI,
                                PasswdSafeContract.Providers.PROJECTION,
                                null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor)
    {
        boolean hasGdrive = false;
        boolean hasDropbox = false;
        boolean hasBox = false;
        boolean hasOnedrive = false;
        boolean hasOwncloud = false;
        for (boolean more = cursor.moveToFirst(); more;
                more = cursor.moveToNext()) {
            String typeStr = cursor.getString(
                    PasswdSafeContract.Providers.PROJECTION_IDX_TYPE);
            try {
                ProviderType type = ProviderType.valueOf(typeStr);
                switch (type) {
                case GDRIVE: {
                    hasGdrive = true;
                    updateGdriveAccount(cursor);
                    break;
                }
                case DROPBOX: {
                    hasDropbox = true;
                    updateDropboxAccount(cursor);
                    break;
                }
                case BOX: {
                    hasBox = true;
                    updateBoxAccount(cursor);
                    break;
                }
                case ONEDRIVE: {
                    hasOnedrive = true;
                    updateOnedriveAccount(cursor);
                    break;
                }
                case OWNCLOUD: {
                    hasOwncloud = true;
                    updateOwncloudAccount(cursor);
                    break;
                }
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Unknown type: " + typeStr);
            }
        }
        if (!hasGdrive) {
            updateGdriveAccount(null);
        }
        if (!hasDropbox) {
            updateDropboxAccount(null);
        }
        if (!hasBox) {
            updateBoxAccount(null);
        }
        if (!hasOnedrive) {
            updateOnedriveAccount(null);
        }
        if (!hasOwncloud) {
            updateOwncloudAccount(null);
        }

        GuiUtils.setVisible(
                findViewById(R.id.no_accounts_msg),
                !(hasGdrive || hasDropbox || hasBox ||
                  hasOnedrive || hasOwncloud));
        GuiUtils.invalidateOptionsMenu(this);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader)
    {
        updateGdriveAccount(null);
        updateDropboxAccount(null);
        updateBoxAccount(null);
        updateOnedriveAccount(null);
        updateOwncloudAccount(null);
        GuiUtils.setVisible(findViewById(R.id.no_accounts_msg), true);
        GuiUtils.invalidateOptionsMenu(this);
    }

    @Override
    public void updateGDriveState(GDriveState state)
    {
        TextView warning = (TextView)findViewById(R.id.gdrive_sync_warning);
        assert warning != null;
        switch (state) {
        case OK: {
            warning.setVisibility(View.GONE);
            break;
        }
        case AUTH_REQUIRED: {
            warning.setVisibility(View.VISIBLE);
            warning.setText(R.string.gdrive_state_auth_required);
            break;
        }
        case PENDING_AUTH: {
            warning.setVisibility(View.VISIBLE);
            warning.setText(R.string.gdrive_state_pending_auth);
            break;
        }
        }
    }

    /**
     * Notification the task is starting
     */
    @Override
    public final void notifyUpdateStarted(AccountUpdateTask task)
    {
        itsUpdateTasks.add(task);
    }

    /**
     * Notification the task is finished
     */
    @Override
    public final void notifyUpdateFinished(AccountUpdateTask task)
    {
        itsUpdateTasks.remove(task);
    }

    /** Update the UI when the Google Drive account is changed */
    private void updateGdriveAccount(Cursor cursor)
    {
        boolean haveCursor = (cursor != null);
        GuiUtils.setVisible(findViewById(R.id.gdrive_container), haveCursor);
        GuiUtils.setVisible(findViewById(R.id.gdrive_separator), haveCursor);
        if (haveCursor) {
            long id = cursor.getLong(
                    PasswdSafeContract.Providers.PROJECTION_IDX_ID);
            String acct = PasswdSafeContract.Providers.getDisplayName(cursor);
            int freqVal = cursor.getInt(
                    PasswdSafeContract.Providers.PROJECTION_IDX_SYNC_FREQ);
            ProviderSyncFreqPref freq =
                    ProviderSyncFreqPref.freqValueOf(freqVal);
            itsGDrivePlayUri = ContentUris.withAppendedId(
                    PasswdSafeContract.Providers.CONTENT_URI, id);

            TextView acctView = (TextView)findViewById(R.id.gdrive_acct);
            assert acctView != null;
            acctView.setText(acct);

            View freqSpinLabel = findViewById(R.id.gdrive_interval_label);
            assert freqSpinLabel != null;
            Spinner freqSpin = (Spinner)findViewById(R.id.gdrive_interval);
            assert freqSpin != null;
            freqSpin.setSelection(freq.getDisplayIdx());
            freqSpin.setEnabled(true);
            freqSpinLabel.setEnabled(true);
        } else {
            itsGDrivePlayUri = null;
        }
    }

    /** Update the UI when the Dropbox account is changed */
    private void updateDropboxAccount(Cursor cursor)
    {
        boolean haveCursor = (cursor != null);
        GuiUtils.setVisible(findViewById(R.id.dropbox_container), haveCursor);
        GuiUtils.setVisible(findViewById(R.id.dropbox_separator), haveCursor);
        if (haveCursor) {
            long id = cursor.getLong(
                    PasswdSafeContract.Providers.PROJECTION_IDX_ID);
            String acct = PasswdSafeContract.Providers.getDisplayName(cursor);
            int freqVal = cursor.getInt(
                    PasswdSafeContract.Providers.PROJECTION_IDX_SYNC_FREQ);
            ProviderSyncFreqPref freq =
                    ProviderSyncFreqPref.freqValueOf(freqVal);
            itsDropboxUri = ContentUris.withAppendedId(
                    PasswdSafeContract.Providers.CONTENT_URI, id);
            boolean authorized = getDbxProvider().isAccountAuthorized();

            TextView acctView = (TextView)findViewById(R.id.dropbox_acct);
            assert acctView != null;
            acctView.setText(acct);
            View chooseFilesBtn = findViewById(R.id.dropbox_choose_files);
            assert chooseFilesBtn != null;
            chooseFilesBtn.setEnabled(authorized);

            View freqSpinLabel = findViewById(R.id.dropbox_interval_label);
            assert freqSpinLabel != null;
            Spinner freqSpin = (Spinner)findViewById(R.id.dropbox_interval);
            assert freqSpin != null;
            freqSpin.setSelection(freq.getDisplayIdx());
            freqSpin.setEnabled(true);
            freqSpinLabel.setEnabled(true);

            GuiUtils.setVisible(findViewById(R.id.dropbox_acct_unlink),
                                !authorized);
        } else {
            itsDropboxUri = null;
        }
    }

    /** Update the UI when the Box account is changed */
    private void updateBoxAccount(Cursor cursor)
    {
        boolean haveCursor = (cursor != null);
        GuiUtils.setVisible(findViewById(R.id.box_container), haveCursor);
        GuiUtils.setVisible(findViewById(R.id.box_separator), haveCursor);
        if (haveCursor) {
            long id = cursor.getLong(
                    PasswdSafeContract.Providers.PROJECTION_IDX_ID);
            String acct = PasswdSafeContract.Providers.getDisplayName(cursor);
            int freqVal = cursor.getInt(
                    PasswdSafeContract.Providers.PROJECTION_IDX_SYNC_FREQ);
            ProviderSyncFreqPref freq =
                    ProviderSyncFreqPref.freqValueOf(freqVal);
            itsBoxUri = ContentUris.withAppendedId(
                    PasswdSafeContract.Providers.CONTENT_URI, id);
            boolean authorized = getBoxProvider().isAccountAuthorized();

            TextView acctView = (TextView)findViewById(R.id.box_acct);
            assert acctView != null;
            acctView.setText(acct);

            View freqSpinLabel = findViewById(R.id.box_interval_label);
            assert freqSpinLabel != null;
            Spinner freqSpin = (Spinner) findViewById(R.id.box_interval);
            assert freqSpin != null;
            freqSpin.setSelection(freq.getDisplayIdx());
            freqSpin.setEnabled(true);
            freqSpinLabel.setEnabled(true);

            GuiUtils.setVisible(findViewById(R.id.box_acct_unlink),
                                !authorized);
        } else {
            itsBoxUri = null;
        }
    }
    
    /** Update the UI when the OneDrive account is changed */
    private void updateOnedriveAccount(Cursor cursor) 
    {
        boolean haveCursor = (cursor != null);
        GuiUtils.setVisible(findViewById(R.id.onedrive_container), haveCursor);
        GuiUtils.setVisible(findViewById(R.id.onedrive_separator), haveCursor);
        if (haveCursor) {
            long id = cursor.getLong(
                    PasswdSafeContract.Providers.PROJECTION_IDX_ID);
            String acct = PasswdSafeContract.Providers.getDisplayName(cursor);
            int freqVal = cursor.getInt(
                    PasswdSafeContract.Providers.PROJECTION_IDX_SYNC_FREQ);
            ProviderSyncFreqPref freq =
                    ProviderSyncFreqPref.freqValueOf(freqVal);
            itsOnedriveUri = ContentUris.withAppendedId(
                    PasswdSafeContract.Providers.CONTENT_URI, id);

            Provider provider = getOnedriveProvider();
            boolean authorized = provider.isAccountAuthorized();

            TextView acctView = (TextView)findViewById(R.id.onedrive_acct);
            assert acctView != null;
            acctView.setText(acct);

            GuiUtils.setVisible(findViewById(R.id.onedrive_auth_required),
                                !authorized);

            View freqSpinLabel = findViewById(R.id.onedrive_interval_label);
            assert freqSpinLabel != null;
            Spinner freqSpin = (Spinner) findViewById(R.id.onedrive_interval);
            assert freqSpin != null;
            freqSpin.setSelection(freq.getDisplayIdx());

            freqSpin.setEnabled(true);
            freqSpinLabel.setEnabled(true);
        } else {
            itsOnedriveUri = null;
        }
    }

    /** Update the UI when the ownCloud account is changed */
    private void updateOwncloudAccount(Cursor cursor)
    {
        boolean haveCursor = (cursor != null);
        GuiUtils.setVisible(findViewById(R.id.owncloud_container), haveCursor);
        GuiUtils.setVisible(findViewById(R.id.owncloud_separator), haveCursor);
        if (haveCursor) {
            long id = cursor.getLong(
                    PasswdSafeContract.Providers.PROJECTION_IDX_ID);
            String acct = PasswdSafeContract.Providers.getDisplayName(cursor);
            itsOwncloudSyncFreq = cursor.getInt(
                    PasswdSafeContract.Providers.PROJECTION_IDX_SYNC_FREQ);
            itsOwncloudUri = ContentUris.withAppendedId(
                    PasswdSafeContract.Providers.CONTENT_URI, id);

            OwncloudProvider provider = getOwncloudProvider();
            boolean authorized = provider.isAccountAuthorized();

            TextView acctView = (TextView)findViewById(R.id.owncloud_acct);
            assert acctView != null;
            acctView.setText(acct);

            GuiUtils.setVisible(findViewById(R.id.owncloud_auth_required),
                                !authorized);
        } else {
            itsOwncloudUri = null;
        }
    }

    /** Remove an account */
    private void removeAccount(Uri currAcct)
    {
        new AccountUpdateTask(currAcct, getString(R.string.removing_account))
        {
            @Override
            protected void doAccountUpdate(ContentResolver cr)
            {
                if (itsAccountUri != null) {
                    cr.delete(itsAccountUri, null, null);
                }
            }
        }.startTask(this, this);
    }

    /** Update the sync frequency for an account */
    private void updateSyncFreq(final ProviderSyncFreqPref freq, Uri acct)
    {
        new AccountUpdateTask(acct, getString(R.string.updating_account))
        {
            @Override
            protected void doAccountUpdate(ContentResolver cr)
            {
                ContentValues values = new ContentValues();
                values.put(PasswdSafeContract.Providers.COL_SYNC_FREQ,
                           freq.getFreq());
                cr.update(itsAccountUri, values, null, null);
            }
        }.startTask(this, this);
    }

    /** Get the Google Drive provider */
    private Provider getGDrivePlayProvider()
    {
        return ProviderFactory.getProvider(ProviderType.GDRIVE, this);
    }

    /** Get the Dropbox provider */
    private Provider getDbxProvider()
    {
        return ProviderFactory.getProvider(ProviderType.DROPBOX, this);
    }

    /** Get the Box provider */
    private Provider getBoxProvider()
    {
        return ProviderFactory.getProvider(ProviderType.BOX, this);
    }

    /** Get the ownCloud provider */
    private OwncloudProvider getOwncloudProvider()
    {
        return (OwncloudProvider)
                ProviderFactory.getProvider(ProviderType.OWNCLOUD, this);
    }

    /** Get the OneDrive provider */
    private Provider getOnedriveProvider()
    {
        return ProviderFactory.getProvider(ProviderType.ONEDRIVE, this);
    }

    /** Update a menu item based on the presence of a provider */
    private void setProviderMenuEnabled(Menu menu, int id, Uri providerUri)
    {
        MenuItem item = menu.findItem(id);
        item.setEnabled(providerUri == null);
    }

    /**
     * Show the about dialog
     */
    private void showAbout()
    {
        String extraLicenses = GoogleApiAvailability
                .getInstance()
                .getOpenSourceSoftwareLicenseInfo(this);
        AboutDialog dlg = AboutDialog.newInstance(extraLicenses);
        dlg.show(getSupportFragmentManager(), "AboutDialog");
    }

    /** Dialog to prompt when an account is cleared */
    public static class ClearPromptDlg extends DialogFragment
    {
        /** Create an instance of the dialog */
        public static ClearPromptDlg newInstance(Uri currAcct)
        {
            ClearPromptDlg dlg = new ClearPromptDlg();
            Bundle args = new Bundle();
            args.putParcelable("currAcct", currAcct);
            dlg.setArguments(args);
            return dlg;
        }

        /* (non-Javadoc)
         * @see android.support.v4.app.DialogFragment#onCreateDialog(android.os.Bundle)
         */
        @Override
        public @NonNull
        Dialog onCreateDialog(Bundle savedInstanceState)
        {
            Bundle args = getArguments();
            final Uri currAcct = args.getParcelable("currAcct");

            AlertDialog.Builder builder =
                    new AlertDialog.Builder(getActivity());
            builder
            .setMessage(R.string.remove_account)
            .setPositiveButton(android.R.string.yes,
                               new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface dialog, int which)
                {
                    MainActivity act = (MainActivity)getActivity();
                    act.removeAccount(currAcct);
                }
            })
            .setNegativeButton(android.R.string.no, null);
            return builder.create();
        }
    }
}
