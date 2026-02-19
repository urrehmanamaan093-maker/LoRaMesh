#include <SoftwareSerial.h>   // LoRa Library 

// LoRa module ke RX-TX pins define kiye
#define LORA_RX 2
#define LORA_TX 3
                                                                                                          
SoftwareSerial LoRaSerial(LORA_RX, LORA_TX);  

// Apne device ka address (unique hota hai har device ka)
const int DEVICE_ADDRESS = 1;    



// Jis device ko message bhejna hai uska address
const int TARGET_ADDRESS = 2;   ;                                  

String userInput = "";

void setup() {
  Serial.begin(9600);          // Serial Monitor ke liye baud rate
  LoRaSerial.begin(115200);    // LoRa module ka baud rate
  delay(2000);                 // Thoda delay taaki module properly start ho jaye


  
  
  LoRaSerial.print("AT+ADDRESS=");
  LoRaSerial.println(DEVICE_ADDRESS);
  delay(300);
 
  LoRaSerial.println("AT+NETWORKID=5");
  delay(300);
  Serial.println("LoRa Chat Ready. Type to send:");
}


void loop() {
  
  // Agar user ne Serial Monitor me kuch type kiya
  if (Serial.available()) {
    userInput = Serial.readStringUntil('\n');  // Enter tak input read karo
    userInput.trim();                          // Extra spaces hata do
    
    if (userInput.length() > 0) {
      // AT command bana rahe hain message bhejne ke liye
      String cmd = "AT+SEND=" + String(TARGET_ADDRESS) + "," + String(userInput.length()) + "," + userInput;
      
      LoRaSerial.println(cmd);        // Command LoRa module ko bheji
      Serial.println("📤 Sent: " + userInput);  // Serial monitor pe show
    }
  }}
 

  if (LoRaSerial.available()) {
    String incoming = LoRaSerial.readStringUntil('\n');
    Serial.println("📥 Received: " + incoming);
  }
}
