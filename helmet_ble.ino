// Smart Helmet: BLE link to the Helmet SOS web app
// Board: ESP32 DevKit (WROOM). Arduino IDE with the "esp32 by Espressif" package.
// Uses only the built-in BLE library. Do not use Serial Bluetooth with this app.

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHAR_UUID    "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

BLECharacteristic *crashChar;
bool phoneConnected = false;

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer *s)    { phoneConnected = true; }
  void onDisconnect(BLEServer *s) { phoneConnected = false; BLEDevice::startAdvertising(); }
};

void setupBLE() {
  BLEDevice::init("SmartHelmet");                 // the app looks for this exact name
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService *svc = server->createService(SERVICE_UUID);
  crashChar = svc->createCharacteristic(CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  crashChar->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();
}

// Call this when the 10 s cancel window ends without the button being pressed.
void sendCrashToPhone() {
  if (!phoneConnected) return;
  crashChar->setValue("CRASH");
  crashChar->notify();
}

void setup() {
  Serial.begin(115200);
  setupBLE();
  // ... your existing sensor setup (MPU6050, IR, reed switch, button, LED, buzzer)
}

void loop() {
  // ... your existing crash logic, then:
  // if (crashConfirmed && !cancelled) sendCrashToPhone();
}
