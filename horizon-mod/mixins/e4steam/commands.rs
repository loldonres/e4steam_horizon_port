// HEAD of `native_commands`: the chat box colours, completes and lists `/steam` like the game's own
// commands (API 35).
fn steam_commands(names: &mut Vec<&'static str>) {
    crate::game::e4steam::on_commands(names);
}
