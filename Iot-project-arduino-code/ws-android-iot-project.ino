#include <WiFi.h>
#include <WiFiClientSecure.h>   // for TLS (wss://)
#include <ArduinoJson.h>

// ---------- CONFIGURATION ----------
const char* ssid = "I2ST_WIFI";
const char* password = "HO_IISTPL1!";

// For local testing (no TLS):
// const char* ws_host = "192.168.x.x";   // your PC's IP
// const int ws_port = 8080;
// #define USE_TLS false

// For production Cloud Run:
const char* ws_host = "YOUR_CLOUD_RUN_URL.run.app";  // <-- replace with your real Cloud Run host
const int ws_port = 443;
#define USE_TLS true

const char* authToken = "rak123";
const char* node_id = "esp32-1";
const int LED_PIN = 2;   // built-in blue LED (active low)
// ----------------------------------

WiFiClientSecure tlsClient;   // for TLS
WiFiClient plainClient;       // for non-TLS (local)

bool wsConnected = false;

// Forward declarations
void sendWebSocketMessage(String message);
bool readWebSocketFrame(String &out);
bool connectWebSocket();

// ───────────────────────────────────────────
//  SEND a masked WebSocket text frame
// ───────────────────────────────────────────
void sendWebSocketMessage(String message) {
  if (!wsConnected) return;

  // Pick the right client
  WiFiClient* client = USE_TLS ? (WiFiClient*)&tlsClient : (WiFiClient*)&plainClient;

  // Generate random 4-byte mask
  uint8_t maskKey[4];
  for (int i = 0; i < 4; i++) {
    maskKey[i] = random(0, 256);
  }

  // FIN + opcode for text frame
  client->write(0x81);

  // Payload length with MASK bit set
  size_t len = message.length();
  if (len < 126) {
    client->write(0x80 | len);
  } else if (len < 65536) {
    client->write(0x80 | 126);
    client->write((len >> 8) & 0xFF);
    client->write(len & 0xFF);
  } else {
    return; // payload too big for this simple impl
  }

  // 4-byte mask key
  client->write(maskKey, 4);

  // Masked payload
  for (size_t i = 0; i < len; i++) {
    client->write(message[i] ^ maskKey[i % 4]);
  }
}

// ───────────────────────────────────────────
//  READ a WebSocket frame (server → client)
// ───────────────────────────────────────────
bool readWebSocketFrame(String &out) {
  WiFiClient* client = USE_TLS ? (WiFiClient*)&tlsClient : (WiFiClient*)&plainClient;

  if (!client->connected() || client->available() < 2) return false;

  // Read first byte (FIN + opcode)
  byte b0 = client->read();
  byte opcode = b0 & 0x0F;
  if (opcode == 0x8) {  // Connection close
    wsConnected = false;
    return false;
  }

  // Read second byte (MASK + payload length)
  byte b1 = client->read();
  bool masked = b1 & 0x80;
  uint64_t len = b1 & 0x7F;
  if (len == 126) {
    len = client->read() << 8;
    len |= client->read();
  } else if (len == 127) {
    len = 0;
    for (int i = 0; i < 8; i++) len = (len << 8) | client->read();
  }

  // Read payload (server frames are NOT masked)
  out = "";
  for (uint64_t i = 0; i < len; i++) {
    if (client->available()) {
      char c = client->read();
      out += c;
    }
  }
  return true;
}

// ───────────────────────────────────────────
//  CONNECT & do WebSocket upgrade
// ───────────────────────────────────────────
bool connectWebSocket() {
  if (USE_TLS) {
    tlsClient.setInsecure();   // skip certificate validation
    if (!tlsClient.connect(ws_host, ws_port)) {
      Serial.println("TLS connect failed");
      return false;
    }
  } else {
    if (!plainClient.connect(ws_host, ws_port)) {
      Serial.println("TCP connect failed");
      return false;
    }
  }

  Serial.println("TCP/TLS connected, sending WebSocket upgrade...");

  WiFiClient* client = USE_TLS ? (WiFiClient*)&tlsClient : (WiFiClient*)&plainClient;

  // Build upgrade request with token
  String req = "GET /?token=" + String(authToken) + " HTTP/1.1\r\n";
  req += "Host: " + String(ws_host) + "\r\n";
  req += "Upgrade: websocket\r\n";
  req += "Connection: Upgrade\r\n";
  req += "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
  req += "Sec-WebSocket-Version: 13\r\n";
  req += "\r\n";
  client->print(req);

  // Wait for 101 response
  unsigned long start = millis();
  String response = "";
  while (client->connected() && millis() - start < 5000) {
    while (client->available()) {
      char c = client->read();
      response += c;
    }
    if (response.indexOf("\r\n\r\n") != -1) {
      if (response.indexOf("HTTP/1.1 101") != -1 || response.indexOf("HTTP/1.0 101") != -1) {
        wsConnected = true;
        Serial.println("WebSocket upgrade successful");
        return true;
      } else {
        Serial.println("Upgrade failed: " + response.substring(0, response.indexOf("\r\n")));
        client->stop();
        return false;
      }
    }
    delay(10);
  }
  Serial.println("Timeout waiting for upgrade response");
  client->stop();
  return false;
}

// ───────────────────────────────────────────
//  SETUP
// ───────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH); // LED off (active low)

  // Wi-Fi
  WiFi.begin(ssid, password);
  Serial.print("Connecting to Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi OK. IP: " + WiFi.localIP().toString());

  // Seed random for masking
  randomSeed(analogRead(0));

  // First connection
  if (connectWebSocket()) {
    // Send registration
    DynamicJsonDocument reg(200);
    reg["type"] = "register";
    reg["node_id"] = node_id;
    String regMsg;
    serializeJson(reg, regMsg);
    sendWebSocketMessage(regMsg);
    Serial.println("Registered: " + regMsg);
  }
}

// ───────────────────────────────────────────
//  MAIN LOOP
// ───────────────────────────────────────────
void loop() {
  // Reconnect if disconnected
  if (!wsConnected) {
    static unsigned long lastReconnect = 0;
    if (millis() - lastReconnect > 5000) {
      lastReconnect = millis();
      WiFiClient* client = USE_TLS ? (WiFiClient*)&tlsClient : (WiFiClient*)&plainClient;
      if (client->connected()) client->stop();
      Serial.println("Reconnecting...");
      if (connectWebSocket()) {
        DynamicJsonDocument reg(200);
        reg["type"] = "register";
        reg["node_id"] = node_id;
        String regMsg;
        serializeJson(reg, regMsg);
        sendWebSocketMessage(regMsg);
      }
    }
  }

  // Read incoming WebSocket frames
  if (wsConnected) {
    String msg;
    if (readWebSocketFrame(msg)) {
      Serial.println("Received: " + msg);

      // Parse JSON
      DynamicJsonDocument doc(200);
      DeserializationError err = deserializeJson(doc, msg);
      if (!err) {
        const char* command = doc["command"];
        if (command) {
          if (strcmp(command, "on") == 0) {
            digitalWrite(LED_PIN, HIGH);  // turn on  (active‑high)
            sendWebSocketMessage("{\"status\":\"on\"}");
            Serial.println("LED ON");
          } else if (strcmp(command, "off") == 0) {
            digitalWrite(LED_PIN, LOW);   // turn off (active‑high)
            sendWebSocketMessage("{\"status\":\"off\"}");
            Serial.println("LED OFF");
          }
        }
      }
    }
  }
}