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

/// Why the debug receiver could not start, in the only two forms a user can act on.
///
/// 1C-D-04@R7 §2C: a receiver that silently does not start (and only writes a log line) is
/// indistinguishable from a working one for the user. These variants carry a short, safe,
/// actionable sentence — never a config value, a token, a path or a raw OS error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReceiverStartError {
    /// No usable local connection configuration was found.
    MissingConfig,
    /// The configured address/port could not be bound (usually already in use).
    BindFailed,
}

impl ReceiverStartError {
    /// The user-facing sentence shown by the app. Chinese, short, no secrets.
    pub fn user_message(self) -> &'static str {
        match self {
            ReceiverStartError::MissingConfig => {
                "本机还没有连接配置：手表传输无法启动。\n请在 SayIt 设置里填写本机的访问令牌后重试。"
            }
            ReceiverStartError::BindFailed => {
                "手表接收端口无法绑定：可能已被其它程序占用。\n请关闭占用该端口的程序后重试；不会结束任何现有进程。"
            }
        }
    }

    /// A stable, non-secret identifier for logs and tests.
    pub fn category(self) -> &'static str {
        match self {
            ReceiverStartError::MissingConfig => "watch-receiver:config-missing",
            ReceiverStartError::BindFailed => "watch-receiver:bind-failed",
        }
    }
}

impl std::fmt::Display for ReceiverStartError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.category())
    }
}

impl std::error::Error for ReceiverStartError {}

/// Starts the debug receiver. Returns an error (receiver does not start) when
/// configuration is missing or invalid, or when the configured address cannot be
/// bound. Never logs or returns the token.
///
/// 1C-D-04@R7 §2C: the bind happens on the CALLER's thread, so a failure is known
/// before this function returns and the app can show it to the user instead of
/// discovering it only in a log. The accept loop still runs on its own thread.
pub fn start() -> Result<(), ReceiverStartError> {
    let cfg = Arc::new(config::load().map_err(|e| {
        log::error!("watch receiver not started: {}", e);
        ReceiverStartError::MissingConfig
    })?);

    // Kept verbatim: the release-guard test pins that mDNS registration happens only
    // after this bind succeeded, under the debug gate.
    let server = match server::ReceiverServer::start(Arc::clone(&cfg)) {
        Ok(s) => s,
        Err(e) => {
            // The raw error may embed the address; only the category is logged.
            log::error!("watch receiver failed to bind: {}", e);
            return Err(ReceiverStartError::BindFailed);
        }
    };

    std::thread::Builder::new()
        .name("watch-receiver".to_string())
        .spawn(move || {
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
            // tiny_http is fully blocking; the accept loop lives on this thread.
            server.run();
        })
        .map_err(|e| {
            log::error!("watch receiver thread failed to start: {}", e);
            ReceiverStartError::BindFailed
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
            .find("let server = match server::ReceiverServer::start(")
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

    // ─── 1C-D-04@R7 §2C: startup failures must be visible and safe ───────────

    #[test]
    fn startup_failure_messages_are_short_actionable_and_secret_free() {
        for error in [
            super::ReceiverStartError::MissingConfig,
            super::ReceiverStartError::BindFailed,
        ] {
            let message = error.user_message();
            assert!(!message.is_empty(), "a failure needs a visible sentence");
            assert!(
                message.chars().count() <= 140,
                "the visible message must stay short: {message}"
            );
            // Never echo a configuration value, a token, a path or a raw OS error.
            for forbidden in [
                "token=",
                "SAYIT_WATCH",
                "18099",
                "192.168",
                "C:\\",
                "os error",
                "Microsoft",
                "Bearer",
            ] {
                assert!(
                    !message.contains(forbidden),
                    "the visible message must not contain {forbidden:?}: {message}"
                );
            }
        }
    }

    #[test]
    fn startup_failure_categories_are_distinct_and_stable() {
        assert_ne!(
            super::ReceiverStartError::MissingConfig.category(),
            super::ReceiverStartError::BindFailed.category(),
        );
        assert_eq!(
            super::ReceiverStartError::MissingConfig.category(),
            "watch-receiver:config-missing"
        );
        assert_eq!(
            super::ReceiverStartError::BindFailed.category(),
            "watch-receiver:bind-failed"
        );
        // Display must be the category, so a log line can never carry a config value.
        assert_eq!(
            super::ReceiverStartError::BindFailed.to_string(),
            "watch-receiver:bind-failed"
        );
    }

    #[test]
    fn a_missing_configuration_is_reported_as_missing_config_not_a_bind_failure() {
        // The two variants exist because the user's action differs. This pins the mapping
        // in `start()`: `config::load()` failure -> MissingConfig.
        let mod_src = fs::read_to_string("src/watch_receiver/mod.rs")
            .expect("watch_receiver/mod.rs must exist");
        let load_pos = mod_src
            .find("config::load().map_err(")
            .expect("start() must classify a config load failure");
        let window = &mod_src[load_pos..(load_pos + 260).min(mod_src.len())];
        assert!(
            window.contains("ReceiverStartError::MissingConfig"),
            "a config load failure must map to MissingConfig"
        );
        let bind_pos = mod_src
            .find("server::ReceiverServer::start(Arc::clone(&cfg))")
            .expect("start() must bind the configured address");
        let bind_window = &mod_src[bind_pos..(bind_pos + 320).min(mod_src.len())];
        assert!(
            bind_window.contains("ReceiverStartError::BindFailed"),
            "a bind failure must map to BindFailed"
        );
    }

    #[test]
    fn the_receiver_reports_a_start_failure_instead_of_only_logging_it() {
        // The defect this closes: the old `start()` swallowed a bind failure inside the
        // spawned thread and returned Ok, so the app could not show anything.
        let mod_src = fs::read_to_string("src/watch_receiver/mod.rs")
            .expect("watch_receiver/mod.rs must exist");
        let start = mod_src
            .split("pub fn start() -> Result<(), ReceiverStartError> {")
            .nth(1)
            .expect("start() must return the typed error");
        let bind_pos = start
            .find("ReceiverServer::start(")
            .expect("start() must bind before spawning");
        let spawn_pos = start
            .find("std::thread::Builder::new()")
            .expect("start() must still run the accept loop on its own thread");
        assert!(
            bind_pos < spawn_pos,
            "the bind must happen on the caller's thread so a failure is returned, not logged"
        );
    }
}
