package com.mikheiko.dmitry.blespeedtest;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.Toast;

import com.mikheiko.dmitry.apptools.PermissionHelper;
import com.mikheiko.dmitry.ble_tools.BLeSerialPortService;

import java.lang.reflect.Array;
import java.security.Guard;
import java.util.Arrays;
import java.util.List;

import de.nitri.gauge.Gauge;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

public class MainActivity extends AppCompatActivity
                          implements BLeSerialPortService.Callback{

    private static Context              AppContext;
    private static Activity             AppActivity;
    private BLeSerialPortService        serialPort;
    private BluetoothDevice             device;
    private PermissionHelper            mPermissionHelper;
    private final int                   REQUEST_ENABLE_BT   = 2;
    private final int                   REQUEST_DEVICE      = 3;
    private final int                   REQUEST_ACCESS_FINE_LOCATION =1;

    private Thread                      testSpeedThread = null;
    private byte[]                      testData;
    private int                         testDataSize = 20;
    private int                         testDataTxCount = 0;

    private Gauge                       gaugeSpeed;
    private Gauge                       gaugeErrors;
    private Gauge                       gaugeRSSI;
    private Button                      scanButton;
    private Button                      testButton;
    private Switch                      switchRxEnable;
    private Switch                      switchTxEnable;

    private Handler                     scanButtonTextHandler;
    private Handler                     testButtonTextHandler;
    private Handler                     actionBarTitleHandler;
    private Handler                     actionBarSubTitleHandler;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContext = this;
        AppActivity = this;
        setContentView(R.layout.activity_main);
        gaugeSpeed = findViewById(R.id.gauge1);
        gaugeErrors = findViewById(R.id.gauge2);
        gaugeRSSI = findViewById(R.id.gauge3);
        scanButton = findViewById(R.id.button_scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(serialPort.isConnected()){
                    if(testSpeedThread != null && testSpeedThread.isAlive())
                    {
                        testSpeedThread.interrupt();
                        testButtonText(getString(R.string.str_en_start));
                    }
                    serialPort.disconnect();
                    scanButton.setText(R.string.str_en_scan);
                    device = null;
                    getSupportActionBar().setSubtitle(R.string.str_en_disconnected);
                }
                else {
                    Intent intent = new Intent(AppContext, DeviceListActivity.class);
                    startActivityForResult(intent, REQUEST_DEVICE);
                }
            }
        });
        testButton = findViewById(R.id.button_test);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Button b = (Button)v;
                //if(serialPort != null && serialPort.isConnected()) serialPort.send("Hello");
                if(testSpeedThread == null)
                {
                    if(serialPort != null && serialPort.isConnected()) {
                        testSpeedThread = new Thread(testSpeed);
                        testSpeedThread.start();
                        b.setText(R.string.str_en_stop);
                    }
                }
                else {
                    testSpeedThread.interrupt();
                    testSpeedThread = null;
                    b.setText(R.string.str_en_start);
                }
            }
        });

        switchRxEnable = findViewById(R.id.switch_rx_enable);
        switchTxEnable = findViewById(R.id.switch_tx_enable);

        scanButtonTextHandler = new Handler(new ButtonSetText(scanButton));
        testButtonTextHandler = new Handler(new ButtonSetText(testButton));
        actionBarTitleHandler = new Handler(new ActionBarSetTitle());
        actionBarSubTitleHandler = new Handler(new ActionBarSetSubTitle());

        mPermissionHelper = new PermissionHelper(this, REQUEST_ACCESS_FINE_LOCATION);
        mPermissionHelper.setOnPermissionsListener(new PermissionHelper.OnPermissionsListener() {
            @Override
            public void onPermissonsChange(String permission, boolean permited) {
                if (!permited && permission.equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    Toast.makeText(AppContext,"ACCESS_FINE_LOCATION denied",Toast.LENGTH_SHORT).show();
                }
            }
        });
        mPermissionHelper.requestAuthorities(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});

        Intent bindIntent = new Intent(this, BLeSerialPortService.class);
        bindService(bindIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        registerReceiver(bleBroadcastReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));

        testData = new byte[testDataSize];
        int checkSum=0;
        for(int i = 0; i < testDataSize;i++)
        {
           testData[i] = (byte)(i & 0xFF);
           checkSum^=i;
        }
        testData[testDataSize-1] = (byte)(checkSum & 0xFF);
    }
    private ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder rawBinder) {
            serialPort = ((BLeSerialPortService.LocalBinder) rawBinder).getService()

                    //register the application context to service for callback
                    .setContext(getApplicationContext())
                    .registerCallback(MainActivity.this);
        }

        public void onServiceDisconnected(ComponentName classname) {
            serialPort.unregisterCallback(MainActivity.this)
                    //Close the bluetooth gatt connection.
                    .close();
        }
    };
    private final BroadcastReceiver bleBroadcastReceiver = new BroadcastReceiver(){
        @ Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)) {
                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI,Short.MIN_VALUE);
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if(name == device.getName())
                    displayRSSI.sendEmptyMessage(rssi);
            }
        }
    };
    @Override
    protected void onStop() {
        super.onStop();
    }
    @Override
    protected void onDestroy() {
        if(testSpeedThread != null) testSpeedThread.interrupt();
        if(serialPort != null && serialPort.isConnected())
        {
            serialPort.disconnect();
            serialPort.stopSelf();
        }
        unbindService(mServiceConnection);
        unregisterReceiver(bleBroadcastReceiver);
        super.onDestroy();
    }

    class ButtonSetText implements Handler.Callback{
        Button button;
        public ButtonSetText(Button button)
        {
            this.button = button;
        }
        @Override
        public boolean handleMessage(Message msg) {
            button.setText((String)msg.obj);
            return true;
        }
    }
    private void scanButtonText(String msg)
    {
        Message _msg = Message.obtain();
        _msg.obj = msg;
        _msg.setTarget(scanButtonTextHandler);
        _msg.sendToTarget();
    }
    private void testButtonText(String msg)
    {
        Message _msg = Message.obtain();
        _msg.obj = msg;
        _msg.setTarget(testButtonTextHandler);
        _msg.sendToTarget();
    }
    class ActionBarSetTitle implements Handler.Callback{
        @Override
        public boolean handleMessage(Message msg) {
            getSupportActionBar().setTitle((String)msg.obj);
            return true;
        }
    }
    class ActionBarSetSubTitle implements Handler.Callback{

        @Override
        public boolean handleMessage(Message msg) {
            getSupportActionBar().setSubtitle((String)msg.obj);
            return true;
        }
    }
    private void actionBarTitle(String msg)
    {
        Message _msg = Message.obtain();
        _msg.obj = msg;
        _msg.setTarget(actionBarTitleHandler);
        _msg.sendToTarget();
    }
    private void actionBarSubTitle(String msg)
    {
        Message _msg = Message.obtain();
        _msg.obj = msg;
        _msg.setTarget(actionBarSubTitleHandler);
        _msg.sendToTarget();
    }
    @Override
    public void onConnected(Context context) {
        // when serial port device is connected
        //Toast.makeText(AppContext,"Connected",Toast.LENGTH_SHORT).show();
        actionBarTitle(device.getName());
        actionBarSubTitle(getString(R.string.str_en_connected));
        scanButtonText(getString(R.string.str_en_disconnect));
    }
    @Override
    public void onConnectFailed(Context context) {
        // when some error occured which prevented serial port connection from completing.

    }

    @Override
    public void onDisconnected(Context context) {
        //when device disconnected.
        device = null;
        actionBarSubTitle(getString(R.string.str_en_disconnected));
        scanButtonText(getString(R.string.str_en_scan));
    }

    @Override
    public void onCommunicationError(int status, String msg) {
        // get the send value bytes
    }

    @Override
    public void onReceive(Context context, BluetoothGattCharacteristic rx) {

    }

    @Override
    public void onDeviceFound(BluetoothDevice device) {
        // Called when a UART device is discovered (after calling startScan).
    }

    @Override
    public void onDeviceInfoAvailable() {
        // writeLine(serialPort.getDeviceInfo());
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults){
        mPermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_DEVICE:
                //When the DeviceListActivity return, with the selected device address
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String deviceAddress = data.getStringExtra(BluetoothDevice.EXTRA_DEVICE);
                    BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress);
                    serialPort.connect(device);
                    this.device = device;
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Bluetooth has turned on ", Toast.LENGTH_SHORT).show();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Toast.makeText(this, "Problem in BT Turning ON ", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
            default:
                break;
        }
    }
    Handler displaySpeed = new Handler(new DisplaySpeedGauge());
    Handler displayErrors = new Handler(new DisplayErrorsGauge());
    Handler displayRSSI = new Handler(new DisplayRSSIGauge());
    class DisplaySpeedGauge implements Handler.Callback{
        @Override
        public boolean handleMessage(Message msg) {
            gaugeSpeed.moveToValue(msg.what / 100);
            return true;
        }
    }
    class DisplayErrorsGauge implements Handler.Callback{
        @Override
        public boolean handleMessage(Message msg) {
            gaugeErrors.moveToValue(msg.what);
            return true;
        }
    }
    class DisplayRSSIGauge implements Handler.Callback{
        @Override
        public boolean handleMessage(Message msg) {
            gaugeRSSI.moveToValue(msg.what);
            return true;
        }
    }
    void UpdateGauges(int speed, int errors)
    {
        displaySpeed.sendEmptyMessage(speed);
        displayErrors.sendEmptyMessage(errors);
    }
    Runnable testSpeed = new Runnable() {
        @Override
        public void run() {
            long startTime;
            int txCount;
            int rxCount;
            int rxSize;
            int speed = 0;
            int errors = 0;
            boolean rx = switchRxEnable.isChecked();
            boolean tx = switchTxEnable.isChecked();
            byte[] rxArr;
            testDataTxCount = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    startTime = System.currentTimeMillis();
                    txCount=0;
                    rxCount=0;
                    rxSize=0;
                    while ((System.currentTimeMillis() - startTime) < 1000) {
                        if(tx){
                            serialPort.send(testData);
                            txCount++;
                        }
                        if(rx)
                        {
                            rxArr = serialPort.readArr();
                            if(rxArr != null)
                            {
                                if(tx)
                                {
                                    if(Arrays.equals(rxArr,testData)) rxCount++;
                                }
                                else rxSize+=rxArr.length;
                            }
                        }
                    }
                    testDataTxCount+=(txCount * testDataSize);
                    if(tx)speed = txCount * testDataSize ;
                    else if(rx) speed = rxSize ;
                    if(tx && rx) errors = (((txCount - rxCount) * 100) / txCount);
                    UpdateGauges(speed, errors);
                } catch (Exception e) {
                    return;
                }

            }
            UpdateGauges(0,0);
            showToast("Sent: "+testDataTxCount);
        }
    };
    Handler toast_Handler = new Handler(new ToastMessage());
    class ToastMessage implements Handler.Callback{
        @Override
        public boolean handleMessage(Message msg) {
            Toast.makeText(AppContext,(String)msg.obj,Toast.LENGTH_SHORT).show();
            return true;
        }
    }
    private void showToast(String msg)
    {
        Message _msg = Message.obtain();
        _msg.obj = msg;
        _msg.setTarget(toast_Handler);
        _msg.sendToTarget();
    }
}
