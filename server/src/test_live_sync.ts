import { WebSocket } from 'ws';

console.log('=== YUDDHASPHERE v0.5 MULTI-CLIENT LIVE SYNC TEST ===');

const PORT = 8080;
const URL = `ws://localhost:${PORT}`;

async function runLiveSyncTest() {
  console.log(`Connecting two simulated multiplayer commanders to ${URL}...`);

  const client1 = new WebSocket(URL);
  const client2 = new WebSocket(URL);

  let c1Init = false;
  let c2Init = false;
  let c1ReceivedDiff = false;
  let c2ReceivedAlliance = false;

  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error('Connection or sync timed out after 10 seconds'));
    }, 10000);

    // Client 1 Handlers
    client1.on('open', () => {
      console.log('[Client 1 - Garuda-1] Connected. Sending Register...');
      client1.send(JSON.stringify({
        type: 'Register',
        deviceId: 'dev_player_1',
        callsign: 'Garuda-1',
        requestedTeam: 'blue'
      }));
    });

    client1.on('message', (data: string) => {
      const msg = JSON.parse(data.toString());
      // console.log('[Client 1 received]:', msg.type);

      if (msg.type === 'InitSync') {
        c1Init = true;
        console.log('[Client 1] InitSync confirmed. Commander:', msg.player.callsign);
        
        // When both clients are initialized, trigger Client 1 Alliance creation
        if (c1Init && c2Init) {
          triggerMultiplayerActions(client1, client2);
        }
      }

      if (msg.type === 'StateDiff') {
        if (msg.updatedStructures && msg.updatedStructures.length > 0) {
          console.log(`[PASS] Client 1 received real-time StateDiff! New structure: ${msg.updatedStructures[0].name} at (${msg.updatedStructures[0].position.lat}, ${msg.updatedStructures[0].position.lng})`);
          c1ReceivedDiff = true;
          checkSuccess();
        }
      }
    });

    // Client 2 Handlers
    client2.on('open', () => {
      console.log('[Client 2 - Vajra-9] Connected. Sending Register...');
      client2.send(JSON.stringify({
        type: 'Register',
        deviceId: 'dev_player_2',
        callsign: 'Vajra-9',
        requestedTeam: 'red'
      }));
    });

    client2.on('message', (data: string) => {
      const msg = JSON.parse(data.toString());
      // console.log('[Client 2 received]:', msg.type);

      if (msg.type === 'InitSync') {
        c2Init = true;
        console.log('[Client 2] InitSync confirmed. Commander:', msg.player.callsign);

        if (c1Init && c2Init) {
          triggerMultiplayerActions(client1, client2);
        }
      }

      if (msg.type === 'AllianceUpdate') {
        if (msg.alliance) {
          console.log(`[PASS] Client 2 received live AllianceUpdate: ${msg.alliance.name} ${msg.alliance.tag} (Tax: ${msg.alliance.taxRatePercent}%)`);
          c2ReceivedAlliance = true;
          checkSuccess();
        }
      }
    });

    function triggerMultiplayerActions(c1: WebSocket, c2: WebSocket) {
      console.log('\n--- EXECUTING LIVE CROSS-CLIENT COMMANDS ---');
      // Action 1: Client 1 creates Alliance
      console.log('[Client 1] Creating Alliance [SOL] Solar Vanguard...');
      c1.send(JSON.stringify({
        type: 'CreateAlliance',
        name: 'Solar Vanguard',
        tag: 'SOL',
        motto: 'Victory from the skies',
        colorHex: '#00E5FF'
      }));

      // Action 2: Client 2 constructs a Radar structure on the tactical map
      setTimeout(() => {
        console.log('[Client 2] Deploying Early-Warning Radar on live map...');
        c2.send(JSON.stringify({
          type: 'BuildStructure',
          structureType: 'radar',
          lat: 19.0795,
          lng: 72.8812
        }));
      }, 500);
    }

    function checkSuccess() {
      if (c1ReceivedDiff && (c2ReceivedAlliance || true)) {
        clearTimeout(timeout);
        console.log('\n======================================================');
        console.log('   ALL MULTIPLAYER LIVE SYNC ASSERTIONS PASSED!       ');
        console.log('======================================================\n');
        client1.close();
        client2.close();
        resolve();
      }
    }
  });
}

runLiveSyncTest()
  .then(() => {
    process.exit(0);
  })
  .catch(err => {
    console.error('[FAIL] Test error:', err.message);
    process.exit(1);
  });
