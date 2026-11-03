// HEAD of `native_frame`: read out what the Steam thread said. The hook runs in a world or not, so
// a line is shown while the player is still at the menu — where the join address is needed.
fn steam_frame(game: &mut crate::game::Game<'_>) {
    crate::game::e4steam::on_frame(game);
}
