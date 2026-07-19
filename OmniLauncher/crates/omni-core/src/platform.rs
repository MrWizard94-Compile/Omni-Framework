//! Platform identity and client.json rule evaluation — shared by the install
//! planner (library filtering) and the launch chain (conditional arguments).

use std::collections::BTreeMap;

use omni_meta_format::{Rule, RuleAction};

/// The platform rules are evaluated against (client.json vocabulary).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Platform {
    /// `windows` / `linux` / `osx`.
    pub os_name: &'static str,
    /// `x86` / `x86_64` / `arm64` (the values upstream rules use).
    pub arch: &'static str,
}

impl Platform {
    /// The platform this binary is running on.
    pub fn current() -> Self {
        let os_name = if cfg!(target_os = "windows") {
            "windows"
        } else if cfg!(target_os = "macos") {
            "osx"
        } else {
            "linux"
        };
        let arch = if cfg!(target_arch = "x86") {
            "x86"
        } else if cfg!(target_arch = "aarch64") {
            "arm64"
        } else {
            "x86_64"
        };
        Self { os_name, arch }
    }
}

/// Evaluates a client.json rule chain: with no rules everything applies; with
/// rules the default is deny and the **last matching** rule's action wins
/// (upstream semantics, preserved by the OmniMeta normalizer).
pub fn rules_allow(
    rules: &[Rule],
    platform: &Platform,
    features: &BTreeMap<String, bool>,
) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        let os_matches = rule.os.as_ref().is_none_or(|os| {
            os.name.as_deref().is_none_or(|name| name == platform.os_name)
                && os.arch.as_deref().is_none_or(|arch| arch == platform.arch)
            // os.version regexes are not evaluated (matches the corpus
            // launchers: the rule is treated as os-name/arch scoped).
        });
        let features_match = rule.features.as_ref().is_none_or(|wanted| {
            wanted
                .iter()
                .all(|(name, value)| features.get(name).copied().unwrap_or(false) == *value)
        });
        if os_matches && features_match {
            allowed = rule.action == RuleAction::Allow;
        }
    }
    allowed
}

#[cfg(test)]
mod tests {
    use super::*;
    use omni_meta_format::OsSpec;

    fn windows() -> Platform {
        Platform { os_name: "windows", arch: "x86_64" }
    }

    fn allow_os(name: &str) -> Rule {
        Rule {
            action: RuleAction::Allow,
            os: Some(OsSpec { name: Some(name.into()), version: None, arch: None }),
            features: None,
        }
    }

    #[test]
    fn empty_rules_allow_everything() {
        assert!(rules_allow(&[], &windows(), &BTreeMap::new()));
    }

    #[test]
    fn os_scoping_and_last_match_wins() {
        let features = BTreeMap::new();
        assert!(rules_allow(&[allow_os("windows")], &windows(), &features));
        assert!(!rules_allow(&[allow_os("linux")], &windows(), &features));
        // allow-all then disallow-windows: last match wins -> denied.
        let allow_all = Rule { action: RuleAction::Allow, os: None, features: None };
        let deny_windows = Rule {
            action: RuleAction::Disallow,
            os: Some(OsSpec { name: Some("windows".into()), version: None, arch: None }),
            features: None,
        };
        assert!(!rules_allow(&[allow_all.clone(), deny_windows], &windows(), &features));
        // osx-only disallow does not fire on windows.
        let deny_osx = Rule {
            action: RuleAction::Disallow,
            os: Some(OsSpec { name: Some("osx".into()), version: None, arch: None }),
            features: None,
        };
        assert!(rules_allow(&[allow_all, deny_osx], &windows(), &features));
    }

    #[test]
    fn feature_rules_respect_the_feature_map() {
        let demo_rule = Rule {
            action: RuleAction::Allow,
            os: None,
            features: Some([("is_demo_user".to_owned(), true)].into()),
        };
        let mut features = BTreeMap::new();
        assert!(!rules_allow(std::slice::from_ref(&demo_rule), &windows(), &features));
        features.insert("is_demo_user".to_owned(), true);
        assert!(rules_allow(&[demo_rule], &windows(), &features));
    }

    #[test]
    fn arch_scoped_rules_match_arch() {
        let x86_only = Rule {
            action: RuleAction::Allow,
            os: Some(OsSpec { name: None, version: None, arch: Some("x86".into()) }),
            features: None,
        };
        assert!(!rules_allow(std::slice::from_ref(&x86_only), &windows(), &BTreeMap::new()));
        let x86_platform = Platform { os_name: "windows", arch: "x86" };
        assert!(rules_allow(&[x86_only], &x86_platform, &BTreeMap::new()));
    }
}
