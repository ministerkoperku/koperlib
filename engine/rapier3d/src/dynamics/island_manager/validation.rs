use crate::dynamics::{IslandManager, RigidBodySet};
use crate::geometry::NarrowPhase;
use crate::prelude::ColliderSet;

impl IslandManager {
    #[allow(dead_code)]
    pub(super) fn assert_state_is_valid(
        &self,
        bodies: &RigidBodySet,
        colliders: &ColliderSet,
        nf: &NarrowPhase,
    ) {
        for (island_id, island) in self.islands.iter() {
            // Sleeping island must not be in the awake list.
            if island.is_sleeping() {
                assert!(!self.awake_islands.contains(&island_id));
            } else {
                // If the island is awake, the awake id must match.
                let awake_id = island.id_in_awake_list.unwrap();
                assert_eq!(self.awake_islands[awake_id], island_id);
            }

            for (body_id, handle) in island.bodies.iter().enumerate() {
                if let Some(rb) = bodies.get(*handle) {
                    // The body’s sleeping status must match the island’s status.
                    assert_eq!(rb.is_sleeping(), island.is_sleeping());
                    // The body’s island id must match the island id.
                    assert_eq!(rb.ids.active_island_id, island_id);
                    // The body’s active set id must match its handle’s position in island.bodies.
                    assert_eq!(body_id, rb.ids.active_set_id);
                }
            }
        }

        // Free island ids must actually be free.
        for id in self.free_islands.iter() {
            assert!(self.islands.get(*id).is_none());
        }

        // The awake islands list must not have duplicates.
        let mut awake_islands_dedup = self.awake_islands.clone();
        awake_islands_dedup.sort();
        awake_islands_dedup.dedup();
        assert_eq!(self.awake_islands.len(), awake_islands_dedup.len());

        // If two bodies have solver contacts, they must be in the same island.
        for pair in nf.contact_pairs() {
            let Some(body_handle1) = colliders[pair.collider1].parent.map(|p| p.handle) else {
                continue;
            };
            let Some(body_handle2) = colliders[pair.collider2].parent.map(|p| p.handle) else {
                continue;
            };

            let body1 = &bodies[body_handle1];
            let body2 = &bodies[body_handle2];

            if body1.is_fixed() || body2.is_fixed() {
                continue;
            }

            if pair.has_any_active_contact() {
                assert_eq!(body1.ids.active_island_id, body2.ids.active_island_id);
            }
        }

        log::info!(
            "`IslandManager::assert_state_is_valid` validation checks passed. This is slow. Only enable for debugging."
        );
    }
}

use alloc::format;
use alloc::string::String;
use alloc::vec::Vec;

impl IslandManager {
    /// Non-panicking version of [`Self::assert_state_is_valid`]: returns one line per broken
    /// invariant instead of aborting. Rapier keeps island bookkeeping (which island a body is in,
    /// which islands are awake) in several parallel arrays; if they drift apart the solver silently
    /// simulates the wrong set of bodies. An empty result means the bookkeeping is consistent.
    pub fn island_state_problems(&self, bodies: &RigidBodySet) -> Vec<String> {
        let mut out = Vec::new();
        for (island_id, island) in self.islands.iter() {
            if island.is_sleeping() {
                if self.awake_islands.contains(&island_id) {
                    out.push(format!("island {island_id} is sleeping but sits in the awake list"));
                }
            } else {
                match island.id_in_awake_list() {
                    None => out.push(format!("island {island_id} is awake with no awake-list slot")),
                    Some(awake_id) => match self.awake_islands.get(awake_id) {
                        None => out.push(format!(
                            "island {island_id} points at awake slot {awake_id}, past the end of a \
                             {}-entry awake list", self.awake_islands.len())),
                        Some(found) if *found != island_id => out.push(format!(
                            "island {island_id} points at awake slot {awake_id}, which holds island \
                             {found}")),
                        _ => {}
                    },
                }
            }

            for (body_id, handle) in island.bodies.iter().enumerate() {
                let Some(rb) = bodies.get(*handle) else { continue };
                if rb.is_sleeping() != island.is_sleeping() {
                    out.push(format!(
                        "body {:?} sleeping={} but its island {island_id} sleeping={}",
                        handle, rb.is_sleeping(), island.is_sleeping()));
                }
                if rb.ids.active_island_id != island_id {
                    out.push(format!(
                        "body {:?} sits in island {island_id} but claims island {}",
                        handle, rb.ids.active_island_id));
                }
                if rb.ids.active_set_id != body_id {
                    out.push(format!(
                        "body {:?} is at slot {body_id} of island {island_id} but claims slot {}",
                        handle, rb.ids.active_set_id));
                }
            }
        }

        // The loop above walks island -> body, so it can only ever check bodies that ARE in some
        // island's list. A body that fell out of every list while still carrying the island id and
        // slot it used to hold is invisible to it — and that is precisely the body that indexes past
        // the end of body_masks in the contact grouping, because that array is sized to the island
        // the body claims. Walk body -> island too.
        for (handle, rb) in bodies.iter() {
            if !rb.is_dynamic() || rb.is_sleeping() {
                continue;
            }
            let (iid, sid) = (rb.ids.active_island_id, rb.ids.active_set_id);
            match self.islands.get(iid) {
                None => out.push(format!(
                    "awake body {handle:?} claims island {iid}, which does not exist")),
                Some(island) => match island.bodies.get(sid) {
                    None => out.push(format!(
                        "awake body {handle:?} claims slot {sid} of island {iid}, which only holds {} bodies",
                        island.bodies.len())),
                    Some(found) if *found != handle => out.push(format!(
                        "awake body {handle:?} claims slot {sid} of island {iid}, but that slot holds {found:?}")),
                    _ => {}
                },
            }
        }

        for id in self.free_islands.iter() {
            if self.islands.get(*id).is_some() {
                out.push(format!("island slot {id} is on the free list but is still occupied"));
            }
        }

        let mut seen = self.awake_islands.clone();
        seen.sort_unstable();
        let before = seen.len();
        seen.dedup();
        if seen.len() != before {
            out.push(format!(
                "awake list has {} duplicate entries: {:?}", before - seen.len(), self.awake_islands));
        }
        out
    }
}
