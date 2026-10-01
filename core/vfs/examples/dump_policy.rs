//! Regenerates `docs/vfs-policy.txt` (the CI golden file).
//!
//! Usage: `cargo run --example dump_policy -- <guest> <host> <host_data_dir>`

use aether_vfs::{Vfs, VfsConfig};

fn main() {
    let mut args = std::env::args().skip(1);
    let guest = args.next().unwrap_or_else(|| "com.target.game".into());
    let host = args.next().unwrap_or_else(|| "dev.aether.host".into());
    let dir = args
        .next()
        .unwrap_or_else(|| "/data/user/0/dev.aether.host".into());

    let vfs = Vfs::new(VfsConfig::new(&guest, &host, &dir));
    println!("# aether-vfs policy dump");
    println!("# guest      : {guest}");
    println!("# host       : {host}");
    println!("# host dir   : {dir}");
    println!("# shadow root: {}", vfs.shadow_root());
    println!("# rules      : {}", vfs.rules().len());
    println!();
    println!("{:<50} {:<22} {}", "TEMPLATE", "ACTION", "NOTE");
    for line in vfs.describe() {
        println!("{line}");
    }
}
