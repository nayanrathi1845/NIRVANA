// NIRVANA's connection to Firebase: login (email + password) and the shared database.
// The rest of the site only talks to the small interface returned here.

const V = "10.14.1";
const BASE = `https://www.gstatic.com/firebasejs/${V}`;

export const firebaseConfig = {
  apiKey: "AIzaSyC_-YG21tRDjEU6MSbP1nUOnh6u58ULApg",
  authDomain: "nirvana-2e2d1.firebaseapp.com",
  projectId: "nirvana-2e2d1",
  storageBucket: "nirvana-2e2d1.firebasestorage.app",
  messagingSenderId: "506379684559",
  appId: "1:506379684559:web:1edc35cb5320c0f885c5ba"
};

export async function makeBackend() {
  const [{ initializeApp }, A, F] = await Promise.all([
    import(`${BASE}/firebase-app.js`),
    import(`${BASE}/firebase-auth.js`),
    import(`${BASE}/firebase-firestore.js`),
  ]);
  const app = initializeApp(firebaseConfig);
  const auth = A.getAuth(app);
  await A.setPersistence(auth, A.browserLocalPersistence);
  const db = F.getFirestore(app);

  const asMap = snap => { const o = {}; snap.forEach(d => { o[d.id] = d.data(); }); return o; };

  return {
    onAuth(cb) {
      return A.onAuthStateChanged(auth, u => cb(u ? { uid: u.uid, email: u.email } : null));
    },
    signUp: (email, pw) => A.createUserWithEmailAndPassword(auth, email, pw),
    signIn: (email, pw) => A.signInWithEmailAndPassword(auth, email, pw),
    signOut: () => A.signOut(auth),
    resetPassword: email => A.sendPasswordResetEmail(auth, email),

    // Resolves to the profile, or null when this account hasn't joined yet
    // (the rules hide everything from non-members, so "not allowed" also means "not joined").
    async getProfile(uid) {
      try {
        const s = await F.getDoc(F.doc(db, "users", uid));
        return s.exists() ? s.data() : null;
      } catch (e) {
        if (e && e.code === "permission-denied") return null;
        throw e;
      }
    },
    createProfile: (uid, data) => F.setDoc(F.doc(db, "users", uid), data),
    updateProfile: (uid, data) => F.setDoc(F.doc(db, "users", uid), data, { merge: true }),
    setAudit: (uid, week, on) => F.updateDoc(F.doc(db, "users", uid), { ["audit." + week]: on }),

    watchUsers(cb, err) {
      return F.onSnapshot(F.collection(db, "users"), s => cb(asMap(s)), err);
    },
    watchDays(uid, cb, err) {
      return F.onSnapshot(F.collection(db, "users", uid, "days"), s => cb(asMap(s)), err);
    },
    setDay: (uid, date, data) => F.setDoc(F.doc(db, "users", uid, "days", date), data, { merge: true }),
    deleteDay: (uid, date) => F.deleteDoc(F.doc(db, "users", uid, "days", date)),
  };
}
