package com.example.bluetoothchatapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSION_LOCATION = 2;

    private BluetoothAdapter bluetoothAdapter;
    private ArrayAdapter<String> devicesArrayAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice connectedDevice;

    private TextView statusTextView, chatMessages;
    private Button btnDiscover, btnSend;
    private EditText editMessage;
    private ListView listDevices;

    private ConnectedThread connectedThread;

    // UUID for SPP (Serial Port Profile)
    private static final UUID MY_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            if (msg.what == ConnectedThread.MESSAGE_READ) {
                byte[] readBuf = (byte[]) msg.obj;
                String receivedMessage = new String(readBuf, 0, msg.arg1);
                chatMessages.append("\nFriend: " + receivedMessage);
                return true;
            }
            return false;
        }
    });

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress();
                devicesArrayAdapter.add(deviceName + "\n" + deviceHardwareAddress);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

         // Init views
        statusTextView = findViewById(R.id.status);
        btnDiscover = findViewById(R.id.btnDiscover);
        listDevices = findViewById(R.id.listDevices);
        editMessage = findViewById(R.id.editMessage);
        btnSend = findViewById(R.id.btnSend);
        chatMessages = findViewById(R.id.chatMessages);

        // Init Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Request Bluetooth to be enabled
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        // Prepare device list
        devicesArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1);
        listDevices.setAdapter(devicesArrayAdapter);

        // Handle device click to connect
        listDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view,
                                    int position, long id) {
                String info = devicesArrayAdapter.getItem(position);
                String address = info.substring(info.length() - 17);
                connectToDevice(address);
            }
        });

        btnDiscover.setOnClickListener(v -> {
            checkPermissionsAndDiscover();
        });

        btnSend.setOnClickListener(v -> {
            String msg = editMessage.getText().toString();
            if (msg.isEmpty() || connectedThread == null) {
                Toast.makeText(MainActivity.this, "Not connected or message empty",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            connectedThread.write(msg.getBytes());
            chatMessages.append("\nYou: " + msg);
            editMessage.setText("");
        });

        // Register discovery receiver
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(discoveryReceiver, filter);
    }

    private void checkPermissionsAndDiscover() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_PERMISSION_LOCATION);
        } else {
            startDiscovery();
        }
    }

    private void startDiscovery() {
        devicesArrayAdapter.clear();
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
        Toast.makeText(this, "Discovering devices...", Toast.LENGTH_SHORT).show();
    }

    private void connectToDevice(String address) {
        bluetoothAdapter.cancelDiscovery();
        statusTextView.setText("Connecting to " + address);
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);

        new Thread(() -> {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();
                connectedDevice = device;
                runOnUiThread(() -> {
                    statusTextView.setText("Connected to " + device.getName());
                });

                connectedThread = new ConnectedThread(bluetoothSocket, handler);
                connectedThread.start();

            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Connection failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    statusTextView.setText("Connection failed");
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(discoveryReceiver);
        if (connectedThread != null) {
            connectedThread.cancel();
        }
        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Handle permission result
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION_LOCATION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDiscovery();
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // Thread to handle connection and message reading/writing
    private static class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inStream;
        private final OutputStream outStream;
        private final Handler handler;

        static final int MESSAGE_READ = 0;

        public ConnectedThread(BluetoothSocket socket, Handler handler) {
            this.socket = socket;
            this.handler = handler;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            inStream = tmpIn;
            outStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    bytes = inStream.read(buffer);
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                } catch (IOException e) {
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                outStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
