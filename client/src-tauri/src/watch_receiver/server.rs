//! Blocking `tiny_http` receiver server (Delivery 1A debug-only).
//!
//! Endpoints:
//! - `GET /api/health` — minimal JSON identifying a debug receiver.
//! - `POST /api/watch/audio` — exact Bearer auth + `audio/wav`, body capped at
//!   10 MiB before allocation/writing, validated WAV saved atomically to
//!   `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.
//!
//! Delivery 1B (frozen Provider Contract §B): `POST /api/watch/audio` admits the
//! run through the WebView Orchestrator **before** the accepted file is replaced,
//! and returns `201` only after the post-save `watch_run_started` acknowledgement
//! proves the Provider session was connected and `start()` succeeded. Busy /
//! not-ready / bridge-timeout rejections answer `409` and never touch disk;
//! preparation and run-start failures answer `500` and abort both sides. The
//! token and Authorization header are never logged.
//!
//! JSON error bodies use explicit 400/401/404/405/409/413/415/500 statuses; 202 is
//! never returned.

// The admission module is mounted from main.rs (debug-only) so that
// `watch_receiver/mod.rs` keeps its Delivery 1A shape.
use crate::watch_admission::admission::{AdmissionGate, AdmissionOutcome};
use crate::watch_receiver::config::ReceiverConfig;
use crate::watch_receiver::wav;
use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

pub const MAX_BODY_BYTES: usize = 10 * 1024 * 1024; // 10 MiB cap before allocation

/// Delivery 1C: frozen service identifier echoed by the authenticated discovery
/// probe. Fixed, non-sensitive, and identical to the Watch's expected value.
pub const DISCOVERY_SERVICE_ID: &str = "sayit-watch-debug-receiver";

/// Delivery 1C: frozen discovery protocol version.
pub const DISCOVERY_PROTOCOL_VERSION: u32 = 1;

/// Delivery 1C: the authenticated probe path itself. It is the mDNS TXT value
/// (`path=/api/watch/discovery`) and the route served here; Repair 1 必修 4
/// removed it from the success body, not from the advertisement.
pub const DISCOVERY_ENDPOINT_PATH: &str = "/api/watch/discovery";

/// Debug-only event sink into the WebView (Tauri `emit` in production, set from
/// `main.rs` setup; a recording closure in tests). The payload MUST be a
/// `serde_json::Value` object — Tauri serializes whatever it is given, so a
/// `String` payload would arrive in JS as a JSON *string*, not as the structured
/// object the ingress adapter reads (Z3 Repair 1). Events emitted before the
/// sink is registered are dropped — the request then fails closed via the
/// admission timeout, which is the correct behavior for a not-yet-ready WebView.
pub type EventSink = Arc<dyn Fn(&str, serde_json::Value) + Send + Sync>;

static EVENT_SINK: OnceLock<EventSink> = OnceLock::new();

/// Registers the production WebView emitter. Debug-only; called from `main.rs`.
pub fn set_event_sink(sink: EventSink) {
    let _ = EVENT_SINK.set(sink);
}

fn event_sink() -> EventSink {
    EVENT_SINK
        .get()
        .cloned()
        .unwrap_or_else(|| Arc::new(|_event, _payload| {}))
}

/// Fixed rejection reasons that map to `409` (busy / not ready / bridge timeout).
/// Everything else is a preparation failure and maps to `500` per the contract.
fn is_busy_reason(reason: &str) -> bool {
    matches!(
        reason,
        "orchestrator_not_idle"
            | "provider_not_ready"
            | "already_reserved"
            | "bridge_timeout"
    )
}

/// Builds the rejection response for an admission outcome: `409` for busy /
/// not-ready / bridge-timeout, `500` for preparation failures (Provider
/// Contract §B.3). The rejected request never touches disk.
fn admission_rejection_response(
    request: Request,
    request_id: &str,
    reason: &str,
) -> Result<(), std::io::Error> {
    let status = if is_busy_reason(reason) {
        StatusCode(409)
    } else {
        StatusCode(500)
    };
    let error = if status.0 == 409 { "busy" } else { "admission failed" };
    log::info!("watch admission: request {request_id} rejected ({reason})");
    json_response(
        request,
        status,
        &format!(
            r#"{{"error":"{error}","detail":"{reason}","requestId":"{request_id}"}}"#
        ),
    )
}

pub struct ReceiverServer {
    server: Server,
    cfg: Arc<ReceiverConfig>,
    gate: Arc<AdmissionGate>,
    emit: EventSink,
}

impl ReceiverServer {
    /// Production constructor: the process-wide shared admission gate and the
    /// globally registered WebView event sink (set from `main.rs` setup).
    pub fn start(
        cfg: Arc<ReceiverConfig>,
    ) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        Self::start_with(cfg, AdmissionGate::shared(), event_sink())
    }

    /// Explicit constructor used by tests to inject their own gate and event log.
    pub fn start_with(
        cfg: Arc<ReceiverConfig>,
        gate: Arc<AdmissionGate>,
        emit: EventSink,
    ) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let addr = format!("{}:{}", cfg.bind_ip, cfg.port);
        let server = Server::http(addr.as_str())?;
        Ok(Self {
            server,
            cfg,
            gate,
            emit,
        })
    }

    pub fn bind_ip(&self) -> String {
        self.cfg.bind_ip.to_string()
    }

    pub fn bind_port(&self) -> u16 {
        self.cfg.port
    }

    /// Runs the accept loop (blocking). Handles each request synchronously.
    pub fn run(&self) {
        for request in self.server.incoming_requests() {
            let _ = self.handle(request);
        }
    }

    fn handle(&self, request: Request) -> Result<(), std::io::Error> {
        let method = request.method().clone();
        let url = request.url().to_string();

        match (method, url.as_str()) {
            (Method::Get, "/api/health") => self.handle_health(request),
            // Delivery 1C: authenticated discovery probe. The Watch only saves a
            // discovered RFC1918 address after this endpoint answers 200 with the
            // frozen service id + protocol version under its own Bearer token.
            (Method::Get, "/api/watch/discovery") => self.handle_discovery(request),
            (Method::Post, "/api/watch/audio") => self.handle_audio(request),
            (Method::Get | Method::Post, _) => {
                json_response(request, StatusCode(404), "not found")
            }
            _ => json_response(request, StatusCode(405), "method not allowed"),
        }
    }

    fn handle_health(&self, request: Request) -> Result<(), std::io::Error> {
        json_response(
            request,
            StatusCode(200),
            r#"{"service":"sayit-watch-debug-receiver","status":"ok","asrReady":false}"#,
        )
    }

    /// `GET /api/watch/discovery` (Delivery 1C).
    ///
    /// Constant-time Bearer check with the existing dev token, then a fixed
    /// non-sensitive body carrying exactly the frozen `service` and `protocol`
    /// (Repair 1 必修 4 removed the extra `path` field; the TXT record still
    /// advertises `path=/api/watch/discovery`). The response never echoes or logs
    /// the token or the Authorization header, and a failed check answers a bare
    /// 401 so the endpoint cannot be used as an unauthenticated address oracle.
    fn handle_discovery(&self, request: Request) -> Result<(), std::io::Error> {
        if !self.authorized(&request) {
            log::warn!("watch discovery: unauthorized probe rejected");
            return json_response(request, StatusCode(401), r#"{"error":"unauthorized"}"#);
        }
        json_response(
            request,
            StatusCode(200),
            &format!(
                r#"{{"service":"{}","protocol":{}}}"#,
                DISCOVERY_SERVICE_ID, DISCOVERY_PROTOCOL_VERSION,
            ),
        )
    }

    fn handle_audio(&self, mut request: Request) -> Result<(), std::io::Error> {
        // 1. Authentication (exact Bearer; never logged).
        if !self.authorized(&request) {
            log::warn!("watch receiver: unauthorized upload attempt rejected");
            return json_response(request, StatusCode(401), r#"{"error":"unauthorized"}"#);
        }

        // 1.5. X-Request-Id must be a well-formed UUID; the receiver preserves it
        // end to end and echoes the same value in the 201 response and success log.
        let request_id = match request
            .headers()
            .iter()
            .find(|h| h.field.equiv("X-Request-Id"))
            .map(|h| h.value.as_str().trim())
        {
            Some(raw) => match uuid::Uuid::parse_str(raw) {
                Ok(u) => u.to_string(),
                Err(_) => {
                    return json_response(
                        request,
                        StatusCode(400),
                        r#"{"error":"X-Request-Id must be a UUID"}"#,
                    );
                }
            },
            None => {
                return json_response(
                    request,
                    StatusCode(400),
                    r#"{"error":"X-Request-Id header is required"}"#,
                );
            }
        };

        // 2. Content type must be audio/wav.
        let content_type = request
            .headers()
            .iter()
            .find(|h| h.field.equiv("Content-Type"))
            .map(|h| h.value.as_str().trim().to_lowercase())
            .unwrap_or_default();
        if !content_type.starts_with("audio/wav") && !content_type.starts_with("audio/x-wav") {
            return json_response(
                request,
                StatusCode(400),
                r#"{"error":"content type must be audio/wav"}"#,
            );
        }

        // 3. Size cap before any allocation/writing (Content-Length when present,
        //    plus a hard cap on streamed reads).
        if let Some(len) = request.body_length() {
            if len > MAX_BODY_BYTES {
                return json_response(
                    request,
                    StatusCode(413),
                    r#"{"error":"body exceeds 10 MiB limit"}"#,
                );
            }
        }

        // 4. Read the body (capped).
        let mut body = Vec::new();
        {
            let mut reader = request.as_reader().take((MAX_BODY_BYTES + 1) as u64);
            reader
                .read_to_end(&mut body)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
        }
        if body.len() > MAX_BODY_BYTES {
            return json_response(
                request,
                StatusCode(413),
                r#"{"error":"body exceeds 10 MiB limit"}"#,
            );
        }
        if body.is_empty() {
            return json_response(request, StatusCode(400), r#"{"error":"empty body"}"#);
        }

        // 5. Parse and validate the WAV.
        let info = match wav::parse(&body) {
            Ok(i) => i,
            Err(e) => {
                log::warn!("watch receiver: rejected invalid WAV: {}", e.0);
                return json_response(
                    request,
                    StatusCode(400),
                    &format!(r#"{{"error":"invalid wav: {}"}}"#, e.0),
                );
            }
        };

        // 5.5 Admission (Delivery 1B, Provider Contract §B): the WebView
        // Orchestrator must accept the run before anything touches disk, so a
        // `409`/preparation failure never overwrites the last accepted file.
        // Repair 2 ordering: the pending waiter is registered BEFORE the event is
        // emitted, so a WebView answering synchronously inside the emit call
        // stack finds it. The decision is bounded and fails closed with
        // `409 bridge_timeout`.
        let admission_rx = match self.gate.begin_admission(&request_id) {
            Ok(rx) => rx,
            Err(reason) => return admission_rejection_response(request, &request_id, &reason),
        };
        (self.emit)(
            "watch://admission-request",
            serde_json::json!({
                "requestId": request_id,
                "bytes": body.len(),
                "sampleCount": info.sample_count,
                "durationMs": info.duration_ms,
            }),
        );
        let outcome = self.gate.wait_admission(&request_id, admission_rx);
        if let AdmissionOutcome::Rejected { reason } = outcome {
            return admission_rejection_response(request, &request_id, &reason);
        }

        // 6. Durable atomic replacement under %LOCALAPPDATA%\com.sayit.app\watch-receiver.
        let dir = watch_receiver_dir();
        let target = dir.join("received_watch.wav");
        match save_atomically(&dir, &target, &body) {
            Ok(()) => {}
            Err(e) => {
                log::error!("watch receiver: failed to save received_watch.wav: {e}");
                // Correlated abort on both sides (Provider Contract §B.6 row 3).
                self.gate.abort(&request_id, "save_failed");
                (self.emit)(
                    "watch://run-abort",
                    serde_json::json!({ "requestId": request_id, "reason": "save_failed" }),
                );
                return json_response(request, StatusCode(500), r#"{"error":"storage failure"}"#);
            }
        }

        // 7. Post-save handoff: tell the WebView the durable file is ready, then
        // wait (bounded) for `watch_run_started` — the acknowledgement that the
        // Provider session was connected and `start()` succeeded for this exact
        // request. Repair 2 symmetry: the ack waiter is registered BEFORE the
        // audio-ready emit. Any failure answers non-2xx (Watch keeps its WAV) and
        // aborts both sides; `201` still means transport/run-start success only.
        let ack_rx = match self.gate.begin_run_ack(&request_id) {
            Ok(rx) => rx,
            Err(reason) => {
                self.gate.abort(&request_id, &reason);
                (self.emit)(
                    "watch://run-abort",
                    serde_json::json!({ "requestId": request_id, "reason": reason }),
                );
                return json_response(
                    request,
                    StatusCode(500),
                    &format!(
                        r#"{{"error":"run failed to start","detail":"{reason}","requestId":"{request_id}"}}"#
                    ),
                );
            }
        };
        (self.emit)(
            "watch://audio-ready",
            serde_json::json!({
                "requestId": request_id,
                "bytes": body.len(),
                "sampleCount": info.sample_count,
                "durationMs": info.duration_ms,
            }),
        );
        if let Err(reason) = self.gate.wait_run_ack(&request_id, ack_rx) {
            (self.emit)(
                "watch://run-abort",
                serde_json::json!({ "requestId": request_id, "reason": reason }),
            );
            return json_response(
                request,
                StatusCode(500),
                &format!(
                    r#"{{"error":"run failed to start","detail":"{reason}","requestId":"{request_id}"}}"#
                ),
            );
        }

        // 8. Success response (only after durable replacement, admission and the
        // run-start acknowledgement). Echo the request UUID exactly as received —
        // never a locally generated unrelated ID.
        let sha = hex_sha256(&body);
        let body_json = format!(
            r#"{{"requestId":"{request_id}","bytes":{},"sampleCount":{},"audioDurationMs":{},"sha256":"{sha}"}}"#,
            body.len(),
            info.sample_count,
            info.duration_ms,
        );
        log::info!(
            "watch receiver: saved received_watch.wav requestId={} bytes={} samples={} durationMs={}",
            request_id,
            body.len(),
            info.sample_count,
            info.duration_ms
        );
        json_response(request, StatusCode(201), &body_json)
    }

    /// Constant-time Bearer token comparison. Returns false on any mismatch
    /// (missing header, wrong scheme, wrong value).
    fn authorized(&self, request: &Request) -> bool {
        let expected = self.cfg.dev_token.as_bytes();
        let Some(header) = request
            .headers()
            .iter()
            .find(|h| h.field.equiv("Authorization"))
        else {
            return false;
        };
        let value = header.value.as_str().trim();
        let Some(token) = value.strip_prefix("Bearer ") else {
            return false;
        };
        let actual = token.trim().as_bytes();
        if actual.len() != expected.len() {
            return false;
        }
        // Constant-time-ish compare.
        let mut diff = 0u8;
        for (a, b) in actual.iter().zip(expected.iter()) {
            diff |= a ^ b;
        }
        diff == 0
    }
}

/// Receiver storage directory. `pub(crate)` so the debug-only
/// `watch_read_reserved_pcm` command can read the accepted file without
/// duplicating the path rule.
pub(crate) fn watch_receiver_dir() -> PathBuf {
    // Test isolation hook: tests redirect the receiver dir to a temp location.
    if let Ok(override_dir) = std::env::var("SAYIT_WATCH_RECEIVER_DIR") {
        if !override_dir.trim().is_empty() {
            return PathBuf::from(override_dir);
        }
    }
    let base = dirs::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
    base.join("com.sayit.app").join("watch-receiver")
}

/// Writes `bytes` to a unique temp file in `dir`, flushes and syncs it, then
/// atomically replaces `target` with the temp file using a Windows
/// replace-with-write-through operation so a partial file never becomes the
/// accepted sample. On failure only this request's temp file is cleaned up.
fn save_atomically(dir: &std::path::Path, target: &std::path::Path, bytes: &[u8]) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let tmp = dir.join(format!(
        "received_watch.wav.tmp.{}",
        uuid::Uuid::new_v4()
    ));

    let write_result = (|| -> std::io::Result<()> {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.flush()?;
        f.sync_all()?;
        drop(f);

        #[cfg(windows)]
        {
            replace_file_windows(target, &tmp)?;
        }
        #[cfg(not(windows))]
        {
            // Non-Windows fallback (unused in Delivery 1A but keeps the module
            // compile-clean on other hosts): rename is atomic on POSIX.
            std::fs::rename(&tmp, target)?;
        }
        Ok(())
    })();

    if write_result.is_err() {
        // Clean only this request's temp file on failure.
        let _ = std::fs::remove_file(&tmp);
    }
    write_result
}

/// MoveFileExW with MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH: an
/// atomic replace-with-write-through operation, so a partial file never
/// becomes the accepted sample. Works whether or not the target exists.
#[cfg(windows)]
fn replace_file_windows(target: &std::path::Path, tmp: &std::path::Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };

    let target_wide: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    let tmp_wide: Vec<u16> = tmp.as_os_str().encode_wide().chain(Some(0)).collect();

    let result = unsafe {
        MoveFileExW(
            windows::core::PCWSTR(tmp_wide.as_ptr()),
            windows::core::PCWSTR(target_wide.as_ptr()),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    result.map_err(|e| std::io::Error::other(e.to_string()))
}

fn hex_sha256(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let digest = hasher.finalize();
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

fn json_response(request: Request, status: StatusCode, body: &str) -> Result<(), std::io::Error> {
    let response = Response::from_string(body.to_string())
        .with_status_code(status)
        .with_header(Header::from_bytes("Content-Type", "application/json").unwrap());
    request.respond(response)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpStream;
    use std::sync::Arc;
    /// Redirects receiver storage to a unique temp dir for the test.
    fn isolate_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "sayit-watch-receiver-test-{}",
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        std::env::set_var("SAYIT_WATCH_RECEIVER_DIR", &dir);
        dir
    }

    fn canonical_wav(data_len: usize) -> Vec<u8> {
        let mut wav = Vec::with_capacity(44 + data_len);
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&(36u32 + data_len as u32).to_le_bytes());
        wav.extend_from_slice(b"WAVE");
        wav.extend_from_slice(b"fmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&16000u32.to_le_bytes());
        wav.extend_from_slice(&32000u32.to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&(data_len as u32).to_le_bytes());
        wav.extend(vec![0u8; data_len]);
        wav
    }

    /// Starts the server on 127.0.0.1:0 (tests bypass the LAN-only config check
    /// by constructing the config directly) and returns the bound address.
    /// The server thread is intentionally not joined: `run` blocks forever and
    /// the process exit terminates it.
    fn start_test_server() -> String {
        let (addr, _gate, _events) = start_test_server_with(Arc::new(AdmissionGate::new()));
        addr
    }

    /// Same, but exposes the gate and the recorded event log for admission/ack
    /// orchestration and ordering assertions from tests.
    type EventLog = Arc<Mutex<Vec<(String, serde_json::Value)>>>;

    fn start_test_server_with(gate: Arc<AdmissionGate>) -> (String, Arc<AdmissionGate>, EventLog) {
        let cfg = Arc::new(ReceiverConfig {
            bind_ip: "127.0.0.1".parse().unwrap(),
            port: 0,
            // Frozen 64-hex dev token for tests (never a real token).
            dev_token: "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234".to_string(),
        });
        let events: Arc<Mutex<Vec<(String, serde_json::Value)>>> = Arc::new(Mutex::new(Vec::new()));
        let sink: EventSink = {
            let events = Arc::clone(&events);
            Arc::new(move |event: &str, payload: serde_json::Value| {
                events.lock().unwrap().push((event.to_string(), payload));
            })
        };
        let server = ReceiverServer::start_with(cfg, gate.clone(), sink).expect("bind");
        let addr = server.server.server_addr().to_string();
        std::thread::spawn(move || server.run());
        // Give the accept loop a moment to start.
        std::thread::sleep(std::time::Duration::from_millis(50));
        (addr, gate, events)
    }

    const TEST_TOKEN: &str = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234";
    const TEST_BEARER: &str = "Bearer abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234";
    const TEST_UUID: &str = "123e4567-e89b-12d3-a456-426614174000";

    /// Standard authenticated headers with a valid X-Request-Id.
    fn auth_headers() -> [(&'static str, &'static str); 3] {
        [
            ("Content-Type", "audio/wav"),
            ("Authorization", TEST_BEARER),
            ("X-Request-Id", TEST_UUID),
        ]
    }

    fn raw_request(addr: &str, method: &str, path: &str, headers: &[(&str, &str)], body: &[u8]) -> String {
        let mut stream = TcpStream::connect(addr).expect("connect");
        let mut req = String::new();
        req.push_str(&format!("{method} {path} HTTP/1.1\r\n"));
        req.push_str(&format!("Host: {addr}\r\n"));
        for (k, v) in headers {
            req.push_str(&format!("{k}: {v}\r\n"));
        }
        req.push_str(&format!("Content-Length: {}\r\n", body.len()));
        req.push_str("Connection: close\r\n\r\n");
        stream.write_all(req.as_bytes()).unwrap();
        stream.write_all(body).unwrap();
        stream.flush().unwrap();

        let mut response = String::new();
        let mut buf = [0u8; 8192];
        loop {
            match stream.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => response.push_str(&String::from_utf8_lossy(&buf[..n])),
                Err(_) => break,
            }
        }
        response
    }

    fn status_of(response: &str) -> u16 {
        response
            .lines()
            .next()
            .unwrap_or("")
            .split_whitespace()
            .nth(1)
            .unwrap_or("0")
            .parse()
            .unwrap_or(0)
    }

    #[test]
    fn health_endpoint_identifies_debug_receiver() {
        isolate_dir();
        let addr = start_test_server();
        let resp = raw_request(&addr, "GET", "/api/health", &[], b"");
        let status = status_of(&resp);
        assert_eq!(status, 200);
        assert!(resp.contains("sayit-watch-debug-receiver"));
        assert!(resp.contains("asrReady"));
    }

    #[test]
    fn discovery_probe_requires_the_exact_token() {
        isolate_dir();
        let addr = start_test_server();
        let path = "/api/watch/discovery";

        // Missing Authorization -> 401, and the body is a bare status.
        let resp = raw_request(&addr, "GET", path, &[], b"");
        assert_eq!(status_of(&resp), 401);
        // A token of the right shape but the wrong value -> 401.
        let resp = raw_request(
            &addr,
            "GET",
            path,
            &[("Authorization", &"b".repeat(64))],
            b"",
        );
        assert_eq!(status_of(&resp), 401);
        // A token of the wrong shape (the constant-time compare must not panic
        // on a length mismatch) -> 401.
        let resp = raw_request(&addr, "GET", path, &[("Authorization", "Bearer short")], b"");
        assert_eq!(status_of(&resp), 401);
        // Wrong scheme -> 401.
        let resp = raw_request(&addr, "GET", path, &[("Authorization", "Token abc")], b"");
        assert_eq!(status_of(&resp), 401);
        // A wrong token must not receive the service identity: no oracle.
        assert!(!resp.contains(DISCOVERY_SERVICE_ID));
        assert!(!resp.contains("protocol"));
    }

    #[test]
    fn discovery_probe_returns_only_the_frozen_identity() {
        isolate_dir();
        let addr = start_test_server();
        let resp = raw_request(
            &addr,
            "GET",
            "/api/watch/discovery",
            &[("Authorization", TEST_BEARER)],
            b"",
        );
        assert_eq!(status_of(&resp), 200);
        assert!(resp.contains(&format!(r#""service":"{DISCOVERY_SERVICE_ID}""#)));
        assert!(resp.contains(&format!(r#""protocol":{DISCOVERY_PROTOCOL_VERSION}"#)));
        // Fixed body: already public metadata only — never the token, and no
        // audio/transcript/host details.
        let body = resp.split("\r\n\r\n").nth(1).unwrap_or("");
        assert!(!body.contains("token"));
        assert!(!body.to_lowercase().contains("bearer"));
        // Repair 1 必修 4: the success body carries EXACTLY `service` and
        // `protocol`. The probe path is advertised through TXT only.
        assert_eq!(
            body,
            format!(
                r#"{{"service":"{}","protocol":{}}}"#,
                DISCOVERY_SERVICE_ID, DISCOVERY_PROTOCOL_VERSION
            )
        );
        assert!(
            !body.contains("path"),
            "the discovery success body must not carry a path field: {body}"
        );
    }

    #[test]
    fn discovery_probe_never_leaks_the_token_into_the_success_log_path() {
        // Source-level guard: the discovery handler must log only a fixed
        // rejection line and must never format the Authorization header.
        let source = std::fs::read_to_string("src/watch_receiver/server.rs")
            .expect("server.rs must exist");
        let handler_start = source.find("fn handle_discovery").expect("handler exists");
        let body = function_body(&source, handler_start);
        assert!(body.contains("self.authorized(&request)"));
        assert!(body.contains("StatusCode(401)"));
        assert!(
            !body.contains("Authorization") && !body.contains("dev_token"),
            "the discovery handler must not touch the Authorization header or the token"
        );
    }

    #[test]
    fn discovery_path_constant_matches_the_advertised_txt_value() {
        // The TXT `path` advertised in mDNS and the route served here are the
        // same string; a drift would make discovery unusable.
        assert_eq!(DISCOVERY_ENDPOINT_PATH, crate::watch_receiver::mdns::DISCOVERY_PATH);
        assert_eq!(crate::watch_receiver::mdns::SERVICE_TYPE, "_sayit-watch._tcp.local.");
    }

    /// Extracts the brace-balanced body of the function starting at `start`.
    fn function_body(source: &str, start: usize) -> &str {
        let open = source[start..]
            .find('{')
            .map(|i| start + i)
            .expect("function must have a body");
        let mut depth = 0usize;
        for (offset, byte) in source.as_bytes()[open..].iter().enumerate() {
            match byte {
                b'{' => depth += 1,
                b'}' => {
                    depth -= 1;
                    if depth == 0 {
                        return &source[open..open + offset + 1];
                    }
                }
                _ => {}
            }
        }
        panic!("unbalanced braces in function body");
    }

    #[test]
    fn auth_required_and_token_never_leaked() {
        isolate_dir();
        let addr = start_test_server();
        let wav = canonical_wav(100);

        // Missing token -> 401
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &[("Content-Type", "audio/wav")], &wav);
        assert_eq!(status_of(&resp), 401);
        // Invalid token -> 401
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[("Content-Type", "audio/wav"), ("Authorization", "Bearer wrong-token")],
            &wav,
        );
        assert_eq!(status_of(&resp), 401);
        // Wrong scheme -> 401
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[("Content-Type", "audio/wav"), ("Authorization", "Token x")],
            &wav,
        );
        assert_eq!(status_of(&resp), 401);
        // Valid token + valid X-Request-Id -> not 401
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        assert_ne!(status_of(&resp), 401);
        // Response must never contain the token.
        assert!(!resp.contains(TEST_TOKEN));
    }

    #[test]
    fn x_request_id_required_and_validated() {
        isolate_dir();
        let (addr, gate, events) = start_test_server_with(Arc::new(AdmissionGate::new()));
        let wav = canonical_wav(100);

        // Missing X-Request-Id -> 400
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[
                ("Content-Type", "audio/wav"),
                ("Authorization", TEST_BEARER),
            ],
            &wav,
        );
        assert_eq!(status_of(&resp), 400);

        // Invalid (non-UUID) X-Request-Id -> 400
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[
                ("Content-Type", "audio/wav"),
                ("Authorization", TEST_BEARER),
                ("X-Request-Id", "not-a-uuid"),
            ],
            &wav,
        );
        assert_eq!(status_of(&resp), 400);

        // Valid UUID -> 201 (Delivery 1B: requires the WebView admission decision
        // and the post-save run-start acknowledgement, simulated here).
        {
            let (gate, events) = (Arc::clone(&gate), Arc::clone(&events));
            std::thread::spawn(move || {
                for _ in 0..300 {
                    if gate.resolve_admission(TEST_UUID, true, None) {
                        break;
                    }
                    std::thread::sleep(TestDuration::from_millis(5));
                }
                for _ in 0..300 {
                    if events.lock().unwrap().iter().any(|(e, _)| e == "watch://audio-ready") {
                        break;
                    }
                    std::thread::sleep(TestDuration::from_millis(5));
                }
                assert!(gate.resolve_run_started(TEST_UUID));
            });
        }
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        assert_eq!(status_of(&resp), 201);
    }

    #[test]
    fn content_type_enforced_and_10_mib_limit() {
        isolate_dir();
        let addr = start_test_server();
        let wav = canonical_wav(100);

        // Wrong content type -> 400 (auth + X-Request-Id valid)
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[
                ("Content-Type", "text/plain"),
                ("Authorization", TEST_BEARER),
                ("X-Request-Id", TEST_UUID),
            ],
            &wav,
        );
        assert_eq!(status_of(&resp), 400);

        // >10 MiB -> 413 (Content-Length check)
        let huge = vec![0u8; MAX_BODY_BYTES + 1];
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &huge);
        assert_eq!(status_of(&resp), 413);
    }

    #[test]
    fn malformed_wav_rejected_400() {
        isolate_dir();
        let addr = start_test_server();

        // Not a WAV at all
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), b"hello world not a wav");
        assert_eq!(status_of(&resp), 400);

        // Truncated WAV
        let mut wav = canonical_wav(100);
        wav.truncate(50);
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        assert_eq!(status_of(&resp), 400);
    }

    #[test]
    fn save_atomically_probe_reports_error() {
        let dir = std::env::temp_dir().join(format!("sayit-probe-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let target = dir.join("received_watch.wav");
        let wav = canonical_wav(32000);
        match save_atomically(&dir, &target, &wav) {
            Ok(()) => eprintln!("PROBE save_atomically OK target_exists={}", target.exists()),
            Err(e) => eprintln!("PROBE save_atomically ERROR: {e}"),
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn successful_upload_returns_201_with_metadata_and_sha() {
        let dir = isolate_dir();
        let (addr, gate, events) = start_test_server_with(Arc::new(AdmissionGate::new()));
        // Delivery 1B: simulate the WebView admission decision + run-start ack.
        {
            let (gate, events) = (Arc::clone(&gate), Arc::clone(&events));
            std::thread::spawn(move || {
                for _ in 0..300 {
                    if gate.resolve_admission(TEST_UUID, true, None) {
                        break;
                    }
                    std::thread::sleep(TestDuration::from_millis(5));
                }
                for _ in 0..300 {
                    if events.lock().unwrap().iter().any(|(e, _)| e == "watch://audio-ready") {
                        break;
                    }
                    std::thread::sleep(TestDuration::from_millis(5));
                }
                assert!(gate.resolve_run_started(TEST_UUID));
            });
        }
        let wav = canonical_wav(32000); // 1 second of 16 kHz mono

        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        let status = status_of(&resp);
        assert_eq!(status, 201);
        // The echoed requestId must be exactly the X-Request-Id we sent.
        assert!(resp.contains(&format!("\"requestId\":\"{TEST_UUID}\"")));
        assert!(resp.contains("\"bytes\":32044")); // 44 header + 32000
        assert!(resp.contains("\"sampleCount\":16000"));
        assert!(resp.contains("\"audioDurationMs\":1000"));
        // Lowercase hex sha256 of the full WAV.
        let expected_sha = hex_sha256(&wav);
        assert!(resp.contains(&format!("\"sha256\":\"{expected_sha}\"")));

        // The accepted file exists and matches.
        let target = dir.join("received_watch.wav");
        let saved = std::fs::read(&target).expect("received_watch.wav written");
        assert_eq!(saved, wav);
        // No temp files left behind.
        let leftovers: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_name().to_string_lossy().contains(".tmp."))
            .collect();
        assert!(leftovers.is_empty(), "temp files must be cleaned: {leftovers:?}");
    }

    #[test]
    fn atomic_replacement_keeps_previous_file_on_failure() {
        let dir = isolate_dir();
        let target = dir.join("received_watch.wav");
        let previous = b"PREVIOUS ACCEPTED WAV CONTENT THAT MUST SURVIVE".to_vec();
        std::fs::write(&target, &previous).unwrap();

        // Force a failure: make the temp-file parent a non-directory so the
        // temp write (or the replace) cannot succeed.
        let broken_dir = dir.join("not-a-dir");
        std::fs::write(&broken_dir, b"i am a file, not a dir").unwrap();
        let broken = save_atomically(&broken_dir, &broken_dir.join("received_watch.wav"), &canonical_wav(64));
        assert!(broken.is_err());

        // The previous accepted file is intact, byte for byte.
        let after = std::fs::read(&target).unwrap();
        assert_eq!(after, previous);
    }

    #[test]
    fn unknown_routes_and_methods() {
        let addr = start_test_server();
        let resp = raw_request(&addr, "GET", "/api/nope", &[], b"");
        assert_eq!(status_of(&resp), 404);
        let resp = raw_request(&addr, "DELETE", "/api/health", &[], b"");
        assert_eq!(status_of(&resp), 405);
    }

    /// Repair 2 regression: the WebView resolves the admission decision and the
    /// run-start ack SYNCHRONOUSLY inside the emit call stack. Because the waiter
    /// is registered before the emit, the resolve must find it — no stale, no
    /// timeout, no polling helper.
    #[test]
    fn sync_resolve_inside_emit_stack_is_accepted() {
        let dir = isolate_dir();
        let gate = Arc::new(AdmissionGate::new());
        let cfg = Arc::new(ReceiverConfig {
            bind_ip: "127.0.0.1".parse().unwrap(),
            port: 0,
            dev_token: "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234".to_string(),
        });
        let sink: EventSink = {
            let gate = Arc::clone(&gate);
            Arc::new(move |event: &str, _payload: serde_json::Value| {
                match event {
                    "watch://admission-request" => {
                        assert!(gate.resolve_admission(TEST_UUID, true, None));
                    }
                    "watch://audio-ready" => {
                        assert!(gate.resolve_run_started(TEST_UUID));
                    }
                    _ => {}
                }
            })
        };
        let server = ReceiverServer::start_with(cfg, Arc::clone(&gate), sink).expect("bind");
        let addr = server.server.server_addr().to_string();
        std::thread::spawn(move || server.run());
        std::thread::sleep(TestDuration::from_millis(50));

        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &canonical_wav(32000));
        assert_eq!(status_of(&resp), 201);
        assert_eq!(gate.snapshot().state, "Reserved");
        assert!(gate.abort(TEST_UUID, "run_finished"));
        assert_eq!(gate.snapshot().state, "Idle");
        let _ = dir;
    }

    /// Repair 1 regression: every payload handed to the event sink serializes as
    /// a JSON OBJECT with the frozen fields — never a JSON string.
    #[test]
    fn event_payloads_are_json_objects_with_frozen_fields() {
        let dir = isolate_dir();
        let (addr, gate, events) = start_test_server_with(Arc::new(AdmissionGate::new()));
        {
            let g = Arc::clone(&gate);
            let events = Arc::clone(&events);
            std::thread::spawn(move || {
                for _ in 0..300 {
                    if g.resolve_admission(TEST_UUID, true, None) {
                        break;
                    }
                    std::thread::sleep(TestDuration::from_millis(5));
                }
                for _ in 0..300 {
                    if events.lock().unwrap().iter().any(|(e, _)| e == "watch://audio-ready") {
                        break;
                    }
                    std::thread::sleep(TestDuration::from_millis(5));
                }
                assert!(g.resolve_run_started(TEST_UUID));
            });
        }
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &canonical_wav(32000));
        assert_eq!(status_of(&resp), 201);

        let log = events.lock().unwrap();
        let admission = &log.iter().find(|(e, _)| e == "watch://admission-request").unwrap().1;
        let ready = &log.iter().find(|(e, _)| e == "watch://audio-ready").unwrap().1;
        for payload in [admission, ready] {
            assert!(payload.is_object(), "payload must be a JSON object");
            assert!(!payload.is_string(), "payload must never be a JSON string");
            let serialized = serde_json::to_string(payload).unwrap();
            assert!(serialized.starts_with('{'), "serialized payload must start with '{{': {serialized}");
            assert!(payload.get("requestId").unwrap().is_string());
            assert!(payload.get("bytes").unwrap().is_u64());
            assert!(payload.get("sampleCount").unwrap().is_u64());
            assert!(payload.get("durationMs").unwrap().is_u64());
        }
        assert_eq!(admission.get("requestId").unwrap().as_str(), Some(TEST_UUID));
        assert_eq!(admission.get("sampleCount").unwrap().as_u64(), Some(16000));
        drop(log);
        let _ = dir;
    }

    #[test]
    fn hex_sha256_is_lowercase() {
        let digest = hex_sha256(b"abc");
        assert_eq!(digest.len(), 64);
        assert!(digest.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
        assert_eq!(hex_sha256(b"abc"), hex_sha256(b"abc"));
        assert_ne!(hex_sha256(b"abc"), hex_sha256(b"abd"));
    }

    // ─── Delivery 1B admission / acknowledgement integration (Provider Contract §B) ───

    use crate::watch_admission::admission::{ADMISSION_TIMEOUT, LEASE, RUN_START_ACK_TIMEOUT};
    use std::time::Duration as TestDuration;

    /// Waits until the receiver's pending admission slot exists, then resolves it.
    fn resolve_admission_when_pending(gate: &Arc<AdmissionGate>, id: &str, accepted: bool, reason: Option<&str>) {
        for _ in 0..300 {
            if gate.pending_admission_count_for_test() > 0 {
                assert!(gate.resolve_admission(id, accepted, reason.map(str::to_string)));
                return;
            }
            std::thread::sleep(TestDuration::from_millis(5));
        }
        panic!("admission request never arrived");
    }

    fn wait_for_event(events: &Arc<Mutex<Vec<(String, serde_json::Value)>>>, name: &str) {
        for _ in 0..300 {
            if events.lock().unwrap().iter().any(|(e, _)| e == name) {
                return;
            }
            std::thread::sleep(TestDuration::from_millis(5));
        }
        panic!("event {name} never emitted");
    }

    #[test]
    fn busy_gate_returns_409_and_preserves_previous_file() {
        let dir = isolate_dir();
        // A previously accepted file must survive a busy rejection untouched.
        let previous = canonical_wav(16000);
        std::fs::write(dir.join("received_watch.wav"), &previous).unwrap();

        let gate = Arc::new(AdmissionGate::new());
        gate.force_reserve_for_test(TEST_UUID, TestDuration::from_secs(1));
        let (addr, gate, _events) = start_test_server_with(gate);

        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &canonical_wav(8000));
        assert_eq!(status_of(&resp), 409);
        assert!(resp.contains(r#""error":"busy""#));
        assert!(resp.contains(r#""detail":"already_reserved""#));
        // The old accepted file is intact; no save occurred.
        assert_eq!(std::fs::read(dir.join("received_watch.wav")).unwrap(), previous);
        // The gate reservation itself is untouched by the rejected upload.
        assert_eq!(gate.snapshot().request_id.as_deref(), Some(TEST_UUID));
    }

    #[test]
    fn preparation_failure_returns_500_without_touching_disk() {
        let dir = isolate_dir();
        let (addr, gate, _events) = start_test_server_with(Arc::new(AdmissionGate::new()));
        {
            let g = Arc::clone(&gate);
            std::thread::spawn(move || {
                resolve_admission_when_pending(&g, TEST_UUID, false, Some("context_capture_failed"));
            });
        }
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &canonical_wav(8000));
        assert_eq!(status_of(&resp), 500);
        assert!(resp.contains(r#""error":"admission failed""#));
        assert!(resp.contains(r#""detail":"context_capture_failed""#));
        assert!(!dir.join("received_watch.wav").exists(), "no file may be written on rejection");
        assert_eq!(gate.snapshot().state, "Idle");
    }

    #[test]
    fn bridge_timeout_returns_409_and_keeps_gate_idle() {
        let dir = isolate_dir();
        // Short admission timeout so the test stays fast; the WebView never answers.
        let (addr, gate, _events) =
            start_test_server_with(Arc::new(AdmissionGate::with_timeouts(TestDuration::from_millis(150), RUN_START_ACK_TIMEOUT, LEASE)));
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &canonical_wav(8000));
        assert_eq!(status_of(&resp), 409);
        assert!(resp.contains(r#""detail":"bridge_timeout""#));
        assert!(!dir.join("received_watch.wav").exists());
        assert_eq!(gate.snapshot().state, "Idle");
    }

    #[test]
    fn full_accept_flow_201_only_after_run_started_ack() {
        let dir = isolate_dir();
        let (addr, gate, events) = start_test_server_with(Arc::new(AdmissionGate::new()));
        {
            let g = Arc::clone(&gate);
            let events = Arc::clone(&events);
            std::thread::spawn(move || {
                // Simulated WebView: Phase A+B accepted.
                resolve_admission_when_pending(&g, TEST_UUID, true, None);
                // Simulated PCM read + connect + start, then the run-start ack.
                wait_for_event(&events, "watch://audio-ready");
                assert!(g.resolve_run_started(TEST_UUID));
            });
        }
        let wav = canonical_wav(32000);
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        assert_eq!(status_of(&resp), 201);
        assert!(resp.contains(r#""requestId":"123e4567-e89b-12d3-a456-426614174000""#));
        // The accepted file exists and matches byte-for-byte.
        assert_eq!(std::fs::read(dir.join("received_watch.wav")).unwrap(), wav);

        // Event ordering: admission-request strictly before audio-ready; no aborts.
        let log = events.lock().unwrap();
        let admission_idx = log.iter().position(|(e, _)| e == "watch://admission-request").expect("admission-request");
        let ready_idx = log.iter().position(|(e, _)| e == "watch://audio-ready").expect("audio-ready");
        assert!(admission_idx < ready_idx);
        assert!(!log.iter().any(|(e, _)| e == "watch://run-abort"));
        drop(log);

        // The run owns the reservation until the run finishes.
        assert_eq!(gate.snapshot().state, "Reserved");
        assert!(gate.abort(TEST_UUID, "run_finished"));
        assert_eq!(gate.snapshot().state, "Idle");
    }

    #[test]
    fn ack_timeout_returns_500_and_releases_gate() {
        let dir = isolate_dir();
        let (addr, gate, events) =
            start_test_server_with(Arc::new(AdmissionGate::with_timeouts(ADMISSION_TIMEOUT, TestDuration::from_millis(150), LEASE)));
        {
            let g = Arc::clone(&gate);
            std::thread::spawn(move || {
                resolve_admission_when_pending(&g, TEST_UUID, true, None);
                // The WebView never acknowledges: PCM read/connect/start hung.
            });
        }
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &canonical_wav(8000));
        assert_eq!(status_of(&resp), 500);
        assert!(resp.contains(r#""error":"run failed to start""#));
        assert!(resp.contains(r#""detail":"ack_timeout""#));
        // Both sides aborted: gate released, run-abort emitted.
        assert_eq!(gate.snapshot().state, "Idle");
        assert!(events.lock().unwrap().iter().any(|(e, p)| e == "watch://run-abort" && p.get("reason").and_then(|r| r.as_str()) == Some("ack_timeout")));
        let _ = dir;
    }

    #[test]
    fn save_failure_aborts_gate_and_returns_500() {
        // Point the receiver dir at a plain file so the atomic save cannot create
        // its directory: a durable-save failure after successful admission.
        let dir = isolate_dir();
        let blocker = dir.join("not-a-dir");
        std::fs::write(&blocker, b"file, not dir").unwrap();
        std::env::set_var("SAYIT_WATCH_RECEIVER_DIR", &blocker);

        let (addr, gate, events) = start_test_server_with(Arc::new(AdmissionGate::new()));
        {
            let g = Arc::clone(&gate);
            std::thread::spawn(move || {
                resolve_admission_when_pending(&g, TEST_UUID, true, None);
            });
        }
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &canonical_wav(8000));
        assert_eq!(status_of(&resp), 500);
        assert!(resp.contains(r#""error":"storage failure""#));
        // The reservation is released on the Rust side (JS half via run-abort event).
        assert_eq!(gate.snapshot().state, "Idle");
        assert!(events.lock().unwrap().iter().any(|(e, p)| e == "watch://run-abort" && p.get("reason").and_then(|r| r.as_str()) == Some("save_failed")));
        std::env::remove_var("SAYIT_WATCH_RECEIVER_DIR");
    }
}
