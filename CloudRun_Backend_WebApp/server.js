require('dotenv').config();
const express = require('express');
const { WebSocketServer } = require('ws');
const path = require('path');
const { OAuth2Client } = require('google-auth-library');
const { google } = require('googleapis');
const { getRows, appendRow } = require('./sheets');

const app = express();
const PORT = 8080;

// ---------- Config ----------
const SPREADSHEET_ID = process.env.SPREADSHEET_ID;   // your sheet ID
const AUTH_TOKEN = process.env.AUTH_TOKEN || 'rak123';   // for ESP32
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID;
if (!GOOGLE_CLIENT_ID) {
  console.error('GOOGLE_CLIENT_ID environment variable is required');
  process.exit(1);
}

// ---------- Sheets helpers ----------
const auth = new google.auth.GoogleAuth({
  scopes: ['https://www.googleapis.com/auth/spreadsheets'],
});
const sheets = google.sheets({ version: 'v4', auth });

// async function getRows(sheetName) { /* same as before, returns array of objects */ }
// async function appendRow(sheetName, values) { /* same as before */ }

// ---------- OAuth client ----------
const oauthClient = new OAuth2Client(GOOGLE_CLIENT_ID);

// ---- Allowed users check (using allowed_users sheet) ----
async function isUserAllowed(email) {
  const rows = await getRows('allowed_users');
  return rows.some(row => row.email === email);
}

// Middleware to verify Google ID token
async function authMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing authorization header' });
  }
  const idToken = authHeader.split(' ')[1];
  try {
    const ticket = await oauthClient.verifyIdToken({
      idToken,
      audience: GOOGLE_CLIENT_ID,
    });
    const payload = ticket.getPayload();
    const email = payload.email;

    // Check if user is in the allowed_users sheet
    const allowed = await isUserAllowed(email);
    if (!allowed) {
      return res.status(403).json({ error: 'Access denied' });
    }

    req.userEmail = email;
    next();
  } catch (err) {
    console.error('Token verification error:', err.message);
    res.status(401).json({ error: 'Invalid token' });
  }
}

// ---------- WebSocket server ----------
const wss = new WebSocketServer({ noServer: true });
let esp32Socket = null;
let pendingCommand = { timestamp: null, userEmail: null, command: null };

wss.on('connection', (ws, request) => {
  // Authenticate ESP32 via token in query string
  const url = new URL(request.url, `http://${request.headers.host}`);
  const token = url.searchParams.get('token');
  if (token !== AUTH_TOKEN) {
    ws.close(4001, 'Unauthorized');
    return;
  }
  console.log('ESP32 authenticated and connected');
  esp32Socket = ws;

  ws.on('message', (msg) => {
    console.log('ESP32:', msg.toString());
    try {
      const data = JSON.parse(msg);
      // If it's a status response ("on" or "off") and we have a pending command
      if (data.status && pendingCommand.timestamp) {
        const latencyMs = Date.now() - pendingCommand.timestamp;
        logAction(pendingCommand.userEmail, pendingCommand.command, 'ok', latencyMs)
          .catch(console.error);
        pendingCommand = {}; // reset
      }
    } catch (e) {
      console.error('Error parsing ESP32 message:', e);
    }
  });

  ws.on('close', () => {
    console.log('ESP32 disconnected');
    esp32Socket = null;
  });
});

// ---------- Serve static frontend ----------
app.use(express.static(path.join(__dirname, 'public')));

// ---------- Config endpoint for frontend ----------
app.get('/api/config', (req, res) => {
  res.json({ googleClientId: GOOGLE_CLIENT_ID });
});

// ---------- API: Toggle LED (authenticated user) ----------
app.post('/api/led', authMiddleware, express.json(), async (req, res) => {
  const { command } = req.body; // "on" or "off"
  if (!esp32Socket) {
    // No need to await here either – respond immediately
    logAction(req.userEmail, command, 'error - no ESP32').catch(console.error);
    return res.status(503).json({ error: 'No ESP32 connected' });
  }

  // Send command to ESP32 instantly
  esp32Socket.send(JSON.stringify({ command }));

  // Respond to the browser immediately – no waiting
  res.json({ status: 'ok' });

  // Store the pending command to measure latency later
  pendingCommand = {
    userEmail: req.userEmail,
    command: command,
    timestamp: Date.now()
  };
});

// ---------- Helper: log to sheet ----------
async function logAction(userEmail, command, status, latencyMs = null) {
  const timestamp = new Date().toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' });
  const row = [
    timestamp,
    userEmail,
    command,
    'esp32-1',
    status,
    latencyMs !== null ? String(latencyMs) : ''
  ];
  await appendRow('logs', row);
}

app.post('/api/verify', authMiddleware, (req, res) => {
  res.json({ allowed: true, email: req.userEmail });
});

// ---------- Upgrade HTTP to WebSocket ----------
const server = app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});
server.on('upgrade', (request, socket, head) => {
  wss.handleUpgrade(request, socket, head, (ws) => {
    wss.emit('connection', ws, request);
  });
});
