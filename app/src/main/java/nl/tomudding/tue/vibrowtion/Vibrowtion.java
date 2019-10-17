/*
 * Copyright (C) 2019 Tom Udding <t.udding@student.tue.nl>
 *
 * This file contains code provided by the Android Open Source Project;
 * Copyright (C) 2013 The Android Open Source Project.
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

package nl.tomudding.tue.vibrowtion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Vibrowtion extends AppCompatActivity {
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mBluetoothDevice;
    private BluetoothLeScanner mBluetoothLeScanner;
    private BluetoothLeService mBluetoothLeService;
    private BluetoothManager mBluetoothManager;

    private Vibrator mVibrator;

    private boolean buttonEnabled = true;
    private boolean isScanning = false;
    private boolean isConnected = false;

    private String mDeviceAddress;

    private Handler mHandler;
    private FloatingActionButton actionButton;
    private TextView status;

    private ArrayList<Integer> classifications = new ArrayList<>();

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_ENABLE_LOCATION = 2;

    private static final long SCAN_PERIOD = 10000;

    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("12566370-6212-4683-B567-037441918442");
    private static final UUID SERVICE_UUID = UUID.fromString("12566370-6212-4683-A567-037441918442");
    private static final List<ScanFilter> SCAN_FILTER_LIST = new ArrayList<ScanFilter>() {{ add((new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(SERVICE_UUID.toString())).build())); }};

    private static final long PATTERN_FAST[] = {0, 50, 10, 50, 10};
    private static final long PATTERN_SLOW[] = {0, 10, 50, 10, 50};

    private static final String TAG = "Vibrowtion";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mHandler = new Handler();

        status = findViewById(R.id.status);
        actionButton = findViewById(R.id.button);
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!buttonEnabled) {
                    Snackbar.make(view, getResources().getText(R.string.location_disabled), Snackbar.LENGTH_LONG).setAction(getResources().getText(R.string.location_grant), new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            requestLocationPermission();
                        }
                    }).show();
                } else {
                    if (isConnected) {
                        mBluetoothLeService.disconnect();
                        mBluetoothLeService.close();
                    } else {
                        if (isScanning) {
                            actionButton.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_bluetooth_searching, null));
                            status.setText(getResources().getText(R.string.scanner_offline));
                            scanLeDevice(false);
                        } else {
                            actionButton.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_bluetooth_disabled, null));
                            status.setText(getResources().getText(R.string.scanning));
                            scanLeDevice(true);
                        }
                    }
                }
            }
        });

        // Initializes a Bluetooth adapter.
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Check if the device supports BLE.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            buttonEnabled = false;
            return;
        }

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }

        requestLocationPermission();

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Force the screen to stay on.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_ENABLE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // REQUEST_ENABLE_LOCATION granted, "enable" button
                    buttonEnabled = true;
                } else {
                    // REQUEST_ENABLE_LOCATION denied, "disable" button
                    buttonEnabled = false;
                }
                return;
            }

        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

        // Ensures Bluetooth is enabled on the device. If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanLeDevice(false);
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService.disconnect();
        mBluetoothLeService.close();
        mBluetoothLeService = null;
    }

    private boolean requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.location_permission_title)
                        .setMessage(R.string.location_permission_text)
                        .setPositiveButton(R.string.button_continue, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(Vibrowtion.this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_ENABLE_LOCATION);
                            }
                        }).create().show();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_ENABLE_LOCATION);
            }
            return false;
        }
        return true;
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    isScanning = false;
                    actionButton.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_bluetooth_searching, null));
                    status.setText(getResources().getText(R.string.peripheral_not_found));
                    mBluetoothLeScanner.stopScan(mScanCallback);
                }
            }, SCAN_PERIOD);

            isScanning = true;
            mBluetoothLeScanner.startScan(SCAN_FILTER_LIST, new ScanSettings.Builder().setScanMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setMatchMode(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT).setNumOfMatches(1).build(), mScanCallback);
        } else {
            isScanning = false;
            actionButton.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_bluetooth_searching, null));
            status.setText(getResources().getText(R.string.scanner_offline));
            mHandler.removeCallbacksAndMessages(null);
            mBluetoothLeScanner.stopScan(mScanCallback);
        }
    }

    // Device scan callback.
    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.d(TAG, "A device has been discovered!");
            // get the peripheral
            mBluetoothDevice = result.getDevice();
            mDeviceAddress = mBluetoothDevice.getAddress();
            // connect to the peripheral via gatt
            mBluetoothLeService.connect(mDeviceAddress);
            // stop scanning
            mHandler.removeCallbacksAndMessages(null);
            mBluetoothLeScanner.stopScan(mScanCallback);
            // characteristic(s) of peripheral
            Log.d(TAG, mBluetoothLeService.getSupportedGattServices().toArray().toString());
            //fixGattServices(mBluetoothLeService.getSupportedGattServices());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Log.d(TAG, "Multiple devices has been discovered!");
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }

            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                isConnected = true;
                actionButton.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_stop, null));
                status.setText(getResources().getText(R.string.connected));
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                isConnected = false;
                actionButton.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_bluetooth_searching, null));
                status.setText(getResources().getText(R.string.disconnected_gatt));
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                subscribeToCharacteristic(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                runVibrowtion(intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    private void subscribeToCharacteristic(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;

        for (BluetoothGattService gattService : gattServices) {
            if (SERVICE_UUID.equals(gattService.getUuid())) {
                // get the correct characteristic 0x2902
                BluetoothGattCharacteristic mBluetoothGattCharacteristic = gattService.getCharacteristic(CHARACTERISTIC_UUID);
                mBluetoothLeService.setCharacteristicNotification(mBluetoothGattCharacteristic, true);

                // write descriptor
                UUID uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
                BluetoothGattDescriptor mBluetoothGattDescriptor = mBluetoothGattCharacteristic.getDescriptor(uuid);
                mBluetoothGattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothLeService.writeDescriptor(mBluetoothGattDescriptor);
            }
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private void runVibrowtion(byte[] data) {
        int i = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();

        classifications.add(i);
        int size = classifications.size();

        if (size >= 6) {
            if (Collections.frequency(classifications.subList(size - 6, size - 1), 1) >= 4) {
                mVibrator.vibrate(VibrationEffect.createOneShot(500, 255));
            } else if (Collections.frequency(classifications.subList(size - 6, size - 2), 2) >= 4) {
                mVibrator.vibrate(VibrationEffect.createOneShot(250, 127));
            } else {
                // could not be determined (or not moving)
            }
            classifications.clear();
        }
    }
}