const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";
const EVE_UID = "eve_uid_999";
const COUPLE_ROOM_ID = "room_alice_bob";
const CALL_ROOM_ID = "call_alice_bob";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function getAuthedDb(uid, token = {}) {
  return testEnv.authenticatedContext(uid, token).firestore();
}

function getUnauthDb() {
  return testEnv.unauthenticatedContext().firestore();
}

function pastTimestamp() {
  return new Date(Date.now() - 5000);
}

async function seedCoupleAndCallRoom() {
  const ts = pastTimestamp();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("couple_rooms").doc(COUPLE_ROOM_ID).set({
      roomId: COUPLE_ROOM_ID,
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID],
      myName: "Alice",
      partnerName: "Bob",
      streakCount: 5,
      totalScore: 42,
      createdAt: ts,
      updatedAt: ts,
    });
    await adminDb.collection("call_rooms").doc(CALL_ROOM_ID).set({
      roomId: CALL_ROOM_ID,
      coupleRoomId: COUPLE_ROOM_ID,
      hostUid: ALICE_UID,
      title: "Date Night Call",
      callType: "VIDEO",
      status: "active",
      layoutMode: "GRID",
      autoCamSwitch: true,
      hideNonVideo: false,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: ts,
      updatedAt: ts,
    });
  });
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// 1. Default Deny & Unauthenticated Access
test("Unauthenticated user: denied read/write on users, couple_rooms, call_rooms", async () => {
  const unauthDb = getUnauthDb();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("couple_rooms").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("call_rooms").where("memberUids", "array-contains", ALICE_UID).get());
});

// 2. PII Isolation & Privilege Escalation Guard
test("User creation: owner succeeds with role=user, attacker blocked from setting role=admin or reading partner PII", async () => {
  const aliceDb = getAuthedDb(ALICE_UID, { email: "alice@example.com", email_verified: true });
  const bobDb = getAuthedDb(BOB_UID, { email: "bob@example.com", email_verified: true });
  const ts = pastTimestamp();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set({
    uid: ALICE_UID,
    email: "alice@example.com",
    username: "Alice",
    inviteCode: "HS-1122",
    role: "user",
    createdAt: ts,
    updatedAt: ts,
  }));

  // Shadow field / privilege escalation fails
  await assertFails(bobDb.collection("users").doc(BOB_UID).set({
    uid: BOB_UID,
    email: "bob@example.com",
    username: "Bob",
    inviteCode: "HS-3344",
    role: "admin",
    createdAt: ts,
    updatedAt: ts,
  }));

  // Cross-user PII read fails
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
});

// 3. Shadow Update & Value Poisoning Tests
test("Shadow Update & Ghost field blocked on users", async () => {
  const aliceDb = getAuthedDb(ALICE_UID);
  const ts = pastTimestamp();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set({
    uid: ALICE_UID,
    email: "alice@example.com",
    username: "Alice",
    inviteCode: "HS-1122",
    createdAt: ts,
    updatedAt: ts,
  }));

  // Ghost field rejected
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({
    username: "Alice Updated",
    isVerifiedByAdmin: true,
    updatedAt: new Date(),
  }));

  // Value poisoning (invalid type / oversize string) rejected
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({
    username: "A".repeat(100),
    updatedAt: new Date(),
  }));
});

// 4. Email Spoofing Test for Admin Escape Hatch
test("Admin spoof with email_verified=false is rejected", async () => {
  const aliceDb = getAuthedDb(ALICE_UID);
  const ts = pastTimestamp();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set({
    uid: ALICE_UID,
    email: "alice@example.com",
    username: "Alice",
    inviteCode: "HS-1122",
    createdAt: ts,
    updatedAt: ts,
  }));

  const spoofAdminDb = getAuthedDb(EVE_UID, { email: "dezacodex@gmail.com", email_verified: false });
  await assertFails(spoofAdminDb.collection("users").doc(ALICE_UID).get());
});

// 5. Couple Room & Messages/Snaps/Stories Access + Query Alignment
test("Paired couple can exchange messages while third party Eve is denied", async () => {
  await seedCoupleAndCallRoom();
  const aliceDb = getAuthedDb(ALICE_UID);
  const bobDb = getAuthedDb(BOB_UID);
  const eveDb = getAuthedDb(EVE_UID);
  const ts = pastTimestamp();

  // Alice sends message
  await assertSucceeds(aliceDb.collection("couple_rooms").doc(COUPLE_ROOM_ID)
    .collection("messages").doc("msg_1").set({
      id: "msg_1",
      roomId: COUPLE_ROOM_ID,
      senderUid: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      text: "Hello love!",
      isDisappearing: false,
      createdAt: ts,
    }));

  // Bob queries messages with matching array-contains filter
  await assertSucceeds(bobDb.collection("couple_rooms").doc(COUPLE_ROOM_ID)
    .collection("messages").where("memberUids", "array-contains", BOB_UID).get());

  // Unfiltered list query denied for non-member (Rules are not filters)
  await assertFails(eveDb.collection("couple_rooms").doc(COUPLE_ROOM_ID)
    .collection("messages").get());

  // Eve blocked from get, cross-user list query, and write
  await assertFails(eveDb.collection("couple_rooms").doc(COUPLE_ROOM_ID).get());
  await assertFails(eveDb.collection("couple_rooms").doc(COUPLE_ROOM_ID)
    .collection("messages").doc("msg_1").get());
  await assertFails(eveDb.collection("couple_rooms").doc(COUPLE_ROOM_ID)
    .collection("messages").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(eveDb.collection("couple_rooms").doc(COUPLE_ROOM_ID)
    .collection("messages").doc("msg_eve").set({
      id: "msg_eve",
      roomId: COUPLE_ROOM_ID,
      senderUid: EVE_UID,
      senderName: "Eve",
      memberUids: [ALICE_UID, BOB_UID, EVE_UID],
      text: "Intrusion",
      isDisappearing: false,
      createdAt: ts,
    }));
});

// 6. Discord-style Multi-Stream Video Call Rooms, Participants & Signals
test("Call room enforces member access, terminal ended state lock, and P2P signal isolation", async () => {
  await seedCoupleAndCallRoom();
  const aliceDb = getAuthedDb(ALICE_UID);
  const bobDb = getAuthedDb(BOB_UID);
  const eveDb = getAuthedDb(EVE_UID);
  const ts = pastTimestamp();

  // Alice publishes camera + screen participant state
  await assertSucceeds(aliceDb.collection("call_rooms").doc(CALL_ROOM_ID)
    .collection("participants").doc(ALICE_UID).set({
      uid: ALICE_UID,
      callRoomId: CALL_ROOM_ID,
      memberUids: [ALICE_UID, BOB_UID],
      displayName: "Alice",
      isAudioMuted: false,
      isCameraEnabled: true,
      isScreenSharing: true,
      screenAudioEnabled: true,
      isSpeaking: true,
      audioLevelDb: -24.5,
      videoQuality: "simulcast",
      joinedAt: ts,
      updatedAt: ts,
    }));

  // Bob cannot spoof Alice's participant doc
  await assertFails(bobDb.collection("call_rooms").doc(CALL_ROOM_ID)
    .collection("participants").doc(ALICE_UID).update({
      isAudioMuted: true,
      updatedAt: new Date(),
    }));

  // Alice sends SDP offer for SCREEN stream to Bob
  await assertSucceeds(aliceDb.collection("call_rooms").doc(CALL_ROOM_ID)
    .collection("signals").doc("sig_1").set({
      id: "sig_1",
      callRoomId: CALL_ROOM_ID,
      fromUid: ALICE_UID,
      toUid: BOB_UID,
      participantUids: [ALICE_UID, BOB_UID],
      streamKind: "SCREEN",
      signalType: "OFFER",
      sdp: "v=0...",
      createdAt: ts,
    }));

  // Eve cannot intercept signals
  await assertFails(eveDb.collection("call_rooms").doc(CALL_ROOM_ID)
    .collection("signals").doc("sig_1").get());

  // End call room -> terminal state locks subsequent updates
  await assertSucceeds(aliceDb.collection("call_rooms").doc(CALL_ROOM_ID).update({
    status: "ended",
    endedAt: new Date(),
    updatedAt: new Date(),
  }));

  await assertFails(bobDb.collection("call_rooms").doc(CALL_ROOM_ID).update({
    layoutMode: "FOCUS",
    updatedAt: new Date(),
  }));
});

// 7. Notifications Subcollection Access Control
test("Partner can send notification into user notifications subcollection only when sharing a valid coupleRoomId", async () => {
  await seedCoupleAndCallRoom();
  const bobDb = getAuthedDb(BOB_UID);
  const eveDb = getAuthedDb(EVE_UID);
  const ts = pastTimestamp();

  await assertSucceeds(bobDb.collection("users").doc(ALICE_UID)
    .collection("notifications").doc("notif_1").set({
      id: "notif_1",
      senderUid: BOB_UID,
      senderName: "Bob",
      coupleRoomId: COUPLE_ROOM_ID,
      type: "CALL",
      title: "Incoming Video Call",
      body: "Bob is calling you",
      isRead: false,
      createdAt: ts,
    }));

  // Eve cannot inject notifications into Alice's inbox
  await assertFails(eveDb.collection("users").doc(ALICE_UID)
    .collection("notifications").doc("notif_2").set({
      id: "notif_2",
      senderUid: EVE_UID,
      senderName: "Eve",
      coupleRoomId: COUPLE_ROOM_ID,
      type: "MESSAGE",
      title: "Spam",
      body: "Spam",
      isRead: false,
      createdAt: ts,
    }));
});
