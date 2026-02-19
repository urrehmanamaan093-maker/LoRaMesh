package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.XonXoffFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.text.Editable;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Base64;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import org.json.JSONObject;
import android.util.Log;










public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {
    private String cleanMessage(String message) {
        // Remove non-printable ASCII characters
        message = message.replaceAll("[^\\x20-\\x7E]", "").trim();

        // Remove numbers
//        message = message.replaceAll("[0-9]", "");

        // Filter out specific unwanted messages
        if (message.startsWith("OK+") || message.equals("OK")) {
            return "";
        }

        return message;
    }


    private enum Connected { False, Pending, True }

    private final Handler mainLooper;
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ImageButton sendBtn;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private enum SendButtonState {Idle, Busy, Disabled};

    private ControlLines controlLines = new ControlLines();
    private XonXoffFilter flowControlFilter;

    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    public TerminalFragment() {
        mainLooper = new Handler(Looper.getMainLooper());
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };


    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
        ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);
    }



    @Override
    public void onStop() {
        getActivity().unregisterReceiver(broadcastReceiver);
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */



    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        sendBtn = view.findViewById(R.id.send_btn);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));

        // Location button click logic
        Button locationButton = view.findViewById(R.id.btnLocation);
        locationButton.setOnClickListener(v -> {
            LocationManager locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);

            if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getActivity(), "Location permission not granted", Toast.LENGTH_SHORT).show();
                return;
            }

            Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location != null) {
                double latitude = location.getLatitude();
                double longitude = location.getLongitude();
                send("Lat: " + latitude + ", Lng: " + longitude);
            } else {
                Toast.makeText(getActivity(), "Unable to get location", Toast.LENGTH_SHORT).show();
            }
        });

        // Image send button click logic
        Button imageSendButton = view.findViewById(R.id.btn_send_image);
        imageSendButton.setOnClickListener(v -> {
            ((MainActivity) getActivity()).pickAndSendImage();
        });

        // WhatsApp message send button click logic
        Button sendWhatsappButton = view.findViewById(R.id.btn_open_whatsapp_popup);
        sendWhatsappButton.setOnClickListener(v -> showWhatsAppDialog());





        controlLines.onCreateView(view);

        return view; // ✅ Return at the end
    }


    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        controlLines.onPrepareOptionsMenu(menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, which) -> {
                newline = newlineValues[which];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            item.setChecked(controlLines.showControlLines(!item.isChecked()));
            return true;
        } else if (id == R.id.flowControl) {
            controlLines.selectFlowControl();
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*
     * Serial + UI
     */
//    @Override
//    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
//        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
//
//        Button sendWhatsAppButton = view.findViewById(R.id.btn_send_whatsapp);
//        sendWhatsAppButton.setOnClickListener(v -> showWhatsAppDialog());
//
//        return view;
//    }

    private void showWhatsAppDialog() {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_send_whatsapp, null);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();

        dialog.show();

        EditText phoneInput = dialogView.findViewById(R.id.edit_phone_number);
        EditText messageInput = dialogView.findViewById(R.id.edit_whatsapp_message);
        Button btnSend = dialogView.findViewById(R.id.btn_send_whatsapp);

        btnSend.setOnClickListener(sendView -> {
            String phone = phoneInput.getText().toString().trim();
            String message = messageInput.getText().toString().trim();

            if (!phone.isEmpty() && !message.isEmpty()) {
                // Call method in MainActivity
                ((MainActivity) requireActivity()).openWhatsAppWithMessage(phone, message);
                dialog.dismiss();
            } else {
                Toast.makeText(getContext(), "Please enter number and message", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(Constants.INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("Setting serial parameters failed: " + e.getMessage());
            }
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        controlLines.stop();
        service.disconnect();
        updateSendBtn(SendButtonState.Idle);
        usbSerialPort = null;
    }

    public void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg;
        byte[] data;
        if(hexEnabled) {
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, TextUtil.fromHexString(str));
            TextUtil.toHexString(sb, newline.getBytes());
            msg = sb.toString();
            data = TextUtil.fromHexString(msg);
        } else {
            msg = str;
            data = (str + newline).getBytes();
        }
        try {
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (SerialTimeoutException e) { // e.g. writing large data at low baud rate or suspended by flow control
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void sendAgain(byte[] data0, int offset) {
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Busy : SendButtonState.Disabled);
        if (connected != Connected.True) {
            return;
        }
        byte[] data;
        if (offset == 0) {
            data = data0;
        } else {
            data = new byte[data0.length - offset];
            System.arraycopy(data0, offset, data, 0, data.length);
        }
        try {
            service.write(data);
        } catch (SerialTimeoutException e) {
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
            return;
        } catch (Exception e) {
            onSerialIoError(e);
        }
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
    }

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (flowControlFilter != null)
                data = flowControlFilter.filter(data);
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = cleanMessage(new String(data));

                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }

                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }

        receiveText.append(spn);

        // ✅ Trigger logic handler for LoRa messages like WhatsApp
        if (!hexEnabled) {
            String fullMessage = spn.toString().replace("\n", "").trim();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).handleIncomingLoRaMessage(fullMessage);
            }
        }
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    void updateSendBtn(SendButtonState state) {
        sendBtn.setEnabled(state == SendButtonState.Idle);
        sendBtn.setImageAlpha(state == SendButtonState.Idle ? 255 : 64);
        sendBtn.setImageResource(state == SendButtonState.Disabled ? R.drawable.ic_block_white_24dp : R.drawable.ic_send_white_24dp);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        controlLines.start();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }
//    private void showWhatsAppDialog() {
//        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_send_whatsapp, null);
//
//        EditText editPhone = dialogView.findViewById(R.id.edit_phone_number);
//        EditText editMessage = dialogView.findViewById(R.id.edit_whatsapp_message);
//        Button btnSend = dialogView.findViewById(R.id.btn_send_whatsapp);
//
//        AlertDialog dialog = new AlertDialog.Builder(requireContext())
//                .setView(dialogView)
//                .create();
//
//        dialog.show();
//
//        btnSend.setOnClickListener(v -> {
//            String phone = editPhone.getText().toString().trim();
//            String message = editMessage.getText().toString().trim();
//
//            if (!phone.isEmpty() && !message.isEmpty()) {
//                openWhatsAppWithMessage(phone, message);
//                dialog.dismiss();
//            } else {
//                Toast.makeText(requireContext(), "Please fill both fields", Toast.LENGTH_SHORT).show();
//            }
//        });
//    }
    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) requireContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }

//    public void openWhatsAppWithMessage(String phone, String message) {
//        Toast.makeText(requireContext(), "Opening WhatsApp with: " + phone, Toast.LENGTH_SHORT).show();
//
//        try {
//            Log.d("LoRa", "openWhatsAppWithMessage called with: " + phone + ", msg: " + message);
//
//            // Internet check
//            if (!isInternetAvailable()) {
//                String formattedLoRaMessage = "[whatsapp][" + phone + "]" + message;
//                send(formattedLoRaMessage);
//                Toast.makeText(getContext(), "No internet. Sent via LoRa to another device.", Toast.LENGTH_SHORT).show();
//                return;
//            }
//
//            // Validate and format number
//            String formattedNumber = phone.replaceAll("[^\\d]", "");
//            if (!formattedNumber.matches("^\\d{10,15}$")) {
//                Toast.makeText(getContext(), "Enter a valid phone number", Toast.LENGTH_SHORT).show();
//                return;
//            }
//            String fullNumber = formattedNumber.startsWith("91") ? formattedNumber : "91" + formattedNumber;
//
//            String encodedMessage = URLEncoder.encode(message, "UTF-8");
//            String url = "https://wa.me/" + fullNumber + "?text=" + encodedMessage;
//
//            PackageManager pm = requireActivity().getPackageManager();
//
//            // ✅ Check if any WhatsApp variant is installed
//            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
//            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//
//            // First try regular WhatsApp
//            intent.setPackage("com.whatsapp");
//            if (intent.resolveActivity(pm) != null) {
//                requireActivity().startActivity(intent);
//                return;
//            }
//
//            // Try WhatsApp Business
//            intent.setPackage("com.whatsapp.w4b");
//            if (intent.resolveActivity(pm) != null) {
//                requireActivity().startActivity(intent);
//                return;
//            }
//
//            // Fallback to browser
//            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
//            fallbackIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            requireActivity().startActivity(fallbackIntent);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            Toast.makeText(requireContext(), "Failed to open WhatsApp: " + e.getMessage(), Toast.LENGTH_LONG).show();
//        }
//    }




    private void sendImageInChunks(String encodedImage) {
        int chunkSize = 200;
        int length = encodedImage.length();

        for (int i = 0; i < length; i += chunkSize) {
            int end = Math.min(length, i + chunkSize);
            String chunk = encodedImage.substring(i, end);
            String wrapped = "{\"type\":\"img\",\"data\":\"" + chunk + "\"}";
            sendViaLoRa(wrapped);
        }
    }
    private void handleReceivedImage(String base64Image) {
        try {
            byte[] decodedBytes = Base64.decode(base64Image, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            if (isAdded()) {
                ImageView imageView = requireView().findViewById(R.id.receivedImageView);
                imageView.setVisibility(View.VISIBLE);
                imageView.setImageBitmap(bitmap);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (isAdded()) {
                Toast.makeText(requireContext(), "Failed to decode image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void sendViaLoRa(String message) {
        TerminalFragment terminal = (TerminalFragment) requireActivity()
                .getSupportFragmentManager().findFragmentByTag("terminal");
        if (terminal != null) {
            terminal.send(message);
        }
    }
    private boolean isReceivingImage = false;
    private StringBuilder imageBuilder = new StringBuilder();

//    public void handleIncomingLoRaMessage(String message) {
//        try {
//            // ✅ Handle Image Transfer
//            if ("IMG_START".equals(message)) {
//                isReceivingImage = true;
//                imageBuilder.setLength(0);
//                return;
//            }
//
//            if ("IMG_END".equals(message)) {
//                isReceivingImage = false;
//                String base64Image = imageBuilder.toString();
//                if (isAdded()) {
//                    requireActivity().runOnUiThread(() -> handleReceivedImage(base64Image));
//                }
//                return;
//            }
//
//            if (isReceivingImage) {
//                imageBuilder.append(message);
//                return;
//            }
//
//            // ✅ Handle WhatsApp Message Format
//            if (message.startsWith("[whatsapp][")) {
//                Log.d("LoRa", "📩 LoRa Msg Detected: " + message);
//                Toast.makeText(requireContext(), "WhatsApp message received: " + message, Toast.LENGTH_SHORT).show();
//
//                int endBracketIndex = message.indexOf("]", 11);
//                if (endBracketIndex != -1) {
//                    String phone = message.substring(11, endBracketIndex);
//                    String msg = message.substring(endBracketIndex + 1).trim();
//
//                    if (isInternetAvailable()) {
//                        if (isAdded()) {
//                            requireActivity().runOnUiThread(() -> {
//                                Log.d("LoRa", "Opening WhatsApp for: " + phone + " -> " + msg);
//                                Toast.makeText(requireContext(), "📤 Sending WhatsApp to " + phone, Toast.LENGTH_SHORT).show();
//                                openWhatsAppWithMessage(phone, msg);
//                            });
//                        }
//                    } else {
//                        if (isAdded()) {
//                            requireActivity().runOnUiThread(() ->
//                                    Toast.makeText(requireContext(), "❌ No internet for WhatsApp", Toast.LENGTH_LONG).show()
//                            );
//                        }
//                    }
//                }
//                return;
//            }
//
//
//            // ✅ Fallback: Plain Text Message (including numeric-only messages)
//            if (isAdded()) {
//                requireActivity().runOnUiThread(() -> {
//                    String cleanMessage = message.trim().replaceAll("^\\\"|\\\"$", ""); // removes starting and ending quotes
//                    Toast.makeText(requireContext(), "📩 Msg: " + cleanMessage, Toast.LENGTH_SHORT).show();
//                });
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            if (isAdded()) {
//                requireActivity().runOnUiThread(() ->
//                        Toast.makeText(requireContext(), "⚠️ Error: " + message, Toast.LENGTH_SHORT).show()
//                );
//            }
//        }
//    }





    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;

        private View frame;
        private ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        private boolean showControlLines;                                               // show & update control line buttons
        private UsbSerialPort.FlowControl flowControl = UsbSerialPort.FlowControl.NONE; // !NONE: update send button state

        boolean sendAllowed = true;

        ControlLines() {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        void onCreateView(View view) {
            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);


        }

        void onPrepareOptionsMenu(Menu menu) {
            try {
                EnumSet<UsbSerialPort.ControlLine> scl = usbSerialPort.getSupportedControlLines();
                EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
                menu.findItem(R.id.controlLines).setEnabled(!scl.isEmpty());
                menu.findItem(R.id.controlLines).setChecked(showControlLines);
                menu.findItem(R.id.flowControl).setEnabled(sfc.size() > 1);
            } catch (Exception ignored) {
            }
        }

        void selectFlowControl() {
            EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
            UsbSerialPort.FlowControl fc = usbSerialPort.getFlowControl();
            ArrayList<String> names = new ArrayList<>();
            ArrayList<UsbSerialPort.FlowControl> values = new ArrayList<>();
            int pos = 0;
            names.add("<none>");
            values.add(UsbSerialPort.FlowControl.NONE);
            if (sfc.contains(UsbSerialPort.FlowControl.RTS_CTS)) {
                names.add("RTS/CTS control lines");
                values.add(UsbSerialPort.FlowControl.RTS_CTS);
                if (fc == UsbSerialPort.FlowControl.RTS_CTS) pos = names.size() -1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.DTR_DSR)) {
                names.add("DTR/DSR control lines");
                values.add(UsbSerialPort.FlowControl.DTR_DSR);
                if (fc == UsbSerialPort.FlowControl.DTR_DSR) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF_INLINE)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF_INLINE);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF_INLINE) pos = names.size() - 1;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Flow Control");
            builder.setSingleChoiceItems(names.toArray(new CharSequence[0]), pos, (dialog, which) -> {
                dialog.dismiss();
                try {
                    flowControl = values.get(which);
                    usbSerialPort.setFlowControl(flowControl);
                    flowControlFilter = usbSerialPort.getFlowControl() == UsbSerialPort.FlowControl.XON_XOFF_INLINE ? new XonXoffFilter() : null;
                    start();
                } catch (Exception e) {
                    status("Set flow control failed: "+e.getClass().getName()+" "+e.getMessage());
                    flowControl = UsbSerialPort.FlowControl.NONE;
                    flowControlFilter = null;
                    start();
                }
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
            builder.setNeutralButton("Info", (dialog, which) -> {
                dialog.dismiss();
                AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
                builder2.setTitle("Flow Control").setMessage("If send is stopped by the external device, the 'Send' button changes to 'Blocked' icon.");
                builder2.create().show();
            });
            builder.create().show();
        }

        public boolean showControlLines(boolean show) {
            showControlLines = show;
            start();
            return showControlLines;
        }

        void start() {
            if (showControlLines) {
                try {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getSupportedControlLines();
                    rtsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RTS) ? View.VISIBLE : View.INVISIBLE);
                    ctsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CTS) ? View.VISIBLE : View.INVISIBLE);
                    dtrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DTR) ? View.VISIBLE : View.INVISIBLE);
                    dsrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DSR) ? View.VISIBLE : View.INVISIBLE);
                    cdBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CD)   ? View.VISIBLE : View.INVISIBLE);
                    riBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RI)   ? View.VISIBLE : View.INVISIBLE);
                } catch (IOException e) {
                    showControlLines = false;
                    status("getSupportedControlLines() failed: " + e.getMessage());
                }
            }
            frame.setVisibility(showControlLines ? View.VISIBLE : View.GONE);
            if(flowControl == UsbSerialPort.FlowControl.NONE) {
                sendAllowed = true;
                updateSendBtn(SendButtonState.Idle);
            }

            mainLooper.removeCallbacks(runnable);
            if (showControlLines || flowControl != UsbSerialPort.FlowControl.NONE) {
                run();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            sendAllowed = true;
            updateSendBtn(SendButtonState.Idle);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                if (showControlLines) {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines();
                    if(rtsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setChecked(!rtsBtn.isChecked());
                    if(ctsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setChecked(!ctsBtn.isChecked());
                    if(dtrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setChecked(!dtrBtn.isChecked());
                    if(dsrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setChecked(!dsrBtn.isChecked());
                    if(cdBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.CD))  cdBtn.setChecked(!cdBtn.isChecked());
                    if(riBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.RI))  riBtn.setChecked(!riBtn.isChecked());
                }
                if (flowControl != UsbSerialPort.FlowControl.NONE) {
                    switch (usbSerialPort.getFlowControl()) {
                        case DTR_DSR:         sendAllowed = usbSerialPort.getDSR(); break;
                        case RTS_CTS:         sendAllowed = usbSerialPort.getCTS(); break;
                        case XON_XOFF:        sendAllowed = usbSerialPort.getXON(); break;
                        case XON_XOFF_INLINE: sendAllowed = flowControlFilter != null && flowControlFilter.getXON(); break;
                        default:              sendAllowed = true;
                    }
                    updateSendBtn(sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
                }
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

    }

}
