// HEAD of `native_settings_click`: which button of our page the player pressed. Horizon redraws the
// labels right after this, so a press only has to act.
fn steam_settings_click(
    game: &mut crate::game::Game<'_>,
    mod_id: &str,
    button: usize,
) {
    crate::game::e4steam::on_press(game, mod_id, button);
}
