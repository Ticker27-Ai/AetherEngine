//! The isolation policy: an ordered, longest-match-wins rule table.

use crate::path;

/// What the engine does with a path once a rule matches.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    /// Rewrite into the host's private shadow tree. Fully isolated, read-write.
    Shadow,
    /// Hand the path to the kernel untouched. Read-write.
    Passthrough,
    /// Hand the path to the kernel, reject write/create/delete/unlink.
    ReadOnlyPassthrough,
    /// Fail the operation.
    Deny,
}

impl Action {
    /// Bucket placeholder for actions that do not use the shadow tree.
    pub const SHADOW_BUCKET_NONE: &'static str = "-";

    /// Decode the C ABI action code.
    pub fn from_code(code: i32) -> Option<Action> {
        match code {
            0 => Some(Action::Shadow),
            1 => Some(Action::Passthrough),
            2 => Some(Action::ReadOnlyPassthrough),
            3 => Some(Action::Deny),
            _ => None,
        }
    }

    /// Encode as the C ABI action code.
    pub fn code(self) -> i32 {
        match self {
            Action::Shadow => 0,
            Action::Passthrough => 1,
            Action::ReadOnlyPassthrough => 2,
            Action::Deny => 3,
        }
    }
}

/// Why an operation was refused. Surfaced to Kotlin as `VfsException`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DenyReason {
    /// No rule matched and the policy is fail-closed.
    NoRule,
    /// A `Deny` rule matched explicitly (host data, another guest, ...).
    ExplicitRule,
    /// Write attempt through a read-only passthrough.
    WriteToReadOnly,
    /// Not absolute, or unresolvable after normalisation.
    Malformed,
    /// Symlink loop / too many hops.
    LinkLoop,
}

impl DenyReason {
    /// Stable, machine-readable name used in logs and diagnostics.
    pub fn as_str(self) -> &'static str {
        match self {
            DenyReason::NoRule => "no-rule",
            DenyReason::ExplicitRule => "explicit-deny",
            DenyReason::WriteToReadOnly => "read-only",
            DenyReason::Malformed => "malformed",
            DenyReason::LinkLoop => "link-loop",
        }
    }
}

/// The operation the guest is attempting. Used to enforce read-only rules.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    /// Open for reading.
    Read,
    /// Open for writing.
    Write,
    /// `O_CREAT` / `mkdir` — treated as a write that also needs the parent.
    Create,
    /// `unlink` / `rmdir`.
    Delete,
    /// `stat`/`access`: never blocked by read-only rules.
    Stat,
}

impl Mode {
    /// True for operations that mutate the filesystem.
    pub fn is_write(self) -> bool {
        matches!(self, Mode::Write | Mode::Create | Mode::Delete)
    }
}

/// A single rule.
///
/// `template` is an absolute path that may contain `*` wildcards. Each `*`
/// matches **exactly one** path segment (it never spans `/`), which keeps
/// matching linear and side-effect free.
///
/// `bucket` names the sub-directory of the shadow root used when
/// `action == Action::Shadow` (see [`crate::translator::ShadowLayout`]).
#[derive(Debug, Clone)]
pub struct Rule {
    /// Absolute match template; each `*` matches exactly one segment.
    pub template: String,
    /// What to do when the template matches.
    pub action: Action,
    /// Shadow sub-directory used when `action` is [`Action::Shadow`].
    pub bucket: String,
    /// Human-readable origin of the rule, surfaced by `RuleSet` dumps.
    pub note: &'static str,
}

impl Rule {
    /// Build a rule.
    pub fn new(
        template: impl Into<String>,
        action: Action,
        bucket: &str,
        note: &'static str,
    ) -> Self {
        Rule {
            template: template.into(),
            action,
            bucket: bucket.to_string(),
            note,
        }
    }

    /// Number of wildcards — used as a specificity tie-break.
    pub fn wildcards(&self) -> usize {
        self.template.matches('*').count()
    }

    /// Length of `path` consumed by this rule's prefix, or `None`.
    ///
    /// A match must end at the end of the path or on a `/` boundary, so
    /// `/data/data/pkg` never matches `/data/data/pkg2`.
    pub fn match_len(&self, path: &str) -> Option<usize> {
        let parts: Vec<&str> = self.template.split('*').collect();
        if !path.starts_with(parts[0]) {
            return None;
        }
        let mut pos = parts[0].len();

        for next in &parts[1..] {
            if next.is_empty() {
                // Trailing `*`: consume exactly one segment, like every other
                // wildcard. (Swallowing the remainder would let shallow rules
                // beat deep ones, which is the opposite of what we want.)
                let rest = &path[pos..];
                let end = rest.find('/').unwrap_or(rest.len());
                if end == 0 {
                    return None;
                }
                pos += end;
                continue;
            }
            let rest = &path[pos..];
            let idx = match rest.find(next) {
                Some(i) => i,
                None => return None,
            };
            let gap = &rest[..idx];
            if gap.is_empty() || gap.contains('/') {
                return None;
            }
            pos += idx + next.len();
        }

        let bytes = path.as_bytes();
        if pos == bytes.len() || bytes.get(pos) == Some(&b'/') {
            Some(pos)
        } else {
            None
        }
    }
}

/// An ordered rule table. Lookup is **longest-match-wins**, with the number of
/// wildcards as a specificity tie-break and declaration order as the last
/// resort — so a concrete `/data/data/<pkg>` always beats `/data/data/*`.
#[derive(Debug, Clone, Default)]
pub struct RuleSet {
    rules: Vec<Rule>,
}

impl RuleSet {
    /// An empty rule table.
    pub fn new() -> Self {
        RuleSet { rules: Vec::new() }
    }

    /// Append a rule. Later rules lose ties to earlier ones.
    pub fn push(&mut self, rule: Rule) -> &mut Self {
        self.rules.push(rule);
        self
    }

    /// Insert with the highest priority (specialised guards evaluated first).
    pub fn push_front(&mut self, rule: Rule) -> &mut Self {
        self.rules.insert(0, rule);
        self
    }

    /// Number of registered rules.
    pub fn len(&self) -> usize {
        self.rules.len()
    }

    /// True when no rules are registered.
    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }

    /// Iterate in declaration order.
    pub fn iter(&self) -> std::slice::Iter<'_, Rule> {
        self.rules.iter()
    }

    /// Pick the best rule for `path`, plus the length it consumed.
    pub fn best_match<'a>(&'a self, path: &str) -> Option<(&'a Rule, usize)> {
        let mut best: Option<(&Rule, usize, usize, usize)> = None; // rule, len, wildcards, index
        for (i, rule) in self.rules.iter().enumerate() {
            if let Some(len) = rule.match_len(path) {
                let cand = (len, usize::MAX - rule.wildcards(), usize::MAX - i);
                let best_key = best.map(|(_, l, w, idx)| (l, usize::MAX - w, usize::MAX - idx));
                if best_key.map_or(true, |bk| cand > bk) {
                    best = Some((rule, len, rule.wildcards(), i));
                }
            }
        }
        best.map(|(r, l, _, _)| (r, l))
    }
}

/// Convenience: is `template` a valid rule template?
pub fn validate_template(template: &str) -> Result<(), String> {
    if !path::is_absolute(template) {
        return Err("template must be absolute".into());
    }
    let normalized = path::normalize(template);
    if normalized != template {
        return Err(format!(
            "template is not normalized: {template} -> {normalized}"
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn literal_templates_respect_segment_boundaries() {
        let r = Rule::new("/data/data/p", Action::Shadow, "data", "");
        assert_eq!(r.match_len("/data/data/p"), Some(12));
        assert_eq!(r.match_len("/data/data/p/x"), Some(12));
        assert_eq!(r.match_len("/data/data/p2/x"), None);
        assert_eq!(r.match_len("/data/data"), None);
    }

    #[test]
    fn wildcard_matches_exactly_one_segment() {
        let r = Rule::new("/data/user/*/p", Action::Shadow, "data", "");
        assert_eq!(r.match_len("/data/user/0/p/x"), Some(14));
        assert_eq!(r.match_len("/data/user/10/p"), Some(15));
        assert_eq!(r.match_len("/data/user/0/other"), None);
        // `*` never spans a separator
        assert_eq!(r.match_len("/data/user/0/1/p"), None);
    }

    #[test]
    fn trailing_wildcard_matches_one_segment_but_claims_the_subtree() {
        let r = Rule::new("/data/data/*", Action::Deny, "-", "");
        assert_eq!(r.match_len("/data/data/com.x"), Some(16));
        // A template that ends in `*` still *claims* everything below the
        // segment it matched — the returned length is just the prefix.
        assert_eq!(r.match_len("/data/data/com.x/y"), Some(16));
        assert_eq!(&"/data/data/com.x/y"[..16], "/data/data/com.x");
    }

    #[test]
    fn longest_match_beats_declaration_order() {
        let mut rs = RuleSet::new();
        rs.push(Rule::new("/sdcard/", Action::Passthrough, "-", "generic"));
        rs.push(Rule::new(
            "/sdcard/Android/data/p",
            Action::Shadow,
            "shared_data",
            "scoped",
        ));
        let (rule, len) = rs.best_match("/sdcard/Android/data/p/x").unwrap();
        assert_eq!(rule.action, Action::Shadow);
        assert_eq!(len, 22);
    }

    #[test]
    fn concrete_beats_equally_long_wildcard() {
        let mut rs = RuleSet::new();
        rs.push(Rule::new(
            "/data/data/com.example",
            Action::Shadow,
            "data",
            "self",
        ));
        rs.push(Rule::new("/data/data/*", Action::Deny, "-", "others"));
        let (rule, _) = rs.best_match("/data/data/com.example").unwrap();
        assert_eq!(rule.action, Action::Shadow);
        let (rule, _) = rs.best_match("/data/data/com.other").unwrap();
        assert_eq!(rule.action, Action::Deny);
    }
}
