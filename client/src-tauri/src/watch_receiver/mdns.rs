//! Delivery 1C: standard DNS-SD/mDNS advertisement of the Windows debug receiver.
//!
//! Frozen contract (docs/DELIVERY-1C-D-AUTO-DISCOVERY-HANDOFF.md §3):
//! - Standard DNS-SD via `mdns-sd`; no custom UDP broadcast protocol.
//! - The service is registered ONLY after the debug receiver bound its port
//!   successfully (see `watch_receiver::start`).
//! - Service type: `_sayit-watch._tcp.local.` (the full name; the Watch uses the
//!   platform form `_sayit-watch._tcp.`).
//! - TXT records carry non-sensitive metadata only: `protocol=1` and
//!   `path=/api/watch/discovery`. The token, a token digest, the user name,
//!   audio content, the target window, the machine name and local paths are
//!   never advertised.
//!
//! Repair 3 (log truthfulness only): enqueueing is not advertising. `mdns-sd`
//! opens its multicast sockets lazily inside the daemon thread, so
//! `ServiceDaemon::register()` returning `Ok` only proves the command was queued.
//! The registration worker therefore consumes the daemon's monitor events for the
//! whole process lifetime and reports three separate things:
//!   `registration queued`  — the command was accepted (NOT an advertisement),
//!   `announcement sent`    — the daemon actually announced OUR fullname,
//!   `daemon error`         — the daemon reported a failure category.
//! Classification output is used instead of the library's raw event text: the
//! `Announce` detail and raw error strings may embed interface names or paths and
//! carry no diagnostic value that the category does not already give.
//!
//! Only the instance/type/address/port and those categories are ever logged.

use crate::watch_receiver::config::ReceiverConfig;
use mdns_sd::{DaemonEvent, RecvTimeoutError, ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::net::{Ipv4Addr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;
use std::time::Duration;

/// Frozen service type (full name as `mdns-sd` requires).
pub const SERVICE_TYPE: &str = "_sayit-watch._tcp.local.";

/// Frozen service instance name. Deliberately generic: it does not embed the
/// machine name or any other host identity.
pub const INSTANCE_NAME: &str = "SayIt";

/// TXT key carrying the discovery protocol version.
pub const TXT_PROTOCOL_KEY: &str = "protocol";

/// Frozen discovery protocol version.
pub const PROTOCOL_VERSION: &str = "1";

/// TXT key carrying the authenticated discovery probe path.
pub const TXT_PATH_KEY: &str = "path";

/// Frozen authenticated discovery probe path.
pub const DISCOVERY_PATH: &str = "/api/watch/discovery";

/// How long the registration worker tolerates hearing nothing at all before it
/// emits its ONE "not yet announced" warning. It keeps consuming events after
/// that: a first announcement can legitimately arrive later, and losing the
/// socket afterwards is exactly the kind of lifecycle event worth watching.
pub const FIRST_ANNOUNCE_WARN_MS: u64 = 5_000;

/// How long the registration worker blocks on one event before re-checking the
/// warning deadline. Also the shutdown granularity.
const EVENT_POLL_MS: u64 = 250;

// Log lines are constants so a test can pin their exact wording (and prove they
// carry no sensitive field) instead of matching a format string by hand.
pub(crate) const LOG_QUEUED: &str = "watch discovery registration queued (not yet announced)";
pub(crate) const LOG_ANNOUNCED: &str = "watch discovery announcement sent";
/// The timeout line states ONLY what was observed. It deliberately makes no
/// claim about multicast, the firewall, daemon health or the manual path, and it
/// is only emitted when the daemon reported no error either.
pub(crate) const LOG_NOT_YET: &str =
    "watch discovery: no Announce for this service was observed yet";
pub(crate) const LOG_DAEMON_ERROR: &str = "watch discovery daemon error";
pub(crate) const LOG_UNAVAILABLE: &str = "watch discovery unavailable (manual IP/port still works)";
pub(crate) const LOG_MONITOR_STOPPED: &str = "watch discovery monitor stopped";
pub(crate) const LOG_WORKER_SPAWN_FAILED: &str = "watch discovery worker could not start";

/// The only text logged next to a daemon error.
///
/// `DaemonEvent::Error` carries a raw library string that can embed interface
/// names or paths and adds nothing diagnostically over the category, so the
/// category is what gets logged. It also has to be a compile-time constant: a
/// runtime string would be an unreviewed way for raw text to reach the log.
pub(crate) const DAEMON_ERROR_CATEGORY: &str = "daemon-reported failure (category only)";

/// Keeps the mDNS daemon thread alive for the whole process lifetime. Dropping
/// the handle does not stop the daemon thread, but holding it keeps the intent
/// explicit.
static DAEMON: OnceLock<ServiceDaemon> = OnceLock::new();

/// One-shot "the daemon really announced our instance" flag. Deliberately the
/// only shared state: the worker keeps consuming events for the process lifetime
/// and does not need a state machine.
static ANNOUNCED: AtomicBool = AtomicBool::new(false);

/// Set once the daemon has reported ANY error, so the timeout line never claims
/// that no error was seen. The error category itself is logged when it arrives.
static DAEMON_ERROR_SEEN: AtomicBool = AtomicBool::new(false);

/// What one daemon event means for our registration. Pure and total, so the
/// observable contract is unit-testable without a daemon.
///
/// A closed monitor channel is NOT an event: the worker sees it as
/// `RecvTimeoutError::Disconnected` and handles it separately.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EventClass {
    /// A real announcement of *our* service instance.
    OurAnnouncement,
    /// The daemon reported an error (bind failure, multicast not permitted, ...).
    DaemonError,
    /// Something else happened; it proves neither an announcement nor a failure.
    Irrelevant,
}

/// True when `fullname` is our own service instance (case-insensitive, as DNS
/// names are).
pub fn is_our_fullname(fullname: &str, registered: &str) -> bool {
    fullname.eq_ignore_ascii_case(registered)
}

/// Classifies one daemon event against the fullname we registered.
///
/// Only a matching `Announce` counts as evidence of a broadcast; every other
/// event stays `Irrelevant` so nothing else can be mistaken for one. The event's
/// own text is never returned — callers log the class, not the payload.
pub fn classify_event(event: &DaemonEvent, registered_fullname: &str) -> EventClass {
    match event {
        DaemonEvent::Announce(fullname, _detail) if is_our_fullname(fullname, registered_fullname) => {
            EventClass::OurAnnouncement
        }
        DaemonEvent::Error(_) => EventClass::DaemonError,
        _ => EventClass::Irrelevant,
    }
}

/// True when the warning window elapsed with neither an announcement nor any
/// daemon error. The warning states only what was observed; it does not claim
/// that multicast works or that the manual path is unaffected.
pub fn should_warn_not_yet(
    announced: bool,
    daemon_error_seen: bool,
    warned_already: bool,
    waited: Duration,
    budget: Duration,
) -> bool {
    !announced && !daemon_error_seen && !warned_already && waited >= budget
}

/// Registers the debug receiver as `_sayit-watch._tcp` on one worker thread.
///
/// The worker owns the monitor for the whole process lifetime: it logs queue
/// acceptance first, then keeps consuming events (so a later announcement or a
/// socket loss is still visible) and emits at most one "not announced yet"
/// warning. A failure to spawn is reported, never swallowed.
pub fn spawn_registration(cfg: &ReceiverConfig) {
    let bind_ip = cfg.bind_ip;
    let port = cfg.port;
    match std::thread::Builder::new()
        .name("watch-mdns-register".to_string())
        .spawn(move || registration_worker(bind_ip, port))
    {
        Ok(_) => {}
        Err(e) => log::error!(
            "{}: {} (manual IP/port still works)",
            LOG_WORKER_SPAWN_FAILED,
            e.kind()
        ),
    }
}

/// The whole registration attempt with truthful, classified logging.
///
/// Runs on the registration worker thread and does not return until the monitor
/// channel ends; the daemon itself stays alive for the process lifetime.
fn registration_worker(bind_ip: Ipv4Addr, port: u16) {
    let daemon = match daemon() {
        Ok(daemon) => daemon,
        Err(category) => {
            log::warn!(
                "{}: {} on {}:{}, daemon {}",
                LOG_UNAVAILABLE,
                SERVICE_TYPE,
                bind_ip,
                port,
                category
            );
            return;
        }
    };

    // Subscribe BEFORE registering: a monitor attached afterwards would miss the
    // first announcement, which is the only local proof of a real broadcast.
    let events = match daemon.monitor() {
        Ok(events) => events,
        Err(e) => {
            log::warn!(
                "{}: {} on {}:{}, monitor {}",
                LOG_UNAVAILABLE,
                SERVICE_TYPE,
                bind_ip,
                port,
                error_category(&e)
            );
            return;
        }
    };

    let service_info = match build_service_info(bind_ip, port) {
        Ok(info) => info,
        Err(_reason) => {
            // The reason is our own wording, but only the category is logged to
            // keep one output shape for every failure.
            log::warn!(
                "{}: {} on {}:{} (record rejected)",
                LOG_UNAVAILABLE,
                SERVICE_TYPE,
                bind_ip,
                port
            );
            return;
        }
    };
    let fullname = service_info.get_fullname().to_string();

    if let Err(e) = daemon.register(service_info) {
        log::warn!(
            "{}: {} on {}:{}, register {}",
            LOG_UNAVAILABLE,
            SERVICE_TYPE,
            bind_ip,
            port,
            error_category(&e)
        );
        return;
    }

    // Queue acceptance only: the daemon has not necessarily opened a socket yet.
    log::info!(
        "{}: {} instance '{}' on {}:{} (protocol {})",
        LOG_QUEUED,
        SERVICE_TYPE,
        INSTANCE_NAME,
        bind_ip,
        port,
        PROTOCOL_VERSION,
    );

    let warn_after = Duration::from_millis(FIRST_ANNOUNCE_WARN_MS);
    let started = std::time::Instant::now();
    let mut warned_not_yet = false;
    loop {
        match events.recv_timeout(Duration::from_millis(EVENT_POLL_MS)) {
            Ok(event) => match classify_event(&event, &fullname) {
                EventClass::OurAnnouncement => {
                    if !ANNOUNCED.swap(true, Ordering::Relaxed) {
                        log::info!(
                            "{}: {} instance '{}' on {}:{} (protocol {})",
                            LOG_ANNOUNCED,
                            SERVICE_TYPE,
                            INSTANCE_NAME,
                            bind_ip,
                            port,
                            PROTOCOL_VERSION,
                        );
                    }
                    // Keep monitoring: later socket loss / IP changes still matter.
                }
                EventClass::DaemonError => {
                    // Record that an error was seen so the timeout line can never
                    // claim otherwise, and log only the safe category.
                    let first_error = !DAEMON_ERROR_SEEN.swap(true, Ordering::Relaxed);
                    log::error!(
                        "{}: {} (instance '{}' on {}:{})",
                        LOG_DAEMON_ERROR,
                        DAEMON_ERROR_CATEGORY,
                        INSTANCE_NAME,
                        bind_ip,
                        port,
                    );
                    if first_error {
                        log::debug!("{}: first error of this registration", LOG_DAEMON_ERROR);
                    }
                }
                EventClass::Irrelevant => {}
            },
            Err(RecvTimeoutError::Timeout) => {
                if should_warn_not_yet(
                    ANNOUNCED.load(Ordering::Relaxed),
                    DAEMON_ERROR_SEEN.load(Ordering::Relaxed),
                    warned_not_yet,
                    started.elapsed(),
                    warn_after,
                ) {
                    warned_not_yet = true;
                    // Only what was observed: no multicast, firewall or
                    // manual-path inference is stated here.
                    log::warn!(
                        "{}: {} instance '{}' on {}:{} — no Announce for this service was \
                         observed inside the first {} ms",
                        LOG_NOT_YET,
                        SERVICE_TYPE,
                        INSTANCE_NAME,
                        bind_ip,
                        port,
                        FIRST_ANNOUNCE_WARN_MS,
                    );
                }
            }
            // A disconnected channel is terminal, not a normal timeout: spinning
            // on it would busy-loop forever.
            Err(RecvTimeoutError::Disconnected) => {
                log::warn!("{}: channel disconnected", LOG_MONITOR_STOPPED);
                return;
            }
        }
    }
}

/// The process-wide daemon, created once.
fn daemon() -> Result<ServiceDaemon, &'static str> {
    if let Some(daemon) = DAEMON.get() {
        return Ok(daemon.clone());
    }
    let daemon = ServiceDaemon::new().map_err(|_| "unavailable")?;
    let _ = DAEMON.set(daemon.clone());
    Ok(daemon)
}

/// Maps a library error to a loggable category without echoing raw text.
fn error_category(error: &mdns_sd::Error) -> &'static str {
    match error {
        mdns_sd::Error::Again => "queue full",
        mdns_sd::Error::DaemonShutdown => "daemon shut down",
        mdns_sd::Error::Msg(_) => "message",
        mdns_sd::Error::ParseIpAddr(_) => "address parse",
        _ => "unknown",
    }
}

/// Pure builder for the advertised service record. Separated from the daemon so
/// the "no sensitive field is advertised" contract is unit-testable.
pub fn build_service_info(bind_ip: Ipv4Addr, port: u16) -> Result<ServiceInfo, String> {
    let addresses = advertised_addresses(bind_ip)?;
    let host_name = advertised_host_name(&addresses[0]);
    let mut properties: HashMap<String, String> = HashMap::new();
    properties.insert(TXT_PROTOCOL_KEY.to_string(), PROTOCOL_VERSION.to_string());
    properties.insert(TXT_PATH_KEY.to_string(), DISCOVERY_PATH.to_string());
    // `mdns-sd` accepts a single IP literal as a string (its own documented
    // example form) and parses it through `AsIpAddrs`.
    let ip_literal = addresses[0].to_string();
    ServiceInfo::new(SERVICE_TYPE, INSTANCE_NAME, &host_name, ip_literal, port, properties)
        .map_err(|e| format!("malformed service info: {e}"))
}

/// The single IPv4 address that is safe to advertise: the configured RFC1918
/// bind address, or — when the receiver bound the wildcard address — the
/// primary LAN IPv4 the host actually routes with.
fn advertised_addresses(bind_ip: Ipv4Addr) -> Result<Vec<Ipv4Addr>, String> {
    if !bind_ip.is_unspecified() {
        if !is_rfc1918(bind_ip) {
            return Err(format!(
                "refusing to advertise non-RFC1918 address {bind_ip}"
            ));
        }
        return Ok(vec![bind_ip]);
    }
    let primary = primary_local_ipv4()
        .ok_or("the receiver bound 0.0.0.0 and no primary RFC1918 LAN IPv4 could be determined")?;
    if !is_rfc1918(primary) {
        return Err(format!(
            "refusing to advertise non-RFC1918 address {primary}"
        ));
    }
    Ok(vec![primary])
}

/// Host label for the A record. Derived from the advertised IP (dots replaced by
/// dashes) so no machine name or user name is broadcast. `mdns-sd`'s own
/// documented example uses the same shape (`192.168.1.12.local.`).
fn advertised_host_name(ip: &Ipv4Addr) -> String {
    format!("{}.local.", ip.to_string().replace('.', "-"))
}

/// Asks the OS which local IPv4 address the default route uses, without sending
/// a single packet (a UDP `connect` only selects a source address). Returns
/// `None` when the host has no default route (e.g. an isolated LAN), in which
/// case automatic discovery is skipped and the manual IP/port path still works.
fn primary_local_ipv4() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    // TEST-NET-1 (RFC 5737): reserved for documentation, never routed.
    socket.connect((Ipv4Addr::new(192, 0, 2, 1), 9)).ok()?;
    match socket.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(ip) if !ip.is_unspecified() && !ip.is_loopback() => Some(ip),
        _ => None,
    }
}

fn is_rfc1918(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    match o[0] {
        10 => true,
        172 => (16..=31).contains(&o[1]),
        192 => o[1] == 168,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mdns_sd::Error as MdnsError;
    use std::collections::HashSet;

    fn lan_ip() -> Ipv4Addr {
        Ipv4Addr::new(192, 168, 12, 144)
    }

    fn our_fullname() -> String {
        format!("{INSTANCE_NAME}.{SERVICE_TYPE}")
    }

    // Existing frozen contract

    #[test]
    fn advertised_service_info_has_frozen_type_name_and_port() {
        let info = build_service_info(lan_ip(), 18099).expect("service info");
        assert_eq!(info.get_type(), SERVICE_TYPE);
        assert_eq!(info.get_port(), 18099);
        assert!(info.get_fullname().starts_with(INSTANCE_NAME));
        assert!(info.get_fullname().ends_with(SERVICE_TYPE));
        assert_eq!(
            info.get_addresses(),
            &HashSet::from([std::net::IpAddr::V4(lan_ip())])
        );
        // Hostnames, IPv6, and the machine name are never advertised.
        assert_eq!(info.get_hostname(), "192-168-12-144.local.");
    }

    #[test]
    fn txt_records_carry_only_the_frozen_non_sensitive_metadata() {
        let info = build_service_info(lan_ip(), 18099).expect("service info");
        let keys: HashSet<String> = info
            .get_properties()
            .iter()
            .map(|p| p.key().to_string())
            .collect();
        assert_eq!(
            keys,
            HashSet::from([TXT_PROTOCOL_KEY.to_string(), TXT_PATH_KEY.to_string()]),
            "TXT must carry exactly the two frozen non-sensitive keys"
        );
        assert_eq!(
            info.get_property_val_str(TXT_PROTOCOL_KEY),
            Some(PROTOCOL_VERSION)
        );
        assert_eq!(info.get_property_val_str(TXT_PATH_KEY), Some(DISCOVERY_PATH));
    }

    #[test]
    fn advertised_record_contains_no_token_or_path_like_secret() {
        let token = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        let info = build_service_info(lan_ip(), 18099).expect("service info");
        let mut advertised = String::new();
        for property in info.get_properties().iter() {
            advertised.push_str(property.key());
            advertised.push('=');
            advertised.push_str(property.val_str());
            advertised.push('\n');
        }
        advertised.push_str(info.get_fullname());
        advertised.push('\n');
        advertised.push_str(info.get_hostname());
        let lowered = advertised.to_lowercase();
        assert!(!lowered.contains(token));
        assert!(!lowered.contains(&token[..16]));
        for property in info.get_properties().iter() {
            let value = property.val_str();
            assert!(value == PROTOCOL_VERSION || value == DISCOVERY_PATH);
        }
    }

    #[test]
    fn wildcard_bind_uses_the_primary_lan_address() {
        let addresses = advertised_addresses(Ipv4Addr::UNSPECIFIED);
        if let Ok(list) = addresses {
            assert_eq!(list.len(), 1);
            assert!(is_rfc1918(list[0]));
            assert!(!list[0].is_unspecified() && !list[0].is_loopback());
        }
    }

    #[test]
    fn refuses_to_advertise_non_rfc1918_addresses() {
        assert!(advertised_addresses(Ipv4Addr::new(8, 8, 8, 8)).is_err());
        assert!(advertised_addresses(Ipv4Addr::new(127, 0, 0, 1)).is_err());
        assert!(advertised_addresses(Ipv4Addr::new(169, 254, 1, 1)).is_err());
        assert!(advertised_addresses(Ipv4Addr::new(100, 64, 0, 1)).is_err());
        for ok in [
            Ipv4Addr::new(10, 0, 0, 5),
            Ipv4Addr::new(172, 16, 0, 5),
            Ipv4Addr::new(172, 31, 255, 254),
            Ipv4Addr::new(192, 168, 1, 5),
        ] {
            assert_eq!(advertised_addresses(ok).unwrap(), vec![ok]);
        }
    }

    // Repair 3: truthful classification

    #[test]
    fn queued_is_not_announced() {
        // Queue acceptance is a distinct line from a real announcement, and it
        // must never read as an advertisement.
        assert!(LOG_QUEUED.contains("queued"));
        assert!(LOG_QUEUED.contains("not yet announced"));
        assert!(!LOG_QUEUED.contains("announcement sent"));
        assert!(!LOG_QUEUED.contains("advertised"));
        assert_ne!(LOG_QUEUED, LOG_ANNOUNCED);
        // The shared flag is process-wide state; a fresh test binary has not
        // announced anything yet unless a live test already ran.
        let _ = ANNOUNCED.load(Ordering::Relaxed);
    }

    #[test]
    fn only_our_own_announcement_counts() {
        let registered = our_fullname();
        assert_eq!(
            classify_event(
                &DaemonEvent::Announce(registered.clone(), "detail".to_string()),
                &registered
            ),
            EventClass::OurAnnouncement
        );
        // Case-insensitive, as DNS names are.
        assert_eq!(
            classify_event(
                &DaemonEvent::Announce(registered.to_uppercase(), "d".to_string()),
                &registered
            ),
            EventClass::OurAnnouncement
        );
        // Another instance, another host, or any other event proves nothing.
        for event in [
            DaemonEvent::Announce("other._x._tcp.local.".to_string(), "d".to_string()),
            DaemonEvent::Respond("wlan0".to_string()),
            DaemonEvent::IpAdd("192.168.12.142".parse().unwrap()),
        ] {
            assert_eq!(classify_event(&event, &registered), EventClass::Irrelevant);
        }
    }

    #[test]
    fn a_daemon_error_is_its_own_class() {
        assert_eq!(
            classify_event(
                &DaemonEvent::Error(MdnsError::Msg("multicast not permitted".to_string())),
                &our_fullname()
            ),
            EventClass::DaemonError
        );
        assert_eq!(error_category(&MdnsError::DaemonShutdown), "daemon shut down");
        assert_eq!(error_category(&MdnsError::Again), "queue full");
        assert_eq!(error_category(&MdnsError::Msg("x".into())), "message");
        assert_eq!(
            error_category(&MdnsError::ParseIpAddr("y".into())),
            "address parse"
        );
    }

    #[test]
    fn the_not_yet_warning_fires_once_and_only_before_an_announcement() {
        let budget = Duration::from_millis(FIRST_ANNOUNCE_WARN_MS);
        // Before the window: nothing to say.
        assert!(!should_warn_not_yet(false, false, false, Duration::from_millis(100), budget));
        // Window elapsed, nothing observed at all: warn once.
        assert!(should_warn_not_yet(false, false, false, budget, budget));
        // Never twice.
        assert!(!should_warn_not_yet(false, false, true, budget, budget));
        // Never after a real announcement.
        assert!(!should_warn_not_yet(true, false, false, budget, budget));
        // Never when the daemon itself already reported an error: the timeout
        // line must not imply that no error was seen.
        assert!(!should_warn_not_yet(false, true, false, budget, budget));
    }

    #[test]
    fn the_timeout_warning_states_only_what_was_observed() {
        // It must not infer multicast/firewall/manual-path conclusions.
        assert!(LOG_NOT_YET.contains("no Announce for this service was observed"));
        assert!(!LOG_NOT_YET.to_lowercase().contains("multicast"));
        assert!(!LOG_NOT_YET.to_lowercase().contains("firewall"));
        assert!(!LOG_NOT_YET.to_lowercase().contains("manual"));
        assert!(!LOG_NOT_YET.contains("no daemon error"));
    }

    #[test]
    fn a_daemon_error_logs_only_a_safe_category() {
        // The category constant is compile-time text, never the library's string.
        assert!(LOG_DAEMON_ERROR.contains("daemon error"));
        assert!(DAEMON_ERROR_CATEGORY.contains("category only"));
        assert!(!DAEMON_ERROR_CATEGORY.contains("multicast not permitted"));
        // error_category maps every library variant to a fixed phrase.
        assert_eq!(error_category(&MdnsError::Msg("secret path C:\\x".into())), "message");
    }

    #[test]
    fn log_lines_stay_classified_and_non_sensitive() {
        // PM requirement: distinct, self-describing categories; no raw detail.
        assert!(LOG_QUEUED.contains("queued") && !LOG_QUEUED.contains("announcement sent"));
        assert!(LOG_ANNOUNCED.contains("announcement sent"));
        assert!(LOG_NOT_YET.contains("no Announce for this service was observed"));
        assert!(LOG_DAEMON_ERROR.contains("daemon error"));
        assert!(DAEMON_ERROR_CATEGORY.contains("category only"));
        assert!(LOG_UNAVAILABLE.contains("unavailable"));
        assert!(LOG_MONITOR_STOPPED.contains("stopped"));
        assert!(LOG_WORKER_SPAWN_FAILED.contains("could not start"));

        // The frozen markers survive for the release-binary scan.
        assert_eq!(SERVICE_TYPE, "_sayit-watch._tcp.local.");
        assert_eq!(INSTANCE_NAME, "SayIt");
    }

    #[test]
    fn the_worker_never_echoes_raw_event_detail() {
        // Source-level guard: the classification must be what gets logged, never
        // the library's own Announce detail or Error text.
        let source = include_str!("mdns.rs");
        let production = source.split("#[cfg(test)]").next().unwrap();
        assert!(
            !production.contains("Announce(fullname, detail)") || !production.contains("log::info!(\"{}\", detail"),
            "raw announce detail must not be logged"
        );
        assert!(
            !production.contains("Failed(e.to_string())"),
            "raw daemon error text must not be logged"
        );
        assert!(
            !production.contains("log::error!(\"{}\", error)"),
            "raw daemon error text must not be logged"
        );
        // No sensitive source is read at all.
        for forbidden in [".dev_token", "hostname::", "whoami"] {
            assert!(!production.contains(forbidden), "mdns.rs must not read {forbidden}");
        }
    }

    // Repair 3: reproduce with the current library and live interfaces

    /// The same shape as the isolated probe (monitor BEFORE register, bounded
    /// wait, live interfaces) but through THIS module's own classification, so a
    /// real announcement is observed by the repaired code path.
    #[test]
    fn live_daemon_announces_or_reports_a_daemon_error() {
        let info = build_service_info(lan_ip(), 18099).expect("service info");
        let fullname = info.get_fullname().to_string();
        let daemon = ServiceDaemon::new().expect("daemon");
        let events = daemon.monitor().expect("monitor");

        daemon.register(info).expect("register queues the command");

        let deadline = std::time::Instant::now() + Duration::from_millis(FIRST_ANNOUNCE_WARN_MS);
        let mut announced = false;
        let mut daemon_error = false;
        while std::time::Instant::now() < deadline {
            match events.recv_timeout(Duration::from_millis(EVENT_POLL_MS)) {
                Ok(event) => match classify_event(&event, &fullname) {
                    EventClass::OurAnnouncement => {
                        announced = true;
                        break;
                    }
                    EventClass::DaemonError => {
                        daemon_error = true;
                        break;
                    }
                    _ => {}
                },
                Err(RecvTimeoutError::Timeout) => continue,
                Err(RecvTimeoutError::Disconnected) => break,
            }
        }

        // Truthfulness: the two outcomes are mutually exclusive, and the monitor
        // must be able to observe at least one of them on a live interface.
        assert!(
            announced || daemon_error,
            "no daemon outcome inside the bounded wait: the monitor observed neither \
             an announcement nor an error, so the instrumentation cannot be trusted"
        );
        assert!(!(announced && daemon_error));

        let _ = daemon.unregister(&fullname);
        let _ = daemon.shutdown();
    }
}
