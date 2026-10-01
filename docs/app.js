// NIRVANA website: login, logging a day, standings and profile.
// The backend (Firebase, or a test stand-in) is injected so this file never talks to Firebase directly.

const $ = s => document.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const pad = n => String(n).padStart(2,"0");
const fmt = d => d.getFullYear()+"-"+pad(d.getMonth()+1)+"-"+pad(d.getDate());
const parse = s => { const [y,m,d] = s.split("-").map(Number); return new Date(y,m-1,d); };
const addDays = (s,n) => { const d = parse(s); d.setDate(d.getDate()+n); return fmt(d); };
const today = () => fmt(new Date());
const weekStart = s => { const d = parse(s); d.setDate(d.getDate() - ((d.getDay()+6)%7)); return fmt(d); };
const weekDays = wk => Array.from({length:7}, (_,i) => addDays(wk,i));
const monthDays = m => { const [y,mo] = m.split("-").map(Number); const n = new Date(y,mo,0).getDate(); return Array.from({length:n},(_,i)=>m+"-"+pad(i+1)); };
const niceDate = s => parse(s).toLocaleDateString(undefined,{weekday:"short",day:"numeric",month:"short"});
const niceMonth = m => parse(m+"-01").toLocaleDateString(undefined,{month:"long",year:"numeric"});
const lsGet = k => { try { return localStorage.getItem(k); } catch { return null; } };
const lsSet = (k,v) => { try { localStorage.setItem(k,v); } catch {} };
let toastT; const toast = msg => { const t=$("#toast"); t.textContent=msg; t.hidden=false; clearTimeout(toastT); toastT=setTimeout(()=>t.hidden=true,2600); };

const S = { be:null, me:null, email:"", profile:null, players:{}, entries:{}, daySubs:{}, unsubs:[],
  date: today(), tab: lsGet("tab") || "log", period: lsGet("period") || "daily", dirty:false, confirmDel:false, authMode:"signin" };

/* ---------- scoring (same rules as the Android app) ---------- */
function lin(v, best, base, max){
  if (v == null || isNaN(v)) return 0;
  if (v <= best) return max;
  if (base <= best || v >= base) return 0;
  return max * (base - v) / (base - best);
}
function dailyScore(e, p){
  if (!e || !p) return null;
  const r = {time:0, opens:0, prot:0, focus:0, div:0, pen:0};
  const bm = +p.baseMin || 0, bo = +p.baseOpens || 0;
  if (e.shot){ r.time = lin(+e.min, 15, bm, 35); r.opens = lin(+e.opens, 5, bo, 15); }
  r.prot = (e.am?5:0) + (e.pm?5:0);
  r.focus = 10 * Math.min(2, +e.focus || 0);
  r.div = 10 * Math.min(2, +e.div || 0);
  if (+e.min > 60) r.pen = -15;
  r.total = Math.max(0, Math.round(r.time + r.opens + r.prot + r.focus + r.div + r.pen));
  return r;
}
const entry = (pid, d) => S.entries[pid+"_"+d];
function weekScore(pid, wk){
  const p = S.players[pid]; let sum=0, good=0, zero=false, focus=0, logged=0;
  for (const d of weekDays(wk)){
    const e = entry(pid,d); const r = dailyScore(e,p); if (!r) continue;
    logged++; sum += r.total; if (r.total >= 70) good++; focus += Math.min(2, +e.focus||0);
    if (e.shot && +e.min === 0) zero = true;
  }
  const audit = !!((p && p.audit) || {})[wk];
  const streak = good >= 5 ? 20 : 0, zb = zero ? 15 : 0, ab = audit ? 10 : 0;
  return { daily:sum, streak, zero:zb, audit:ab, bonus: streak+zb+ab, total: sum+streak+zb+ab, good, focus, logged };
}
function reductionPct(pid, month){
  const bm = +S.players[pid].baseMin || 0; if (!bm) return null;
  const es = monthDays(month).map(d => entry(pid,d)).filter(e => e && e.shot).slice(-7);
  if (es.length < 3) return null;
  const avg = es.reduce((a,e)=>a+(+e.min||0),0) / es.length;
  return { pct: (bm - avg) / bm * 100, avg };
}
function monthScores(month){
  const ids = Object.keys(S.players); const t = today();
  const mondays = monthDays(month).filter(d => parse(d).getDay() === 1);
  const res = {};
  for (const pid of ids){
    let daily = 0, logged = 0;
    for (const d of monthDays(month)){ const r = dailyScore(entry(pid,d), S.players[pid]); if (r){ daily += r.total; logged++; } }
    let wbonus = 0; const completed = [];
    for (const wk of mondays){ const w = weekScore(pid, wk); wbonus += w.bonus; if (addDays(wk,6) <= t) completed.push(w.total); }
    const consistency = completed.length && completed.every(x => x > 400) ? 25 : 0;
    res[pid] = { daily, wbonus, consistency, red: reductionPct(pid, month), logged, reduction:0 };
  }
  const reds = ids.map(id => res[id].red).filter(Boolean).map(r => r.pct);
  const best = reds.length ? Math.max(...reds) : null;
  for (const pid of ids){
    const r = res[pid];
    if (best != null && best > 0 && r.red && Math.abs(r.red.pct - best) < 1e-9) r.reduction = 50;
    r.total = r.daily + r.wbonus + r.consistency + r.reduction;
  }
  return res;
}

/* ---------- shell ---------- */
function showShell(on){
  $("#shell").hidden = !on; $("#gate").hidden = on;
}
function setTab(t){
  S.tab = t; lsSet("tab", t); S.confirmDel = false;
  document.querySelectorAll("nav.tabs button").forEach(b => b.setAttribute("aria-selected", b.dataset.tab === t));
  render(true);
}
function setDate(d){ if (!d) return; S.date = d; $("#dateInput").value = d; S.dirty = false; S.confirmDel = false; render(true); }
function sortedPlayers(){ return Object.entries(S.players).sort((a,b)=>(a[1].name||"").localeCompare(b[1].name||"")); }
function render(force){
  if (!S.profile) return;
  const v = $("#view");
  $("#who").textContent = S.profile.name;
  if (S.tab === "log"){ if (force || !S.dirty) renderLog(v); }
  else if (S.tab === "standings") renderStandings(v);
  else if (S.tab === "profile"){ if (force || !v.contains(document.activeElement) || document.activeElement.tagName === "BUTTON") renderProfile(v); }
  else renderRules(v);
}

/* ---------- auth gate ---------- */
function authError(e){
  const c = (e && e.code) || "";
  if (c.includes("invalid-credential") || c.includes("wrong-password") || c.includes("user-not-found")) return "That email and password don't match. Try again or reset your password.";
  if (c.includes("email-already-in-use")) return "An account with this email already exists. Sign in instead.";
  if (c.includes("weak-password")) return "Use a password of at least 6 characters.";
  if (c.includes("invalid-email")) return "That doesn't look like an email address.";
  if (c.includes("too-many-requests")) return "Too many attempts. Wait a few minutes and try again.";
  if (c.includes("network")) return "No connection. Check your internet and try again.";
  return "Something went wrong. Try again.";
}
function renderAuth(){
  const up = S.authMode === "signup";
  $("#gate").innerHTML = `
  <div class="card panel auth">
    <div class="seg" role="group" aria-label="Sign in or create account">
      <button id="m-in" aria-pressed="${!up}">Sign in</button><button id="m-up" aria-pressed="${up}">Create account</button>
    </div>
    <form id="authForm" class="panel" novalidate>
      <div class="field"><label for="a-email">Email</label><input id="a-email" type="email" autocomplete="email" inputmode="email" required></div>
      <div class="field"><label for="a-pw">Password</label><input id="a-pw" type="password" autocomplete="${up ? "new-password" : "current-password"}" minlength="6" required>
        ${up ? `<span class="hint">At least 6 characters.</span>` : ""}</div>
      <p id="a-err" class="err" hidden></p>
      <button class="primary" id="a-go" type="submit">${up ? "Create account" : "Sign in"}</button>
      ${up ? "" : `<button class="ghost linkish" id="a-reset" type="button">Forgot password?</button>`}
    </form>
  </div>`;
  $("#m-in").onclick = () => { S.authMode = "signin"; renderAuth(); };
  $("#m-up").onclick = () => { S.authMode = "signup"; renderAuth(); };
  const err = m => { const p = $("#a-err"); p.textContent = m; p.hidden = !m; };
  $("#authForm").onsubmit = async ev => {
    ev.preventDefault(); err("");
    const email = $("#a-email").value.trim(), pw = $("#a-pw").value;
    if (!email || !pw){ err("Enter your email and password."); return; }
    const b = $("#a-go"); b.disabled = true;
    try { up ? await S.be.signUp(email, pw) : await S.be.signIn(email, pw); }
    catch(e){
      if (up && e && String(e.code).includes("email-already-in-use")){ S.authMode = "signin"; renderAuth(); $("#a-email").value = email; err("You already have an account. Sign in with your password."); return; }
      err(authError(e)); b.disabled = false;
    }
  };
  const r = $("#a-reset");
  if (r) r.onclick = async () => {
    const email = $("#a-email").value.trim();
    if (!email){ err("Enter your email above, then tap Forgot password."); return; }
    try { await S.be.resetPassword(email); toast("Password reset email sent to " + email); } catch(e){ err(authError(e)); }
  };
}
function renderJoin(){
  $("#gate").innerHTML = `
  <div class="card panel auth">
    <div><h2>Join the league</h2><p class="hint" style="margin:6px 0 0">Signed in as ${esc(S.email)}. Enter the invite code Nayan shared, your name, and your Week 0 baseline: your average daily Instagram minutes and app opens from a normal week.</p></div>
    <form id="joinForm" class="panel" novalidate>
      <div class="field"><label for="j-code">Invite code</label><input id="j-code" autocomplete="off" autocapitalize="none" placeholder="word-1234" required></div>
      <div class="field"><label for="j-name">Your name</label><input id="j-name" maxlength="40" autocomplete="given-name" required></div>
      <div class="grid2">
        <div class="field"><label for="j-min">Minutes / day</label><input id="j-min" type="number" min="1" inputmode="numeric" class="num" placeholder="120"></div>
        <div class="field"><label for="j-opens">Opens / day</label><input id="j-opens" type="number" min="0" inputmode="numeric" class="num" placeholder="40"></div>
      </div>
      <p id="j-err" class="err" hidden></p>
      <button class="primary" id="j-go" type="submit">Join NIRVANA</button>
      <button class="ghost linkish" id="j-out" type="button">Sign out</button>
    </form>
  </div>`;
  const err = m => { const p = $("#j-err"); p.textContent = m; p.hidden = !m; };
  $("#j-out").onclick = () => S.be.signOut();
  $("#joinForm").onsubmit = async ev => {
    ev.preventDefault(); err("");
    const code = $("#j-code").value.trim().toLowerCase(), name = $("#j-name").value.trim();
    const baseMin = Math.round(+$("#j-min").value || 0), baseOpens = Math.round(+$("#j-opens").value || 0);
    if (!code){ err("Enter the invite code."); return; }
    if (!name){ err("Enter your name."); return; }
    if (baseMin < 1){ err("Enter your baseline minutes per day."); return; }
    const b = $("#j-go"); b.disabled = true;
    try {
      const prof = { name, baseMin, baseOpens, joinCode: code, audit: {}, joinedAt: Date.now() };
      await S.be.createProfile(S.me, prof);
      enterLeague(prof);
    } catch(e){
      b.disabled = false;
      err(e && e.code === "permission-denied" ? "That invite code isn't right. Check it with Nayan." : "Couldn't join. Check your connection and try again.");
    }
  };
}

/* ---------- league ---------- */
function stopWatching(){ S.unsubs.forEach(u => { try { u(); } catch {} }); S.unsubs = []; S.daySubs = {}; S.players = {}; S.entries = {}; }
function enterLeague(profile){
  S.profile = profile; S.players[S.me] = profile;
  showShell(true);
  document.querySelectorAll("nav.tabs button").forEach(b => b.setAttribute("aria-selected", b.dataset.tab === S.tab));
  $("#dateInput").value = S.date;
  const onErr = () => toast("Lost connection to the league. Reload the page.");
  const watchDays = id => {
    if (S.daySubs[id]) return;
    S.daySubs[id] = true;
    S.unsubs.push(S.be.watchDays(id, days => {
      for (const k of Object.keys(S.entries)) if (k.startsWith(id+"_")) delete S.entries[k];
      for (const d in days) S.entries[id+"_"+d] = days[d];
      render(false);
    }, onErr));
  };
  S.unsubs.push(S.be.watchUsers(users => {
    S.players = users;
    if (users[S.me]) S.profile = users[S.me];
    Object.keys(users).forEach(watchDays);
    render(false);
  }, onErr));
  render(true);
  maybeInstallHint();
}

/* ---------- Log ---------- */
function renderLog(v){
  const e = entry(S.me, S.date) || {};
  const val = (k, d="") => e[k] ?? d;
  v.innerHTML = `
  <div class="card panel">
    <div class="row" style="justify-content:space-between">
      <h2>${esc(niceDate(S.date))}</h2>
      ${e.source === "app" ? `<span class="pill good">From the Android app</span>` : entry(S.me,S.date) ? `<span class="pill good">Logged</span>` : `<span class="pill">Not logged yet</span>`}
    </div>
    <p class="hint" style="margin:0">Baseline ${esc(S.profile.baseMin)} min · ${esc(S.profile.baseOpens)} opens a day</p>
    <h3>Screen time (Instagram + Shorts + Reels, combined)</h3>
    <div class="grid2">
      <div class="field"><label for="f-min">Short-form minutes</label><input id="f-min" type="number" min="0" inputmode="numeric" value="${esc(val("min"))}" placeholder="e.g. 45"></div>
      <div class="field"><label for="f-opens">App opens / pickups</label><input id="f-opens" type="number" min="0" inputmode="numeric" value="${esc(val("opens"))}" placeholder="e.g. 12"></div>
    </div>
    <label class="tog"><input type="checkbox" id="f-shot" ${e.shot?"checked":""}><span><b>Screenshot posted in the group</b><br><span class="hint">iPhone: Settings → Screen Time → See All App &amp; Website Activity. Without it, minutes and opens score 0.</span></span></label>
    <h3>Protected hours</h3>
    <div class="grid2">
      <label class="tog"><input type="checkbox" id="f-am" ${e.am?"checked":""}><span><b>No Instagram in the first hour after waking</b></span></label>
      <label class="tog"><input type="checkbox" id="f-pm" ${e.pm?"checked":""}><span><b>No Instagram in the last hour before sleep</b></span></label>
    </div>
    <h3>Focus and diverse input</h3>
    <div class="grid2">
      <div class="field"><span class="lbl">45-min focus blocks</span>
        <div class="stepper"><button class="icon" data-step="f-focus" data-d="-1" aria-label="Fewer focus blocks">&minus;</button><output id="f-focus">${+val("focus",0)}</output><button class="icon" data-step="f-focus" data-d="1" aria-label="More focus blocks">+</button><span class="hint">10 pts each, max 2</span></div></div>
      <div class="field"><span class="lbl">Diverse inputs</span>
        <div class="stepper"><button class="icon" data-step="f-div" data-d="-1" aria-label="Fewer inputs">&minus;</button><output id="f-div">${+val("div",0)}</output><button class="icon" data-step="f-div" data-d="1" aria-label="More inputs">+</button><span class="hint">Book 20+ pages, long read, podcast</span></div></div>
    </div>
    <div class="field"><label for="f-note">One-line takeaway</label><textarea id="f-note" rows="2" placeholder="What did you read or hear that you wouldn't see in your feed?">${esc(val("note"))}</textarea></div>
  </div>
  <div class="card">
    <div class="preview" id="preview"></div>
    <div class="row" style="margin-top:14px;justify-content:space-between">
      <div id="delZone"></div>
      <button class="primary" id="saveBtn">${entry(S.me,S.date) ? "Update day" : "Save day"}</button>
    </div>
  </div>`;
  const markDirty = () => { S.dirty = true; updatePreview(); };
  v.querySelectorAll("input,textarea").forEach(i => i.addEventListener("input", markDirty));
  v.querySelectorAll("input[type=checkbox]").forEach(i => i.addEventListener("change", markDirty));
  v.querySelectorAll("[data-step]").forEach(b => b.addEventListener("click", () => {
    const o = $("#"+b.dataset.step); o.value = o.textContent = Math.max(0, Math.min(6, (+o.textContent||0) + +b.dataset.d)); markDirty();
  }));
  $("#saveBtn").onclick = saveDay;
  renderDelZone();
  updatePreview();
}
function readForm(){
  const n = id => { const x = $("#"+id).value; return x === "" ? null : Math.max(0, Math.round(+x)); };
  return { date:S.date, min:n("f-min"), opens:n("f-opens"), shot:$("#f-shot").checked,
    am:$("#f-am").checked, pm:$("#f-pm").checked, focus:+$("#f-focus").textContent||0, div:+$("#f-div").textContent||0,
    note:$("#f-note").value.trim().slice(0,280) };
}
function updatePreview(){
  const r = dailyScore(readForm(), S.profile);
  const f = x => (Math.round(x*10)/10).toString();
  $("#preview").innerHTML = `
    <span>Short-form time</span><span class="num">${f(r.time)} / 35</span>
    <span>App opens</span><span class="num">${f(r.opens)} / 15</span>
    <span>Protected hours</span><span class="num">${r.prot} / 10</span>
    <span>Focus blocks</span><span class="num">${r.focus} / 20</span>
    <span>Diverse input</span><span class="num">${r.div} / 20</span>
    ${r.pen ? `<span class="neg">Over 60 min penalty</span><span class="num neg">&minus;15</span>` : ""}
    <div class="tot"><span class="totlbl">Day score</span><span class="num">${r.total}</span></div>`;
}
function renderDelZone(){
  const z = $("#delZone"); if (!z) return;
  if (!entry(S.me,S.date)){ z.innerHTML = ""; return; }
  z.innerHTML = S.confirmDel
    ? `<div class="confirm"><span class="hint">Delete this day's entry?</span><button id="delYes" class="ghost neg">Delete</button><button id="delNo" class="ghost">Keep</button></div>`
    : `<button id="delBtn" class="ghost">Clear this day</button>`;
  if (S.confirmDel){
    $("#delYes").onclick = async () => { try { await S.be.deleteDay(S.me, S.date); S.confirmDel=false; S.dirty=false; delete S.entries[S.me+"_"+S.date]; toast("Entry deleted"); renderLog($("#view")); } catch(err){ fail(err); } };
    $("#delNo").onclick = () => { S.confirmDel=false; renderDelZone(); };
  } else $("#delBtn").onclick = () => { S.confirmDel=true; renderDelZone(); };
}
async function saveDay(){
  const e = readForm();
  if (e.min == null || e.opens == null){ toast("Enter minutes and app opens first"); return; }
  const btn = $("#saveBtn"); btn.disabled = true;
  try {
    const body = {...e, source:"web", updatedAt: Date.now()};
    S.entries[S.me+"_"+S.date] = {...(entry(S.me,S.date)||{}), ...body};
    await S.be.setDay(S.me, S.date, body);
    S.dirty = false; toast("Saved " + niceDate(S.date)); renderLog($("#view"));
  } catch(err){ fail(err); btn.disabled = false; }
}
function fail(err){
  if (err && err.code === "permission-denied") toast("That change isn't allowed. You can only change your own entries.");
  else toast("Couldn't save. Check your connection and try again.");
}

/* ---------- Standings ---------- */
function renderStandings(v){
  const ps = sortedPlayers();
  const per = S.period;
  const you = id => id === S.me ? " (you)" : "";
  let html = `<div class="row" style="justify-content:space-between"><div class="seg" role="group" aria-label="Period">
    ${["daily","weekly","monthly"].map(p=>`<button data-per="${p}" aria-pressed="${p===per}">${p[0].toUpperCase()+p.slice(1)}</button>`).join("")}</div>`;
  if (per === "daily"){
    html += `<span class="hint">${esc(niceDate(S.date))}</span></div>`;
    const rows = ps.map(([id,p]) => ({id, p, e:entry(id,S.date), r:dailyScore(entry(id,S.date),p)}));
    const done = rows.filter(x=>x.r).sort((a,b)=> b.r.total-a.r.total || (+a.e.min||0)-(+b.e.min||0));
    const miss = rows.filter(x=>!x.r);
    html += `<div class="board">` + done.map((x,i)=>{
      const win = i===0 && (done.length===1 || x.r.total !== done[1].r.total || +x.e.min !== +done[1].e.min);
      return rankRow(i+1, win, x.p.name + you(x.id),
        `${esc(x.e.min)} min · ${esc(x.e.opens)} opens · ${Math.min(2,+x.e.focus||0)} focus · ${Math.min(2,+x.e.div||0)} reads${x.e.shot?"":" · <span class='neg'>no screenshot</span>"}${x.r.pen?" · <span class='neg'>over 60 min</span>":""}`
        + (x.e.note ? `<br>&ldquo;${esc(x.e.note)}&rdquo;` : ""), x.r.total);
    }).join("") + miss.map(x => `<div class="rank"><span class="pos">&ndash;</span><div class="who"><div class="name">${esc(x.p.name + you(x.id))}</div><div class="meta">Not logged for this day</div></div><span class="score" style="color:var(--muted)">&ndash;</span></div>`).join("") + `</div>`;
    html += `<p class="hint">Tie-breaker: fewer short-form minutes wins.</p>`;
  } else if (per === "weekly"){
    const wk = weekStart(S.date);
    html += `<span class="hint">${esc(niceDate(wk))} &ndash; ${esc(niceDate(addDays(wk,6)))}</span></div>`;
    const rows = ps.map(([id,p]) => ({id,p,w:weekScore(id,wk)})).sort((a,b)=> b.w.total-a.w.total || b.w.focus-a.w.focus);
    const anyLogged = rows.some(x=>x.w.logged);
    html += `<div class="board">` + rows.map((x,i)=>{
      const win = anyLogged && i===0 && (rows.length===1 || x.w.total!==rows[1].w.total || x.w.focus!==rows[1].w.focus);
      const b = [x.w.streak?`<span class="pill good">Streak +20</span>`:"", x.w.zero?`<span class="pill good">Zero day +15</span>`:"", x.w.audit?`<span class="pill good">Feed audit +10</span>`:""].join(" ");
      const auditBox = x.id === S.me ? `<div style="margin-top:6px"><label class="hint" style="display:inline-flex;gap:6px;align-items:center;cursor:pointer"><input type="checkbox" id="auditBox" ${x.w.audit?"checked":""}> Feed audit done (10 unfollows, 3 new follows)</label></div>` : "";
      return rankRow(i+1, win, x.p.name + you(x.id), `${x.w.logged}/7 days logged · ${x.w.good} days at 70+ · ${x.w.focus} focus blocks ${b}${auditBox}`, x.w.total);
    }).join("") + `</div>`;
    html += weekGrid(ps, wk);
    html += `<p class="hint">Tie-breaker: more focus blocks wins. Streak bonus needs 5 days at 70 or more. A zero day needs a screenshot showing 0 minutes.</p>`;
  } else {
    const m = S.date.slice(0,7);
    html += `<span class="hint">${esc(niceMonth(m))}</span></div>`;
    const ms = monthScores(m);
    const rows = ps.map(([id,p])=>({id,p,s:ms[id]})).sort((a,b)=> b.s.total-a.s.total || ((b.s.red?.pct ?? -1e9) - (a.s.red?.pct ?? -1e9)));
    const anyLogged = rows.some(x=>x.s.logged);
    html += `<div class="board">` + rows.map((x,i)=>{
      const win = anyLogged && i===0 && (rows.length===1 || x.s.total!==rows[1].s.total);
      const red = x.s.red ? `${x.s.red.pct>=0?"down":"up"} ${Math.abs(Math.round(x.s.red.pct))}% vs baseline (recent avg ${Math.round(x.s.red.avg)} min)` : "needs 3+ logged days for reduction";
      const b = [x.s.reduction?`<span class="pill good">Biggest drop +50</span>`:"", x.s.consistency?`<span class="pill good">Consistency +25</span>`:""].join(" ");
      return rankRow(i+1, win, x.p.name + you(x.id), `${x.s.logged} days logged · daily ${x.s.daily} + weekly bonuses ${x.s.wbonus} · ${red} ${b}`, x.s.total);
    }).join("") + `</div>`;
    html += `<p class="hint">Tie-breaker: bigger percentage reduction wins. Weekly bonuses count in the month the week starts. Consistency needs every finished week above 400.</p>`;
  }
  v.innerHTML = html;
  v.querySelectorAll("[data-per]").forEach(b => b.onclick = () => { S.period = b.dataset.per; lsSet("period", S.period); renderStandings(v); });
  const ab = $("#auditBox");
  if (ab) ab.onchange = async () => {
    const wk = weekStart(S.date), on = ab.checked;
    S.players[S.me] = {...S.players[S.me], audit: {...(S.players[S.me].audit||{}), [wk]: on}};
    renderStandings(v);
    try { await S.be.setAudit(S.me, wk, on); toast(on ? "Feed audit bonus added" : "Feed audit bonus removed"); } catch(err){ fail(err); }
  };
}
function rankRow(pos, win, name, meta, score){
  return `<div class="rank${win?" win":""}"><span class="pos">${pos}</span><div class="who"><div class="name">${esc(name)} ${win?`<span class="pill gold">Winner</span>`:""}</div><div class="meta">${meta}</div></div><span class="score">${score}<small>points</small></span></div>`;
}
function weekGrid(ps, wk){
  const days = weekDays(wk);
  const head = days.map(d => `<th>${parse(d).toLocaleDateString(undefined,{weekday:"short"})}<br><span class="num" style="font-weight:500">${parse(d).getDate()}</span></th>`).join("");
  const body = ps.map(([id,p]) => `<tr><td><b>${esc(p.name)}</b></td>${days.map(d=>{ const r = dailyScore(entry(id,d),p); if(!r) return `<td class="cell lo">&middot;</td>`; return `<td class="cell ${r.total>=70?"hi":""}">${r.total}</td>`; }).join("")}</tr>`).join("");
  return `<div class="card"><h3 style="margin-bottom:8px">Day scores this week</h3><div class="tablewrap"><table><thead><tr><th>Player</th>${head}</tr></thead><tbody>${body}</tbody></table></div><p class="hint" style="margin:8px 0 0">Green cells count toward the streak bonus.</p></div>`;
}

/* ---------- Profile ---------- */
function renderProfile(v){
  const p = S.profile; const others = sortedPlayers().filter(([id]) => id !== S.me);
  v.innerHTML = `
  <div class="card panel">
    <div><h2>Your profile</h2><p class="hint" style="margin:6px 0 0">Signed in as ${esc(S.email)}. Only you can change your name and baseline.</p></div>
    <div class="player" style="border:0;padding:0">
      <div class="field pname"><label for="p-name">Name</label><input id="p-name" maxlength="40" value="${esc(p.name)}"></div>
      <div class="field"><label for="p-min">Minutes / day</label><input id="p-min" type="number" min="1" class="num" value="${esc(p.baseMin)}"></div>
      <div class="field"><label for="p-opens">Opens / day</label><input id="p-opens" type="number" min="0" class="num" value="${esc(p.baseOpens)}"></div>
      <button class="primary" id="p-save">Save</button>
    </div>
    <div class="row"><button class="ghost" id="p-out">Sign out</button></div>
  </div>
  <div class="card panel">
    <div><h2>Members</h2><p class="hint" style="margin:6px 0 0">Friends join by creating an account and entering the invite code.</p></div>
    <div>${others.length ? others.map(([,q]) => `<div class="row" style="justify-content:space-between;padding-block:10px;border-bottom:1px solid var(--line)"><b>${esc(q.name)}</b><span class="num hint">${esc(q.baseMin)} min · ${esc(q.baseOpens)} opens / day</span></div>`).join("")
      : `<div class="empty"><strong>No one else yet</strong>Send your friends the link and the invite code.</div>`}</div>
  </div>
  <div class="card panel" id="installCard"></div>`;
  $("#p-save").onclick = async () => {
    const name = $("#p-name").value.trim(), baseMin = Math.round(+$("#p-min").value||0), baseOpens = Math.max(0, Math.round(+$("#p-opens").value||0));
    if (!name || baseMin < 1){ toast("Enter your name and baseline minutes"); return; }
    try { await S.be.updateProfile(S.me, {name, baseMin, baseOpens}); S.profile = {...S.profile, name, baseMin, baseOpens}; S.players[S.me] = S.profile; toast("Profile saved"); render(true); } catch(err){ fail(err); }
  };
  $("#p-out").onclick = () => S.be.signOut();
  $("#installCard").innerHTML = installHelp();
}
function isIOS(){ return /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1); }
function isStandalone(){ return window.navigator.standalone === true || matchMedia("(display-mode: standalone)").matches; }
function installHelp(){
  if (isStandalone()) return `<h2>Installed</h2><p class="hint" style="margin:0">You're using NIRVANA from your home screen.</p>`;
  return `<h2>Put NIRVANA on your home screen</h2>
    <p style="margin:0"><b>iPhone:</b> open this page in Safari, tap the Share button, then <b>Add to Home Screen</b>.</p>
    <p style="margin:0"><b>Android:</b> install the NIRVANA app. It reads your minutes automatically.</p>`;
}
function maybeInstallHint(){
  if (!isIOS() || isStandalone() || lsGet("iosHintSeen")) return;
  const n = $("#notice"); n.hidden = false;
  n.innerHTML = `On iPhone, tap Safari's Share button, then <b>Add to Home Screen</b> to use NIRVANA like an app. <button class="ghost linkish" id="hintOk">Got it</button>`;
  $("#hintOk").onclick = () => { lsSet("iosHintSeen","1"); n.hidden = true; };
}

/* ---------- Rules ---------- */
function renderRules(v){
  v.innerHTML = `<div class="card rules">
    <h2>How scoring works</h2>
    <p class="hint" style="margin:0">Instagram, Instagram on the web, YouTube Shorts and similar apps count as one short-form bucket. Post your Screen Time or Digital Wellbeing screenshot to the group before bed.</p>
    <h3>Daily (out of 100)</h3>
    <div class="tablewrap"><table><tbody>
      <tr><td><b>Short-form time</b><br><span class="hint">15 min or less gets full marks; at or above your baseline gets 0; linear in between</span></td><td>35</td></tr>
      <tr><td><b>App opens</b><br><span class="hint">5 or fewer gets full marks; at or above your baseline gets 0</span></td><td>15</td></tr>
      <tr><td><b>Protected hours</b><br><span class="hint">5 each for no Instagram in the first hour awake and the last hour before sleep</span></td><td>10</td></tr>
      <tr><td><b>Focus blocks</b><br><span class="hint">10 per 45-minute phone-away session, up to 2</span></td><td>20</td></tr>
      <tr><td><b>Diverse input</b><br><span class="hint">10 per book session, long read or podcast outside your usual views, up to 2</span></td><td>20</td></tr>
      <tr><td><b>Hard cap penalty</b><br><span class="hint">More than 60 minutes of short-form. Day score never goes below 0.</span></td><td>&minus;15</td></tr>
      <tr><td><b>No screenshot</b><br><span class="hint">Time and opens score 0 for the day. Days sent from the Android app count as proof.</span></td><td>0</td></tr>
    </tbody></table></div>
    <h3>Weekly (Monday to Sunday)</h3>
    <div class="tablewrap"><table><tbody>
      <tr><td>Sum of the 7 day scores</td><td>&Sigma;</td></tr>
      <tr><td>Streak: 5 or more days at 70+</td><td>+20</td></tr>
      <tr><td>Feed audit: unfollow or mute 10, follow 3 that challenge your views</td><td>+10</td></tr>
      <tr><td>Zero day: a full day with 0 short-form minutes</td><td>+15</td></tr>
    </tbody></table></div>
    <h3>Monthly (calendar month)</h3>
    <div class="tablewrap"><table><tbody>
      <tr><td>Day scores in the month plus weekly bonuses for weeks starting in it</td><td>&Sigma;</td></tr>
      <tr><td>Biggest reduction: largest % drop from baseline, using your last 7 logged days in the month (needs 3+)</td><td>+50</td></tr>
      <tr><td>Consistency: every finished week above 400</td><td>+25</td></tr>
    </tbody></table></div>
    <h3>Tie-breakers</h3>
    <p style="margin:0">Daily: fewer short-form minutes. Weekly: more focus blocks. Monthly: bigger percentage reduction.</p>
  </div>`;
}

/* ---------- boot ---------- */
document.querySelectorAll("nav.tabs button").forEach(b => b.addEventListener("click", () => setTab(b.dataset.tab)));
$("#dateInput").addEventListener("change", e => setDate(e.target.value));
$("#prevDay").addEventListener("click", () => setDate(addDays(S.date,-1)));
$("#nextDay").addEventListener("click", () => setDate(addDays(S.date,1)));
$("#todayBtn").addEventListener("click", () => setDate(today()));
$("#dateInput").value = S.date;

export async function start(backend){
  S.be = backend;
  S.be.onAuth(async user => {
    stopWatching();
    S.profile = null; S.me = user ? user.uid : null; S.email = user ? (user.email || "") : "";
    if (!user){ S.authMode = "signin"; showShell(false); renderAuth(); return; }
    showShell(false);
    $("#gate").innerHTML = `<div class="card empty"><strong>Opening the league…</strong></div>`;
    let prof = null;
    try { prof = await S.be.getProfile(user.uid); }
    catch { $("#gate").innerHTML = `<div class="card empty"><strong>Can't reach NIRVANA</strong>Check your connection and reload the page.</div>`; return; }
    if (prof) enterLeague(prof); else renderJoin();
  });
}
