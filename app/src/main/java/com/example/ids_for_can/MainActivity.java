package com.example.ids_for_can;

import static android.content.ContentValues.TAG;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.github.pires.obd.commands.MonitorAllCommand;
import com.github.pires.obd.commands.ObdCommand;
import com.github.pires.obd.commands.protocol.EchoOffCommand;
import com.github.pires.obd.commands.protocol.HeadersOnCommand;
import com.github.pires.obd.commands.protocol.LineFeedOnCommand;
import com.github.pires.obd.commands.protocol.ObdWarmStartCommand;
import com.github.pires.obd.commands.protocol.SelectProtocolCommand;
import com.github.pires.obd.commands.protocol.SpacesOnCommand;
import com.github.pires.obd.enums.AvailableCommandNames;
import com.example.ids_for_can.connectivity.ObdConfig;
import com.example.ids_for_can.connectivity.AbstractGatewayService;
import com.example.ids_for_can.connectivity.MockObdGatewayService;
import com.example.ids_for_can.connectivity.ObdCommandJob;
import com.example.ids_for_can.connectivity.ObdGatewayService;
import com.example.ids_for_can.connectivity.ObdProgressListener;
import com.github.pires.obd.enums.ObdProtocols;
import com.google.inject.Inject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import roboguice.RoboGuice;
import roboguice.activity.RoboActivity;
import roboguice.inject.ContentView;
import roboguice.inject.InjectView;

@ContentView(R.layout.main)
public class MainActivity extends RoboActivity implements ObdProgressListener {

    private static final int NO_BLUETOOTH_ID = 0;
    private static final int BLUETOOTH_DISABLED = 1;
    private static final int START_LIVE_DATA = 2;
    private static final int TRAIN_IDS = 3;
    private static final int RETRAIN_IDS = 4;
    private static final int START_IDS = 5;
    private static final int STOP_LIVE_DATA_OR_IDS = 6;
    private static final int START_LOGGING = 7;
    private static final int SETTINGS = 8;
    private static final int QUIT_APPLICATION = 9;
    private static final int TABLE_ROW_MARGIN = 7;
    private static final int REQUEST_ENABLE_BT = 1234;
    private static boolean bluetoothDefaultIsEnable = false;
    private static final int PERMISSIONS_REQUEST_BLUETOOTH = 1;
    private static final int PERMISSIONS_REQUEST_READ_AND_WRITE_EXTERNAL_STORAGE = 2;

    // This variable runs the commands needed to train or start the IDS
    // Without this variable, the app runs the commands needed to fetch diagnostic data
    private static boolean initIDSDone = false;

    // This variable determines if the IDS has been trained on the current vehicle
    public static boolean trainingComplete = false;

    // This variable is a counter for the training data
    public static int trainingCounter = 0;

    // This variable is a threshold for the training data
    // When the trainingCounter reaches this threshold, we have sufficient data to create the matrix
    public static int trainingThreshold = 5000;

    // This variable is a counter for the retraining data
    public static int retrainingCounter = 0;

    // This variable is a threshold for the retraining data
    // When the retrainingCounter reaches this threshold, we want to save and update the matrix
    public static int retrainingThreshold = 500;

    // The IDS is currently training/re-training
    public static boolean IDSTrain = false;
    public static boolean IDSRetrain = false;

    // The IDS is currently running
    public static boolean IDSOn = false;

    // Logging to external storage is enabled
    public static boolean loggingOn = false;

    // Default name is "My Vehicle"
    private String userText = "My Vehicle";

    // Waiting on user input
    public static boolean waitingOnUser = false;

    public static String[] ATMAOrder = null;
    public static boolean[][] profileMatrix = null;

    public static ArrayList<String> anomalyTrace = new ArrayList<>();

    public static int anomalyCounter = 0;
    public static int anomalyThreshold = 5;
    public static int healthyCounter = 0;
    public static int healthyThreshold = 2500;
    public static double anomalyCounterForPercent = 0;
    public static double healthyCounterForPercent = 0;
    public static double minimumHealthyPercent = 0.9;
    public static double minimumTrafficBeforeUpdate = 5000;
    public static int invalidIDAlertCount = 0;
    public static int invalidSequenceAlertCount = 0;
    public static int totalAlertCount = 0;

    private static final int invalid_id_alert = 1;
    private static final int invalid_id_sequence_alert = 2;

    public static boolean USER_ALERTED = false;

    static {
        RoboGuice.setUseAnnotationDatabases(false);
    }

    public Map<String, String> commandResult = new HashMap<>();
    
    @InjectView(R.id.BT_STATUS) TextView btStatusTextView;
    @InjectView(R.id.OBD_STATUS) TextView obdStatusTextView;
    @InjectView(R.id.vehicle_view) LinearLayout vv;
    @InjectView(R.id.data_table) TableLayout tl;
    @Inject SharedPreferences prefs;
    private boolean isServiceBound;
    private AbstractGatewayService service;
    private final Runnable mQueueCommands = new Runnable() {
        public void run() {
            if (service != null && service.isRunning() && service.queueEmpty()) {
                queueCommands();
                commandResult.clear();
            }
            // run again in period defined in preferences
            new Handler().postDelayed(mQueueCommands, ConfigActivity.getObdUpdatePeriod(prefs));
        }
    };

    private boolean preRequisites = true;
    private ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, className.toString() + " service is bound");
            isServiceBound = true;
            service = ((AbstractGatewayService.AbstractGatewayServiceBinder) binder).getService();
            service.setContext(MainActivity.this);
            Log.d(TAG, "CONNECT SERVICE");
            try {
                initIDSDone = false;
                service.startService();
                if (preRequisites)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
            } catch (IOException ioe) {
                Log.e(TAG, "FAILED TO CONNECT SERVICE");
                btStatusTextView.setText(getString(R.string.status_bluetooth_error_connecting));
                doUnbindService();
            }
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        // This method is *only* called when the connection to the service is lost unexpectedly
        // and *not* when the client unbinds (http://developer.android.com/guide/components/bound-services.html)
        // So the isServiceBound attribute should also be set to false when we unbind from the service.
        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, className.toString() + " service is unbound");
            isServiceBound = false;
        }
    };

    public static String LookUpCommand(String txt) {
        for (AvailableCommandNames item : AvailableCommandNames.values()) {
            if (item.getValue().equals(txt)) return item.name();
        }
        return txt;
    }

    public void updateTextView(final TextView view, final String txt) {
        new Handler().post(new Runnable() {
            public void run() {
                view.setText(txt);
            }
        });
    }

    public void stateUpdate(final ObdCommandJob job) {
        final String cmdName = job.getCommand().getName();
        String cmdResult = "";
        String rawCmdResult = "";
        final String cmdID = LookUpCommand(cmdName);

        if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR)) {
            cmdResult = job.getCommand().getResult();
            if (cmdResult != null && isServiceBound && !cmdName.equals("Monitor All")) {
                obdStatusTextView.setText(cmdResult.toLowerCase());
            } else {
                cmdResult = job.getCommand().getFormattedResult();
                if (IDSTrain || IDSRetrain) {
                    obdStatusTextView.setText(getString(R.string.ids_training));
                } else if (IDSOn) {
                    obdStatusTextView.setText(getString(R.string.ids_active));
                } else {
                    obdStatusTextView.setText(getString(R.string.unknown_state));
                }
            }
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.BROKEN_PIPE)) {
            if (isServiceBound)
                stopLiveData();
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED)) {
            cmdResult = getString(R.string.status_obd_no_support);
        } else {
            cmdResult = job.getCommand().getFormattedResult();
            rawCmdResult = job.getCommand().getResult();
            if(isServiceBound)
                obdStatusTextView.setText(getString(R.string.status_obd_data));
        }

        if (vv.findViewWithTag(cmdID) != null) {
            TextView existingTV = (TextView) vv.findViewWithTag(cmdID);
            existingTV.setText(cmdResult);
        } else addTableRow(cmdID, cmdName, cmdResult);
        commandResult.put(cmdID, cmdResult);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setContentView(R.layout.main);

        // Check all required permissions
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_ADMIN) ==
                        PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "BLUETOOTH PERMISSION GRANTED!");
        } else {
            // You can directly ask for the permission.
            // The registered ActivityResultCallback gets the result of this request.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_ADMIN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN},
                    PERMISSIONS_REQUEST_BLUETOOTH);
            Log.d(TAG, "BLUETOOTH PERMISSION REQUESTED!");
        }

        // Check all required permissions
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_ADMIN) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED) {
            final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter != null) {
                bluetoothDefaultIsEnable = btAdapter.isEnabled();
                if (!bluetoothDefaultIsEnable) {
                    btAdapter.enable();
                }
            }
        }

        obdStatusTextView.setText(getString(R.string.status_obd_disconnected));

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_AND_WRITE_EXTERNAL_STORAGE PERMISSION GRANTED!");
        } else {
            // You can directly ask for the permission.
            // The registered ActivityResultCallback gets the result of this request.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSIONS_REQUEST_READ_AND_WRITE_EXTERNAL_STORAGE);
            Log.d(TAG, "READ_AND_WRITE_EXTERNAL_STORAGE PERMISSION REQUESTED!");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            // Check all of the following Bluetooth permissions
            case PERMISSIONS_REQUEST_BLUETOOTH:
                if (grantResults.length > 4 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED
                        && grantResults[2] == PackageManager.PERMISSION_GRANTED
                        && grantResults[3] == PackageManager.PERMISSION_GRANTED
                        && grantResults[4] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "BLUETOOTH GRANTED");
                } else {
                    Log.d(TAG, "BLUETOOTH DENIED");
                }
                break;
            // Check the following storage permissions
            // The logging functionality cannot be enabled without these permissions
            case PERMISSIONS_REQUEST_READ_AND_WRITE_EXTERNAL_STORAGE:
                if (grantResults.length > 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "READ_AND_WRITE_EXTERNAL_STORAGE GRANTED");
                }
                break;
            default:
                Log.d(TAG, "UNEXPECTED SWITCH CASE");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        Log.d(TAG, "Entered onStart...");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "Entered onDestroy...");

        // Reset pertinent variables to initial state
        IDSOn = false;
        IDSTrain = false;
        IDSRetrain = false;
        trainingComplete = false;
        trainingCounter = 0;
        retrainingCounter = 0;
        loggingOn = false;

        USER_ALERTED = false;

        anomalyCounter = 0;
        healthyCounter = 0;
        anomalyCounterForPercent = 0;
        healthyCounterForPercent = 0;
        invalidIDAlertCount = 0;
        invalidSequenceAlertCount = 0;
        totalAlertCount = 0;

        if (isServiceBound) {
            //we don't want to unbind the service
            //we want the IDS to continue receiving and processing data
            doUnbindService();
        }

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isEnabled() && !bluetoothDefaultIsEnable)
            btAdapter.disable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Pausing...");
    }

    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resuming...");

        // get Bluetooth device
        final BluetoothAdapter btAdapter = BluetoothAdapter
                .getDefaultAdapter();

        preRequisites = btAdapter != null && btAdapter.isEnabled();
        if (!preRequisites && prefs.getBoolean(ConfigActivity.ENABLE_BT_KEY, false)) {
            preRequisites = btAdapter != null && btAdapter.enable();
        }

        if (!preRequisites) {
            showDialog(BLUETOOTH_DISABLED);
            btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
        } else {
            btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
        }
    }

    private void updateConfig() {
        startActivity(new Intent(this, ConfigActivity.class));
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, START_LIVE_DATA, 0, getString(R.string.menu_start_live_data));
        menu.add(0, TRAIN_IDS, 0, getString(R.string.train_ids));
        menu.add(0, RETRAIN_IDS, 0, getString(R.string.train_more));
        menu.add(0, START_IDS, 0, getString(R.string.start_ids));
        menu.add(0, STOP_LIVE_DATA_OR_IDS, 0, getString(R.string.stop_live_data_ids));
        menu.add(0, START_LOGGING, 0, getString(R.string.start_logging));
        menu.add(0, SETTINGS, 0, getString(R.string.menu_settings));
        menu.add(0, QUIT_APPLICATION, 0, getString(R.string.quit_application));
        Log.d(TAG, "Creating menu...");
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case START_LIVE_DATA:
                startLiveData();
                return true;
            case TRAIN_IDS:
                Log.d(TAG, "Train IDS");
                IDSTrain = true;
                trainIDS();
                return true;
            case RETRAIN_IDS:
                Log.d(TAG, "Re-train IDS");
                IDSRetrain = true;
                trainIDS();
                return true;
            case START_IDS:
                startIDS();
                return true;
            case STOP_LIVE_DATA_OR_IDS:
                stopLiveData();
                return true;
            case START_LOGGING:
                startLogging();
                return true;
            case SETTINGS:
                updateConfig();
                return true;
            case QUIT_APPLICATION:
                finishAndRemoveTask();
                return true;
            default:
                return false;
        }
    }

    private void startLiveData() {
        Log.d(TAG, "Starting live data...");

        tl.removeAllViews(); // start fresh
        doBindService();

        // start command execution
        new Handler().post(mQueueCommands);
    }

    private void trainIDS() {
        Log.d(TAG, "Training IDS...");
        if (IDSTrain) {
            IDSTrain = true;
            trainingComplete = false;
        } else if (IDSRetrain) {
            IDSRetrain = true;
        }

        trainingCounter = 0;
        retrainingCounter = 0;

        tl.removeAllViews(); //start fresh
        doBindService();

        // start command execution
        new Handler().post(mQueueCommands);
    }

    private void startIDS() {
        Log.d(TAG, "Starting IDS...");
        IDSOn = true;

        tl.removeAllViews(); //start fresh
        doBindService();

        // start command execution
        new Handler().post(mQueueCommands);
    }

    private void stopLiveData() {
        Log.d(TAG, "Stopping live data...");
        initIDSDone = false;
        IDSOn = false;
        IDSTrain = false;
        IDSRetrain = false;
        trainingCounter = 0;
        retrainingCounter = 0;

        USER_ALERTED = false;

        anomalyCounter = 0;
        healthyCounter = 0;
        anomalyCounterForPercent = 0;
        healthyCounterForPercent = 0;
        invalidIDAlertCount = 0;
        invalidSequenceAlertCount = 0;
        totalAlertCount = 0;

        doUnbindService();
    }

    private void startLogging() {
        Log.d(TAG, "Starting logging...");

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_AND_WRITE_EXTERNAL_STORAGE PERMISSION GRANTED!");
        } else {
            // You can directly ask for the permission.
            // The registered ActivityResultCallback gets the result of this request.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSIONS_REQUEST_READ_AND_WRITE_EXTERNAL_STORAGE);
            Log.d(TAG, "READ_AND_WRITE_EXTERNAL_STORAGE PERMISSION REQUESTED!");
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "LOGGING ON!");
            loggingOn = true;
        }
    }

    protected Dialog onCreateDialog(int id) {
        AlertDialog.Builder build = new AlertDialog.Builder(this);
        switch (id) {
            case NO_BLUETOOTH_ID:
                build.setMessage(getString(R.string.text_no_bluetooth_id));
                return build.create();
            case BLUETOOTH_DISABLED:
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                return build.create();
        }
        return null;
    }

    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem startItem = menu.findItem(START_LIVE_DATA);
        MenuItem trainItem = menu.findItem(TRAIN_IDS);
        MenuItem retrainItem = menu.findItem(RETRAIN_IDS);
        MenuItem idsItem = menu.findItem(START_IDS);
        MenuItem stopItem = menu.findItem(STOP_LIVE_DATA_OR_IDS);
        MenuItem loggingItem = menu.findItem(START_LOGGING);
        MenuItem settingsItem = menu.findItem(SETTINGS);

        if (service != null && service.isRunning()) {
            startItem.setEnabled(false);
            trainItem.setEnabled(false);
            retrainItem.setEnabled(false);
            idsItem.setEnabled(false);
            stopItem.setEnabled(true);
            settingsItem.setEnabled(false);
        } else {
            stopItem.setEnabled(false);
            trainItem.setEnabled(true);
            startItem.setEnabled(true);
            if (trainingComplete) {
                retrainItem.setEnabled(true);
                idsItem.setEnabled(true);
            } else {
                retrainItem.setEnabled(false);
                idsItem.setEnabled(false);
            }
            settingsItem.setEnabled(true);
        }

        if (loggingOn) {
            loggingItem.setEnabled(false);
        } else {
            loggingItem.setEnabled(true);
        }

        return true;
    }

    private void addTableRow(String id, String key, String val) {
        TableRow tr = new TableRow(this);
        MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.setMargins(TABLE_ROW_MARGIN, TABLE_ROW_MARGIN, TABLE_ROW_MARGIN,
                TABLE_ROW_MARGIN);
        tr.setLayoutParams(params);

        TextView name = new TextView(this);
        name.setGravity(Gravity.RIGHT);
        name.setText(key + ": ");
        TextView value = new TextView(this);
        value.setGravity(Gravity.LEFT);
        value.setText(val);
        value.setTag(id);
        tr.addView(name);
        tr.addView(value);
        tl.addView(tr, params);
    }

    private void queueCommands() {
        if (isServiceBound) {
            // Live Data Mode
            if (!IDSTrain && !IDSRetrain && !IDSOn) {
                for (ObdCommand Command : ObdConfig.getCommands()) {
                    if (prefs.getBoolean(Command.getName(), true))
                        service.queueJob(new ObdCommandJob(Command));
                }
            // IDS Train, IDS Re-train, or IDS On
            } else if (!waitingOnUser) {
                if (!initIDSDone) {
                    service.queueJob(new ObdCommandJob(new ObdWarmStartCommand()));
                    service.queueJob(new ObdCommandJob(new LineFeedOnCommand()));
                    service.queueJob(new ObdCommandJob(new EchoOffCommand()));
                    service.queueJob(new ObdCommandJob(new SpacesOnCommand()));
                    service.queueJob(new ObdCommandJob(new HeadersOnCommand()));
                    service.queueJob(new ObdCommandJob(new SelectProtocolCommand(ObdProtocols.ISO_15765_4_CAN)));
                    initIDSDone = true;
                }
                service.queueJob(new ObdCommandJob(new MonitorAllCommand()));

                //if (trainingCounter < 100 || trainingCounter % 10000 == 0) {
                    Log.d(TAG, "trainingCounter: " + trainingCounter);
                //}

                Log.d(TAG, "retrainingCounter: " + retrainingCounter);

                // We are in training mode, and we have sufficient data to create the matrix
                if ((IDSTrain && trainingCounter >= trainingThreshold) || (IDSRetrain && retrainingCounter >= retrainingThreshold)) {
                    createMatrix();
                }

                // We are in monitoring mode, and we have sufficient data to compare against the matrix
                if (IDSOn && ObdCommand.currentIDs.size() > 10) {
                    idsDetect();
                }
            }
        }
    }

    private void doBindService() {
        if (!isServiceBound) {
            Log.d(TAG, "Binding OBD service...");
            if (preRequisites) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_connecting));
                Intent serviceIntent = new Intent(this, ObdGatewayService.class);
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            } else {
                btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
                Intent serviceIntent = new Intent(this, MockObdGatewayService.class);
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            }
        }
    }

    private void doUnbindService() {
        if (isServiceBound) {
            if (service.isRunning()) {
                service.stopService();
                if (preRequisites)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
            }
            Log.d(TAG, "Unbinding OBD service...");
            unbindService(serviceConn);
            isServiceBound = false;
            obdStatusTextView.setText(getString(R.string.status_obd_disconnected));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
            } else {
                Toast.makeText(this, R.string.text_bluetooth_disabled, Toast.LENGTH_LONG).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void createMatrix() {
        // Create the matrix/profile for this vehicle, which enables the IDS to function

        // HashSet removes duplicates
        // then back to ArrayList
        // then to primitive Array (for performance)
        // then sort (for performance)

        HashSet<String> ATMASet = new HashSet<>(ObdCommand.ATMATrace);

        // Add the previous ATMAOrder (if it exists) to the set, so that we can check equivalence later
        // If there is previous ATMAOrder, then nothing will be added
        if (ATMAOrder != null) {
            Collections.addAll(ATMASet, ATMAOrder);
        }
        ArrayList<String> uniqueATMA = new ArrayList(ATMASet);
        String[] tempOrder = uniqueATMA.toArray(new String[uniqueATMA.size()]);
        Arrays.sort(tempOrder);

        // If we are re-training the IDS (adding more training data)
        // then we do not want to destroy the old matrix
        if (!IDSRetrain) {
            Log.d(TAG, "Create the ATMAOrder and profileMatrix from scratch.");
            ATMAOrder = uniqueATMA.toArray(new String[uniqueATMA.size()]);
            Arrays.sort(ATMAOrder);

            profileMatrix = new boolean[ATMAOrder.length][ATMAOrder.length];
        }

        // This is an example of the profileMatrix
        // "ATMAOrder" is the order for both the rows and the columns
        // the row is the previous ID
        // the column is any subsequent ID that can follow the previous ID in attack-free traffic

        //    0  1  2  3
        // 0  f  f  f  t
        // 1  f  f  t  f
        // 2  f  t  f  f
        // 3  t  f  f  f

        // TODO: resizeMatrix()

        // if ATMAOrder.length is not equal to tempOrder.length,
        // then we know that tempOrder must contain IDs that are not in ATMAOrder or the profileMatrix
        // because we added ATMAOrder to tempOrder (so if there was nothing new, they would match)
        // We need to call a function to build an updated matrix, which will be larger than the original
        if (ATMAOrder != null && ATMAOrder.length != tempOrder.length) {
            Log.d(TAG, "ATMAOrder / tempOrder");
            Log.d(TAG, String.valueOf(ATMAOrder.length));
            Log.d(TAG, String.valueOf(tempOrder.length));
            resizeMatrix();
        }

        boolean checkNext = false;

        for (int i = 0; i < ATMAOrder.length; i++) {
            String currentId = ATMAOrder[i];

            for (String id : ObdCommand.ATMATrace) {
                if (checkNext) {
                    // This ID was preceded by the current ID,
                    // meaning it is a valid transition and should be changed to true

                    // We know "i" is the row because we are iterating by ATMAOrder
                    // and we can find "j" for the column using binarySearch, since we sorted the array
                    int j = Arrays.binarySearch(ATMAOrder, id);
                    if (j >= 0) {
                        profileMatrix[i][j] = true;
                    } else {
                        Log.d(TAG, "This id is not found in the ATMAOrder: " + id);
                    }
                }

                if (currentId.equals(id)) {
                    checkNext = true;
                } else {
                    checkNext = false;
                }
            }
        }

        ObdCommand.ATMATrace = new ArrayList<>();

        // BEGIN -- PRINTING FOR VALIDATION / DEBUGGING
        // **
        Log.d(TAG,"ATMAOrder.length -- " + ATMAOrder.length);
        Log.d(TAG,"profileMatrix.length -- " + profileMatrix.length + ", profileMatrix.width -- " + profileMatrix[0].length);

        System.out.println("in createMatrix() -- ATMAOrder");
        System.out.printf("%-10s", "");
        for (int i = 0; i < ATMAOrder.length; i++) {
            System.out.printf("%-10s", ATMAOrder[i]);
        }
        System.out.println();

        System.out.println("in createMatrix() -- profileMatrix");
        for (int i = 0; i < ATMAOrder.length; i++) {
            System.out.printf("%-10s", ATMAOrder[i]);
            for (int j = 0; j < ATMAOrder.length; j++) {
                System.out.printf("%-10s", profileMatrix[i][j]);
            }
            System.out.println();
        }
        // **
        // END -- PRINTING FOR VALIDATION / DEBUGGING

        // Now that we have a matrix/profile for this vehicle, we need to save this vehicle as an option in preferences
        Log.d(TAG, "Saving the vehicle in preferences...");

        if (!IDSRetrain) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter a nickname for this vehicle: ");
            EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            builder.setView(input);

            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    userText = input.getText().toString();
                    try {
                        appendToSharedPreferences();
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });
            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                    try {
                        appendToSharedPreferences();
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });

            builder.show();
            waitingOnUser = true;
        } else {
            try {
                updateSharedPreferences();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void appendToSharedPreferences() throws JSONException {
        Log.d(TAG, "Appending to SharedPreferences...");

        SharedPreferences vehiclePreference = getApplicationContext().getSharedPreferences("VEHICLE_PREFERENCE", MODE_MULTI_PROCESS);

        // Retrieve the HashSet of all vehicles
        HashSet<String> all_vehicles = new HashSet<>();
        if (vehiclePreference.contains("ALL_VEHICLES")) {
            all_vehicles = new HashSet<>(vehiclePreference.getStringSet("ALL_VEHICLES", new HashSet<>()));
        }

        // Retrieve the string that represents the "profiles" JSON object
        String jsonString = null;
        if (vehiclePreference.contains("PROFILES")) {
            jsonString = vehiclePreference.getString("PROFILES", new String());
        }

        JSONArray profiles = null;
        if (jsonString != null) {
            profiles = new JSONArray(jsonString);
        }

        if (profiles == null) {
            profiles = new JSONArray();
        }

        // We need to enforce unique names (we have a HashSet).
        // So if the name exists in the HashSet, we append a number.
        // If the name still exists in the HashSet, we increment the number and append.
        // On and on, until we find a name that is not in the HashSet.

        if (userText.isEmpty()) {
            userText = "My Vehicle";
        }

        String newUserText = userText;
        int counter = 1;
        while (all_vehicles.contains(newUserText)) {
            newUserText = userText + " (" + counter + ")";
            counter++;
        }

        all_vehicles.add(newUserText);
        String selected_vehicle = newUserText;

        // Create the JSON for the new vehicle matrix/profile
        JSONObject newProfile = new JSONObject();
        newProfile.put("profileName", newUserText);

        JSONArray orderArray = new JSONArray(ATMAOrder);
        //JSONArray orderArray = new JSONArray(Arrays.asList(ATMAOrder));
        newProfile.put("order", orderArray);

        JSONArray parentArray = new JSONArray();
        // loop by row, then by column
        for (int i = 0;  i < profileMatrix.length; i++){
            JSONArray childArray = new JSONArray();
            for (int j = 0; j < profileMatrix[i].length; j++){
                childArray.put(profileMatrix[i][j]);
            }
            parentArray.put(childArray);
        }

        newProfile.put("matrix", parentArray);

        // Add the new vehicle matrix/profile to the "profiles" JSON object
        profiles.put(newProfile);
        String updatedJSONData = profiles.toString();

        SharedPreferences.Editor editor = vehiclePreference.edit();
        //editor.clear();
        editor.putStringSet("ALL_VEHICLES", all_vehicles);
        editor.putString("SELECTED_VEHICLE", selected_vehicle);
        editor.putString("PROFILES", updatedJSONData);
        boolean commitResult = editor.commit();

        // START - VALIDATION CODE
        // **
        HashSet<String> resultHashSet = new HashSet<>(vehiclePreference.getStringSet("ALL_VEHICLES", new HashSet<>()));
        Log.d(TAG, "resultHashSet: " + resultHashSet);

        String resultString = vehiclePreference.getString("SELECTED_VEHICLE", new String());
        Log.d(TAG, "resultString: " + resultString);

        String resultJSON = vehiclePreference.getString("PROFILES", new String());
        Log.d(TAG, "resultJSON: " + resultJSON);

        JSONArray storedJSON = new JSONArray(resultJSON);

        JSONObject obj = null;
        for (int i = 0; i < storedJSON.length(); i++) {
            JSONObject temp_obj = storedJSON.getJSONObject(i);
            if (temp_obj.get("profileName").equals(selected_vehicle)) {
                obj = temp_obj;
                break;
            }
        }

        String[] recoveredOrder = null;
        boolean[][] recoveredMatrix = null;

        JSONArray orderJSON = null;
        JSONArray matrixParent = null;

        if (obj != null) {
            // If the array exists, it should be at least 1
            orderJSON = obj.getJSONArray("order");
            recoveredOrder = new String[orderJSON.length()];

            for (int i = 0; i < orderJSON.length(); i++) {
                recoveredOrder[i] = orderJSON.getString(i);
            }

            // If the matrix exists, it should be at least 1 x 1
            matrixParent = obj.getJSONArray("matrix");
            int rows = matrixParent.length();
            int cols = matrixParent.getJSONArray(0).length();
            recoveredMatrix = new boolean[rows][cols];

            for (int i = 0; i < matrixParent.length(); i++) {
                JSONArray matrixChild = matrixParent.getJSONArray(i);
                for (int j = 0; j < matrixChild.length(); j++) {
                    recoveredMatrix[i][j] = matrixChild.getBoolean(j);
                }
            }
        }

        if (recoveredOrder != null) {
            Log.d(TAG, "Printing recoveredOrder...");
            Log.d(TAG, Arrays.toString(recoveredOrder));
        }

        if (recoveredMatrix != null) {
            Log.d(TAG, "Printing recoveredMatrix...");
            for (boolean[] arr : recoveredMatrix) {
                Log.d(TAG, Arrays.toString(arr));
            }
        }
        // **
        // END - VALIDATION CODE

        Log.d(TAG, "Training complete, starting IDS...");

        // If we are training / appending, then we started with a new matrix, not a re-train matrix
        trainingComplete = true;
        IDSTrain = false;
        IDSRetrain = false;
        IDSOn = true;
        waitingOnUser = false;
    }

    public void updateSharedPreferences() throws JSONException {
        Log.d(TAG, "Updating SharedPreferences...");

        SharedPreferences vehiclePreference = getApplicationContext().getSharedPreferences("VEHICLE_PREFERENCE", MODE_MULTI_PROCESS);

        // Retrieve the HashSet of all vehicles
        HashSet<String> all_vehicles = new HashSet<>();
        if (vehiclePreference.contains("ALL_VEHICLES")) {
            all_vehicles = new HashSet<>(vehiclePreference.getStringSet("ALL_VEHICLES", new HashSet<>()));
        }

        // Retrieve the String of the selected vehicle
        String selected_vehicle = null;
        if (vehiclePreference.contains("SELECTED_VEHICLE")) {
            selected_vehicle = vehiclePreference.getString("SELECTED_VEHICLE", new String());
        }

        // Retrieve the string that represents the "profiles" JSON object
        String jsonString = null;
        if (vehiclePreference.contains("PROFILES")) {
            jsonString = vehiclePreference.getString("PROFILES", new String());
        }

        JSONArray profiles = null;
        if (jsonString != null) {
            profiles = new JSONArray(jsonString);
        }

        if (profiles == null) {
            // If we don't have a pre-existing matrix/profile,
            // then we need to add to the shared preferences
            // because there is no existing matrix/profile to update
            appendToSharedPreferences();
        }

        JSONObject profileToUpdate = null;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject temp_obj = profiles.getJSONObject(i);
            if (temp_obj.get("profileName").equals(selected_vehicle)) {
                profileToUpdate = temp_obj;
                break;
            }
        }

        if (profileToUpdate == null) {
            // If we don't have a pre-existing matrix/profile,
            // then we need to add to the shared preferences
            // because there is no existing matrix/profile to update
            appendToSharedPreferences();
        }

        JSONArray orderArray = new JSONArray(ATMAOrder);
        //JSONArray orderArray = new JSONArray(Arrays.asList(ATMAOrder));
        profileToUpdate.put("order", orderArray);

        JSONArray parentArray = new JSONArray();
        // loop by row, then by column
        for (int i = 0;  i < profileMatrix.length; i++){
            JSONArray childArray = new JSONArray();
            for (int j = 0; j < profileMatrix[i].length; j++){
                childArray.put(profileMatrix[i][j]);
            }
            parentArray.put(childArray);
        }

        profileToUpdate.put("matrix", parentArray);

        String updatedJSONData = profiles.toString();

        SharedPreferences.Editor editor = vehiclePreference.edit();
        //editor.clear();
        editor.putStringSet("ALL_VEHICLES", all_vehicles);
        editor.putString("SELECTED_VEHICLE", selected_vehicle);
        editor.putString("PROFILES", updatedJSONData);
        boolean commitResult = editor.commit();

        // START - VALIDATION CODE
        // **
        HashSet<String> resultHashSet = new HashSet<>(vehiclePreference.getStringSet("ALL_VEHICLES", new HashSet<>()));
        Log.d(TAG, "resultHashSet: " + resultHashSet);

        String resultString = vehiclePreference.getString("SELECTED_VEHICLE", new String());
        Log.d(TAG, "resultString: " + resultString);

        String resultJSON = vehiclePreference.getString("PROFILES", new String());
        Log.d(TAG, "resultJSON: " + resultJSON);

        JSONArray storedJSON = new JSONArray(resultJSON);

        JSONObject obj = null;
        for (int i = 0; i < storedJSON.length(); i++) {
            JSONObject temp_obj = storedJSON.getJSONObject(i);
            if (temp_obj.get("profileName").equals(selected_vehicle)) {
                obj = temp_obj;
                break;
            }
        }

        String[] recoveredOrder = null;
        boolean[][] recoveredMatrix = null;

        JSONArray orderJSON = null;
        JSONArray matrixParent = null;

        if (obj != null) {
            // If the array exists, it should be at least 1
            orderJSON = obj.getJSONArray("order");
            recoveredOrder = new String[orderJSON.length()];

            for (int i = 0; i < orderJSON.length(); i++) {
                recoveredOrder[i] = orderJSON.getString(i);
            }

            // If the matrix exists, it should be at least 1 x 1
            matrixParent = obj.getJSONArray("matrix");
            int rows = matrixParent.length();
            int cols = matrixParent.getJSONArray(0).length();
            recoveredMatrix = new boolean[rows][cols];

            for (int i = 0; i < matrixParent.length(); i++) {
                JSONArray matrixChild = matrixParent.getJSONArray(i);
                for (int j = 0; j < matrixChild.length(); j++) {
                    recoveredMatrix[i][j] = matrixChild.getBoolean(j);
                }
            }
        }

        if (recoveredOrder != null) {
            Log.d(TAG, "Printing recoveredOrder...");
            Log.d(TAG, Arrays.toString(recoveredOrder));
        }

        if (recoveredMatrix != null) {
            Log.d(TAG, "Printing recoveredMatrix...");
            for (boolean[] arr : recoveredMatrix) {
                Log.d(TAG, Arrays.toString(arr));
            }
        }
        // **
        // END - VALIDATION CODE

        // If we are re-training / updating, then we want to continue the re-training process
        // without switching over to IDS mode
        // We want the user to be able to re-train as long as needed to reduce false positives
        // The user can manually turn off live data when done, then switch to IDS mode
        Log.d(TAG, "Re-training data saved, repeating...");

        // Restart the retraining counter, so that we aren't saving and updating too often
        retrainingCounter = 0;
        trainingComplete = true;
        waitingOnUser = false;
    }

    public void idsDetect() {
        // Use the matrix/profile to check current traffic,
        // update false positives,
        // and raise alerts

        if (ObdCommand.currentIDs.isEmpty()) {
            // No data received; no alert
            return;
        }

        // We need to check each pair of adjacent IDs in currentIDs
        // if pair (i, i + 1) is a valid transition (true), we do nothing
        // if pair (i, i + 1) is not a valid transition (false), we update the anomaly counter
        // When the anomaly counter reaches the anomaly threshold, we raise an alert

        for (int i = 0; i < ObdCommand.currentIDs.size() - 1; i++) {
            String prevID = ObdCommand.currentIDs.get(i);
            String nextID = ObdCommand.currentIDs.get(i + 1);

            int row = Arrays.binarySearch(ATMAOrder, prevID);
            int col = Arrays.binarySearch(ATMAOrder, nextID);
            if (row < 0) {
                Log.d(TAG, "This is an anomaly: This ID is not valid");
                Log.d(TAG, "prevID: " + prevID);

                // Given the size of our trace, we would never expect a previously unknown ECU to start transmitting
                // As such, we expect an unknown identifier to indicate an attack
                sendNotification(invalid_id_alert);
            } else if (col < 0) {
                Log.d(TAG, "This is an anomaly: This ID is not valid");
                Log.d(TAG, "nextID: " + nextID);

                // Given the size of our trace, we would never expect a previously unknown ECU to start transmitting
                // As such, we expect an unknown identifier to indicate an attack
                sendNotification(invalid_id_alert);
            } else if (!profileMatrix[row][col]) {
                Log.d(TAG, "This is an anomaly: This sequence is not valid");
                Log.d(TAG, "prevID: " + prevID + ", nextID: " + nextID);
                anomalyTrace.add(prevID);
                anomalyTrace.add(nextID);
                anomalyCounter++;
                anomalyCounterForPercent++;
            } else {
                healthyCounter++;
                healthyCounterForPercent++;
            }
        }

        if (anomalyCounter >= anomalyThreshold) {
            sendNotification(invalid_id_sequence_alert);
        }

        // If we have an extended period of healthy traffic,
        // then previous suspicious traffic may have been false positives
        // and we should update the matrix
        // so that we do not see the same false positives
        Log.d(TAG, "healthyCounter: " + healthyCounter);
        if (healthyCounter >= healthyThreshold) {
            Log.d(TAG, "healthyThreshold reached, updating matrix...");

            // We are going to perform the matrix update, we need to reset the healthyCounter
            healthyCounter = 0;
            updateMatrix();
        }

        // If we have mostly healthy traffic and very few anomalies
        // then the anomalies may have been false positives, and we can update the matrix accordingly
        // We don't want to update too often, so we will check when totalTraffic reaches totalTrafficThreshold
        double totalTraffic = anomalyCounterForPercent + healthyCounterForPercent;
        double percentHealthyTraffic = healthyCounterForPercent / totalTraffic;
        Log.d(TAG, "totalTraffic: " + totalTraffic);
        Log.d(TAG, "percentHealthyTraffic: " + percentHealthyTraffic);
        if (totalTraffic > minimumTrafficBeforeUpdate && percentHealthyTraffic > minimumHealthyPercent) {
            Log.d(TAG, "percentHealthyTraffic reached, updating matrix...");
            anomalyCounterForPercent = 0;
            healthyCounterForPercent = 0;
            updateMatrix();
        }

        ObdCommand.currentIDs = new ArrayList<>();
    }

    public void updateMatrix() {
        // If we see very few anomalies over a significant period,
        // then the "anomalies" are probably false positives
        // We should record them so that we can update the matrix if have not hit the anomaly threshold
        // (We do not think the "anomalies" were part of an attack--we think they were false positives)

        // We need to iterate by pairs, because each pair is part of the trace,
        // but it is not associated with the next pair
        for (int i = 0; i < anomalyTrace.size() - 1; i += 2) {
            String prevId = anomalyTrace.get(i);
            int row = Arrays.binarySearch(ATMAOrder, prevId);

            String nextId = anomalyTrace.get(i + 1);
            int col = Arrays.binarySearch(ATMAOrder, nextId);

            if (row >= 0 && col >= 0) {
                profileMatrix[row][col] = true;
            } else {
                resizeMatrix();
            }
        }

        anomalyTrace = new ArrayList<>();
        anomalyCounter = 0;

        try {
            updateSharedPreferences();
        } catch (JSONException jsonException) {
            jsonException.printStackTrace();
        }
    }

    public void resizeMatrix() {
        // Create newATMAOrder containing the new elements
        HashSet<String> ATMASet = new HashSet<>(ObdCommand.ATMATrace);
        // Include the previous ATMAOrder
        Collections.addAll(ATMASet, ATMAOrder);
        ArrayList<String> uniqueATMA = new ArrayList(ATMASet);
        String[] newATMAOrder = uniqueATMA.toArray(new String[uniqueATMA.size()]);
        Arrays.sort(newATMAOrder);

        // create newProfileMatrix sized to fit the new elements
        boolean[][] newProfileMatrix = new boolean[newATMAOrder.length][newATMAOrder.length];

        // Iterate over the old ATMAOrder (rows of the profileMatrix), then by the columns of the profileMatrix
        // Find the index of the old ATMAOrder's row in the new ATMAOrder
        // If profileMatrix[i][j] is false, then we ignore it (it is initialized to false anyway)
        // If profileMatrix[i][j] is true, then we need to find the old ATMAOrder's column in the new ATMAOrder
        for (int i = 0; i < ATMAOrder.length; i++) {
            int newRow = Arrays.binarySearch(newATMAOrder, ATMAOrder[i]);

            for (int j = 0; j < ATMAOrder.length; j++) {
                if (profileMatrix[i][j]) {
                    int newCol = Arrays.binarySearch(newATMAOrder, ATMAOrder[j]);
                    newProfileMatrix[newRow][newCol] = true;
                }
            }
        }

        ATMAOrder = newATMAOrder;
        profileMatrix = newProfileMatrix;

        for (int i = 0; i < ObdCommand.ATMATrace.size() - 1; i += 2) {
            String prevId = ObdCommand.ATMATrace.get(i);
            int row = Arrays.binarySearch(ATMAOrder, prevId);

            String nextId = ObdCommand.ATMATrace.get(i + 1);
            int col = Arrays.binarySearch(ATMAOrder, nextId);

            if (row >= 0 && col >= 0) {
                profileMatrix[row][col] = true;
            } else {
                System.out.println("Error resizing matrix. The 'row' is " + row + ", and the 'col' is " + col);
            }
        }

        // BEGIN -- PRINTING FOR VALIDATION / DEBUGGING
        // **
        System.out.println("RESIZE MATRIX");
        System.out.println("ATMAOrder.length -- " + ATMAOrder.length);
        System.out.println("profileMatrix.length -- " + profileMatrix.length + ", profileMatrix.width -- " + profileMatrix[0].length);

        System.out.println("in createMatrix() -- ATMAOrder");
        System.out.printf("%-10s", "");
        for (int i = 0; i < ATMAOrder.length; i++) {
            System.out.printf("%-10s", ATMAOrder[i]);
        }
        System.out.println();

        System.out.println("in createMatrix() -- profileMatrix");
        for (int i = 0; i < ATMAOrder.length; i++) {
            System.out.printf("%-10s", ATMAOrder[i]);
            for (int j = 0; j < ATMAOrder.length; j++) {
                System.out.printf("%-10s", profileMatrix[i][j]);
            }
            System.out.println();
        }
        // **
        // END -- PRINTING FOR VALIDATION / DEBUGGING

        // Clear ATMATrace, so that we can use it for resizeMatrix() again
        ObdCommand.ATMATrace = new ArrayList<>();

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void sendNotification(int alert_type) {
        Log.d(TAG, "Sending notification...");

        String alert_type_content = "Suspicious traffic has been detected, indicative of a potential attack.";

        switch (alert_type) {
            case invalid_id_alert:
                alert_type_content = "Invalid messages have been detected. This may indicate a bus error or an attack.";
                invalidIDAlertCount++;
                break;
            case invalid_id_sequence_alert:
                alert_type_content = "Unusual patterns of messages have been detected. This may be the result of unusual activity, or it may indicate an attack.";
                invalidSequenceAlertCount++;
                break;
            default:
                alert_type_content = "Unknown.";
        }

        Log.d(TAG, "invaldIDAlertCount: " + invalidIDAlertCount);
        Log.d(TAG, "invalidSequenceAlertCount: " + invalidSequenceAlertCount);

        totalAlertCount++;
        Log.d(TAG, "totalAlertCount: " + totalAlertCount);

        if (USER_ALERTED) {
            Log.d(TAG, "User has been notified; no new notification will be sent.");
            return;
        }

        anomalyCounter = 0;

        // We've encountered suspicious traffic, so we need to reset the healthyCounter
        healthyCounter = 0;

        // We've encountered suspicious traffic, so we need to remove the anomalies
        // because we think they are suspicious traffic, not false positives
        anomalyTrace = new ArrayList<>();

        // Get an instance of NotificationManager
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.scribble)
                .setContentTitle("ALERT: Potential attack detected!")
                .setContentText(alert_type_content)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(alert_type_content))
                        //.bigText("Detail of the suspicious traffic and possibility of an an attack."))
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        // Gets an instance of the NotificationManager service

        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Creating notification channel...");
            CharSequence name = "IDS ALERTS";
            String description = "Alerts from the intrusion detection system (IDS)";
            NotificationChannel channel = new NotificationChannel("001", name, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            mNotificationManager.createNotificationChannel(channel);
            mBuilder.setChannelId("001");
        }

        // When you issue multiple notifications about the same type of event,
        // it’s best practice for your app to try to update an existing notification
        // with this new information, rather than immediately creating a new notification.
        // If you want to update this notification at a later date, you need to assign it an ID.
        // You can then use this ID whenever you issue a subsequent notification.
        // If the previous notification is still visible, the system will update this existing notification,
        // rather than create a new one. In this example, the notification’s ID is 001

        mNotificationManager.notify(001, mBuilder.build());
        Log.d(TAG, "Notification sent...");

        USER_ALERTED = true;
    }
}