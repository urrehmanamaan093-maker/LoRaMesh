#include <SoftwareSerial.h>   // LoRa module se communication ke liye SoftwareSerial use ho raha hai

// LoRa module ke RX aur TX pins define kiye (Arduino side)
#define LORA_RX 2
#define LORA_TX 3

// LoRa ke liye separate serial channel create kiya
SoftwareSerial LoRaSerial(LORA_RX, LORA_TX);  

// Is device ka address (yeh SENDER device hai)
const int DEVICE_ADDRESS = 1;                 

// Jis device ko message bhejna hai (RECEIVER ka address)
const int TARGET_ADDRESS = 2;                                  

String userInput = "";   // User ka message temporarily store karne ke liye

void setup() {
  Serial.begin(9600);         // Serial Monitor se input/output ke liye
  LoRaSerial.begin(115200);   // LoRa module ka baud rate
  delay(2000);                // Module ko stable hone ka time de rahe hain
  
  // Sender device ka address set kar rahe hain
  LoRaSerial.print("AT+ADDRESS=");
  LoRaSerial.println(DEVICE_ADDRESS);
  delay(300);
 
  // Dono devices ke liye same Network ID zaroori hoti hai
  LoRaSerial.println("AT+NETWORKID=5");
  delay(300);

  // User ko indicate karne ke liye ki sender ready hai
  Serial.println("LoRa Sender Ready. Type message to send:");
}

void loop() {
  
  // Serial Monitor se message type kiya gaya hai ya nahi
  if (Serial.available()) {
    userInput = Serial.readStringUntil('\n');  // Enter tak pura message read karo
    userInput.trim();                          // Extra spaces/newline hata do
    
    if (userInput.length() > 0) {
      // AT command format bana rahe hain message bhejne ke liye
      String cmd = "AT+SEND=" + String(TARGET_ADDRESS) + "," + String(userInput.length()) + "," + userInput;
      
      LoRaSerial.println(cmd);        // Message LoRa module ko bhej diya
      Serial.println("📤 Sent: " + userInput);  // Confirmation show karo
    }
  }

  // Sender side normally receive nahi karega, 
  // but agar koi response aata hai to yahan show ho jayega
  if (LoRaSerial.available()) {
    String incoming = LoRaSerial.readStringUntil('\n');
    Serial.println("📥 Received: " + incoming);
  }
}
