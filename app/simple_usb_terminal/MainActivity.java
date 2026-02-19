package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater; // ✅ This fixes your current error
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;






public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {
    private ProgressDialog progressDialog;
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    StringBuilder imageBuilder = new StringBuilder();
    boolean isReceivingImage = false;



    private static final int IMAGE_PICK_REQUEST = 101;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Permissions
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                1
        );

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportFragmentManager().addOnBackStackChangedListener(this);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.fragment, new DevicesFragment(), "devices")
                    .commit();
        } else {
            onBackStackChanged();
        }




        // Progress dialog for image compression
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Compressing image...");
        progressDialog.setCancelable(false);

        // Image picker launcher
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri imageUri = result.getData().getData();
                        progressDialog.show(); // Show loading
                        new Thread(() -> {
                            try {
                                Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 40, baos);
                                byte[] imageBytes = baos.toByteArray();
                                String encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                                runOnUiThread(() -> {
                                    progressDialog.dismiss(); // Hide loading
                                    sendImageInChunks(encodedImage);
                                    Toast.makeText(this, "Image compressed and sent", Toast.LENGTH_SHORT).show();
                                });
                            } catch (IOException e) {
                                runOnUiThread(() -> {
                                    progressDialog.dismiss();
                                    Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show();
                                });
                                e.printStackTrace();
                            }
                        }).start();
                    }
                }
        );
        Button openWhatsappDialog = findViewById(R.id.btn_open_whatsapp_popup);

//        openWhatsappDialog.setOnClickListener(v -> {
//            LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
//            View view = inflater.inflate(R.layout.dialog_send_whatsapp, null);
//
//            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
//                    .setView(view)
//                    .create();
//
//            dialog.show();
//
//            EditText phoneInput = view.findViewById(R.id.edit_phone_number);
//            EditText messageInput = view.findViewById(R.id.edit_whatsapp_message);
//            Button btnSend = view.findViewById(R.id.btn_send_whatsapp);
//
//            btnSend.setOnClickListener(sendView -> {
//                String phone = phoneInput.getText().toString().trim();
//                String message = messageInput.getText().toString().trim();
//
//                if (!phone.isEmpty() && !message.isEmpty()) {
//                    openWhatsAppWithMessage(phone, message);
//                    dialog.dismiss();
//                } else {
//                    Toast.makeText(MainActivity.this, "Please enter both number and message", Toast.LENGTH_SHORT).show();
//                }
//            });
//        });
    }
        @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(
                getSupportFragmentManager().getBackStackEntryCount() > 0
        );
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager()
                    .findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

    // ✅ Open image picker
    public void pickAndSendImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        imagePickerLauncher.launch(Intent.createChooser(intent, "Select Picture"));
    }

    // ✅ Process image and send
//    @Override
//    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
//        super.onActivityResult(requestCode, resultCode, data);
//
//        if (requestCode == IMAGE_PICK_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
//            Uri imageUri = data.getData();
//            try {
//                Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
//                ByteArrayOutputStream baos = new ByteArrayOutputStream();
//                bitmap.compress(Bitmap.CompressFormat.JPEG, 40, baos);  // Compress to avoid black screen
//                byte[] imageBytes = baos.toByteArray();
//                String encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
//
//                sendImageInChunks(encodedImage);
//
//            } catch (IOException e) {
//                e.printStackTrace();
//                Toast.makeText(this, "Error sending image", Toast.LENGTH_SHORT).show();
//            }
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

    public void sendViaLoRa(String message) {
        TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager()
                .findFragmentByTag("terminal");
        if (terminal != null) {
            terminal.send(message);
        }
    }
//    private void receive(String message) {
////        status("Received: " + message);
//        TerminalFragment terminalFragment = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
//        if (terminalFragment != null) {
//            terminalFragment.handleIncomingLoRaMessage(message);
//        }
//    }



    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }
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
//
//                    runOnUiThread(() -> handleReceivedImage(base64Image));
//
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
//                int endBracketIndex = message.indexOf("]", 11);
//                if (endBracketIndex != -1) {
//                    String phone = message.substring(11, endBracketIndex);
//                    String msg = message.substring(endBracketIndex + 1).trim();
//
//                    if (isInternetAvailable()) {
//
//                           runOnUiThread(() -> openWhatsAppWithMessage(phone, msg));
//
//                    } else {
//
//                            runOnUiThread(() ->
//                                    Toast.makeText(this, "❌ No internet for WhatsApp", Toast.LENGTH_LONG).show()
//                            );
//
//                    }
//                }
//                return;
//            }
//
//            // ✅ Fallback: Plain Text Message (including numeric-only messages)
//
//                runOnUiThread(() -> {
//                    String cleanMessage = message.trim().replaceAll("^\\\"|\\\"$", ""); // removes starting and ending quotes
//                    Toast.makeText(this, "📩 Msg: " + cleanMessage, Toast.LENGTH_SHORT).show();
//                });
//
//
//        } catch (Exception e) {
//            e.printStackTrace();
//
//                runOnUiThread(() ->
//                        Toast.makeText(this, "⚠️ Error: " + message, Toast.LENGTH_SHORT).show()
//                );
//
//        }
//    }
//Button sendWhatsappButton = view.findViewById(R.id.btn_open_whatsapp_popup);
//sendWhatsappButton.setOnClickListener(new View.OnClickListener() {
//        @Override
//        public void onClick(View v) {
//            showWhatsAppDialog();
//        }
//    });




//




    public void handleIncomingLoRaMessage(String message) {
        try {
            // Trim unwanted spaces/newlines
            String cleanMessage = message.trim();

            // ✅ Strip LoRa metadata (like +RCV=2,28, etc.) and keep only the WhatsApp/Image/Text part
            int wpIndex = cleanMessage.indexOf("[whatsapp][");
            if (wpIndex != -1) {
                cleanMessage = cleanMessage.substring(wpIndex);
            }

            // ✅ Handle Image Transfer
            if ("IMG_START".equals(cleanMessage)) {
                isReceivingImage = true;
                imageBuilder.setLength(0);
                return;
            }

            if ("IMG_END".equals(cleanMessage)) {
                isReceivingImage = false;
                String base64Image = imageBuilder.toString();
                runOnUiThread(() -> handleReceivedImage(base64Image));
                return;
            }

            if (isReceivingImage) {
                imageBuilder.append(cleanMessage);
                return;
            }

            // ✅ Handle WhatsApp Message Format (works even if RCV data was present)
            if (cleanMessage.startsWith("[whatsapp][")) {
                Log.d("LoRa", "📩 LoRa WhatsApp Msg Detected: " + cleanMessage);
                Toast.makeText(this, "WhatsApp message received: " + cleanMessage, Toast.LENGTH_SHORT).show();

                int endBracketIndex = cleanMessage.indexOf("]", 11);
                if (endBracketIndex != -1) {
                    String phone = cleanMessage.substring(11, endBracketIndex);
                    String msg = cleanMessage.substring(endBracketIndex + 1).trim();

                    if (isInternetAvailable()) {
                        runOnUiThread(() -> {
                            Log.d("LoRa", "Opening WhatsApp for: " + phone + " -> " + msg);
                            Toast.makeText(this, "📤 Sending WhatsApp to " + phone, Toast.LENGTH_SHORT).show();
                            openWhatsAppWithMessage(phone, msg);
                        });
                    } else {
                        runOnUiThread(() ->
                                Toast.makeText(this, "❌ No internet for WhatsApp", Toast.LENGTH_LONG).show()
                        );
                    }
                }
                return; // Exit after processing WhatsApp message
            }

            // ✅ Fallback: Plain Text Message
            // ✅ Fallback: Plain Text Message
            final String finalMsg = cleanMessage.replaceAll("^\\\"|\\\"$", ""); // remove starting/ending quotes
            runOnUiThread(() -> {
                Toast.makeText(this, "📩 Msg: " + finalMsg, Toast.LENGTH_SHORT).show();
            });


        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() ->
                    Toast.makeText(this, "⚠️ Error: " + message, Toast.LENGTH_SHORT).show()
            );
        }
    }

    public void openWhatsAppWithMessage(String phone, String message) {
        Toast.makeText(this, "Opening WhatsApp with: " + phone, Toast.LENGTH_SHORT).show();

        try {
            Log.d("LoRa", "openWhatsAppWithMessage called with: " + phone + ", msg: " + message);

            // Internet check
            if (!isInternetAvailable()) {
                String formattedLoRaMessage = "[whatsapp][" + phone + "]" + message;
                sendViaLoRa(formattedLoRaMessage);
                Toast.makeText(this, "No internet. Sent via LoRa to another device.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Validate and format number
            String formattedNumber = phone.replaceAll("[^\\d]", "");
            if (!formattedNumber.matches("^\\d{10,15}$")) {
                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show();
                return;
            }
            String fullNumber = formattedNumber.startsWith("91") ? formattedNumber : "91" + formattedNumber;

            String encodedMessage = URLEncoder.encode(message, "UTF-8");
            String url = "https://wa.me/" + fullNumber + "?text=" + encodedMessage;

            PackageManager pm = this.getPackageManager();

            // ✅ Check if any WhatsApp variant is installed
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // First try regular WhatsApp
            intent.setPackage("com.whatsapp");
            if (intent.resolveActivity(pm) != null) {
                this.startActivity(intent);
                return;
            }

            // Try WhatsApp Business
            intent.setPackage("com.whatsapp.w4b");
            if (intent.resolveActivity(pm) != null) {
                this.startActivity(intent);
                return;
            }

            // Fallback to browser
            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            fallbackIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.startActivity(fallbackIntent);

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to open WhatsApp: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


//    public void openWhatsAppWithMessage(String phone, String message) {
//        Toast.makeText(this, "Opening WhatsApp with: " + phone, Toast.LENGTH_SHORT).show();
//
//        try {
//            Log.d("LoRa", "openWhatsAppWithMessage called with: " + phone + ", msg: " + message);
//
//            // Internet check
//            if (!isInternetAvailable()) {
//                String formattedLoRaMessage = "[whatsapp][" + phone + "]" + message;
//                send(formattedLoRaMessage);
//                Toast.makeText(this, "No internet. Sent via LoRa to another device.", Toast.LENGTH_SHORT).show();
//                return;
//            }
//
//            // Validate and format number
//            String formattedNumber = phone.replaceAll("[^\\d]", "");
//            if (!formattedNumber.matches("^\\d{10,15}$")) {
//                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show();
//                return;
//            }
//            String fullNumber = formattedNumber.startsWith("91") ? formattedNumber : "91" + formattedNumber;
//
//            String encodedMessage = URLEncoder.encode(message, "UTF-8");
//            String url = "https://wa.me/" + fullNumber + "?text=" + encodedMessage;
//
//            PackageManager pm = this.getPackageManager();
//
//            // ✅ Check if any WhatsApp variant is installed
//            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
//            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//
//            // First try regular WhatsApp
//            intent.setPackage("com.whatsapp");
//            if (intent.resolveActivity(pm) != null) {
//                this.startActivity(intent);
//                return;
//            }
//
//            // Try WhatsApp Business
//            intent.setPackage("com.whatsapp.w4b");
//            if (intent.resolveActivity(pm) != null) {
//                this.startActivity(intent);
//                return;
//            }
//
//            // Fallback to browser
//            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
//            fallbackIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            this.startActivity(fallbackIntent);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            Toast.makeText(this, "Failed to open WhatsApp: " + e.getMessage(), Toast.LENGTH_LONG).show();
//        }
//    }





    // ✅ Optional: use this for TerminalFragment to fetch location
    public void sendCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        if (location != null) {
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            String locationMsg = "Lat: " + latitude + ", Lng: " + longitude;
            sendViaLoRa(locationMsg);
        } else {
            Toast.makeText(this, "Unable to get location", Toast.LENGTH_SHORT).show();
        }
    }


    private void handleReceivedImage(String base64ImageString) {
        try {
            // Step 1: Convert Base64 string to image bytes
            byte[] decodedBytes = Base64.decode(base64ImageString, Base64.NO_WRAP);

            // Step 2: Turn bytes into a Bitmap (image object)
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            // Step 3: Save image as JPG in app's private storage
            File imageFile = new File(getExternalFilesDir(null), "received_image_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos); // Save as JPG
            fos.close();

            // Step 4: Show image in the app (optional)
            ImageView imageView = findViewById(R.id.receivedImageView);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(bitmap);

            Toast.makeText(this, "Image received and saved!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show();
        }
    }
    private boolean hasInternetConnection() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnected();
        }
        return false;
    }
} 