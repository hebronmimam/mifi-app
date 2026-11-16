# Device protocols

How the app talks to each supported box. Both answer on the LAN at the gateway
address (often `192.168.0.1`), so the app first probes which one it reached.
All device traffic goes through the native layer (`net/Http.kt`), bound to the
Wi-Fi network, because the web screens are served from an `https` origin and
cannot reach a plain-`http` LAN address themselves (mixed content and CORS).

## ZTE MiFi (goform) — e.g. MTN U10LS

- Read: `GET /goform/goform_get_cmd_process?isTest=false&multi_data=1&cmd=a,b,c`
  with `Referer: http://<host>/index.html`. Returns a flat JSON object.
- Write: `POST /goform/goform_set_cmd_process`, form-encoded,
  `isTest=false&goformId=<ID>&...&AD=<token>`.
- Identify without signing in: `cmd=model_name,wa_inner_version,cr_version,loginfo`.
- Sign in (firmware with `WEB_ATTR_IF_SUPPORT_SHA256=2`):
  1. `GET cmd=LD` → `{"LD":"<nonce>"}`
  2. `POST goformId=LOGIN&password=<H>` where
     `H = SHA256( SHA256(password) + LD )` (hex; casing per the firmware's own JS).
  3. `{"result":"0"}` means signed in; the session is the `stok` cookie.
  The firmware locks sign-in after repeated failures, so the app never retries
  a rejected password on its own.
- Write token: `AD = hash( hash(wa_inner_version + cr_version) + RD )`, with
  `RD` from `GET cmd=RD`, fetched fresh for every write.
- Per-device data: **not available.** `station_list` gives hostname, MAC and IP
  only; byte counters are totals for the whole SIM.

## Tozed ZLT home router (MTN-branded, Vue web app)

- Every call: `POST /cgi-bin/http.cgi` with a JSON body
  `{"cmd":<n>,"method":"GET"|"POST","sessionId":"<id>", ...}`.
  Writes (`method:"POST"`) also carry `"token"`; after each write the web app
  refreshes it with `{"cmd":233,"method":"GET"}` → `{"token":...}`.
- Sign in:
  1. `{"cmd":232,"method":"GET","sessionId":""}` → `{"token":"<t>"}`
  2. `{"cmd":100,"method":"POST","username":"admin","passwd":sha256hex(t + password),
     "isAutoUpgrade":"1","isCheckPasswd":"1","sessionId":""}`
  3. Success → `{"sessionId":...,"user_level":...}`. Failure →
     `login_fail:"fail"` with `login_times`; **3 failures lock sign-in for 180 s**.
  `sha256hex` is lowercase hex (js-sha256).
- Expired session: responses carry `message:"NO_AUTH"` or `"LOGIN_TIMEOUT"`.
- Commands used by the app:

| cmd | name in the web app | use |
|---|---|---|
| 80 | getBasicData | model and version, readable before sign-in |
| 104 | wakeupHttpd | wake the web server before sign-in |
| 113 | sys_status | SIM state, network type, operator, signal level, uptime, SSIDs |
| 205 | getData | `RSRP`, `SINR`, `flow_dl`/`flow_ul` (MB since the line came up), `mon_total_flow` (MB), `onlineDuration` (`d-h-m-s`) |
| 207 | getRuntime | `device_temperature` (°C), `online_duration` |
| 402 | getHomeInfo | live rates: `dl_flow_rate` + `dl_flow_rate_4g` (and `ul_…`), kbit/s, summed |
| 1005 | — | `sms_unread`, `mon_download_flow` |
| 223 | getAllDevice | `dhcp_list_info[]` = hostname, mac, ip |
| 224 / 225 | get24WifiNum / get5WifiNum | `wlan24g_wifi_info[]` = user, mac, rssi |
| 337 / 355 | getFlow / getFlowN | `mon_download_flow` (MB, download + upload despite the name), `usedFlow`, data limit |
| 24 | getIPBand | per-device speed limits (`datas[]` = mac, ippro, remark) |
| 26 / 29 | getUrlData / getUrlFilterMode | website filter |
| 12 / 13 / 14 / 16 | getSms / sendSms / deleteSms / getSmsSetting | messages |
| 561 | postUssd | USSD codes (17 s timeout) |
| 218 / 213 / 248 | network mode / APN | mobile network |
| 230 / 231 | getWifi24Set / getWifi5Set | the two Wi-Fi bands |
| 6 / 112 | reboot / restore | restart / factory reset |

- Per-device data: **not available.** The device list has no byte counters, and
  parent control (397) is a time schedule only.

## What "per-device usage" means in this app

Neither box counts bytes per client, so the app records, on the phone:

- **This phone, exact.** Android's own counters, sampled while on that Wi-Fi.
- **Every other device, a range.** At each check the app knows the box's total
  bytes since the last check and which devices were online. A device's *most* is
  everything carried while it was online; its *least* is what was carried while
  it was the only device online apart from this phone. The range narrows by
  itself whenever a device is on alone.
