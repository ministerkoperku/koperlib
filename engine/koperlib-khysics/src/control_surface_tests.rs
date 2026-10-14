use super::*;

struct Flight {
    lift: f32,
    roll: f32,
    angle: f32,
    anchor_error: f32,
}

// Same asymmetric split as a wool wing with its outer half on an X-axis bearing.
// Settle the bearing first so its motor's reaction is not mistaken for aerodynamic control.
fn fly(mode: AeroMode, degrees: f32, air: bool) -> Flight {
    let mut world = KhysWorld::new();
    let mut hull = Vec::new();
    let mut masses = Vec::new();
    for x in -1..=1 {
        for z in -2..=2 {
            hull.push([x as f32, 0.0, z as f32]);
            masses.push(1.0);
        }
    }
    for x in -5..=-3 {
        for z in -1..=1 {
            hull.push([x as f32, 0.0, z as f32]);
            masses.push(0.3);
        }
    }
    world.spawn_kontraktion_from_offsets(1, hull, &masses, 0, [0.0, 100.0, 0.0]);
    let mut flap = Vec::new();
    for x in -1..=1 {
        for z in -1..=1 {
            flap.push([x as f32, 0.0, z as f32]);
        }
    }
    world.spawn_kontraktion_from_offsets(2, flap, &[0.3; 9], 0, [4.0, 100.0, 0.0]);
    for id in [1, 2] {
        world.process_cmd(PhysicsCmd::SetAeroMode { id, mode });
        world.process_cmd(PhysicsCmd::SetGravityScale { id, scale: 0.0 });
        let k = world.kontras.get_mut(&id).unwrap();
        for off in &k.block_offsets {
            if id == 1 && off[0] > -3.0 { continue; }
            k.aero.push(AeroSurface {
                off: Vec3::from(*off), normal: Vec3::Y, area: 1.0,
                cd: 0.8, cl: 2.0, propeller: 2.0, buoy: 0.0, chunky: true,
            });
        }
    }
    world.process_cmd(PhysicsCmd::SetParked { id: 1, parked: true });
    world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
        joint_id: 10, a: 1, b: 2,
        anchor_a: [4.0, 0.0, 0.0], anchor_b: [0.0; 3], axis: [1.0, 0.0, 0.0],
    });
    let angle = degrees.to_radians();
    world.process_cmd(PhysicsCmd::JointSetMotorPosition {
        joint_id: 10, target_pos: angle, stiffness: 1000.0, damping: 100.0,
        max_force: 20000.0, force_based: true,
    });
    for _ in 0..180 { world.step(1.0 / 60.0); }
    let settled = world.build_snapshot().joints[&10];
    assert!((settled[0] - angle).abs() < 0.03,
        "{mode:?} bearing failed to hold {degrees} degrees: {settled:?}");
    world.process_cmd(PhysicsCmd::JointSetLimits { joint_id: 10, min: angle, max: angle });
    world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: 10, target_vel: 0.0, max_force: 0.0 });
    world.process_cmd(PhysicsCmd::SetParked { id: 1, parked: false });
    if !air { world.process_cmd(PhysicsCmd::SetAtmosphere { drag: 0.0 }); }
    for id in [1, 2] {
        world.process_cmd(PhysicsCmd::SetDamping { id, linear: 0.0, angular: 0.0 });
        world.process_cmd(PhysicsCmd::SetVelocity { id, v: [0.0, 0.0, 30.0] });
        world.process_cmd(PhysicsCmd::SetAngularVelocity { id, v: [0.0; 3] });
    }
    for _ in 0..30 { world.step(1.0 / 60.0); }
    let hull = world.bodies.get(world.kontras[&1].body).unwrap();
    let flap = world.bodies.get(world.kontras[&2].body).unwrap();
    let anchor = hull.translation() + hull.rotation() * Vec3::new(4.0, 0.0, 0.0);
    let error = (anchor - flap.translation()).length();
    assert!(hull.translation().is_finite() && hull.linvel().is_finite() && hull.angvel().is_finite());
    assert!(flap.translation().is_finite() && flap.linvel().is_finite() && flap.angvel().is_finite());
    Flight {
        lift: hull.linvel().y, roll: hull.angvel().z,
        angle: world.build_snapshot().joints[&10][0], anchor_error: error,
    }
}

#[test]
fn opposite_bearing_wing_angles_change_lift_and_roll_in_air() {
    for mode in [AeroMode::Correct, AeroMode::Extreme] {
        let down = fly(mode, -20.0, true);
        let neutral = fly(mode, 0.0, true);
        let up = fly(mode, 20.0, true);
        eprintln!("CONTROL {mode:?}: -20 lift={} roll={}, 0 lift={} roll={}, +20 lift={} roll={}",
            down.lift, down.roll, neutral.lift, neutral.roll, up.lift, up.roll);
        assert!(down.lift > neutral.lift + 0.05 && neutral.lift > up.lift + 0.05,
            "{mode:?}: opposite wing angles did not change vertical motion in opposite directions");
        assert!(down.roll > neutral.roll + 0.02 && neutral.roll > up.roll + 0.02,
            "{mode:?}: opposite wing angles did not roll the chassis in opposite directions");
        for (flight, degrees) in [(&down, -20.0f32), (&neutral, 0.0), (&up, 20.0)] {
            assert!((flight.angle - degrees.to_radians()).abs() < 0.05,
                "{mode:?}: wind displaced the bearing from {degrees} degrees: {}", flight.angle);
            assert!(flight.anchor_error < 0.01,
                "{mode:?}: wing detached by {} blocks", flight.anchor_error);
        }
    }
}

#[test]
fn settled_bearing_wing_angles_do_not_steer_in_vacuum() {
    for mode in [AeroMode::Correct, AeroMode::Extreme] {
        for angle in [-20.0, 0.0, 20.0] {
            let flight = fly(mode, angle, false);
            assert!(flight.lift.abs() < 0.001 && flight.roll.abs() < 0.001,
                "{mode:?}: {angle} degrees created motion without airflow: lift={}, roll={}",
                flight.lift, flight.roll);
        }
    }
}

#[test]
fn velocity_controller_holds_a_light_wing_against_airflow() {
    for mode in [AeroMode::Correct, AeroMode::Extreme] {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(1, vec![[0.0; 3]], &[100.0], 0, [0.0, 150.0, 0.0]);
        let mut offsets = vec![[0.0; 3]];
        for x in 1..=3 { for z in -1..=1 { offsets.push([x as f32, 0.0, z as f32]); } }
        let mut masses = vec![0.025; offsets.len()];
        masses[0] = 4.0;
        world.spawn_kontraktion_from_offsets(2, offsets, &masses, 0, [2.0, 150.0, 0.0]);
        world.set_parked(1, true);
        world.process_cmd(PhysicsCmd::SetGravityScale { id: 2, scale: 0.0 });
        let wing = world.kontras.get_mut(&2).unwrap();
        wing.aero_mode = mode;
        for off in &wing.block_offsets[1..] {
            wing.aero.push(AeroSurface {
                off: Vec3::from(*off), normal: Vec3::Y, area: 1.0,
                cd: 0.22, cl: 10.98, propeller: 8.0, buoy: 0.0, chunky: false,
            });
        }
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 10, a: 1, b: 2, anchor_a: [2.0, 0.0, 0.0],
            anchor_b: [0.0; 3], axis: [1.0, 0.0, 0.0],
        });
        let wanted = 8.0f32.to_radians();
        for step in 0..420 {
            if step == 120 { world.wind = Vec3::Z * 20.0; }
            if step % 3 == 0 {
                let angle = world.build_snapshot().joints[&10][0];
                let velocity = ((wanted - angle) * 8.0).clamp(-1.57, 1.57);
                world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: 10, target_vel: velocity, max_force: 200_000.0 });
            }
            world.step(1.0 / 60.0);
        }
        let state = world.build_snapshot().joints[&10];
        let rotation = world.bodies.get(world.kontras[&2].body).unwrap().rotation();
        eprintln!("WING {mode:?} angle={} normal={:?}", state[0], rotation * Vec3::Y);
        assert!((state[0] - wanted).abs() < 1.0f32.to_radians(),
            "{mode:?} loaded wing lost its commanded eight-degree incidence: {state:?}");
    }
}

#[test]
fn a_wing_rotating_with_its_parent_keeps_the_same_aerodynamic_wrench() {
    for mode in [AeroMode::Correct, AeroMode::Extreme] {
        for rate in [1.2, 6.0] {
            let mut wrenches = Vec::new();
            for mounted in [false, true] {
                let mut world = KhysWorld::new();
                world.spawn_kontraktion_from_offsets(1, vec![[0.0; 3]], &[100.0], 0, [0.0, 150.0, 0.0]);
                world.spawn_kontraktion_from_offsets(2, vec![[0.0; 3]], &[10.0], 0, [2.0, 150.0, 0.0]);
                let wing = world.kontras.get_mut(&2).unwrap();
                wing.aero_mode = mode;
                wing.aero.push(AeroSurface {
                    off: Vec3::Z, normal: Vec3::Y, area: 1.0,
                    cd: 0.8, cl: 2.0, propeller: 2.0, buoy: 0.0, chunky: true,
                });
                if mounted {
                    world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                        joint_id: 10, a: 1, b: 2, anchor_a: [2.0, 0.0, 0.0],
                        anchor_b: [0.0; 3], axis: [1.0, 0.0, 0.0],
                    });
                }
                for id in [1, 2] {
                    world.process_cmd(PhysicsCmd::SetVelocity { id, v: [0.0, 0.0, -20.0] });
                    world.process_cmd(PhysicsCmd::SetAngularVelocity { id, v: [rate, 0.0, 0.0] });
                }
                world.apply_aero(1.0 / 60.0);
                let body = world.bodies.get(world.kontras[&2].body).unwrap();
                wrenches.push((body.user_force(), body.user_torque()));
            }
            assert!((wrenches[0].0 - wrenches[1].0).length() < 1.0e-4
                && (wrenches[0].1 - wrenches[1].1).length() < 1.0e-4,
                "{mode:?} rigid assembly rotation at {rate} changed a mounted wing into a rotor: {wrenches:?}");
        }
    }
}
