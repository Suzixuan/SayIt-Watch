//! Delivery 1A debug-only Windows LAN receiver.
//!
//! This module compiles and starts ONLY under `debug_assertions`. Release
//! builds contain no active receiver startup path (see main.rs and the
//! release-guard tests in this module).
//!
//! It binds one explicit RFC1918 LAN IPv4 and serves two endpoints:
//! - `GET /api/health` — minimal JSON identifying a debug receiver.
//! - `POST /api/watch/audio` — authenticated WAV ingest, saved atomically to
//!   `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.

pub mod config;
// Delivery 1C: standard DNS-SD/mDNS advertisement. Debug-only for the same
// reason as the whole receiver — release builds contain no advertisement entry
// point (see `mdns.rs` and the release-guard tests in this module).
#[cfg(debug_assertions)]
pub mod mdns;
pub mod server;
pub mod wav;

use std::sync::Arc;

/// Starts the debug receiver on a dedicated blocking thread. Returns an error
/// (receiver does not start) when configuration is missing or invalid.
/// Never logs the token.
pub fn start() -> Result<(), Box<dyn std::error::Error>> {
    let cfg = Arc::new(config::load()?);
    let thread_cfg = Arc::clone(&cfg);
    std::thread::Builder::new()
        .name("watch-receiver".to_string())
        .spawn(move || {
            // tiny_http is fully blocking; the accept loop lives on this thread.
            let server = match server::ReceiverServer::start(thread_cfg) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("watch receiver failed to start: {}", e);
                    return;
                }
            };
            log::info!(
                "watch receiver listening on {}:{} (dev token present: {})",
                server.bind_ip(),
                server.bind_port(),
                cfg.has_dev_token(),
            );
            // Delivery 1C: advertise only AFTER the port was bound successfully,
            // so the Watch can never discover an address that is not listening.
            #[cfg(debug_assertions)]
            mdns::spawn_registration(&cfg);
            server.run();
        })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::fs;

    #[test]
    fn release_guard_start_is_debug_only() {
        // The receiver startup path must be compiled out of release builds.
        // Check the source-level guard that main.rs uses.
        // (cargo test runs with CWD = crate root, i.e. client/src-tauri)
        let main_src = fs::read_to_string("src/main.rs")
            .expect("main.rs must exist next to the watch_receiver module");
        let start_marker = "watch_receiver::start";
        assert!(
            main_src.contains(start_marker),
            "main.rs must call watch_receiver::start"
        );
        let block_start = main_src
            .find(start_marker)
            .expect("start marker present");
        // The start call lives inside `.setup()` (after the event sink is
        // registered), so the enclosing `#[cfg(debug_assertions)]` block may be
        // well over 400 characters earlier — scan back a generous window.
        // Use char-boundary-safe slicing (the comment may contain multi-byte UTF-8).
        let limit = block_start.saturating_sub(4000);
        let win_start = main_src[..block_start]
            .char_indices()
            .rev()
            .find(|(i, _)| *i <= limit)
            .map(|(i, _)| i)
            .unwrap_or(0);
        let window = &main_src[win_start..block_start];
        assert!(
            window.contains("#[cfg(debug_assertions)]"),
            "watch_receiver::start must be guarded by #[cfg(debug_assertions)]"
        );
    }

    #[test]
    fn module_gate_is_debug_assertions() {
        // The module declaration in main.rs must also be debug-only.
        let main_src = fs::read_to_string("src/main.rs").expect("main.rs exists");
        let idx = main_src.find("mod watch_receiver;").expect("module declared");
        let window = &main_src[idx.saturating_sub(200)..idx];
        assert!(
            window.contains("#[cfg(debug_assertions)]"),
            "mod watch_receiver must be guarded by #[cfg(debug_assertions)]"
        );
    }

    #[test]
    fn event_sink_registered_before_receiver_start() {
        // Regression guard for the bridge_timeout defect: the receiver snapshots
        // the global EVENT_SINK at construction, so if `watch_receiver::start`
        // runs before `set_event_sink` the server is wired to the no-op sink and
        // every admission event is silently dropped (the WebView never sees
        // watch://admission-request -> 409 bridge_timeout). The source order must
        // keep set_event_sink strictly before watch_receiver::start.
        let main_src = fs::read_to_string("src/main.rs")
            .expect("main.rs must exist next to the watch_receiver module");
        // Match the concrete calls, not explanatory comments containing the same
        // symbol names; otherwise reversing the calls could leave this test green.
        let sink_pos = main_src
            .find("watch_receiver::server::set_event_sink(std::sync::Arc::new(")
            .expect("main.rs must register the concrete event sink");
        let start_pos = main_src
            .find("match watch_receiver::start()")
            .expect("main.rs must start the receiver from setup");
        assert!(
            sink_pos < start_pos,
            "set_event_sink must be registered BEFORE watch_receiver::start (otherwise the receiver snaps the no-op sink -> every admission event is dropped -> 409 bridge_timeout)"
        );
    }

    #[test]
    fn discovery_module_gate_is_debug_assertions() {
        // Delivery 1C: the mDNS advertisement module must be declared inside the
        // same debug-only receiver module, so release builds contain no
        // advertisement code path at all.
        let mod_src = fs::read_to_string("src/watch_receiver/mod.rs")
            .expect("watch_receiver/mod.rs must exist");
        let idx = mod_src
            .find("pub mod mdns;")
            .expect("mod.rs must declare the mdns module");
        let window = &mod_src[idx.saturating_sub(400)..idx];
        assert!(
            window.contains("#[cfg(debug_assertions)]"),
            "pub mod mdns must be guarded by #[cfg(debug_assertions)]"
        );
    }

    #[test]
    fn discovery_registration_requires_a_bound_receiver() {
        // The advertisement must be issued only after the receiver's own bind
        // succeeded, and only under the debug gate: a registration that ran
        // before the bind (or without the gate) could advertise a port that is
        // not actually listening, or ship an advertisement path in release.
        let mod_src = fs::read_to_string("src/watch_receiver/mod.rs")
            .expect("watch_receiver/mod.rs must exist");
        let bind_pos = mod_src
            .find("let server = match server::ReceiverServer::start(thread_cfg)")
            .expect("the receiver must bind through ReceiverServer::start");
        let register_pos = mod_src
            .find("mdns::spawn_registration(&cfg)")
            .expect("the receiver must register the mDNS service");
        assert!(
            bind_pos < register_pos,
            "mDNS registration must happen AFTER a successful receiver bind"
        );
        let window = &mod_src[bind_pos..register_pos];
        assert!(
            window.contains("#[cfg(debug_assertions)]"),
            "the mDNS registration call must be guarded by #[cfg(debug_assertions)]"
        );
    }
}
