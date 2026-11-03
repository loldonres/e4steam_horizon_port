// HEAD of `native_command`: the line goes to e4steam before the server and Horizon's own commands
// are asked, and `ran` is what keeps it from then running as an unknown vanilla command.
fn steam_command(game: &mut crate::game::Game<'_>, command: &str, ran: &mut bool) {
    crate::game::e4steam::on_command(game, command, ran);
}
