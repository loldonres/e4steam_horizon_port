// HEAD of `native_mods`: Options > Mods asks the native mods for their rows, and ours says what the
// Steam thread is doing right now.
fn steam_mods(mods: &mut Vec<crate::game::native_hooks::NativeMod>) {
    crate::game::e4steam::on_mods(mods);
}
